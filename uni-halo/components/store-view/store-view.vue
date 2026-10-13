<template>
	<view class="store-view">
		<view class="store-view_body" :style="calcBodyStyle">
			<!--
				内嵌小店：用微信官方「店铺卡」组件 store-home 把微信小店主页搬进小程序，
				用户在小程序内完成浏览与下单，不需要跳出去。
				合规红线（官方规则）：组件必须完整展示，不得修改其内部样式与透明度，否则能力会被封。
				所以这里只给宿主元素宽高，不加蒙层、不加内边距、底栏也改成 static 模式不压在它上面。
			-->
			<view v-if="calcState === 'embed'" class="store-embed">
				<!-- v-if 是必需的：该组件只认首次渲染，appid 还没拿到就渲染会一直空白（官方给的写法是 wx:if） -->
				<store-home v-if="storeAppId" id="uh-store-home" :appid="storeAppId" class="store-embed_inner">
				</store-home>
			</view>

			<!-- 未配置微信小店ID：给出开通指引（站长照这个步骤做即可） -->
			<view v-else-if="calcState === 'guide'" class="store-panel">
				<view class="store-panel_icon">
					<text class="iconfont icon-shoppingcart"></text>
				</view>
				<text class="store-panel_title">{{ storeName || '本站小店' }}尚未接入微信小店</text>
				<text class="store-panel_desc">
					{{ storeDesc || '开通微信小店后，小程序里就能直接浏览商品并下单，无需跳转。' }}
				</text>
				<view class="store-steps">
					<view class="store-steps_item">
						<text class="store-steps_index">1</text>
						<text class="store-steps_text">准备营业执照（企业或个体工商户），在微信内搜索并打开「微信小店」，按引导完成开店</text>
					</view>
					<view class="store-steps_item">
						<text class="store-steps_index">2</text>
						<text class="store-steps_text">完成店铺信息与商品上架</text>
					</view>
					<view class="store-steps_item">
						<text class="store-steps_index">3</text>
						<text class="store-steps_text">在小店后台「店铺管理 → 基础信息 → 账号信息」里复制「微信小店ID」</text>
					</view>
					<view class="store-steps_item">
						<text class="store-steps_index">4</text>
						<text class="store-steps_text">把 ID 填到：插件设置 → 底部导航（TabBar）→ 微信小店 → 微信小店ID</text>
					</view>
				</view>
				<view v-if="storeCover" class="store-panel_cover">
					<image class="store-panel_cover-img" :src="storeCover" mode="aspectFill"></image>
				</view>
				<tm-button v-if="storeGuideUrl" theme="indigo" :shadow="0" block @click="fnToGuideUrl">查看开通指引</tm-button>
				<text class="store-panel_note">
					不想让访客看到这一页时，可在插件后台关闭「显示『微信小店』」开关。
				</text>
			</view>

			<!-- 微信版本过低：内嵌需要基础库 3.5.5+ -->
			<view v-else-if="calcState === 'version'" class="store-panel">
				<view class="store-panel_icon">
					<text class="iconfont icon-shoppingcart"></text>
				</view>
				<text class="store-panel_title">当前微信版本过低</text>
				<text class="store-panel_desc">
					内嵌微信小店需要微信基础库 3.5.5 及以上，请把微信升级到最新版后再试。
				</text>
				<view v-if="storeQrCode" class="store-panel_qrcode">
					<image class="store-panel_qrcode-img" :src="storeQrCode" mode="aspectFit"></image>
					<text class="store-panel_qrcode-tip">也可以先扫码进店</text>
				</view>
			</view>

			<!-- 非微信端（H5 / App）：内嵌能力只在微信小程序里存在 -->
			<view v-else-if="calcState === 'unsupported'" class="store-panel">
				<view class="store-panel_icon">
					<text class="iconfont icon-shoppingcart"></text>
				</view>
				<text class="store-panel_title">请在微信小程序中打开</text>
				<text class="store-panel_desc">内嵌微信小店依赖微信小程序能力，当前环境暂不支持。</text>
				<view v-if="storeQrCode" class="store-panel_qrcode">
					<image class="store-panel_qrcode-img" :src="storeQrCode" mode="aspectFit"></image>
					<text class="store-panel_qrcode-tip">扫码进店</text>
				</view>
			</view>

			<!-- 已配置但组件没渲染出来：小店未开通 / ID 有误 / 组件不可用 -->
			<view v-else class="store-panel">
				<view class="store-panel_icon">
					<text class="iconfont icon-shoppingcart"></text>
				</view>
				<text class="store-panel_title">小店暂时打不开</text>
				<text class="store-panel_desc">
					可能的原因：微信小店ID 填写有误、小店尚未开通或已关闭、当前微信版本不支持内嵌小店。
				</text>
				<view v-if="storeQrCode" class="store-panel_qrcode">
					<image class="store-panel_qrcode-img" :src="storeQrCode" mode="aspectFit"></image>
					<text class="store-panel_qrcode-tip">扫码进店</text>
				</view>
				<tm-button theme="indigo" :shadow="0" block @click="fnOnRetry">重新加载</tm-button>
			</view>

			<!-- 诊断信息：真机排查用（哪一层判定失败一眼可见） -->
			<text class="store-view_diag">诊断：{{ calcDiagText }}</text>
		</view>
	</view>
</template>

<script>
	import tmButton from '@/tm-vuetify/components/tm-button/tm-button.vue';
	import { tabbarBottomOffsetPx } from '@/utils/tabbar.js';

	/** store-home 组件要求的基础库最低版本（官方文档：3.5.5 起支持） */
	const SDK_MIN_VERSION = '3.5.5';
	/** 渲染探测延迟：组件没有加载/失败回调，只能等一会儿量盒子 */
	const PROBE_DELAY = 2500;

	const compareVersion = (a, b) => {
		const pa = String(a || '').split('.').map(Number);
		const pb = String(b || '').split('.').map(Number);
		for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
			const va = pa[i] || 0;
			const vb = pb[i] || 0;
			if (va !== vb) return va > vb ? 1 : -1;
		}
		return 0;
	};

	export default {
		name: 'store-view',
		components: {
			tmButton
		},
		props: {
			/**
			 * 是否「可见且正停在小店视图」：宿主页传 viewMode === 'store' && 页面在前台。
			 *
			 * 必须传：底栏是固定的，视图切换用 v-show 保留状态，隐藏时节点高度为 0，
			 * 渲染探测会把「正常小店」误判成「未开通」。
			 */
			active: {
				type: Boolean,
				default: true
			}
		},
		data() {
			return {
				/** 渲染探测结论：组件量出来是 0 高（或压根没渲染）时置 true */
				probeFailed: false,
				probeTimer: null
			};
		},
		watch: {
			active(val) {
				if (val) {
					this.fnScheduleProbe();
				} else {
					this.fnClearProbe();
				}
			}
		},
		computed: {
			haloConfigs() {
				return this.$tm.vx.getters().getConfigs;
			},
			/** 给固定底栏留出的底部内边距：官方要求 store-home 完整展示，不能让底栏压住它 */
			calcBodyStyle() {
				const offset = tabbarBottomOffsetPx();
				return offset ? `padding-bottom: ${offset}px` : '';
			},
			storeConfig() {
				return this.haloConfigs?.tabbarConfig?.store || {};
			},
			storeAppId() {
				return (this.storeConfig.appId || '').trim();
			},
			storeName() {
				return (this.storeConfig.name || '').trim();
			},
			storeDesc() {
				return (this.storeConfig.desc || '').trim();
			},
			storeCover() {
				const url = (this.storeConfig.coverUrl || '').trim();
				return url ? this.$utils.checkImageUrl(url) : '';
			},
			storeQrCode() {
				const url = (this.storeConfig.qrCodeUrl || '').trim();
				return url ? this.$utils.checkImageUrl(url) : '';
			},
			storeGuideUrl() {
				return (this.storeConfig.guideUrl || '').trim();
			},
			/** 是否微信小程序（内嵌小店只在微信小程序里可用） */
			calcIsWeixinMiniProgram() {
				// #ifdef MP-WEIXIN
				return true;
				// #endif
				// #ifndef MP-WEIXIN
				return false;
				// #endif
			},
			calcSdkVersion() {
				// #ifdef MP-WEIXIN
				return uni.getSystemInfoSync().SDKVersion || '';
				// #endif
				// #ifndef MP-WEIXIN
				return '';
				// #endif
			},
			/** 基础库是否满足 store-home 要求 */
			calcSdkVersionOk() {
				if (!this.calcIsWeixinMiniProgram) return false;
				return compareVersion(this.calcSdkVersion, SDK_MIN_VERSION) >= 0;
			},
			/**
			 * 小店页的四种落点。
			 *
			 * 「小店是否开通」在客户端没有可查的接口（订单类接口需要小店侧发起关联），
			 * 所以判断分三层：配置 → 环境 → 渲染探测，逐层降级，每层都有明确的说明页，
			 * 不会出现「点进来一片空白」。
			 */
			calcState() {
				if (!this.storeAppId) return 'guide';
				if (!this.calcIsWeixinMiniProgram) return 'unsupported';
				if (!this.calcSdkVersionOk) return 'version';
				if (this.probeFailed) return 'unavailable';
				return 'embed';
			},
			calcDiagText() {
				if (!this.storeAppId) return '未填写微信小店ID（插件设置 → 底部导航 → 微信小店）';
				if (!this.calcIsWeixinMiniProgram) return '当前端不支持内嵌小店（仅微信小程序可用）';
				if (!this.calcSdkVersionOk) {
					return `基础库 ${this.calcSdkVersion || '未知'} 低于 ${SDK_MIN_VERSION}`;
				}
				if (this.probeFailed) return 'store-home 组件未渲染出内容';
				return `内嵌中（基础库 ${this.calcSdkVersion}，小店ID ${this.storeAppId}）`;
			}
		},
		/** 由宿主页在显示时转发：用户可能刚从后台改完配置回来，重试一次渲染探测 */
		methods: {
			/** 清掉待执行的渲染探测：切走视图或重新排期时都要先取消，否则旧定时器会改错状态 */
			fnClearProbe() {
				if (this.probeTimer) {
					clearTimeout(this.probeTimer);
					this.probeTimer = null;
				}
			},
			fnScheduleProbe() {
				this.fnClearProbe();
				// 没停在小店视图（v-show 隐藏）时不探测：隐藏节点量出来是 0 高，会误判
				if (!this.active || this.calcState !== 'embed') return;
				this.probeTimer = setTimeout(() => this.fnProbeStore(), PROBE_DELAY);
			},
			/**
			 * 量一下 store-home 渲染出来没有。
			 *
			 * 判据刻意保守：只有「能选中该节点、但高度为 0」才判定失败。
			 * 量不到节点（选择器不支持、组件是原生组件等）时不判失败，否则会把
			 * 「正常但量不到」误报成「小店未开通」，那比不探测更糟。
			 */
			fnProbeStore() {
				if (!this.active) return;
				uni.createSelectorQuery()
					.in(this)
					.select('#uh-store-home')
					.boundingClientRect((rect) => {
						if (!rect) return;
						if ((rect.height || 0) <= 0) {
							this.probeFailed = true;
						}
					})
					.exec();
			},
			fnOnRetry() {
				this.probeFailed = false;
				this.fnScheduleProbe();
			},
			fnToGuideUrl() {
				if (!this.storeGuideUrl) return;
				uni.navigateTo({
					url: '/pagesC/website/website?data=' +
						JSON.stringify({
							title: '微信小店开通指引',
							url: encodeURIComponent(this.storeGuideUrl)
						})
				});
			}
		}
	};
</script>

<style lang="scss" scoped>
	/* 整页固定高度：内容区自适应 + 底栏占流内空间（static），保证 store-home 完整可见 */
	.store-view {
		display: flex;
		flex-direction: column;
		flex: 1;
		min-height: 0;
		width: 100%;
		box-sizing: border-box;
		background-color: $uh-bg;
	}

	.store-view_body {
		flex: 1;
		min-height: 0;
		display: flex;
		flex-direction: column;
	}

	.store-embed {
		flex: 1;
		min-height: 0;
		width: 100%;
		/* 只给宿主元素尺寸，不碰组件内部样式（官方合规要求） */
		overflow: hidden;
	}

	.store-embed_inner {
		display: block;
		width: 100%;
		height: 100%;
	}

	.store-view_diag {
		display: block;
		padding: 12rpx 24rpx;
		font-size: 20rpx;
		color: $uh-ink-4;
		text-align: center;
	}

	/* ===== 说明页（未开通 / 版本过低 / 端不支持 / 不可用）===== */
	.store-panel {
		flex: 1;
		min-height: 0;
		overflow-y: auto;
		box-sizing: border-box;
		padding: 48rpx 32rpx 32rpx;
	}

	.store-panel_icon {
		display: flex;
		align-items: center;
		justify-content: center;
		width: 120rpx;
		height: 120rpx;
		margin: 0 auto 24rpx;
		border-radius: 32rpx;
		background-color: $uh-accent-weak;
	}

	.store-panel_icon .iconfont {
		font-size: 60rpx;
		color: $uh-accent;
	}

	.store-panel_title {
		display: block;
		text-align: center;
		font-size: 32rpx;
		font-weight: 600;
		color: $uh-ink-1;
	}

	.store-panel_desc {
		display: block;
		margin-top: 12rpx;
		text-align: center;
		font-size: 24rpx;
		line-height: 1.6;
		color: $uh-ink-3;
	}

	.store-steps {
		margin: 32rpx 0;
		padding: 24rpx;
		border-radius: $uh-radius-md;
		background-color: $uh-card;
	}

	.store-steps_item {
		display: flex;
		align-items: flex-start;
	}

	.store-steps_item + .store-steps_item {
		margin-top: 20rpx;
	}

	.store-steps_index {
		flex-shrink: 0;
		width: 36rpx;
		height: 36rpx;
		margin-right: 16rpx;
		border-radius: 50%;
		background-color: $uh-accent;
		font-size: 22rpx;
		line-height: 36rpx;
		text-align: center;
		color: #ffffff;
	}

	.store-steps_text {
		flex: 1;
		font-size: 24rpx;
		line-height: 1.6;
		color: $uh-ink-2;
	}

	.store-panel_cover {
		margin-bottom: 24rpx;
		border-radius: $uh-radius-md;
		overflow: hidden;
	}

	.store-panel_cover-img {
		display: block;
		width: 100%;
		height: 240rpx;
	}

	.store-panel_qrcode {
		display: flex;
		flex-direction: column;
		align-items: center;
		margin: 32rpx 0;
	}

	.store-panel_qrcode-img {
		width: 320rpx;
		height: 320rpx;
		border-radius: $uh-radius-md;
		background-color: $uh-card;
	}

	.store-panel_qrcode-tip {
		margin-top: 12rpx;
		font-size: 22rpx;
		color: $uh-ink-3;
	}

	.store-panel_note {
		display: block;
		margin-top: 20rpx;
		text-align: center;
		font-size: 20rpx;
		line-height: 1.6;
		color: $uh-ink-4;
	}
</style>
