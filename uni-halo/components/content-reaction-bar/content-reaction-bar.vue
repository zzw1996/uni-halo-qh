<template>
	<view v-if="show" class="reaction-bar">
		<view
			v-for="emoji in emojiList"
			:key="emoji.kind"
			class="reaction-item"
			:class="{ 'is-active': mine === emoji.kind, 'is-loading': loadingKind === emoji.kind }"
			@click="fnClick(emoji)"
		>
			<text class="reaction-emoji">{{ emoji.icon }}</text>
			<text class="reaction-count">{{ counts[emoji.kind] || 0 }}</text>
		</view>
		<wx-login-dialog
			:show.sync="loginDialogShow"
			title="登录后参与互动"
			desc="登录后即可使用表情反馈"
			@success="fnOnLoginSuccess"
		></wx-login-dialog>
	</view>
</template>

<script>
	import wxLoginDialog from '@/components/wx-login-dialog/wx-login-dialog.vue';
	import { checkHasWxLogin } from '@/utils/auth.js';

	/** 固定表情集（kind 与后端 UserActivity.reactionKind 一致） */
	const EMOJI_LIST = [
		{ kind: 'like', icon: '👍' },
		{ kind: 'love', icon: '❤️' },
		{ kind: 'haha', icon: '😆' },
		{ kind: 'wow', icon: '😮' },
		{ kind: 'sad', icon: '😢' },
		{ kind: 'applause', icon: '👏' }
	];

	export default {
		name: 'content-reaction-bar',
		components: {
			wxLoginDialog
		},
		props: {
			contentType: {
				type: String,
				default: 'post'
			},
			contentName: {
				type: String,
				default: ''
			}
		},
		data() {
			return {
				emojiList: EMOJI_LIST,
				counts: {},
				mine: '',
				loadingKind: '',
				loginDialogShow: false,
				show: false
			};
		},
		onLoad() {
			this.fnRefresh();
			uni.$on('wx_login_status_changed', this.fnRefresh);
		},
		onUnload() {
			uni.$off('wx_login_status_changed', this.fnRefresh);
		},
		methods: {
			async fnRefresh() {
				if (!this.contentName) return;
				try {
					const status = await this.$httpApi.v2.getContentReactionStatus(this.contentType, this.contentName);
					this.counts = status.counts || {};
					this.mine = status.mine || '';
					this.show = true;
				} catch (err) {
					// 接口未就绪时静默隐藏，不影响页面
					this.show = false;
				}
			},
			async fnClick(emoji) {
				if (this.loadingKind) return;
				if (!checkHasWxLogin()) {
					this.loginDialogShow = true;
					return;
				}
				this.loadingKind = emoji.kind;
				try {
					if (this.mine === emoji.kind) {
						await this.$httpApi.v2.cancelContentReaction(this.contentType, this.contentName);
					} else {
						await this.$httpApi.v2.submitContentReaction(this.contentType, this.contentName, emoji.kind);
					}
					await this.fnRefresh();
					this.$emit('reaction_changed', {
						contentType: this.contentType,
						contentName: this.contentName,
						counts: this.counts,
						mine: this.mine
					});
				} catch (err) {
					uni.showToast({ title: err?.error || '操作失败，请稍后重试', icon: 'none' });
				} finally {
					this.loadingKind = '';
				}
			},
			fnOnLoginSuccess() {
				this.fnRefresh();
			}
		}
	};
</script>

<style scoped lang="scss">
	.reaction-bar {
		display: flex;
		align-items: center;
		flex-wrap: wrap;
		gap: 12rpx;
		margin: 20rpx 0 8rpx;
	}

	.reaction-item {
		display: flex;
		align-items: center;
		gap: 6rpx;
		padding: 10rpx 20rpx;
		border-radius: 999rpx;
		background: #f5f6fa;
		border: 2rpx solid transparent;

		&.is-active {
			background: #eef4ff;
			border-color: #667eea;
		}

		&.is-loading {
			opacity: 0.6;
		}
	}

	.reaction-emoji {
		font-size: 30rpx;
		line-height: 1;
	}

	.reaction-count {
		font-size: 24rpx;
		color: #909399;

		.is-active & {
			color: #667eea;
			font-weight: 600;
		}
	}
</style>
