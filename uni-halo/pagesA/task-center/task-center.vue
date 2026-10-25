<template>
	<view :class="skinClass" class="theme-page-pagesA-task-center-task-center app-page">
		<!-- 未登录 -->
		<view v-if="!isLoggedIn" class="login-gate flex flex-center flex-col">
			<tm-empty icon="icon-user" label="登录后查看每日任务"></tm-empty>
			<tm-button class="mt-24" theme="bg-gradient-indigo-accent" :shadow="4" @click="loginDialogShow = true">
				微信登录
			</tm-button>
			<tm-button class="mt-16" plan theme="grey" :fllowTheme="false" :shadow="0" @click="fnBackHome">返回首页</tm-button>
		</view>
		<template v-else>
			<view v-if="loading === 'loading'" class="loading-wrap">
				<tm-skeleton model="listAvatr"></tm-skeleton>
				<tm-skeleton model="listAvatr"></tm-skeleton>
			</view>
			<view v-else-if="loading === 'error'" class="content-empty flex flex-center flex-col" style="min-height: 60vh;">
				<tm-empty icon="icon-shiliangzhinengduixiang-" label="加载失败"></tm-empty>
				<tm-button class="mt-24" plan theme="grey" :fllowTheme="false" :shadow="0" @click="fnLoad">重新加载</tm-button>
			</view>
			<view v-else class="content">
				<view class="summary-card">
					<view class="summary-row">
						<text class="summary-label">今日已得</text>
						<text class="summary-value">{{ todayEarned }}</text>
						<text class="summary-unit">积分</text>
					</view>
					<view class="summary-row">
						<text class="summary-label-secondary">当前余额</text>
						<text class="summary-balance">{{ balance }}</text>
						<text class="summary-unit">积分 · {{ dateText }}</text>
					</view>
				</view>

				<view v-if="displayItems.length === 0" class="tool-card">
					<text class="tool-card-tip">暂无每日任务，请联系站长在插件后台「每日任务」中配置。</text>
				</view>
				<view v-for="item in displayItems" :key="item.type" class="task-card">
					<view class="task-main">
						<view class="task-title-row">
							<text class="task-icon">{{ taskIcon(item.type) }}</text>
							<text class="task-title">{{ item.label }}</text>
							<tm-tags
								v-if="item.claimed"
								color="grey"
								:shadow="0"
								size="s"
								model="fill"
							>已领取</tm-tags>
							<tm-tags
								v-else-if="item.done"
								color="bg-gradient-indigo-accent"
								:shadow="0"
								size="s"
								model="fill"
							>可领取</tm-tags>
						</view>
						<view class="task-progress">
							<view class="progress-track">
								<view class="progress-fill" :style="{ width: progressPercent(item) + '%' }"></view>
							</view>
							<text class="progress-text">{{ item.progress }}/{{ item.target }}</text>
						</view>
					</view>
					<view class="task-action">
						<!-- 只给券不给积分的任务必须显示券，否则会写成「+0 积分」 -->
						<text class="reward-text">{{ rewardText(item) }}</text>
						<tm-button
							size="s"
							:theme="actionTheme(item)"
							:fllowTheme="actionTheme(item) !== 'grey'"
							:disabled="item.claimed || claimingType === item.type"
							:loading="claimingType === item.type"
							@click="fnDoTask(item)"
						>{{ actionText(item) }}</tm-button>
					</view>
				</view>

				<view class="footer-tip">
					<text>任务进度按「当日该行为的积分记录」统计，完成每天每任务仅可领取一次奖励。</text>
					<text>基础行为积分与任务奖励可叠加：如浏览文章既有单篇积分，达成每日目标后还可额外领取任务积分。</text>
				</view>
			</view>
		</template>

		<wx-login-dialog
			:show.sync="loginDialogShow"
			title="登录后查看每日任务"
			desc="登录后可查看进度并领取每日任务奖励"
			@success="fnOnLoginSuccess"
		></wx-login-dialog>
		<points-toast></points-toast>
	</view>
</template>

<script>
	import tmSkeleton from '@/tm-vuetify/components/tm-skeleton/tm-skeleton.vue';
	import tmEmpty from '@/tm-vuetify/components/tm-empty/tm-empty.vue';
	import tmButton from '@/tm-vuetify/components/tm-button/tm-button.vue';
	import tmTags from '@/tm-vuetify/components/tm-tags/tm-tags.vue';
	import wxLoginDialog from '@/components/wx-login-dialog/wx-login-dialog.vue';
	import pointsToast from '@/components/points-toast/points-toast.vue';
	import { checkHasWxLogin } from '@/utils/auth.js';
	import {
		fetchDailyTasks,
		claimDailyTaskReward
	} from '@/utils/points.js';

	// 每日签到入口在「我的」页面，每日任务列表不展示签到任务
	const TASK_ICONS = {
		browse: '📖',
		comment: '💬',
		upvote: '❤️',
		vote: '🗳️'
	};

	export default {
		components: {
			tmSkeleton,
			tmEmpty,
			tmButton,
			tmTags,
			wxLoginDialog,
			pointsToast
		},
		data() {
			return {
				loading: 'loading',
				isLoggedIn: false,
				loginDialogShow: false,
				items: [],
				todayEarned: 0,
				balance: 0,
				dateText: '',
				claimingType: ''
			};
		},
		onLoad() {
			this.isLoggedIn = checkHasWxLogin();
			if (this.isLoggedIn) {
				this.fnLoad();
			}
		},
		onPullDownRefresh() {
			this.fnLoad().finally(() => uni.stopPullDownRefresh());
		},
		computed: {
			/** 每日签到入口在「我的」页面，此处过滤掉签到任务 */
			displayItems() {
				return (this.items || []).filter((item) => item.type !== 'sign_in');
			}
		},
		methods: {
			taskIcon(type) {
				return TASK_ICONS[type] || '⭐';
			},
			progressPercent(item) {
				if (!item?.target) return 0;
				return Math.min(100, Math.round(((item.progress || 0) / item.target) * 100));
			},
			actionText(item) {
				if (item.claimed) return '已领取';
				if (item.done) return '领取奖励';
				return '去完成';
			},
			/** 奖励文案：积分与阅读券分开列，两者都为 0 时不留空文案。 */
			rewardText(item) {
				const rewards = [];
				if (Number(item.points || 0) > 0) {
					rewards.push('+' + item.points + ' 积分');
				}
				if (Number(item.tickets || 0) > 0) {
					rewards.push('+' + item.tickets + ' 张阅读券');
				}
				return rewards.join(' ');
			},
			actionTheme(item) {
				if (item.claimed) return 'grey';
				if (item.done) return 'primary';
				return 'grey';
			},
			async fnLoad() {
				try {
					const status = await fetchDailyTasks(this.$httpApi.v2);
					if (status.ok) {
						this.items = status.items || [];
						this.todayEarned = status.todayEarned || 0;
						this.balance = status.balance != null ? status.balance : 0;
						this.dateText = status.date || this.buildDateText();
						this.loading = 'success';
					} else {
						this.loading = 'error';
						uni.showToast({ title: status.reason || '加载失败，请稍后重试', icon: 'none' });
					}
				} catch (err) {
					this.loading = 'error';
					uni.showToast({ title: err?.error || '加载失败，请稍后重试', icon: 'none' });
				}
			},
			buildDateText() {
				const now = new Date();
				const week = ['日', '一', '二', '三', '四', '五', '六'][now.getDay()];
				return `${now.getMonth() + 1}月${now.getDate()}日 星期${week}`;
			},
			async fnDoTask(item) {
				if (this.claimingType || !item) return;
				if (item.claimed) return;
				if (!item.done) {
					uni.showToast({ title: '先完成该任务再来领取吧', icon: 'none' });
					return;
				}
				this.claimingType = item.type;
				try {
					const result = await claimDailyTaskReward(this.$httpApi.v2, item.type);
					if (!result.ok) {
						uni.showToast({ title: result.reason || '领取失败，请稍后重试', icon: 'none' });
						return;
					}
					if (result.earned > 0) {
						uni.showToast({ title: result.message || `领取成功 +${result.earned} 积分`, icon: 'none' });
					}
					await this.fnLoad();
				} finally {
					this.claimingType = '';
				}
			},
			fnOnLoginSuccess() {
				this.isLoggedIn = true;
				this.fnLoad();
			},
			fnBackHome() {
				uni.switchTab({ url: '/pages/tabbar/home/home' });
			}
		}
	};
</script>

<style scoped lang="scss">
	.app-page {
		min-height: 100vh;
		background: $uh-fill;
		padding: 24rpx 24rpx 48rpx;
	}

	.login-gate {
		min-height: 70vh;
	}

	.mt-24 {
		margin-top: 24rpx;
	}

	.mt-16 {
		margin-top: 16rpx;
	}

	.loading-wrap,
	.content-empty {
		padding: 24rpx;
	}

	.summary-card {
		padding: 32rpx 28rpx;
		border-radius: 20rpx;
		background-image: $uh-grad-accent;
		color: #fff;
		margin-bottom: 24rpx;
	}

	.summary-row {
		display: flex;
		align-items: baseline;
	}

	.summary-row + .summary-row {
		margin-top: 12rpx;
	}

	.summary-label {
		font-size: 26rpx;
		opacity: 0.9;
		margin-right: 12rpx;
	}

	.summary-label-secondary {
		font-size: 22rpx;
		opacity: 0.8;
		margin-right: 12rpx;
	}

	.summary-value {
		font-size: 56rpx;
		font-weight: 700;
		line-height: 1;
	}

	.summary-balance {
		font-size: 32rpx;
		font-weight: 600;
	}

	.summary-unit {
		font-size: 22rpx;
		opacity: 0.85;
		margin-left: 12rpx;
	}

	.tool-card,
	.task-card {
		background: $uh-card;
		border-radius: 16rpx;
		padding: 28rpx;
		margin-bottom: 24rpx;
	}

	.task-card {
		display: flex;
		align-items: center;
	}

	.task-main {
		flex: 1;
		min-width: 0;
		margin-right: 20rpx;
	}

	.task-title-row {
		display: flex;
		align-items: center;
		gap: 12rpx;
	}

	.task-icon {
		font-size: 32rpx;
	}

	.task-title {
		font-size: 30rpx;
		font-weight: 700;
		color: $uh-ink-1;
	}

	.task-progress {
		display: flex;
		align-items: center;
		margin-top: 16rpx;
	}

	.progress-track {
		flex: 1;
		height: 12rpx;
		border-radius: 6rpx;
		background: #eaedf3;
		overflow: hidden;
	}

	.progress-fill {
		height: 100%;
		border-radius: 6rpx;
		background-image: $uh-grad-accent;
		transition: width 0.3s ease;
	}

	.progress-text {
		margin-left: 12rpx;
		font-size: 22rpx;
		color: $uh-ink-3;
	}

	.task-action {
		display: flex;
		flex-direction: column;
		align-items: flex-end;
		gap: 10rpx;
	}

	.reward-text {
		font-size: 22rpx;
		color: #f56c6c;
		font-weight: 600;
	}

	.tool-card-tip {
		display: block;
		font-size: 22rpx;
		line-height: 1.6;
		color: $uh-ink-3;
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
@import '@/common/styles/redesign/maple-sub.scss';
@import '@/common/styles/redesign/pagesA-task-center-task-center-themes.scss';
</style>
