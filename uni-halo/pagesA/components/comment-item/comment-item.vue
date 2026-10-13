<template>
	<view v-if="comment" class="comment-item flex flex-col mt-30 pt-24"
		:class="[{ 'child-comment-item': isChild, 'no-solid': !useSolid, classItem }, skinClass]">
		<view class="comment-item_user flex">
			<image class="user-avatar"
				:class="resolvedOwner.avatarFrame ? avatarFrameClass(resolvedOwner.avatarFrame) : ''"
				:src="displayAvatar" mode="aspectFill"
				@error="fnOnImageError"></image>
			<view class="user-info pl-14">
				<view class="author flex items-center">
					<text class="mr-6 text-grey-darken-1 text-size-m text-weight-b">{{ displayName }}</text>
					<tm-tags v-if="isWxUser" dense :round="24" color="indigo" size="xs" label="微信用户"></tm-tags>
				</view>
				<view class="flex mt-4">
					<view class="time text-size-xs text-grey">
						<text>{{ $tm.dayjs(comment.spec.creationTime).format('YYYY年MM月DD日') }}</text>
						<text class="ml-12">{{ $tm.dayjs(comment.spec.creationTime).fromNow(true) }}前</text>
					</view>
				</view>
			</view>
			<view v-if="useActions" class="comment-item_actions">
				<tm-button v-if="!disallowComment" size="s" text theme="indigo"
					@click="$emit('on-comment', { type: 'user', comment: comment })">回复</tm-button>
				<tm-button v-if="showDelete" size="s" text theme="red" :fllowTheme="false"
					@click.stop="$emit('on-delete', comment)">删除</tm-button>
				<tm-button size="s" text theme="grey" :fllowTheme="false" @click="$emit('on-copy', comment.spec.raw)">复制</tm-button>
			</view>
		</view>

		<view class="comment-item_content mt-12" :class="{ 'has-bg': useContentBg, 'not-ml': isChild }"
			@click="$emit('on-detail', comment)" v-html="comment.spec.raw"></view>
	</view>
</template>

<script>
	import tmTags from '@/tm-vuetify/components/tm-tags/tm-tags.vue';
	import tmButton from '@/tm-vuetify/components/tm-button/tm-button.vue';
	import { resolveCommentOwner, canDeleteComment } from '@/utils/comment.js';
	import { avatarFrameClass, nickFramePrefix } from '@/utils/outfitSkin.js';

	export default {
		name: 'comment-item',
		components: {
			tmTags,
			tmButton
		},
		props: {
			classItem: {
				type: Array,
				default: () => []
			},
			disallowComment: {
				type: Boolean,
				default: false
			},
			useActions: {
				type: Boolean,
				default: true
			},
			useSolid: {
				type: Boolean,
				default: true
			},
			useContentBg: {
				type: Boolean,
				default: true
			},
			isChild: {
				type: Boolean,
				default: false
			},
			postName: {
				type: String,
				default: ""
			},
			comment: {
				type: Object,
				default: () => null
			}
		},
		computed: {
			imageConfigs() {
				return this.$tm.vx.getters().getConfigs.imagesConfig;
			},
			resolvedOwner() {
				return resolveCommentOwner(this.comment);
			},
			displayName() {
				const prefix = nickFramePrefix(this.resolvedOwner.nickFrame);
				return prefix ? `${prefix} ${this.resolvedOwner.displayName}` : this.resolvedOwner.displayName;
			},
			displayAvatar() {
				return this.$utils.checkAvatarUrl(this.resolvedOwner.avatar, false);
			},
			isWxUser() {
				return !!this.resolvedOwner.openid || !!(this.comment?.owner?.email || '').includes('@mini.unihalo.local');
			},
			showDelete() {
				return canDeleteComment(this.comment);
			}
		},
		methods: {
			fnOnImageError() {
				if (this.comment?.owner) {
					const defaultUrl = this.imageConfigs?.defaultAvatarUrl || '';
					if (defaultUrl) {
						this.comment.owner.avatar = defaultUrl;
					}
				}
			}
		}
	};
</script>

<style scoped lang="scss">
	.comment-item {
		box-sizing: border-box;
		border-top: 2rpx solid $uh-fill;

		&.child-comment-item {
			padding-top: 0;
			margin-left: 80rpx;
			border: 0;
		}

		&.no-solid {
			border: 0;
			margin-top: 0 !important;
		}

		&_user {
			display: flex;
			align-items: center;

			.user-avatar {
				width: 70rpx;
				height: 70rpx;
				box-sizing: border-box;
				box-shadow: $uh-shadow-sm;
				border: 4rpx solid #ffffff;
				border-radius: 12rpx;
				flex-shrink: 0;

				&.is-radius {
					border-radius: 50%;
				}

				// 头像框（装扮全站可见）
				&.frame-gold {
					border: 5rpx solid #f5c542;
					box-shadow: 0 0 16rpx rgba(245, 197, 66, 0.5);
				}

				&.frame-rainbow {
					border: 5rpx solid transparent;
					background: linear-gradient(#f0f0f5, #f0f0f5) padding-box,
						linear-gradient(135deg, #f79ea3, #9b8cff, #79d6f2) border-box;
					box-shadow: 0 0 16rpx rgba(155, 140, 255, 0.4);
				}

				&.frame-silver {
					border: 5rpx solid #c3cbd8;
					box-shadow: 0 0 16rpx rgba(195, 203, 216, 0.5);
				}
			}

			.user-info {
				width: 0;
				flex-grow: 1;
			}
		}

		&_actions {
			flex-shrink: 0;
		}

		&_content {
			font-size: 28rpx;
			margin-left: 80rpx;
			box-sizing: border-box;
			border-radius: 10rpx;
			line-height: 1.8;
			color: #1f1f1f;

			&.has-bg {
				background-color: $uh-bg;
				padding: 6rpx 24rpx;
			}

			&.not-ml {
				margin-left: 80rpx;
			}
		}
	}
</style>
