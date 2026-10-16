<template>
	<!-- 满宽纸面导航；后台与用户决定显示项，原生 tab 路由继续保留页面状态。 -->
	<view :class="skinClass">
		<view v-if="items.length && mode !== 'static'" class="uh-tabbar_holder"></view>
		<view v-if="items.length" class="uh-tabbar" :class="mode === 'static' ? 'is-static' : 'is-fixed'">
			<view class="uh-tabbar_inner">
				<view v-for="item in items" :key="item.key" class="uh-tabbar_item"
					:class="{ 'is-active': item.key === current }" @click="fnOnTap(item)">
					<view class="uh-tabbar_icon-wrap"><uh-icon v-if="iconNames[item.key]" :name="iconNames[item.key]" :state="item.key === current ? 'active' : 'muted'" :size="42"></uh-icon>
					<image v-else class="uh-tabbar_icon" :src="item.key === current ? item.activeIcon : item.icon"
						mode="aspectFit"></image></view>
					<text class="uh-tabbar_label">{{ item.label }}</text>
				</view>
			</view>
			<!-- iPhone 底部安全区：单独一层，避免把 rpx 和 env() 混进同一个 calc -->
			<view class="uh-tabbar_safe"></view>
		</view>
	</view>
</template>

<script>
import UhIcon from '@/components/uh-icon/uh-icon.vue';
	import { getTabbarItems, hideNativeTabBar, requestTabbarView, setPendingTabbarMode } from '@/utils/tabbar.js';

	export default {
		components: { UhIcon },
		name: 'uh-tabbar',
		data() { return { iconNames: { home: 'house', category: 'compass', moments: 'images', rank: 'chart-no-axes-combined', about: 'user-round', gallery: 'images', store: 'shopping-bag' } }; },
		props: {
			/** 当前项 key（home / category / moments / rank / gallery / store / about） */
			current: {
				type: String,
				default: ''
			},
			/** fixed=固定（默认）｜static=占流内空间（小店页用，避免遮挡 store-home） */
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

@import '@/common/themes/navigation.scss';
</style>
