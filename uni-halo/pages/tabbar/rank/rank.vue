<template>
	<view :class="skinClass" class="app-page">
		<!-- 一级切换：文章榜 / 用户榜 / 动态 -->
		<view v-if="boardTabs.length > 1" class="board-tabs">
			<view
				v-for="tab in boardTabs"
				:key="tab.value"
				class="board-tab"
				:class="{ 'is-active': activeBoard === tab.value }"
				@click="fnOnBoardChange(tab.value)"
			>
				<text>{{ tab.label }}</text>
			</view>
		</view>

		<!-- 站长关闭榜单功能 -->
		<view v-if="!calcRankEnabled" class="content-empty flex flex-center">
			<tm-empty icon="icon-shiliangzhinengduixiang-" label="榜单功能未开启"></tm-empty>
		</view>

		<!-- 文章榜 -->
		<block v-else-if="activeBoard === 'article'">
			<view v-if="loading === 'loading'" class="loading-wrap">
				<tm-skeleton model="listAvatr"></tm-skeleton>
				<tm-skeleton model="listAvatr"></tm-skeleton>
				<tm-skeleton model="listAvatr"></tm-skeleton>
			</view>
			<view v-else-if="loading === 'error'" class="content-empty flex flex-center flex-col">
				<tm-empty icon="icon-wind-cry" :label="rankErrorText"></tm-empty>
				<tm-button class="mt-24" plan theme="grey" :fllowTheme="false" :shadow="0" @click="fnGetRank()">
					重新加载
				</tm-button>
			</view>
			<view v-else-if="sortedRankList.length === 0" class="content-empty flex flex-center">
				<tm-empty icon="icon-shiliangzhinengduixiang-" :label="emptyText"></tm-empty>
			</view>
			<block v-else>
				<view class="rank-head flex flex-between">
					<text class="rank-head-tip">TOP {{ sortedRankList.length }}</text>
					<view class="sort-switch flex">
						<view
							v-for="sort in articleSorts"
							:key="sort.value"
							class="sort-item"
							:class="{ 'is-active': articleSort === sort.value }"
							@click="articleSort = sort.value"
						>
							<text>{{ sort.label }}</text>
						</view>
					</view>
				</view>
				<view class="rank-list">
					<view
						v-for="(item, index) in sortedRankList"
						:key="item.name"
						class="ani-fade-up"
						:style="'animation-delay:' + (index * 50 + 50) + 'ms'"
					>
						<view class="rank-item flex" :class="{ 'is-first': index === 0 }" @click="fnToArticle(item)">
							<text class="rank-no" :class="{ 'is-top': index < 3 }">{{ index + 1 }}</text>
							<image
								v-if="fnResolveCover(item)"
								class="rank-cover"
								:src="fnResolveCover(item)"
								mode="aspectFill"
							/>
							<view v-else class="rank-cover rank-cover--empty flex flex-center">
								<text class="iconfont icon-picture"></text>
							</view>
							<text class="rank-title">{{ item.title || item.name }}</text>
							<text class="rank-value">{{ fnMetricText(item) }}</text>
						</view>
					</view>
				</view>
			</block>
		</block>

		<!-- 用户榜 -->
		<block v-else-if="activeBoard === 'user'">
			<view class="sort-switch sort-switch--board flex">
				<view
					v-for="board in userBoards"
					:key="board.value"
					class="sort-item"
					:class="{ 'is-active': userBoardType === board.value }"
					@click="fnOnUserBoardChange(board.value)"
				>
					<text>{{ board.label }}</text>
				</view>
			</view>

			<view v-if="userLoading === 'loading'" class="loading-wrap">
				<tm-skeleton model="listAvatr"></tm-skeleton>
				<tm-skeleton model="listAvatr"></tm-skeleton>
				<tm-skeleton model="listAvatr"></tm-skeleton>
			</view>
			<view v-else-if="userLoading === 'error'" class="content-empty flex flex-center flex-col">
				<tm-empty icon="icon-wind-cry" :label="userRankErrorText"></tm-empty>
				<tm-button class="mt-24" plan theme="grey" :fllowTheme="false" :shadow="0"
					@click="fnGetUserRank(userBoardType, { force: true })">
					重新加载
				</tm-button>
			</view>
			<template v-else-if="userRankReady">
				<view class="rank-list">
					<view
						v-for="(item, index) in userRank.items"
						:key="item.rank"
						class="ani-fade-up"
						:style="'animation-delay:' + (index * 50 + 50) + 'ms'"
					>
						<view class="rank-item flex" :class="{ 'is-first': index === 0 }">
							<text class="rank-no" :class="{ 'is-top': index < 3 }">{{ item.rank }}</text>
							<image class="user-avatar" :class="fnAvatarFrameClass(item)"
								:src="fnItemAvatar(item)" mode="aspectFill" />
							<view class="user-main">
								<text class="user-name">{{ fnNickName(item) }}</text>
								<text class="user-level">Lv.{{ item.level }} · {{ item.levelTitle }}</text>
							</view>
							<text v-if="calcShowScore" class="rank-value">{{ fnUserValueText(item) }}</text>
						</view>
					</view>
				</view>
				<view v-if="userRankThinTip" class="rank-thin-tip flex flex-center">
					<text>{{ userRankThinTip }}</text>
				</view>
				<view class="my-rank flex flex-between" @click="fnOnMyRankClick">
					<text class="my-rank-text">{{ myRankText }}</text>
					<text class="my-rank-arrow">›</text>
				</view>
			</template>
			<view v-else class="content-empty flex flex-center">
				<tm-empty icon="icon-user-group" :label="userRankEmptyText"></tm-empty>
			</view>

			<view class="rule-card">
				<view class="rule-head flex flex-between">
					<text class="page-item_title text-weight-b">怎么上榜</text>
					<text class="rule-tip">按累计积分排名</text>
				</view>
				<view
					v-for="(rule, index) in rankRules"
					:key="rule.label"
					class="rule-item flex flex-between"
					:class="{ 'is-first': index === 0 }"
				>
					<text class="rule-label">{{ rule.label }}</text>
					<text class="rule-value">{{ rule.value }}</text>
				</view>
				<view v-if="weeklyRewardTip" class="rule-reward flex">
					<text class="rule-reward-icon">🏆</text>
					<text class="rule-reward-text">{{ weeklyRewardTip }}</text>
				</view>
				<view class="rule-foot flex flex-center" @click="fnToTaskCenter">
					<text>去赚积分</text>
					<text class="rule-arrow">›</text>
				</view>
			</view>
		</block>

		<!-- 互动动态 -->
		<block v-else>
			<view v-if="wallLoading === 'loading'" class="loading-wrap">
				<tm-skeleton model="listAvatr"></tm-skeleton>
				<tm-skeleton model="listAvatr"></tm-skeleton>
				<tm-skeleton model="listAvatr"></tm-skeleton>
			</view>
			<view v-else-if="wallLoading === 'error'" class="content-empty flex flex-center flex-col">
				<tm-empty icon="icon-wind-cry" :label="wallErrorText"></tm-empty>
				<tm-button class="mt-24" plan theme="grey" :fllowTheme="false" :shadow="0"
					@click="fnGetWall(true)">
					重新加载
				</tm-button>
			</view>
			<view v-else-if="wallList.length === 0" class="content-empty flex flex-center">
				<tm-empty icon="icon-commentlines-fill" :label="wallEmptyText"></tm-empty>
			</view>
			<view v-else class="rank-list">
				<view v-for="(item, index) in wallList" :key="index" class="ani-fade-up"
					:style="'animation-delay:' + (index * 50 + 50) + 'ms'">
					<view class="rank-item wall-item flex" :class="{ 'is-first': index === 0 }"
						@click="fnToActivityTarget(item)">
						<image class="user-avatar" :class="fnAvatarFrameClass(item)" :src="fnItemAvatar(item)"
							mode="aspectFill" />
						<view class="wall-main">
							<view class="wall-line">
								<text class="wall-name">{{ fnNickName(item) }}</text>
								<text class="wall-action">{{ item.actionText }}</text>
							</view>
							<text class="wall-title">《{{ item.title }}》</text>
							<text v-if="item.excerpt" class="wall-excerpt">{{ item.excerpt }}</text>
						</view>
						<text class="wall-time">{{ fnRelativeTime(item.time) }}</text>
					</view>
				</view>
			</view>
		</block>

		<!-- 仅在用户主动点「我的排名」时唤起，榜单本身不需要登录 -->
		<wx-login-dialog
			:show.sync="loginDialogShow"
			title="登录后查看我的排名"
			desc="榜单公开可看，登录后这里会显示你的名次"
			@success="fnOnLoginSuccess"
			@close="fnOnLoginClose"
		></wx-login-dialog>
		<!-- 自绘底部导航：后台「底部导航」开关关闭时不渲染，仍用微信原生底栏 -->
		<uh-tabbar current="rank"></uh-tabbar>
	</view>
</template>

<script>
	import tmSkeleton from '@/tm-vuetify/components/tm-skeleton/tm-skeleton.vue';
	import uhTabbar from '@/components/uh-tabbar/uh-tabbar.vue';
	import tmEmpty from '@/tm-vuetify/components/tm-empty/tm-empty.vue';
	import tmButton from '@/tm-vuetify/components/tm-button/tm-button.vue';

	import wxLoginDialog from '@/components/wx-login-dialog/wx-login-dialog.vue';
	import { checkHasWxLogin } from '@/utils/auth.js';
	import { avatarFrameClass, nickFramePrefix } from '@/utils/outfitSkin.js';
	import { resolvePointsEnabled } from '@/utils/points.js';

	/** 后端 /rank/articles 与 /rank/users 的 limit 上限为 50（RankServiceImpl 内部裁剪） */
	const RANK_LIMIT = 20;
	/** 互动动态条数（后端上限 30，站长可在后台调小） */
	const WALL_LIMIT = 10;
	/** 与后端榜单缓存同口径：超过该时长未刷新，回到页面时静默重取 */
	const RANK_CACHE_TTL_MS = 60 * 1000;
	/** 参与人数少于该值时榜单降级为引导，避免出现只有一两个人的空榜 */
	const MIN_RANK_PARTICIPANTS = 5;

	export default {
		components: {
			tmSkeleton,
			tmEmpty,
			tmButton,
			wxLoginDialog,
			uhTabbar
		},
		data() {
			return {
				activeBoard: 'article',
				articleSort: 'visit',
				loading: 'loading',
				rankList: [],
				rankErrorText: '榜单加载失败，请稍后重试',
				userBoardType: 'points',
				userLoading: 'loading',
				userRankErrorText: '榜单加载失败，请稍后重试',
				userRank: {
					items: [],
					total: 0,
					me: null
				},
				/** 按榜单类型缓存，来回切换不重复请求（下拉刷新会强制重取） */
				userRankCache: {},
				loginDialogShow: false,
				wallList: [],
				wallLoading: 'loading',
				wallErrorText: '动态加载失败，请稍后重试',
				/** 各板块最近一次请求时间，用于回到页面时判断是否需要静默刷新 */
				rankFetchedAt: 0,
				userRankFetchedAt: 0,
				wallFetchedAt: 0
			};
		},
		computed: {
			haloConfigs() {
				return this.$tm.vx.getters().getConfigs;
			},
			calcAuditModeEnabled() {
				return !!this.haloConfigs?.auditConfig?.auditModeEnabled;
			},
			calcPointsEnabled() {
				return resolvePointsEnabled(this.haloConfigs?.pointsConfig);
			},
			rankConfig() {
				return this.haloConfigs?.rankConfig || {};
			},
			/** 站长可整体关闭榜单（关闭后页面只显示提示，也不下发任何榜单数据） */
			calcRankEnabled() {
				return this.rankConfig.enabled !== false;
			},
			calcShowScore() {
				return this.rankConfig.showScore !== false;
			},
			calcInviteBoardEnabled() {
				return this.rankConfig.enableInviteBoard !== false;
			},
			calcWallEnabled() {
				return this.rankConfig.wallEnabled !== false;
			},
			boardTabs() {
				if (!this.calcRankEnabled) {
					return [];
				}
				const tabs = [{
					label: '文章榜',
					value: 'article'
				}];
				// 用户榜依赖积分体系，积分未开启时整块不展示
				if (this.calcPointsEnabled) {
					tabs.push({
						label: '用户榜',
						value: 'user'
					});
				}
				if (this.calcWallEnabled) {
					tabs.push({
						label: '动态',
						value: 'wall'
					});
				}
				return tabs;
			},
			articleSorts() {
				return [{
					label: '最多阅读',
					value: 'visit'
				}, {
					label: '最多点赞',
					value: 'upvote'
				}];
			},
			sortedRankList() {
				const key = this.articleSort === 'upvote' ? 'upvote' : 'visit';
				return [...this.rankList].sort((a, b) => Number(b?.[key] ?? 0) - Number(a?.[key] ?? 0));
			},
			emptyText() {
				return this.calcAuditModeEnabled ? '暂无数据' : '榜单还没有数据，快去留下足迹吧';
			},
			userBoards() {
				const boards = [{
					label: '总积分',
					value: 'points'
				}, {
					label: '近 7 日',
					value: 'active'
				}];
				if (this.calcInviteBoardEnabled && this.haloConfigs?.inviteConfig?.enabled !== false) {
					boards.push({
						label: '邀请榜',
						value: 'invite'
					});
				}
				return boards;
			},
			userValueUnit() {
				return this.userBoardType === 'invite' ? '人' : '积分';
			},
			// 有人上榜就展示榜单：人数少只补一句提示，绝不隐藏真实数据
			userRankReady() {
				return this.userRank.items.length > 0;
			},
			/** 上榜人数偏少时的提示语（0 人时不提示，走空态文案） */
			userRankThinTip() {
				if (this.calcAuditModeEnabled
					|| this.userRank.total <= 0
					|| this.userRank.total >= MIN_RANK_PARTICIPANTS) {
					return '';
				}
				return `目前只有 ${this.userRank.total} 人上榜，做任务就能抢占前排`;
			},
			userRankEmptyText() {
				if (this.calcAuditModeEnabled) {
					return '暂无数据';
				}
				return this.userRank.total > 0
					? '上榜的人还不多，做任务就能抢占前排'
					: '榜单还空着，第一个上榜的就是你';
			},
			myRankText() {
				const me = this.userRank.me;
				if (!me) {
					return '登录后这里会显示你的排名';
				}
				if (!me.joined) {
					return '你还没上榜，去做任务赚积分';
				}
				if (!this.calcShowScore) {
					return `我的排名：第 ${me.rank} 名`;
				}
				return `我的排名：第 ${me.rank} 名（${this.fnFormatCount(me.value)} ${this.userValueUnit}）`;
			},
			wallEmptyText() {
				return this.calcAuditModeEnabled ? '暂无数据' : '还没有动态，快去留下第一条足迹吧';
			},
			/** 周榜奖励说明（站长开启后展示，口径与后端逐名递减一致） */
			weeklyRewardTip() {
				const config = this.rankConfig || {};
				if (config.weeklyRewardEnabled !== true) {
					return '';
				}
				const topN = Number(config.weeklyRewardTopN) || 0;
				const base = Number(config.weeklyRewardPoints) || 0;
				const step = Number(config.weeklyRewardStep) || 0;
				const rewards = [];
				for (let rank = 1; rank <= topN; rank += 1) {
					const points = base - (rank - 1) * step;
					if (points <= 0) break;
					rewards.push(points);
				}
				if (!rewards.length) {
					return '';
				}
				return `每周一结算上周活跃榜，前 ${rewards.length} 名分别得 ${rewards.join(' / ')} 积分`;
			},
			rankRules() {
				const rules = this.haloConfigs?.pointsConfig?.rules || {};
				const pointsOf = (key, fallback) => {
					const value = Number(rules?.[key]?.points);
					return Number.isFinite(value) ? value : fallback;
				};
				const list = [{
					label: '浏览文章',
					value: `+${pointsOf('browse', 1)} / 次`
				}, {
					label: '每日签到',
					value: `+${pointsOf('signIn', 10)} / 天`
				}, {
					label: '发表评论',
					value: `+${pointsOf('comment', 5)} / 次`
				}, {
					label: '点赞文章',
					value: `+${pointsOf('upvote', 2)} / 次`
				}];
				if (this.haloConfigs?.inviteConfig?.enabled !== false) {
					const reward = Number(this.haloConfigs?.inviteConfig?.rewardPoints);
					list.push({
						label: '邀请好友',
						value: `+${Number.isFinite(reward) && reward > 0 ? reward : 30} / 人`
					});
				}
				return list;
			}
		},
		onLoad() {
			this.fnGetRank();
		},
		onShow() {
			this.fnRefreshStaleBoard();
		},
		onPullDownRefresh() {
			if (!this.calcRankEnabled) {
				uni.stopPullDownRefresh();
				return;
			}
			if (this.activeBoard === 'user') {
				this.fnGetUserRank(this.userBoardType, { fromPullDown: true, force: true });
				return;
			}
			if (this.activeBoard === 'wall') {
				this.fnGetWall(true);
				return;
			}
			this.fnGetRank(true);
		},
		methods: {
			/**
			 * 回到页面时若当前榜单数据已过期则静默重取。
			 *
			 * <p>否则用户赚完积分切回榜单会看到旧名次（前端缓存 + 后端 60 秒缓存叠加），
			 * 看起来就像"积分没上榜"。刷新失败时保留旧数据并提示，不打断浏览。</p>
			 */
			fnRefreshStaleBoard() {
				if (this.calcAuditModeEnabled || !this.calcRankEnabled) {
					return;
				}
				const now = Date.now();
				if (this.activeBoard === 'article') {
					if (now - this.rankFetchedAt > RANK_CACHE_TTL_MS) {
						// fromPullDown=true 时保留已有列表、不显示骨架屏
						this.fnGetRank(true);
					}
					return;
				}
				if (this.activeBoard === 'user') {
					if (now - this.userRankFetchedAt > RANK_CACHE_TTL_MS) {
						this.fnGetUserRank(this.userBoardType);
					}
					return;
				}
				if (this.activeBoard === 'wall' && now - this.wallFetchedAt > RANK_CACHE_TTL_MS) {
					this.fnGetWall(true);
				}
			},
			/** 缓存是否仍在有效期内（与后端榜单缓存同口径） */
			isRankCacheFresh(cached) {
				return !!cached && (Date.now() - (cached.fetchedAt || 0)) < RANK_CACHE_TTL_MS;
			},
			fnOnBoardChange(value) {
				if (value === this.activeBoard) return;
				this.activeBoard = value;
				// 用户榜与动态都按需加载：只在真正切过去时才请求
				if (value === 'user' && this.userLoading !== 'success') {
					this.fnGetUserRank(this.userBoardType);
				}
				if (value === 'wall' && !this.isRankCacheFresh({
						fetchedAt: this.wallFetchedAt
					})) {
					this.fnGetWall();
				}
			},
			/**
			 * 拉取互动动态。动态由站长开关控制，审核模式下不发请求。
			 */
			async fnGetWall(fromPullDown = false) {
				if (this.calcAuditModeEnabled) {
					this.wallList = [];
					this.wallLoading = 'success';
					this.wallFetchedAt = Date.now();
					if (fromPullDown) uni.stopPullDownRefresh();
					return;
				}
				if (!fromPullDown) {
					this.wallLoading = 'loading';
				}
				this.wallFetchedAt = Date.now();
				try {
					const res = await this.$httpApi.v2.getRankActivities(WALL_LIMIT);
					const body = res?.data || res || {};
					this.wallList = Array.isArray(body.items) ? body.items : [];
					this.wallLoading = 'success';
				} catch (err) {
					if (fromPullDown && this.wallList.length > 0) {
						this.wallLoading = 'success';
						uni.showToast({
							icon: 'none',
							title: '刷新失败，请稍后重试'
						});
					} else {
						this.wallList = [];
						this.wallLoading = 'error';
						this.wallErrorText = err?.statusCode === 404
							? '动态功能需要更新插件版本'
							: '动态加载失败，请稍后重试';
					}
				} finally {
					if (fromPullDown) uni.stopPullDownRefresh();
				}
			},
			fnOnUserBoardChange(type) {
				if (type === this.userBoardType) return;
				this.userBoardType = type;
				this.fnGetUserRank(type);
			},
			/**
			 * 拉取用户榜。榜单公开可读，未登录也能看；
			 * 带登录态时后端会额外返回 me（我的排名）。审核模式与文章榜一致，不发请求。
			 */
			async fnGetUserRank(type = 'points', { fromPullDown = false, force = false } = {}) {
				if (this.calcAuditModeEnabled) {
					this.userRank = {
						items: [],
						total: 0,
						me: null
					};
					this.userLoading = 'success';
					if (fromPullDown) uni.stopPullDownRefresh();
					return;
				}
				const cached = this.userRankCache[type];
				if (!force && this.isRankCacheFresh(cached)) {
					this.userRank = cached;
					this.userLoading = 'success';
					if (fromPullDown) uni.stopPullDownRefresh();
					return;
				}
				if (!fromPullDown) {
					this.userLoading = 'loading';
				}
				this.userRankFetchedAt = Date.now();
				try {
					const res = await this.$httpApi.v2.getUserRank({
						type,
						range: 'all',
						limit: RANK_LIMIT
					});
					const body = res?.data || res || {};
					const rankData = {
						items: Array.isArray(body.items) ? body.items : [],
						total: Number(body.total) || 0,
						me: body.me || null,
						fetchedAt: Date.now()
					};
					this.userRank = rankData;
					this.userRankCache = {
						...this.userRankCache,
						[type]: rankData
					};
					this.userLoading = 'success';
				} catch (err) {
					if (fromPullDown && this.userRank.items.length > 0) {
						this.userLoading = 'success';
						uni.showToast({
							icon: 'none',
							title: '刷新失败，请稍后重试'
						});
					} else {
						this.userRank = {
							items: [],
							total: 0,
							me: null
						};
						this.userLoading = 'error';
						this.userRankErrorText = err?.statusCode === 404
							? '榜单功能需要更新插件版本'
							: '榜单加载失败，请稍后重试';
					}
				} finally {
					if (fromPullDown) uni.stopPullDownRefresh();
				}
			},
			fnItemAvatar(item) {
				return this.$utils.checkAvatarUrl(item?.avatar);
			},
			fnAvatarFrameClass(item) {
				return item?.avatarFrame ? avatarFrameClass(item.avatarFrame) : '';
			},
			fnNickName(item) {
				const prefix = item?.nickFrame ? nickFramePrefix(item.nickFrame) : '';
				return prefix ? `${prefix} ${item.nickname}` : item?.nickname;
			},
			fnRelativeTime(time) {
				if (!time) return '';
				return this.$tm.dayjs(time).fromNow(true) + '前';
			},
			fnToActivityTarget(item) {
				if (!item?.targetName) return;
				const path = item.contentType === 'moment'
					? '/pagesA/moment-detail/moment-detail'
					: '/pagesA/article-detail/article-detail';
				uni.navigateTo({
					url: `${path}?name=${encodeURIComponent(item.targetName)}`,
					animationType: 'slide-in-right'
				});
			},
			fnUserValueText(item) {
				return `${this.fnFormatCount(item?.value)} ${this.userValueUnit}`;
			},
			fnOnMyRankClick() {
				if (!checkHasWxLogin()) {
					this.loginDialogShow = true;
					return;
				}
				if (this.userRank.me && !this.userRank.me.joined) {
					this.fnToTaskCenter();
					return;
				}
				if (this.userRank.me) {
					uni.showToast({
						icon: 'none',
						title: '保持活跃，名次还能往上冲'
					});
				}
			},
			fnOnLoginSuccess() {
				this.loginDialogShow = false;
				// 登录后重取，带上「我的排名」
				this.fnGetUserRank(this.userBoardType, { force: true });
			},
			fnOnLoginClose() {
				this.loginDialogShow = false;
			},
			/**
			 * 拉取文章榜。审核模式不请求接口，避免平台审核期出现榜单数据。
			 */
			async fnGetRank(fromPullDown = false) {
				// 站长关闭榜单 / 平台审核期都不请求接口
				if (this.calcAuditModeEnabled || !this.calcRankEnabled) {
					this.rankList = [];
					this.loading = 'success';
					if (fromPullDown) uni.stopPullDownRefresh();
					return;
				}
				if (!fromPullDown) {
					this.loading = 'loading';
				}
				// 记录「本次尝试」时间：onLoad 与 onShow 会先后触发，避免首次进入重复请求
				this.rankFetchedAt = Date.now();
				try {
					const res = await this.$httpApi.v2.getRankArticles(RANK_LIMIT);
					const body = res?.data || res || {};
					const items = Array.isArray(body.items) ? body.items : [];
					this.rankList = items.filter((item) => item && item.name);
					this.loading = 'success';
				} catch (err) {
					// 下拉刷新失败时保留原有列表，不清空已展示内容
					if (fromPullDown && this.rankList.length > 0) {
						this.loading = 'success';
						uni.showToast({
							icon: 'none',
							title: '刷新失败，请稍后重试'
						});
					} else {
						this.rankList = [];
						this.loading = 'error';
						this.rankErrorText = err?.statusCode === 404
							? '榜单功能需要更新插件版本'
							: '榜单加载失败，请稍后重试';
					}
				} finally {
					if (fromPullDown) uni.stopPullDownRefresh();
				}
			},
			fnToArticle(item) {
				if (!item?.name) return;
				uni.navigateTo({
					url: '/pagesA/article-detail/article-detail?name=' + encodeURIComponent(item.name),
					animationType: 'slide-in-right'
				});
			},
			fnToTaskCenter() {
				uni.navigateTo({
					url: '/pagesA/task-center/task-center'
				});
			},
			fnResolveCover(item) {
				return item?.cover ? this.$utils.checkThumbnailUrl(item.cover) : '';
			},
			fnMetricText(item) {
				const value = Number((this.articleSort === 'upvote' ? item?.upvote : item?.visit) ?? 0);
				return `${this.fnFormatCount(value)} ${this.articleSort === 'upvote' ? '赞' : '阅读'}`;
			},
			fnFormatCount(value) {
				const num = Number(value);
				if (!Number.isFinite(num)) return '0';
				return String(Math.trunc(num)).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
			}
		}
	};
</script>

<style lang="scss" scoped>
	@keyframes fadeUp {
		from { opacity: 0; transform: translateY(40rpx); }
		to { opacity: 1; transform: translateY(0); }
	}
	.ani-fade-up {
		animation: fadeUp 0.5s ease-out both;
	}

	.app-page {
		box-sizing: border-box;
		width: 100vw;
		min-height: 100vh;
		padding: 24rpx 0;
	}

	.board-tabs {
		display: flex;
		align-items: center;
		gap: 12rpx;
		padding: 4rpx 24rpx 20rpx;
	}

	.board-tab {
		padding: 10rpx 32rpx;
		border-radius: 999rpx;
		background: $uh-card;
		font-size: 26rpx;
		color: $uh-ink-3;

		&.is-active {
			background: $uh-accent;
			color: #fff;
			font-weight: 600;
		}
	}

	.loading-wrap {
		padding: 0 24rpx;
	}

	.content-empty {
		min-height: 40vh;
	}

	.rank-head {
		align-items: center;
		padding: 0 24rpx 16rpx;
	}

	.rank-head-tip {
		font-size: 24rpx;
		color: $uh-ink-3;
	}

	.sort-switch {
		align-items: center;
		gap: 8rpx;
	}

	.sort-item {
		padding: 6rpx 20rpx;
		border-radius: 999rpx;
		background: $uh-card;
		font-size: 22rpx;
		color: $uh-ink-3;

		&.is-active {
			background: #e1f5fe;
			color: $uh-accent;
			font-weight: 600;
		}
	}

	.rank-list {
		margin: 0 24rpx;
		border-radius: 12rpx;
		background: $uh-card;
		box-shadow: $uh-shadow-sm;
		overflow: hidden;
	}

	.rank-item {
		align-items: center;
		padding: 20rpx 24rpx;
		border-top: 1rpx solid #f5f6f8;

		&.is-first {
			border-top: none;
		}

		&:active {
			background: #fafbfc;
		}
	}

	.rank-no {
		flex-shrink: 0;
		width: 40rpx;
		font-size: 26rpx;
		font-weight: 700;
		color: $uh-ink-4;

		&.is-top {
			color: $uh-warning;
		}
	}

	.rank-cover {
		flex-shrink: 0;
		width: 88rpx;
		height: 88rpx;
		margin: 0 20rpx 0 12rpx;
		border-radius: 12rpx;
		background: #f5f7fa;
	}

	.rank-cover--empty {
		.iconfont {
			font-size: 34rpx;
			color: #c8cdd6;
		}
	}

	.rank-title {
		flex: 1;
		min-width: 0;
		font-size: 28rpx;
		color: $uh-ink-1;
		line-height: 1.4;
		overflow: hidden;
		text-overflow: ellipsis;
		display: -webkit-box;
		-webkit-line-clamp: 2;
		-webkit-box-orient: vertical;
	}

	.rank-value {
		flex-shrink: 0;
		margin-left: 20rpx;
		font-size: 22rpx;
		color: $uh-ink-3;
	}

	.sort-switch--board {
		padding: 0 24rpx 20rpx;
	}

	.user-avatar {
		flex-shrink: 0;
		box-sizing: border-box;
		width: 66rpx;
		height: 66rpx;
		margin: 0 20rpx 0 12rpx;
		border-radius: 50%;
		border: 4rpx solid transparent;
		background: #f5f7fa;

		// 头像框（装扮，与评论列表口径一致）
		&.frame-gold {
			border: 4rpx solid #f5c542;
			box-shadow: 0 0 16rpx rgba(245, 197, 66, 0.5);
		}

		&.frame-rainbow {
			border: 4rpx solid transparent;
			background: linear-gradient(#f0f0f5, #f0f0f5) padding-box,
				linear-gradient(135deg, #f79ea3, #9b8cff, #79d6f2) border-box;
			box-shadow: 0 0 16rpx rgba(155, 140, 255, 0.4);
		}

		&.frame-silver {
			border: 4rpx solid #c3cbd8;
			box-shadow: 0 0 16rpx rgba(195, 203, 216, 0.5);
		}
	}

	.wall-item {
		align-items: flex-start;
	}

	.wall-main {
		display: flex;
		flex-direction: column;
		flex: 1;
		min-width: 0;
	}

	.wall-line {
		display: flex;
		align-items: center;
	}

	.wall-name {
		max-width: 260rpx;
		font-size: 28rpx;
		font-weight: 600;
		color: $uh-ink-1;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.wall-action {
		margin-left: 10rpx;
		font-size: 26rpx;
		color: $uh-ink-3;
	}

	.wall-title {
		margin-top: 6rpx;
		font-size: 26rpx;
		color: $uh-accent;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.wall-excerpt {
		margin-top: 6rpx;
		font-size: 24rpx;
		color: $uh-ink-3;
		overflow: hidden;
		display: -webkit-box;
		-webkit-line-clamp: 2;
		-webkit-box-orient: vertical;
	}

	.wall-time {
		flex-shrink: 0;
		margin-left: 16rpx;
		font-size: 22rpx;
		color: $uh-ink-4;
	}

	.user-main {
		display: flex;
		flex-direction: column;
		flex: 1;
		min-width: 0;
	}

	.user-name {
		font-size: 28rpx;
		color: $uh-ink-1;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.user-level {
		margin-top: 6rpx;
		font-size: 22rpx;
		color: $uh-ink-3;
	}

	.rank-thin-tip {
		margin: 16rpx 24rpx 0;
		padding: 14rpx 20rpx;
		border-radius: 12rpx;
		background: #eef7fe;
		font-size: 24rpx;
		color: #0277bd;
	}

	.my-rank {
		align-items: center;
		margin: 16rpx 24rpx 0;
		padding: 20rpx 24rpx;
		border-radius: 12rpx;
		background: $uh-card;
		font-size: 26rpx;
		color: $uh-accent;
		box-shadow: $uh-shadow-sm;

		&:active {
			background: #fafbfc;
		}
	}

	.my-rank-text {
		flex: 1;
		min-width: 0;
	}

	.my-rank-arrow {
		margin-left: 12rpx;
		font-size: 28rpx;
		color: $uh-ink-4;
	}

	.rule-card {
		margin: 24rpx;
		padding: 24rpx;
		border-radius: 12rpx;
		background: $uh-card;
		box-shadow: $uh-shadow-sm;
	}

	.rule-head {
		align-items: center;
		margin-bottom: 8rpx;
	}

	.rule-tip {
		font-size: 22rpx;
		color: $uh-ink-4;
	}

	.rule-item {
		align-items: center;
		padding: 14rpx 0;
		border-top: 1rpx solid #f5f6f8;

		&.is-first {
			border-top: none;
		}
	}

	.rule-label {
		font-size: 26rpx;
		color: $uh-ink-2;
	}

	.rule-value {
		font-size: 26rpx;
		font-weight: 600;
		color: $uh-accent;
	}

	.rule-reward {
		align-items: flex-start;
		margin-top: 20rpx;
		padding: 18rpx 20rpx;
		border-radius: 12rpx;
		background: #fff8e6;
	}

	.rule-reward-icon {
		flex-shrink: 0;
		margin-right: 10rpx;
		font-size: 26rpx;
	}

	.rule-reward-text {
		flex: 1;
		min-width: 0;
		font-size: 24rpx;
		line-height: 1.5;
		color: #b26a00;
	}

	.rule-foot {
		margin-top: 24rpx;
		padding: 18rpx 0;
		border-radius: 999rpx;
		gap: 6rpx;
		background: #e1f5fe;
		font-size: 26rpx;
		color: $uh-accent;
		font-weight: 600;
	}

	.rule-arrow {
		font-size: 28rpx;
		line-height: 1;
	}
</style>
