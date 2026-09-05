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
				<text class="balance-sub">发布券 {{ publishTickets }} 张 · 今日可再兑换 {{ remaining >= 0 ? remaining : '-' }} 次</text>
			</view>

			<view class="tool-card">
				<view class="tool-card-head">
					<view class="tool-card-title-row">
						<text class="tool-card-title">瞬间发布券</text>
						<tm-tags color="bg-gradient-orange-accent" :shadow="0" size="s" model="fill"
							>每日免费 {{ freeQuota }} 条</tm-tags
						>
					</view>
					<text class="tool-card-tip">瞬间发布每天免费 {{ freeQuota }} 条，超出后每条需消耗 1 张发布券（{{ publishCost }} 积分/张，每日最多 {{ publishLimit }} 张）</text>
				</view>
				<view class="tool-actions">
					<tm-button
						size="m"
						theme="primary"
						:loading="exchanging === 'publish_ticket'"
						@click="fnExchange('publish_ticket')"
					>{{ remaining > 0 ? `兑换（${publishCost} 积分）` : '今日已兑完' }}</tm-button>
				</view>
			</view>

			<view class="tool-card">
				<view class="tool-card-head">
					<view class="tool-card-title-row">
						<text class="tool-card-title">数据看板高级版</text>
						<tm-tags v-if="dashboardUnlockedToday" color="bg-gradient-light-blue-accent" :shadow="0" size="s" model="fill"
							>今日已解锁</tm-tags
						>
					</view>
					<text class="tool-card-tip">解锁当日数据看板高级版：30 日趋势与数据摘要导出（{{ dashboardCost }} 积分/次，每日 1 次，当天有效）</text>
				</view>
				<view class="tool-actions">
					<tm-button
						size="m"
						theme="primary"
						:loading="exchanging === 'dashboard_advanced'"
						@click="fnExchange('dashboard_advanced')"
					>{{ dashboardUnlockedToday ? '今日已解锁' : `解锁（${dashboardCost} 积分）` }}</tm-button>
				</view>
			</view>

			<view class="footer-tip">
				<text>积分仅限站内权益兑换（发布券、看板高级版等），不可提现、不可转让、不可兑换现金或实物。</text>
				<text>兑换记录可在「积分明细」中查看；兑换成功后立即生效。</text>
			</view>
		</block>
	</view>
</template>

<script>
	import tmSkeleton from '@/tm-vuetify/components/tm-skeleton/tm-skeleton.vue';
	import tmButton from '@/tm-vuetify/components/tm-button/tm-button.vue';
	import tmTags from '@/tm-vuetify/components/tm-tags/tm-tags.vue';
	import { checkHasWxLogin } from '@/utils/auth.js';
	import { fetchExchangeBalance, exchangePointsItem, emitPointsUpdated } from '@/utils/points.js';

	export default {
		components: {
			tmSkeleton,
			tmButton,
			tmTags
		},
		data() {
			return {
				loading: 'loading',
				balance: 0,
				publishTickets: 0,
				freeQuota: 3,
				remaining: 5,
				publishCost: 50,
				publishLimit: 5,
				dashboardCost: 30,
				dashboardUnlockedToday: false,
				exchanging: ''
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
				try {
					const status = await fetchExchangeBalance(this.$httpApi.v2);
					if (status.ok) {
						this.balance = status.balance != null ? status.balance : 0;
						this.publishTickets = status.publishTickets || 0;
						this.freeQuota = status.freeQuota || 3;
						this.remaining = status.remaining != null ? status.remaining : 5;
						this.dashboardUnlockedToday = status.dashboardUnlockedToday === true;
					}
					// 兑换消耗读取插件配置（rules.exchange），按默认展示即可
					const exchange = this.haloConfigs?.pointsConfig?.rules?.exchange || {};
					this.publishCost = Number(exchange?.publishTicket?.points || 50);
					this.publishLimit = Number(exchange?.publishTicket?.dailyMaxCount || 5);
					this.dashboardCost = Number(exchange?.dashboardAdvanced?.points || 30);
					this.loading = 'success';
				} catch (err) {
					this.loading = 'success';
					uni.showToast({ title: err?.error || '加载失败，请稍后重试', icon: 'none' });
				}
			},
			async fnExchange(item) {
				if (this.exchanging) return;
				this.exchanging = item;
				try {
					const result = await exchangePointsItem(this.$httpApi.v2, item);
					if (!result.ok) {
						uni.showToast({ title: result.reason || '兑换失败', icon: 'none' });
						return;
					}
					uni.showToast({ title: result.message || '兑换成功', icon: 'success' });
					if (result.balance != null) {
						this.balance = result.balance;
						emitPointsUpdated({ balance: result.balance });
					}
					if (result.publishTickets != null) {
						this.publishTickets = result.publishTickets;
					}
					if (item === 'dashboard_advanced') {
						this.dashboardUnlockedToday = true;
					}
					this.remaining -= 1;
					await this.fnLoad();
				} finally {
					this.exchanging = '';
				}
			}
		},
		computed: {
			haloConfigs() {
				return this.$tm.vx.getters().getConfigs;
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

	.balance-card {
		padding: 32rpx 28rpx;
		border-radius: 20rpx;
		background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
		color: #fff;
		margin-bottom: 24rpx;
	}

	.balance-label {
		display: block;
		font-size: 26rpx;
		opacity: 0.9;
	}

	.balance-value {
		display: block;
		margin-top: 8rpx;
		font-size: 56rpx;
		font-weight: 700;
	}

	.balance-sub {
		display: block;
		margin-top: 8rpx;
		font-size: 22rpx;
		opacity: 0.85;
	}

	.tool-card {
		background: #fff;
		border-radius: 16rpx;
		padding: 28rpx;
		margin-bottom: 24rpx;
	}

	.tool-card-head {
		margin-bottom: 20rpx;
	}

	.tool-card-title-row {
		display: flex;
		align-items: center;
		gap: 12rpx;
	}

	.tool-card-title {
		font-size: 30rpx;
		font-weight: 700;
		color: #303133;
	}

	.tool-card-tip {
		display: block;
		margin-top: 8rpx;
		font-size: 22rpx;
		line-height: 1.6;
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
