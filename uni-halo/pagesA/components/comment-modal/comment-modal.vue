<template>
    <tm-poup v-model="isShow" position="bottom" height="auto" :round="6" @change="handleOnChange">
        <view class="comment-modal">
            <view class="comment-modal__header">
                <text class="comment-modal__title">{{ calcTitle }}</text>
                <text v-if="isReply" class="comment-modal__subtitle">回复 @{{ replyTargetName }}</text>
            </view>

            <view class="comment-modal__author">
                <image
                    class="comment-modal__avatar"

                    :src="$utils.checkAvatarUrl(authorInfo.avatar, false)"
                    mode="aspectFill"
                ></image>
                <view class="comment-modal__author-info">
                    <text class="comment-modal__nickname">{{ authorInfo.displayName }}</text>
                    <text class="comment-modal__hint">评论将绑定您的微信身份，无需填写邮箱</text>
                </view>
            </view>

            <tm-form @submit="fnOnSubmit">
                <tm-input
                    name="content"
                    :vertical="true"
                    required
                    :height="220"
                    input-type="textarea"
                    bg-color="grey-lighten-5"
                    :maxlength="500"
                    :borderBottom="false"
                    placeholder="写下你的评论..."
                    v-model="form.content"
                ></tm-input>
                <view class="comment-modal__footer">
                    <text class="comment-modal__counter">{{ form.content.length }}/500</text>
                    <tm-button navtie-type="form" theme="bg-gradient-blue-accent" size="m" :disabled="submitting">
                        {{ submitting ? '提交中...' : '发表评论' }}
                    </tm-button>
                </view>
            </tm-form>
        </view>
    </tm-poup>
</template>

<script>
import tmPoup from '@/tm-vuetify/components/tm-poup/tm-poup.vue';
import tmForm from '@/tm-vuetify/components/tm-form/tm-form.vue';
import tmInput from '@/tm-vuetify/components/tm-input/tm-input.vue';
import tmButton from '@/tm-vuetify/components/tm-button/tm-button.vue';
import { getCommentAuthorInfo, buildCommentPayload, buildMomentCommentPayload, canSubmitComment, isCommentSerializationError } from '@/utils/comment.js';

export default {
    name: 'CommentModal',
    components: {
        tmPoup,
        tmForm,
        tmInput,
        tmButton
    },
    props: {
        show: {
            type: Boolean,
            default: false
        },
        isComment: {
            type: Boolean,
            default: false
        },
        title: {
            type: String,
            default: ''
        },
        postName: {
            type: String,
            default: ''
        },
        subjectType: {
            type: String,
            default: 'post'
        }
    },
    data() {
        return {
            isShow: false,
            submitting: false,
            form: {
                content: '',
                postName: ''
            }
        };
    },
    computed: {
        authorInfo() {
            return getCommentAuthorInfo();
        },
        isReply() {
            return !this.isComment;
        },
        replyTargetName() {
            if (this.isComment) return '';
            return (this.title || '').replace(/^回复用户：/, '');
        },
        calcTitle() {
            if (this.isComment) {
                return this.title || '发表评论';
            }
            return '回复评论';
        }
    },
    watch: {
        show: {
            immediate: true,
            handler(newVal) {
                if (!newVal) return;
                if (!canSubmitComment()) {
                    uni.$tm.toast('请先完成微信登录后再评论');
                    this.$emit('on-close', { isSubmit: false, refresh: false, needLogin: true });
                    return;
                }
                this.isShow = true;
                this.handleResetForm();
                this.form.postName = this.postName;
            }
        }
    },
    methods: {
        handleResetForm() {
            this.submitting = false;
            this.form = {
                content: '',
                postName: this.postName || ''
            };
        },
        fnOnSubmit(e) {
            if (e === false) {
                return uni.$tm.toast('请填写评论内容');
            }
            const content = (this.form.content || '').trim();
            if (!content) {
                return uni.$tm.toast('请填写评论内容');
            }
            if (!canSubmitComment()) {
                return uni.$tm.toast('请先完成微信登录后再评论');
            }
            this.fnHandle(content);
        },
        fnHandle(content) {
            if (this.submitting) return;
            this.submitting = true;
            uni.showLoading({ title: '正在提交...', mask: true });

            const finish = () => {
                uni.hideLoading();
                this.submitting = false;
            };
            const onSuccess = (msg) => {
                finish();
                uni.$tm.toast(msg);
                this.handleClose(true);
                this.handleResetForm();
            };
            const onFail = (err) => {
                finish();
                if (isCommentSerializationError(err)) {
                    uni.$tm.toast('评论可能已提交，正在刷新列表');
                    this.handleClose(true);
                    this.handleResetForm();
                    return;
                }
                const msg = err?.message || err?.error || err?.detail || (typeof err === 'string' ? err : '提交失败，请稍后重试');
                uni.$tm.toast(msg);
            };

            const buildPayload = (options) => {
                if (this.subjectType === 'moment') {
                    return buildMomentCommentPayload(options);
                }
                return buildCommentPayload(options);
            };

            const submitPromise = this.isComment
                ? this.$httpApi.v2.addPostComment(buildPayload({
                    content,
                    postName: this.form.postName,
                    isReply: false
                }))
                : this.$httpApi.v2.addPostCommentReply(
                    this.form.postName,
                    buildPayload({
                        content,
                        postName: this.form.postName,
                        isReply: true,
                        quoteReply: this.form.postName
                    })
                );

            submitPromise
                .then(() => onSuccess(this.isComment ? '评论成功，可能需要审核' : '回复成功，可能需要审核'))
                .catch(onFail);
        },
        handleOnChange(isOpen) {
            this.isShow = isOpen;
            if (!isOpen) {
                this.$emit('on-close', {
                    isSubmit: false,
                    refresh: false
                });
            }
        },
        handleClose(refresh = false) {
            this.isShow = false;
            this.$emit('on-close', {
                isSubmit: true,
                refresh
            });
        }
    }
};
</script>

<style lang="scss" scoped>
.comment-modal {
    padding: 24rpx 24rpx calc(24rpx + env(safe-area-inset-bottom));

    &__header {
        margin-bottom: 24rpx;
    }

    &__title {
        display: block;
        text-align: center;
        font-size: 32rpx;
        font-weight: 600;
        color: #303133;
    }

    &__subtitle {
        display: block;
        text-align: center;
        font-size: 24rpx;
        color: #909399;
        margin-top: 8rpx;
    }

    &__author {
        display: flex;
        align-items: center;
        padding: 20rpx 24rpx;
        margin-bottom: 16rpx;
        background: #f5f7fa;
        border-radius: 12rpx;
    }

    &__avatar {
        width: 72rpx;
        height: 72rpx;
        border-radius: 12rpx;
        flex-shrink: 0;
        border: 2rpx solid #fff;
        box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, 0.06);

        &.is-radius {
            border-radius: 50%;
        }
    }

    &__author-info {
        flex: 1;
        margin-left: 16rpx;
        min-width: 0;
    }

    &__nickname {
        display: block;
        font-size: 28rpx;
        font-weight: 600;
        color: #303133;
    }

    &__hint {
        display: block;
        font-size: 22rpx;
        color: #909399;
        margin-top: 4rpx;
    }

    &__footer {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: 16rpx 8rpx 0;
    }

    &__counter {
        font-size: 24rpx;
        color: #c0c4cc;
    }
}
</style>
