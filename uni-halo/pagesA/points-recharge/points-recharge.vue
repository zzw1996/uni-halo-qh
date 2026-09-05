<template>
	<view class="app-page">
		<view v-if="loading !== 'success'" class="loading-wrap">
			<tm-skeleton model="listAvatr"></tm-skeleton>
			<tm-skeleton model="listAvatr"></tm-skeleton>
		</view>
		<block v-else>
			<view class="balance-card">
				<text class="balance-label">当前积分</text>
				<text class="balance-value">{{ balance }}</text>
			</view>

			<view v-if="goodsList.length === 0" class="empty">
				<tm-empty icon="icon-shiliangzhinengduixiang-" label="暂无可购买的积分道具"></tm-empty>
			</view>
			<block v-else>
				<view class="goods-list">
					<view v-for="(item, index) in goodsList" :key="index" class="goods-card">
						<view class="goods-main">
							<text class="goods-name">{{ item.name }}</text>
							<text class="goods-points">到账 {{ item.points }} 积分</text>
						</view>
						<view class="goods-side">
							<text class="goods-price">￥{{ Number(item.price) / 100 }}</text>
							<tm-button
								size="s"
								theme="primary"
								:loading="payingIndex === index"
								@click="fnBuy(item, index)"
							>购买</tm-button>
						</view>
					</view>
				</view>
			</block>

			<view class="footer-tip">
				<text>说明：充值成功后积分自动到账，可在「积分明细」中查看。</text>
				<text>虚拟支付由微信提供，费率以微信平台规则为准（Android 约 1%、iOS 约 12%，180 天内退款退还技术服务费）；个人主体小程序全终端月支付限额为 10 万元。</text>
			</view>
		</block>
	</view>
</template>

<script>
	import tmSkeleton from '@/tm-vuetify/components/tm-skeleton/tm-skeleton.vue';
	import tmEmpty from '@/tm-vuetify/components/tm-empty/tm-empty.vue';
	import tmButton from '@/tm-vuetify/components/tm-button/tm-button.vue';
	import { getAppConfigs } from '@/config/index.js';
	import { checkHasWxLogin } from '@/utils/auth.js';
	import { fetchPointsOverview, syncPointsAfterLogin, emitPointsUpdated } from '@/utils/points.js';

	const WX_IOS_MIN_VERSION = '8.0.68';
	const SDK_MIN_VERSION = '2.19.2';
	const POLL_TOTAL = 15;
	const POLL_INTERVAL = 2000;

	function compareVersion(a, b) {
		const pa = a.split('.').map(Number);
		const pb = b.split('.').map(Number);
		for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
			const va = pa[i] || 0;
			const vb = pb[i] || 0;
			if (va !== vb) return va > vb ? 1 : -1;
		}
		return 0;
	}

	/**
	 * iOS 端需微信客户端 >= 8.0.68 才能调用 wx.requestVirtualPayment；
	 * 基础库需 >= 2.19.2。
	 */
	function checkPayEnvironment() {
		// #ifdef MP-WEIXIN
		const sys = uni.getSystemInfoSync();
		if (sys.platform === 'ios' && compareVersion(sys.version, WX_IOS_MIN_VERSION) < 0) {
			return { ok: false, reason: '请将微信更新至最新版后再进行支付' };
		}
		if (compareVersion(sys.SDKVersion, SDK_MIN_VERSION) < 0 || typeof wx.requestVirtualPayment !== 'function') {
			return { ok: false, reason: '当前微信版本过低，不支持虚拟支付，请升级微信后重试' };
		}
		return { ok: true };
		// #endif
		// #ifndef MP-WEIXIN
		return { ok: false, reason: '当前平台暂不支持虚拟支付，请使用微信小程序访问' };
		// #endif
	}

	function requestVirtualPayment(payData) {
		// #ifdef MP-WEIXIN
		return new Promise((resolve, reject) => {
			wx.requestVirtualPayment({
				signData: payData.signData,
				paySig: payData.paySig,
				signature: payData.signature,
				mode: payData.mode,
				success: () => resolve({ ok: true }),
				fail: (err) => resolve({ ok: false, reason: (err && err.errMsg) || '支付取消' })
			});
		});
		// #endif
		// #ifndef MP-WEIXIN
		return Promise.resolve({ ok: false, reason: '当前平台暂不支持虚拟支付' });
		// #endif
	}

	export default {
		components: {
			tmSkeleton,
			tmEmpty,
			tmButton
		},
		data() {
			return {
				loading: 'loading',
				balance: 0,
				goodsList: [],
				payingIndex: -1
			};
		},
		onLoad() {
			if (!checkHasWxLogin()) {
				uni.showToast({ title: '请先登录', icon: 'none' });
				setTimeout(() => uni.navigateBack(), 800);
				return;
			}
			this.fnLoad();
		},
		methods: {
			parseBody(res) {
				if (res?.statusCode && res.statusCode !== 200) {
					throw { error: res?.data?.error || res?.data?.detail || '加载失败' };
				}
				return res?.data || res || {};
			},
			async fnLoad() {
				// 先用启动时缓存的插件配置（getConfigs 已含 payConfig 与商品）即时渲染
				const cached = (getAppConfigs()?.payConfig) || {};
				if (cached?.enabled === true && Array.isArray(cached?.goods)) {
					this.goodsList = cached.goods
						.filter((item) => item && item.goodsId)
						.map((item) => ({
							goodsId: item.goodsId,
							name: item.name,
							price: Number(item.price || 0),
							points: Number(item.points || 0)
						}));
					this.loading = 'success';
				}
				try {
					const overview = await syncPointsAfterLogin(this.$httpApi.v2);
					if (overview) {
						this.balance = Number(overview.balance || 0);
					}
					const res = await this.$httpApi.v2.getVirtualPayConfig();
					const body = this.parseBody(res);
					if (!body.enabled) {
						uni.showToast({ title: '积分充值未开启', icon: 'none' });
						setTimeout(() => uni.navigateBack(), 800);
						return;
					}
					const goods = (body.goods && body.goods.length ? body.goods : cached?.goods) || [];
					this.goodsList = goods.map((item) => ({
						goodsId: item.goodsId,
						name: item.name,
						price: Number(item.price || 0),
						points: Number(item.points || 0)
					}));
					this.loading = 'success';
				} catch (err) {
					this.loading = 'success';
					const notUpgraded = err?.statusCode === 404 || err?.isHtml
						|| String(err?.error || '').includes('404');
					uni.showToast({
						title: notUpgraded
							? '积分充值需要插件 v1.0.63+，请先更新插件'
							: (err?.error || '充值页面加载失败，请稍后重试'),
						icon: 'none'
					});
				}
			},
			async fnBuy(item, index) {
				if (this.payingIndex >= 0) return;
				const envCheck = checkPayEnvironment();
				if (!envCheck.ok) {
					uni.showModal({
						title: '支付受限',
						content: envCheck.reason,
						showCancel: false
					});
					return;
				}
				this.payingIndex = index;
				try {
					const res = await this.$httpApi.v2.createPayOrder(item.goodsId);
					const body = this.parseBody(res);
					if (!body.payData || !body.outTradeNo) {
						throw { error: body?.error || '下单失败，请稍后重试' };
					}
					const payResult = await requestVirtualPayment(body.payData);
					if (!payResult.ok) {
						uni.showToast({ title: payResult.reason || '支付已取消', icon: 'none' });
						return;
					}
					// 支付成功（前端回调仅作展示，发货以服务器推送/查单为准）
					uni.showLoading({ title: '确认到账中...' });
					const delivered = await this.fnWaitDelivered(body.outTradeNo);
					uni.hideLoading();
					if (delivered.ok) {
						uni.showToast({ title: `到账 ${delivered.points} 积分`, icon: 'success' });
						this.balance = Number(delivered.balance != null ? delivered.balance : this.balance);
						emitPointsUpdated({ balance: this.balance });
					} else {
						uni.showToast({ title: '支付成功，积分稍后自动到账', icon: 'none' });
					}
				} catch (err) {
					uni.showToast({
						title: err?.error || '购买失败，请稍后重试',
						icon: 'none'
					});
				} finally {
					this.payingIndex = -1;
				}
			},
			async fnWaitDelivered(outTradeNo) {
				for (let i = 0; i < POLL_TOTAL; i++) {
					await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL));
					try {
						const res = await this.$httpApi.v2.queryPayOrder(outTradeNo);
						const body = this.parseBody(res);
						if (body.delivered) {
							return { ok: true, points: body.points || 0, balance: body.balance };
						}
					} catch (err) {
						// 轮询失败继续重试
					}
				}
				return { ok: false };
			}
		}
	};
</script>

<style scoped lang="scss">
	.app-page {
		min-height: 100vh;
		background: #f5f6fa;
		padding-bottom: 40rpx;
	}

	.loading-wrap {
		padding: 24rpx;
	}

	.balance-card {
		margin: 24rpx 28rpx 16rpx;
		padding: 32rpx 28rpx;
		border-radius: 20rpx;
		background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
		color: #fff;
	}

	.balance-label {
		display: block;
		font-size: 28rpx;
		opacity: 0.92;
	}

	.balance-value {
		display: block;
		margin-top: 8rpx;
		font-size: 56rpx;
		font-weight: 700;
		line-height: 1.1;
	}

	.goods-list {
		padding: 0 28rpx;
	}

	.goods-card {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 28rpx;
		margin-bottom: 16rpx;
		border-radius: 16rpx;
		background: #fff;
	}

	.goods-main {
		flex: 1;
		min-width: 0;
		padding-right: 20rpx;
	}

	.goods-name {
		display: block;
		font-size: 30rpx;
		font-weight: 600;
		color: #303133;
	}

	.goods-points {
		display: block;
		margin-top: 8rpx;
		font-size: 24rpx;
		color: #909399;
	}

	.goods-side {
		flex-shrink: 0;
		text-align: right;
	}

	.goods-price {
		display: block;
		margin-bottom: 12rpx;
		font-size: 30rpx;
		font-weight: 700;
		color: #e53935;
	}

	.footer-tip {
		margin: 32rpx 28rpx 0;
		padding: 24rpx;
		border-radius: 12rpx;
		background: #fff7e6;
	}

	.footer-tip text {
		display: block;
		font-size: 22rpx;
		line-height: 1.7;
		color: #b3800a;

		& + text {
			margin-top: 8rpx;
		}
	}

	.empty {
		padding-top: 80rpx;
	}
</style>
