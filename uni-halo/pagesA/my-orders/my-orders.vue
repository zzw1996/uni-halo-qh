<template>
	<view :class="skinClass" class="theme-page-pagesA-my-orders-my-orders app-page">
		<view v-if="loading !== 'success'" class="loading-wrap">
			<tm-skeleton model="listAvatr"></tm-skeleton>
			<tm-skeleton model="listAvatr"></tm-skeleton>
		</view>
		<block v-else>
			<!--
				汇总：笔数与实付合计。
				只统计「已到账」的订单金额，待支付/需退款的不计入实付——
				否则用户会看到自己没花出去的钱出现在消费合计里。
			-->
			<view class="summary-card">
				<view class="summary-row">
					<text class="summary-label">订单笔数</text>
					<text class="summary-value">{{ total }}<text class="summary-unit">笔</text></text>
				</view>
				<view class="summary-row">
					<text class="summary-label">累计实付</text>
					<text class="summary-value">{{ paidAmountText }}<text class="summary-unit">元</text></text>
				</view>
			</view>

			<view class="section-card">
				<view class="section-head">
					<text class="section-title">我的订单</text>
					<text class="section-sub">按下单时间倒序，下拉可刷新</text>
				</view>

				<view v-if="orders.length === 0" class="empty-tip">
					还没有订单记录。可在「权益直购」页购买阅读券或装扮。
				</view>

				<view
					v-for="(item, index) in orders"
					:key="item.outTradeNo + index"
					class="order-item"
					:class="'order-item--' + fnStatusTone(item.status)"
				>
					<view class="order-head">
						<text class="order-name">{{ item.goodsName }}</text>
						<text class="order-amount">￥{{ fnAmountText(item.amount) }}</text>
					</view>
					<view class="order-meta">
						<text class="order-type">{{ item.goodsTypeLabel || '权益道具' }}</text>
						<text v-if="item.quantity > 1" class="order-qty">×{{ item.quantity }}</text>
						<text class="order-time">{{ item.paidTime || item.createTime || '—' }}</text>
					</view>
					<view class="order-status-row">
						<text class="order-status" :class="'order-status--' + fnStatusTone(item.status)">
							{{ item.statusLabel || item.status }}
						</text>
					</view>
					<view class="order-foot">
						<text class="order-no">订单号 {{ item.outTradeNo }}</text>
						<text
							v-if="fnCanQuery(item)"
							class="order-action"
							@click="fnQueryOrder(item)"
						>刷新状态</text>
					</view>
				</view>

				<view v-if="orders.length" class="load-text">{{ loadMoreText }}</view>
			</view>

			<view class="footer-tip">
				<text>「待支付」的订单 15 分钟内有效，超时未支付会自动变为「已失效」（届时可重新下单）；若已扣款请耐心等待，系统会自动补发到账；「需人工处理」表示该笔权益未发放，请联系站长退款。</text>
			</view>
		</block>
	</view>
</template>

<script>
	import tmSkeleton from '@/tm-vuetify/components/tm-skeleton/tm-skeleton.vue';
	import loadMoreMixin from '@/common/mixins/loadMore.js';
	import { checkHasWxLogin } from '@/utils/auth.js';

	/** 已到账、可计入实付合计的状态 */
	const PAID_STATUS = 'delivered';

	export default {
		mixins: [loadMoreMixin],
		components: {
			tmSkeleton
		},
		data() {
			return {
				loading: 'loading',
				queryParams: {
					size: 15,
					page: 0
				},
				total: 0,
				hasMore: false,
				isLoadMore: false,
				loadMoreText: '',
				orders: [],
				/** 单笔「刷新状态」进行中，防止连点 */
				queryingNo: ''
			};
		},
		computed: {
			/**
			 * 累计实付（元）。
			 *
			 * 只累加已到账订单：待支付与需退款的金额都不是用户真正花掉的钱。
			 */
			paidAmountText() {
				const cents = this.orders
					.filter((item) => item.status === PAID_STATUS)
					.reduce((sum, item) => sum + Number(item.amount || 0), 0);
				return (cents / 100).toFixed(2);
			}
		},
		onLoad() {
			if (!checkHasWxLogin()) {
				uni.showToast({ title: '请先登录', icon: 'none' });
				setTimeout(() => uni.navigateBack(), 800);
				return;
			}
			this.fnLoad(true);
		},
		onPullDownRefresh() {
			this.isLoadMore = false;
			this.fnLoadMoreDone();
			this.queryParams.page = 0;
			this.fnLoad(true);
		},
		onReachBottom() {
			// 空列表不提示「没有更多数据了」——那会像报错；只在有数据且确实到底时才提示
			if (!this.orders.length) return;
			this.fnLoadMore(this.hasMore, () => {
				this.queryParams.page += 1;
				this.isLoadMore = true;
				this.fnLoad(false);
			});
		},
		methods: {
			async fnLoad(refresh) {
				if (!this.isLoadMore) {
					this.loading = 'loading';
				}
				this.loadMoreText = '加载中...';
				try {
					const res = await this.$httpApi.v2.getMyPayOrders({
						page: this.queryParams.page,
						size: this.queryParams.size
					});
					const body = res?.data || res || {};
					if (body.error) {
						throw { error: body.error };
					}
					const items = Array.isArray(body.items) ? body.items : [];
					this.orders = this.isLoadMore ? this.orders.concat(items) : items;
					this.total = Number(body.total || items.length);
					this.hasMore = body.hasMore === true;
					this.loadMoreText = this.hasMore ? '上拉加载更多' : '没有更多了';
					this.loading = 'success';
				} catch (err) {
					this.loading = 'success';
					this.loadMoreText = '';
					uni.showToast({
						title: err?.error || err?.detail || '订单加载失败，请稍后重试',
						icon: 'none'
					});
				} finally {
					uni.stopPullDownRefresh();
					this.fnLoadMoreDone();
				}
			},
			/**
			 * 单笔刷新状态：触发服务端对 pending 订单做一次微信侧查单兜底。
			 *
			 * 用户付完钱回到小程序、发货推送恰好丢了的时候，这里是唯一的自助补救入口。
			 */
			async fnQueryOrder(item) {
				if (this.queryingNo) return;
				this.queryingNo = item.outTradeNo;
				uni.showLoading({ title: '查询中...' });
				try {
					const res = await this.$httpApi.v2.queryPayOrder(item.outTradeNo);
					const body = res?.data || res || {};
					if (body.status) {
						item.status = body.status;
						item.statusLabel = body.message || item.statusLabel;
					}
					// 兜底查单可能刚刚补发成功，整表刷新一次更准
					this.queryParams.page = 0;
					this.isLoadMore = false;
					this.fnLoadMoreDone();
					await this.fnLoad(true);
					uni.showToast({ title: body.delivered ? '已到账' : '状态已更新', icon: 'none' });
				} catch (err) {
					uni.showToast({
						title: err?.error || err?.detail || '查询失败，请稍后重试',
						icon: 'none'
					});
				} finally {
					uni.hideLoading();
					this.queryingNo = '';
				}
			},
			/** 分 → 元，保留两位 */
			fnAmountText(cents) {
				return (Number(cents || 0) / 100).toFixed(2);
			},
			/** 只有待支付与需人工处理的订单才值得让用户主动刷新 */
			fnCanQuery(item) {
				return item.status === 'pending' || item.status === 'needs_refund';
			},
			/** 状态色调：已到账绿色、待支付橙色、需人工处理红色、其余灰色 */
			fnStatusTone(status) {
				if (status === PAID_STATUS) return 'done';
				if (status === 'pending') return 'pending';
				if (status === 'needs_refund') return 'alert';
				// 已失效 / 已关闭都是「不用管」的终态，统一走灰色
				return 'muted';
			}
		}
	};
</script>

<style scoped lang="scss">
	.app-page {
		min-height: 100vh;
		background: $uh-fill;
		padding-bottom: 40rpx;
	}

	.loading-wrap {
		padding: 24rpx;
	}

	.summary-card {
		display: flex;
		margin: 24rpx 28rpx 16rpx;
		padding: 32rpx 28rpx;
		border-radius: 20rpx;
		background-image: $uh-grad-accent;
		color: #fff;
	}

	.summary-row {
		flex: 1;
		display: flex;
		flex-direction: column;
		align-items: center;
	}

	.summary-label {
		font-size: 24rpx;
		opacity: 0.92;
	}

	.summary-value {
		margin-top: 8rpx;
		font-size: 48rpx;
		font-weight: 700;
		line-height: 1.1;
	}

	.summary-unit {
		margin-left: 6rpx;
		font-size: 22rpx;
		font-weight: 400;
		opacity: 0.9;
	}

	.section-card {
		margin: 0 28rpx 16rpx;
		padding: 24rpx 28rpx;
		border-radius: 20rpx;
		background: $uh-card;
	}

	.section-head {
		margin-bottom: 12rpx;
	}

	.section-title {
		display: block;
		font-size: 28rpx;
		font-weight: 700;
		color: $uh-ink-1;
	}

	.section-sub {
		display: block;
		margin-top: 4rpx;
		font-size: 20rpx;
		color: $uh-ink-3;
	}

	.empty-tip {
		padding: 48rpx 0;
		font-size: 24rpx;
		color: $uh-ink-3;
		text-align: center;
	}

	.order-item {
		padding: 24rpx 0;
		border-top: 1rpx solid #f0f1f5;
	}

	.order-item:first-of-type {
		border-top: none;
	}

	.order-head {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
	}

	.order-name {
		flex: 1;
		font-size: 28rpx;
		font-weight: 600;
		color: $uh-ink-1;
	}

	.order-amount {
		margin-left: 16rpx;
		font-size: 30rpx;
		font-weight: 700;
		color: #f2542d;
	}

	.order-meta {
		display: flex;
		align-items: center;
		margin-top: 8rpx;
		font-size: 21rpx;
		color: $uh-ink-3;
	}

	.order-type {
		padding: 2rpx 12rpx;
		border-radius: 6rpx;
		background: #f2f4fa;
		color: #7b81a0;
	}

	.order-qty {
		margin-left: 10rpx;
	}

	.order-time {
		margin-left: auto;
	}

	.order-status-row {
		margin-top: 12rpx;
	}

	.order-status {
		font-size: 22rpx;
		line-height: 1.6;
	}

	.order-status--done {
		color: #17a673;
	}

	.order-status--pending {
		color: #d1851a;
	}

	.order-status--alert {
		color: #e04b4b;
	}

	.order-status--muted {
		color: $uh-ink-3;
	}

	.order-item--alert {
		background: #fff8f8;
	}

	.order-foot {
		display: flex;
		align-items: center;
		justify-content: space-between;
		margin-top: 14rpx;
	}

	.order-no {
		font-size: 20rpx;
		color: #b6bac7;
	}

	.order-action {
		padding: 4rpx 18rpx;
		border-radius: 999rpx;
		border: 1rpx solid $uh-accent;
		font-size: 20rpx;
		color: $uh-accent;
	}

	.load-text {
		padding-top: 20rpx;
		font-size: 22rpx;
		color: #b6bac7;
		text-align: center;
	}

	.footer-tip {
		padding: 0 36rpx;
		font-size: 21rpx;
		line-height: 1.7;
		color: #a1a6b8;
	}
@import '@/common/styles/redesign/maple-sub.scss';
@import '@/common/styles/redesign/pagesA-my-orders-my-orders-themes.scss';
</style>
