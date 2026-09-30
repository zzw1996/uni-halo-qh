<template>
    <view :class="skinClass" class="app-page">
        <!--
            订阅墙引导条：未订阅的分类列表可以随便看，读全文需要订阅。
            服务端订阅墙开启时才出现——墙没开时喊「先订阅才能看」是在骗人。
        -->
        <view v-if="calcShowSubBar" class="sub-bar">
            <view class="sub-bar-main">
                <text class="sub-bar-title">{{ subBarTitle }}</text>
                <text class="sub-bar-desc">{{ subBarDesc }}</text>
            </view>
            <view class="sub-bar-btn" @click="fnSubscribeFromBar">
                <text class="sub-bar-btn-text">{{ subBarBtnText }}</text>
            </view>
        </view>
        <view v-if="loading != 'success'" class="loading-wrap">
            <tm-skeleton model="listAvatr"></tm-skeleton>
            <tm-skeleton model="listAvatr"></tm-skeleton>
            <tm-skeleton model="listAvatr"></tm-skeleton>
            <tm-skeleton model="listAvatr"></tm-skeleton>
        </view>
        <block v-else>
            <view class="empty" v-if="dataList.length == 0">
                <tm-empty icon="icon-shiliangzhinengduixiang-"
                          label="该分类下暂无文章"></tm-empty>
            </view>
            <block v-else>
                <block v-for="(article, index) in dataList" :key="article.createTime">
                    <!-- 文章卡片 -->
                    <tm-translate animation-name="fadeUp" :wait="calcAniWait(index)">
                        <article-card :article="article"
                                      @on-click="fnToArticleDetail"></article-card>
                    </tm-translate>
                </block>
                <view class="load-text">{{ loadMoreText }}</view>
            </block>

            <tm-flotbutton @click="fnToTopPage" color="indigo" size="m" icon="icon-angle-up"></tm-flotbutton>
        </block>

        <!-- 微信登录弹窗（游客在引导条上点「登录并订阅」） -->
        <wx-login-dialog
            :show.sync="loginDialogShow"
            title="登录后订阅"
            desc="订阅分类后即可阅读该分类下的全部文章"
            @success="fnOnSubscribeLoginSuccess"
        ></wx-login-dialog>
    </view>
</template>

<script>
import tmSkeleton from '@/tm-vuetify/components/tm-skeleton/tm-skeleton.vue';
import loadMoreMixin from '@/common/mixins/loadMore.js';
import tmTranslate from '@/tm-vuetify/components/tm-translate/tm-translate.vue';
import tmFlotbutton from '@/tm-vuetify/components/tm-flotbutton/tm-flotbutton.vue';
import tmEmpty from '@/tm-vuetify/components/tm-empty/tm-empty.vue';
import ArticleCard from '@/components/article-card/article-card.vue';
import wxLoginDialog from '@/components/wx-login-dialog/wx-login-dialog.vue';
import { fetchCategorySubscription, subscribeCategory, subscriptionLimit } from '@/utils/categorySubscription.js';

export default {
    mixins: [loadMoreMixin],
    components: {
        tmSkeleton,
        tmTranslate,
        tmFlotbutton,
        tmEmpty,
        ArticleCard,
        wxLoginDialog
    },
    data() {
        return {
            loading: 'loading',
            queryParams: {
                size: 10,
                page: 0
            },
            name: '',
            pageTitle: '加载中...',
            hasNext: false,
            dataList: [],
            isLoadMore: false,
            loadMoreText: '',
            /** 订阅状态（含服务端额度）：引导条的内容与动作由它决定 */
            subState: null,
            subscribing: false,
            loginDialogShow: false
        };
    },

    onLoad(e) {
        this.name = e.name;
        this.pageTitle = e.title;
        this.fnGetData();
        this.fnLoadSubscriptionState();
    },
    onPullDownRefresh() {
        this.isLoadMore = false;
        this.fnLoadMoreDone();
        this.queryParams.page = 0;
        this.fnGetData();
        this.fnLoadSubscriptionState();
    },
    /** 我自己的邀请码；取不到就不带，不影响分享 */
    fnInviteCode() {
        try {
            return uni.getStorageSync('invite.code') || '';
        } catch (e) {
            return '';
        }
    },
    /**
     * 分享分类：带上我的邀请码，别人点进来就能看到我关注的内容。
     *
     * 朋友圈分享必须用 query（微信只接受 query），原来写成 path 会丢参数。
     */
    onShareAppMessage() {
        const code = this.fnInviteCode();
        return {
            title: this.pageTitle,
            path: `/pagesA/category-detail/category-detail?name=${this.name}&title=${this.pageTitle}`
                + (code ? `&inviteCode=${encodeURIComponent(code)}` : '')
        }
    },
    onShareTimeline() {
        const code = this.fnInviteCode();
        return {
            title: this.pageTitle,
            query: Object.assign(
                { name: this.name, title: this.pageTitle },
                code ? { inviteCode: code } : {}
            )
        }
    },
    onReachBottom() {
        this.fnLoadMore(this.hasNext, () => {
            this.queryParams.page += 1;
            this.isLoadMore = true;
            this.fnGetData();
        });
    },
    computed: {
        /** 服务端订阅墙开关：没开启时引导条不出现（墙没有，喊「先订阅才能看」就是骗人） */
        calcWallEnabled() {
            return this.$tm.vx.getters().getConfigs?.subscriptionWallConfig?.enabled === true;
        },
        calcSubscribedHere() {
            return !!this.subState?.subscribed?.includes(this.name);
        },
        calcPausedHere() {
            return !!this.subState?.limits?.pausedCategories?.includes(this.name);
        },
        calcShowSubBar() {
            // subState 未加载完成前不渲染：登录用户会先闪一瞬「未订阅」，观感很差
            return this.calcWallEnabled && !!this.name && !!this.subState
                && !this.calcSubscribedHere;
        },
        subBarTitle() {
            if (!this.subState?.loggedIn) {
                return `订阅「${this.pageTitle}」后可阅读全文`;
            }
            if (this.calcPausedHere) {
                return '订阅已暂停';
            }
            return this.calcShowSubscribeBtn ? '该分类还未订阅' : '订阅额度已满';
        },
        subBarDesc() {
            if (!this.subState?.loggedIn) {
                return '登录并订阅后，可阅读该分类下的全部文章';
            }
            if (this.calcPausedHere) {
                return '解锁额度或续费会员后可继续阅读本分类文章';
            }
            return this.calcShowSubscribeBtn
                ? `订阅后可阅读全文，已订阅 ${(this.subState.subscribed || []).length}/${subscriptionLimit(this.subState.limits)} 个分类`
                : '可用积分解锁额度，或开通会员订阅不限量';
        },
        subBarBtnText() {
            if (!this.subState?.loggedIn) {
                return '登录并订阅';
            }
            return (this.calcPausedHere || !this.calcShowSubscribeBtn) ? '去解锁' : '立即订阅';
        },
        /** 额度是否还有空位（暂停的分类仍占位置） */
        calcShowSubscribeBtn() {
            if (!this.subState) {
                return false;
            }
            return (this.subState.subscribed || []).length < subscriptionLimit(this.subState.limits);
        }
    },
    methods: {
        /** 读取订阅状态：引导条只在「墙开着 + 未订阅」时出现 */
        async fnLoadSubscriptionState() {
            this.subState = await fetchCategorySubscription(this.$httpApi.v2);
        },
        /** 引导条动作：登录 / 订阅 / 去发现页解锁（那边有完整的额度管理） */
        async fnSubscribeFromBar() {
            if (!this.subState?.loggedIn) {
                this.loginDialogShow = true;
                return;
            }
            if (this.calcPausedHere || !this.calcShowSubscribeBtn) {
                uni.switchTab({ url: '/pages/tabbar/discover/discover' });
                return;
            }
            if (this.subscribing || !this.name) {
                return;
            }
            this.subscribing = true;
            try {
                const result = await subscribeCategory(this.$httpApi.v2, this.name);
                if (result.ok) {
                    uni.showToast({ title: '订阅成功', icon: 'success' });
                    this.subState = {
                        loggedIn: true,
                        subscribed: result.saved || [],
                        inherited: result.inherited || [],
                        limits: result.limits || this.subState.limits
                    };
                    return;
                }
                if (result.reason === 'not-logged-in') {
                    this.loginDialogShow = true;
                    return;
                }
                if (result.reason === 'quota-full') {
                    uni.switchTab({ url: '/pages/tabbar/discover/discover' });
                    return;
                }
                uni.showToast({ title: result.reason || '订阅失败，请稍后重试', icon: 'none' });
            } finally {
                this.subscribing = false;
            }
        },
        /** 引导条登录成功：接着把当前分类订阅上（用户点按钮的意图就是订阅） */
        async fnOnSubscribeLoginSuccess() {
            this.subState = await fetchCategorySubscription(this.$httpApi.v2);
            if (this.calcSubscribedHere || !this.calcShowSubscribeBtn || !this.name) {
                return;
            }
            const result = await subscribeCategory(this.$httpApi.v2, this.name);
            if (result.ok) {
                uni.showToast({ title: '订阅成功', icon: 'success' });
                this.subState = {
                    loggedIn: true,
                    subscribed: result.saved || [],
                    inherited: result.inherited || [],
                    limits: result.limits || this.subState.limits
                };
            }
        },
        fnGetData() {
            // uni.showLoading({
            // 	mask: true,
            // 	title: '加载中...'
            // });
            // 设置状态为加载中
            if (!this.isLoadMore) {
                this.loading = 'loading';
            }
            this.loadMoreText = '加载中...';
            this.$httpApi.v2
                .getCategoryPostList(this.name, this.queryParams)
                .then(res => {
                    console.log("请求成功：", res)
                    this.fnSetPageTitle(`${this.pageTitle} （共${res.total}篇）`);
                    this.hasNext = res.hasNext;
                    if (this.isLoadMore) {
                        this.dataList = this.dataList.concat(res.items);
                    } else {
                        this.dataList = res.items;
                    }
                    this.loadMoreText = res.hasNext ? '上拉加载更多' : '呜呜，没有更多数据啦~';
                    setTimeout(() => {
                        this.loading = 'success';
                    }, 500);
                })
                .catch(err => {
                    console.error(err);
                    this.loading = 'error';
                    this.loadMoreText = '加载失败，请下拉刷新！';
                })
                .finally(() => {
                    setTimeout(() => {
                        uni.hideLoading();
                        uni.stopPullDownRefresh();
                        this.fnLoadMoreDone();
                    }, 500);
                });
        },
        //跳转文章详情
        fnToArticleDetail(article) {
            uni.navigateTo({
                url: '/pagesA/article-detail/article-detail?name=' + article.metadata.name,
                animationType: 'slide-in-right'
            });
        }
    }
};
</script>

<style lang="scss">
.app-page {
    width: 100vw;
    min-height: 100vh;
    display: flex;
    flex-direction: column;
    padding: 24rpx 0;
    background-color: $uh-bg;
}

.loading-wrap {
    padding: 0 24rpx;
    min-height: 100vh;
}

/* 订阅墙引导条：列表可见、全文需订阅的提示与动作 */
.sub-bar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin: 0 24rpx 8rpx;
    padding: 16rpx 20rpx;
    border-radius: $uh-radius-md;
    background: $uh-card;
    box-shadow: $uh-shadow-sm;
}

.sub-bar-main {
    display: flex;
    flex-direction: column;
    flex: 1;
    min-width: 0;
}

.sub-bar-title {
    font-size: 26rpx;
    font-weight: 600;
    color: $uh-ink-1;
}

.sub-bar-desc {
    margin-top: 4rpx;
    font-size: 22rpx;
    color: $uh-ink-2;
    overflow: hidden;
    text-overflow: ellipsis;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
}

.sub-bar-btn {
    flex-shrink: 0;
    margin-left: 16rpx;
    padding: 12rpx 24rpx;
    border-radius: $uh-radius-md;
    background: $uh-accent;

    .sub-bar-btn-text {
        font-size: 24rpx;
        color: #ffffff;
    }
}

.empty {
    height: 60vh;
    display: flex;
    align-items: center;
    justify-content: center;
}
</style>
