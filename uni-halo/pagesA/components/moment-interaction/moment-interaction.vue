<template>
	<view :class="skinClass" class="moment-interaction">
		<view class="moment-interaction__footer">
			<text class="moment-interaction__time">{{ releaseTimeText }}</text>
			<view class="moment-interaction__actions">
				<view
					class="moment-interaction__menu-btn"
					:class="{ 'is-active': menuVisible }"
					@click.stop="fnToggleMenu"
				>
					<view class="moment-interaction__dots">
						<view class="moment-interaction__dot"></view>
						<view class="moment-interaction__dot"></view>
						<view class="moment-interaction__dot"></view>
					</view>
				</view>
				<view v-if="menuVisible" class="moment-interaction__menu-mask" @click.stop="fnCloseMenu"></view>
				<view v-if="menuVisible" class="moment-interaction__menu" @click.stop>
					<view class="moment-interaction__menu-item" @click.stop="fnTryLike">
						<text class="icon iconfont" :class="isUpvoted ? 'icon-heart-fill' : 'icon-like'"></text>
						<text>{{ isUpvoted ? '取消' : '赞' }}</text>
					</view>
					<view v-if="showComment" class="moment-interaction__menu-item" @click.stop="fnTryComment">
						<text class="icon iconfont icon-commentdots-fill"></text>
						<text>评论</text>
					</view>
				</view>
			</view>
		</view>

		<view
			v-if="showInteractionBox"
			class="moment-interaction__box"
			@click.stop
		>
			<view v-if="upvoteCount > 0" class="moment-interaction__likes">
				<text class="icon iconfont icon-heart-fill likes-icon"></text>
				<view class="likes-avatars">
					<view
						v-for="(item, index) in likeAvatarList"
						:key="item.key || index"
						class="likes-avatar-item"
					>
						<image
							v-if="item.avatar"
							class="likes-avatar-img"
							:src="item.avatar"
							mode="aspectFill"
						></image>
						<view v-else class="likes-avatar-placeholder">
							<text class="icon iconfont icon-user placeholder-icon"></text>
						</view>
					</view>
				</view>
			</view>
			<view
				v-if="showComment && commentLines.length"
				class="moment-interaction__comments"
				:class="{ 'has-likes': upvoteCount > 0 }"
			>
				<view
					v-for="(line, index) in commentLines"
					:key="line.key || index"
					class="comment-line"
					:data-index="index"
					@longpress="fnOnCommentLongPress"
					@click.stop="fnOnCommentTap"
				>
					<text class="comment-author">{{ line.authorName }}</text>
					<text v-if="line.replyToName" class="comment-reply-tag">回复</text>
					<text v-if="line.replyToName" class="comment-author">{{ line.replyToName }}</text>
					<text class="comment-colon">：</text>
					<text class="comment-content">{{ line.content }}</text>
				</view>
			</view>
		</view>
	</view>
</template>

<script>
import { checkHasWxLogin, getOpenid, getWxLoginInfo } from '@/utils/auth.js';
import { isWxLoginRequired } from '@/utils/wxLogin.js';
import { resolveCommentOwner, canDeleteComment, isCommentSerializationError } from '@/utils/comment.js';
import { upvote } from '@/utils/upvote.js';
import {
	USER_ACTIVITY_TYPES,
	syncMomentUpvoteStatus,
	recordMomentUserActivity,
	removeMomentUserActivity
} from '@/utils/userActivity.js';

export default {
	name: 'moment-interaction',
	props: {
		moment: {
			type: Object,
			required: true
		},
		showComment: {
			type: Boolean,
			default: true
		},
		bloggerNickname: {
			type: String,
			default: ''
		},
		currentUserName: {
			type: String,
			default: '微信用户'
		},
		currentUserAvatar: {
			type: String,
			default: ''
		}
	},
	data() {
		return {
			menuVisible: false,
			isUpvoted: false,
			upvoteLoading: false,
			commentsLoading: false,
			commentsLoaded: false,
			commentList: [],
			localUpvoteCount: 0,
			localUpvoters: []
		};
	},
	computed: {
		momentName() {
			return this.moment?.metadata?.name || '';
		},
		releaseTimeText() {
			const time = this.moment?.spec?.releaseTime;
			if (!time) return '';
			return this.$tm.dayjs(time).format('YYYY-MM-DD HH:mm');
		},
		upvoteCount() {
			const statsCount = Number(this.moment?.stats?.upvote ?? 0);
			return Math.max(statsCount, this.localUpvoteCount, 0);
		},
		likeAvatarList() {
			const count = this.upvoteCount;
			if (count <= 0) return [];
			const known = this.fnGetKnownUpvoters();
			const list = [];
			for (let i = 0; i < count; i += 1) {
				const user = known[i];
				list.push({
					key: user?.openid || `placeholder-${i}`,
					avatar: user?.avatar || ''
				});
			}
			return list;
		},
		showInteractionBox() {
			const hasComments = this.showComment && this.commentLines.length > 0;
			return this.upvoteCount > 0 || hasComments;
		},
		commentLines() {
			if (!this.showComment || !this.commentList.length) return [];
			const lines = [];
			this.commentList.forEach((comment) => {
				lines.push(this.fnBuildCommentLine(comment, null));
				const replies = comment?.replies?.items || [];
				replies.forEach((reply) => {
					lines.push(this.fnBuildCommentLine(reply, comment));
				});
			});
			return lines;
		}
	},
	watch: {
		moment: {
			immediate: true,
			handler(val) {
				if (!val) return;
				this.localUpvoteCount = Number(val?.stats?.upvote ?? 0);
				this.fnInitUpvotersFromMoment(val);
				this.fnInitUpvoteState();
				if (this.showComment && Number(val?.stats?.approvedComment ?? val?.stats?.totalComment ?? 0) > 0) {
					this.fnLoadComments();
				}
			}
		},
		showComment(val) {
			if (val && !this.commentsLoaded && Number(this.moment?.stats?.approvedComment ?? 0) > 0) {
				this.fnLoadComments();
			}
		}
	},
	created() {
		uni.$on('moment_interaction_close_menu', this.fnCloseMenu);
	},
	beforeDestroy() {
		uni.$off('moment_interaction_close_menu', this.fnCloseMenu);
	},
	methods: {
		fnGetCurrentUserUpvoter() {
			const wxUser = getWxLoginInfo();
			const openid = getOpenid() || wxUser?.openid || '';
			const avatarUrl = this.currentUserAvatar || wxUser?.avatarUrl || '';
			return {
				openid,
				displayName: this.currentUserName,
				avatar: avatarUrl ? this.$utils.checkAvatarUrl(avatarUrl, false) : ''
			};
		},
		fnInitUpvotersFromMoment(moment) {
			const remoteList = moment?.upvoters || moment?.likedUsers || [];
			if (!Array.isArray(remoteList) || !remoteList.length) return;
			this.localUpvoters = remoteList.map((item, index) => ({
				openid: item.openid || item.id || `remote-${index}`,
				displayName: item.displayName || item.nickname || '微信用户',
				avatar: item.avatar || item.avatarUrl
					? this.$utils.checkAvatarUrl(item.avatar || item.avatarUrl, false)
					: ''
			}));
		},
		fnGetKnownUpvoters() {
			const list = [...this.localUpvoters];
			if (this.isUpvoted) {
				const current = this.fnGetCurrentUserUpvoter();
				if (current.openid && !list.some((item) => item.openid === current.openid)) {
					list.unshift(current);
				}
			}
			return list.slice(0, this.upvoteCount);
		},
		fnSyncCurrentUserUpvoter(add) {
			const current = this.fnGetCurrentUserUpvoter();
			if (!current.openid) return;
			if (add) {
				if (!this.localUpvoters.some((item) => item.openid === current.openid)) {
					this.localUpvoters.unshift(current);
				}
				return;
			}
			this.localUpvoters = this.localUpvoters.filter((item) => item.openid !== current.openid);
		},
		fnCloseMenu() {
			this.menuVisible = false;
		},
		fnToggleMenu() {
			if (!this.menuVisible) {
				uni.$emit('moment_interaction_close_menu');
			}
			this.menuVisible = !this.menuVisible;
		},
		async fnInitUpvoteState() {
			if (!this.momentName) return;
			if (checkHasWxLogin() && getOpenid()) {
				this.isUpvoted = await syncMomentUpvoteStatus(this.$httpApi.v2, this.momentName);
				this.fnSyncCurrentUserUpvoter(this.isUpvoted);
				return;
			}
			this.isUpvoted = upvote.has('moment', this.momentName);
			this.fnSyncCurrentUserUpvoter(this.isUpvoted);
		},
		fnBuildCommentLine(comment, parentComment) {
			const owner = resolveCommentOwner(comment);
			const parentOwner = parentComment ? resolveCommentOwner(parentComment) : null;
			return {
				key: comment?.metadata?.name,
				comment,
				parentComment,
				canDelete: canDeleteComment(comment),
				authorName: owner.displayName || '匿名用户',
				replyToName: parentOwner?.displayName || '',
				content: (comment?.spec?.raw || comment?.spec?.content || '').replace(/<[^>]+>/g, '')
			};
		},
		fnEnsureLogin(action) {
			const haloConfigs = this.$tm.vx.getters().getConfigs;
			if (isWxLoginRequired(haloConfigs) && !checkHasWxLogin()) {
				this.$emit('need-login', { action, momentName: this.momentName });
				return false;
			}
			if (!getOpenid()) {
				this.$emit('need-login', { action, momentName: this.momentName });
				return false;
			}
			return true;
		},
		fnTryLike() {
			this.menuVisible = false;
			if (!this.fnEnsureLogin('like')) return;
			this.fnDoLike();
		},
		fnTryComment() {
			this.menuVisible = false;
			if (!this.showComment) return;
			if (!this.fnEnsureLogin('comment')) return;
			this.$emit('comment', {
				isComment: true,
				postName: this.momentName,
				momentName: this.momentName,
				title: '评论瞬间'
			});
		},
		fnGetCommentLineFromEvent(e) {
			const index = Number(e?.currentTarget?.dataset?.index);
			if (Number.isNaN(index)) return null;
			return this.commentLines[index] || null;
		},
		fnOnCommentTap(e) {
			const line = this.fnGetCommentLineFromEvent(e);
			if (!line?.comment?.metadata?.name) return;
			if (!this.showComment) return;
			if (!this.fnEnsureLogin('reply')) return;
			if (line.canDelete) {
				uni.showActionSheet({
					itemList: ['回复', '删除'],
					success: (res) => {
						if (res.tapIndex === 0) {
							this.fnOpenReplyModal(line);
						} else if (res.tapIndex === 1) {
							this.fnConfirmDeleteComment(line);
						}
					}
				});
				return;
			}
			this.fnOpenReplyModal(line);
		},
		fnOpenReplyModal(line) {
			this.$emit('comment', {
				isComment: false,
				postName: line.comment.metadata.name,
				momentName: this.momentName,
				title: `回复用户：${line.authorName}`
			});
		},
		fnConfirmDeleteComment(line) {
			if (!line?.comment?.metadata?.name) return;
			uni.showModal({
				title: '删除评论',
				content: '确定删除这条评论吗？删除后不可恢复。',
				confirmText: '删除',
				confirmColor: '#ff5722',
				success: (res) => {
					if (!res.confirm) return;
					this.fnDeleteComment(line.comment);
				}
			});
		},
		fnDeleteComment(comment) {
			if (!comment?.metadata?.name) return;
			uni.showLoading({ title: '删除中...', mask: true });
			this.$httpApi.v2.deletePostComment(comment.metadata.name)
				.then(() => {
					uni.hideLoading();
					uni.$tm.toast('删除成功');
					this.fnLoadComments(true);
					this.fnApplyCommentStatsDelta(-1);
				})
				.catch((err) => {
					uni.hideLoading();
					if (isCommentSerializationError(err)) {
						uni.$tm.toast('可能已删除，正在刷新');
						this.fnLoadComments(true);
						this.fnApplyCommentStatsDelta(-1);
						return;
					}
					uni.$tm.toast(err?.error || err?.message || '删除失败');
				});
		},
		fnOnCommentLongPress(e) {
			const line = this.fnGetCommentLineFromEvent(e);
			if (!line?.comment) return;
			if (!line.canDelete) {
				uni.$tm.toast('只能删除自己的评论');
				return;
			}
			this.fnConfirmDeleteComment(line);
		},
		fnGetUpvoteCancelCooldownTip() {
			if (!this.momentName) return '点赞后需满1分钟才能取消';
			const remainMs = upvote.getCancelCooldownRemainingMs('moment', this.momentName);
			if (remainMs <= 0) return '';
			const remainSec = Math.ceil(remainMs / 1000);
			return `点赞后需满1分钟才能取消，请${remainSec}秒后再试`;
		},
		fnCanCancelUpvote() {
			if (!this.momentName) return true;
			return upvote.canCancelUpvote('moment', this.momentName);
		},
		fnUpdateUpvoteState(upvoted) {
			if (this.momentName) {
				upvote.sync('moment', this.momentName, upvoted);
				if (upvoted) {
					upvote.setUpvoteTime('moment', this.momentName);
				} else {
					upvote.clearUpvoteTime('moment', this.momentName);
				}
			}
			this.isUpvoted = !!upvoted;
			this.fnSyncCurrentUserUpvoter(!!upvoted);
		},
		fnApplyUpvoteStatsDelta(delta) {
			if (!delta) return;
			const next = Math.max(this.upvoteCount + delta, 0);
			this.localUpvoteCount = next;
			if (this.moment?.stats) {
				this.$set(this.moment.stats, 'upvote', next);
			}
		},
		fnApplyCommentStatsDelta(delta) {
			if (!delta || !this.moment?.stats) return;
			const approved = Math.max(Number(this.moment.stats.approvedComment || 0) + delta, 0);
			const total = Math.max(Number(this.moment.stats.totalComment || 0) + delta, 0);
			this.$set(this.moment.stats, 'approvedComment', approved);
			this.$set(this.moment.stats, 'totalComment', total);
		},
		fnBuildActivityPayload() {
			if (!this.momentName) return null;
			const raw = this.moment?.spec?.content?.raw || '';
			return {
				momentName: this.momentName,
				title: raw.slice(0, 50) || '瞬间',
				excerpt: raw.slice(0, 100)
			};
		},
		fnDoLike() {
			if (this.upvoteLoading || !this.momentName) return;
			if (this.isUpvoted && !this.fnCanCancelUpvote()) {
				uni.$tm.toast(this.fnGetUpvoteCancelCooldownTip());
				return;
			}
			this.upvoteLoading = true;
			const payload = this.fnBuildActivityPayload();

			if (this.isUpvoted) {
				removeMomentUserActivity(this.$httpApi.v2, USER_ACTIVITY_TYPES.UPVOTE, payload)
					.then(async () => {
						try {
							await this.$httpApi.v2.submitMomentDownvote(this.momentName);
						} catch (e) {
							console.warn('[Moment] downvote fallback failed:', e?.error || e);
						}
						this.fnUpdateUpvoteState(false);
						this.fnApplyUpvoteStatsDelta(-1);
						await this.fnInitUpvoteState();
						uni.$tm.toast('已取消点赞');
					})
					.catch(async (err) => {
						await this.fnInitUpvoteState();
						if (!this.isUpvoted) {
							uni.$tm.toast('已取消点赞');
							return;
						}
						uni.$tm.toast(err?.error || '取消点赞失败');
					})
					.finally(() => {
						this.upvoteLoading = false;
					});
				return;
			}

			this.$httpApi.v2.submitMomentUpvote(this.momentName)
				.then(async () => {
					this.fnUpdateUpvoteState(true);
					this.fnApplyUpvoteStatsDelta(1);
					await recordMomentUserActivity(this.$httpApi.v2, USER_ACTIVITY_TYPES.UPVOTE, payload);
					await this.fnInitUpvoteState();
					uni.$tm.toast('点赞成功');
				})
				.catch(async (err) => {
					await this.fnInitUpvoteState();
					if (this.isUpvoted) {
						uni.$tm.toast('点赞成功');
						return;
					}
					uni.$tm.toast(err?.error || '点赞失败');
				})
				.finally(() => {
					this.upvoteLoading = false;
				});
		},
		fnLoadComments(force = false) {
			if (!this.showComment || !this.momentName) return Promise.resolve();
			if (this.commentsLoading) return Promise.resolve();
			if (this.commentsLoaded && !force) return Promise.resolve();

			this.commentsLoading = true;
			return this.$httpApi.v2.getMomentCommentList(this.momentName)
				.then((res) => {
					this.commentList = res?.items || [];
					this.commentsLoaded = true;
				})
				.catch((err) => {
					console.warn('[Moment] load comments failed:', err?.error || err);
				})
				.finally(() => {
					this.commentsLoading = false;
				});
		},
		refreshComments() {
			return this.fnLoadComments(true);
		},
		onCommentSubmitted() {
			this.fnApplyCommentStatsDelta(1);
			return this.fnLoadComments(true);
		}
	}
};
</script>

<style lang="scss" scoped>
.moment-interaction {
	padding: 0 24rpx 24rpx;

	&__footer {
		display: flex;
		align-items: center;
		justify-content: space-between;
		margin-top: 8rpx;
	}

	&__time {
		font-size: 24rpx;
		color: $uh-ink-3;
	}

	&__actions {
		position: relative;
		z-index: 12;
	}

	&__menu-btn {
		width: 72rpx;
		height: 44rpx;
		border-radius: 8rpx;
		background: $uh-fill;
		display: flex;
		align-items: center;
		justify-content: center;
		box-shadow: inset 0 0 0 1rpx rgba(0, 0, 0, 0.04);

		&.is-active {
			background: $uh-line;
		}
	}

	&__dots {
		display: flex;
		align-items: center;
		justify-content: center;
		gap: 8rpx;
	}

	&__dot {
		width: 8rpx;
		height: 8rpx;
		border-radius: 50%;
		background: #637793;
	}

	&__menu-mask {
		position: fixed;
		left: 0;
		top: 0;
		right: 0;
		bottom: 0;
		z-index: 11;
		background: transparent;
	}

	&__menu {
		position: absolute;
		right: 80rpx;
		top: 50%;
		transform: translateY(-50%);
		display: flex;
		align-items: center;
		background: #4c4c4c;
		border-radius: 8rpx;
		padding: 0 8rpx;
		z-index: 13;
		animation: moment-menu-in 0.18s ease-out;

		&::after {
			content: '';
			position: absolute;
			right: -10rpx;
			top: 50%;
			transform: translateY(-50%);
			border-width: 10rpx 0 10rpx 10rpx;
			border-style: solid;
			border-color: transparent transparent transparent #4c4c4c;
		}
	}

	&__menu-item {
		display: flex;
		align-items: center;
		padding: 16rpx 24rpx;
		color: #fff;
		font-size: 26rpx;
		white-space: nowrap;

		.icon {
			font-size: 28rpx;
			margin-right: 8rpx;
		}

		& + & {
			border-left: 1rpx solid rgba(255, 255, 255, 0.15);
		}
	}

	&__box {
		margin-top: 16rpx;
		background: $uh-fill;
		border-radius: 8rpx;
		padding: 12rpx 16rpx;
	}

	&__likes {
		display: flex;
		align-items: center;
		padding-bottom: 8rpx;

		.likes-icon {
			font-size: 24rpx;
			color: $uh-accent;
			margin-right: 12rpx;
			flex-shrink: 0;
		}

		.likes-avatars {
			display: flex;
			align-items: center;
			flex-wrap: wrap;
			gap: 8rpx;
		}

		.likes-avatar-item {
			width: 44rpx;
			height: 44rpx;
			border-radius: 8rpx;
			overflow: hidden;
			flex-shrink: 0;
		}

		.likes-avatar-img {
			width: 100%;
			height: 100%;
			display: block;
			background: $uh-fill;
		}

		.likes-avatar-placeholder {
			width: 100%;
			height: 100%;
			background: $uh-line;
			display: flex;
			align-items: center;
			justify-content: center;

			.placeholder-icon {
				font-size: 22rpx;
				color: $uh-ink-4;
			}
		}
	}

	&__comments {
		&.has-likes {
			border-top: 1rpx solid $uh-line;
			padding-top: 8rpx;
			margin-top: 4rpx;
		}
	}

	.comment-line {
		font-size: 26rpx;
		line-height: 1.7;
		padding: 4rpx 0;
		word-break: break-all;
	}

	.comment-author {
		color: $uh-accent;
		font-weight: 500;
	}

	.comment-reply-tag {
		color: $uh-ink-1;
		margin: 0 4rpx;
	}

	.comment-colon {
		color: $uh-ink-1;
	}

	.comment-content {
		color: $uh-ink-1;
	}
}

@keyframes moment-menu-in {
	from {
		opacity: 0;
		transform: translateY(-50%) translateX(12rpx);
	}

	to {
		opacity: 1;
		transform: translateY(-50%) translateX(0);
	}
}
</style>
