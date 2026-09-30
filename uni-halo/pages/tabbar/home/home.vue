<template>
	<view :class="skinClass" class="app-page">
		<tm-menubars iconColor="white" color="white" :flat="true" :showback="false">
			<view slot="left" @click="fnOnLogoToPage" class="header-avatar ml-24">
				<image v-if="wxUserInfo && wxUserInfo.avatarUrl" class="logo round-24" :src="calcHeaderAvatar"
					mode="aspectFill"></image>
				<view v-else class="logo logo--guest round-24 flex flex-center">
					<tm-icons name="icon-user" :size="34" color="#c0c4cc"></tm-icons>
				</view>
			</view>
			<view class="search-input round-12 pt-12 pb-12 flex pl-24" @click="fnToSearch">
				<text class="search-input_icon iconfont text-size-m icon-search text-grey"></text>
				<view class="search-input_text pl-12 text-size-m text-grey">搜索内容...</view>
			</view>
			<!-- #ifdef APP-PLUS || H5 -->
			<view slot="right" class="mr-24 text-size-m text-grey text-overflow">{{ appInfo.name }}</view>
			<!-- #endif -->
		</tm-menubars>
		<view v-if="loading !== 'success' && articleList.length===0" class="loading-wrap">
			<tm-skeleton model="card"></tm-skeleton>
			<tm-skeleton model="cardActions"></tm-skeleton>
			<tm-skeleton model="list"></tm-skeleton>
			<tm-skeleton model="listAvatr"></tm-skeleton>
			<tm-skeleton model="listAvatr"></tm-skeleton>
			<tm-skeleton model="listAvatr"></tm-skeleton>
		</view>
		<block v-else>
			<view class="banner ml-24 mr-24 mt-12 mb-24 round-6 card-shadow" v-if="bannerConfig.enabled && bannerList.length">
				<e-swiper :list="bannerList" :autoplay="true" :interval="bannerConfig.interval"
					:duration="bannerConfig.duration" :fit="bannerConfig.fit"
					:fallback-height="bannerConfig.fallbackHeight" :show-dots="calcBannerShowDots"
					:dot-position="calcBannerDotPosition" @on-click="fnOnBannerClick" />
			</view>

			<!--
				首页双卡并排：AI 定制内容 + 视频号。
				两张都是「运营位」（站长可各自开关），并排省一半高度、首屏更快到文章列表；
				只有一张可见时 flex:1 自然占满整行。
				视觉分工：AI 卡浅底描边（轻），视频号卡品牌渐变（重），一深一浅有层次。
			-->
			<view class="promo-row ml-24 mr-24 mt-16 mb-16" v-if="aiCardVisible || channelsConfig">
				<!-- AI 定制内容：已开通显示徽章；未开通点进去是购买与积分兑换的引导页 -->
				<view v-if="aiCardVisible" class="promo-card ai-card round-6 card-shadow"
					:class="{ 'promo-card--wide': promoIsWide }"
					hover-class="promo-card--pressed" :hover-stay-time="60" @click="fnOpenAiContent">
					<view class="promo-head flex items-center">
						<view class="ai-card-icon">
							<text class="ai-card-icon_text">AI</text>
						</view>
					</view>
					<view class="promo-body">
						<view class="promo-title-row">
							<text class="promo-title promo-title--ai">{{ aiCardContent.title }}</text>
							<text v-if="aiContentActive" class="ai-card-badge">已开通</text>
						</view>
						<text v-if="aiCardContent.desc" class="promo-desc promo-desc--ai">{{ aiCardContent.desc }}</text>
					</view>
					<view class="promo-action flex items-center">
						<text class="promo-action_text promo-action_text--ai">{{ aiContentActive ? '去看看' : '立即体验' }}</text>
						<text class="promo-action_arrow promo-action_arrow--ai">›</text>
					</view>
				</view>

				<!--
					视频号入口（原「每日一句」区块）
					卡片内容全部来自后台「视频号」配置；没开启或没填视频号 ID 时整块不渲染——
					点不动的卡片比没有卡片更糟。跳转与降级见 utils/channels.js。
				-->
				<view v-if="channelsConfig" class="promo-card channels-card round-6 card-shadow"
					:class="{ 'promo-card--wide': promoIsWide }"
					hover-class="promo-card--pressed" :hover-stay-time="60" @click="fnOpenChannels">
					<view class="promo-head flex items-center">
						<view class="channels-avatar">
							<image v-if="channelsAvatar" class="channels-avatar_img" :src="channelsAvatar" mode="aspectFill"></image>
							<text v-else class="channels-avatar_text">{{ channelsAvatarText }}</text>
							<view class="channels-avatar_play">
								<text class="channels-avatar_triangle"></text>
							</view>
						</view>
					</view>
					<view class="promo-body">
						<view class="promo-title-row">
							<text class="promo-title promo-title--channels">{{ channelsConfig.nickname }}</text>
							<text class="channels-badge">视频号</text>
						</view>
						<text class="promo-desc promo-desc--channels">{{ channelsConfig.desc || channelsTip }}</text>
					</view>
					<view class="promo-action flex items-center">
						<text class="promo-action_text promo-action_text--channels">{{ channelsButtonText }}</text>
						<text class="promo-action_arrow promo-action_arrow--channels">›</text>
					</view>
				</view>
			</view>

			<!-- 最新文章 -->
			<view class="flex flex-between mt-24 mb-24 pl-24 pr-24">
				<view class="page-item_title text-weight-b">最新列表</view>
				<view class="show-more flex flex-center bg-white round-6" @click="fnToArticlesPage">
					<text class="iconfont icon-angle-right text-size-s text-grey-darken-1"></text>
				</view>
				<view v-if="false" class="flex flex-center text-size-s text-grey-darken-1" @click="fnToArticlesPage">
					<text class=" text-size-m ">查看更多</text>
					<text class="iconfont icon-angle-right text-size-s "></text>
				</view>
			</view>
			<!--
				订阅状态条：订阅是按分类在分类页里设的，首页只显示订阅分类的文章。
				不说明白的话，用户会以为站点丢了内容——所以这里既解释「为什么不一样」，
				也给一个直接去改的口子。
			-->
			<!-- 内容为什么是这样，说清楚；零订阅的引导条可以关掉，本地记住不再打扰 -->
			<view v-if="feedHintVisible" class="feed-hint ml-24 mr-24 mb-16">
				<text class="feed-hint-text">{{ feedHintText }}</text>
				<text v-if="feedHintDismissible" class="feed-hint-close"
					@click="fnDismissFeedHint">知道了</text>
			</view>

			<view v-if="articleList.length === 0" class="article-empty">
				<tm-empty icon="icon-shiliangzhinengduixiang-" label="博主还没有发表任何内容~"></tm-empty>
			</view>
			<block v-else>
				<view :class="globalAppSettings.layout.home">
					<tm-translate v-for="(article, index) in articleList" :key="index" class="ani-item"
						animation-name="fadeUp" :wait="calcAniWait(index)">
						<article-card from="home" :article="article" :post="article" :show-category="true"
							@on-click="fnToArticleDetail"></article-card>
					</tm-translate>
				</view>
				<view class="load-text mt-12">{{ loadMoreText }}</view>
				<!-- offset 里加上底栏高度：原生栏被隐藏后窗口占满全屏，不抬高会被自绘底栏压住 -->
				<tm-flotbutton v-if="articleList.length > 10" :width="90" color="indigo" @click="fnToTopPage"
					size="s" :offset="calcFlotOffset" icon="icon-angle-up"></tm-flotbutton>
			</block>
		</block>


		<!-- 弹窗 -->
		<NotifyDialog v-if="notify.show" :show="notify.show" :title="notify.data.title" :content="notify.data.content"
			:url="notify.data.url" @on-change="fnOnNotifyChange"></NotifyDialog>
		<!-- 自绘底部导航：后台「底部导航」开关关闭时不渲染，仍用微信原生底栏 -->
		<uh-tabbar current="home"></uh-tabbar>
	</view>
</template>

<script>
	import tmMenubars from '@/tm-vuetify/components/tm-menubars/tm-menubars.vue';
	import tmSkeleton from '@/tm-vuetify/components/tm-skeleton/tm-skeleton.vue';
	import tmTranslate from '@/tm-vuetify/components/tm-translate/tm-translate.vue';
	import tmFlotbutton from '@/tm-vuetify/components/tm-flotbutton/tm-flotbutton.vue';
	import tmIcons from '@/tm-vuetify/components/tm-icons/tm-icons.vue';
	import tmEmpty from '@/tm-vuetify/components/tm-empty/tm-empty.vue';
	import tmGrid from '@/tm-vuetify/components/tm-grid/tm-grid.vue';

	import eSwiper from '@/components/e-swiper/e-swiper.vue';
	import loadMoreMixin from '@/common/mixins/loadMore.js';
	import NotifyDialog from "@/components/notify-dialog/notify-dialog.vue";
	import uhTabbar from '@/components/uh-tabbar/uh-tabbar.vue';
	import { openChannelsProfile } from '@/utils/channels.js';
	import { tabbarBottomOffsetPx } from '@/utils/tabbar.js';
	import { accumulatePushQuota } from '@/utils/subscribeMessage.js';
	import qs from 'qs'
	import {
		checkHasWxLogin,
		getWxLoginInfo
	} from '@/utils/auth.js';

	/** 零订阅引导条「知道了」的本地标记：关掉后不再打扰（它不是功能提示，只是建议） */
const HOME_GUIDE_DISMISSED_KEY = 'home.feedGuideDismissed';

export default {
		mixins: [loadMoreMixin],
		components: {
			tmMenubars,
			tmSkeleton,
			tmTranslate,
			tmFlotbutton,
			tmIcons,
			tmEmpty,
			tmGrid,
			eSwiper,
			uhTabbar,
			NotifyDialog
		},
			data() {
				return {
					loading: 'loading',
					queryParams: {
						size: 5,
					page: 1,
					sort: ['spec.pinned,desc', 'spec.publishTime,desc']
				},
				result: {},
				isLoadMore: false,
				loadMoreText: '加载中...',
				bannerList: [],
				noticeList: [],
				articleList: [],
				notify: {
					show: false,
					data: {}
				},
				wxUserInfo: null,
				/** 订阅的分类（站点分类的 metadata.name），由分类页设置 */
				subscription: { postCategories: [], inheritedCategories: [], effectiveCategories: [] },
				/** 主列表是否正在按订阅分类筛选 / 是否已回落站内默认列表 */
				feedPersonalized: false,
				feedFallback: false,
				/** 列表上方的提示条：服务端会说明为什么内容是这样（零订阅/有暂停/没内容） */
				feedHint: '',
				/** 零订阅的引导条是否允许关闭（关掉后本地记住，不再打扰） */
				feedHintDismissible: false,
				/** 多路合并分页：每个分类当前取到第几页 */
				feedPages: {},
				/** 已同步过的订阅版本号：分类页改过订阅后会写这个值，切回首页时据此判断要不要重取 */
				subscriptionRev: ''
			};
		},
		computed: {
			feedFallbackText() {
				return '你订阅的分类暂时没有内容，已为你展示全站最新';
			},
			/** 顶部提示条：服务端给了原因就用它，否则用本地那句「已回落全站最新」 */
			feedHintText() {
				return this.feedHint || (this.feedFallback ? this.feedFallbackText : '');
			},
			feedHintVisible() {
				return !!this.feedHintText;
			},
			haloConfigs() {
				return this.$tm.vx.getters().getConfigs;
			},
			calcHeaderAvatar() {
				if (this.wxUserInfo?.avatarUrl) {
					return this.$utils.checkAvatarUrl(this.wxUserInfo.avatarUrl, true);
				}
				return '';
			},
			appInfo() {
				const appInfo = this.haloConfigs?.appConfig?.appInfo;
				if (!appInfo) return {
					logo: '',
					name: ''
				};
				return {
					...appInfo,
					logo: this.$utils.checkImageUrl(appInfo.logo)
				};
			},
			mockJson() {
				return this.$tm.vx.getters().getMockJson;
			},
			calcAuditModeEnabled() {
				return !!this.haloConfigs?.auditConfig?.auditModeEnabled
			},
			calcVotePluginEnabled() {
				return !!this.haloConfigs?.pluginIntegrations?.vote
			},
			calcLinksPluginEnabled() {
				return !!this.haloConfigs?.pluginIntegrations?.links;
			},
			/**
			 * 轮播图指示器：设置页（我的 → 系统设置）里的「轮播图指示器 / 位置」控制。
			 * 这两个开关以前是死的——首页只用了自己写死的配置，从没读过 appSettings.banner，
			 * 用户在设置页来回切却什么都不会变。现在接上，改完回首页即可见效。
			 */
			calcBannerShowDots() {
				return this.globalAppSettings?.banner?.useDot !== false;
			},
			/** 悬浮按钮偏移：自绘底栏关闭时与历史一致（默认 16px），开启时抬高一个底栏高度 */
			calcFlotOffset() {
				return [16, 16 + tabbarBottomOffsetPx()];
			},
			calcBannerDotPosition() {
				return this.globalAppSettings?.banner?.dotPosition === 'bottom' ? 'bottom' : 'right';
			},
			/**
			 * 首页轮播图配置。
			 *
			 * - enabled：总开关，关掉后首页不占位（而不是留一块空白）
			 * - type：post=取站内文章封面；custom=用下面的 list（需要自定义轮播时改这里）
			 * - fit：按封面真实宽高自适应高度，关掉则固定用 fallbackHeight
			 * - max / fetchSize：轮播是推荐位，取 fetchSize 篇里挑前 max 篇有封面的
			 */
			bannerConfig() {
				return {
					enabled: true,
					type: 'post',
					list: [],
					fit: true,
					fallbackHeight: '400rpx',
					interval: 4000,
					duration: 400,
					max: 5,
					fetchSize: 10
				}
			},
			/**
			 * 视频号入口配置：后台「视频号」里开启且填了视频号 ID 才返回，
			 * 否则返回 null 让整张卡片不渲染。
			 */
			channelsConfig() {
				const cfg = this.haloConfigs?.channelsConfig;
				const finderUserName = (cfg?.finderUserName || '').trim();
				if (cfg?.enabled !== true || !finderUserName) return null;
				return {
					...cfg,
					finderUserName,
					// 昵称是必填项，但后台没填时卡片不能是空标题
					nickname: (cfg.nickname || '').trim() || '我的视频号'
				};
			},
			/**
			 * AI 定制内容运营位卡：插件开启本功能 + 站长没关首页卡时返回，
			 * 否则整张卡不渲染（未开通的用户点进去是购买/兑换引导页）。
			 */
			aiCardConfig() {
				return this.haloConfigs?.aiContentConfig || {};
			},
			aiCardVisible() {
				return this.aiCardConfig.enabled === true
					&& this.aiCardConfig.homeCardEnabled === true;
			},
			aiCardContent() {
				return {
					title: (this.aiCardConfig.homeCardTitle || '').trim() || 'AI 定制内容',
					desc: (this.aiCardConfig.homeCardDesc || '').trim()
				};
			},
			/** 是否已开通（在定制页加载过权益后写入本地，首页不多打一次接口） */
			aiContentActive() {
				try {
					return uni.getStorageSync('ai.content.active') === '1';
				} catch (e) {
					return false;
				}
			},
			/** 两张运营位卡只剩一张时切横排：整行竖排卡会显得空旷 */
			promoIsWide() {
				return !(this.aiCardVisible && this.channelsConfig);
			},
			/** 头像地址（Halo 返回的是相对路径，交给 utils 补全域名） */
			channelsAvatar() {
				const url = this.channelsConfig?.avatarUrl;
				return url ? this.$utils.checkImageUrl(url) : '';
			},
			/** 没有头像时用名称首字兜底，比放一张灰色占位图好看 */
			channelsAvatarText() {
				const name = (this.channelsConfig?.nickname || '视').trim();
				return name.slice(0, 1);
			},
			channelsButtonText() {
				return (this.channelsConfig?.buttonText || '').trim() || '进入视频号';
			},
			channelsTip() {
				return (this.channelsConfig?.tip || '').trim();
			}
		},
		watch: {
			haloConfigs: {
				handler(val) {
					if (!val) return;
					this.fnInitNotify();
				},
				deep: true,
				immediate: true,
			}
		},
		onLoad() {
			this.fnSetPageTitle();
			this.fnInitNotify();
		},
		created() {
			this.fnSyncWxUser();
			uni.$on('wx_login_status_changed', this.fnOnWxLoginStatusChanged);
			// 订阅是在分类页改的：改完切回首页要立刻生效，不能等下拉刷新
			uni.$on('category_subscription_changed', this.fnOnSubscriptionChanged);
			this.fnQuery();
		},
		onShow() {
			this.fnSyncWxUser();
			// 订阅是在分类页改的，本页可能还停在旧结果上。
			// 事件通知可能因为页面生命周期错过（例如首页还没创建就改了订阅），
			// 所以切回本 tab 时再按版本号对一次，双保险。
			this.fnCheckSubscriptionChanged();
		},
		onUnload() {
			uni.$off('wx_login_status_changed', this.fnOnWxLoginStatusChanged);
			uni.$off('category_subscription_changed', this.fnOnSubscriptionChanged);
		},
		onPullDownRefresh() {
			this.isLoadMore = false;
			this.fnLoadMoreDone();
			this.queryParams.page = 1;
			// 多路分页也要重置，否则下拉后每一路都从中间页继续取
			this.feedPages = {};
			this.fnQuery();
		},
		onReachBottom() {
			if (this.calcAuditModeEnabled) {
				this.fnLoadMoreNoMore();
				return;
			}
			this.fnLoadMore(this.result.hasNext, () => {
				this.queryParams.page += 1;
				this.isLoadMore = true;
				this.fnGetArticleList();
			});
		},
		methods: {
			fnSyncWxUser() {
				this.wxUserInfo = checkHasWxLogin() ? getWxLoginInfo() : null;
			},
			/**
			 * 登录态变化：除同步用户信息外，登录回来还要重拉订阅并刷新内容流。
			 *
			 * <p>为什么必须重拉：开发者工具「清除缓存」/重装会清掉本地登录态，而启动页的
			 * 静默登录是异步的——首页首查经常发生在登录回来之前，这一查就落到了
			 * 「未登录 → 全站热门 + 订阅引导」上；订阅本身在服务端并没有丢，
			 * 登录态恢复后不重拉，用户就会一直看到「没有订阅」的提示。</p>
			 */
			fnOnWxLoginStatusChanged(info) {
				this.fnSyncWxUser();
				if (info && info.openid) {
					this.fnQuery();
				}
			},
			/**
			 * 首页数据入口。
			 *
			 * <p>顺序有讲究：先拿订阅分类，才能决定主列表是「按订阅筛选」还是「站内默认」。</p>
			 */
			async fnQuery() {
				// 每次重新拉取都从「没有回落」开始，否则上一轮的空列表提示会一直挂着
				this.feedFallback = false;
				this.feedHint = '';
				this.feedHintDismissible = false;
				await this.fnLoadSubscription();
				this.fnGetBanner();
				this.fnGetArticleList();
			},

			/**
			 * 取订阅分类；未登录直接跳过。
			 *
			 * <p>未登录不做任何个性化：首页对公众保持完全一致，
			 * 不能把公开浏览体验变成「先登录」。</p>
			 */
			async fnLoadSubscription() {
				if (!checkHasWxLogin()) {
					return;
				}
				try {
					const res = await this.$httpApi.v2.getDigestSubscription();
					const body = res?.data || res || {};
					const sub = body.subscription || {};
					const postCategories = Array.isArray(sub.postCategories) ? sub.postCategories : [];
					this.subscription = {
						postCategories,
						inheritedCategories: Array.isArray(sub.inheritedCategories)
							? sub.inheritedCategories : [],
						// 首页实际筛选用的就是服务端算好的并集（自己优先、最多 8 个）
						effectiveCategories: Array.isArray(sub.effectiveCategories)
							? sub.effectiveCategories : postCategories
					};
					// 有订阅（自己的或继承来的）才影响主列表；都没有就是站内默认列表
					this.feedPersonalized = this.subscription.effectiveCategories.length > 0;
					// 记下「已经按这一版订阅取过数据了」，避免每次切回首页都白取一次
					this.subscriptionRev = uni.getStorageSync('category.subscription.rev') || '';
				} catch (err) {
					// 静默：拿不到订阅分类就按站内默认列表展示
					this.subscription = {
						postCategories: [],
						inheritedCategories: [],
						effectiveCategories: []
					};
					this.feedPersonalized = false;
				}
			},

			/**
			 * 首页内容流（服务端并集）。
			 *
			 * <p>一次请求拿到「我订阅的生效分类」合并后的文章；没有订阅时服务端改用人气分类
			 * （scope=hot）并把原因写在 fallback 里，首页据此显示引导条——而不是留一片空白。</p>
			 *
			 * <p>失败时不做复杂补偿：直接回落站内默认列表（feedFallback=true），
			 * 首页永远要能出内容。</p>
			 */
			async fnGetUnionFeed() {
				const scope = this.feedPersonalized ? 'mine' : 'hot';
				const page = this.isLoadMore ? this.queryParams.page : 1;
				if (!this.isLoadMore) {
					this.loading = 'loading';
				}
				this.loadMoreText = '加载中...';
				try {
					const res = await this.$httpApi.v2.getHomeFeed({
						scope,
						page,
						size: this.queryParams.size
					});
					const body = res?.data || res || {};
					if (body.error) {
						throw new Error(body.error);
					}
					const items = this.$utils.stripDataUnsafeList(body.items || []);
					const merged = this.fnMergeArticles(
						this.isLoadMore ? this.articleList.concat(items) : items);
					// 订阅了却一篇都没取到：换成全站最新，并把原因留在提示条里
					if (!this.isLoadMore && merged.length === 0) {
						this.feedHint = body.hint || this.feedFallbackText;
						this.feedHintDismissible = false;
						this.fnFallbackToLatest();
						return;
					}
					this.articleList = merged;
					this.result.hasNext = body.hasMore === true;
					this.feedPersonalized = scope === 'mine';
					this.fnApplyFeedHint(body);
					this.loading = 'success';
					this.loadMoreText = body.hasMore ? '上拉加载更多' : '呜呜，没有更多数据啦~';
					uni.hideLoading();
					uni.stopPullDownRefresh();
					this.fnLoadMoreDone();
				} catch (err) {
					// 并集接口不可用（老服务端 / 网络问题）：先退回原来「按分类并发」的实现，
					// 它自己也取不到内容时会继续回落站内默认列表——首页永远要能出内容
					if (this.feedPersonalized) {
						this.fnGetPersonalArticleList();
						return;
					}
					this.fnFallbackToLatest();
				}
			},

			/**
			 * 提示条：服务端说清原因，本地决定要不要给「知道了」。
			 *
			 * <p>零订阅的引导条允许关掉（本地记住）：同一条引导每次进首页都弹，
			 * 很快就会从「提醒」变成「噪音」，而它本来只是建议。</p>
			 */
			fnApplyFeedHint(body) {
				const dismissed = uni.getStorageSync(HOME_GUIDE_DISMISSED_KEY) === '1';
				const paused = Array.isArray(body.pausedCategories) ? body.pausedCategories.length : 0;
				if (paused > 0) {
					this.feedHint = body.hint || '';
					this.feedHintDismissible = false;
					return;
				}
				if ((body.fallback === 'no-subscription' || body.fallback === 'inherited-only')
					&& body.hint && !dismissed) {
					// 两种都是「去自己选几个」的建议：可以关掉，不再反复打扰
					this.feedHint = body.hint;
					this.feedHintDismissible = true;
					return;
				}
				this.feedHint = '';
				this.feedHintDismissible = false;
			},

			fnDismissFeedHint() {
				if (this.feedHintDismissible) {
					try {
						uni.setStorageSync(HOME_GUIDE_DISMISSED_KEY, '1');
					} catch (e) {
						// 存储不可用就算了：下次进来再提示一遍，总比这里报错好
					}
				}
				this.feedHint = '';
				this.feedHintDismissible = false;
			},

			/**
			 * 按订阅的分类取文章：每类一路并发拉取，合并去重后按置顶 + 时间排序。
			 *
			 * <p>用 Halo 自己的分类文章接口（分类详情页也在用），因此分页、排序口径与站点一致，
			 * 不需要另建一套内容接口。</p>
			 */
			async fnGetPersonalArticleList() {
				// 用服务端算好的并集：自己订阅的（≤5）+ 从邀请人继承的（≤3）
				const categories = this.subscription.effectiveCategories
					|| this.subscription.postCategories || [];
				if (!categories.length) {
					this.fnFallbackToLatest();
					return;
				}
				const size = this.queryParams.size;
				const isLoadMore = this.isLoadMore;
				if (!isLoadMore) {
					this.loading = 'loading';
				}
				this.loadMoreText = '加载中...';

				const results = await Promise.all(categories.map((name) => {
					const page = isLoadMore ? (this.feedPages[name] || 1) + 1 : 1;
					return this.$httpApi.v2.getCategoryPostList(name, { page, size })
						.then((res) => ({
							name,
							page,
							items: (res && res.items) || [],
							hasNext: res && res.hasNext === true
						}))
						.catch(() => ({ name, page: 1, items: [], hasNext: false }));
				}));

				const gotAny = results.some((item) => item.items.length > 0);
				// 一篇都没取到（订阅了冷门分类、或接口异常）：回落站内默认列表并说明，
				// 否则用户会看到一个空首页，还以为站点坏了
				if (!isLoadMore && !gotAny) {
					this.fnFallbackToLatest();
					return;
				}

				results.forEach((item) => {
					this.feedPages[item.name] = item.page;
				});
				const flat = [];
				results.forEach((item) => {
					item.items.forEach((article) => flat.push(article));
				});

				this.articleList = this.fnMergeArticles(isLoadMore ? this.articleList.concat(flat) : flat);
				this.result.hasNext = results.some((item) => item.hasNext);
				this.loading = 'success';
				this.loadMoreText = this.result.hasNext ? '上拉加载更多' : '呜呜，没有更多数据啦~';
				uni.hideLoading();
				uni.stopPullDownRefresh();
				this.fnLoadMoreDone();
			},

			/** 合并多路结果：按文章名去重，置顶优先、其次发布时间倒序 */
			fnMergeArticles(items) {
				const seen = {};
				const merged = [];
				(this.$utils.stripDataUnsafeList(items) || []).forEach((item) => {
					const name = item && item.metadata && item.metadata.name;
					if (!name || seen[name]) {
						return;
					}
					seen[name] = true;
					merged.push(item);
				});
				merged.sort((a, b) => {
					const pinnedA = a && a.spec && a.spec.pinned === true ? 1 : 0;
					const pinnedB = b && b.spec && b.spec.pinned === true ? 1 : 0;
					if (pinnedA !== pinnedB) {
						return pinnedB - pinnedA;
					}
					const timeA = (a && a.spec && a.spec.publishTime) || '';
					const timeB = (b && b.spec && b.spec.publishTime) || '';
					if (timeA === timeB) {
						return 0;
					}
					// Halo 的时间是 ISO 字符串，同格式下字典序即时间序
					return timeA < timeB ? 1 : -1;
				});
				return merged;
			},

			/**
			 * 回落站内默认列表。
			 *
			 * <p>订阅的分类一篇都没有时才会走到这里：内容换成站内默认列表，
			 * 状态条仍然显示订阅了什么，提示条说明为什么变了——订阅没有被清掉。</p>
			 */
			fnFallbackToLatest() {
				this.feedPersonalized = false;
				this.feedFallback = true;
				if (!this.feedHint) {
					this.feedHint = this.feedFallbackText;
				}
				this.isLoadMore = false;
				this.fnLoadMoreDone();
				this.queryParams.page = 1;
				this.fnGetArticleList();
			},

			/**
			 * 切回首页时按版本号判断订阅是否变过，变过才重取。
			 *
			 * <p>不用每次都重取：首页是 tabbar 页，来回切很频繁，
			 * 每次切都打一次接口既没必要也费流量。版本号由分类页在保存成功后写入本地存储，
			 * 不依赖事件总线（事件在页面生命周期边界上可能错过）。</p>
			 */
			async fnCheckSubscriptionChanged() {
				const rev = uni.getStorageSync('category.subscription.rev');
				if (!rev || rev === this.subscriptionRev) {
					return;
				}
				await this.fnOnSubscriptionChanged();
			},

			/**
			 * 订阅在分类页被改过之后：重取分类与列表。
			 *
			 * <p>首页与分类页是两个 tab，改完订阅切回来时首页还停在旧结果上，
			 * 靠这个事件当场刷新，不用等下拉或重启小程序。</p>
			 */
			async fnOnSubscriptionChanged() {
				this.feedPages = {};
				this.isLoadMore = false;
				this.fnLoadMoreDone();
				this.queryParams.page = 1;
				this.feedFallback = false;
				await this.fnLoadSubscription();
				// fnGetArticleList 内部按 feedPersonalized 分流，两条路都从这里进
				this.fnGetArticleList();
			},

			// 获取轮播图
			fnGetBanner() {
				if (!this.bannerConfig.enabled) {
					this.bannerList = [];
					return;
				}

				if (this.calcAuditModeEnabled) {
					this.bannerList = this.mockJson.home.bannerList.map((item) => {
						return {
							mp4: '',
							id: Date.now() * Math.random(),
							nickname: this.appInfo.name || '博主',
							avatar: this.appInfo.logo,
							address: '',
							createTime: item.time,
							title: item.title,
							src: this.$utils.checkThumbnailUrl(item.cover),
							image: this.$utils.checkThumbnailUrl(item.cover),
							type: "custom",
							content: "",
							url: ""
						}
					}).slice(0, this.bannerConfig.max);
					return;
				}

				if (this.bannerConfig.type === 'custom') {
					this.bannerList = this.bannerConfig.list.map((item) => {
						return {
							mp4: '',
							id: Date.now() * Math.random(),
							nickname: this.appInfo.name || '博主',
							avatar: this.appInfo.logo,
							address: '',
							createTime: "",
							title: item.title,
							src: this.$utils.checkThumbnailUrl(item.cover),
							image: this.$utils.checkThumbnailUrl(item.cover),
							type: "custom",
							content: item.content,
							url: item.url
						}
					}).slice(0, this.bannerConfig.max);
					return;
				}

				// 轮播有自己的取数口径：只看「站内置顶 + 最新」，与主列表的订阅筛选无关。
				// 也不能和主列表共用 queryParams——那个对象会被上拉分页改掉 page，
				// 一旦共用，下拉刷新时轮播可能取到的不是第一页。
				const paramsStr = qs.stringify({
					size: this.bannerConfig.fetchSize,
					page: 1,
					sort: ['spec.pinned,desc', 'spec.publishTime,desc']
				}, {
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
						const items = (res.data?.items || []).map((item) => {
							return {
								mp4: '',
								id: item.metadata.name,
								nickname: item.owner.displayName,
								avatar: this.$utils.checkAvatarUrl(item.owner.avatar),
								address: '',
								createTime: uni.$tm.dayjs(item.spec.publishTime).fromNow(),
								title: item.spec.title,
								src: this.$utils.checkThumbnailUrl(item.spec.cover),
								image: this.$utils.checkThumbnailUrl(item.spec.cover),
								type: "post",
								content: item.status.excerpt,
								url: "",
								/** 有没有真实封面：没有的话这里会是站点默认缩略图 */
								hasCover: !!item.spec.cover
							};
						});
						// 有封面的排前面：没封面的会被兜底成同一张默认图，
						// 首屏最显眼的位置摆 5 张一样的图比少几张更难看
						const withCover = items.filter((item) => item.hasCover);
						this.bannerList = (withCover.length ? withCover : items).slice(0, this.bannerConfig.max);
					},
					fail: (err) => {
						// 请求挂了就隐藏轮播，而不是留一块空盒子
						this.bannerList = [];
					}
				})

			},
			fnOnNotifyChange(e) {
				this.notify.show = e;
			},
			fnOnBannerClick(item) {
				if (this.calcAuditModeEnabled) {
					return;
				}
				if (item.type === 'custom') {
					if (item.content) {
						this.notify.data = item
						this.notify.show = true
						return;
					}
					if (uni.$utils.checkIsUrl(item.url)) {
						uni.navigateTo({
							url: '/pagesC/website/website?data=' +
								JSON.stringify({
									title: item.title || "加载中...",
									url: encodeURIComponent(item.url)
								})
						});
					}
					return;
				}

				if (item.id === '') return;
				this.fnToArticleDetail({
					metadata: {
						name: item.id
					}
				});
			},
			// 文章列表
			fnGetArticleList() {
				if (this.calcAuditModeEnabled) {
					this.articleList = this.mockJson.home.postList.map((item) => {
						return {
							metadata: {
								name: Date.now() * Math.random(),
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
					this.loading = 'success';
					this.loadMoreText = '呜呜，没有更多数据啦~';
					uni.hideLoading();
					uni.stopPullDownRefresh();
					this.fnLoadMoreDone();
					return;
				}
				// 主列表统一走服务端的并集接口：订阅了几个分类都只发一次请求，
				// 分类数量不再影响首页并发；零订阅时它会自动用人气分类兜底。
				// feedFallback 为 true 时（并集取不到内容/接口失败）才回到站内默认列表，
				// 这个标记也顺便避免了「并集 → 回落 → 又去试并集」的来回切换。
				if (!this.feedFallback) {
					this.fnGetUnionFeed();
					return;
				}

				// 设置状态为加载中
				if (!this.isLoadMore) {
					this.loading = 'loading';
				}
				this.loadMoreText = '加载中...';

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
						this.result.hasNext = data.hasNext;
						// 必须清洗：Halo 的 label 键名带点（content.halo.run/…），
						// 微信 setData 会把它当路径解析并在中途抛错，导致整次数据更新失败
						const items = this.$utils.stripDataUnsafeList(data.items);
						if (this.isLoadMore) {
							this.articleList = this.articleList.concat(items);
						} else {
							this.articleList = items;
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
						uni.stopPullDownRefresh();
						this.fnLoadMoreDone();
					}
				})
			},
			//跳转文章详情
			fnToArticleDetail(article) {
				if (this.calcAuditModeEnabled) {
					return;
				}
				// 顺手补一次「更新提醒」额度：这是用户本来就要做的点击，微信允许在这个时机
				// 调订阅授权；勾过「总是保持以上选择」的话不弹窗、也没有任何提示，
				// 于是用户同意一次之后就不必再回来手动续了（内部有多道闸门，见 utils/subscribeMessage.js）
				accumulatePushQuota(this.$httpApi.v2);
				uni.navigateTo({
					url: '/pagesA/article-detail/article-detail?name=' + article.metadata.name,
					animationType: 'slide-in-right'
				});
			},

			/** 进入视频号：小程序端跳视频号主页，其他端降级为复制 ID + 微信内搜索 */
			async fnOpenChannels() {
				if (!this.channelsConfig) return;
				const res = await openChannelsProfile(this.channelsConfig);
				if (!res.ok && res.reason) {
					uni.$tm.toast(res.reason);
				}
			},
			/** 进 AI 定制页：未登录/未开通时页面自己会给出购买与兑换引导 */
			fnOpenAiContent() {
				uni.navigateTo({
					url: '/pagesA/ai-content/ai-content'
				});
			},

			/**
			 * 活动公告弹窗：后端插件「基本配置 → 活动公告弹窗」开关控制，
			 * 每天最多弹一次（按标题 + 日期去重）。
			 */
			fnInitNotify() {
				const cfg = this.haloConfigs?.basicConfig?.notifyConfig || {};
				if (cfg?.enabled !== true || !cfg?.title) return;
				const key = `notify_shown:${cfg.title}`;
				const today = new Date().toDateString();
				if (uni.getStorageSync(key) === today) return;
				this.notify.data = {
					title: cfg.title,
					content: cfg.content || '',
					url: cfg.url || ''
				};
				this.notify.show = true;
				uni.setStorageSync(key, today);
			},

			// 快捷导航页面跳转
			fnToNavPage(item) {
				switch (item.type) {
					case 'tabbar':
						uni.switchTab({
							url: item.path
						});
						break;
					case 'page':
						uni.navigateTo({
							url: item.path
						});
						break;
				}
			},
			// 所有的文章列表页面
			fnToArticlesPage() {
				uni.navigateTo({
					url: '/pagesA/articles/articles'
				});
			},

			fnChangeMode() {
				const isBlackTheme = this.$tm.vx.state().tmVuetify.black;
				this.$tm.theme.setBlack(!isBlackTheme);
				uni.setNavigationBarColor({
					backgroundColor: !isBlackTheme ? '#0a0a0a' : '#ffffff',
					frontColor: !isBlackTheme ? '#ffffff' : '#0a0a0a'
				});
			},

			fnToSearch() {
				uni.navigateTo({
					url: '/pagesA/articles/articles'
				});
			},
			fnOnLogoToPage() {
				uni.switchTab({
					url: '/pages/tabbar/about/about'
				})
			},
		}
	};
</script>

<style lang="scss" scoped>
	.app-page {
		width: 100vw;
		min-height: 100vh;
		display: flex;
		flex-direction: column;
		// background-color: #ffffff;

		.logo {
			width: 60rpx;
			height: 60rpx;
			box-sizing: border-box;
		}

		.header-avatar {
			display: flex;
			align-items: center;
		}

		.logo--guest {
			background: $uh-fill;
			border: 1rpx solid $uh-line;
		}

		::v-deep {
			.tm-menubars .body .body_wk .left {
				min-width: initial;
			}
		}
	}

	.loading-wrap {
		padding: 24rpx;
	}

	.search-input {
		background-color: $uh-fill;
		align-items: center;
		/* #ifdef MP-WEIXIN */
		margin-right: 24rpx;

		/* #endif */
		&_icon {}

		&_text {}
	}

	.show-more {
		width: 42rpx;
		height: 42rpx;
		box-sizing: border-box;
		box-shadow: $uh-shadow-sm;
	}

	.banner {
		overflow: hidden;
	}

	.quick-nav {
		/* 走 Token 而不是写死白色：皮肤生成器按调色板字面量换色，
		   短写 #fff 不在调色板里 → 深色皮肤下会留一块白底 */
		background-color: $uh-card;
		box-sizing: border-box;

		// box-shadow: 0rpx 2rpx 24rpx rgba(0, 0, 0, 0.03);
		.name {
			color: #1f1f1f;
		}
	}

	.page-item {
		&_title {
			position: relative;
			padding-left: 24rpx;
			font-size: 32rpx;
			z-index: 1;
			color: #1f1f1f;

			&:before {
				content: '';
				position: absolute;
				left: 0rpx;
				top: 8rpx;
				width: 8rpx;
				height: 30rpx;
				background-color: $uh-accent;
				border-radius: 6rpx;
				z-index: 0;
			}
		}
	}

	.h_row_col2 {
		display: flex;
		flex-wrap: wrap;
		box-sizing: border-box;
		padding: 0 12rpx;

		.ani-item {
			width: 50%;
		}
	}

	/* ===== 主列表个性化提示 ===== */
	.feed-hint {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 12rpx;
		padding: 14rpx 20rpx;
		border-radius: $uh-radius-md;
		background: $uh-accent-weak;
	}

	.feed-hint-text {
		flex: 1;
		font-size: 21rpx;
		color: $uh-accent;
	}

	/* 只有「零订阅引导」可关：它是建议不是功能提示，反复出现会变成噪音 */
	.feed-hint-close {
		flex-shrink: 0;
		padding: 4rpx 16rpx;
		border-radius: $uh-radius-pill;
		border: 1rpx solid $uh-accent;
		font-size: 20rpx;
		color: $uh-accent;
	}

	/* ===== 首页双卡并排：AI 定制内容 + 视频号 =====
	   并排省高度、首屏更快看到文章列表；只有一张可见时 flex:1 自然占满整行。
	   视觉分工：AI 卡浅底描边（轻），视频号卡品牌渐变（重），一深一浅有层次。 */
	.promo-row {
		display: flex;
		align-items: stretch;
	}

	.promo-card {
		display: flex;
		flex-direction: column;
		flex: 1;
		min-width: 0;
		padding: 22rpx;
	}

	/* 两张卡之间的缝；只有一张时这条不生效 */
	.promo-card + .promo-card {
		margin-left: 16rpx;
	}

	/* 点击反馈：卡片整体是热区，按下时压一点透明度比换色更稳 */
	.promo-card--pressed {
		opacity: 0.92;
	}

	.promo-head {
		/* 顶部一行固定高度：两卡的标题因此始终对齐 */
		min-height: 76rpx;
	}

	.promo-body {
		margin-top: 14rpx;
		min-width: 0;
	}

	/* 标题与徽章同行：徽章跟着标题走，横排/竖排两种布局都不用改结构 */
	.promo-title-row {
		display: flex;
		align-items: center;
		min-width: 0;
	}

	.promo-title {
		min-width: 0;
		font-size: 28rpx;
		font-weight: 700;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.promo-desc {
		margin-top: 8rpx;
		font-size: 21rpx;
		line-height: 1.5;
		/* 最多两行：后台文案可能写很长，不能让它把卡片撑高 */
		display: -webkit-box;
		-webkit-box-orient: vertical;
		-webkit-line-clamp: 2;
		overflow: hidden;
	}

	/* 动作行永远贴底：两卡文案长短不一时高度差由它吸收 */
	.promo-action {
		margin-top: auto;
		padding-top: 16rpx;
	}

	/*
		只有一张卡可见时它占满整行，竖排会显得空旷：
		切成「图标左 / 文字中 / 动作右」的横排，比拉长的竖排饱满得多。
	*/
	.promo-card--wide {
		flex-direction: row;
		align-items: center;
		padding: 28rpx 32rpx;
	}

	.promo-card--wide .promo-head {
		min-height: 0;
		flex-shrink: 0;
	}

	.promo-card--wide .promo-body {
		flex: 1;
		margin: 0 0 0 20rpx;
	}

	.promo-card--wide .promo-action {
		margin: 0 0 0 16rpx;
		padding-top: 0;
		flex-shrink: 0;
	}

	.promo-action_text {
		font-size: 22rpx;
		font-weight: 600;
	}

	.promo-action_arrow {
		margin-left: 4rpx;
		font-size: 26rpx;
		line-height: 1;
	}

	/* ── AI 定制内容：浅色卡 + 渐变方块 ── */
	.ai-card {
		position: relative;
		overflow: hidden;
		background: $uh-card;
		border: 1rpx solid $uh-accent-weak;
	}

	.ai-card-icon {
		display: flex;
		align-items: center;
		justify-content: center;
		width: 76rpx;
		height: 76rpx;
		border-radius: 18rpx;
		background-image: $uh-grad-accent;
	}

	.ai-card-icon_text {
		font-size: 28rpx;
		font-weight: 800;
		color: #ffffff;
	}

	.ai-card-badge {
		flex-shrink: 0;
		margin-left: 12rpx;
		padding: 2rpx 14rpx;
		border-radius: $uh-radius-pill;
		background: $uh-success-weak;
		font-size: 20rpx;
		color: $uh-success;
	}

	.promo-title--ai {
		color: $uh-ink-1;
	}

	.promo-desc--ai {
		color: $uh-ink-3;
	}

	.promo-action_text--ai,
	.promo-action_arrow--ai {
		color: $uh-accent;
	}

	/* ── 视频号：品牌渐变卡 + 白色文字 ──
	   皮肤生成器不会把 color 上的 #ffffff 重染色，深色皮肤下依然是「白字 + 品牌色底」。 */
	.channels-card {
		position: relative;
		overflow: hidden;
		background-image: $uh-grad-accent;

		/* 右上角一枚半透明大圆：给纯渐变加层次，避免一整块死板的直筒渐变 */
		&:before {
			content: '';
			position: absolute;
			top: -80rpx;
			right: -60rpx;
			width: 200rpx;
			height: 200rpx;
			border-radius: 50%;
			background-color: rgba(255, 255, 255, 0.12);
		}
	}

	.channels-avatar {
		position: relative;
		flex-shrink: 0;
		width: 76rpx;
		height: 76rpx;
	}

	.channels-avatar_img {
		display: block;
		width: 76rpx;
		height: 76rpx;
		border-radius: 22rpx;
		border: 2rpx solid rgba(255, 255, 255, 0.7);
		box-sizing: border-box;
	}

	.channels-avatar_text {
		display: flex;
		align-items: center;
		justify-content: center;
		width: 76rpx;
		height: 76rpx;
		border-radius: 22rpx;
		border: 2rpx solid rgba(255, 255, 255, 0.7);
		box-sizing: border-box;
		background-color: rgba(255, 255, 255, 0.22);
		font-size: 34rpx;
		font-weight: 600;
		color: #ffffff;
	}

	/* 头像右下角的播放角标：一眼看出这是视频入口 */
	.channels-avatar_play {
		position: absolute;
		right: -6rpx;
		bottom: -6rpx;
		display: flex;
		align-items: center;
		justify-content: center;
		width: 32rpx;
		height: 32rpx;
		border-radius: 50%;
		background-color: #ffffff;
	}

	.channels-avatar_triangle {
		width: 0;
		height: 0;
		margin-left: 3rpx;
		border-top: 7rpx solid transparent;
		border-bottom: 7rpx solid transparent;
		border-left: 11rpx solid $uh-accent;
	}

	.channels-badge {
		flex-shrink: 0;
		margin-left: 12rpx;
		padding: 2rpx 14rpx;
		border-radius: $uh-radius-pill;
		background-color: rgba(255, 255, 255, 0.22);
		font-size: 20rpx;
		color: #ffffff;
	}

	.promo-title--channels {
		color: #ffffff;
	}

	.promo-desc--channels {
		color: rgba(255, 255, 255, 0.82);
	}

	.promo-action_text--channels,
	.promo-action_arrow--channels {
		color: #ffffff;
	}

	.nav-box {		background-color: $uh-card;
		overflow: hidden;
		margin-bottom: 24rpx;
	}

	.nav-list {
		flex-wrap: wrap;
		align-items: center;
		// justify-content: space-between;
		//justify-content: space-around;
		//justify-content: start;
		display: grid;
		grid-template-columns: repeat(4, 1fr);
	}

	.nav-item {
		font-size: 26rpx;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 12rpx;
	}

	.nav-item-icon {
		display: flex;
		align-items: center;
		justify-content: center;
		height: 96rpx;
		padding-bottom: 6rpx;
	}

	.nav-item-text {
		font-size: 24rpx;
	}
</style>