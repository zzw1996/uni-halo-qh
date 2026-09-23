<template>
	<view class="gallery-view" :class="uniHaloPluginPageClass">
		<PluginUnavailable v-if="!uniHaloPluginAvailable" :pluginId="uniHaloPluginId"
			:error-text="uniHaloPluginAvailableError" />
		<template v-else>
			<!--
				顶部分类切换：只有一个分类（通常只有「全部」）时不显示——
				一条只有单项的分类栏既没用又占掉首屏一条空间。
			-->
			<view class="cate-bar e-fixed" v-if="calcShowCategoryBar">
				<tm-tabs color="indigo" :shadow="0" v-model="category.activeIndex" range-key="displayName"
					:list="category.list" align="left" @change="fnOnCategoryChange($event, false)"></tm-tabs>
			</view>
			<!-- 占位区域：避免固定分类栏盖住第一行图片 -->
			<view v-if="calcShowCategoryBar" class="cate-bar-holder"></view>
			<!--
				加载骨架：按瀑布流的双列排，和最终布局一致，切换时不跳动。
			-->
			<view v-if="loading == 'loading'" class="sk-waterfall">
				<view class="sk-waterfall_col">
					<view v-for="i in 3" :key="'l' + i" class="sk-waterfall_card" :style="{ height: i % 2 ? '360rpx' : '260rpx' }"></view>
				</view>
				<view class="sk-waterfall_col">
					<view v-for="i in 3" :key="'r' + i" class="sk-waterfall_card" :style="{ height: i % 2 ? '260rpx' : '340rpx' }"></view>
				</view>
			</view>
			<view v-else-if="loading == 'error'" class="flex flex-col flex-center" style="width:100%;height:60vh;">
				<tm-empty icon="icon-wind-cry" label="阿偶，获取数据失败了~">
					<tm-button theme="indigo" size="m" :shadow="0" @click="fnGetData(true)">刷新试试</tm-button>
				</tm-empty>
			</view>
			<!-- 内容区域 -->
			<view v-else class="content">
				<k-touch-listen class="touch-listen-content" @touchLeft="touchLeft" @touchRight="touchRight">
					<view v-if="dataList.length === 0" class="content-empty">
						<!-- 空布局 -->
						<tm-empty icon="icon-shiliangzhinengduixiang-" label="博主还没有分享图片~"></tm-empty>
					</view>
					<block v-else>
						<!--瀑布流（两列，卡片由组件内部渲染）-->
						<tm-flowLayout-custom ref="wafll" style="width: 100%;"
							@click="fnOnFlowClick"></tm-flowLayout-custom>
						<view class="load-text">{{ loadMoreText }}</view>
					</block>
				</k-touch-listen>
			</view>

			<view v-if="!calcAuditModeEnabled" class="flot-buttons" :style="calcFlotButtonsStyle">
				<tm-button v-if="loading == 'error'" @click="fnGetCategory" size="m" :fab="true" theme="indigo"
					icon="icon-sync-alt"></tm-button>
				<tm-button @click="fnToTopPage" size="m" :fab="true" theme="indigo"
					icon="icon-angle-up"></tm-button>
			</view>
		</template>
	</view>
</template>

<script>
	import { tabbarBottomOffsetPx } from '@/utils/tabbar.js';
	import tmEmpty from '@/tm-vuetify/components/tm-empty/tm-empty.vue';
	import tmFlowLayoutCustom from '@/tm-vuetify/components/tm-flowLayout-custom/tm-flowLayout-custom.vue';
	import tmTabs from '@/tm-vuetify/components/tm-tabs/tm-tabs.vue';
	import tmButton from '@/tm-vuetify/components/tm-button/tm-button.vue';

	import pluginAvailable from "@/common/mixins/pluginAvailable.js"
	import PluginUnavailable from '@/components/plugin-unavailable/plugin-unavailable.vue'
	export default {
		name: 'gallery-view',
		options: {
			multipleSlots: true
		},
		mixins: [pluginAvailable],
		components: {
			tmEmpty,
			tmFlowLayoutCustom,
			tmTabs,
			tmButton,
			PluginUnavailable
		},
		data() {
			return {
				isBlackTheme: false,
				loading: 'loading',
				category: {
					activeIndex: 0,
					activeValue: '',
					list: []
				},
				queryParams: {
					size: 10,
					page: 1,
					group: ""
				},
				isLoadMore: false,
				loadMoreText: '',
				hasNext: false,
				dataList: [],
				lock: false
			};
		},
		computed: {
			/** 悬浮按钮抬高量：自绘底栏开启时窗口占满全屏，不抬高会被底栏压住 */
			calcFlotButtonsStyle() {
				const offset = tabbarBottomOffsetPx();
				return offset ? `bottom: calc(100rpx + ${offset}px)` : '';
			},
			/** 图库页布局固定值（原后台「页面配置 - 图库配置」已移除） */
			galleryConfig() {
				return {
					pageTitle: '图库'
				};
			},
			/** 分类多于一个才显示顶部切换栏（只有一个「全部」时没必要占一条） */
			calcShowCategoryBar() {
				return this.category.list.length > 1;
			},
			haloConfigs() {
				return this.$tm.vx.getters().getConfigs;
			},
			mockJson() {
				return this.$tm.vx.getters().getMockJson;
			},
			calcAuditModeEnabled() {
				return this.haloConfigs.auditConfig.auditModeEnabled
			},
		},
		watch: {
			galleryConfig: {
				async handler(newValue, oldValue) {
					if (!newValue) return;
					this.fnSetPageTitle(newValue.pageTitle);
					this.fnGetCategory();
				},
				deep: true,
				immediate: true
			},
			/** 宿主页转发来的下拉刷新 / 触底加载信号（递增计数，只能靠属性传） */
			pullDownTick(val) {
				if (val > 0) this.viewOnPullDownRefresh();
			},
			reachBottomTick(val) {
				if (val > 0) this.viewOnReachBottom();
			}
		},
		props: {
			/**
			 * 宿主页（瞬间页）的下拉刷新 / 触底加载信号。
			 *
			 * <p>小程序端不能用 `$refs` 调子组件方法（编译后 ref 是包装对象，方法取不到）；
			 * 也不该用 `uni.$emit`——全局事件是常驻监听，组件销毁后监听器可能仍在，
			 * 会报「Cannot read properties of undefined (reading 'apply')」。
			 * 用递增的计数属性最稳：宿主页 +1，这里 watch 到变化就执行。</p>
			 */
			pullDownTick: {
				type: Number,
				default: 0
			},
			reachBottomTick: {
				type: Number,
				default: 0
			}
		},
		async mounted() {
			// 检查插件
			this.setPluginId(this.NeedPluginIds.PluginPhotos)
			this.setPluginError("阿偶，检测到当前插件没有安装或者启用，无法使用图库功能哦，请联系管理员")
			await this.checkPluginAvailable()
		},
		methods: {
			/**
			 * 由宿主页（瞬间页）转发进来的下拉刷新。
			 *
			 * 必须放在 methods 里——早前从页面钩子 `onPullDownRefresh` 改名后留在了顶层选项上，
			 * 结果 this.viewOnPullDownRefresh 根本不存在，watch 一触发就报 is not a function。
			 */
			viewOnPullDownRefresh() {
				if (!this.uniHaloPluginAvailable) {
					uni.hideLoading();
					uni.stopPullDownRefresh();
					return
				}
				this.dataList = []
				this.isLoadMore = false;
				this.queryParams.page = 1;
				this.fnGetData(true);
			},
			/** 由宿主页转发进来的触底加载 */
			viewOnReachBottom(e) {
				if (!this.uniHaloPluginAvailable) return;
				if (this.calcAuditModeEnabled) {
					uni.showToast({
						icon: 'none',
						title: '没有更多数据了'
					});
					return;
				}

				if (this.hasNext) {
					this.queryParams.page += 1;
					this.isLoadMore = true;
					this.fnGetData(false);
				} else {
					uni.showToast({
						icon: 'none',
						title: '没有更多数据了'
					});
				}
			},
			fnGetDataByCategory(index) {
				this.fnResetSetAniWaitIndex();
				this.queryParams.group = this.category.list[index].name;
				this.queryParams.page = 1;
				this.fnToTopPage();
				this.dataList = [];
				this.fnGetData(true);
			},
			fnOnCategoryChange(index) {
				if (this.lock) {
					// uni.showToast({
					// 	title: "上一个请求进行中...",
					// 	icon: "none"
					// })
					return;
				}
				this.fnGetDataByCategory(index)
			},
			fnGetCategory() {
				if (this.calcAuditModeEnabled) {
					this.fnGetData(true);
					return
				}
				this.$httpApi.v2.getPhotoGroupList({
					page: 1,
					size: 0
				}).then(res => {
					this.category.list = res.map(item => {
						return {
							name: item.metadata.name,
							displayName: item.spec.displayName,
							priority: item.spec.priority
						}
					}).sort((a, b) => a.priority - b.priority)
					this.category.list.unshift({
						name: undefined,
						displayName: '全部',
						priority: 0
					});
					if (this.category.list.length !== 0) {
						this.queryParams.group = this.category.list[0].name;
						this.fnGetData(true);
					}
				}).catch(e => {
					this.loading = 'error'
					this.category.list = []
					this.category.activeIndex = 0
					this.category.activeValue = ""
				});
			},
			fnGetData(isClearWaterfall = false) {
				if (this.calcAuditModeEnabled) {
					this.dataList = this.mockJson.gallery.list.map(item => {
						return {
							metadata: {
								name: Date.now() * Math.random(),
							},
							spec: {
								url: this.$utils.checkImageUrl(item)
							}
						}
					})

					this.loading = 'success';

					this.$nextTick(() => {
						if (isClearWaterfall) {
							this.$refs.wafll.clear()
						}
						setTimeout(() => {
							this.$refs.wafll.pushData(this.dataList)
						}, 50)
					})
					this.loadMoreText = '呜呜，没有更多数据啦~';
					uni.hideLoading();
					uni.stopPullDownRefresh();
					this.lock = false;
					return;
				}

				// 设置状态为加载中
				if (!this.isLoadMore) {
					this.loading = 'loading';
				}
				this.loadMoreText = '';
				this.$httpApi.v2
					.getPhotoListByGroupName(this.queryParams)
					.then(res => {
						this.hasNext = res.hasNext;
						this.loading = 'success';
						if (res.items.length !== 0) {
							const _list = res.items.map((item, index) => {
								item.spec.url = this.$utils.checkImageUrl(item.spec.url || item.spec.cover);
								return item;
							});
							if (this.isLoadMore) {
								this.dataList = this.dataList.concat(_list);
							} else {
								this.dataList = _list;
							}
							this.$nextTick(() => {
								if (isClearWaterfall) {
									this.$refs.wafll.clear()
								}
								this.$refs.wafll.pushData(_list)
							})
						}
						this.loadMoreText = res.hasNext ? '上拉加载更多' : '呜呜，没有更多数据啦~';
					})
					.catch(err => {
						console.error(err);
						this.loading = 'error';
						this.loadMoreText = '加载失败，请下拉刷新！';
					})
					.finally(() => {
						setTimeout(() => {
							uni.hideLoading();
							uni.stopPullDownRefresh();
							this.lock = false;
						}, 500);
					});
			},
			fnOnFlowClick({
				item
			}) {
				this.fnPreview(item)
			},
			// 预览
			fnPreview(data) {
				uni.previewImage({
					current: this.dataList.findIndex(x => x.metadata.name === data.metadata.name),
					urls: this.dataList.map(x => x.spec.url),
					indicator: 'number',
					loop: true
				});
			},
			touchLeft() {
				if (this.loading != "success") return;
				this.category.activeIndex += 1
				if (this.category.activeIndex >= this.category.list.length) {
					this.category.activeIndex = 0
				}
				this.lock = true
				this.fnGetDataByCategory(this.category.activeIndex)
			},
			touchRight() {
				if (this.loading != "success") return;
				this.category.activeIndex -= 1
				if (this.category.activeIndex < 0) {
					this.category.activeIndex = 0
				}
				this.lock = true
				this.fnGetDataByCategory(this.category.activeIndex)
			}
		}
	};
</script>

<style lang="scss" scoped>
	.gallery-view {
		width: 100vw;
		min-height: 100vh;
		display: flex;
		flex-direction: column;
		padding-bottom: 24rpx;
		background-color: $uh-bg;
	}

	.content {
		width: 100%;
		display: flex;
		flex-wrap: wrap;
		box-sizing: border-box;

		.content-empty {
			width: 100%;
			height: 70vh;
			display: flex;
			align-items: center;
			justify-content: center;
		}
	}

	.touch-listen-content {
		width: 100%;
		display: flex;
		flex-wrap: wrap;
		box-sizing: border-box;
		padding: 24rpx 24rpx 0;
		gap: 12rpx 0;
	}

	/* 顶部分类栏：铺一层纸面并加细分割线，避免和下面的图片糊在一起 */
	.cate-bar {
		background-color: $uh-card;
		border-bottom: 1rpx solid $uh-line;
	}

	.cate-bar-holder {
		width: 100vw;
		height: 90rpx;
	}

	/* 加载骨架：双列，和瀑布流布局一致 */
	.sk-waterfall {
		display: flex;
		justify-content: space-between;
		box-sizing: border-box;
		padding: 24rpx 24rpx 0;
	}

	.sk-waterfall_col {
		width: 48.6%;
	}

	.sk-waterfall_card {
		width: 100%;
		margin-bottom: 20rpx;
		border-radius: $uh-radius-md;
		background-color: $uh-fill;
		animation: skWaterfallPulse 1.2s ease-in-out infinite;
	}

	@keyframes skWaterfallPulse {
		0%,
		100% {
			opacity: 1;
		}

		50% {
			opacity: 0.55;
		}
	}

	.load-text {
		width: 100%;
		text-align: center;
	}

	.flot-buttons {
		position: fixed;
		/* 自绘底栏开启时窗口占满全屏，靠内联样式再顶起一个底栏高度（见模板 calcFlotButtonsStyle） */
		bottom: 100rpx;
		right: 32rpx;
		flex-direction: column;
		display: flex;
		gap: 6rpx;
		z-index: 999;
	}
</style>