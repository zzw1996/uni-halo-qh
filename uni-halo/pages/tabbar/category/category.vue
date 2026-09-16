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
                    <!--
                        订阅说明条：卡片右上角的「订阅」按钮本身只有两个字，但它决定首页显示什么，
                        所以动手之前先把后果说清楚，否则用户点了几张卡之后会发现首页"少了一半文章"。
                    -->
                    <view class="wall-head">
                        <text class="wall-title">按分类订阅</text>
                        <text class="wall-tip">{{ subTipText }}</text>
                        <view v-if="subscribedCount > 0" class="wall-summary">
                            <text class="wall-summary-text">已订阅 {{ subscribedCount }}/{{ maxSubscribe }}</text>
                            <text class="wall-summary-action" @click="fnClearSubscribe">全部取消</text>
                        </view>
                    </view>

                    <!-- 分类卡片墙：点卡片进分类，点右上角订阅 -->
                    <view class="cat-wall">
                        <view v-for="(item, index) in dataList" :key="index" class="cat-card">
                            <!-- 封面当底色的柔光；没有封面时用一组固定色板，保证每张卡都有颜色 -->
                            <image v-if="fnCatCover(item)" class="cat-card-glow" :src="fnCatCover(item)"
                                   mode="aspectFill" lazy-load></image>
                            <view v-else class="cat-card-glow"
                                  :style="{ backgroundImage: fnCatGlow(index) }"></view>
                            <view class="cat-card-shade"></view>

                            <!-- 审核模式下是模拟数据，订阅按钮不出现 -->
                            <view v-if="!calcAuditModeEnabled" class="cat-sub"
                                  :class="{ 'is-on': item.subscribed }"
                                  @click.stop="fnOnSubscribeChange(item)">
                                <text class="cat-sub-text">{{ item.subscribed ? '✓ 已订阅' : '＋ 订阅' }}</text>
                            </view>

                            <view class="cat-card-main" @click="handleToCategory(item)">
                                <image v-if="fnCatCover(item)" class="cat-icon" :src="fnCatCover(item)"
                                       mode="aspectFill" lazy-load></image>
                                <view v-else class="cat-icon cat-icon-fallback"
                                      :style="{ backgroundImage: fnCatGlow(index) }">
                                    <text class="cat-icon-letter">{{ fnCatInitial(item) }}</text>
                                </view>
                                <text class="cat-name">{{ item.spec.displayName }}</text>
                                <text class="cat-count">{{ item.postCount || 0 }}篇文章</text>
                                <text v-if="item.spec.description" class="cat-desc">{{ item.spec.description }}</text>
                            </view>
                        </view>
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

        <!-- 订阅要存到账号上，所以未登录点开关时先引导登录 -->
        <wx-login-dialog
            :show.sync="loginDialogShow"
            title="登录后订阅分类"
            desc="订阅会记在你的账号上，换设备也在；登录后首页只显示订阅分类的文章"
            @success="fnOnLoginSuccess"
        ></wx-login-dialog>

    </view>
</template>

<script>
import tmSkeleton from '@/tm-vuetify/components/tm-skeleton/tm-skeleton.vue';
import tmButton from '@/tm-vuetify/components/tm-button/tm-button.vue';
import tmTranslate from '@/tm-vuetify/components/tm-translate/tm-translate.vue';
import tmEmpty from '@/tm-vuetify/components/tm-empty/tm-empty.vue';
import tmSliderNav from '@/tm-vuetify/components/tm-sliderNav/tm-sliderNav.vue';
import wxLoginDialog from '@/components/wx-login-dialog/wx-login-dialog.vue';
import ArticleMinCard from '@/components/article-min-card/article-min-card.vue';
import { checkHasWxLogin } from '@/utils/auth.js';


export default {
    components: {
        tmSkeleton,
        tmButton,
        tmTranslate,
        tmEmpty,
        tmSliderNav,
        wxLoginDialog,
        ArticleMinCard
    },
    created() {
        this.fnLoadSubscribed();
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
            /** 已订阅的分类（metadata.name），列表里每行的开关状态由它投影出来 */
            subscribedCategories: [],
            /** 与后端 DigestSubscriptionService.MAX_POST_CATEGORIES 保持一致 */
            maxSubscribe: 5,
            loggedIn: false,
            /** onShow 里跳过首次展示（首次由 created 负责加载） */
            pageShown: false,
            /** 保存进行中：保存是整体替换，期间把所有开关禁用，避免连点交错 */
            savingSubscribe: false,
            loginDialogShow: false,
            /** 未登录时用户想订阅的那个分类：登录成功后接着订阅，不用再点一次 */
            pendingSubscribeName: '',
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
            return !!this.haloConfigs?.auditConfig?.auditModeEnabled
        },
        mockJson() {
            return this.$tm.vx.getters().getMockJson;
        },
        /** 分类页布局固定值（原后台「页面配置」已移除） */
        categoryConfig() {
            return {
                type: "list"
            }
        },
        calcShowType() {
            return this.currentCategoryConfig.type
        },
        subscribedCount() {
            return this.subscribedCategories.length
        },
        /**
         * 说明条文案：三种状态各说各的，重点是让用户知道「一个都不订阅会怎样」。
         */
        subTipText() {
            if (!this.loggedIn) {
                return '登录后可以订阅分类：订阅什么，首页就只显示什么分类的文章';
            }
            if (this.subscribedCount === 0) {
                return '订阅后首页只显示这些分类的文章；一个都不订阅时，首页按站内默认顺序展示全部文章';
            }
            return '首页当前只显示已订阅分类的文章，取消全部订阅即恢复站内默认';
        },
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
    /**
     * 每次切回本 tab 都重新对一次登录态与订阅状态。
     *
     * <p>这两个东西都可能在别的页面被改掉：用户可能是在「我的」里登录的，
     * 而本页在 created 时拿到的 loggedIn 还是 false —— 那样点订阅按钮只会弹登录框，
     * 看起来就像「订阅之后无法取消订阅」。订阅同理（可能在别处改过）。
     * 一次 GET 换掉这两类不一致，很划算。</p>
     */
    onShow() {
        // 首次展示由 created() 负责，避免启动时重复请求
        if (!this.pageShown) {
            this.pageShown = true;
            return;
        }
        this.fnLoadSubscribed();
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
        /**
         * 取用户已订阅的分类；未登录时清空。
         *
         * <p>订阅数据存在服务端（按 openid 唯一），所以换个手机、重装小程序都还在。</p>
         */
        async fnLoadSubscribed() {
            try {
                this.loggedIn = checkHasWxLogin();
            } catch (e) {
                this.loggedIn = false;
            }
            if (!this.loggedIn) {
                this.fnApplySubscribed([]);
                return;
            }
            try {
                const res = await this.$httpApi.v2.getDigestSubscription();
                const body = res?.data || res || {};
                const sub = body.subscription || {};
                this.fnApplySubscribed(Array.isArray(sub.postCategories) ? sub.postCategories : []);
            } catch (err) {
                // 静默：拿不到订阅状态就按「一个都没订阅」显示，分类列表本身照常可用
                this.fnApplySubscribed([]);
            }
        },
        /**
         * 把订阅名列表投影到已渲染的数据上。
         *
         * <p>不做「已订阅的排到前面」：列表里每行都有开关，一点开关整行位置就变，
         * 手指下的那一行会跳到别处——订阅状态靠开关本身表达就够，顺序保持站长排的。</p>
         */
        fnApplySubscribed(names) {
            this.subscribedCategories = Array.isArray(names) ? names.slice() : [];
            const mark = (item) => {
                const name = item && item.metadata && item.metadata.name;
                item.subscribed = !!name && this.subscribedCategories.includes(name);
                return item;
            };
            this.dataList = (this.dataList || []).map(mark);
            this.categoryList = (this.categoryList || []).map(mark);
        },
        /** 当前应该保存的订阅名列表（以卡片上的订阅状态为准） */
        fnSubscribedNames() {
            return this.dataList
                .filter((item) => item.subscribed && item.metadata && item.metadata.name)
                .map((item) => item.metadata.name);
        },
        /**
         * 卡片封面的完整地址；没有封面时返回空串，交给色板兜底。
         *
         * <p>两个坑都在这里挡掉了：站点里老分类的封面存的是相对路径
         * （{@code /upload/xxx.jpg}），直接给 image 组件是加载不出来的；
         * 而 checkThumbnailUrl 在封面为空时会返回「默认缩略图」，
         * 那样自动创建的主题分类就会全都顶着同一张默认图，看不出是个兜底。</p>
         */
        fnCatCover(item) {
            const cover = item && item.spec && item.spec.cover;
            if (!cover) {
                return '';
            }
            return this.$utils.checkThumbnailUrl(cover, true);
        },
        /**
         * 无封面时的渐变色板（按位置循环）。
         *
         * <p>自动创建的主题分类没有封面图，卡片不能因此变成一片白：
         * 用一组柔和的渐变打底，保证每张卡都有颜色。</p>
         */
        fnCatGlow(index) {
            const palette = [
                'linear-gradient(135deg, #ffd9e3 0%, #fff3f6 100%)',
                'linear-gradient(135deg, #cfe4ff 0%, #eef5ff 100%)',
                'linear-gradient(135deg, #e3d9ff 0%, #f5f1ff 100%)',
                'linear-gradient(135deg, #c9f0ff 0%, #eafaff 100%)',
                'linear-gradient(135deg, #ffe2c2 0%, #fff5e8 100%)',
                'linear-gradient(135deg, #ffd7f0 0%, #fdeef9 100%)'
            ];
            return palette[index % palette.length];
        },
        /** 无封面时用分类名首字当图标 */
        fnCatInitial(item) {
            const name = item && item.spec && item.spec.displayName;
            return name ? String(name).trim().charAt(0) : '·';
        },
        /**
         * 点卡片右上角的订阅按钮：切换这一个分类的订阅状态，然后整体提交。
         *
         * <p><b>状态必须在这里翻转</b>。这个按钮早期是 {@code tm-switch} 开关，
         * 那时 v-model 会在回调之前先把值翻好，所以回调里直接读就行；改成普通点击的
         * 「订阅 / 已订阅」胶囊之后，没有任何地方再翻这个值了——照旧读就会读到一份
         * 全空的清单，保存成功却提示「已取消全部订阅」。</p>
         */
        async fnOnSubscribeChange(item) {
            const name = item && item.metadata && item.metadata.name;
            if (!name) {
                return;
            }
            const next = !item.subscribed;
            if (!this.loggedIn) {
                // 未登录先不动本地状态：登录成功后按 pendingSubscribeName 接着订阅
                this.pendingSubscribeName = name;
                this.loginDialogShow = true;
                return;
            }
            // 要订阅时先看上限（未登录/取消不受限）。取消订阅永远允许。
            if (next && this.fnSubscribedNames().length >= this.maxSubscribe) {
                uni.showToast({
                    title: `最多订阅 ${this.maxSubscribe} 个分类，先取消一个再选`,
                    icon: 'none'
                });
                return;
            }
            item.subscribed = next;
            await this.fnSaveSubscription(next
                ? `已订阅「${item.spec.displayName}」`
                : `已取消「${item.spec.displayName}」`);
        },
        /** 全部取消：一次请求清空，比让人一个个点回去快得多 */
        async fnClearSubscribe() {
            if (!this.subscribedCount) {
                return;
            }
            this.dataList.forEach((item) => {
                item.subscribed = false;
            });
            await this.fnSaveSubscription('已取消全部订阅');
        },
        /** 登录成功：如果刚才是在点订阅按钮，接着把它订阅上，不用再点一次 */
        async fnOnLoginSuccess() {
            this.loggedIn = true;
            const name = this.pendingSubscribeName;
            this.pendingSubscribeName = '';
            await this.fnLoadSubscribed();
            if (!name) {
                return;
            }
            const item = this.dataList.find((entry) => entry.metadata && entry.metadata.name === name);
            // 登录期间可能已在别处订阅过：已经是订阅态就不再重复切换
            if (!item || item.subscribed || this.subscribedCount >= this.maxSubscribe) {
                return;
            }
            item.subscribed = true;
            await this.fnSaveSubscription(`已订阅「${item.spec.displayName}」`);
        },
        /**
         * 保存订阅。
         *
         * <p>服务端是整体替换，失败或超限时以服务端为准回读一次，
         * 免得界面停留在「看起来订阅了、实际没存上」的状态。</p>
         *
         * @param message 成功后的提示语；不传就按订阅数量给一句通用的
         */
        async fnSaveSubscription(message) {
            const names = this.fnSubscribedNames();
            this.savingSubscribe = true;
            try {
                const res = await this.$httpApi.v2.saveDigestSubscription({
                    enabled: names.length > 0,
                    // 旧字段（AI 分类 / 关键词 / 来源）已经没有维护界面了，显式清空
                    categories: [],
                    keywords: [],
                    sources: [],
                    postCategories: names
                });
                const body = res?.data || res || {};
                if (body.error) {
                    uni.showToast({ title: body.error, icon: 'none' });
                    await this.fnLoadSubscribed();
                    return;
                }
                this.subscribedCategories = names;
                // 提示语由调用方给：订阅某一个、取消某一个、全部取消，说清楚做的是哪件事，
                // 比统一回一句「已订阅 N 个分类」好懂
                uni.showToast({
                    title: message || (names.length
                        ? `已订阅 ${names.length} 个分类` : '已取消全部订阅'),
                    icon: 'none'
                });
                // 首页可能正停在旧的筛选结果上：写一个版本号（切回首页时据它重取），
                // 同时发事件（首页还活着时立刻刷新）。两条路都留着——
                // 事件在页面生命周期边界上可能错过，版本号则一定对得上。
                uni.setStorageSync('category.subscription.rev', String(Date.now()));
                uni.$emit('category_subscription_changed');
            } catch (err) {
                uni.showToast({
                    title: err?.error || err?.detail || '保存失败，请稍后重试',
                    icon: 'none'
                });
                await this.fnLoadSubscribed();
            } finally {
                this.savingSubscribe = false;
            }
        },
        fnGetData() {
            if (this.calcAuditModeEnabled) {
                this.currentCategoryConfig.type = "list"
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

                        // 分类对象带着 content.halo.run/… 这类带点的 label 键，
                        // 不清洗会让微信 setData 解析路径时抛错、整页数据更新失败
                        const tempItems = this.$utils.stripDataUnsafeList(res.items).map(item => {
							item.postCount = item.postCount ?? 0
                            // 只有真有封面才归一化：checkThumbnailUrl 在空值时会返回默认缩略图，
                            // 那会让「无封面」的分类看起来像有封面，色板兜底就永远走不到了
                            item.spec.cover = item.spec.cover
                                ? this.$utils.checkThumbnailUrl(item.spec.cover, true) : ''
                            // 订阅状态由已订阅列表投影出来，服务端返回的分类对象里没有这个字段
                            item.subscribed = this.subscribedCategories.includes(item.metadata && item.metadata.name)
                            return item;
                        })

                        if (this.isLoadMore) {
                            this.dataList = this.dataList.concat(tempItems);
                        } else {
                            this.dataList = tempItems;
                        }
                    } else {
                        this.dataList = this.$utils.stripDataUnsafeList(res.items)
                        const mappedCategories = this.dataList.map(item => {
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

/* ===== 分类卡片墙 ===== */
.wall-head {
    padding: 24rpx 28rpx 0;
    display: flex;
    flex-direction: column;
}

.wall-title {
    font-size: 30rpx;
    font-weight: 700;
    color: #2f3548;
}

.wall-tip {
    margin-top: 8rpx;
    font-size: 22rpx;
    line-height: 1.7;
    color: #9aa1b5;
}

.wall-summary {
    margin-top: 16rpx;
    padding: 12rpx 22rpx;
    border-radius: 999rpx;
    background: #eef1fb;
    display: flex;
    align-items: center;
    justify-content: space-between;
}

.wall-summary-text {
    font-size: 22rpx;
    color: #4c5480;
}

.wall-summary-action {
    margin-left: 16rpx;
    font-size: 22rpx;
    color: #667eea;
}

.cat-wall {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    gap: 20rpx;
    padding: 24rpx 24rpx 0;
    width: 100%;
    box-sizing: border-box;
}

.cat-card {
    position: relative;
    display: flex;
    flex-direction: column;
    min-height: 340rpx;
    border-radius: 24rpx;
    background-color: #fff;
    box-shadow: 0 8rpx 24rpx rgba(31, 41, 55, 0.08);
    overflow: hidden;
}

/* 封面当底色：压到很低的不透明度再叠一层白色渐变，出来的是柔和色晕而不是一张照片 */
.cat-card-glow {
    position: absolute;
    left: 0;
    top: 0;
    width: 100%;
    height: 260rpx;
    opacity: 0.28;
}

.cat-card-shade {
    position: absolute;
    left: 0;
    top: 0;
    right: 0;
    bottom: 0;
    background: linear-gradient(180deg, rgba(255, 255, 255, 0.45) 0%, #ffffff 62%);
}

.cat-sub {
    position: absolute;
    top: 16rpx;
    right: 16rpx;
    z-index: 3;
    padding: 6rpx 18rpx;
    border-radius: 999rpx;
    background: rgba(255, 255, 255, 0.86);
    border: 1rpx solid rgba(255, 255, 255, 0.95);
    box-shadow: 0 4rpx 12rpx rgba(31, 41, 55, 0.08);
}

.cat-sub.is-on {
    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
    border-color: transparent;
}

.cat-sub-text {
    font-size: 20rpx;
    color: #6b7186;
}

.cat-sub.is-on .cat-sub-text {
    color: #fff;
}

.cat-card-main {
    position: relative;
    z-index: 2;
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: 56rpx 22rpx 26rpx;
    box-sizing: border-box;
}

.cat-icon {
    width: 132rpx;
    height: 132rpx;
    border-radius: 50%;
    border: 6rpx solid #ffffff;
    box-shadow: 0 6rpx 18rpx rgba(31, 41, 55, 0.12);
    flex-shrink: 0;
}

.cat-icon-fallback {
    display: flex;
    align-items: center;
    justify-content: center;
}

.cat-icon-letter {
    font-size: 48rpx;
    font-weight: 700;
    color: #ffffff;
    text-shadow: 0 2rpx 8rpx rgba(0, 0, 0, 0.18);
}

.cat-name {
    margin-top: 20rpx;
    font-size: 34rpx;
    font-weight: 700;
    color: #2f3548;
    text-align: center;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    max-width: 100%;
}

.cat-count {
    margin-top: 10rpx;
    font-size: 22rpx;
    color: #9aa1b5;
}

.cat-desc {
    margin-top: 12rpx;
    font-size: 22rpx;
    line-height: 1.5;
    color: #9aa1b5;
    text-align: center;
    overflow: hidden;
    text-overflow: ellipsis;
    display: -webkit-box;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
}

.load-text {
    width: 100%;
    text-align: center;
    padding: 24rpx 0 16rpx;
    font-size: 22rpx;
    color: #909399;
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
