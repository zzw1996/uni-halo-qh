<template>
	<view :class="skinClass" class="app-page points-history-theme">
		<view v-if="loading !== 'success'" class="loading-wrap">
			<tm-skeleton model="listAvatr"></tm-skeleton>
			<tm-skeleton model="listAvatr"></tm-skeleton>
			<tm-skeleton model="listAvatr"></tm-skeleton>
		</view>
		<block v-else>
			<view class="summary-card">
				<view class="summary-balance">
					<text class="summary-label">当前积分</text>
					<text class="summary-value">{{ balance }}</text>
				</view>
				<view class="summary-meta">
					<text>今日已获得 {{ todayEarned }} 积分</text>
					<text v-if="localMode" class="summary-local">本地模式</text>
				</view>
			</view>

			<view v-if="dataList.length === 0" class="empty">
				<tm-empty icon="icon-shiliangzhinengduixiang-" label="暂无积分记录"></tm-empty>
			</view>
			<block v-else>
				<view
					v-for="(item, index) in dataList"
					:key="item.id + index"
					class="history-item"
				>
					<view class="history-main">
						<text class="history-title">{{ item.actionLabel }}</text>
						<text v-if="item.description" class="history-desc">{{ item.description }}</text>
						<text class="history-time">{{ item.createTimeText || (item.createTime | formatTime) }}</text>
					</view>
					<view class="history-side">
						<text class="history-points" :class="item.points >= 0 ? 'is-plus' : 'is-minus'">
							{{ item.pointsText }}
						</text>
						<text v-if="item.balance != null" class="history-balance">余额 {{ item.balance }}</text>
					</view>
				</view>
				<view class="load-text">{{ loadMoreText }}</view>
			</block>
			<tm-flotbutton @click="fnToTopPage" color="indigo" size="m" icon="icon-angle-up"></tm-flotbutton>
		</block>
	</view>
</template>

<script>
	import tmSkeleton from '@/tm-vuetify/components/tm-skeleton/tm-skeleton.vue';
	import loadMoreMixin from '@/common/mixins/loadMore.js';
	import tmFlotbutton from '@/tm-vuetify/components/tm-flotbutton/tm-flotbutton.vue';
	import tmEmpty from '@/tm-vuetify/components/tm-empty/tm-empty.vue';
	import { checkHasWxLogin } from '@/utils/auth.js';
	import { formatPointsHistoryItem, isPointsEnabled, fetchPointsOverview, fetchPointsHistory, syncPointsAfterLogin, normalizePointsApiError, getPointsApiMode } from '@/utils/points.js';

	export default {
		mixins: [loadMoreMixin],
		components: {
			tmSkeleton,
			tmFlotbutton,
			tmEmpty
		},
		data() {
			return {
				loading: 'loading',
				balance: 0,
				todayEarned: 0,
				localMode: false,
				queryParams: {
					size: 15,
					page: 0
				},
				hasNext: false,
				dataList: [],
				isLoadMore: false,
				loadMoreText: ''
			};
		},
		onLoad() {
			if (!isPointsEnabled()) {
				uni.showToast({ title: '积分功能未开启', icon: 'none' });
				setTimeout(() => uni.navigateBack(), 800);
				return;
			}
			if (!checkHasWxLogin()) {
				uni.showToast({ title: '请先登录', icon: 'none' });
				setTimeout(() => uni.navigateBack(), 800);
				return;
			}
			this.fnGetData(true);
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
			fnToTopPage() {
				uni.pageScrollTo({ scrollTop: 0, duration: 300 });
			},
			parseBody(res) {
				if (res?.statusCode && res.statusCode !== 200) {
					throw { error: res?.data?.error || res?.data?.detail || '加载失败' };
				}
				return res?.data || res || {};
			},
			async fnGetData(refreshOverview) {
				if (!this.isLoadMore) {
					this.loading = 'loading';
				}
				this.loadMoreText = '加载中...';
				try {
					if (refreshOverview) {
						const overview = await syncPointsAfterLogin(this.$httpApi.v2);
						if (overview) {
							this.balance = Number(overview.balance || 0);
							this.todayEarned = Number(overview.todayEarned || 0);
							this.localMode = !!overview.localMode || getPointsApiMode() === 'local';
						}
					}

					const body = await fetchPointsHistory(this.$httpApi.v2, {
						page: this.queryParams.page,
						size: this.queryParams.size
					});
					const items = (body.items || []).map(formatPointsHistoryItem).filter(Boolean);
					this.hasNext = body.hasNext === true;
					if (this.isLoadMore) {
						this.dataList = this.dataList.concat(items);
					} else {
						this.dataList = items;
					}
					// 兜底排序：时间倒序（前端防御，不依赖后端顺序）
					this.dataList.sort((a, b) => {
						const ta = String(a.createTimeText || a.createTime || '');
						const tb = String(b.createTimeText || b.createTime || '');
						return tb.localeCompare(ta);
					});
					this.loadMoreText = this.hasNext ? '上拉加载更多' : '没有更多了';
					this.loading = 'success';
				} catch (err) {
					this.loading = 'success';
					this.loadMoreText = '';
					uni.showToast({ title: normalizePointsApiError(err), icon: 'none' });
				} finally {
					uni.stopPullDownRefresh();
					this.fnLoadMoreDone();
				}
			}
		}
	};
</script>

<style scoped lang="scss">
	.app-page {
		min-height: 100vh;
		padding-top: 1rpx;
		background: $uh-fill;
		padding-bottom: 40rpx;
	}

	.loading-wrap {
		padding: 24rpx;
	}

	.summary-card {
		margin: 24rpx 28rpx 16rpx;
		padding: 32rpx 28rpx;
		border-radius: 20rpx;
		background-image: $uh-grad-accent;
		color: #fff;
	}

	.summary-balance {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
	}

	.summary-label {
		font-size: 28rpx;
		opacity: 0.92;
	}

	.summary-value {
		font-size: 56rpx;
		font-weight: 700;
		line-height: 1.1;
	}

	.summary-meta {
		margin-top: 12rpx;
		font-size: 24rpx;
		opacity: 0.86;
		display: flex;
		align-items: center;
		justify-content: space-between;
	}

	.summary-local {
		padding: 4rpx 12rpx;
		border-radius: 999rpx;
		background: rgba(255, 255, 255, 0.18);
		font-size: 22rpx;
	}

	.history-item {
		display: flex;
		align-items: flex-start;
		justify-content: space-between;
		margin: 0 28rpx 16rpx;
		padding: 28rpx;
		border-radius: 16rpx;
		background: $uh-card;
	}

	.history-main {
		flex: 1;
		min-width: 0;
		padding-right: 20rpx;
	}

	.history-title {
		display: block;
		font-size: 30rpx;
		font-weight: 600;
		color: $uh-ink-1;
	}

	.history-desc {
		display: block;
		margin-top: 8rpx;
		font-size: 24rpx;
		color: $uh-ink-3;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.history-time {
		display: block;
		margin-top: 10rpx;
		font-size: 22rpx;
		color: $uh-ink-4;
	}

	.history-side {
		flex-shrink: 0;
		text-align: right;
	}

	.history-points {
		display: block;
		font-size: 34rpx;
		font-weight: 700;

		&.is-plus {
			color: $uh-warning;
		}

		&.is-minus {
			color: $uh-danger;
		}
	}

	.history-balance {
		display: block;
		margin-top: 8rpx;
		font-size: 22rpx;
		color: $uh-ink-3;
	}

	.empty {
		padding-top: 80rpx;
	}

	.load-text {
		padding: 24rpx 0 40rpx;
		text-align: center;
		font-size: 24rpx;
		color: $uh-ink-3;
	}
@import '@/common/styles/redesign/points-history-themes.scss';
</style>
