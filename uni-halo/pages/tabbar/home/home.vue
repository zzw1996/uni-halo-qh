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
				视频号入口（原「每日一句」区块）
				卡片内容全部来自后台「视频号」配置；没开启或没填视频号 ID 时整块不渲染——
				点不动的卡片比没有卡片更糟。跳转与降级见 utils/channels.js。
			-->
			<view class="channels-card ml-24 mr-24 mt-16 mb-16 round-6 card-shadow" v-if="channelsConfig"
				hover-class="channels-card--pressed" :hover-stay-time="60" @click="fnOpenChannels">
				<view class="channels-main flex items-center">
					<view class="channels-avatar">
						<image v-if="channelsAvatar" class="channels-avatar_img" :src="channelsAvatar" mode="aspectFill"></image>
						<text v-else class="channels-avatar_text">{{ channelsAvatarText }}</text>
						<view class="channels-avatar_play">
							<text class="channels-avatar_triangle"></text>
						</view>
					</view>
					<view class="channels-meta flex-1">
						<view class="channels-meta_head flex items-center">
							<text class="channels-name">{{ channelsConfig.nickname }}</text>
							<text class="channels-badge">视频号</text>
						</view>
						<text v-if="channelsConfig.desc" class="channels-desc">{{ channelsConfig.desc }}</text>
					</view>
					<view class="channels-enter flex items-center">
						<text class="channels-enter_text">{{ channelsButtonText }}</text>
						<text class="channels-enter_arrow">›</text>
					</view>
				</view>
				<view class="channels-foot flex items-center">
					<text class="channels-foot_tip">{{ channelsTip }}</text>
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
			<!-- 筛选结果为空时说明原因，而不是留一个空首页 -->
			<view v-if="feedFallback" class="feed-hint ml-24 mr-24 mb-16">
				<text class="feed-hint-text">{{ feedFallbackText }}</text>
			</view>

			<view v-if="articleList.length === 0" class="article-empty">
				<tm-empty icon="icon-shiliangzhinengduixiang-" label="博主还没有发表任何内容~"></tm-empty>
			</view>
			<block v-else>
				<view :class="globalAppSettings.layout.home">
					<tm-translate v-for="(article, index) in articleList" :key="index" class="ani-item"
						animation-name="fadeUp" :wait="calcAniWait(index)">
						<article-card from="home" :article="article" :post="article"
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
	import NotifyDialog from "@/components/notify-dialog/notify-dialog.vue";
	import uhTabbar from '@/components/uh-tabbar/uh-tabbar.vue';
	import { openChannelsProfile } from '@/utils/channels.js';
	import { tabbarBottomOffsetPx } from '@/utils/tabbar.js';
	import qs from 'qs'
	import {
		checkHasWxLogin,
		getWxLoginInfo
	} from '@/utils/auth.js';

	export default {
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
			uni.$on('wx_login_status_changed', this.fnSyncWxUser);
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
			uni.$off('wx_login_status_changed', this.fnSyncWxUser);
			uni.$off('category_subscription_changed', this.fnOnSubscriptionChanged);
		},
		onPullDownRefresh() {
			this.isLoadMore = false;
			this.queryParams.page = 1;
			// 多路分页也要重置，否则下拉后每一路都从中间页继续取
			this.feedPages = {};
			this.fnQuery();
		},
		onReachBottom(e) {
			if (this.calcAuditModeEnabled) {
				uni.showToast({
					icon: 'none',
					title: '没有更多数据了'
				});
				return
			}
			if (this.result.hasNext) {
				this.queryParams.page += 1;
				this.isLoadMore = true;
				this.fnGetArticleList();
			} else {
				uni.showToast({
					icon: 'none',
					title: '没有更多数据了'
				});
			}
		},
		methods: {
			fnSyncWxUser() {
				this.wxUserInfo = checkHasWxLogin() ? getWxLoginInfo() : null;
			},
			/**
			 * 首页数据入口。
			 *
			 * <p>顺序有讲究：先拿订阅分类，才能决定主列表是「按订阅筛选」还是「站内默认」。</p>
			 */
			async fnQuery() {
				// 每次重新拉取都从「没有回落」开始，否则上一轮的空列表提示会一直挂着
				this.feedFallback = false;
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
				this.isLoadMore = false;
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
					return;
				}
				// 配了「站点分类」关注条件时按它筛选；未配或已退回则走原路径
				// 只看 feedPersonalized：它已经包含「继承来的分类」，
				// 光比 postCategories 会让「只继承、没自己订」的用户被当成没订阅
				if (this.feedPersonalized) {
					this.fnGetPersonalArticleList();
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
					},
					fail: (err) => {
						this.loading = 'error';
						this.loadMoreText = '加载失败，请下拉刷新！';
						uni.$tm.toast(err.message || '数据加载失败！');
						uni.stopPullDownRefresh();
					}
				})
			},
			//跳转文章详情
			fnToArticleDetail(article) {
				if (this.calcAuditModeEnabled) {
					return;
				}
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
		background-color: #fff;
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
		padding: 14rpx 20rpx;
		border-radius: $uh-radius-md;
		background: $uh-accent-weak;
	}

	.feed-hint-text {
		flex: 1;
		font-size: 21rpx;
		color: $uh-accent;
	}

	/* ===== 视频号入口卡片 =====
	   整页唯一一块渐变卡：视频号是「站外内容」的入口，用品牌色块比白卡更容易被看见。
	   文字一律白色——皮肤生成器不会把 color 上的 #ffffff 重染色，
	   所以深色皮肤下依然是「白字 + 品牌色底」，不会出现白字压白底。 */
	.channels-card {
		position: relative;
		overflow: hidden;
		background-image: $uh-grad-accent;

		/* 右上角一枚半透明大圆：给纯渐变加层次，避免一整块死板的直筒渐变 */
		&:before {
			content: '';
			position: absolute;
			top: -90rpx;
			right: -70rpx;
			width: 240rpx;
			height: 240rpx;
			border-radius: 50%;
			background-color: rgba(255, 255, 255, 0.12);
		}
	}

	/* 点击反馈：卡片整体是热区，按下时压一点透明度比换色更稳 */
	.channels-card--pressed {
		opacity: 0.92;
	}

	.channels-main {
		position: relative;
		padding: 24rpx;
	}

	.channels-avatar {
		position: relative;
		flex-shrink: 0;
		width: 96rpx;
		height: 96rpx;
	}

	.channels-avatar_img {
		display: block;
		width: 96rpx;
		height: 96rpx;
		border-radius: 28rpx;
		border: 2rpx solid rgba(255, 255, 255, 0.7);
		box-sizing: border-box;
	}

	.channels-avatar_text {
		display: flex;
		align-items: center;
		justify-content: center;
		width: 96rpx;
		height: 96rpx;
		border-radius: 28rpx;
		border: 2rpx solid rgba(255, 255, 255, 0.7);
		box-sizing: border-box;
		background-color: rgba(255, 255, 255, 0.22);
		font-size: 40rpx;
		font-weight: 600;
		color: #ffffff;
	}

	/* 头像右下角的播放角标：一眼看出这是视频入口 */
	.channels-avatar_play {
		position: absolute;
		right: -8rpx;
		bottom: -8rpx;
		display: flex;
		align-items: center;
		justify-content: center;
		width: 38rpx;
		height: 38rpx;
		border-radius: 50%;
		background-color: #ffffff;
	}

	.channels-avatar_triangle {
		width: 0;
		height: 0;
		margin-left: 4rpx;
		border-top: 8rpx solid transparent;
		border-bottom: 8rpx solid transparent;
		border-left: 13rpx solid $uh-accent;
	}

	.channels-meta {
		min-width: 0;
		padding: 0 16rpx 0 20rpx;
	}

	.channels-meta_head {
		min-width: 0;
	}

	.channels-name {
		font-size: 30rpx;
		font-weight: 600;
		color: #ffffff;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
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

	.channels-desc {
		margin-top: 8rpx;
		font-size: 22rpx;
		line-height: 1.5;
		color: rgba(255, 255, 255, 0.82);
		/* 简介最多两行：后台可能写很长，不能让它把卡片撑高 */
		display: -webkit-box;
		-webkit-box-orient: vertical;
		-webkit-line-clamp: 2;
		overflow: hidden;
	}

	.channels-enter {
		flex-shrink: 0;
		padding: 12rpx 20rpx;
		border-radius: $uh-radius-pill;
		background-color: #ffffff;
	}

	.channels-enter_text {
		font-size: 24rpx;
		font-weight: 600;
		color: $uh-accent;
	}

	.channels-enter_arrow {
		margin-left: 4rpx;
		font-size: 28rpx;
		line-height: 1;
		color: $uh-accent;
	}

	.channels-foot {
		position: relative;
		padding: 14rpx 24rpx;
		background-color: rgba(255, 255, 255, 0.14);
	}

	.channels-foot_tip {
		flex: 1;
		min-width: 0;
		font-size: 21rpx;
		color: rgba(255, 255, 255, 0.85);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
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