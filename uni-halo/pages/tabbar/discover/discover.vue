<template>
	<view :class="skinClass" class="app-page">
		<!-- 搜索：分类会越来越多，靠搜索+排序来收，而不是靠一直往下翻 -->
		<view class="search-bar">
			<view class="search-box">
				<text class="search-icon">🔍</text>
				<input class="search-input" v-model="keyword" type="text" placeholder="搜索分类"
					placeholder-class="search-placeholder" confirm-type="search" />
				<text v-if="keyword" class="search-clear" @click="fnClearKeyword">✕</text>
			</view>
		</view>

		<!-- 首屏骨架；失败时给出重试入口（骨架屏一直转会被当成"卡住了"） -->
		<view v-if="loading === 'error'" class="content-empty flex flex-center">
			<tm-empty icon="icon-shiliangzhinengduixiang-" label="加载失败"></tm-empty>
			<view class="retry-btn" @click="fnLoadCategories">重新加载</view>
		</view>
		<view v-else-if="loading !== 'success'" class="loading-wrap">
			<tm-skeleton model="listAvatr"></tm-skeleton>
			<tm-skeleton model="listAvatr"></tm-skeleton>
			<tm-skeleton model="listAvatr"></tm-skeleton>
		</view>

		<view v-else class="app-page-content">
			<!--
				订阅概览条：把「订了几个」与「相关动作」放在一起。
				原来那颗「全部取消订阅」单独悬在热门榜上方——既看着像属于热门榜，
				又是个没有边界的裸文字按钮；状态与动作分开两处，用户还得自己找关联。
			-->
			<view v-if="subscribedCount > 0 || inheritedCount > 0 || calcPushEntryVisible"
				class="sub-bar">
				<view class="sub-bar-main">
					<text class="sub-bar-icon"
						:class="{ 'is-full': isAtLimit && pausedCount === 0, 'is-member': subIsMember }">
						{{ subIsMember ? '★' : (subscribedCount > 0 ? '✓' : '☆') }}
					</text>
					<text class="sub-bar-title" :class="{ 'is-full': isAtLimit }">
						{{ subBarTitle }}
					</text>
					<text v-if="pausedCount > 0" class="sub-bar-flag is-paused">
						{{ pausedCount }} 个已暂停
					</text>
					<text v-else-if="isAtLimit" class="sub-bar-flag">已达上限</text>
				</view>
				<view class="sub-bar-actions">
					<text v-if="calcPushEntryVisible" class="sub-bar-btn is-push"
						@click="fnOnPushRemind">🔔 {{ pushLabel }}</text>
					<text v-if="subCanUnlock && !subUnlimited" class="sub-bar-btn is-unlock"
						@click="fnOpenUnlockSheet">解锁更多</text>
					<text v-if="subscribedCount > 0" class="sub-bar-btn is-danger"
						@click="fnClearSubscribe">清空订阅</text>
				</view>
			</view>

			<view v-if="dataList.length === 0" class="content-empty flex flex-center">
				<tm-empty icon="icon-shiliangzhinengduixiang-" label="暂无分类"></tm-empty>
			</view>

			<block v-else>
				<!-- 热门榜：不需要运营干预的排序依据，回答「大多数人订了什么」 -->
				<view v-if="hotList.length" class="section">
					<view class="section-head">
						<text class="section-title">🔥 热门订阅</text>
						<text class="section-sub">按订阅人数</text>
					</view>
					<view class="rank-list">
						<view v-for="(item, index) in hotList" :key="item.metadata.name" class="rank-row">
							<text class="rank-no" :class="'rank-no-' + (index + 1)">{{ index + 1 }}</text>
							<view class="rank-main" @click="handleToCategory(item)">
								<view class="rank-icon-wrap">
									<image v-if="fnCatCover(item)" class="rank-icon" :src="fnCatCover(item)"
										mode="aspectFill" lazy-load></image>
									<view v-else class="rank-icon rank-icon-fallback"
										:style="{ backgroundImage: fnCatGlow(item.glowIndex) }">
										<text class="rank-icon-letter">{{ fnCatInitial(item) }}</text>
									</view>
								</view>
								<view class="rank-text">
									<text class="rank-name">{{ item.spec.displayName }}</text>
									<text class="rank-meta">{{ fnMetaText(item) }}</text>
								</view>
							</view>
							<view v-if="!calcAuditModeEnabled" class="cat-sub"
								:class="{ 'is-on': item.subscribed }"
								@click.stop="fnOnSubscribeChange(item)">
								<text class="cat-sub-text">{{ item.subscribed ? '✓ 已订阅' : '＋ 订阅' }}</text>
							</view>
						</view>
					</view>
				</view>

				<!-- 全部分类 -->
				<view class="section">
					<view class="section-head">
						<text class="section-title">{{ sectionTitle }}</text>
						<view class="filter-group">
							<text class="filter-chip" :class="{ 'is-on': scope === 'all' }"
								@click="fnSwitchScope('all')">全部</text>
							<text class="filter-chip" :class="{ 'is-on': scope === 'subscribed' }"
								@click="fnSwitchScope('subscribed')">已订阅</text>
						</view>
					</view>

					<view class="sort-bar">
						<text v-for="option in sortOptions" :key="option.key" class="sort-item"
							:class="{ 'is-active': sortKey === option.key }"
							@click="sortKey = option.key">{{ option.label }}</text>
					</view>

					<view v-if="visibleList.length === 0" class="content-empty flex flex-center">
						<tm-empty icon="icon-shiliangzhinengduixiang-" :label="fnEmptyLabel()"></tm-empty>
					</view>
					<!-- 三列磁贴：分类会越来越多，一屏能扫到十几个才谈得上"发现" -->
					<view v-else class="cat-grid">
						<view v-for="item in renderedList" :key="item.metadata.name" class="cat-tile"
							:class="{ 'is-on': item.subscribed, 'is-paused': fnIsPaused(item) }">
							<!-- 订阅按钮在磁贴右上角：点它只切订阅，点磁贴其余部分进分类详情 -->
							<view v-if="!calcAuditModeEnabled" class="tile-btn"
								:class="{ 'is-on': item.subscribed }"
								@click.stop="fnOnSubscribeChange(item)">
								<text class="tile-btn-text">{{ item.subscribed ? '✓' : '＋' }}</text>
							</view>
							<!-- 额度之外被暂停的分类：点一下去解锁；记录与顺序都在，解锁后自动恢复 -->
						<view v-if="fnIsPaused(item)" class="tile-paused-flag"
							@click.stop="fnOpenUnlockSheet">
							<text class="tile-paused-text">已暂停 · 去解锁</text>
						</view>
						<!-- 订阅墙开着时，未订阅的分类标个小锁：详情读全文需要订阅，先说清楚 -->
						<view v-if="calcWallEnabled && !item.subscribed" class="tile-wall-flag">
							<text class="tile-wall-text">🔒</text>
						</view>
						<view class="tile-main" @click="handleToCategory(item)">
								<image v-if="fnCatCover(item)" class="tile-icon" :src="fnCatCover(item)"
									mode="aspectFill" lazy-load></image>
								<view v-else class="tile-icon tile-icon-fallback"
									:style="{ backgroundImage: fnCatGlow(item.glowIndex) }">
									<text class="tile-icon-letter">{{ fnCatInitial(item) }}</text>
								</view>
								<text class="tile-name">{{ item.spec.displayName }}</text>
								<text class="tile-meta">{{ fnMetaText(item) }}</text>
							</view>
						</view>
					</view>
					<view class="load-text">{{ fnLoadText() }}</view>
				</view>
			</block>
		</view>

		<!--
			解锁额度：居中弹窗。
			原来是底部 actionSheet——自绘底栏是悬浮胶囊、层级更高，弹层下半截会被它压住，
			两个选项的说明也被宽度截断；改成居中卡片后说明写得全，并把「还差多少积分」
			摆到点按之前（与 AI 定制内容页的兑换交互同一套）。
		-->
		<view v-if="unlockSheetShow" class="unlock-mask" @click="unlockSheetShow = false">
			<view class="unlock-dialog" @click.stop="">
				<view class="unlock-close" @click="unlockSheetShow = false">
					<tm-icons name="icon-times" :size="28" color="#9a9a94"></tm-icons>
				</view>

				<text class="unlock-title">解锁更多订阅额度</text>
				<text class="unlock-status">{{ unlockStatusText }}</text>

				<!-- 积分解锁：价格写在右侧徽标，余额与差额写在副标题，点按前就知道够不够 -->
				<view v-if="subCanUnlock" class="unlock-option" hover-class="unlock-option--hover"
					@click="fnUnlockWithPoints">
					<view class="unlock-option-icon unlock-option-icon--points">
						<tm-icons name="icon-gem" :size="34" color="indigo"></tm-icons>
					</view>
					<view class="unlock-option-body">
						<text class="unlock-option-title">用积分解锁一份额度</text>
						<text class="unlock-option-desc" :class="{ 'is-short': unlockPointsShort > 0 }">
							{{ unlockPointsDesc }}
						</text>
					</view>
					<view class="unlock-price">
						<text class="unlock-price-num">{{ unlockPrice }}</text>
						<text class="unlock-price-unit">积分</text>
					</view>
				</view>

				<!-- 会员卡：现金通道。商品名由站长配置（可能叫月卡），跳过去后直购页会指认出这张卡 -->
				<view class="unlock-option" hover-class="unlock-option--hover" @click="fnToBuyMember">
					<view class="unlock-option-icon unlock-option-icon--member">
						<tm-icons name="icon-star-circle" :size="34" color="orange"></tm-icons>
					</view>
					<view class="unlock-option-body">
						<text class="unlock-option-title">开通会员 · 订阅额度提升</text>
						<text class="unlock-option-desc">会员期内订阅上限自动提升，还能免券读付费文章</text>
					</view>
					<view class="unlock-arrow">
						<tm-icons name="icon-angle-right" :size="24" color="#9a9a94"></tm-icons>
					</view>
				</view>

				<text class="unlock-note">解锁的额度永久有效；取消订阅不会退还已解锁的额度</text>
			</view>
		</view>

		<!--
			分类多起来之后，"回到顶部"比"切换视图"有用得多。
			抬高量走与首页/瞬间页同一个来源（tabbarBottomOffsetPx）：自绘底栏是悬浮胶囊，
			它比原生栏「更占高度」，所以偏移必须由底栏几何算出来——原来这里写死 100rpx，
			底栏加上下沿间距后就被压住了一半。
		-->
		<tm-flotbutton v-if="visibleList.length > 6" :offset="[16, 16 + calcTabbarOffset]"
			@click="fnToTopPage" :width="90" size="s" :icon-size="24" color="indigo"
			icon="icon-angle-up"></tm-flotbutton>

		<!-- 订阅要存到账号上，所以未登录点订阅按钮时先引导登录 -->
		<wx-login-dialog :show.sync="loginDialogShow" title="登录后订阅分类"
			desc="订阅会记在你的账号上，换设备也在；登录后首页只显示订阅分类的文章"
			@success="fnOnLoginSuccess"></wx-login-dialog>

		<!-- 自绘底部导航：后台「底部导航」开关关闭时不渲染，仍用微信原生底栏 -->
		<uh-tabbar current="category"></uh-tabbar>
	</view>
</template>

<script>
import tmSkeleton from '@/tm-vuetify/components/tm-skeleton/tm-skeleton.vue';
import tmButton from '@/tm-vuetify/components/tm-button/tm-button.vue';
import tmEmpty from '@/tm-vuetify/components/tm-empty/tm-empty.vue';
import wxLoginDialog from '@/components/wx-login-dialog/wx-login-dialog.vue';
import uhTabbar from '@/components/uh-tabbar/uh-tabbar.vue';
import { checkHasWxLogin } from '@/utils/auth.js';
import { getAppConfigs } from '@/config/index.js';
import { requestSubscribe, openSubscribeSetting, accumulatePushQuota, pushRemindState }
	from '@/utils/subscribeMessage.js';
import loadMoreMixin from '@/common/mixins/loadMore.js';
import tmFlotbutton from '@/tm-vuetify/components/tm-flotbutton/tm-flotbutton.vue';
import tmIcons from '@/tm-vuetify/components/tm-icons/tm-icons.vue';
import { tabbarBottomOffsetPx } from '@/utils/tabbar.js';
import { isUnlimitedQuota } from '@/utils/categorySubscription.js';

/** 一次拉全量分类：站点分类通常几十个，一屏搜完比翻页找更符合「发现」的用法 */
const CATEGORY_PAGE_SIZE = 100;
/** 兜底上限：分类多到要翻 3 页时，与其继续拉不如让站长先整理分类 */
const MAX_CATEGORY_PAGES = 3;
/** 热门榜取前几名：再多就不是「榜」了，而是一份没排完的列表 */
const HOT_LIMIT = 10;
/** 卡片墙一屏先渲染多少个：分类上百个时一次性 setData 会明显卡顿，滚动再追加 */
const RENDER_STEP = 24;

export default {
	mixins: [loadMoreMixin],
	components: {
		tmSkeleton,
		tmButton,
		tmEmpty,
		wxLoginDialog,
		uhTabbar,
		tmIcons,
		tmFlotbutton
	},
	created() {
		this.fnLoadSubscribed();
		this.fnLoadPushStatus();
		this.fnLoadCategories();
		this.fnLoadRanking();
	},
	data() {
		return {
			loading: 'loading',
			/** 全量分类（含 postCount / subscribed / subscribers） */
			dataList: [],
			/** 搜索词：本地过滤，输入即筛 */
			keyword: '',
			/** 浏览范围：全部 / 只看已订阅 */
			scope: 'all',
			/** 排序：人气 / 文章数 / 名称 */
			sortKey: 'hot',
			sortOptions: [
				{ key: 'hot', label: '人气' },
				{ key: 'posts', label: '文章' },
				{ key: 'name', label: '名称' }
			],
			/** 分类名 → 订阅人数；榜单接口失败时保持空表，页面照常可用 */
			rankingMap: {},
			rankingAt: '',
			/** 卡片墙已经渲染到第几个（分类多时滚动追加，避免一次 setData 上百张卡） */
			renderLimit: RENDER_STEP,
			/** 已订阅的分类（metadata.name），列表里每行的开关状态由它投影出来 */
			subscribedCategories: [],
			/** 从邀请人继承来的分类：卡片上标一下，用户才知道这几个不是自己选的 */
			inheritedCategories: [],
			/**
			 * 订阅额度（服务端下发）：上限、免费额度、已购额度、解锁价格、被暂停的分类。
			 *
			 * <p>原来这里硬编码了一个 5 并「与服务端常量保持一致」；现在额度可配
			 * （免费额度 + 积分解锁 + 会员档），只能以服务端为准——写死一个数字迟早会对不上，
			 * 而服务端才是权威（超额提交会被它直接 400 拒绝）。</p>
			 */
			subLimits: null,
			/** 解锁额度弹层 */
			unlockSheetShow: false,
			/** 解锁进行中：防连点（服务端按额度序号幂等，这里只是体验） */
			unlocking: false,
			loggedIn: false,
			/** 已经取过的那份订阅对应的版本号（见 onShow 的去重） */
			subscriptionRev: '',
			/** onShow 里跳过首次展示（首次由 created 负责加载） */
			pageShown: false,
			/** 保存进行中：保存是整体替换，期间禁用开关，避免连点交错 */
			savingSubscribe: false,
			loginDialogShow: false,
			/** 未登录时用户想订阅的那个分类：登录成功后接着订阅，不用再点一次 */
			pendingSubscribeName: '',
			/** 推送状态（服务端记账的额度与总开关）；null = 未取到或功能未开启 */
			pushStatus: null,
			/** 提醒是否已同意过 / 是否被微信侧禁用（本地记账，决定入口要不要出现） */
			pushAgreed: false,
			pushBanned: false
		};
	},
	computed: {
		/**
		 * 分类区标题。
		 *
		 * <p>原来固定写「全部分类」有两处不对：搜出结果时它明明只是搜索结果；
		 * 而且这张表本来就不是「全部」——有排序与筛选，还受审核开关影响。
		 * 标题只该回答「下面这块在给你看什么」。</p>
		 */
		sectionTitle() {
			if (this.keyword) {
				return '搜索结果';
			}
			return this.scope === 'subscribed' ? '我的订阅' : '按分类浏览';
		},

		haloConfigs() {
			return this.$tm.vx.getters().getConfigs;
		},
		calcAuditModeEnabled() {
			return !!this.haloConfigs?.auditConfig?.auditModeEnabled;
		},
		/** 站长是否开启了消息推送（模板也配好了才算开启） */
		calcPushEnabled() {
			const push = this.haloConfigs?.pushConfig;
			return push?.enabled === true && !!push?.templateId;
		},
		/** 订阅墙开关：开着时未订阅分类的磁贴标小锁（详情读全文需订阅） */
		calcWallEnabled() {
			return this.haloConfigs?.subscriptionWallConfig?.enabled === true;
		},
		/**
		 * 是否显示「更新提醒」入口。
		 *
		 * <p><b>只在真的需要用户动手时才出现</b>：没同意过（要拉一次授权）、或微信侧
		 * 「拒绝且不再询问」了（只能去设置页打开）。已经同意的用户不需要看到它——
		 * 额度会在他们读文章、点分类这些本来就要做的动作里静默补上，
		 * 「剩余几条」既不归用户管，也容易被误解成「只能推两次」。</p>
		 */
		calcPushEntryVisible() {
			// 用户在「我的 → 接收更新提醒」里主动关掉时不再出现：那边才是「我不要提醒」的正主，
			// 这里再放一个按钮只会让人以为没关掉
			return this.calcPushEnabled && this.subscribedCount > 0
				&& this.pushNeedsAction && this.pushStatus?.userEnabled !== false;
		},
		/** 入口文案：两种情况分开说，别让用户猜点下去会发生什么 */
		pushLabel() {
			return this.pushBanned ? '恢复更新提醒' : '开启更新提醒';
		},
		subscribedCount() {
			return this.subscribedCategories.length;
		},
		inheritedCount() {
			return this.inheritedCategories.length;
		},
		/** 悬浮按钮抬高量：自绘底栏开启时窗口占满全屏，不抬高会被底栏压住 */
		calcTabbarOffset() {
			return tabbarBottomOffsetPx();
		},
		/** 当前的订阅上限：服务端没下发时按 5（服务端仍会拦，界面不至于放开） */
		subLimit() {
			const limit = Number(this.subLimits?.limit);
			return Number.isFinite(limit) && limit > 0 ? limit : 5;
		},
		subIsMember() {
			return this.subLimits?.memberActive === true;
		},
		/** 是否已到上限（到上限才谈得上「解锁」） */
		isAtLimit() {
			return this.subscribedCount >= this.subLimit;
		},
		/**
		 * 超出额度被暂停的分类。
		 *
		 * <p>本地按「订阅顺序 + 上限」推导，与服务端的 pausedCategories 同一口径：
		 * 这样刚订阅/刚解锁时界面立刻正确，不必等服务端回读。
		 * 服务端没下发额度时返回空——宁可少标，也不要凭空给用户标一片「已暂停」。</p>
		 */
		pausedCategories() {
			if (!this.subLimits) {
				return [];
			}
			return this.subscribedCategories.slice(this.subLimit);
		},
		pausedCount() {
			return this.pausedCategories.length;
		},
		/** 还能不能解锁（开关开着且没买满） */
		/** 是否不限量（站点所有者/管理员）：概览条与解锁弹窗的文案要分开说 */
		subUnlimited() {
			return isUnlimitedQuota(this.subLimits);
		},
		subCanUnlock() {
			return this.subLimits?.purchaseEnabled === true && this.subLimits?.canPurchase === true;
		},
		/** 概览条文案：免费额度 / 已扩容 / 会员三种形态分开说 */
		subBarTitle() {
			if (this.subscribedCount === 0) {
				// 自己有 0 个、但继承了邀请人的推荐：首页这时仍在按那几个分类展示，
				// 概览条必须说出来，否则用户会以为「首页为什么还按分类筛」
				return this.inheritedCount > 0
					? `首页按好友推荐的 ${this.inheritedCount} 个分类展示`
					: '还没订阅分类';
			}
			if (this.subIsMember) {
				return `已订阅 ${this.subscribedCount} 个分类 · 会员不限量`;
			}
			if (this.subUnlimited) {
				// 站点所有者/管理员：不显示「N/2147483647」这种哨兵值
				return `已订阅 ${this.subscribedCount} 个分类 · 不限量`;
			}
			const owned = Number(this.subLimits?.purchasedQuota) || 0;
			return owned > 0
				? `已订阅 ${this.subscribedCount}/${this.subLimit} 个分类（已解锁 ${owned} 份）`
				: `已订阅 ${this.subscribedCount}/${this.subLimit} 个分类`;
		},
		/**
		 * 解锁弹窗的取数。
		 *
		 * <p>把「还差多少积分」直接写进副标题：用户不用自己拿余额去减价格，
		 * 不够时也一眼知道差多少，而不是点下去才被拒绝。</p>
		 */
		unlockPrice() {
			return Number(this.subLimits?.quotaPrice) || 0;
		},
		unlockPointsShort() {
			const balance = Number(this.subLimits?.pointsBalance);
			if (!Number.isFinite(balance) || balance < 0) {
				return 0;
			}
			return Math.max(0, this.unlockPrice - balance);
		},
		unlockPointsDesc() {
			const balance = Number(this.subLimits?.pointsBalance);
			if (!Number.isFinite(balance) || balance < 0) {
				return `解锁后可再订 1 个分类`;
			}
			const short = this.unlockPointsShort;
			return short > 0
				? `当前 ${balance} 积分，还差 ${short} 积分`
				: `当前 ${balance} 积分，解锁后可再订 1 个分类`;
		},
		/** 弹窗顶部的一句话：被暂停 / 到上限 / 未到上限三种口径分开说（未到上限也能从「解锁更多」进来） */
		unlockStatusText() {
			if (this.subUnlimited) {
				return `你的账号订阅不限量，想订哪个就订哪个`;
			}
			if (this.pausedCount > 0) {
				return `已订阅 ${this.subscribedCount}/${this.subLimit} 个分类，${this.pausedCount} 个分类因额度不足被暂停`;
			}
			if (this.isAtLimit) {
				return `已订阅 ${this.subscribedCount}/${this.subLimit} 个分类，免费额度已用完`;
			}
			const remain = Math.max(0, this.subLimit - this.subscribedCount);
			return `当前还能再订 ${remain} 个分类；也可以先解锁，把喜欢的分类都攒着`;
		},
		/** 提醒入口是否需要用户动手（没同意过 / 被禁用了） */
		pushNeedsAction() {
			return !this.pushAgreed || this.pushBanned;
		},
		/** 命中搜索词的分类（按显示名与描述匹配：搜「漏洞」也能找到「安全」） */
		matchedList() {
			const keyword = String(this.keyword || '').trim().toLowerCase();
			if (!keyword) {
				return this.dataList;
			}
			return this.dataList.filter((item) => {
				const name = String(item?.spec?.displayName || '').toLowerCase();
				const desc = String(item?.spec?.description || '').toLowerCase();
				return name.includes(keyword) || desc.includes(keyword);
			});
		},
		/**
		 * 热门榜：永远按订阅人数排，与卡片墙当前选的排序无关。
		 *
		 * 搜索时整块隐藏——用户已经在找某个具体分类了，再顶一份榜单只是噪音。
		 */
		hotList() {
			if (String(this.keyword || '').trim()) {
				return [];
			}
			return this.dataList
				.filter((item) => this.fnSubscribersOf(item) > 0)
				.sort((a, b) => this.fnByPopularity(a, b))
				.slice(0, HOT_LIMIT);
		},
		/** 分类墙最终列表：范围过滤 → 排序 */
		visibleList() {
			const scoped = this.scope === 'subscribed'
				? this.matchedList.filter((item) => item.subscribed)
				: this.matchedList;
			return this.fnSortList(scoped);
		},
		/** 实际渲染出去的一批（滚动到底再追加） */
		renderedList() {
			return this.visibleList.slice(0, this.renderLimit);
		},
		hasMoreRender() {
			return this.visibleList.length > this.renderLimit;
		}
	},
	watch: {
		/** 搜索/筛选/排序变化后回到顶部并重置渲染批次：否则用户会停在上一次结果的滚动位置上 */
		keyword() {
			this.fnResetRender();
		},
		scope() {
			this.fnResetRender();
		},
		sortKey() {
			this.fnResetRender();
		}
	},
	onPullDownRefresh() {
		this.fnLoadSubscribed();
		this.fnLoadCategories();
		this.fnLoadRanking();
		this.fnLoadPushStatus();
	},
	onReachBottom() {
		// 这里是「把已经拿到的分类多渲染几个」，不是再请求接口——分类是一次拉全的
		this.fnLoadMore(this.hasMoreRender, () => {
			this.renderLimit += RENDER_STEP;
			this.fnLoadMoreDone();
		});
	},
	/**
	 * 分享「我的订阅」：落地页会展示我订阅了哪些分类、以及这些分类下的文章——
	 * 「谁给你挑了什么」比一句泛泛的邀请更容易让人点进来。
	 */
	onShareAppMessage() {
		const code = this.fnMyInviteCode();
		const labels = (this.dataList || [])
			.filter((item) => item.subscribed)
			.map((item) => item.spec.displayName)
			.slice(0, 2);
		return {
			title: labels.length
				? `我在关注「${labels.join('、')}」，这些内容分享给你`
				: '这些内容分享给你',
			path: '/pagesA/invite-landing/invite-landing'
				+ (code ? `?code=${encodeURIComponent(code)}` : '')
		};
	},
	onShareTimeline() {
		const code = this.fnMyInviteCode();
		return {
			title: '看看我在关注哪些内容',
			query: code ? { code } : {}
		};
	},
	/**
	 * 每次切回本 tab 都对一次登录态与订阅状态。
	 *
	 * 这两个东西都可能在别的页面被改掉：用户可能是在「我的」里登录的，
	 * 而本页在 created 时拿到的 loggedIn 还是 false —— 那样点订阅按钮只会弹登录框，
	 * 看起来就像「订阅之后无法取消订阅」。
	 *
	 * <p>但「对一次」不等于「打一次接口」：本页是常驻 tab 页，来回切两次 tab 也会走到这里。
	 * 订阅唯一的变化入口都会写本地版本号（分类页保存成功、邀请页接受关注），
	 * 所以版本号没变、登录态也没变时直接跳过，切 tab 就是零请求。</p>
	 */
	onShow() {
		// 首次展示由 created() 负责，避免启动时重复请求
		if (!this.pageShown) {
			this.pageShown = true;
			return;
		}
		const rev = uni.getStorageSync('category.subscription.rev') || '';
		if (rev === this.subscriptionRev && checkHasWxLogin() === this.loggedIn) return;
		this.fnLoadSubscribed();
	},
	methods: {
		// ── 取数 ──

		/**
		 * 拉全量分类（最多 3 页）。
		 *
		 * 「发现」页要一次拿到全部分类才能做本地搜索与排序，所以这里主动翻页把结果拼起来，
		 * 而不是把翻页交给用户；上拉只负责多渲染几张卡（见 onReachBottom）。
		 */
		async fnLoadCategories() {
			if (this.calcAuditModeEnabled) {
				this.dataList = this.fnMockCategories();
				this.loading = 'success';
				uni.stopPullDownRefresh();
				return;
			}
			// 下拉刷新时列表已有内容：不清空、不闪骨架，静默替换
			if (!this.dataList.length) {
				this.loading = 'loading';
			}
			try {
				const collected = [];
				for (let page = 1; page <= MAX_CATEGORY_PAGES; page++) {
					const res = await this.$httpApi.v2.getCategoryList({
						size: CATEGORY_PAGE_SIZE,
						page,
						fieldSelector: ['spec.hideFromList=false']
					});
					const items = this.$utils.stripDataUnsafeList(res?.items || []);
					collected.push(...items);
					if (!res?.hasNext || items.length === 0) {
						break;
					}
				}
				this.dataList = collected.map((item, index) => this.fnMapCategory(item, index));
				this.loading = 'success';
			} catch (err) {
				// 有旧数据就留着用（下拉刷新失败不该把页面清空），没有才整页报错
				this.loading = this.dataList.length ? 'success' : 'error';
			} finally {
				uni.stopPullDownRefresh();
			}
		},

		/** 分类对象 → 页面用的形状：补齐订阅态、热度与兜底色板下标 */
		fnMapCategory(item, index) {
			const name = item?.metadata?.name || '';
			// 只有真有封面才归一化：checkThumbnailUrl 在空值时会返回默认缩略图，
			// 那会让「无封面」的分类看起来像有封面，色板兜底就永远走不到了
			const cover = item?.spec?.cover
				? this.$utils.checkThumbnailUrl(item.spec.cover, true) : '';
			return {
				...item,
				spec: { ...(item.spec || {}), cover },
				postCount: item?.postCount ?? 0,
				// 订阅状态由已订阅列表投影出来，服务端返回的分类对象里没有这个字段
				subscribed: this.subscribedCategories.includes(name),
				// 色板下标由分类名决定而不是列表下标：换个排序颜色就跟着变，会让人以为换了一批分类
				glowIndex: this.fnGlowIndex(name || String(index))
			};
		},

		/** 某个分类在色板里的下标（稳定：同一分类任何时候都是同一个颜色） */
		fnGlowIndex(seed) {
			let hash = 0;
			const text = String(seed);
			for (let i = 0; i < text.length; i++) {
				hash = (hash * 31 + text.charCodeAt(i)) % 997;
			}
			return hash;
		},

		/**
		 * 取分类订阅热度（「哪些分类被订阅得最多」）。
		 *
		 * 失败就整块降级：热门榜不显示、卡片上不显示人数，分类墙与订阅照常可用——
		 * 热度只是排序依据，不能因为它把整页拖垮。
		 */
		async fnLoadRanking() {
			try {
				const res = await this.$httpApi.v2.getCategoryRanking();
				const body = res?.data || res || {};
				if (body.error || !Array.isArray(body.items)) {
					throw new Error(body.error || 'ranking unavailable');
				}
				const map = {};
				body.items.forEach((item) => {
					if (item && item.name) {
						map[item.name] = Number(item.subscribers || 0);
					}
				});
				this.rankingMap = map;
				this.rankingAt = body.updatedAt || '';
			} catch (err) {
				this.rankingMap = {};
				this.rankingAt = '';
			}
		},

		/**
		 * 取用户已订阅的分类；未登录时清空。
		 *
		 * 订阅数据存在服务端（按 openid 唯一），所以换个手机、重装小程序都还在。
		 */
		async fnLoadSubscribed() {
			try {
				this.loggedIn = checkHasWxLogin();
			} catch (e) {
				this.loggedIn = false;
			}
			// 记下「这份订阅状态对应哪个版本号」，onShow 据此决定要不要再取一次
			this.subscriptionRev = uni.getStorageSync('category.subscription.rev') || '';
			if (!this.loggedIn) {
				this.fnApplySubscribed([]);
				return;
			}
			try {
				const res = await this.$httpApi.v2.getDigestSubscription();
				const body = res?.data || res || {};
				const sub = body.subscription || {};
				// 额度以服务端为准：上限、免费额度、已购份数、解锁价格、被暂停的分类
				this.subLimits = sub.limits || null;
				this.fnApplySubscribed(Array.isArray(sub.postCategories) ? sub.postCategories : [],
					Array.isArray(sub.inheritedCategories) ? sub.inheritedCategories : []);
			} catch (err) {
				// 静默：拿不到订阅状态就按「一个都没订阅」显示，分类列表本身照常可用
				this.fnApplySubscribed([]);
			}
		},

		/**
		 * 把订阅名列表投影到已渲染的数据上。
		 *
		 * 列表顺序不做「已订阅置顶」或「已订阅沉底」：一点订阅按钮整页顺序就变，
		 * 手指下的那张卡会跳到别处——订阅状态靠卡片自身的开关表达就够，
		 * 想只看自己订的，用上面那个「已订阅」筛选。
		 */
		fnApplySubscribed(names, inherited) {
			this.subscribedCategories = Array.isArray(names) ? names.slice() : [];
			this.inheritedCategories = Array.isArray(inherited) ? inherited.slice() : [];
			this.dataList = (this.dataList || []).map((item) => {
				item.subscribed = this.subscribedCategories.includes(item?.metadata?.name);
				return item;
			});
		},

		// ── 展示 ──

		/**
		 * 磁贴副标题：篇数与订阅人数（热度拿不到时只显示篇数）。
		 *
		 * 继承来的分类换成「好友推荐」——继承项不是用户自己订的，这时候人数对他没有意义，
		 * 而这个信息（"这几个不是我自己选的"）更重要；一行的宽度也只够放两个信息。
		 */
		fnMetaText(item) {
			const prefix = this.fnIsInherited(item) ? ['好友推荐'] : [];
			const parts = [...prefix, `${item?.postCount || 0}篇`];
			const subscribers = this.fnSubscribersOf(item);
			if (!prefix.length && subscribers > 0) {
				parts.push(`${subscribers}人订阅`);
			}
			return parts.join(' · ');
		},

		fnSubscribersOf(item) {
			const name = item?.metadata?.name;
			return name ? Number(this.rankingMap[name] || 0) : 0;
		},

		/** 人气序：订阅人数 → 文章数 → 名称（没有热度的站点等价于按文章数排） */
		fnByPopularity(a, b) {
			return this.fnSubscribersOf(b) - this.fnSubscribersOf(a)
				|| (b.postCount || 0) - (a.postCount || 0)
				|| this.fnCompareName(a, b);
		},

		/** 排序：人气 / 文章数 / 名称 */
		fnSortList(list) {
			const items = (list || []).slice();
			if (this.sortKey === 'posts') {
				return items.sort((a, b) => (b.postCount || 0) - (a.postCount || 0)
					|| this.fnCompareName(a, b));
			}
			if (this.sortKey === 'name') {
				return items.sort((a, b) => this.fnCompareName(a, b));
			}
			return items.sort((a, b) => this.fnByPopularity(a, b));
		},

		/**
		 * 分类名比较。
		 *
		 * 小程序里没有可靠的拼音排序（localeCompare 的 locale 参数多数环境会被忽略），
		 * 所以按码位比较：结果至少是稳定的（同名分类每次都在同一位置），而不是「每次刷新都乱」。
		 */
		fnCompareName(a, b) {
			const left = String(a?.spec?.displayName || '');
			const right = String(b?.spec?.displayName || '');
			try {
				return left.localeCompare(right, 'zh-CN');
			} catch (e) {
				return left === right ? 0 : (left < right ? -1 : 1);
			}
		},

		/** 有空状态（搜索无结果 / 没订阅过 / 站点还没分类）时，文案要说清是哪一种 */
		fnEmptyLabel() {
			if (String(this.keyword || '').trim()) {
				return '没有找到相关分类';
			}
			return this.scope === 'subscribed' ? '还没有订阅任何分类' : '暂无分类';
		},

		fnLoadText() {
			if (this.visibleList.length <= RENDER_STEP) {
				return '';
			}
			return this.hasMoreRender ? '上拉加载更多' : '呜呜，没有更多数据啦~';
		},

		/** 换搜索词/筛选/排序后从第一批重新渲染，并回到顶部 */
		fnResetRender() {
			this.renderLimit = RENDER_STEP;
			this.fnToTopPage(0);
		},

		fnSwitchScope(scope) {
			this.scope = scope;
		},

		fnClearKeyword() {
			this.keyword = '';
		},

		/**
		 * 卡片封面的完整地址；没有封面时返回空串，交给色板兜底。
		 *
		 * 站点里老分类的封面存的是相对路径（{@code /upload/xxx.jpg}），
		 * 直接给 image 组件是加载不出来的，所以取数时已经归一化过一次。
		 */
		fnCatCover(item) {
			const cover = item && item.spec && item.spec.cover;
			return cover || '';
		},

		/**
		 * 无封面时的渐变色板（按分类名循环）。
		 *
		 * 自动创建的主题分类没有封面图，卡片不能因此变成一片白：
		 * 用一组柔和的渐变打底，保证每张卡都有颜色。
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

		/** 审核模式的模拟数据：订阅按钮不出现，只展示布局 */
		fnMockCategories() {
			return (this.$tm.vx.getters().getMockJson?.category?.list || []).map((item, index) => {
				const name = `mock-${index}`;
				return {
					metadata: { name },
					spec: {
						displayName: item.title,
						description: '',
						cover: this.$utils.checkImageUrl(item.cover)
					},
					postCount: 0,
					subscribed: false,
					glowIndex: this.fnGlowIndex(name)
				};
			});
		},

		// ── 订阅 ──

		/**
		 * 点卡片右上角的订阅按钮：切换这一个分类的订阅状态，然后整体提交。
		 *
		 * <p><b>状态必须在这里翻转</b>。这个按钮早期是 {@code tm-switch} 开关，
		 * 那时 v-model 会在回调之前先把值翻好，所以回调里直接读就行；改成普通点击的
		 * 「订阅 / 已订阅」胶囊之后，没有任何地方再翻这个值了。</p>
		 */
		async fnOnSubscribeChange(item) {
			const name = item && item.metadata && item.metadata.name;
			if (!name || this.savingSubscribe) {
				return;
			}
			// 关键：按 name 从 dataList 取回「当前这一张卡」再改，不要用事件参数里的那个对象。
			// 小程序事件参数拿到的是渲染期快照里的对象，改它改不到页面数据——
			// 于是下面读到的还是旧值、提交上去还是原来那份清单，表现就是「怎么点都取消不掉」。
			const row = this.fnRowOf(name);
			if (!row) {
				return;
			}
			const next = !row.subscribed;
			if (!this.loggedIn) {
				// 未登录先不动本地状态：登录成功后按 pendingSubscribeName 接着订阅
				this.pendingSubscribeName = name;
				this.loginDialogShow = true;
				return;
			}
			// 要订阅时先看额度（取消订阅永远允许）
			if (next && this.fnSubscribedNames().length >= this.subLimit) {
				if (this.subCanUnlock) {
					// 到上限了：直接把「怎么多订」摆出来，而不是让用户自己去别处找
					this.fnOpenUnlockSheet();
					return;
				}
				uni.showToast({
					title: `最多订阅 ${this.subLimit} 个分类，先取消一个再选`,
					icon: 'none'
				});
				return;
			}
			row.subscribed = next;
			await this.fnSaveSubscription(next
				? `已订阅「${row.spec.displayName}」`
				: `已取消「${row.spec.displayName}」`);
		},

		// ── 解锁额度 ──

		/** 这个分类是不是被额度暂停了（暂停期不参与首页取数，但订阅本身还在） */
		fnIsPaused(item) {
			const name = item?.metadata?.name;
			return !!name && this.pausedCategories.includes(name);
		},

		fnOpenUnlockSheet() {
			if (!this.loggedIn) {
				this.loginDialogShow = true;
				return;
			}
			if (!this.subCanUnlock && !this.subIsMember) {
				uni.showToast({ title: '当前没有可解锁的额度', icon: 'none' });
				return;
			}
			this.unlockSheetShow = true;
		},

		/** 弹窗里选了会员卡：关掉弹窗再跳权益直购页；highlight=member 让对方指认出该买哪张卡 */
		fnToBuyMember() {
			this.unlockSheetShow = false;
			uni.navigateTo({
				url: '/pagesA/points-recharge/points-recharge?highlight=member',
				fail: () => uni.showToast({ title: '打开权益直购页失败', icon: 'none' })
			});
		},

		/**
		 * 用积分解锁一份额度。
		 *
		 * <p>点按前先用已知余额判断够不够：不足时直接给「还差多少」与「去赚积分」入口，
		 * 而不是等用户确认完再被服务端拒绝（与 AI 定制内容页的兑换同一套交互）。
		 * 余额可能已变，服务端仍会做权威校验。</p>
		 */
		async fnUnlockWithPoints() {
			if (this.unlocking) {
				return;
			}
			const price = this.unlockPrice;
			const balance = Number(this.subLimits?.pointsBalance);
			if (price > 0 && Number.isFinite(balance) && balance >= 0 && balance < price) {
				const short = price - balance;
				uni.showModal({
					title: '积分不足',
					content: `解锁一份需要 ${price} 积分，你当前有 ${balance} 积分，还差 ${short} 积分。`
						+ '签到、浏览文章、发表评论都能赚积分。',
					confirmText: '去赚积分',
					cancelText: '知道了',
					success: (res) => {
						if (res.confirm) {
							this.unlockSheetShow = false;
							uni.navigateTo({
								url: '/pagesA/task-center/task-center',
								fail: () => uni.switchTab({ url: '/pages/tabbar/about/about' })
							});
						}
					}
				});
				return;
			}
			this.unlocking = true;
			try {
				const res = await this.$httpApi.v2.purchaseSubscribeQuota(1);
				const body = res?.data || res || {};
				if (body.error) {
					uni.showToast({ title: body.error, icon: 'none' });
					return;
				}
				// 成功就地关弹窗：新的上限与暂停分类由 fnLoadSubscribed 从服务端取回
				this.unlockSheetShow = false;
				uni.showToast({ title: body.message || '已解锁一份订阅额度', icon: 'none' });
				// 积分余额变了：我的页/身份页靠这个事件同步（与其它扣分链路同一约定）
				if (body.balance != null) {
					uni.$emit('points_updated', { balance: body.balance });
				}
				await this.fnLoadSubscribed();
			} catch (err) {
				uni.showToast({
					title: err?.error || err?.detail || '解锁失败，请稍后重试',
					icon: 'none'
				});
			} finally {
				this.unlocking = false;
			}
		},

		/** 这个分类是不是从邀请人那里继承来的（标出来，用户才知道不是自己选的） */
		fnIsInherited(item) {
			const name = item && item.metadata && item.metadata.name;
			return !!name && this.inheritedCategories.includes(name);
		},

		/** 我自己的邀请码；没有就返回空（照常分享，只是不带邀请） */
		fnMyInviteCode() {
			try {
				return uni.getStorageSync('invite.code') || '';
			} catch (e) {
				return '';
			}
		},

		/** 按 metadata.name 取回列表里的当前行（事件参数可能是快照，不能直接改它） */
		fnRowOf(name) {
			return (this.dataList || []).find((entry) => entry && entry.metadata
				&& entry.metadata.name === name) || null;
		},

		/** 当前应该保存的订阅名列表（以卡片上的订阅状态为准） */
		fnSubscribedNames() {
			return this.dataList
				.filter((item) => item.subscribed && item.metadata && item.metadata.name)
				.map((item) => item.metadata.name);
		},

		/**
		 * 清空订阅：一次请求清空，比让人一个个点回去快得多；先确认再动手。
		 *
		 * <p>这一步一次抹掉全部分类订阅，而按钮就在拇指容易碰到的地方——
		 * 没有确认的话，误触的代价是「订阅全没了」，用户还不知道发生了什么。</p>
		 */
		async fnClearSubscribe() {
			if (!this.subscribedCount) {
				return;
			}
			const count = this.subscribedCount;
			const confirmed = await new Promise((resolve) => {
				uni.showModal({
					title: '清空全部订阅？',
					content: this.inheritedCount > 0
						? `将取消这 ${count} 个分类的订阅，好友推荐给你的 ${this.inheritedCount} 个分类也会一并取消（否则首页还会按它们筛选）。分类内容不受影响，随时可以重新订阅。`
						: `将取消这 ${count} 个分类的订阅。分类内容不受影响，随时可以重新订阅。`,
					confirmText: '清空',
					confirmColor: '#d9483b',
					success: (res) => resolve(!!res.confirm),
					fail: () => resolve(false)
				});
			});
			if (!confirmed) {
				return;
			}
			this.dataList.forEach((item) => {
				item.subscribed = false;
			});
			await this.fnSaveSubscription('已清空全部订阅');
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
			const item = this.fnRowOf(name);
			// 登录期间可能已在别处订阅过：已经是订阅态就不再重复切换
			if (!item || item.subscribed || this.subscribedCount >= this.subLimit) {
				return;
			}
			item.subscribed = true;
			await this.fnSaveSubscription(`已订阅「${item.spec.displayName}」`);
		},

		/**
		 * 保存订阅。
		 *
		 * 服务端是整体替换，失败或超限时以服务端为准回读一次，
		 * 免得界面停留在「看起来订阅了、实际没存上」的状态。
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
				// 以服务端返回的为准回写界面：本地标记万一有偏差（事件参数是快照、
				// 或两次点击挨得太近），这里能把状态与服务端对齐，不会停在"看起来没生效"
				const saved = Array.isArray(body.postCategories) ? body.postCategories : names;
				const savedInherited = Array.isArray(body.inheritedCategories)
					? body.inheritedCategories : this.inheritedCategories;
				// 提示语由调用方给：订阅某一个、取消某一个、全部取消，说清楚做的是哪件事
				uni.showToast({
					title: message || (saved.length
						? `已订阅 ${saved.length} 个分类` : '已取消全部订阅'),
					icon: 'none'
				});
				this.fnApplySubscribed(saved, savedInherited);
				// 首页可能正停在旧的筛选结果上：写一个版本号（切回首页时据它重取），
				// 同时发事件（首页还活着时立刻刷新）。两条路都留着——
				// 事件在页面生命周期边界上可能错过，版本号则一定对得上。
				const rev = String(Date.now());
				uni.setStorageSync('category.subscription.rev', rev);
				this.subscriptionRev = rev;
				uni.$emit('category_subscription_changed');
				// 刚订阅完是最自然的授权时机：此时弹「有更新时通知你」用户不会觉得被打扰。
				// 只在「首次订阅成功且还没额度」时请求，避免每次点订阅都弹窗。
				this.fnMaybeAskPushPermission(saved.length > 0);
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

		/** 读取提醒状态：本地记账决定入口是否出现，服务端状态只用于「要不要争取一次授权」 */
		async fnLoadPushStatus() {
			// 本地那份先读：即使还没登录也能决定入口文案
			const state = pushRemindState();
			this.pushAgreed = state.agreed;
			this.pushBanned = state.banned;
			if (!this.calcPushEnabled || !checkHasWxLogin()) {
				this.pushStatus = null;
				return;
			}
			try {
				const res = await this.$httpApi.v2.getPushStatus();
				const body = res?.data || res || {};
				this.pushStatus = body.error ? null : body;
			} catch (err) {
				this.pushStatus = null;
			}
		},

		/**
		 * 点「更新提醒」：请求订阅授权 + 上报额度。
		 *
		 * 用户勾过「总是保持以上选择」之后这一步不再弹窗、直接返回同意；
		 * 之后的额度由 {@link accumulatePushQuota} 在用户读文章/点分类时静默补上，
		 * 所以这个入口对用户来说只需要点这一次。
		 */
		async fnOnPushRemind() {
			if (!this.loggedIn) {
				// 与订阅同一套登录拦截：登录后用户再点一次即可（不自动续做，避免授权窗在登录流程后突兀弹出）
				this.loginDialogShow = true;
				return;
			}
			const push = getAppConfigs()?.pushConfig || {};
			const result = await requestSubscribe(this.$httpApi.v2, [
				{ kind: 'newPost', tmplId: push.templateId }
			]);
			// 本地记账跟着刷新：授权成功就不再显示入口，被禁用了就换成「恢复」文案
			const state = pushRemindState();
			this.pushAgreed = state.agreed;
			this.pushBanned = state.banned;
			if (result.ok) {
				uni.showToast({ title: '已开启：订阅的分类有新文章时通知你', icon: 'none' });
				this.fnLoadPushStatus();
				return;
			}
			if (Array.isArray(result.banned) && result.banned.length > 0) {
				// 被「拒绝且不再询问」：只能引导到设置页，这里给一次直达
				uni.showModal({
					title: '需要在小程序设置里打开',
					content: '你之前关闭了该小程序的订阅消息。点「去设置」打开「接收订阅消息」即可继续接收更新提醒。',
					confirmText: '去设置',
					success: (res) => {
						if (res.confirm) {
							openSubscribeSetting();
						}
					}
				});
				return;
			}
			uni.showToast({ title: result.error || '未能开启提醒', icon: 'none' });
		},

		/**
		 * 订阅成功后顺带争取一次推送授权（已有额度时不打扰）。
		 *
		 * 只在「还没同意过」时问一次：同意过的用户由静默续额兜住，
		 * 每次点订阅都弹授权窗才是真的打扰人。
		 */
		async fnMaybeAskPushPermission(hasSubscription) {
			if (!hasSubscription || !this.calcPushEnabled || this.pushAgreed || this.pushBanned) {
				return;
			}
			const status = this.pushStatus;
			if (status && Number(status.newPostQuota || 0) > 0) {
				return;
			}
			const push = getAppConfigs()?.pushConfig || {};
			const result = await requestSubscribe(this.$httpApi.v2, [
				{ kind: 'newPost', tmplId: push.templateId }
			]);
			const state = pushRemindState();
			this.pushAgreed = state.agreed;
			this.pushBanned = state.banned;
			if (result.ok) {
				this.fnLoadPushStatus();
			}
		},

		/**
		 * 点分类：进分类详情页看文章（订阅是旁边那颗按钮的事，两者互不干扰）。
		 *
		 * 顺手补一次提醒额度：这是用户本来就要做的点击，微信允许在这个时机调用订阅授权，
		 * 勾过「总是保持以上选择」的话不会弹窗、也不会有任何提示。
		 */
		handleToCategory(data) {
			if (this.calcAuditModeEnabled) {
				return;
			}
			if (!data?.metadata?.name) {
				return;
			}
			accumulatePushQuota(this.$httpApi.v2);
			uni.navigateTo({
				url: `/pagesA/category-detail/category-detail?name=${data.metadata.name}&title=${data.spec.displayName}`,
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
	box-sizing: border-box;
	padding-bottom: 40rpx;
}

.loading-wrap {
	padding: 24rpx;
}

.app-page-content {
	display: block;
}

.content-empty {
	width: 100%;
	padding: 100rpx 0;
	flex-direction: column;
}

.retry-btn {
	margin-top: 24rpx;
	padding: 12rpx 40rpx;
	border-radius: $uh-radius-pill;
	background: $uh-accent-weak;
	color: $uh-accent;
	font-size: 24rpx;
}

/* ===== 搜索 ===== */
/* 吸顶：分类上百个时往下翻，搜索框还得够得着，否则「找分类」等于从头再翻一遍 */
.search-bar {
	position: sticky;
	top: 0;
	z-index: 20;
	display: flex;
	align-items: center;
	padding: 20rpx 24rpx 16rpx;
	background: $uh-bg;
}

.search-box {
	flex: 1;
	display: flex;
	align-items: center;
	height: 72rpx;
	padding: 0 22rpx;
	border-radius: $uh-radius-pill;
	background: $uh-card;
	box-shadow: $uh-shadow-sm;
}

.search-icon {
	font-size: 26rpx;
	color: $uh-ink-3;
}

.search-input {
	flex: 1;
	height: 100%;
	margin-left: 12rpx;
	font-size: 26rpx;
	color: $uh-ink-1;
}

.search-placeholder {
	color: $uh-ink-3;
	font-size: 26rpx;
}

.search-clear {
	padding: 0 6rpx;
	font-size: 26rpx;
	color: $uh-ink-3;
}

/* 订阅额度是硬上限（服务端也只收 5 个），订满时把它标出来 */
/* ===== 订阅概览条：状态在左、动作在右，一条解决「订了几个/怎么管理」 ===== */
.sub-bar {
	display: flex;
	align-items: center;
	justify-content: space-between;
	margin: 4rpx 24rpx 0;
	padding: 16rpx 20rpx;
	border-radius: $uh-radius-md;
	background: $uh-card;
	box-shadow: $uh-shadow-sm;
}

.sub-bar-main {
	display: flex;
	align-items: center;
	flex: 1;
	min-width: 0;
}

/* 状态图标比文字稍重，扫一眼就知道这条讲什么 */
.sub-bar-icon {
	display: flex;
	align-items: center;
	justify-content: center;
	width: 40rpx;
	height: 40rpx;
	margin-right: 12rpx;
	border-radius: $uh-radius-pill;
	background: $uh-success-weak;
	color: $uh-success;
	font-size: 22rpx;
}

/* 未订阅时用中性色：此时它不是「成就」，只是提示这里的状态 */
.sub-bar-icon.is-full {
	background: $uh-warning-weak;
	color: $uh-warning;
}

.sub-bar-title {
	font-size: 24rpx;
	color: $uh-ink-2;
	overflow: hidden;
	text-overflow: ellipsis;
	white-space: nowrap;
}

/* 订满是一个真实约束（第 6 个订不上），要说得比普通状态重一点 */
.sub-bar-title.is-full {
	color: $uh-ink-1;
	font-weight: 600;
}

.sub-bar-flag {
	margin-left: 12rpx;
	padding: 2rpx 12rpx;
	border-radius: $uh-radius-pill;
	background: $uh-warning-weak;
	color: $uh-warning;
	font-size: 20rpx;
}

.sub-bar-actions {
	display: flex;
	align-items: center;
	flex-shrink: 0;
}

.sub-bar-btn {
	padding: 10rpx 22rpx;
	border-radius: $uh-radius-pill;
	font-size: 22rpx;
	line-height: 1.4;
}

.sub-bar-btn + .sub-bar-btn {
	margin-left: 12rpx;
}

/* 更新提醒是主动作（用户能因此收到推送），保持实心；清空订阅是破坏性次要动作 */
.sub-bar-btn.is-push {
	background-image: $uh-grad-accent;
	color: #ffffff;
}

/* 解锁是「想要更多」的动作，用品牌色描边，与「清空订阅」（破坏性）区分开 */
.sub-bar-btn.is-unlock {
	border: 1rpx solid $uh-accent;
	color: $uh-accent;
	background: $uh-accent-weak;
}

.sub-bar-btn.is-danger {
	border: 1rpx solid $uh-danger;
	color: $uh-danger;
	background: transparent;
}

/* 会员期内用星形图标：与普通已订阅区分开，用户能看出上限为什么这么宽 */
.sub-bar-icon.is-member {
	background: $uh-warning-weak;
	color: $uh-warning;
}

/* 超出额度被暂停：整张卡降饱和，右上角一块可点的「去解锁」 */
.cat-tile.is-paused {
	opacity: 0.62;
}

.tile-paused-flag {
	position: absolute;
	left: 12rpx;
	top: 12rpx;
	padding: 4rpx 12rpx;
	border-radius: $uh-radius-pill;
	background: $uh-warning-weak;
	z-index: 3;
}

.tile-paused-text {
	font-size: 18rpx;
	color: $uh-warning;
}

/* 订阅墙小锁：未订阅分类的详情读全文需要订阅，右上角轻量标记 */
.tile-wall-flag {
	position: absolute;
	left: 12rpx;
	top: 12rpx;
	padding: 2rpx 8rpx;
	border-radius: $uh-radius-pill;
	background: $uh-fill;
	z-index: 3;
}

.tile-wall-text {
	font-size: 18rpx;
	line-height: 1.4;
}

/* ===== 区块头 ===== */
.section {
	margin-top: 24rpx;
}

.section-head {
	display: flex;
	align-items: center;
	justify-content: space-between;
	padding: 0 28rpx;
}

.section-title {
	font-size: 30rpx;
	font-weight: 700;
	color: $uh-ink-1;
}

.section-sub {
	font-size: 21rpx;
	color: $uh-ink-3;
}

.filter-group {
	display: flex;
	align-items: center;
}

.filter-chip {
	padding: 6rpx 20rpx;
	margin-left: 12rpx;
	border-radius: $uh-radius-pill;
	background: $uh-fill;
	color: $uh-ink-2;
	font-size: 22rpx;
}

.filter-chip.is-on {
	background: $uh-accent-weak;
	color: $uh-accent;
	font-weight: 700;
}

.sort-bar {
	display: flex;
	align-items: center;
	padding: 16rpx 28rpx 0;
}

.sort-item {
	margin-right: 28rpx;
	font-size: 23rpx;
	color: $uh-ink-3;
}

.sort-item.is-active {
	color: $uh-accent;
	font-weight: 700;
}

/* ===== 热门榜 ===== */
.rank-list {
	margin: 16rpx 24rpx 0;
	border-radius: $uh-radius-lg;
	background-color: $uh-card;
	box-shadow: $uh-shadow-sm;
	overflow: hidden;
}

.rank-row {
	display: flex;
	align-items: center;
	padding: 20rpx 22rpx;
}

.rank-row + .rank-row {
	border-top: 1rpx solid $uh-line;
}

/* 名次用「层级递减」而不是彩色：前三名越靠前越重，第四名之后统一最浅 */
.rank-no {
	width: 44rpx;
	font-size: 26rpx;
	font-weight: 700;
	color: $uh-ink-4;
	text-align: center;
	flex-shrink: 0;
}

.rank-no-1 {
	color: $uh-accent;
	font-size: 30rpx;
}

.rank-no-2 {
	color: $uh-ink-2;
}

.rank-no-3 {
	color: $uh-ink-3;
}

.rank-main {
	flex: 1;
	display: flex;
	align-items: center;
	min-width: 0;
	margin-left: 8rpx;
}

.rank-icon-wrap {
	flex-shrink: 0;
}

.rank-icon {
	width: 72rpx;
	height: 72rpx;
	border-radius: 50%;
	border: 4rpx solid #ffffff;
	box-shadow: 0 4rpx 12rpx rgba(31, 41, 55, 0.12);
}

.rank-icon-fallback {
	display: flex;
	align-items: center;
	justify-content: center;
}

.rank-icon-letter {
	font-size: 30rpx;
	font-weight: 700;
	color: #ffffff;
	text-shadow: 0 2rpx 8rpx rgba(0, 0, 0, 0.18);
}

.rank-text {
	flex: 1;
	min-width: 0;
	display: flex;
	flex-direction: column;
	margin-left: 18rpx;
}

.rank-name {
	font-size: 28rpx;
	font-weight: 600;
	color: $uh-ink-1;
	overflow: hidden;
	text-overflow: ellipsis;
	white-space: nowrap;
}

.rank-meta {
	margin-top: 6rpx;
	font-size: 21rpx;
	color: $uh-ink-3;
}

/* 热门榜行里的订阅胶囊：这一行背景是纯白卡片，所以未订阅态用浅灰底而不是透明白 */
.rank-row .cat-sub {
	flex-shrink: 0;
	padding: 7rpx 20rpx;
	border-radius: $uh-radius-pill;
	background: $uh-fill;
}

.rank-row .cat-sub.is-on {
	background-image: $uh-grad-accent;
}

.rank-row .cat-sub-text {
	font-size: 20rpx;
	color: $uh-ink-2;
}

.rank-row .cat-sub.is-on .cat-sub-text {
	color: #fff;
}

/* ===== 分类磁贴（三列） ===== */
.cat-grid {
	display: grid;
	grid-template-columns: repeat(3, 1fr);
	gap: 16rpx;
	padding: 16rpx 24rpx 0;
	width: 100%;
	box-sizing: border-box;
}

.cat-tile {
	position: relative;
	display: flex;
	padding: 26rpx 12rpx 20rpx;
	border-radius: $uh-radius-md;
	background-color: $uh-card;
	box-shadow: $uh-shadow-sm;
	overflow: hidden;
	transition: box-shadow 0.2s;
}

/* 已订阅：淡靛底 + 品牌色光晕；未订阅保持纯白，视觉上更轻 */
.cat-tile.is-on {
	background: linear-gradient(165deg, $uh-accent-weak 0%, #ffffff 70%);
	box-shadow: 0 6rpx 16rpx rgba(47, 84, 235, 0.14);
}

/* 订阅按钮压在右上角：给到 48rpx 才不至于难点——它旁边还有整块磁贴可点（进详情） */
.tile-btn {
	position: absolute;
	right: 8rpx;
	top: 8rpx;
	z-index: 3;
	width: 48rpx;
	height: 48rpx;
	border-radius: 50%;
	display: flex;
	align-items: center;
	justify-content: center;
	background: $uh-accent-weak;
}

.tile-btn.is-on {
	background-image: $uh-grad-accent;
}

.tile-btn-text {
	font-size: 26rpx;
	line-height: 1;
	color: $uh-accent;
}

.tile-btn.is-on .tile-btn-text {
	color: #fff;
	font-weight: 700;
}

.tile-main {
	flex: 1;
	min-width: 0;
	display: flex;
	flex-direction: column;
	align-items: center;
}

.tile-icon {
	width: 88rpx;
	height: 88rpx;
	border-radius: $uh-radius-md;
	flex-shrink: 0;
}

.tile-icon-fallback {
	display: flex;
	align-items: center;
	justify-content: center;
}

.tile-icon-letter {
	font-size: 36rpx;
	font-weight: 700;
	color: #ffffff;
	text-shadow: 0 2rpx 6rpx rgba(0, 0, 0, 0.18);
}

.tile-name {
	margin-top: 16rpx;
	max-width: 100%;
	font-size: 26rpx;
	font-weight: 600;
	color: $uh-ink-1;
	overflow: hidden;
	text-overflow: ellipsis;
	white-space: nowrap;
}

.tile-meta {
	margin-top: 6rpx;
	max-width: 100%;
	font-size: 20rpx;
	color: $uh-ink-3;
	overflow: hidden;
	text-overflow: ellipsis;
	white-space: nowrap;
}

.load-text {
	width: 100%;
	text-align: center;
	padding: 24rpx 0 16rpx;
	font-size: 22rpx;
	color: $uh-ink-3;
}

/* ── 解锁额度弹窗（居中） ──
   遮罩层级必须压过自绘底栏（z-index 1000），否则弹窗下半截被底栏盖住——
   换居中弹窗就是从这个遮挡问题来的，这里取 1100 再留出余量。 */
.unlock-mask {
	position: fixed;
	top: 0;
	left: 0;
	right: 0;
	bottom: 0;
	z-index: 1100;
	background: rgba(0, 0, 0, 0.45);
	display: flex;
	align-items: center;
	justify-content: center;
	padding: 48rpx;
	box-sizing: border-box;
}

.unlock-dialog {
	position: relative;
	width: 100%;
	max-width: 620rpx;
	background: $uh-card;
	border-radius: 24rpx;
	padding: 40rpx 32rpx 28rpx;
	box-sizing: border-box;
}

.unlock-close {
	position: absolute;
	top: 24rpx;
	right: 24rpx;
	padding: 8rpx;
}

.unlock-title {
	display: block;
	padding-right: 56rpx;
	font-size: 34rpx;
	font-weight: 700;
	color: $uh-ink-1;
}

.unlock-status {
	display: block;
	margin-top: 10rpx;
	font-size: 24rpx;
	line-height: 1.6;
	color: $uh-ink-3;
}

.unlock-option {
	display: flex;
	align-items: center;
	margin-top: 24rpx;
	padding: 24rpx;
	border-radius: 18rpx;
	background: $uh-fill;
}

.unlock-option--hover {
	opacity: 0.75;
}

.unlock-option-icon {
	flex-shrink: 0;
	width: 76rpx;
	height: 76rpx;
	border-radius: 20rpx;
	display: flex;
	align-items: center;
	justify-content: center;
	background: $uh-card;
}

.unlock-option-body {
	flex: 1;
	min-width: 0;
	margin: 0 20rpx;
}

.unlock-option-title {
	display: block;
	font-size: 28rpx;
	font-weight: 600;
	color: $uh-ink-1;
}

.unlock-option-desc {
	display: block;
	margin-top: 6rpx;
	font-size: 22rpx;
	line-height: 1.5;
	color: $uh-ink-3;
}

/* 积分不够时差额用警示色：让用户先看到「差多少」再决定去哪赚 */
.unlock-option-desc.is-short {
	color: $uh-warning;
}

.unlock-price {
	flex-shrink: 0;
	text-align: center;
	padding: 10rpx 20rpx;
	border-radius: $uh-radius-pill;
	background: $uh-accent-weak;
}

.unlock-price-num {
	font-size: 30rpx;
	font-weight: 700;
	color: $uh-accent;
}

.unlock-price-unit {
	display: block;
	font-size: 18rpx;
	color: $uh-accent;
}

.unlock-arrow {
	flex-shrink: 0;
}

.unlock-note {
	display: block;
	margin-top: 24rpx;
	text-align: center;
	font-size: 20rpx;
	color: $uh-ink-3;
}

/* 「回到顶部」改用 tm-flotbutton：定位与安全区由组件统一处理（与首页/瞬间页一致），
   这里不再自定义悬浮层——原来写死 bottom 会与自绘底栏的高度失配。 */
</style>
