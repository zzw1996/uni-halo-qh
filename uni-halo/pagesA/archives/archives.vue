<template>
    <view :class="skinClass" class="app-page">

        <view class="e-fixed">
            <tm-tabs color="indigo" v-model="tab.activeIndex" :list="tab.list" align="center"
                     @change="fnOnTabChange"></tm-tabs>
        </view>
        <!-- 占位区域 -->
        <view style="width: 100vw;height: 90rpx;"></view>

        <!-- 骨架屏：加载区域 -->
        <view v-if="loading !== 'success'" class="loading-wrap">
            <tm-skeleton model="listAvatr"></tm-skeleton>
            <tm-skeleton model="listAvatr"></tm-skeleton>
            <tm-skeleton model="listAvatr"></tm-skeleton>
        </view>

        <!-- 加载完成区域 -->
        <block v-else>
            <view v-if="dataList.length === 0" class="list-empty flex flex-center">
                <tm-empty v-if="calcAuditModeEnabled" icon="icon-shiliangzhinengduixiang-" label="暂无归档的内容"></tm-empty>
                <tm-empty v-else icon="icon-shiliangzhinengduixiang-" label="暂无归档的文章"></tm-empty>
            </view>
            <!--
                分组列表：年月作为分组头，文章按组紧凑排列。
                早前这里套了时间轴组件——左侧一整列（110rpx）只放一个点加一条线，
                在手机宽度下等于白扔掉 1/7 的横向空间，标题被迫折行；分组头本身已经承担了
                时间轴的信息，所以整列去掉，只在分组标题前留一个小圆点。
            -->
            <view v-else class="arch-list">
                <view v-for="(item, index) in dataList" :key="index" class="arch-group-wrap">
                    <view class="arch-group">
                        <view class="arch-group_dot"></view>
                        <text class="arch-group_title">{{ item.year }}年<text
                                v-if="tab.activeIndex === 0">{{ item.month }}月</text></text>
                        <text class="arch-group_count">{{ item.posts.length }} 篇</text>
                    </view>
                    <block v-if="item.posts.length !== 0">
                        <view v-for="(post, postIndex) in item.posts" :key="post.metadata.name" class="arch-item"
                              hover-class="arch-item--pressed" :hover-stay-time="60" @click="fnToArticleDetail(post)">
                            <image v-if="calcShowThumb" class="arch-item_thumb"
                                   :src="$utils.checkThumbnailUrl(post.spec.cover)" mode="aspectFill" lazy-load></image>
                            <view class="arch-item_body">
                                <text class="arch-item_title text-sub-more-2">{{ post.spec.title }}</text>
                                <view class="arch-item_meta">
                                    <text class="iconfont icon-clock arch-item_meta-icon"></text>
                                    <text>{{ fnArchiveTime(post.spec.publishTime) }}</text>
                                </view>
                            </view>
                        </view>
                    </block>
                    <view v-else class="arch-empty">这里还没有归档文章</view>
                </view>
            </view>
            <view class="load-text mt-12">{{ loadMoreText }}</view>
            <!-- 返回顶部 -->
            <tm-flotbutton @click="fnToTopPage" size="m" color="bg-gradient-indigo-accent"
                           icon="icon-angle-up"></tm-flotbutton>
        </block>
    </view>
</template>

<script>
import tmSkeleton from '@/tm-vuetify/components/tm-skeleton/tm-skeleton.vue';
import loadMoreMixin from '@/common/mixins/loadMore.js';
import tmFlotbutton from '@/tm-vuetify/components/tm-flotbutton/tm-flotbutton.vue';
import tmTabs from '@/tm-vuetify/components/tm-tabs/tm-tabs.vue';
import tmEmpty from '@/tm-vuetify/components/tm-empty/tm-empty.vue';
import qs from 'qs'
import { toTimestamp } from '@/utils/time.js'

export default {
    mixins: [loadMoreMixin],
    components: {
        tmSkeleton,
        tmFlotbutton,
        tmTabs,
        tmEmpty
    },
    data() {
        return {
            loading: 'loading',
            tab: {
                activeIndex: 0,
                list: ['按月份查看', '按年份查看']
            },
            queryParams: {
                size: 10,
                page: 1
            },
            result: {},
            cacheDataList: [], // 所有请求的缓存数据
            dataList: [], // 显示的数据
            isLoadMore: false,
            loadMoreText: "加载中..."
        };
    },
    computed: {
        haloConfigs() {
            return this.$tm.vx.getters().getConfigs;
        },
        mockJson() {
            return this.$tm.vx.getters().getMockJson;
        },
        calcAuditModeEnabled() {
            return this.haloConfigs.auditConfig.auditModeEnabled
        },
        /**
         * 行内是否显示缩略图。
         *
         * 归档页不跟随「文章卡片样式」里的上图下文/大卡片排版——那是给首页feed 用的，
         * 放在归档里会把每篇文章撑成半屏高（一屏看不到几条，失去「按时间扫标题」的意义）。
         * 只有用户明确选了「纯文字」时才不显示缩略图，尊重这个偏好的意图。
         */
        calcShowThumb() {
            return this.globalAppSettings?.layout?.cardType !== 'only_text';
        },
    },
    created() {
        this.fnGetData();
    },
    onPullDownRefresh() {
        this.isLoadMore = false;
        this.fnLoadMoreDone();
        this.queryParams.page = 1;
        this.fnGetData();
    },
    onReachBottom() {
        if (this.calcAuditModeEnabled) {
            this.fnLoadMoreNoMore();
            return;
        }
        this.fnLoadMore(this.result.hasNext, () => {
            this.queryParams.page += 1;
            this.isLoadMore = true;
            this.fnGetData();
        });
    },
    methods: {
        /**
         * 归档行内的时间：只精确到天 + 星期。
         *
         * 用 utils/time.js 的 toTimestamp 解析（旧实现走 `formatTime` 过滤器直接 new Date()，
         * 遇到 null / 秒级时间戳会静默渲染成 1970 年）。
         */
        fnArchiveTime(value) {
            const ts = toTimestamp(value);
            if (ts === null) return '—';
            const d = new Date(ts);
            const week = ['日', '一', '二', '三', '四', '五', '六'][d.getDay()];
            const md = `${d.getMonth() + 1}月${d.getDate()}日`;
            return `${md} · 周${week}`;
        },
        fnOnTabChange(index) {
            this.fnResetSetAniWaitIndex();
            this.queryParams.page = 0;
            this.dataList = this.fnSanitizeGroups(
                this.handleGetShowDataList(this.handleGetPosts(this.cacheDataList)))
            this.fnToTopPage();
        },
        /**
         * 分组数据进页面 data 之前先「洗」一遍。
         *
         * Halo 的 labels 键名带点（content.halo.run/archive-year），而微信的 setData 会把键名当路径解析，
         * 中途读到 null 就抛错并让这次数据更新整体失效——表现是「页面空白/列表不刷新」，
         * 而分组本身早在 handleGetPosts 里用完了这些 label，渲染根本用不到。
         */
        fnSanitizeGroups(groups) {
            return (groups || []).map((group) => ({
                ...group,
                posts: this.$utils.stripDataUnsafeList(group.posts)
            }));
        },
        fnGetData() {
            if (this.calcAuditModeEnabled) {
                const dataList = this.mockJson.archives.list.map(item => {
                    const date = new Date(item.time)
                    const year = date.getFullYear()
                    const month = date.getMonth() + 1
                    return {
                        metadata: {
                            name: Date.now() * Math.random(),
                            labels: {
                                "content.halo.run/archive-year": year,
                                "content.halo.run/archive-month": month
                            }
                        },
                        spec: {
                            pinned: false,
                            cover: item.cover,
                            title: item.title,
                            publishTime: item.time
                        },
                        status: {
                            excerpt: item.desc
                        },
                        stats: {
                            visit: 0
                        }
                    }
                });
                const posts = this.handleGetPosts(dataList)
                this.dataList = []
                this.cacheDataList = dataList;
                this.dataList = this.fnSanitizeGroups(this.handleGetShowDataList(posts))
                this.loading = 'success';
                this.loadMoreText = '呜呜，没有更多数据啦~';
                uni.hideLoading();
                uni.stopPullDownRefresh();
                this.fnLoadMoreDone();
                return;
            }
            // 上拉加载不再弹全屏 loading：列表底部的「加载中...」文案已经够了，
            // 弹窗每加载一页闪一次，正是「一卡一卡」的观感来源之一
            if (!this.isLoadMore) {
                this.loading = 'loading';
            }
            this.loadMoreText = "加载中...";
            const paramsStr = qs.stringify(this.queryParams, {
                allowDots: true,
                encodeValuesOnly: true,
                skipNulls: true,
                encode: true,
                arrayFormat: 'repeat'
            })
            uni.request({
                url: this.$baseApiUrl + '/apis/api.content.halo.run/v1alpha1/posts?' + paramsStr,
                method: 'GET',
                success: (res) => {
                    const data = res.data;
                    this.result = data;
                    const posts = this.handleGetPosts(data.items)
                    const showDataList = this.fnSanitizeGroups(this.handleGetShowDataList(posts))
                    if (this.isLoadMore) {
                        this.cacheDataList = this.handleUniqueCacheDatalist([
                            ...this.cacheDataList, ...data.items
                        ]);
                        this.handleMergeDataList2(showDataList)
                    } else {
                        this.dataList = []
                        this.cacheDataList = data.items;
                        this.dataList = showDataList
                    }

                    this.loading = 'success';
                    this.loadMoreText = data.hasNext ? '上拉加载更多' : '呜呜，没有更多数据啦~';
                    uni.hideLoading();
                    uni.stopPullDownRefresh();
                    this.fnLoadMoreDone();

                },
                fail: (err) => {
                    this.loading = 'error';
                    this.loadMoreText = '加载失败，请下拉刷新！';
                    uni.$tm.toast(err.message || '数据加载失败！');
                    uni.hideLoading();
                    uni.stopPullDownRefresh();
                    this.fnLoadMoreDone();
                }
            })
        },
        // 处理数据分类
        handleGetPosts(dataList) {
            const posts = {}
            const postLabelYearKey = "content.halo.run/archive-year"
            const postLabelMonthKey = "content.halo.run/archive-month"
            dataList.forEach(item => {
                let postItemKey = ""
                if (this.tab.activeIndex === 0) {
                    postItemKey =
                        `${item.metadata.labels[postLabelYearKey]}-${item.metadata.labels[postLabelMonthKey]}`
                } else {
                    postItemKey = `${item.metadata.labels[postLabelYearKey]}`
                }
                if (posts[postItemKey]) {
                    posts[postItemKey].push(item)
                } else {
                    posts[postItemKey] = [item]
                }
            })
            return posts;
        },
        // 根据分类的数据，处理成显示的数据
        handleGetShowDataList(posts) {
            const dataListResult = []
            Object.keys(posts).forEach((key) => {
                const postData = {
                    sort: 0,
                    key: key,
                    year: key,
                    month: "",
                    posts: posts[key]
                }
                if (this.tab.activeIndex == 0) {
                    const splitDate = key.split("-")
                    postData.year = splitDate[0]
                    postData.month = splitDate[1]
                    postData.sort = Number(key.replace("-", ""))
                } else {
                    postData.sort = Number(key)
                }
                dataListResult.push(postData)
            })

            dataListResult.sort((a, b) => {
                return Number(b.sort) - Number(a.sort)
            })

            return dataListResult;
        },
        handleMergeDataList(list1, list2) {
            // 将list1转换为以key为键的对象
            let merged = list1.reduce((acc, item) => {
                acc[item.key] = {
                    ...item
                };
                return acc;
            }, {});

            // 遍历list2，合并posts数组或添加新对象
            list2.forEach(item => {
                if (merged[item.key]) {
                    // 如果key已存在，合并posts数组
                    merged[item.key].posts = [...merged[item.key].posts, ...item.posts];
                } else {
                    // 如果key不存在，添加新对象
                    merged[item.key] = {
                        ...item
                    };
                }
            });

            // 将对象转换回数组
            return Object.values(merged);
        },
        handleMergeDataList2(list) {
            list.forEach((item, index) => {
                const find = this.dataList.find(x => x.key == item.key)
                if (find) {
                    item.posts.forEach(post => {
                        if (!find.posts.find(x => x.metadata.name == post.metadata.name)) {
                            find.posts.push(post)
                        }
                    })
                }
            })
            list.forEach(post => {
                if (!this.dataList.find(x => x.key === post.key)) {
                    this.dataList.push(post)
                }
            })

            this.dataList.sort((a, b) => {
                return Number(b.sort) - Number(a.sort)
            })
        },
        handleUniqueCacheDatalist(dataList) {
            const seen = new Set();
            return dataList.filter(item => {
                return seen.has(item.metadata.name) ? false : seen.add(item.metadata.name);
            });
        },
        fnToArticleDetail(article) {
            if (this.calcAuditModeEnabled) {
                return;
            }
            uni.navigateTo({
                url: '/pagesA/article-detail/article-detail?name=' + article.metadata.name,
                animationType: 'slide-in-right'
            });
        }
    }
};
</script>

<style lang="scss" scoped>
.app-page {
    width: 100vw;
    min-height: 100vh;
    display: flex;
    flex-direction: column;
    background-color: $uh-bg;
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

.list-empty {
    width: 100vw;
    height: 100vh;
}

.statistics {
    background-color: $uh-card;
}

/* 分组列表容器：左右各留 24rpx，卡片直接吃满剩余宽度 */
.arch-list {
    padding: 24rpx 24rpx 0;
}

.arch-group-wrap + .arch-group-wrap {
    margin-top: 16rpx;
}

/* ===== 归档行：紧凑列表（缩略图 + 标题 + 日期） =====
   归档的使用场景是「按时间扫标题」，所以这里刻意不用首页那种大卡片排版：
   一行一条，一屏能看到 6~8 条，而不是 1~2 条。 */

.arch-group {
    display: flex;
    align-items: center;
    margin-bottom: 16rpx;
}

/* 分组标题前的小圆点：替代原来整列时间轴，保留一点「时间线」的语言 */
.arch-group_dot {
    flex-shrink: 0;
    width: 14rpx;
    height: 14rpx;
    margin-right: 12rpx;
    border-radius: 50%;
    background-color: $uh-accent;
    box-shadow: 0 0 0 6rpx $uh-accent-weak;
}

.arch-group_title {
    font-size: 30rpx;
    font-weight: 600;
    color: $uh-ink-1;
}

.arch-group_count {
    margin-left: 12rpx;
    padding: 2rpx 14rpx;
    border-radius: $uh-radius-pill;
    background-color: $uh-accent-weak;
    font-size: 20rpx;
    color: $uh-accent;
}

.arch-item {
    display: flex;
    width: 100%;
    align-items: center;
    box-sizing: border-box;
    padding: 16rpx;
    margin-bottom: 16rpx;
    border-radius: $uh-radius-md;
    background-color: $uh-card;
    box-shadow: $uh-shadow-sm;
}

/* 按下反馈：归档是长列表，点完立刻跳转，给一点触感能减少「点没点中」的疑虑 */
.arch-item--pressed {
    background-color: $uh-fill;
}

.arch-item_thumb {
    flex-shrink: 0;
    width: 176rpx;
    height: 128rpx;
    border-radius: $uh-radius-sm;
    background-color: $uh-fill;
}

.arch-item_body {
    flex: 1;
    min-width: 0;
    margin-left: 18rpx;
}

.arch-item_title {
    font-size: 28rpx;
    line-height: 1.45;
    color: $uh-ink-1;
}

.arch-item_meta {
    display: flex;
    align-items: center;
    margin-top: 10rpx;
    font-size: 22rpx;
    color: $uh-ink-3;
}

.arch-item_meta-icon {
    margin-right: 6rpx;
    font-size: 22rpx;
}

.arch-empty {
    padding: 24rpx 0;
    font-size: 24rpx;
    color: $uh-ink-3;
}
</style>
