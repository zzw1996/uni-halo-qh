<template>
	<view :class="skinClass" class="publish-page">
		<view v-if="!statusChecked" class="loading-wrap flex flex-center">
			<tm-loadding color="indigo" :size="48"></tm-loadding>
		</view>

		<view v-else-if="!canPublish" class="denied-wrap flex flex-center flex-col">
			<tm-empty icon="icon-article" label="暂时不能投稿"></tm-empty>
			<text class="denied-tip">{{ disabledReason }}</text>
			<tm-button class="mt-24" plan theme="grey" :fllowTheme="false" :shadow="0"
				@click="fnBack">返回</tm-button>
		</view>

		<view v-else class="publish-body">
			<view class="publish-card">
				<input
					class="publish-title"
					v-model="form.title"
					:maxlength="maxTitleLength"
					placeholder="标题"
					:disabled="submitting"
				/>
				<textarea
					class="publish-input"
					v-model="form.content"
					:maxlength="maxContentLength"
					placeholder="正文（支持 Markdown，例如 ## 小标题、**加粗**、- 列表）"
					:disabled="submitting"
					:auto-height="true"
				></textarea>
				<view class="publish-count">
					{{ form.content.length }}/{{ maxContentLength }}
				</view>
			</view>

			<!-- 直发与待审对用户的预期完全不同，提交前必须说清楚 -->
			<view class="mode-tip" :class="{ 'mode-tip--draft': isDraftMode }">
				<text class="mode-tip-text">
					{{ isDraftMode
						? '本次投稿将进入待审，站长审核通过后才会公开'
						: '本次投稿将直接发布到站点' }}
				</text>
			</view>

			<text v-if="status" class="cost-tip">
				本次投稿将消耗 {{ status.articleCost }} 积分（当前 {{ status.balance }} 积分）
			</text>

			<view class="publish-actions">
				<tm-button
					block
					theme="bg-gradient-indigo-accent"
					:shadow="4"
					:loading="submitting"
					:disabled="!canSubmit"
					@click="fnSubmit"
				>{{ submitText }}</tm-button>
			</view>
		</view>
	</view>
</template>

<script>
	import tmEmpty from '@/tm-vuetify/components/tm-empty/tm-empty.vue';
	import tmButton from '@/tm-vuetify/components/tm-button/tm-button.vue';
	import tmLoadding from '@/tm-vuetify/components/tm-loadding/tm-loadding.vue';
	import { checkHasWxLogin } from '@/utils/auth.js';
	import {
		fetchUserPublishStatus,
		submitUserArticle
	} from '@/utils/userPublish.js';

	export default {
		components: {
			tmEmpty,
			tmButton,
			tmLoadding
		},
		data() {
			return {
				statusChecked: false,
				canPublish: false,
				disabledReason: '暂不能投稿',
				status: null,
				submitting: false,
				form: {
					title: '',
					content: ''
				}
			};
		},
		computed: {
			maxTitleLength() {
				return this.status?.maxArticleTitleLength || 60;
			},
			maxContentLength() {
				return this.status?.maxArticleContentLength || 5000;
			},
			isDraftMode() {
				return this.status?.articleMode !== 'direct';
			},
			canSubmit() {
				return !this.submitting
					&& !!this.form.title.trim()
					&& !!this.form.content.trim();
			},
			submitText() {
				if (!this.status) {
					return '提交投稿';
				}
				return this.isDraftMode
					? `提交审核（${this.status.articleCost} 积分）`
					: `发布（${this.status.articleCost} 积分）`;
			}
		},
		onLoad() {
			this.fnLoadStatus();
		},
		methods: {
			async fnLoadStatus() {
				if (!checkHasWxLogin()) {
					this.statusChecked = true;
					this.canPublish = false;
					this.disabledReason = '请先登录后再投稿';
					return;
				}
				const status = await fetchUserPublishStatus(this.$httpApi.v2);
				this.statusChecked = true;
				this.status = status;
				// 能不能发以服务端结论为准（服务端会区分角色、门槛、余额、每日上限）
				this.canPublish = !!(status && status.canPublishArticle);
				this.disabledReason = (status && status.articleDisabledReason)
					|| '站长未开启投稿，或当前账号暂无投稿权限';
			},
			fnBack() {
				uni.navigateBack({
					fail: () => {
						uni.switchTab({ url: '/pages/tabbar/home/home' });
					}
				});
			},
			async fnSubmit() {
				if (!this.canSubmit) {
					return;
				}
				// 扣分发生在服务端，提交前再确认一次，避免误触花掉积分
				const cost = this.status?.articleCost || 0;
				const confirmed = await this.fnConfirm(cost);
				if (!confirmed) {
					return;
				}
				this.submitting = true;
				uni.showLoading({ mask: true, title: '提交中...' });
				try {
					const result = await submitUserArticle(this.$httpApi.v2, {
						title: this.form.title.trim(),
						content: this.form.content.trim()
					});
					if (!result.ok) {
						throw { error: result.reason };
					}
					uni.showToast({
						icon: 'success',
						title: result.data?.statusText || '提交成功'
					});
					setTimeout(() => this.fnBack(), 900);
				} catch (err) {
					const message = err?.error || err?.detail || err?.message || '投稿失败，请稍后重试';
					uni.showToast({ icon: 'none', title: message });
				} finally {
					this.submitting = false;
					uni.hideLoading();
				}
			},
			fnConfirm(cost) {
				return new Promise((resolve) => {
					uni.showModal({
						title: '确认投稿',
						content: cost > 0
							? `本次将消耗 ${cost} 积分，发布失败会自动退还。确定继续吗？`
							: '确定提交这篇投稿吗？',
						confirmText: '确定',
						confirmColor: '#2F54EB',
						success: (res) => resolve(!!res.confirm),
						fail: () => resolve(false)
					});
				});
			}
		}
	};
</script>

<style lang="scss" scoped>
	.publish-page {
		min-height: 100vh;
		background-color: $uh-bg;
	}

	.loading-wrap {
		padding-top: 30vh;
	}

	.denied-wrap {
		padding: 20vh 48rpx 0;
	}

	.denied-tip {
		margin-top: 16rpx;
		font-size: 26rpx;
		color: $uh-ink-3;
		text-align: center;
	}

	.publish-body {
		padding: 24rpx;
	}

	.publish-card {
		padding: 24rpx;
		background-color: $uh-card;
		border-radius: $uh-radius-lg;
		box-shadow: $uh-shadow-sm;
	}

	.publish-title {
		width: 100%;
		font-size: 34rpx;
		font-weight: 600;
		color: $uh-ink-1;
		padding-bottom: 20rpx;
		border-bottom: 1rpx solid $uh-line;
	}

	.publish-input {
		width: 100%;
		min-height: 520rpx;
		margin-top: 20rpx;
		font-size: 29rpx;
		line-height: 1.75;
		color: $uh-ink-1;
	}

	.publish-count {
		margin-top: 12rpx;
		font-size: 22rpx;
		color: $uh-ink-4;
		text-align: right;
	}

	.mode-tip {
		margin-top: 20rpx;
		padding: 18rpx 22rpx;
		border-radius: $uh-radius-md;
		background-color: $uh-accent-weak;

		&--draft {
			background-color: $uh-warning-weak;
		}
	}

	.mode-tip-text {
		font-size: 24rpx;
		color: $uh-accent;
		line-height: 1.6;
	}

	.mode-tip--draft .mode-tip-text {
		color: $uh-warning;
	}

	.cost-tip {
		display: block;
		margin-top: 16rpx;
		font-size: 24rpx;
		color: $uh-ink-3;
	}

	.publish-actions {
		margin-top: 28rpx;
	}
</style>
