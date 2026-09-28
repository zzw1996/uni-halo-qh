<template>
	<view :class="skinClass" class="app-page">
		<view v-if="loading !== 'success'" class="loading-wrap">
			<tm-skeleton model="listAvatr"></tm-skeleton>
			<tm-skeleton model="listAvatr"></tm-skeleton>
		</view>
		<block v-else>
			<!-- 汇总：抽奖次数与累计消耗都来自后端按 action 过滤后的统计，
			     不是「次数 × 单价」推算，所以站长改过消耗积分后历史数字依然准确 -->
			<view class="summary-card">
				<view class="summary-row">
					<text class="summary-label">共抽奖</text>
					<text class="summary-value">{{ drawTotal }}<text class="summary-unit">次</text></text>
				</view>
				<view class="summary-row">
					<text class="summary-label">累计消耗</text>
					<text class="summary-value">{{ drawCostTotal }}<text class="summary-unit">积分</text></text>
				</view>
			</view>

			<!-- 中奖信息：唯一需要用户操作的状态（待填写收货信息）在这里提醒，也含物流查询 -->
			<view class="section-card">
				<view class="section-head">
					<text class="section-title">我的中奖礼品</text>
					<text class="section-sub">点击查看发放状态与物流信息</text>
				</view>
				<view v-if="myClaims.length === 0" class="empty-tip">还没有中奖记录，去开一盒试试手气～</view>
				<view
					v-for="(claim, idx) in myClaims"
					:key="idx"
					class="claim-item"
					:class="{ 'is-pending': claim.status === 'pending' }"
					hover-class="claim-item-hover"
					@click="fnGoClaim(idx)"
				>
					<text class="claim-emoji">{{ claim.emoji || '🎁' }}</text>
					<view class="claim-main">
						<text class="claim-name">{{ claim.prizeName }}</text>
						<text class="claim-time">{{ formatTimeSafe(claim.winTime) }}</text>
						<text v-if="claim.status === 'shipped'" class="claim-express">
							{{ claim.expressCompany || '快递' }} {{ claim.expressNo || '（无物流单号）' }} · {{ formatTimeSafe(claim.shipTime) }} 发货
						</text>
					</view>
					<text class="claim-tag" :class="fnLuckyTagClass(claim.status)">{{ fnLuckyStatusText(claim.status) }}</text>
					<text class="claim-arrow">›</text>
				</view>
			</view>

			<!-- 抽奖消耗明细：按动作码 lucky_draw 过滤，分页由后端保证准确 -->
			<view class="section-card">
				<view class="section-head">
					<text class="section-title">抽奖消耗明细</text>
					<text class="section-sub">每开一盒的积分扣减记录</text>
				</view>
				<view v-if="dataList.length === 0" class="empty-tip">还没有抽奖消耗记录</view>
				<view v-for="(item, index) in dataList" :key="item.id + index" class="draw-item">
					<view class="draw-main">
						<text class="draw-title">{{ item.description || item.actionLabel || '积分抽奖' }}</text>
						<text class="draw-time">{{ item.createTimeText || formatTimeSafe(item.createTime) }}</text>
					</view>
					<view class="draw-side">
						<text class="draw-points">{{ item.pointsText }}</text>
						<text v-if="item.balance != null" class="draw-balance">余额 {{ item.balance }}</text>
					</view>
				</view>
				<view v-if="dataList.length" class="load-text">{{ loadMoreText }}</view>
			</view>

			<view class="footer-tip">
				<text>累计消耗只统计抽奖流水；全部积分收支可在「我的积分 → 明细」中查看。</text>
			</view>
		</block>
	</view>
</template>

<script>
	import tmSkeleton from '@/tm-vuetify/components/tm-skeleton/tm-skeleton.vue';
	import loadMoreMixin from '@/common/mixins/loadMore.js';
	import { checkHasWxLogin } from '@/utils/auth.js';
	import { formatPointsHistoryItem, fetchPointsHistory, normalizePointsApiError } from '@/utils/points.js';
	import { formatTimeSafe } from '@/utils/time.js';

	/** 抽奖消耗的流水动作码（与后端 LuckyDrawServiceImpl.ACTION 一致） */
	const LUCKY_DRAW_ACTION = 'lucky_draw';

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
				hasNext: false,
				isLoadMore: false,
				loadMoreText: '',
				/** 抽奖消耗明细（action=lucky_draw） */
				dataList: [],
				drawTotal: 0,
				drawCostTotal: 0,
				/** 中奖礼品（发放进度与物流） */
				myClaims: []
			};
		},
		onLoad() {
			if (!checkHasWxLogin()) {
				uni.showToast({ title: '请先登录', icon: 'none' });
				setTimeout(() => uni.navigateBack(), 800);
				return;
			}
			this.fnGetData(true);
		},
		onShow() {
			// 从填写收货信息页返回时刷新发放状态
			this.fnLoadLuckyClaims();
		},
		onPullDownRefresh() {
			this.isLoadMore = false;
			this.fnLoadMoreDone();
			this.queryParams.page = 0;
			this.fnGetData(true);
		},
		onReachBottom() {
			this.fnLoadMore(this.hasNext, () => {
				this.queryParams.page += 1;
				this.isLoadMore = true;
				this.fnGetData(false);
			});
		},
		methods: {
			/** 时间格式化（兼容 ISO 字符串 / 秒级或毫秒级时间戳；异常值显示「—」） */
			formatTimeSafe(value) {
				return formatTimeSafe(value);
			},
			async fnGetData(refresh) {
				if (!this.isLoadMore) {
					this.loading = 'loading';
				}
				this.loadMoreText = '加载中...';
				try {
					const body = await fetchPointsHistory(this.$httpApi.v2, {
						action: LUCKY_DRAW_ACTION,
						page: this.queryParams.page,
						size: this.queryParams.size
					});
					const items = (body.items || []).map(formatPointsHistoryItem).filter(Boolean);
					this.hasNext = body.hasNext === true;
					this.dataList = this.isLoadMore ? this.dataList.concat(items) : items;
					// 汇总只在刷新时更新（加载更多时分页数据会重复累加）
					if (refresh) {
						this.drawTotal = Number(body.total || 0);
						// 后端返回的是含符号的积分合计（消耗为负），页面展示成消耗额度更直观
						this.drawCostTotal = Math.abs(Number(body.totalPoints || 0));
					}
					this.loadMoreText = this.hasNext ? '上拉加载更多' : '没有更多了';
					if (refresh) {
						await this.fnLoadLuckyClaims();
					}
					this.loading = 'success';
				} catch (err) {
					this.loading = 'success';
					this.loadMoreText = '';
					uni.showToast({ title: normalizePointsApiError(err), icon: 'none' });
				} finally {
					uni.stopPullDownRefresh();
					this.fnLoadMoreDone();
				}
			},
			/** 中奖礼品列表，失败静默 */
			async fnLoadLuckyClaims() {
				try {
					const res = await this.$httpApi.v2.getMyLuckyClaims();
					const raw = Array.isArray(res) ? res : (res?.data || res || {});
					const list = Array.isArray(raw) ? raw : (Array.isArray(raw.items) ? raw.items : []);
					this.myClaims = list.map((item) => {
						const normalized = Object.assign({}, item);
						// 兼容新版 winningId / 旧接口 id 两种单号字段
						if (!normalized.winningId && item && item.id) {
							normalized.winningId = item.id;
						}
						return normalized;
					});
				} catch (err) {
					// 静默：拿不到中奖记录不影响消耗明细
					this.myClaims = [];
				}
			},
			/** 中奖单状态文案：待填写 / 待发货 / 已发货 */
			fnLuckyStatusText(status) {
				if (status === 'shipped') return '已发货';
				if (status === 'submitted') return '待发货';
				return '待填写';
			},
			/** 中奖单状态标签样式 */
			fnLuckyTagClass(status) {
				if (status === 'shipped') return 'claim-tag-done';
				if (status === 'submitted') return 'claim-tag-sent';
				return 'claim-tag-pending';
			},
			fnGoClaim(idx) {
				// 用索引取条目，避免编译产物事件参数解析问题（传对象会被解析成 null）
				const claim = this.myClaims[idx];
				if (!claim || !claim.winningId) {
					uni.showToast({ title: '中奖单数据异常，请下拉重试', icon: 'none' });
					return;
				}
				uni.navigateTo({
					url: `/pagesA/lucky-claim/lucky-claim?winningId=${encodeURIComponent(claim.winningId)}&name=${encodeURIComponent(claim.prizeName || '')}&emoji=${encodeURIComponent(claim.emoji || '🎁')}&status=${encodeURIComponent(claim.status || '')}`,
					fail: () => uni.showToast({ title: '页面打开失败，请重试', icon: 'none' })
				});
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
		background: linear-gradient(135deg, #ffb347 0%, #ff8a3d 100%);
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
		margin-bottom: 6rpx;
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
		padding: 24rpx 0 8rpx;
		font-size: 24rpx;
		color: #b0b5c0;
	}

	/* ===== 中奖礼品 ===== */
	.claim-item {
		display: flex;
		align-items: center;
		padding: 20rpx 0;
		border-bottom: 2rpx solid $uh-fill;
	}

	.claim-item:last-child {
		border-bottom: none;
		padding-bottom: 0;
	}

	.claim-emoji {
		font-size: 40rpx;
		margin-right: 16rpx;
	}

	.claim-main {
		flex: 1;
		min-width: 0;
	}

	.claim-name {
		display: block;
		font-size: 26rpx;
		font-weight: 600;
		color: $uh-ink-1;
	}

	.claim-time {
		display: block;
		margin-top: 4rpx;
		font-size: 20rpx;
		color: $uh-ink-3;
	}

	.claim-express {
		display: block;
		margin-top: 4rpx;
		font-size: 20rpx;
		color: #3a9e3a;
	}

	.claim-arrow {
		font-size: 30rpx;
		color: $uh-ink-4;
		margin-left: 8rpx;
	}

	/* 待填写是唯一需要用户操作的状态，用底色强调 */
	.claim-item.is-pending {
		background: #fff7e6;
		border-radius: 12rpx;
		padding: 20rpx 12rpx;
	}

	.claim-item-hover {
		background: #f0f2f7;
	}

	.claim-tag {
		flex-shrink: 0;
		padding: 6rpx 16rpx;
		border-radius: 999rpx;
		font-size: 20rpx;
		margin-left: 8rpx;
	}

	.claim-tag-pending {
		background: #ffefe0;
		color: #e8862b;
	}

	.claim-tag-sent {
		background: #e8f1ff;
		color: #3a6ee0;
	}

	.claim-tag-done {
		background: #e6f7e6;
		color: #3a9e3a;
	}

	/* ===== 消耗明细 ===== */
	.draw-item {
		display: flex;
		align-items: flex-start;
		justify-content: space-between;
		padding: 20rpx 0;
		border-bottom: 2rpx solid $uh-fill;
	}

	.draw-item:last-child {
		border-bottom: none;
	}

	.draw-main {
		flex: 1;
		min-width: 0;
		padding-right: 20rpx;
	}

	.draw-title {
		display: block;
		font-size: 26rpx;
		color: $uh-ink-1;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.draw-time {
		display: block;
		margin-top: 6rpx;
		font-size: 21rpx;
		color: $uh-ink-4;
	}

	.draw-side {
		flex-shrink: 0;
		text-align: right;
	}

	.draw-points {
		display: block;
		font-size: 30rpx;
		font-weight: 700;
		color: $uh-danger;
	}

	.draw-balance {
		display: block;
		margin-top: 6rpx;
		font-size: 21rpx;
		color: $uh-ink-3;
	}

	.load-text {
		padding: 20rpx 0 8rpx;
		text-align: center;
		font-size: 24rpx;
		color: $uh-ink-3;
	}

	.footer-tip {
		margin: 8rpx 28rpx 0;
		padding: 20rpx 24rpx;
		border-radius: 12rpx;
		background: #fff7e6;
	}

	.footer-tip text {
		font-size: 21rpx;
		line-height: 1.7;
		color: #b3800a;
	}
</style>
