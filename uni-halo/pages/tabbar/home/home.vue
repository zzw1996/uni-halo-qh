<template>
	<view :class="skinClass" class="theme-page-pages-tabbar-home-home app-page">
		<uh-page-header :title="appInfo.name || 'Halo'" brand></uh-page-header>

		<view class="home-search-row">
			<view class="search-input" @click="fnToSearch">
				<text class="iconfont icon-search search-input_icon"></text>
				<text class="search-input_text">搜索内容、发现灵感</text>
			</view>
			<view class="header-avatar" @click="fnOnLogoToPage">
				<image v-if="wxUserInfo && wxUserInfo.avatarUrl" class="logo" :src="calcHeaderAvatar" mode="aspectFill"></image>
				<view v-else class="logo logo--guest flex flex-center"><text class="iconfont icon-user-circle"></text></view>
			</view>
		</view>
		<!-- 枫光改版：问候 + 签到入口（签到本身在「我的」页，这里只做引导，不重复请求积分接口） -->
		<view class="home-greet">
			<view class="home-greet_text">
				<text class="home-greet_hello">{{ calcGreeting }}</text>
				<text class="home-greet_title">今天想读点什么？</text>
			</view>
			<view class="home-greet_chip" hover-class="home-greet_chip--pressed" :hover-stay-time="60" @click="fnToCheckin">
				<text class="home-greet_chip-dot"></text>
				<text class="home-greet_chip-text">签到领积分</text>
			</view>
		</view>
		<view v-if="loading !== 'success' && articleList.length===0" class="loading-wrap">
			<tm-skeleton model="card"></tm-skeleton>
			<tm-skeleton model="cardActions"></tm-skeleton>
			<tm-skeleton model="list"></tm-skeleton>
			<tm-skeleton model="listAvatr"></tm-skeleton>
			<tm-skeleton model="listAvatr"></tm-skeleton>
			<tm-skeleton model="listAvatr"></tm-skeleton>
		</view>
		<block v-else>
			<view class="banner ml-24 mr-24 mt-12 mb-24 card-shadow" v-if="bannerConfig.enabled && bannerList.length">
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
			<!-- 枫光改版：金刚区。AI / 视频号跟随原有开关，任务与邀请是常驻玩法入口 -->
			<view class="home-quick">
				<view v-if="aiCardVisible" class="home-quick_item" @click="fnOpenAiContent">
					<view class="home-quick_icon home-quick_icon--night"><uh-icon name="sparkles" :size="44"></uh-icon></view>
					<text class="home-quick_label">AI 日报</text>
				</view>
				<view v-if="channelsConfig" class="home-quick_item" @click="fnOpenChannels">
					<view class="home-quick_icon home-quick_icon--accent"><uh-icon name="play" :size="44"></uh-icon></view>
					<text class="home-quick_label">视频号</text>
				</view>
				<view class="home-quick_item" @click="fnToTaskCenter">
					<view class="home-quick_icon home-quick_icon--reward">
						<uh-icon name="circle-check" :size="44"></uh-icon>
						<text class="home-quick_badge">赚积分</text>
					</view>
					<text class="home-quick_label">每日任务</text>
				</view>
				<view class="home-quick_item" @click="fnToInvite">
					<view class="home-quick_icon home-quick_icon--blue"><uh-icon name="gift" :size="44"></uh-icon></view>
					<text class="home-quick_label">邀请有礼</text>
				</view>
			</view>
			<view class="promo-row ml-24 mr-24 mt-16 mb-16" v-if="aiCardVisible || channelsConfig">
				<!-- AI 定制内容：已开通显示徽章；未开通点进去是购买与积分兑换的引导页 -->
				<view v-if="aiCardVisible" class="promo-card ai-card card-shadow"
					:class="{ 'promo-card--wide': promoIsWide }"
					hover-class="promo-card--pressed" :hover-stay-time="60" @click="fnOpenAiContent">
					<view class="promo-head flex items-center">
						<view class="ai-card-icon">
							<uh-icon name="sparkles" :size="40"></uh-icon>
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
				<view v-if="channelsConfig" class="promo-card channels-card card-shadow"
					:class="{ 'promo-card--wide': promoIsWide }"
					hover-class="promo-card--pressed" :hover-stay-time="60" @click="fnOpenChannels">
					<view class="promo-head flex items-center">
						<view class="channels-avatar">
							<image v-if="channelsAvatar" class="channels-avatar_img" :src="channelsAvatar" mode="aspectFill"></image>
							<text v-else class="channels-avatar_text">{{ channelsAvatarText }}</text>
							<view class="channels-avatar_play">
								<uh-icon name="play" :size="20"></uh-icon>
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
			<view class="home-section-head flex flex-between">
				<view class="page-item_title text-weight-b">最新文章</view>
				<view class="show-more" @click="fnToArticlesPage"><text>全部</text><text class="iconfont icon-arrow-right"></text></view>
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
					<view v-for="(article, index) in articleList" :key="index"
						class="ani-item" :class="{ 'ani-fade-up': index < 10 }"
						:style="index < 10 ? 'animation-delay:' + (index * 50 + 50) + 'ms' : ''">
						<article-card from="home" :article="article" :post="article" :show-category="true"
							@on-click="fnToArticleDetail"></article-card>
					</view>
				</view>
				<view class="load-text mt-12">{{ loadMoreText }}</view>
			</block>
		</block>

		<!--
			右下角悬浮动作区：写文章 + 回到顶部。
			两个按钮放在同一个从底往上排的竖列里，而不是各用各的 tm-flotbutton——
			那组件的 offset 只在 mounted 时算一次，运行时改 offset 不会重新定位
			（源码里 thisPos 只在 init() 里赋值）。共用一个贴底的容器，
			「回到顶部」出现/消失时「写文章」就会被自然顶上/落回原位。

			「写文章」的资格由服务端判定（总开关 / 积分门槛 / 角色策略），
			客户端只额外滤掉演示模式；入口不再挂在「我的」页。
		-->
		<view class="home-fabs" :style="calcFabsStyle">
			<view
				v-if="loading === 'success' && canPublishArticle"
				class="home-fab home-fab--publish"
				hover-class="home-fab--pressed"
				:hover-stay-time="60"
				@click="fnToPublishArticle"
			>
				<text class="home-fab_icon iconfont icon-plus"></text>
				<text class="home-fab_label">写文章</text>
			</view>
			<view
				v-if="showTopButton"
				class="home-fab home-fab--top"
				hover-class="home-fab--pressed"
				:hover-stay-time="60"
				@click="fnToTopPage(400)"
			>
				<text class="home-fab_icon iconfont icon-angle-up"></text>
			</view>
		</view>

		<!-- 弹窗 -->
		<NotifyDialog v-if="notify.show" :show="notify.show" :title="notify.data.title" :content="notify.data.content"
			:url="notify.data.url" @on-change="fnOnNotifyChange"></NotifyDialog>
		<!-- 自绘底部导航：后台「底部导航」开关关闭时不渲染，仍用微信原生底栏 -->
		<uh-tabbar current="home"></uh-tabbar>
	</view>
</template>

<script>

import UhIcon from '@/components/uh-icon/uh-icon.vue';
import UhPageHeader from '@/components/uh-page-header/uh-page-header.vue';
	import tmSkeleton from '@/tm-vuetify/components/tm-skeleton/tm-skeleton.vue';
	import tmEmpty from '@/tm-vuetify/components/tm-empty/tm-empty.vue';
	import tmGrid from '@/tm-vuetify/components/tm-grid/tm-grid.vue';

	import eSwiper from '@/components/e-swiper/e-swiper.vue';
	import loadMoreMixin from '@/common/mixins/loadMore.js';
	import NotifyDialog from "@/components/notify-dialog/notify-dialog.vue";
	import uhTabbar from '@/components/uh-tabbar/uh-tabbar.vue';
	import { openChannelsProfile } from '@/utils/channels.js';
	import { tabbarBottomOffsetPx } from '@/utils/tabbar.js';
	import { accumulatePushQuota } from '@/utils/subscribeMessage.js';
	import { refreshAppConfigs } from '@/utils/configRefresh.js';
	import qs from 'qs'
	import {
		checkHasWxLogin,
		getWxLoginInfo
	} from '@/utils/auth.js';
	import { fetchUserPublishStatus } from '@/utils/userPublish.js';

	/** 零订阅引导条「知道了」的本地标记：关掉后不再打扰（它不是功能提示，只是建议） */
const HOME_GUIDE_DISMISSED_KEY = 'home.feedGuideDismissed';

/** 下滑超过这个距离才给「回到顶部」：一两屏以内本来也不难回顶，出现反而碍眼 */
const TOP_BUTTON_THRESHOLD = 300;

export default {
		mixins: [loadMoreMixin],
		components: {

		UhIcon,
		UhPageHeader,
			tmSkeleton,
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
				/**
				 * 「写文章」悬浮入口是否显示。
				 *
				 * <p>必须声明在 data 里：未声明的实例属性没有响应性，读它的 watcher
				 * 会把首次算出的值永久缓存住，入口就会定格成不显示。</p>
				 */
				canPublishArticle: false,
				/**
				 * 「回到顶部」是否显示：跟着滚动位置走，而不是「列表够长」这种静态条件——
				 * 静态条件下只要这一屏的文章数没到阈值，用户滑到底也看不到回顶按钮。
				 */
				showTopButton: false,
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
			/** 按本地时间问候；带上昵称更像「对你说话」 */
			calcGreeting() {
				const h = new Date().getHours();
				const part = h < 5 ? '夜深了' : h < 11 ? '早上好' : h < 13 ? '中午好' : h < 18 ? '下午好' : '晚上好';
				const name = this.wxUserInfo && (this.wxUserInfo.nickName || this.wxUserInfo.nickname);
				return name ? `${part}，${name}` : part;
			},
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
			/**
			 * 悬浮动作区距屏幕底部的距离。
			 *
			 * <p>与 tm-flotbutton 同口径：底部安全区 + 自绘底栏占用（它内部算的是
			 * {@code bottom: offset + safeArea}）。自绘底栏关闭时 tabbarBottomOffsetPx()
			 * 为 0，退化成「离底 16px + 安全区」，与改用竖列之前的位置一致。</p>
			 */
			calcFabsStyle() {
				const sys = uni.getSystemInfoSync();
				const screenBottom = (sys && sys.safeArea && sys.safeArea.bottom) || 0;
				const safeArea = Math.abs((sys && sys.screenHeight ? sys.screenHeight : 0) - screenBottom);
				return `bottom: ${16 + tabbarBottomOffsetPx() + safeArea}px;`;
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
			'haloConfigs.basicConfig.notifyConfig': {
				handler(val) {
					if (!val) return;
					this.fnInitNotify();
				},
				immediate: true,
			},
			/**
			 * 演示模式（审核模式）展示的是 mock 内容，绝不能让真投稿入口出现——
			 * 否则审核员点「写文章」就发到真实站点上了。开关翻转时立刻重判。
			 */
			calcAuditModeEnabled(val) {
				if (val) {
					this.canPublishArticle = false;
				} else {
					this.fnRefreshPublishPermission();
				}
			}
		},
		onLoad() {
			this.fnSetPageTitle();
			this.fnInitNotify();
		},
		created() {
			this.fnSyncWxUser();
			uni.$on('wx_login_status_changed', this.fnOnWxLoginStatusChanged);
			// 订阅在分类页改的，不需要事件：改订阅的页面一定会写版本号，
			// 而回到本页时 onShow 的 fnCheckSubscriptionChanged 必定对得上（事件反而可能在
			// 页面生命周期边界上错过，还会让首页在后台 tab 上白拉一次列表）。
			// 积分则只把节流窗口作废：门槛按余额算，赚够/花光之后切回首页自然拿到新结论，
			// 不必在此刻（本页通常在后台）发请求。
			uni.$on('points_updated', this.fnExpirePublishPermRefresh);
			// 投稿成功返回列表：把新文章带进来
			uni.$on('article_list_should_refresh', this.fnReloadArticleList);
			this.fnQuery();
		},
		onShow() {
			this.fnSyncWxUser();
			// 订阅是在分类页改的，本页可能还停在旧结果上：按版本号对一次，变了就重取。
			this.fnCheckSubscriptionChanged();
			// 入口的显示条件全在服务端，进页面取一次最新结论
			this.fnRefreshPublishPermission();
		},
		/**
		 * 「回到顶部」跟着滚动位置显隐。
		 *
		 * <p>只在跨阈值那一刻写 data：scroll 事件很密（小程序里每次 setData 都要过桥），
		 * 每次都赋值会让滚动明显发涩。</p>
		 */
		onPageScroll(e) {
			const next = (e && e.scrollTop ? e.scrollTop : 0) > TOP_BUTTON_THRESHOLD;
			if (next !== this.showTopButton) {
				this.showTopButton = next;
			}
		},
		onUnload() {
			uni.$off('wx_login_status_changed', this.fnOnWxLoginStatusChanged);
			uni.$off('points_updated', this.fnExpirePublishPermRefresh);
			uni.$off('article_list_should_refresh', this.fnReloadArticleList);
		},
		onPullDownRefresh() {
			this.isLoadMore = false;
			this.fnLoadMoreDone();
			this.queryParams.page = 1;
			// 多路分页也要重置，否则下拉后每一路都从中间页继续取
			this.feedPages = {};
			// 顺手对齐一次站点配置（节流）：站长在后台改完底部导航/首页布局，下拉就能看到
			refreshAppConfigs();
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
					// 换了账号，能不能投稿的结论要跟着换；登录对话框也可能就开在本页上
					this.fnRefreshPublishPermission(true);
				}
			},
			/**
			 * 「写文章」入口的显隐判定。
			 *
			 * <p>能不能发、入口显不显示，一律以服务端结论为准（总开关、积分门槛、角色策略、
			 * 每日额度都在服务端算好）。未登录或接口不可用时拿到 null，入口不显示——
			 * 与「发表瞬间」一致，不在客户端另拼一套条件。</p>
			 */
			async fnRefreshPublishPermission(force) {
				if (this.calcAuditModeEnabled) {
					this.canPublishArticle = false;
					return;
				}
				const now = Date.now();
				if (!force && this._lastPublishPermRefresh && now - this._lastPublishPermRefresh < 30000) {
					return;
				}
				this._lastPublishPermRefresh = now;
				const status = await fetchUserPublishStatus(this.$httpApi.v2);
				this.canPublishArticle = !!(status && status.canPublishArticle && status.visible);
			},
			fnExpirePublishPermRefresh() {
				this._lastPublishPermRefresh = 0;
			},
			fnToPublishArticle() {
				if (!this.canPublishArticle || this.calcAuditModeEnabled) {
					return;
				}
				if (!checkHasWxLogin()) {
					uni.showToast({ icon: 'none', title: '请先登录后再投稿' });
					return;
				}
				uni.navigateTo({ url: '/pagesA/article-publish/article-publish' });
			},
			/** 投稿成功返回列表时重拉：订阅分流、置顶排序都要跟着重算，不能只插一条 */
			fnReloadArticleList() {
				this.isLoadMore = false;
				this.fnLoadMoreDone();
				this.queryParams.page = 1;
				this.feedPages = {};
				this.fnQuery();
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
			 * 由 onShow 的版本号比对触发这里，不用等下拉或重启小程序。</p>
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
			/** 签到在「我的」页完成（那边有连签进度与奖励），首页只负责带过去 */
			fnToCheckin() {
				uni.switchTab({
					url: '/pages/tabbar/about/about'
				});
			},
			fnToTaskCenter() {
				uni.navigateTo({
					url: '/pagesA/task-center/task-center'
				});
			},
			fnToInvite() {
				uni.navigateTo({
					url: '/pagesA/invite/invite'
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
			color: #1A1A1F;
		}
	}

	.page-item {
		&_title {
			position: relative;
			padding-left: 24rpx;
			font-size: 32rpx;
			z-index: 1;
			color: #1A1A1F;

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

	/* ===== CSS 入场动画（替代 tm-translate 组件，零 JS 开销）===== */
	@keyframes fadeUp {
		from {
			opacity: 0;
			transform: translateY(40rpx);
		}
		to {
			opacity: 1;
			transform: translateY(0);
		}
	}
	.ani-fade-up {
		animation: fadeUp 0.5s ease-out both;
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

	/*
	 * 右下角悬浮动作区。
	 *
	 * 固定贴右下角、从底往上排：「回到顶部」在竖列末尾（贴角），
	 * 「写文章」在它上面——后者出现/消失时前者会被自然顶上/落回。
	 * 配色全部走设计 Token（强调渐变 + 强调色），换皮肤/暗色由
	 * tools/gen-skins.cjs 生成覆盖，不写死颜色。
	 */
	.home-fabs {
		position: fixed;
		right: 16px;
		z-index: 900;
		display: flex;
		flex-direction: column;
		align-items: center;
	}

	.home-fab {
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		box-sizing: border-box;
		box-shadow: $uh-shadow-md;
	}

	.home-fab--pressed {
		opacity: 0.86;
	}

	/* 写文章：强调渐变 + 图标与文字（与投稿页主按钮同一套 Token） */
	.home-fab--publish {
		width: 92rpx;
		padding: 12rpx 6rpx;
		border-radius: 26rpx;
		background-image: $uh-grad-accent;
	}

	.home-fab_label {
		margin-top: 2rpx;
		font-size: 20rpx;
		line-height: 1.2;
		color: #fff;
	}

	/* 回到顶部：圆形，纯强调色，单独占角落 */
	.home-fab--top {
		width: 64rpx;
		height: 64rpx;
		border-radius: 50%;
		background-color: $uh-accent;
	}

	.home-fab--publish + .home-fab--top {
		margin-top: 16rpx;
	}

@import '@/common/styles/redesign/home.scss';


@import '@/common/styles/redesign/pages-tabbar-home-home-themes.scss';
</style>