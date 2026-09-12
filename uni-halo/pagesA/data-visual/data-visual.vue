<template>
	<view class="app-page">
		<!-- 高级版卡片 -->
		<view class="advanced-card">
			<view class="advanced-head">
				<text class="advanced-title">数据看板高级版</text>
				<tm-tags v-if="advancedUnlocked" color="bg-gradient-light-blue-accent" :shadow="0" size="s" model="fill">今日已解锁</tm-tags>
			</view>
			<text class="advanced-tip">{{ advancedUnlocked ? '已解锁：完整数据导出（含近 7 日趋势与热榜数据）' : '解锁当日高级功能：完整数据导出（30 积分/次，每日 1 次）' }}</text>
			<view class="advanced-actions">
				<tm-button v-if="!advancedUnlocked" size="m" theme="primary" :loading="unlocking" @click="fnUnlockAdvanced">解锁高级版</tm-button>
				<tm-button v-else size="m" theme="primary" @click="fnCopySummary">导出完整数据</tm-button>
			</view>
		</view>

		<view v-if="loading === 'loading'" class="loading-wrap">
			<tm-skeleton model="listAvatr"></tm-skeleton>
			<tm-skeleton model="listAvatr"></tm-skeleton>
			<tm-skeleton model="listAvatr"></tm-skeleton>
		</view>
		<view v-else-if="loading === 'error'" class="empty-wrap">
			<tm-empty icon="icon-wind-cry" label="数据加载失败"></tm-empty>
			<tm-button class="mt-16" size="s" theme="grey" :fllowTheme="false" @click="fnGetData">重新加载</tm-button>
			<text class="empty-tip">若仍失败，请确认插件已升级到 v1.0.75+</text>
		</view>
		<view v-else>
			<view class="stat-grid">
				<view class="stat-card" v-for="(card, index) in statCards" :key="index">
					<text class="stat-value">{{ card.value }}</text>
					<text class="stat-label">{{ card.label }}</text>
				</view>
			</view>

			<view class="section-card">
				<view class="section-head">
					<text class="section-title">近 7 日发文趋势</text>
				</view>
				<view class="trend-row">
					<view v-for="(item, index) in trendBars" :key="index" class="trend-col">
						<text class="trend-count">{{ item.count }}</text>
						<view class="trend-bar-wrap">
							<view class="trend-bar" :style="{ height: item.height }"></view>
						</view>
						<text class="trend-date">{{ item.short }}</text>
					</view>
				</view>
			</view>

			<view class="section-card">
				<view class="section-head">
					<text class="section-title">热榜 TOP5</text>
				</view>
				<view v-if="!summary || !summary.topArticles || !summary.topArticles.length" class="empty-tip">暂无数据</view>
				<view
					v-for="(item, index) in summary && summary.topArticles ? summary.topArticles : []"
					:key="item.name"
					class="rank-item"
					@click="fnToArticle(item)"
				>
					<text class="rank-no" :class="{ 'is-top': index < 3 }">{{ index + 1 }}</text>
					<text class="rank-title">{{ item.title || item.name }}</text>
					<text class="rank-visit">{{ item.visit }} 阅读</text>
				</view>
			</view>

			<view class="footer-tip">
				<text>统计由 UniHalo 插件实时计算（文章/分类/标签/评论/访问/点赞/微信用户/积分发放），不依赖第三方插件。</text>
				<text>高级版解锁后可导出完整数据（JSON 文本），当日有效。</text>
			</view>
		</view>
	</view>
</template>

<script>
	import tmSkeleton from '@/tm-vuetify/components/tm-skeleton/tm-skeleton.vue';
	import tmEmpty from '@/tm-vuetify/components/tm-empty/tm-empty.vue';
	import tmButton from '@/tm-vuetify/components/tm-button/tm-button.vue';
	import tmTags from '@/tm-vuetify/components/tm-tags/tm-tags.vue';
	import { exchangePointsItem } from '@/utils/points.js';

	export default {
		components: {
			tmSkeleton,
			tmEmpty,
			tmButton,
			tmTags
		},
		data() {
			return {
				loading: 'loading',
				summary: null,
				advancedUnlocked: false,
				unlocking: false
			};
		},
		computed: {
			statCards() {
				const s = this.summary || {};
				return [
					{ label: '文章', value: s.totalPosts || 0 },
					{ label: '分类', value: s.totalCategories || 0 },
					{ label: '标签', value: s.totalTags || 0 },
					{ label: '评论', value: s.totalComments || 0 },
					{ label: '总访问', value: s.totalVisits || 0 },
					{ label: '总点赞', value: s.totalUpvotes || 0 },
					{ label: '用户', value: s.totalWxUsers || 0 },
					{ label: '累计积分', value: s.totalPointsIssued || 0 }
				];
			},
			trendBars() {
				const trend = (this.summary && this.summary.weekTrend) || [];
				const max = Math.max(1, ...trend.map((item) => Number(item.count) || 0));
				return trend.map((item) => ({
					count: Number(item.count) || 0,
					short: String(item.date || '').slice(5),
					height: Math.max(8, Math.round(((Number(item.count) || 0) / max) * 160)) + 'rpx'
				}));
			}
		},
		async onReady() {
			await this.fnInitAdvanced();
			this.fnGetData();
		},
		onPullDownRefresh() {
			this.fnGetData().finally(() => uni.stopPullDownRefresh());
		},
		methods: {
			async fnGetData() {
				this.loading = 'loading';
				try {
					const res = await this.$httpApi.v2.getDashboardSummary();
					const body = res?.data || res || {};
					this.summary = body;
					this.loading = 'success';
				} catch (err) {
					this.loading = 'error';
				}
			},
			fnToArticle(item) {
				if (!item?.name) return;
				uni.navigateTo({
					url: '/pagesA/article-detail/article-detail?name=' + encodeURIComponent(item.name),
					animationType: 'slide-in-right'
				});
			},
			async fnInitAdvanced() {
				const now = new Date();
				const key = `dashboard.advanced.${now.getFullYear()}-${now.getMonth() + 1}-${now.getDate()}`;
				if (uni.getStorageSync(key) === '1') {
					this.advancedUnlocked = true;
					return;
				}
				try {
					const api = this.$httpApi?.v2;
					if (api?.getExchangeBalance) {
						const res = await api.getExchangeBalance();
						const body = res?.data || res || {};
						const items = Array.isArray(body?.items) ? body.items : [];
						const unlocked = body?.dashboardUnlockedToday === true
							|| items.some((item) => item.key === 'dashboard_advanced' && Number(item.todayCount || 0) > 0);
						if (unlocked) {
							this.advancedUnlocked = true;
							uni.setStorageSync(key, '1');
						}
					}
				} catch (e) {
					// 兑换接口未部署时忽略
				}
			},
			async fnUnlockAdvanced() {
				if (this.unlocking) return;
				this.unlocking = true;
				try {
					const result = await exchangePointsItem(this.$httpApi.v2, 'dashboard_advanced');
					if (!result.ok) {
						uni.showToast({ icon: 'none', title: result.reason || '解锁失败' });
						return;
					}
					this.advancedUnlocked = true;
					const now = new Date();
					uni.setStorageSync(`dashboard.advanced.${now.getFullYear()}-${now.getMonth() + 1}-${now.getDate()}`, '1');
					uni.showToast({ icon: 'success', title: '已解锁今日高级版' });
				} finally {
					this.unlocking = false;
				}
			},
			fnCopySummary() {
				const text = JSON.stringify(this.summary || {}, null, 2);
				uni.setClipboardData({
					data: text,
					success: () => uni.showToast({ icon: 'none', title: '已复制完整数据' })
				});
			}
		}
	};
</script>

<style scoped lang="scss">
	.app-page {
		min-height: 100vh;
		background: #f5f6fa;
		padding: 24rpx 24rpx 48rpx;
	}

	.loading-wrap {
		padding: 24rpx;
	}

	.empty-wrap {
		display: flex;
		flex-direction: column;
		align-items: center;
		padding-top: 80rpx;
	}

	.empty-tip {
		margin-top: 16rpx;
		font-size: 22rpx;
		color: #909399;
		text-align: center;
	}

	.advanced-card {
		margin-bottom: 24rpx;
		padding: 24rpx 28rpx;
		border-radius: 16rpx;
		background: linear-gradient(135deg, #f0f8ff 0%, #ffffff 100%);
		border: 1rpx solid #b9dcff;
	}

	.advanced-head {
		display: flex;
		align-items: center;
		gap: 12rpx;
	}

	.advanced-title {
		font-size: 28rpx;
		font-weight: 700;
		color: #303133;
	}

	.advanced-tip {
		display: block;
		margin-top: 8rpx;
		font-size: 22rpx;
		line-height: 1.6;
		color: #909399;
	}

	.advanced-actions {
		margin-top: 16rpx;
	}

	.stat-grid {
		display: flex;
		flex-wrap: wrap;
		justify-content: space-between;
	}

	.stat-card {
		width: 23%;
		background: #fff;
		border-radius: 16rpx;
		padding: 24rpx 0;
		margin-bottom: 20rpx;
		text-align: center;
	}

	.stat-value {
		display: block;
		font-size: 34rpx;
		font-weight: 800;
		color: #03a9f4;
	}

	.stat-label {
		display: block;
		margin-top: 6rpx;
		font-size: 22rpx;
		color: #909399;
	}

	.section-card {
		background: #fff;
		border-radius: 16rpx;
		padding: 24rpx 28rpx;
		margin-bottom: 24rpx;
	}

	.section-head {
		margin-bottom: 16rpx;
	}

	.section-title {
		font-size: 28rpx;
		font-weight: 700;
		color: #303133;
	}

	.trend-row {
		display: flex;
		align-items: flex-end;
		justify-content: space-between;
		height: 240rpx;
	}

	.trend-col {
		flex: 1;
		display: flex;
		flex-direction: column;
		align-items: center;
	}

	.trend-count {
		font-size: 20rpx;
		color: #909399;
	}

	.trend-bar-wrap {
		height: 180rpx;
		display: flex;
		align-items: flex-end;
		margin-top: 4rpx;
	}

	.trend-bar {
		width: 36rpx;
		border-radius: 8rpx 8rpx 0 0;
		background: linear-gradient(180deg, #74b9ff 0%, #03a9f4 100%);
	}

	.trend-date {
		margin-top: 8rpx;
		font-size: 20rpx;
		color: #c0c4cc;
	}

	.rank-item {
		display: flex;
		align-items: center;
		padding: 14rpx 0;
		border-bottom: 1rpx solid #f5f6fa;
	}

	.rank-no {
		width: 40rpx;
		font-size: 24rpx;
		font-weight: 700;
		color: #c0c4cc;

		&.is-top {
			color: #ff9800;
		}
	}

	.rank-title {
		flex: 1;
		min-width: 0;
		font-size: 26rpx;
		color: #303133;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.rank-visit {
		margin-left: 16rpx;
		font-size: 22rpx;
		color: #909399;
	}

	.footer-tip {
		margin-top: 8rpx;
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
</style>
