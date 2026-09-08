<template>
    <view class="app-page" :style="{
		padding:calcShowType==='list-post'? 0 : '24rpx 0'
	}">
        <view v-if="loading !== 'success'" class="loading-wrap">
            <tm-skeleton model="listAvatr"></tm-skeleton>
            <tm-skeleton model="listAvatr"></tm-skeleton>
            <tm-skeleton model="listAvatr"></tm-skeleton>
        </view>
        <!-- 内容区域 -->
        <view v-else class="app-page-content" :class="[calcShowType==='list-post'?'list-post':'']">
            <view v-if="dataList.length === 0" class="content-empty flex flex-center" style="height: 70vh;">
                <!-- 空布局 -->
                <tm-empty icon="icon-shiliangzhinengduixiang-" label="暂无数据"></tm-empty>
            </view>
            <block v-else>
                <block v-if="calcAuditModeEnabled || calcShowType==='list'">
                    <!-- 分类统计 -->
                    <view class="category-stats">
                        <text class="cs-item"><text class="cs-num">{{ dataList.length }}</text> 个分类</text>
                        <text class="cs-divider">·</text>
                        <text class="cs-item"><text class="cs-num">{{ totalPostCount }}</text> 篇文章</text>
                        <text class="cs-tip">点击卡片查看</text>
                    </view>
                    <!-- 分类宫格 -->
                    <view class="category-grid">
                        <tm-translate v-for="(item, index) in dataList" :key="index"
                                      style="height: 100%;" animation-name="fadeUp"
                                      :wait="calcAniWait(index)">
                            <view class="catgory-card" :class="{ 'catgory-card--featured': index === 0 }">
                                <image
                                    v-if="item.spec.cover"
                                    class="cat-bg"
                                    :src="item.spec.cover"
                                    mode="aspectFill"
                                    lazy-load
                                ></image>
                                <view v-else class="cat-bg cat-bg--fallback"></view>
                                <view class="content" @click="handleToCategory(item)">
                                    <text class="cat-title">{{ item.spec.displayName }}</text>
                                    <text v-if="index === 0 && item.spec.description"
                                          class="cat-desc">{{ item.spec.description }}</text>
                                    <text v-if="!calcAuditModeEnabled"
                                          class="cat-count">共 {{ item.postCount }} 篇</text>
                                </view>
                            </view>
                        </tm-translate>
                    </view>
                    <view class="load-text">{{ loadMoreText }}</view>
                </block>
                <view v-else-if="calcShowType==='list-post'" class="fulled flex" style="min-height:100vh">
                    <view class="bg-white" :style="{height: '100%'}">
                        <tm-sliderNav :list="categoryList" bg-color="white" color="light-blue" rang-key="displayName"
                                      @change="fnOnCategoryChange"></tm-sliderNav>
                    </view>
                    <scroll-view class="right-content pt-12 pb-12" :scroll-y="true" :scroll-top="scrollTop"
                                 :scroll-with-animation="true" :refresher-enabled="true" :refresher-triggered="triggered"
                                 :refresher-threshold="60" refresher-background="#fafafa"
                                 @refresherrefresh="fnGetPostByCategory(true)" @scrolltolower="fnGetPostByCategory(false)"
                                 @scroll="fnOnScroll" @touchmove.stop @touchstart="fnOnTouchStart" @touchend="fnOnTouchEnd"
                                 @touchcancel="fnOnTouchEnd">
                        <view v-if="postList.length === 0" class="article-empty flex flex-center">
                            <tm-empty :size="120" icon="icon-shiliangzhinengduixiang-" label="该分类下暂无文章~"></tm-empty>
                        </view>
                        <block v-else>
                            <block v-for="(post, index) in postList" :key="post.spec.publishTime">
                                <tm-translate animation-name="fadeUp" :wait="calcAniWait(index)">
                                    <article-min-card :article="post" @on-click="fnToArticleDetail"></article-min-card>
                                </tm-translate>
                            </block>
                            <view class="load-text">{{ loadMoreText }}</view>
                        </block>
                    </scroll-view>
                </view>

                <view class="flot-buttons">
                    <tm-button @click="fnScrollTop" size="m" :fab="true" theme="light-blue"
                               icon="icon-angle-up"></tm-button>
                    <tm-button v-if="!calcAuditModeEnabled" @click="fnChangeShowType" size="m" :fab="true" theme="light-blue"
                               :icon="calcShowType==='list'?'icon-align-left':'icon-all'"></tm-button>
                </view>
            </block>
        </view>

    </view>
</template>

<script>
import tmSkeleton from '@/tm-vuetify/components/tm-skeleton/tm-skeleton.vue';
import tmButton from '@/tm-vuetify/components/tm-button/tm-button.vue';
import tmTranslate from '@/tm-vuetify/components/tm-translate/tm-translate.vue';
import tmEmpty from '@/tm-vuetify/components/tm-empty/tm-empty.vue';
import tmSliderNav from '@/tm-vuetify/components/tm-sliderNav/tm-sliderNav.vue';
import ArticleMinCard from '@/components/article-min-card/article-min-card.vue';


export default {
    components: {
        tmSkeleton,
        tmButton,
        tmTranslate,
        tmEmpty,
        tmSliderNav,
        ArticleMinCard
    },
    data() {
        return {
            loading: 'loading',
            queryParams: {
                size: 20,
                page: 1,
                fieldSelector: ['spec.hideFromList=false']
            },
            hasNext: false,
            dataList: [],
            categoryList: [],
            isLoadMore: false,
            loadMoreText: '加载中...',
            currentCategoryConfig: {
                type: "list"
            },
            currentCategoryName: "",
            postQueryParams: {
                size: 10,
                page: 0,
            },
            postList: [],
            triggered: false,
            scrollTop: 0,
            tempScrollTop: 0,
            scrollTimeout: null,
        };
    },
    computed: {
        haloConfigs() {
            return this.$tm.vx.getters().getConfigs;
        },
        calcAuditModeEnabled() {
            return this.haloConfigs.auditConfig.auditModeEnabled
        },
        mockJson() {
            return this.$tm.vx.getters().getMockJson;
        },
        categoryConfig() {
            return this.haloConfigs.pageConfig.categoryConfig
        },
        calcShowType() {
            return this.currentCategoryConfig.type
        },
        totalPostCount() {
            return (this.dataList || []).reduce((sum, item) => sum + (Number(item.postCount) || 0), 0)
        }
    },
    watch: {
        categoryConfig: {
            deep: true,
            immediate: true,
            handler(newVal) {
                console.log("执行了", newVal)
                if (!newVal) return;
                this.currentCategoryConfig = newVal
                this.handleInitPage()
            },
        }
    },
    onPullDownRefresh() {
        this.isLoadMore = false;
        this.queryParams.page = 0;
        this.fnGetData();
    },
    onReachBottom(e) {
        if (this.calcAuditModeEnabled) {
            uni.showToast({
                icon: 'none',
                title: '没有更多数据了'
            });
            return
        }
        if (this.hasNext) {
            if (this.calcShowType === 'list') {

                this.queryParams.page += 1;
            } else {
                this.postQueryParams.page += 1;
            }
            this.isLoadMore = true;
            this.fnGetData();
        } else {
            uni.showToast({
                icon: 'none',
                title: '没有更多数据了'
            });
        }
    },
    methods: {
        fnChangeShowType() {
            if (this.calcShowType === 'list-post') {
                this.currentCategoryConfig.type = 'list'
            } else {
                this.currentCategoryConfig.type = 'list-post'
            }
            this.handleInitPage();
        },
        handleResetInit() {
            this.postList = []
            this.dataList = []
            this.categoryList = []
            this.queryParams.page = 0;
            this.postQueryParams.page = 0;
            this.hasNext = false
            this.isLoadMore = false
            this.loadMoreText = '加载中...'
            this.currentCategoryName = ""
            this.triggered = false
            this.fnResetSetAniWaitIndex()
        },
        handleInitPage() {
            this.handleResetInit()
            if (this.calcShowType === 'list-post') {
                this.queryParams.size = 99999
            }
            this.fnGetData();
        },
        fnGetData() {
            if (this.calcAuditModeEnabled) {
                this.categoryConfig.type = "list"
                this.dataList = this.mockJson.category.list.map((item) => {
                    return {
                        metadata: {
                            name: Date.now() * Math.random(),
                        },
                        spec: {
                            displayName: item.title,
                            cover: this.$utils.checkImageUrl(item.cover)
                        },
                        postCount: 0
                    }
                });
                this.loading = 'success';
                this.loadMoreText = '呜呜，没有更多数据啦~';
                uni.hideLoading();
                uni.stopPullDownRefresh();
                return;
            }
            uni.showLoading({
                mask: true,
                title: '加载中...'
            });
            // 设置状态为加载中
            if (!this.isLoadMore) {
                this.loading = 'loading';
            }
            this.loadMoreText = '加载中...';
            this.$httpApi.v2
                .getCategoryList(this.queryParams)
                .then(res => {
                    // console.log('请求结果：');
                    // console.log(res);
                    if (this.calcShowType === 'list') {
                        this.loading = 'success';
                        this.loadMoreText = res.hasNext ? '上拉加载更多' : '呜呜，没有更多数据啦~';
                        // 处理数据
                        this.hasNext = res.hasNext;

                        const tempItems = res.items.map(item => {
							item.postCount = item.postCount ?? 0
                            item.spec.cover = this.$utils.checkThumbnailUrl(item.spec.cover, true)
                            return item;
                        })

                        if (this.isLoadMore) {
                            this.dataList = this.dataList.concat(tempItems);
                        } else {
                            this.dataList = tempItems;
                        }
                    } else {
                        this.dataList = res.items
                        this.categoryList = res.items.map(item => {
                            return {
                                displayName: item.spec.displayName,
                                name: item.metadata.name,
                                postCount: item.postCount ?? 0
                            }
                        })
                        this.triggered = false;
                        this.loading = 'success';
                        if (this.dataList.length !== 0) {
                            this.currentCategoryName = this.dataList[0].metadata.name;
                            this.fnGetPostByCategory()
                        }
                    }
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
                    }, 500);
                });
        },
        handlePreview(index, list) {
            uni.previewImage({
                current: index,
                urls: list.map(item => item.url)
            })
        },
        handleToCategory(data) {
            if (this.calcAuditModeEnabled) {
                return;
            }
            uni.navigateTo({
                url: `/pagesA/category-detail/category-detail?name=${data.metadata.name}&title=${data.spec.displayName}`
            })
        },
        fnGetPostByCategory(isPulldownRefresh = true, triggered = true) {
            if (!isPulldownRefresh) {
                if (this.hasNext) {
                    this.postQueryParams.page += 1;
                } else {
                    return uni.showToast({
                        icon: 'none',
                        title: '没有更多数据了'
                    });
                }
            } else {
                this.postQueryParams.page = 0;
                if (triggered) {
                    this.triggered = true;
                }
            }

            this.$httpApi.v2
                .getCategoryPostList(this.currentCategoryName, this.postQueryParams)
                .then(res => {
                    this.hasNext = res.hasNext;

                    if (!isPulldownRefresh) {
                        this.postList = this.postList.concat(res.items);
                    } else {
                        this.postList = res.items;
                    }
                    this.loadMoreText = res.hasNext ? '上拉加载更多' : '呜呜，没有更多数据啦~';
                })
                .catch(err => {
                    this.loadMoreText = '加载失败！';
                })
                .finally(() => {
                    this.triggered = false;
                });
        },

        fnOnCategoryChange(e) {
            this.fnResetSetAniWaitIndex();
            this.currentCategoryName = this.dataList[e].metadata.name;
            this.fnToTopScroll();
            this.postList = [];
            this.fnGetPostByCategory();
        },

        fnOnScroll(e) {
            this.tempScrollTop = e.detail.scrollTop;
        },
        fnToTopScroll() {
            uni.pageScrollTo({
                scrollTop: 0,
                duration: 500
            });
            this.scrollTop = 0;
            this.tempScrollTop = 0;
        },
        fnOnTouchStart() {
            clearTimeout(this.scrollTimeout);
        },
        fnOnTouchEnd() {
            this.scrollTimeout = setTimeout(() => {
                this.scrollTop = this.tempScrollTop;
            }, 500);
        },
        //跳转文章详情
        fnToArticleDetail(post) {
            uni.navigateTo({
                url: '/pagesA/article-detail/article-detail?name=' + post.metadata.name,
                animationType: 'slide-in-right'
            });
        },
		fnScrollTop() {
			if (this.calcShowType === 'list') {
				this.fnToTopPage()
			} else {
				this.fnToTopScroll()
			}
		}
	}
};
</script>

<style lang="scss" scoped>
.app-page {
    width: 100vw;
    display: flex;
    flex-direction: column;
    padding: 24rpx 0;
}

.auditModeEnabled {
    width: 100%;
    height: 80vh;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
}

.loading-wrap {
    padding: 24rpx;
}

.app-page-content {
    display: flex;
    flex-wrap: wrap;
    padding: 0;
    gap: 20rpx 0;

    &.list-post {
        padding: 0;
        gap: 0;
    }
}

/* ===== 分类宫格 ===== */
.category-grid {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    gap: 16rpx;
    padding: 24rpx 24rpx 0;
    width: 100%;
    box-sizing: border-box;
}

.catgory-card {
    width: 100%;
    height: 200rpx;
    position: relative;
    display: flex;
    flex-direction: column;
    box-sizing: border-box;
    border-radius: 20rpx;
    background-color: #fff;
    box-shadow: 0 6rpx 20rpx rgba(31, 41, 55, 0.08);
    overflow: hidden;
}

.cat-bg {
    position: absolute;
    left: 0;
    top: 0;
    width: 100%;
    height: 100%;

    &--fallback {
        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
    }
}

.catgory-card:before {
    content: '';
    position: absolute;
    left: 0;
    top: 0;
    right: 0;
    bottom: 0;
    background: linear-gradient(180deg, rgba(0, 0, 0, 0.04) 0%, rgba(0, 0, 0, 0.42) 100%);
    z-index: 1;
    border-radius: 20rpx;
}

.content {
    width: 100%;
    height: 100%;
    position: relative;
    z-index: 2;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: 0 20rpx;
    box-sizing: border-box;
}

.cat-title {
    font-size: 30rpx;
    font-weight: 700;
    color: #ffffff;
    text-shadow: 0 2rpx 10rpx rgba(0, 0, 0, 0.35);
    text-align: center;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    max-width: 100%;
}

.cat-count {
    margin-top: 10rpx;
    font-size: 20rpx;
    color: #ffffff;
    background: rgba(255, 255, 255, 0.22);
    border-radius: 999rpx;
    padding: 6rpx 18rpx;
    line-height: 1;
}

.load-text {
    width: 100%;
    text-align: center;
    padding: 24rpx 0 16rpx;
    font-size: 22rpx;
    color: #909399;
}

/* ===== 分类统计条 ===== */
.category-stats {
    display: flex;
    align-items: baseline;
    padding: 20rpx 28rpx 4rpx;
}

.cs-item {
    font-size: 24rpx;
    color: #909399;
}

.cs-num {
    font-size: 30rpx;
    font-weight: 800;
    color: #667eea;
    margin-right: 6rpx;
}

.cs-divider {
    margin: 0 14rpx;
    color: #d0d3da;
}

.cs-tip {
    margin-left: auto;
    font-size: 20rpx;
    color: #b0b5c0;
}

/* ===== 首卡大图 ===== */
.catgory-card--featured {
    grid-column: 1 / -1;
    height: 260rpx;

    .content {
        align-items: flex-start;
        padding: 0 32rpx;
    }

    .cat-title {
        font-size: 36rpx;
    }

    .cat-count {
        margin-top: 10rpx;
    }
}

.cat-desc {
    margin-top: 8rpx;
    max-width: 82%;
    font-size: 22rpx;
    color: rgba(255, 255, 255, 0.88);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

/* ===== 左右分栏模式 ===== */
.right-content {
    background: #fafafa;
    padding-left: 24rpx;
    padding-right: 24rpx;
    box-sizing: border-box;
}

.flot-buttons {
    position: fixed;
    bottom: 100rpx;
    right: 32rpx;
    flex-direction: column;
    display: flex;
    gap: 6rpx;
    z-index: 999;
}
</style>
