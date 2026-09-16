<template>
	<view class="app-page">
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
			<view v-if="bannerConfig.enabled" class="bg-white pb-24">
				<view class="banner bg-white ml-24 mr-24 mt-12 round-3" v-if="bannerList.length !== 0">
					<e-swiper :height="bannerConfig.height" :dotPosition="bannerConfig.dotPosition" :autoplay="true"
						:useDot="bannerConfig.showIndicator" :showTitle="bannerConfig.showTitle"
						:type="bannerConfig.type" :list="bannerList" @on-click="fnOnBannerClick" />
				</view>
			</view>

			<!-- 每日一句 + 倒数日（原「热榜」块已随榜单页上线移除） -->
			<view class="daily-block ml-24 mr-24 mt-16 mb-16 bg-white round-3">
				<view class="daily-quote uh-py-12">
					<view class="daily-quote-head flex flex-between">
						<text class="page-item_title text-weight-b">每日一句</text>
						<view class="daily-quote-head-right">
							<text class="daily-toolbox-link" @click="fnToToolbox">工具箱</text>
							<text class="daily-quote-date">{{ todayQuoteDate }}</text>
						</view>
					</view>
					<text class="daily-quote-text">{{ todayQuote.text }}</text>
					<view class="daily-quote-foot flex flex-between">
						<text class="daily-quote-author">—— {{ todayQuote.author }}</text>
						<view class="daily-quote-actions">
							<text class="daily-quote-copy" @click="fnCopyTodayQuote">复制</text>
							<text class="daily-quote-share" @click="fnShareDailyQuote">分享</text>
						</view>
					</view>
				</view>
				<!-- <view class="daily-countdown">
					<view class="countdown-head flex flex-between">
						<text class="page-item_title text-weight-b">倒数日</text>
						<view class="countdown-add-btn" @click="fnToggleCountdownForm">
							<text>{{ countdownShowForm ? '收起' : '＋ 新增' }}</text>
						</view>
					</view>
					<view v-if="countdownShowForm" class="countdown-form">
						<input class="countdown-input" v-model="countdownForm.name" placeholder="名称，如：恋爱纪念日" maxlength="20" />
						<picker mode="date" :value="countdownForm.date" @change="fnOnCountdownDateChange">
							<view class="countdown-input countdown-date">{{ countdownForm.date || '选择日期' }}</view>
						</picker>
						<view class="countdown-add-btn countdown-add-btn-solid" @click="fnAddCountdown">
							<text>添加</text>
						</view>
					</view>
					<view v-if="countdownList.length" class="countdown-list">
						<view v-for="(item, index) in countdownList" :key="index" class="countdown-item flex flex-between">
							<view class="countdown-main">
								<text class="countdown-name">{{ item.name }}</text>
								<text class="countdown-date-text">{{ item.date }}</text>
							</view>
							<text class="countdown-days" :class="{ 'is-today': item.days === 0 }">
								{{ item.days === 0 ? '就是今天！' : (item.days > 0 ? `还有 ${item.days} 天` : `已过 ${-item.days} 天`) }}
							</text>
							<text class="countdown-del" @click="fnRemoveCountdown(index)">删除</text>
						</view>
					</view>
					<view v-else class="countdown-empty">还没有倒数日，点右上角「＋ 新增」添加一个吧</view>
				</view> -->
			</view>

			<!-- 每日任务入口 -->
			<view class="daily-task-block ml-24 mr-24 mt-16 mb-16 bg-white round-3" @click="fnToTaskCenter">
				<view class="daily-task-main">
					<text class="page-item_title text-weight-b">每日任务</text>
					<text class="daily-task-tip">签到 / 浏览 / 点赞 / 投票，完成任务领积分</text>
				</view>
				<view class="daily-task-arrow">
					<text class="daily-task-btn">去完成</text>
					<text class="daily-task-arrow-icon">›</text>
				</view>
			</view>

			<!-- 最新文章 -->
			<view class="flex flex-between mt-24 mb-24 pl-24 pr-24">
				<view class="page-item_title text-weight-b">最新列表</view>
				<view class="show-more flex flex-center bg-white round-3" @click="fnToArticlesPage">
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
			<view v-if="feedPersonalized" class="feed-hint ml-24 mr-24 mb-16">
				<text class="feed-hint-text">{{ subscribedHintText }}</text>
				<text class="feed-hint-action" @click="fnToCategoryTab">去调整</text>
			</view>

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
				<tm-flotbutton v-if="articleList.length > 10" :width="90" color="light-blue" @click="fnToTopPage"
					size="s" icon="icon-angle-up"></tm-flotbutton>
			</block>
		</block>


		<!-- 弹窗 -->
		<NotifyDialog v-if="notify.show" :show="notify.show" :title="notify.data.title" :content="notify.data.content"
			:url="notify.data.url" @on-change="fnOnNotifyChange"></NotifyDialog>
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
	import { getTodayQuote } from '@/utils/dailyQuotes.js';
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
			NotifyDialog
		},
			data() {
				return {
					loading: 'loading',
					todayQuote: { text: '', author: '' },
					todayQuoteDate: '',
					countdownList: [],
					countdownShowForm: false,
					countdownForm: {
						name: '',
						date: ''
					},
					queryParams: {
						size: 5,
					page: 1,
					sort: ['spec.pinned,desc', 'spec.publishTime,desc']
				},
				result: {},
				isLoadMore: false,
				loadMoreText: '加载中...',
				bannerCurrent: 0,
				bannerList: [],
				noticeList: [],
				articleList: [],
				notify: {
					show: false,
					data: {}
				},
				wxUserInfo: null,
				/** 订阅的分类（站点分类的 metadata.name），由分类页设置 */
				subscription: { postCategories: [] },
				/** 主列表是否正在按订阅分类筛选 / 是否已回落站内默认列表 */
				feedPersonalized: false,
				feedFallback: false,
				/** 订阅分类的显示名（含中文），用于状态条 */
				feedCategories: [],
				/** 多路合并分页：每个分类当前取到第几页 */
				feedPages: {},
				/** 已同步过的订阅版本号：分类页改过订阅后会写这个值，切回首页时据此判断要不要重取 */
				subscriptionRev: ''
			};
		},
		computed: {
			/** 状态条里的订阅清单：用中文名，主题分类的 key 是英文看不懂 */
			subscribedLabel() {
				const labels = (this.feedCategories || []).map((item) => item.label);
				return labels.length ? labels.join('、') : '';
			},
			/** 取不到分类显示名（接口异常）时退化成只说个数，不显示「已订阅：。」这种空话 */
			subscribedHintText() {
				if (this.subscribedLabel) {
					return `已订阅：${this.subscribedLabel}。首页只显示这些分类的文章`;
				}
				const count = (this.subscription.postCategories || []).length;
				return `已订阅 ${count} 个分类，首页只显示这些分类的文章`;
			},
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
			bannerConfig() {
				return {
					enabled: true,
					showTitle: true,
					showIndicator: true,
					height: '400rpx',
					dotPosition: 'right',
					type: 'post',
					list: []
				}
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
			this.fnInitDaily();
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
					this.subscription = { postCategories };
					// 订阅了分类才影响主列表；一个都没订阅就是站内默认列表
					this.feedPersonalized = postCategories.length > 0;
					// 记下「已经按这一版订阅取过数据了」，避免每次切回首页都白取一次
					this.subscriptionRev = uni.getStorageSync('category.subscription.rev') || '';
					this.fnLoadFeedCategories();
				} catch (err) {
					// 静默：拿不到订阅分类就按站内默认列表展示
					this.subscription = { postCategories: [] };
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
				const categories = this.subscription.postCategories || [];
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

			/** 去分类页改订阅（分类页是 tabbar 页，只能用 switchTab） */
			fnToCategoryTab() {
				uni.switchTab({ url: '/pages/tabbar/category/category' });
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

			/**
			 * 取订阅分类的中文名，用于状态条。
			 *
			 * <p>自动创建的主题分类其 name 是英文 key（{@code ai-ml}），
			 * 直接显示给用户看不懂，所以要拿站点分类的显示名来映射。</p>
			 */
			async fnLoadFeedCategories() {
				const followed = this.subscription.postCategories || [];
				if (!checkHasWxLogin() || !followed.length) {
					this.feedCategories = [];
					return;
				}
				try {
					const res = await this.$httpApi.v2.getCategoryList({
						size: 100,
						page: 1,
						fieldSelector: ['spec.hideFromList=false']
					});
					const items = Array.isArray(res?.items) ? res.items : [];
					const labels = {};
					items.forEach((item) => {
						const name = item.metadata?.name;
						if (name) {
							labels[name] = item.spec?.displayName || name;
						}
					});
					this.feedCategories = followed.map((name) => ({
						name,
						label: labels[name] || name
					}));
				} catch (err) {
					// 拿不到显示名就退回英文 key：状态条仍然可用，只是不够好看
					this.feedCategories = followed.map((name) => ({ name, label: name }));
				}
			},

			// 获取轮播图
			fnGetBanner() {
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
					});
					return;
				}


				if (!this.bannerConfig.enabled) return;

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
					})
					return;
				}

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
						this.bannerList = res.data.items.map((item, index) => {
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
								url: ""
							};
						});
					},
					fail: (err) => {}
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
				if (this.feedPersonalized && this.subscription.postCategories.length > 0) {
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
			fnInitDaily() {
				const quote = getTodayQuote();
				this.todayQuote = quote || { text: '', author: '' };
				const now = new Date();
				this.todayQuoteDate = `${now.getMonth() + 1}月${now.getDate()}日`;
				this.fnInitNotify();
				this.fnLoadCountdowns();
			},

			fnToTaskCenter() {
				uni.navigateTo({ url: '/pagesA/task-center/task-center' });
			},

			fnLoadCountdowns() {
				const raw = uni.getStorageSync('toolbox.countdowns');
				const list = Array.isArray(raw) ? raw : [];
				this.countdownList = list.map((item) => ({
					...item,
					days: this.fnCalcDays(item.date)
				}));
			},
			fnSaveCountdowns() {
				uni.setStorageSync('toolbox.countdowns', this.countdownList.map((item) => ({
					name: item.name,
					date: item.date
				})));
			},
			fnCalcDays(dateStr) {
				if (!dateStr) return 0;
				const target = new Date(dateStr);
				if (isNaN(target.getTime())) return 0;
				target.setHours(0, 0, 0, 0);
				const now = new Date();
				now.setHours(0, 0, 0, 0);
				return Math.round((target.getTime() - now.getTime()) / 86400000);
			},
			fnOnCountdownDateChange(e) {
				this.countdownForm.date = e.detail.value;
			},
			fnToggleCountdownForm() {
				this.countdownShowForm = !this.countdownShowForm;
			},
			fnAddCountdown() {
				const name = (this.countdownForm.name || '').trim();
				if (!name || !this.countdownForm.date) {
					uni.showToast({ title: '请填写名称和日期', icon: 'none' });
					return;
				}
				this.countdownList.unshift({
					name,
					date: this.countdownForm.date,
					days: this.fnCalcDays(this.countdownForm.date)
				});
				this.fnSaveCountdowns();
				this.countdownForm = { name: '', date: '' };
				this.countdownShowForm = false;
				uni.showToast({ title: '已添加', icon: 'success' });
			},
			fnRemoveCountdown(index) {
				this.countdownList.splice(index, 1);
				this.fnSaveCountdowns();
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

			fnCopyTodayQuote() {
				const text = this.todayQuote.text
					? `每日一句：${this.todayQuote.text}${this.todayQuote.author ? ` —— ${this.todayQuote.author}` : ''}`
					: '';
				if (!text) return;
				uni.setClipboardData({
					data: text,
					success: () => uni.showToast({ title: '已复制', icon: 'none' })
				});
			},

			fnShareDailyQuote() {
				this.fnCopyTodayQuote();
				uni.showToast({ title: '已复制，快去分享给朋友吧', icon: 'none' });
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
			fnToToolbox() {
				uni.navigateTo({
					url: '/pagesA/toolbox/toolbox',
					animationType: 'slide-in-right'
				});
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
			background: #f0f2f5;
			border: 1rpx solid #e4e7ed;
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
		background-color: #f5f5f5;
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
		box-shadow: 0rpx 0rpx 24rpx rgba(0, 0, 0, 0.03);
	}

	.banner {
		overflow: hidden;
	}

	.quick-nav {
		background-color: #fff;
		box-sizing: border-box;

		// box-shadow: 0rpx 2rpx 24rpx rgba(0, 0, 0, 0.03);
		.name {
			color: var(--main-text-color);
		}
	}

	.page-item {
		&_title {
			position: relative;
			padding-left: 24rpx;
			font-size: 32rpx;
			z-index: 1;
			color: var(--main-text-color);

			&:before {
				content: '';
				position: absolute;
				left: 0rpx;
				top: 8rpx;
				width: 8rpx;
				height: 30rpx;
				background-color: rgba(33, 150, 243, 1);
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
		border-radius: 12rpx;
		background: #eef1fb;
	}

	.feed-hint-text {
		flex: 1;
		font-size: 21rpx;
		color: #5b6ee8;
	}

	.feed-hint-action {
		flex-shrink: 0;
		margin-left: 16rpx;
		font-size: 21rpx;
		color: #667eea;
	}

	.daily-block {
		overflow: hidden;
	}

	.daily-task-block {
		display: flex;
		align-items: center;
		padding: 20rpx 24rpx;
		background-color: #fff;
	}

	.daily-task-main {
		flex: 1;
		min-width: 0;
	}

	.daily-task-tip {
		display: block;
		margin-top: 6rpx;
		padding-left: 24rpx;
		font-size: 22rpx;
		color: #909399;
	}

	.daily-task-arrow {
		display: flex;
		align-items: center;
		gap: 4rpx;
		margin-left: 16rpx;
	}

	.daily-task-btn {
		font-size: 22rpx;
		padding: 8rpx 18rpx;
		border-radius: 999rpx;
		background: #f0f8ff;
		color: #03a9f4;
	}

	.daily-task-arrow-icon {
		font-size: 30rpx;
		color: #03a9f4;
		opacity: 0.6;
	}

	.daily-countdown {
		padding: 20rpx 24rpx;
	}

	.countdown-head {
		align-items: center;
	}

	.countdown-add-btn {
		padding: 8rpx 20rpx;
		border-radius: 999rpx;
		background: #f0f8ff;
		font-size: 24rpx;
		color: #03a9f4;

		&.countdown-add-btn-solid {
			background: #03a9f4;
			color: #fff;
		}
	}

	.countdown-form {
		display: flex;
		align-items: center;
		gap: 12rpx;
		margin-top: 16rpx;
	}

	.countdown-input {
		flex: 1;
		min-width: 0;
		height: 68rpx;
		line-height: 68rpx;
		padding: 0 16rpx;
		background: #f5f6fa;
		border-radius: 10rpx;
		font-size: 24rpx;
		color: #303133;
	}

	.countdown-date {
		text-align: center;
	}

	.countdown-list {
		margin-top: 8rpx;
	}

	.countdown-item {
		align-items: center;
		padding: 14rpx 0;
		border-bottom: 1rpx solid #f5f6fa;
	}

	.countdown-main {
		flex: 1;
		min-width: 0;
	}

	.countdown-name {
		display: block;
		font-size: 26rpx;
		color: #303133;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.countdown-date-text {
		display: block;
		margin-top: 2rpx;
		font-size: 20rpx;
		color: #c0c4cc;
	}

	.countdown-days {
		flex-shrink: 0;
		margin: 0 16rpx;
		font-size: 26rpx;
		font-weight: 600;
		color: #ff9800;

		&.is-today {
			color: #e53935;
		}
	}

	.countdown-del {
		flex-shrink: 0;
		font-size: 22rpx;
		color: #909399;
	}

	.countdown-empty {
		padding: 16rpx 0 4rpx;
		font-size: 22rpx;
		color: #c0c4cc;
	}

	.daily-quote {
		padding: 20rpx 24rpx;
		border-bottom: 1rpx solid #f0f0f0;
	}

	.daily-quote-head {
		align-items: center;
	}

	.daily-quote-date {
		font-size: 22rpx;
		color: #909399;
	}

	.daily-quote-head-right {
		display: flex;
		align-items: center;
		gap: 16rpx;
	}

	.daily-toolbox-link {
		padding: 4rpx 16rpx;
		border-radius: 999rpx;
		background: #f0f8ff;
		font-size: 22rpx;
		color: #03a9f4;
	}

	.daily-quote-text {
		display: block;
		margin-top: 12rpx;
		font-size: 28rpx;
		line-height: 1.6;
		color: #303133;
	}

	.daily-quote-foot {
		align-items: center;
		margin-top: 12rpx;
	}

	.daily-quote-author {
		font-size: 22rpx;
		color: #909399;
	}

	.daily-quote-actions {
		display: flex;
		align-items: center;
		gap: 16rpx;
	}

	.daily-quote-actions text {
		padding: 4rpx 16rpx;
		border-radius: 999rpx;
		background: #f0f8ff;
		font-size: 22rpx;
		color: #03a9f4;
	}

	.nav-box {		background-color: #ffff;
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