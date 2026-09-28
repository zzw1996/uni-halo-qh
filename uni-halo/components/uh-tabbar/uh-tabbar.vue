<template>
	<!--
		自绘底部导航。

		为什么不是原生 tabBar：微信原生 `tabBar.list` 是编译期静态的（最多 5 项、运行期无法增删），
		而站长要在 7 个候选里配置显示 3~5 项，只能隐藏原生栏自己画。
		pages.json 里的 tabBar 声明保持不动，才能继续用 switchTab 保留页面状态。

		两种模式：
		- fixed（默认）：底栏悬浮在页面底部，并自带一个等高占位撑出空间
		  （页面只要挂本组件，不用自己写 padding-bottom）；
		- static：底栏占流内空间、不悬浮，给「微信小店」页用——官方要求 store-home
		  组件完整展示，底栏不能压在它上面。

	视觉是「液态玻璃」：悬浮胶囊 + 磨砂（backdrop-filter）+ 顶部高光边 +
	跟着选中项滑动的液态高亮块。三处刻意的取舍：
	- 玻璃底色用字面量（不是 $uh-* Token）：Token 是编译期常量，而皮肤是运行时的覆盖层，
	  玻璃需要「半透明的卡片色」这种 Token 表达不了的值，改由 skins.scss 针对深色皮肤单独覆盖；
	- 高亮块用「$uh-accent 实色 + opacity」而不是 rgba(accent)：前者是十六进制字面量，
	  皮肤生成器能识别并换成各皮肤的主题色，后者不在生成器的调色板里、换肤后会失真；
	- 图标仍是 PNG（选中态不会跟着皮肤变色，与改造前一致）。
	-->
	<view>
		<view v-if="mode !== 'static'" class="uh-tabbar_holder"></view>
		<view class="uh-tabbar" :class="mode === 'static' ? 'is-static' : 'is-fixed'">
			<view class="uh-tabbar_inner">
				<!-- 玻璃边缘的反光：一条顶亮边 + 一圈内描边，玻璃的「厚度」靠它表达 -->
				<view class="uh-tabbar_rim"></view>
				<!-- 液态高亮：跟着选中项滑动的那块药丸，切换时有一个短促的流动过程 -->
				<view class="uh-tabbar_glider" :style="gliderStyle"></view>
				<view v-for="item in items" :key="item.key" class="uh-tabbar_item"
					:class="{ 'is-active': item.key === current }" @click="fnOnTap(item)">
					<image class="uh-tabbar_icon" :src="item.key === current ? item.activeIcon : item.icon"
						mode="aspectFit"></image>
					<text class="uh-tabbar_label">{{ item.label }}</text>
				</view>
			</view>
			<!-- iPhone 底部安全区：单独一层，避免把 rpx 和 env() 混进同一个 calc -->
			<view class="uh-tabbar_safe"></view>
		</view>
	</view>
</template>

<script>
	import { getTabbarItems, hideNativeTabBar, requestTabbarView, setPendingTabbarMode } from '@/utils/tabbar.js';

	export default {
		name: 'uh-tabbar',
		props: {
			/** 当前项 key（home / category / moments / rank / gallery / store / about） */
			current: {
				type: String,
				default: ''
			},
			/** fixed=悬浮（默认）｜static=占流内空间（小店页用，避免遮挡 store-home） */
			mode: {
				type: String,
				default: 'fixed'
			}
		},
		computed: {
			haloConfigs() {
				return this.$tm.vx.getters().getConfigs;
			},
			/**
			 * 底栏项 = 站长在插件后台配的默认 + 用户在「我的 → 系统设置 → 底部导航」里的本地偏好。
			 *
			 * 走 vuex / 全局设置而不是直接读本地存储：后台改完配置、或用户刚在设置页改完偏好，
			 * 底栏都会立刻跟着变，不用重启小程序。开关关闭时返回空数组 = 不渲染自绘底栏，
			 * 页面照旧用微信原生底栏。去重、脏数据校验、3~5 项收敛都在 getTabbarItems 里。
			 */
			items() {
				return getTabbarItems(this.haloConfigs, this.globalAppSettings?.tabbar);
			},
			/**
			 * 液态高亮块的位置：宽度 = 一格，位移 = 选中项序号 × 一格。
			 *
			 * 用 transform 位移而不是给每个 item 画自己的选中背景：前者在切换时是「流动」的，
			 * 后者是「瞬间跳变」——iOS 的液态玻璃要的正是前者那种连续感。
			 */
			gliderStyle() {
				const count = this.items.length || 1;
				const index = Math.max(0, this.items.findIndex((item) => item.key === this.current));
				return {
					width: `calc(100% / ${ count })`,
					transform: `translateX(${ index * 100 }%)`
				};
			}
		},
		watch: {
			/**
			 * 配置可能比首个页面晚到（冷启动时配置还在请求中），那时 onShow/onReady 里的
			 * 判断还是「未启用」而直接返回了。这里等底栏项真正出现时再压一次原生栏，
			 * 否则就会出现「自绘栏渲染出来了、原生栏还挂着」的画面。
			 */
			items: {
				handler(val) {
					if (val.length) hideNativeTabBar();
				},
				immediate: true
			}
		},
		methods: {
			/**
			 * 切换规则（用服务端下发的 nativeTab 判断，前端不猜）：
			 *
			 * - 原生 tab 页 → `switchTab`：保留页面状态，并自动清掉非原生页；
			 * - 非原生页（图库 / 小店）→ `redirectTo`：它们必须表现得和 tab 一样「不是二级页面」。
			 *   早前用 `navigateTo`，会在标题栏留下返回箭头，从「我的」点进去再返回就退回「我的」，
			 *   看起来像子页面——这是明确反馈不合理的点。
			 *   代价：被替换掉的那个 tab 页会在下次进入时重新加载（原生 5 项仍走 switchTab，状态照常保留）。
			 *   用 navigateTo 也解决不了这个代价——只要想保留上个页面的状态，返回箭头就会出现。
			 */
			fnOnTap(item) {
				if (!item?.path || item.key === this.current) return;

				// 带 mode 的项：图库/小店与瞬间共用同一个原生页，切的是「视图」而不是页面。
				// 已经在宿主页上时 switchTab 到同一页不会触发 onShow，只能靠事件通知它换视图。
				if (item.mode) {
					setPendingTabbarMode(item.mode);
					if (this.fnIsOnPath(item.path)) {
						// 已经在宿主页上：switchTab 到同一页不触发 onShow，用响应式状态通知它换视图
						requestTabbarView(item.mode);
					} else if (item.nativeTab) {
						uni.switchTab({ url: item.path });
					} else {
						uni.redirectTo({ url: item.path });
					}
					return;
				}

				if (item.nativeTab) {
					uni.switchTab({ url: item.path });
					return;
				}
				uni.redirectTo({ url: item.path });
			},
			/** 当前页是不是就是它（用来避免 switchTab 到自己） */
			fnIsOnPath(path) {
				const pages = getCurrentPages();
				if (!pages.length) return false;
				return `/${pages[pages.length - 1].route}` === path;
			}
		}
	};
</script>

<style lang="scss" scoped>
	@import './uh-tabbar.scss';
</style>
