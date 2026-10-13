<template>
	<view :class="skinClass" class="profile-page">
		<!-- ========== 1. 渐变头部 ========== -->
		<view class="profile-header">
			<view class="header-bg"></view>
			<view class="header-inner">
				<view class="status-bar" :style="{ height: statusBarHeight + 'px' }"></view>
				<view class="nav-bar flex-between flex-center">
					<view class="nav-side"></view>
					<text class="nav-title">个人中心</text>
					<view class="nav-side flex flex-center-end" @click="fnToSettings">
						<tm-icons name="haloicon-shezhi" :size="36" color="#fff"></tm-icons>
					</view>
				</view>

				<!-- 用户卡片 -->
				<view class="user-card" @click="fnHandleUserCard">
					<view class="uc-avatar" :class="[{ online: isLoggedIn }, calcAvatarFrameClass]">
						<image v-if="isLoggedIn && calcUserAvatar" :src="calcUserAvatar" mode="aspectFill"></image>
						<tm-icons v-else name="icon-user" :size="52" color="#c6c6c0"></tm-icons>
						<view v-if="isLoggedIn" class="online-dot"></view>
					</view>
					<view class="uc-info">
						<text class="uc-name">{{ isLoggedIn ? calcUserNickname : '点击登录' }}</text>
						<text v-if="isLoggedIn && calcLevelText" class="uc-level">{{ calcLevelText }}</text>
						<text class="uc-desc">{{ isLoggedIn ? '点击编辑资料' : '登录后享受更多功能' }}</text>
					</view>
					<tm-icons v-if="!isLoggedIn" name="icon-angle-right" :size="22" color="#c6c6c0"></tm-icons>
				</view>

				<!-- 快捷入口 -->
				<view class="quick-links">
					<view class="quick-link" @click="fnToIdentityCenter">
						<view class="ql-icon ql-icon--crown">👑</view>
						<view class="ql-main">
							<text class="ql-title">我的身份</text>
							<text class="ql-sub">等级 · 称号</text>
						</view>
						<text class="ql-arrow">›</text>
					</view>
					<view v-if="calcPayEnabled" class="quick-link" @click="fnToPointsRecharge">
						<view class="ql-icon ql-icon--gift">🛍️</view>
						<view class="ql-main">
							<text class="ql-title">权益直购</text>
							<text class="ql-sub">现金购买道具权益</text>
						</view>
						<text class="ql-arrow">›</text>
					</view>
				</view>
			</view>
		</view>

		<!-- ========== 2. 积分签到 ========== -->
		<view v-if="calcPointsEnabled" class="section-card points-card">
				<view class="points-card-head">
					<view class="points-card-title-wrap">
						<text class="points-card-title">我的积分</text>
						<text class="points-card-balance">{{ calcPointsBalance }}</text>
					</view>
					<view class="points-card-links">
						<!-- 权益直购入口已提到头部胶囊，此处只保留积分明细 -->
						<view class="points-card-link" @click="fnToPointsHistory">
							<text>明细</text>
							<tm-icons name="icon-angle-right" :size="20" color="#9a9a94"></tm-icons>
						</view>
					</view>
				</view>
				<view class="points-actions-grid">
					<view class="pa-card pa-card--sign" :class="{ 'is-done': calcSignedToday }" @click="fnHandleSignIn">
						<text class="pa-icon">📅</text>
						<text class="pa-title">今日签到</text>
						<text class="pa-tip">{{ calcSignedToday ? '已连续 ' + calcConsecutiveDays + ' 天' : '每天签到领积分' }}</text>
						<text class="pa-btn" :class="{ 'is-done': calcSignedToday }">{{ calcSignInButtonText }}</text>
					</view>
					<view class="pa-card pa-card--task" @click="fnToTaskCenter">
						<text class="pa-icon">✅</text>
						<text class="pa-title">每日任务</text>
						<text class="pa-tip">完成任务领积分</text>
						<text class="pa-btn">去完成</text>
					</view>
				</view>
			<view v-if="calcWeekRecords.length" class="points-week-row">
				<view
					v-for="(signed, idx) in calcWeekRecords"
					:key="'week-' + idx"
					class="points-week-item"
					:class="{ 'is-signed': signed, 'is-today': idx === calcWeekTodayIndex }"
				>
					<text class="points-week-dot"></text>
					<text class="points-week-label">{{ idx + 1 }}天</text>
				</view>
			</view>
		</view>

		<!-- ========== 3. 我的服务 ========== -->
		<view class="section-card">
			<view class="sc-title">我的服务</view>
			<view class="service-grid">
				<view
					v-for="(item, index) in mergedServiceList"
					:key="index"
					class="service-item"
					@click="fnOnServiceClick(item)"
				>
				<view class="service-icon" :class="'service-icon--' + (item.color || 'blue')">
					<text :class="[fnIconFontClass(item.icon), item.icon]"></text>
				</view>
					<text class="service-label">{{ item.text }}</text>
				</view>
			</view>
		</view>

		<!-- ========== 5. 版权 ========== -->
		<view v-if="copyrightConfig.enabled" class="copyright">
			<text>{{ copyrightConfig.content }}</text>
		</view>

		<!-- ========== 6. 微信登录弹窗 ========== -->
		<wx-login-dialog
			:show.sync="loginDialogShow"
			@login-ready="fnOnWxLoginReady"
			@success="fnOnLoginSuccess"
		></wx-login-dialog>
		<wx-login-dialog
			v-if="calcHaloBindFeatureEnabled"
			:show.sync="haloBindDialogShow"
			start-mode="haloBind"
			@login-ready="fnOnWxLoginReady"
			@success="fnOnHaloBindSuccess"
		></wx-login-dialog>

		<!-- ========== 7. 编辑资料（二级页） ========== -->
		<view v-if="profileDialogShow" class="profile-page-mask">
			<view class="profile-page-sheet">
				<view class="profile-page-header">
					<text class="profile-page-title">编辑资料</text>
					<view class="profile-page-close" @click="fnCloseProfileDialog">
						<tm-icons name="icon-times" :size="28" color="#9a9a94"></tm-icons>
					</view>
				</view>

				<view class="profile-modify-tip" :class="{ 'is-disabled': !calcProfileModifyState.ok }">
					<text>{{ calcProfileModifyState.hint }}</text>
				</view>

				<!-- #ifdef MP-WEIXIN -->
				<view class="profile-avatar-section">
					<button
						class="profile-avatar-btn"
						open-type="chooseAvatar"
						:disabled="!calcProfileModifyState.ok || profileSaving"
						@chooseavatar="fnOnProfileChooseAvatar"
					>
						<image
							:src="profileForm.avatarTempPath || profileForm.avatarUrl || calcDefaultAvatar"
							class="profile-avatar"
							:class="calcAvatarFrameClass"
							mode="aspectFill"
						></image>
						<text v-if="calcProfileModifyState.ok" class="profile-avatar-tip">点击更换头像</text>
					</button>
				</view>
				<view class="profile-nickname-field">
					<text class="profile-nickname-label">昵称</text>
					<input
						type="nickname"
						class="profile-nickname-input"
						v-model="profileForm.nickname"
						:disabled="!calcProfileModifyState.ok || profileSaving"
						placeholder="点击输入或选择微信昵称"
						@blur="fnOnNicknameBlur"
					/>
				</view>
				<!-- #endif -->

				<!-- #ifndef MP-WEIXIN -->
				<view class="profile-avatar-section">
					<image
						:src="profileForm.avatarUrl || calcDefaultAvatar"
						class="profile-avatar"
						:class="calcAvatarFrameClass"
						mode="aspectFill"
					></image>
				</view>
				<tm-input
					title="昵称"
					align="right"
					placeholder="请输入昵称"
					v-model="profileForm.nickname"
					:disabled="!calcProfileModifyState.ok || profileSaving"
				></tm-input>
				<!-- #endif -->

				<view class="profile-page-actions">
					<view v-if="calcHaloBindInfo.show" class="halo-bind-status">
						<tm-icons name="icon-check-circle" :size="28" color="#19be6b"></tm-icons>
						<text class="halo-bind-status__text">{{ calcHaloBindInfo.text }}</text>
					</view>
					<tm-button
						v-if="calcNeedsHaloBind"
						block
						theme="bg-gradient-indigo-accent"
						:shadow="4"
						@click="fnOpenHaloBindDialog"
					>关联 Halo 账号</tm-button>
					<tm-button
						v-if="calcHaloBindInfo.show"
						block
						theme="red"
						plan
						:fllowTheme="false"
						:shadow="0"
						:loading="haloUnbinding"
						@click="fnUnbindHaloAccount"
					>解绑 Halo 账号</tm-button>
					<tm-button
						block
						theme="bg-gradient-indigo-accent"
						:shadow="4"
						:loading="profileSaving"
						:disabled="!calcProfileModifyState.ok"
						@click="fnSaveProfile"
					>保存资料</tm-button>
					<tm-button
						block
						theme="red"
						plan
						:fllowTheme="false"
						:shadow="0"
						@click="fnHandleLogout"
					>退出登录</tm-button>
					<tm-button block plan theme="grey" :fllowTheme="false" :shadow="0" @click="fnCloseProfileDialog">返回</tm-button>
				</view>
			</view>
		</view>
		<points-toast></points-toast>
		<!-- 自绘底部导航：后台「底部导航」开关关闭时不渲染，仍用微信原生底栏 -->
		<uh-tabbar current="about"></uh-tabbar>
	</view>
</template>

<script>
	import uhTabbar from '@/components/uh-tabbar/uh-tabbar.vue';
	import {
		checkHasAdminLogin,
		checkHasWxLogin,
		getWxLoginInfo,
		clearWxLoginInfo,
	} from '@/utils/auth.js';
	import { getProfileModifyState, performSaveWxProfile } from '@/utils/wxLogin.js';
	import { fetchUserPublishStatus } from '@/utils/userPublish.js';
	import { shouldPromptHaloBind, shouldShowHaloBindBoundInfo, isHaloBindFeatureEnabled, performUnbindHaloAccount } from '@/utils/haloUser.js';
	import { resolvePointsEnabled, performPointsSignIn, fetchPointsOverview, syncPointsAfterLogin, isPointsApiReady, getPointsApiMode, buildWeekSignDisplay, emitPointsUpdated } from '@/utils/points.js';
	import { applyThemeSkin, clearThemeSkin } from '@/utils/themeSkin.js';
import { avatarFrameClass } from '@/utils/outfitSkin.js';
import { cacheOwnOutfit, clearOwnOutfit } from '@/utils/ownOutfit.js';
	// #ifdef APP-PLUS
	import CheckAppUpdate from '@/uni_modules/uni-upgrade-center-app/utils/check-update';
	// #endif
	import { CheckWxUpdate, clearVolatileStorage } from '@/utils/update.js';
	import {
		NeedPluginIds,
		checkNeedPluginAvailable
	} from "@/utils/plugin.js"

	import tmButton from '@/tm-vuetify/components/tm-button/tm-button.vue';
	import tmIcons from '@/tm-vuetify/components/tm-icons/tm-icons.vue';
	import tmInput from '@/tm-vuetify/components/tm-input/tm-input.vue';
	import wxLoginDialog from '@/components/wx-login-dialog/wx-login-dialog.vue';
	import PointsToast from '@/components/points-toast/points-toast.vue';

	export default {
		components: {
			tmButton,
			tmIcons,
			tmInput,
			wxLoginDialog,
			PointsToast,
			uhTabbar,
		},
			data() {
			const sys = uni.getSystemInfoSync();
			return {
				statusBarHeight: sys.statusBarHeight || 20,
				isLoggedIn: false,
				wxUserInfo: null,
				loginDialogShow: false,
				haloBindDialogShow: false,
				profileDialogShow: false,
				profileSaving: false,
				haloUnbinding: false,
				payApiEnabled: false,
				payProbeTime: 0,
				profileForm: {
					nickname: '',
					avatarUrl: '',
					avatarTempPath: ''
				},
				pointsBalance: 0,
				pointsTodayEarned: 0,
				signInStatus: null,
				signInLoading: false,
				pointsApiReady: null,
				pointsLocalMode: false,
					navList: [],
					levelStatus: null,
					outfitStatus: null,
					/**
					 * 「我的投稿」入口是否可见（仅后台选「仅能投稿或投过稿」时需要判定）。
					 *
					 * <p>必须声明在 data 里：未声明的实例属性没有响应性，serviceList 会把
					 * 首次算出的值永久缓存住（首页「写文章」踩过同一个坑）。</p>
					 */
					myArticlesEntryVisible: false,
					/** 是否已经查过一次「我有没有投过稿」，避免每次切回本页都多打一次列表接口 */
					myArticlesProbed: false,
				};
		},
		computed: {
			haloConfigs() {
				return this.$tm.vx.getters().getConfigs
			},
			wxAppConfig() {
				return this.haloConfigs?.wxAppConfig || {};
			},
			copyrightConfig() {
				return this.haloConfigs?.basicConfig?.copyrightConfig || { enabled: false, content: '' };
			},
			calcAuditModeEnabled() {
				return !!this.haloConfigs?.auditConfig?.auditModeEnabled
			},
			/**
			 * 「我的投稿」入口的显示范围，后台「用户投稿 → 「我的」页投稿记录入口」下发：
			 * always 所有人 / eligible 仅能投稿或投过稿的人 / hidden 都不显示。
			 * 键可能还没被站长保存过（插件未升级到带该配置项的版本），此时按 eligible 兜底——
			 * 入口不该因为一个缺失的配置项就暴露给所有访客。
			 */
			calcMyArticlesEntryMode() {
				const mode = this.haloConfigs?.userPublishConfig?.myArticlesEntry;
				return (mode === 'always' || mode === 'hidden') ? mode : 'eligible';
			},
			/**
			 * 「我的投稿」这一项是否展示。
			 * eligible 的判定放在服务端（能不能发由总开关/积分门槛/角色策略共同决定），
			 * 前端只用结论 myArticlesEntryVisible + 「已投过稿」的补判，不另拼一套条件。
			 * 审核模式一律隐藏：它是给审核员看的门面，不该出现投稿管理入口。
			 */
			calcShowMyArticlesEntry() {
				if (this.calcAuditModeEnabled) return false;
				const mode = this.calcMyArticlesEntryMode;
				if (mode === 'hidden') return false;
				if (mode === 'always') return true;
				return this.myArticlesEntryVisible;
			},
			calcLinksPluginEnabled() {
				return !!this.haloConfigs?.pluginIntegrations?.links;
			},
			postDetailConfig() {
				return this.haloConfigs?.basicConfig?.postDetailConfig || { showComment: true };
			},
			calcIsShowComment() {
				return this.postDetailConfig.showComment !== false;
			},
			calcDefaultAvatar() {
				return this.$utils.checkAvatarUrl(this.haloConfigs?.imagesConfig?.defaultAvatarUrl);
			},
			calcUserAvatar() {
				if (this.wxUserInfo && this.wxUserInfo.avatarUrl) {
					return this.wxUserInfo.avatarUrl;
				}
				return this.calcDefaultAvatar;
			},
			calcUserNickname() {
				if (this.wxUserInfo && this.wxUserInfo.nickname) {
					return this.wxUserInfo.nickname;
				}
				return '微信用户';
			},
			calcLevelText() {
				const status = this.levelStatus;
				if (!status || !status.enabled || !status.level) {
					return '';
				}
				return `Lv.${status.level}${status.levelTitle ? ' · ' + status.levelTitle : ''}`;
			},
			calcAvatarFrameClass() {
				return avatarFrameClass(this.outfitStatus?.avatarFrame);
			},
			calcProfileModifyState() {
				return getProfileModifyState(this.wxUserInfo, this.wxAppConfig);
			},
			calcHaloBindFeatureEnabled() {
				return isHaloBindFeatureEnabled(this.haloConfigs);
			},
			calcNeedsHaloBind() {
				return shouldPromptHaloBind(this.wxUserInfo, this.haloConfigs);
			},
			calcHaloBindInfo() {
				if (!shouldShowHaloBindBoundInfo(this.wxUserInfo, this.haloConfigs)) {
					return { show: false, text: '' };
				}
				return {
					show: true,
					text: `已关联 Halo 账号：${(this.wxUserInfo?.haloUserName || '').trim()}`
				};
			},
			calcPointsEnabled() {
				const pointsConfig = this.haloConfigs?.pointsConfig;
				return resolvePointsEnabled(pointsConfig)
					&& pointsConfig?.showInProfile !== false;
			},
			calcPayEnabled() {
				// 后端开关为唯一真相：配置（getConfigs）或实时探测 /pay/config 任一返回开启即显示
				return this.haloConfigs?.payConfig?.enabled === true || this.payApiEnabled;
			},
			/**
			 * 是否展示「我的订单 / 已解锁文章」两个入口。
			 *
			 * 订单只在开启权益直购后才会产生；已解锁文章只在开启付费内容后才会产生。
			 * 两者都关的站点把入口藏起来，否则用户点进去只能看到一个空页面。
			 */
			calcShowPayRelated() {
				return this.calcPayEnabled || this.haloConfigs?.contentConfig?.enabled === true;
			},
			calcPointsBalance() {
				if (!this.isLoggedIn) return '-';
				return this.pointsBalance;
			},
			calcConsecutiveDays() {
				return Number(this.signInStatus?.consecutiveDays || 0);
			},
			calcSignedToday() {
				return !!this.signInStatus?.signedToday;
			},
			calcWeekSignDisplay() {
				return buildWeekSignDisplay(this.signInStatus);
			},
			calcWeekRecords() {
				return this.calcWeekSignDisplay.records;
			},
			calcWeekTodayIndex() {
				return this.calcWeekSignDisplay.todayIndex;
			},
			calcSignInTip() {
				if (!this.isLoggedIn) return '登录后可签到领取积分';
				if (this.pointsApiReady === false) {
					return '服务端积分接口未生效，请联系管理员部署 /points/* 接口';
				}
				if (this.pointsApiReady !== true) return '积分信息加载中...';
				if (this.pointsLocalMode) {
					if (this.calcSignedToday) return `今日已获得 ${this.pointsTodayEarned || 0} 积分（临时本地，待服务端同步）`;
					return '服务端接口未就绪，当前为临时本地模式，清缓存会丢失积分';
				}
				if (this.calcSignedToday) return `今日已获得 ${this.pointsTodayEarned || 0} 积分`;
				const signPoints = this.haloConfigs?.pointsConfig?.rules?.signIn?.points || 10;
				return `签到可领 ${signPoints} 积分，浏览/评论/点赞也能赚积分`;
			},
			calcSignInButtonDisabled() {
				return !this.isLoggedIn
					|| this.calcSignedToday
					|| this.signInLoading
					|| this.pointsApiReady !== true;
			},
			calcSignInButtonTheme() {
				if (this.calcSignedToday || this.pointsApiReady !== true) return 'grey';
				return 'bg-gradient-indigo-accent';
			},
			calcSignInButtonShadow() {
				return this.calcSignInButtonTheme === 'grey' ? 0 : 2;
			},
			calcSignInButtonText() {
				if (this.calcSignedToday) return '已签到';
				if (this.pointsApiReady === false) return '接口未就绪';
				if (this.pointsLocalMode && this.pointsApiReady === true) return '签到领积分';
				if (this.pointsApiReady !== true) return '加载中...';
				return '签到领积分';
			},
			siteFunctionList() {
				return this.navList
					.filter(nav => nav.show)
					.map(nav => ({
						key: nav.key,
						text: nav.title,
						icon: nav.icon,
						color: nav.iconColor,
						path: nav.path,
						type: nav.type,
						openType: nav.openType,
						isAdmin: nav.isAdmin,
						requireLogin: nav.requireLogin,
					}));
			},
			// 我的服务 + 站点功能合并为一个区域
			mergedServiceList() {
				return [...this.serviceList, ...this.siteFunctionList];
			},
			serviceList() {
				const list = [];
				list.push({
					text: '邀请好友',
					icon: 'icon-paper-plane',
					color: 'green',
					path: '/pagesA/invite/invite',
					requireLogin: true
				});
				if (this.calcIsShowComment) {
					list.push({
						text: '我的评论',
						icon: 'haloicon-pinglun',
						color: 'light-blue',
						path: '/pagesA/user-activities/user-activities?type=comment',
						requireLogin: true
					});
				}
				list.push({
					text: '我的浏览',
					icon: 'haloicon-rizhi1',
					color: 'green',
					path: '/pagesA/user-activities/user-activities?type=browse',
					requireLogin: true
				}, {
					text: '我的点赞',
					icon: 'halocoloricon-attent',
					color: 'orange',
					path: '/pagesA/user-activities/user-activities?type=upvote',
						requireLogin: true
					});
			// 投稿记录：发布入口在首页「写文章」，这里放「我投过哪些 / 在哪篇删」
			// 图标用 icon-file 而不是 icon-edit：后者被 mp-html 的图标字体占了同名类
			// （\e7a0 被 \eb03 覆盖），渲染出来是空白
			// 显隐由后台「用户投稿 → 「我的」页投稿记录入口」控制，默认只给能投稿或投过稿的人看
			if (this.calcShowMyArticlesEntry) {
				list.push({
					text: '我的投稿',
					icon: 'icon-file',
					color: 'cyan',
					path: '/pagesA/my-articles/my-articles',
					requireLogin: true
				});
			}
				// 订单与已解锁文章只在付费内容/权益直购任一开启时才有意义，
				// 全关的站点不展示这两个入口，避免用户点进去看到空页面
				if (this.calcShowPayRelated) {
					list.push({
						text: '我的订单',
						icon: 'haloicon-rizhi1',
						color: 'purple',
						path: '/pagesA/my-orders/my-orders',
						requireLogin: true
					}, {
						text: '已解锁文章',
						icon: 'haloicon-wenzhang1',
						color: 'light-blue',
						path: '/pagesA/unlocked-articles/unlocked-articles',
						requireLogin: true
					});
				}
				list.push({
					text: '应用配置',
					icon: 'haloicon-shezhi',
					color: 'blue',
					action: 'settings'
				});
				return list;
			},
		},
		watch: {
			haloConfigs: {
				handler(val) {
					if (!val) return;
					this.fnGetNavList();
					if (this.calcPointsEnabled && this.isLoggedIn) {
						this.fnRefreshPointsOverview({ silent: true });
					}
				},
				deep: true,
				immediate: true,
			}
		},
		created() {
			this.fnSyncPointsApiStateFromCache();
			this.fnCheckLoginStatus();
			this.fnRefreshPointsData();
			uni.$on('wx_login_status_changed', this.fnOnLoginStatusChanged);
			uni.$on('points_updated', this.fnOnPointsUpdated);
			uni.$on('points_config_ready', this.fnOnPointsConfigReady);
		},
		onShow() {
			this.fnSyncPointsApiStateFromCache();
			this.fnCheckLoginStatus();
			this.fnProbePayEnabled();
			this.fnRefreshIdentity();
			this.fnRefreshMyArticlesEntry();
			if (this.calcPointsEnabled && this.isLoggedIn) {
				this.fnRefreshPointsOverview({ silent: true });
			}
		},
		onUnload() {
			uni.$off('wx_login_status_changed', this.fnOnLoginStatusChanged);
			uni.$off('points_updated', this.fnOnPointsUpdated);
			uni.$off('points_config_ready', this.fnOnPointsConfigReady);
		},
		onPullDownRefresh() {
			uni.$tm.vx.actions('config/fetchConfigs').finally(() => {
				this.fnRefreshPointsData();
			});
		},
		methods: {
			fnToSettings() {
				uni.navigateTo({ url: '/pagesA/setting/setting' });
			},

			/** 登录态变化：刷新登录信息与积分状态 */
			fnOnLoginStatusChanged(info) {
				this.fnCheckLoginStatus(info);
			},

			fnEnsureLogin() {
				if (this.isLoggedIn) return true;
				this.loginDialogShow = true;
				return false;
			},

			/** 按图标名选择字体基类：halocoloricon / haloicon 字体（common/icons）或 tm 通用 iconfont */
			fnIconFontClass(icon) {
				const name = String(icon || '');
				if (name.startsWith('halocoloricon')) {
					return 'halocoloricon';
				}
				if (name.startsWith('haloicon')) {
					return 'haloicon';
				}
				return 'iconfont';
			},

			fnOnServiceClick(e) {
				// 兼容 tm-grid 回调（e.data）与自定义网格直传 item
				const item = e?.data || e;
				if (!item) return;
				if (item.action === 'settings') {
					this.fnToSettings();
					return;
				}
				if (item.requireLogin && !this.fnEnsureLogin()) return;
				this.fnOnNav({
					type: item.type || (item.path ? 'page' : undefined),
					path: item.path,
					isAdmin: item.isAdmin,
					openType: item.openType,
					requireLogin: item.requireLogin,
				});
			},

			fnCheckLoginStatus() {
				const wasLoggedIn = this.isLoggedIn;
				this.isLoggedIn = checkHasWxLogin();
				this.wxUserInfo = this.isLoggedIn ? getWxLoginInfo() : null;
				if (this.isLoggedIn && !wasLoggedIn) {
					this.fnRefreshPointsData();
					if (this.calcPointsEnabled) {
						this.fnRefreshPointsOverview({ forceRemote: true });
					}
				}
				if (!this.isLoggedIn) {
					this.pointsBalance = 0;
					this.pointsTodayEarned = 0;
					this.signInStatus = null;
					this.pointsApiReady = null;
					this.pointsLocalMode = false;
				}
			},


			fnSyncPointsApiStateFromCache() {
				const ready = isPointsApiReady();
				if (ready === true) {
					this.pointsApiReady = true;
					this.pointsLocalMode = getPointsApiMode() === 'local';
				} else if (ready === false) {
					this.pointsApiReady = false;
					this.pointsLocalMode = false;
				}
			},

			fnOnPointsUpdated(payload = {}) {
				if (payload.balance != null) {
					this.pointsBalance = Number(payload.balance) || 0;
				}
				if (payload.todayEarned != null) {
					this.pointsTodayEarned = Number(payload.todayEarned) || 0;
				}
				if (payload.signInStatus) {
					this.signInStatus = payload.signInStatus;
				}
				if (payload.localMode != null) {
					this.pointsLocalMode = !!payload.localMode;
				}
			},

			fnOnPointsConfigReady() {
				if (this.isLoggedIn) {
					this.fnRefreshPointsOverview({ silent: isPointsApiReady() === true });
				}
			},

			async fnRefreshPointsOverview(options = {}) {
				if (!this.calcPointsEnabled || !this.isLoggedIn) return;
				const overview = options.forceRemote
					? await syncPointsAfterLogin(this.$httpApi.v2)
					: await fetchPointsOverview(this.$httpApi.v2);
				this.pointsApiReady = isPointsApiReady();
				this.pointsLocalMode = getPointsApiMode() === 'local';
				if (overview) {
					this.fnOnPointsUpdated({
						balance: overview.balance,
						todayEarned: overview.todayEarned,
						signInStatus: overview.signInStatus,
						localMode: overview.localMode
					});
				}
			},

			fnToPointsHistory() {
				if (!this.fnEnsureLogin()) return;
				uni.navigateTo({ url: '/pagesA/points-history/points-history' });
			},

			fnToIdentityCenter() {
				if (!this.fnEnsureLogin()) return;
				uni.navigateTo({ url: '/pagesA/identity/identity' });
			},

			/** 拉取等级/装扮状态（失败静默，不影响页面） */
			async fnRefreshIdentity() {
				if (!this.isLoggedIn) {
					this.levelStatus = null;
					this.outfitStatus = null;
					// 未登录状态不保留上一个账号的皮肤，恢复项目原有配色
					applyThemeSkin('');
					return;
				}
				try {
					const [levelRes, outfitRes] = await Promise.all([
						this.$httpApi.v2.getLevelStatus(),
						this.$httpApi.v2.getOutfitStatus()
					]);
					const levelData = levelRes?.data || levelRes || {};
					this.levelStatus = {
						enabled: levelData.enabled !== false,
						level: Number(levelData.level || 0),
						levelTitle: levelData.levelTitle || ''
					};
					const outfitData = outfitRes?.data || outfitRes || {};
					this.outfitStatus = {
						avatarFrame: outfitData.avatarFrame || '',
						nickFrame: outfitData.nickFrame || '',
						theme: outfitData.theme || ''
					};
					// 同步到本地装扮缓存：评论输入弹窗、充值页预览等页面同步读取，
					// 避免每个页面各发一次 getOutfitStatus。
					cacheOwnOutfit(this.outfitStatus);
					// 服务端 activeTheme 是权威来源，据此纠正启动时的本地缓存；
					// 本页是 tabBar 页，同时把 tabBar 选中色一并同步。
					applyThemeSkin(this.outfitStatus.theme);
				} catch (err) {
					this.levelStatus = null;
					this.outfitStatus = null;
					// 接口失败（如网络抖动）时不要动主题：本地缓存里是用户已购皮肤，
					// 误清会让用户以为买的东西没了。下次加载成功后自然会被服务端值纠正。
				}
			},

			fnToTaskCenter() {
				if (!this.fnEnsureLogin()) return;
				uni.navigateTo({ url: '/pagesA/task-center/task-center' });
			},

			/**
			 * 「我的投稿」入口的显隐判定（只有后台选「仅能投稿或投过稿」时才需要）。
			 *
			 * <p>能不能发一律以服务端结论为准（总开关、积分门槛、角色策略都在服务端算），
			 * 与首页「写文章」同口径；这里再补一次「我有没有投过稿」——积分门槛是波动的
			 * （发一篇扣 50 分、门槛 600 分），发过稿的人可能今天不达标了，但已投的文章
			 * 还得能编辑、能撤回，不能因为一次门槛波动就把入口收走。</p>
			 */
			async fnRefreshMyArticlesEntry() {
				if (this.calcMyArticlesEntryMode !== 'eligible' || this.calcAuditModeEnabled) {
					return;
				}
				if (!this.isLoggedIn) {
					this.myArticlesEntryVisible = false;
					this.myArticlesProbed = false;
					return;
				}
				const status = await fetchUserPublishStatus(this.$httpApi.v2);
				if (status && status.canPublishArticle && status.visible) {
					this.myArticlesEntryVisible = true;
					return;
				}
				// 查过一次就不重复查：本页是 tabBar 页会常驻，每次 onShow 都打列表接口不划算
				if (this.myArticlesProbed) return;
				this.myArticlesProbed = true;
				try {
					const res = await this.$httpApi.v2.getMyArticles();
					const items = (res?.data || res || {}).items;
					this.myArticlesEntryVisible = Array.isArray(items) && items.length > 0;
				} catch (err) {
					this.myArticlesEntryVisible = false;
				}
			},

			/**
			 * 实时探测后端虚拟支付开关（/pay/config），绕过 getConfigs 缓存/未保存问题。
			 * 后端开关未开启或插件为旧版时静默保持隐藏。
			 */
			async fnProbePayEnabled() {
				if (!this.calcPointsEnabled || !this.isLoggedIn) return;
				// 限频：60 秒内最多探测一次
				const now = Date.now();
				if (now - this.payProbeTime < 60000) return;
				this.payProbeTime = now;
				try {
					const res = await this.$httpApi.v2.getVirtualPayConfig();
					const body = res?.data || res || {};
					this.payApiEnabled = body.enabled === true;
				} catch (err) {
					this.payApiEnabled = false;
				}
			},

			fnToPointsRecharge() {
				if (!this.fnEnsureLogin()) return;
				uni.navigateTo({ url: '/pagesA/points-recharge/points-recharge' });
			},

			async fnHandleSignIn() {
				if (!this.fnEnsureLogin()) return;
				if (this.calcSignInButtonDisabled) return;
				this.signInLoading = true;
				const result = await performPointsSignIn(this.$httpApi.v2);
				this.signInLoading = false;
				this.pointsLocalMode = getPointsApiMode() === 'local';
				if (!result.ok) {
					if (result.reason && (result.reason.includes('未部署') || result.reason.includes('未就绪') || result.reason.includes('未开放'))) {
						this.pointsApiReady = false;
					}
					uni.showToast({ title: result.reason || '签到失败', icon: 'none' });
					return;
				}
				this.pointsApiReady = true;
				this.fnOnPointsUpdated({
					balance: result.balance,
					todayEarned: result.todayEarned,
					signInStatus: result.signInStatus,
					localMode: result.localMode
				});
				await this.fnRefreshPointsOverview({ silent: true });
			},


			fnHandleUserCard() {
				if (this.isLoggedIn) {
					this.profileForm.nickname = this.wxUserInfo?.nickname || this.calcUserNickname;
					this.profileForm.avatarUrl = this.wxUserInfo?.avatarUrl || this.calcUserAvatar;
					this.profileForm.avatarTempPath = '';
					this.profileDialogShow = true;
					return;
				}
				this.loginDialogShow = true;
			},

			fnOnWxLoginReady(userInfo) {
				this.wxUserInfo = userInfo;
				this.isLoggedIn = true;
			},

			fnOnLoginSuccess(userInfo) {
				this.wxUserInfo = userInfo;
				this.isLoggedIn = true;
				this.profileDialogShow = false;
				this.fnRefreshPointsData();
				this.fnRefreshMyArticlesEntry();
			},

			fnOpenHaloBindDialog() {
				this.profileDialogShow = false;
				this.$nextTick(() => {
					this.haloBindDialogShow = true;
				});
			},

			fnOnHaloBindSuccess(userInfo) {
				this.wxUserInfo = userInfo;
				this.isLoggedIn = true;
				this.profileDialogShow = false;
			},

			fnUnbindHaloAccount() {
				if (this.haloUnbinding) return;
				const haloUserName = (this.wxUserInfo?.haloUserName || '').trim();
				uni.$eShowModal({
					title: '解绑 Halo 账号',
					content: haloUserName
						? `确定解绑 Halo 账号「${haloUserName}」吗？解绑后可重新关联其他账号。`
						: '确定解绑当前 Halo 账号关联吗？解绑后可重新关联其他账号。',
					showCancel: true,
					cancelText: '取消',
					cancelColor: '#999999',
					confirmText: '解绑',
					confirmColor: '#e53935'
				}).then(async () => {
					this.haloUnbinding = true;
					const result = await performUnbindHaloAccount(this.$httpApi.v2);
					this.haloUnbinding = false;
					if (result.ok) {
						this.wxUserInfo = result.data;
						uni.showToast({ title: '已解绑', icon: 'success' });
						return;
					}
					uni.showToast({ title: result.reason || '解绑失败', icon: 'none' });
				}).catch(() => {});
			},

			fnCloseProfileDialog() {
				if (this.profileSaving) return;
				this.profileDialogShow = false;
			},

			fnOnNicknameBlur(e) {
				const val = e?.detail?.value;
				if (val) this.profileForm.nickname = val;
			},

			fnOnProfileChooseAvatar(e) {
				if (!this.calcProfileModifyState.ok) {
					return uni.showToast({ title: this.calcProfileModifyState.hint, icon: 'none' });
				}
				const avatarUrl = e?.detail?.avatarUrl;
				if (avatarUrl) {
					this.profileForm.avatarTempPath = avatarUrl;
				}
			},

			async fnSaveProfile() {
				if (!this.calcProfileModifyState.ok) {
					return uni.showToast({ title: this.calcProfileModifyState.hint, icon: 'none' });
				}

				const nickname = (this.profileForm.nickname || '').trim();
				if (!nickname) {
					return uni.showToast({ title: '请输入昵称', icon: 'none' });
				}

				const avatarChanged = !!this.profileForm.avatarTempPath;
				const nicknameChanged = nickname !== (this.wxUserInfo?.nickname || '').trim();
				if (!avatarChanged && !nicknameChanged) {
					return uni.showToast({ title: '资料未变更', icon: 'none' });
				}

				this.profileSaving = true;
				const result = await performSaveWxProfile(this.$httpApi.v2, {
					nickname,
					avatarTempPath: this.profileForm.avatarTempPath,
					currentAvatarUrl: avatarChanged ? '' : this.profileForm.avatarUrl
				});
				this.profileSaving = false;

				if (result.ok) {
					this.wxUserInfo = result.data;
					this.profileDialogShow = false;
					uni.showToast({ title: '资料已更新', icon: 'success' });
					return;
				}
				uni.showToast({ title: result.reason || '更新失败', icon: 'none' });
			},

			fnHandleLogout() {
				uni.$eShowModal({
					title: '提示',
					content: '确定要退出登录吗？',
					showCancel: true,
					cancelText: '取消',
					cancelColor: '#999999',
					confirmText: '退出',
					confirmColor: '#e53935'
				}).then(() => {
					clearWxLoginInfo();
					this.isLoggedIn = false;
					this.wxUserInfo = null;
					this.profileDialogShow = false;
					// 皮肤/装扮属于账号权益，登出后不应残留给下一个使用者
					clearThemeSkin();
					clearOwnOutfit();
					this.levelStatus = null;
					this.outfitStatus = null;
					// 「我的投稿」入口的判定结果同样属于上一个账号，登出后一并清掉
					this.myArticlesEntryVisible = false;
					this.myArticlesProbed = false;
					uni.showToast({ title: '已退出', icon: 'success' });
				}).catch(() => {});
			},

			async fnGetNavList() {
				let _isWx = false;
				// #ifdef MP-WEIXIN
				_isWx = true;
				// #endif
				this.navList = [{
					key: 'data-visual',
					title: '数据看板',
					icon: 'haloicon-rizhishenji',
					iconColor: 'light-blue',
					path: '/pagesA/data-visual/data-visual',
					isAdmin: false,
					type: 'page',
					requireLogin: true,
					// 数据看板始终显示（页面内部有插件未安装提示）
					show: true
				}, {
					key: 'archives',
					title: this.calcAuditModeEnabled ? '内容归档' : '文章归档',
					icon: 'halocoloricon-classify',
					iconColor: 'red',
					path: '/pagesA/archives/archives',
					isAdmin: false,
					type: 'page',
					show: true
				}, {
					key: 'friend-links',
					title: '友情链接',
					icon: 'haloicon-links',
					iconColor: 'blue',
					path: '/pagesA/friend-links/friend-links',
					isAdmin: false,
					type: 'page',
					show: !!this.calcLinksPluginEnabled,
				}, {
					key: 'disclaimers',
					title: '免责声明',
					icon: 'haloicon-wenzhang1',
					iconColor: 'grey',
					path: '/pagesA/disclaimers/disclaimers',
					isAdmin: false,
					type: 'page',
					show: !!this.haloConfigs?.basicConfig?.disclaimers?.enabled
				}, {
					key: 'session',
					title: '在线客服',
					icon: 'haloicon-a-pinglunxiaoxi-05',
					iconColor: 'cyan',
					path: null,
					isAdmin: false,
					type: 'page',
					openType: 'contact',
					show: _isWx
				}, {
					key: 'feedback',
					title: '意见反馈',
					icon: 'haloicon-pinglun',
					iconColor: 'light-blue',
					path: null,
					isAdmin: false,
					type: 'page',
					openType: 'feedback',
					show: _isWx
				}, {
					key: 'about',
					title: '关于项目',
					icon: 'haloicon-caidan',
					iconColor: 'blue',
					path: '/pagesA/about/about',
					isAdmin: false,
					type: 'page',
					show: !!this.haloConfigs?.basicConfig?.showAboutSystem
				}];
			},

			/**
			 * 刷新本页的积分数据（登录后、下拉刷新、登录成功时调用）。
			 *
			 * 原实现同时拉取站点统计（文章/访客/分类/评论数），该统计区块已按产品决定移除，
			 * 因此这里只剩积分概览与下拉刷新收尾；方法名相应调整以免误导。
			 */
			fnRefreshPointsData() {
				if (!this.isLoggedIn) {
					uni.stopPullDownRefresh();
					return;
				}
				this.fnRefreshPointsOverview({ silent: isPointsApiReady() === true })
					.finally(() => { uni.stopPullDownRefresh(); });
			},

			fnOnNav(data) {
				if (!data) return;
				const { type, path, isAdmin, openType, requireLogin } = data;
				if (openType) return;
				if (requireLogin && !this.fnEnsureLogin()) return;
				if (type === 'tabbar') {
					// tabBar 页面必须用 switchTab：navigateTo 会直接失败
					if (!path) {
						uni.showToast({ title: '页面地址无效', icon: 'none' });
						return;
					}
					uni.switchTab({
						url: path,
						fail: (err) => {
							console.error('[about] tab 跳转失败', path, err);
						}
					});
					return;
				}

				if (type === 'page') {
					if (!path) {
						uni.showToast({ title: '页面地址无效', icon: 'none' });
						return;
					}
					if (isAdmin && !checkHasAdminLogin()) {
						uni.$eShowModal({
							title: '提示',
							content: '未登录超管账号或登录状态已过期，是否立即登录？',
							showCancel: true,
							cancelText: '否',
							cancelColor: '#999999',
							confirmText: '是',
							confirmColor: '#2F54EB'
						})
						.then(() => {
							// 小程序端没有独立的超管登录页（pagesB 分包不存在，跳过去是静默失败）：
							// 走微信登录获得身份，管理员身份由绑定 Halo 账号承载
							uni.navigateTo({
								url: '/pagesA/setting/setting',
								fail: () => uni.showToast({ title: '请先在设置里完成登录', icon: 'none' })
							});
						})
						.catch(() => {});
						return;
					}
					uni.navigateTo({
						url: path,
						fail: (err) => {
							console.error('[about] 页面跳转失败', path, err);
							uni.showToast({ title: '页面打开失败', icon: 'none' });
						}
					});
					return;
				}

				if (type === 'poup') {
					if (path === 'clear') {
						uni.$eShowModal({
							title: '提示',
							content: '确定清除本地缓存？登录状态与个人设置会保留。要重置设置请用「应用设置」里的恢复默认。',
							showCancel: true,
							cancelText: '否',
							cancelColor: '#999999',
							confirmText: '是',
							confirmColor: '#2F54EB'
						}).then(() => {
							// 只清易失缓存：原来的一把清空会连登录态与设置一起清掉，
							// 用户以为在「清缓存」，实际被登出、设置也回到默认（见 utils/update.js 的说明）
							clearVolatileStorage();
							uni.showToast({ icon: 'none', title: '缓存已清理' });
							this.fnCheckLoginStatus();
						}).catch(() => {});
					} else if (path === 'update') {
						// #ifdef APP-PLUS
						CheckAppUpdate();
						// #endif
						// #ifdef MP-WEIXIN
						CheckWxUpdate(true);
						// #endif
						// #ifndef APP-PLUS|| MP-WEIXIN
						uni.showToast({ icon: 'none', title: '版本无需更新！' });
						// #endif
					}
				}
			},
		}
	};
</script>

<style scoped lang="scss">
	$bg: $uh-bg;
	$card: $uh-card;
	$text1: $uh-ink-1;
	$text2: $uh-ink-1;
	$text3: $uh-ink-3;

	.profile-page {
		min-height: 100vh;
		background: $bg;
		padding-bottom: env(safe-area-inset-bottom);
	}

	/* ===== 头部：渐变背景撑满容器，不再溢出 ===== */
	.profile-header {
		position: relative;
		overflow: hidden;
		padding-bottom: 24rpx;
	}

	.header-bg {
		position: absolute;
		top: 0;
		left: 0;
		right: 0;
		bottom: 0;
		background-image: $uh-grad-accent;
		border-radius: 0 0 40rpx 40rpx;

		&::after {
			content: '';
			position: absolute;
			inset: 0;
			background:
				radial-gradient(circle at 20% 30%, rgba(255, 255, 255, .12) 0%, transparent 50%),
				radial-gradient(circle at 80% 55%, rgba(255, 255, 255, .06) 0%, transparent 40%);
		}
	}

	.header-inner {
		position: relative;
		z-index: 1;
	}

	.nav-bar {
		height: 88rpx;
		padding: 0 32rpx;
	}

	.nav-side {
		width: 80rpx;
		flex-shrink: 0;
	}

	.nav-title {
		flex: 1;
		text-align: center;
		font-size: 36rpx;
		font-weight: 700;
		color: #fff;
	}

	/* ===== 用户卡片 ===== */
	.user-card {
		display: flex;
		align-items: center;
		margin: 16rpx 28rpx 0;
		padding: 28rpx;
		background: $card;
		border-radius: 20rpx;
		box-shadow: 0 4rpx 24rpx rgba(47, 84, 235, 0.15);
	}

	.uc-avatar {
		position: relative;
		flex-shrink: 0;
		width: 100rpx;
		height: 100rpx;
		border-radius: 50%;
		background: #f0f0f5;
		border: 3rpx solid #e8e8ee;
		display: flex;
		align-items: center;
		justify-content: center;
		overflow: hidden;

		&.online {
			border-color: $uh-accent;
		}

		&.frame-gold {
			border: 5rpx solid #f5c542;
			box-shadow: 0 0 16rpx rgba(245, 197, 66, 0.5);
		}

		&.frame-rainbow {
			border: 5rpx solid transparent;
			background: linear-gradient(#f0f0f5, #f0f0f5) padding-box,
				linear-gradient(135deg, #f79ea3, #9b8cff, #79d6f2) border-box;
			box-shadow: 0 0 16rpx rgba(155, 140, 255, 0.4);
		}

		&.frame-silver {
			border: 5rpx solid #c3cbd8;
			box-shadow: 0 0 16rpx rgba(195, 203, 216, 0.5);
		}

		image {
			width: 100%;
			height: 100%;
		}
	}

	.online-dot {
		position: absolute;
		right: 4rpx;
		bottom: 4rpx;
		width: 20rpx;
		height: 20rpx;
		border-radius: 50%;
		background: #43a047;
		border: 3rpx solid #fff;
	}

	.uc-info {
		flex: 1;
		margin-left: 24rpx;
		min-width: 0;
	}

	.uc-name {
		display: block;
		font-size: 34rpx;
		font-weight: 700;
		color: $text1;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.uc-level {
		display: inline-block;
		font-size: 20rpx;
		font-weight: 600;
		color: $uh-accent;
		background: rgba(47, 84, 235, 0.1);
		border-radius: 999rpx;
		padding: 2rpx 14rpx;
		margin-top: 8rpx;
	}

	.uc-desc {
		display: block;
		font-size: 24rpx;
		color: $text3;
		margin-top: 4rpx;
	}

	/* ===== 快捷入口 ===== */
	.quick-links {
		display: flex;
		gap: 16rpx;
		margin: 24rpx 28rpx 0;
	}

	.quick-link {
		flex: 1;
		min-width: 0;
		display: flex;
		align-items: center;
		gap: 16rpx;
		padding: 20rpx 22rpx;
		border-radius: 18rpx;
		background: rgba(255, 255, 255, 0.95);
		box-shadow: 0 6rpx 18rpx rgba(31, 41, 55, 0.12);
	}

	.ql-icon {
		width: 68rpx;
		height: 68rpx;
		border-radius: 20rpx;
		display: flex;
		align-items: center;
		justify-content: center;
		font-size: 36rpx;
		flex-shrink: 0;

		&--crown {
			background: #fff2d6;
		}

		&--gift {
			background: #ffe9e0;
		}
	}

	.ql-main {
		flex: 1;
		min-width: 0;
	}

	.ql-title {
		display: block;
		font-size: 26rpx;
		font-weight: 700;
		color: $uh-ink-1;
	}

	.ql-sub {
		display: block;
		margin-top: 2rpx;
		font-size: 20rpx;
		color: $uh-ink-3;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		max-width: 100%;
	}

	.ql-arrow {
		font-size: 30rpx;
		color: $uh-ink-4;
		margin-left: 4rpx;
		flex-shrink: 0;
	}

	/* ===== 通用卡片 ===== */
	.section-card {
		margin: 16rpx 28rpx;
		background: $card;
		border-radius: $uh-radius-lg;
		box-shadow: $uh-shadow-sm;
		overflow: hidden;
	}

	.sc-title {
		font-size: 28rpx;
		font-weight: 600;
		color: $text2;
		padding: 24rpx 28rpx 0;
	}

	/* ===== 我的服务 ===== */
	.service-grid {
		display: grid;
		grid-template-columns: repeat(4, 1fr);
		gap: 12rpx 8rpx;
		padding: 20rpx 16rpx 24rpx;
	}

	.service-item {
		display: flex;
		flex-direction: column;
		align-items: center;
		padding: 10rpx 0;
		min-width: 0;
	}

	.service-icon {
		width: 84rpx;
		height: 84rpx;
		border-radius: 24rpx;
		display: flex;
		align-items: center;
		justify-content: center;
		font-size: 44rpx;

		.haloicon {
			font-size: 44rpx;
		}

		&.service-icon--blue {
			background: #e5efff;
			color: #4a7de0;
		}

		&.service-icon--light-blue {
			background: $uh-accent-weak;
			color: $uh-accent;
		}

		&.service-icon--green {
			background: #e5f7ec;
			color: #35a35f;
		}

		/* 「我的投稿」用的青色调（原来这一项写的是 cyan，但这里没有对应样式，
		   图标会是裸字形、没有底色——补上） */
		&.service-icon--cyan {
			background: #e2f4f7;
			color: #1a93a8;
		}

		&.service-icon--orange {
			background: #fff0e0;
			color: #e8862b;
		}

		&.service-icon--purple {
			background: #f1eaff;
			color: #8a63d2;
		}

		&.service-icon--red {
			background: #ffe9e9;
			color: #e05555;
		}

		&.service-icon--grey {
			background: #f0f1f5;
			color: #7b8290;
		}
	}

	.service-label {
		display: block;
		margin-top: 12rpx;
		font-size: 22rpx;
		color: $text2;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		max-width: 100%;
	}

	/* ===== 积分签到 ===== */
	.points-card {
		padding: 20rpx 24rpx 22rpx;
	}

	.points-card-head {
		display: flex;
		align-items: center;
		justify-content: space-between;
		margin-bottom: 20rpx;
	}

	.points-card-title-wrap {
		display: flex;
		align-items: baseline;
		gap: 16rpx;
	}

	.points-card-title {
		font-size: 28rpx;
		font-weight: 600;
		color: $text2;
	}

	.points-card-balance {
		font-size: 40rpx;
		font-weight: 700;
		color: $uh-warning;
	}

	.points-card-links {
		display: flex;
		align-items: center;
		gap: 20rpx;
	}

	.points-card-link {
		display: flex;
		align-items: center;
		gap: 4rpx;
		font-size: 24rpx;
		color: $text3;
	}

	.points-actions-grid {
		display: grid;
		/* 三宫格改两宫格 */
		grid-template-columns: repeat(2, 1fr);
		gap: 12rpx;
		margin-top: 14rpx;
	}

	.pa-card {
		display: flex;
		flex-direction: column;
		align-items: center;
		padding: 16rpx 8rpx 14rpx;
		border-radius: 16rpx;
		border: 2rpx solid transparent;
		text-align: center;

		&.pa-card--sign {
			background: linear-gradient(160deg, #eef4ff 0%, #e8f0ff 100%);
			border-color: #d6e4ff;

			&.is-done {
				background: linear-gradient(160deg, #eef8ea 0%, #e2f2dd 100%);
				border-color: #cdeec1;
			}
		}

		&.pa-card--task {
			background: linear-gradient(160deg, #f3f0ff 0%, #ece7ff 100%);
			border-color: #ddd4fb;
		}
	}

	.pa-icon {
		font-size: 34rpx;
		line-height: 1.2;
	}

	.pa-title {
		display: block;
		margin-top: 6rpx;
		font-size: 25rpx;
		font-weight: 700;
		color: $text2;
	}

	.pa-tip {
		display: block;
		margin-top: 4rpx;
		font-size: 20rpx;
		color: $text3;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		max-width: 100%;
	}

	.pa-btn {
		margin-top: 10rpx;
		font-size: 22rpx;
		font-weight: 600;
		color: #ffffff;
		background-image: $uh-grad-accent;
		border-radius: 999rpx;
		padding: 8rpx 24rpx;
		line-height: 1;

		&.is-done {
			background: #c9dcf6;
			color: #5a7fbe;
		}
	}

	/* 周签到点阵：7 个点横排一行。
	   注意：这条规则曾在上一次调整样式时被误删（删块时多切了一条），
	   丢掉 display:flex 后 7 个点会竖向堆叠、把卡片撑得很高。 */
	.points-week-row {
		display: flex;
		justify-content: space-between;
		margin-top: 14rpx;
		padding-top: 12rpx;
		border-top: 1rpx dashed $uh-line;
	}

	.points-week-item {
		flex: 1;
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 8rpx;

		.points-week-dot {
			width: 18rpx;
			height: 18rpx;
			border-radius: 50%;
			background: #e0e0e0;
		}

		.points-week-label {
			font-size: 20rpx;
			color: $uh-ink-4;
		}

		&.is-signed .points-week-dot {
			background: #ffb300;
		}

		&.is-signed .points-week-label {
			color: $uh-warning;
		}

		&.is-today .points-week-dot {
			box-shadow: 0 0 0 4rpx rgba(255, 179, 0, 0.25);
		}
	}

	/* ===== 站点功能网格（与我的服务一致） ===== */
	.func-grid {
		display: flex;
		flex-wrap: wrap;
		padding: 8rpx 0 16rpx;
	}

	.func-grid-item {
		width: 25%;
	}

	.func-grid-cell {
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		height: 140rpx;
		padding: 0 10rpx;
		box-sizing: border-box;

		&--active,
		&:active {
			opacity: 0.72;
		}
	}

	.func-grid-icon-wrap {
		display: flex;
		align-items: center;
		justify-content: center;
		padding-bottom: 6rpx;
	}

	.func-grid-text {
		font-size: 24rpx;
		color: #424242;
		text-align: center;
		line-height: 1.3;
	}

	.func-grid-btn {
		background: transparent;
		border: none;
		padding: 0;
		margin: 0;
		line-height: normal;
		width: 100%;

		&::after {
			border: none;
		}
	}

	/* ===== 编辑资料（二级页） ===== */
	.profile-page-mask {
		position: fixed;
		top: 0;
		left: 0;
		right: 0;
		bottom: 0;
		z-index: 1000;
		background: rgba(0, 0, 0, 0.45);
		display: flex;
		align-items: flex-end;
		box-sizing: border-box;
	}

	.profile-page-sheet {
		width: 100%;
		background: $uh-card;
		border-radius: 32rpx 32rpx 0 0;
		padding: 32rpx 40rpx calc(40rpx + env(safe-area-inset-bottom));
		box-sizing: border-box;
		max-height: 88vh;
		overflow-y: auto;
	}

	.profile-page-header {
		display: flex;
		align-items: center;
		justify-content: center;
		position: relative;
		margin-bottom: 24rpx;
	}

	.profile-page-title {
		font-size: 34rpx;
		font-weight: 700;
		color: $text1;
	}

	.profile-page-close {
		position: absolute;
		right: 0;
		top: 50%;
		transform: translateY(-50%);
		padding: 8rpx;
	}

	.profile-modify-tip {
		text-align: center;
		font-size: 24rpx;
		color: $uh-ink-3;
		padding: 16rpx 24rpx;
		margin-bottom: 24rpx;
		border-radius: 12rpx;
		background: #f5f7fa;

		&.is-disabled {
			color: $uh-danger;
			background: #ffebee;
		}
	}

	.profile-avatar-section {
		display: flex;
		flex-direction: column;
		align-items: center;
		margin-bottom: 32rpx;
	}

	.profile-avatar-btn {
		display: flex;
		flex-direction: column;
		align-items: center;
		background: transparent;
		border: none;
		padding: 0;
		margin: 0;
		line-height: normal;

		&::after {
			border: none;
		}

		&[disabled] {
			opacity: 0.6;
		}
	}

	.profile-avatar-tip {
		margin-top: 12rpx;
		font-size: 24rpx;
		color: $uh-accent;
	}

	.profile-page-actions {
		display: flex;
		flex-direction: column;
		gap: 16rpx;
		margin-top: 40rpx;
	}

	.halo-bind-status {
		display: flex;
		align-items: center;
		justify-content: center;
		gap: 12rpx;
		padding: 20rpx 24rpx;
		border-radius: 16rpx;
		background: #f0f9eb;
	}

	.halo-bind-status__text {
		font-size: 26rpx;
		color: #19be6b;
		line-height: 1.5;
	}

	.profile-avatar {
		width: 128rpx;
		height: 128rpx;
		border-radius: 50%;
		background: $uh-fill;

		// 头像框（与个人中心右上角、评论列表同一套色值）
		&.frame-gold {
			border: 6rpx solid #f5c542;
			box-shadow: 0 0 18rpx rgba(245, 197, 66, 0.5);
		}

		&.frame-rainbow {
			border: 6rpx solid transparent;
			background: linear-gradient($uh-fill, $uh-fill) padding-box,
				linear-gradient(135deg, #f79ea3, #9b8cff, #79d6f2) border-box;
			box-shadow: 0 0 18rpx rgba(155, 140, 255, 0.4);
		}

		&.frame-silver {
			border: 6rpx solid #c3cbd8;
			box-shadow: 0 0 18rpx rgba(195, 203, 216, 0.5);
		}
	}

	.profile-avatar--placeholder {
		display: flex;
		align-items: center;
		justify-content: center;
	}

	.profile-nickname-field {
		display: flex;
		align-items: center;
		padding: 24rpx 0;
		border-bottom: 1rpx solid $uh-line;
		margin-bottom: 8rpx;
	}

	.profile-nickname-label {
		width: 120rpx;
		font-size: 28rpx;
		color: $uh-ink-1;
		flex-shrink: 0;
	}

	.profile-nickname-input {
		flex: 1;
		font-size: 28rpx;
		color: $uh-ink-1;
		text-align: right;

		&[disabled] {
			color: $uh-ink-4;
		}
	}
	.copyright {
		padding: 48rpx 0 60rpx;
		text-align: center;
		font-size: 22rpx;
		color: #c0c4c7;
	}

	/* ===== 登录弹窗 ===== */
	.login-mask {
		position: fixed;
		top: 0;
		left: 0;
		right: 0;
		bottom: 0;
		z-index: 999;
		background: rgba(0, 0, 0, 0.45);
		display: flex;
		align-items: center;
		justify-content: center;
		padding: 48rpx;
		box-sizing: border-box;
	}

	.login-dialog {
		position: relative;
		width: 100%;
		max-width: 620rpx;
		background: $uh-card;
		border-radius: 24rpx;
		padding: 48rpx 40rpx 40rpx;
		box-shadow: $uh-shadow-md;
		box-sizing: border-box;
	}

	.login-dialog-close {
		position: absolute;
		top: 24rpx;
		right: 24rpx;
		padding: 8rpx;
	}

	.login-dialog-icon {
		width: 112rpx;
		height: 112rpx;
		margin: 0 auto 24rpx;
		border-radius: 50%;
		background-image: $uh-grad-accent;
		display: flex;
		align-items: center;
		justify-content: center;
	}

	.login-dialog-title {
		display: block;
		text-align: center;
		font-size: 36rpx;
		font-weight: 700;
		color: $text1;
		margin-bottom: 24rpx;
	}

	.login-dialog-body {
		margin-bottom: 32rpx;
	}

	.login-dialog-desc {
		display: block;
		text-align: center;
		font-size: 26rpx;
		color: $text3;
		line-height: 1.6;
	}

	.login-dialog-hint {
		display: block;
		text-align: center;
		font-size: 24rpx;
		color: $uh-ink-4;
		line-height: 1.6;
		margin-top: 16rpx;
	}

	.login-dialog-status {
		display: block;
		text-align: center;
		font-size: 30rpx;
		font-weight: 600;
		color: $text2;
		margin-top: 24rpx;
	}

	.login-dialog-loading {
		display: flex;
		flex-direction: column;
		align-items: center;
		padding: 16rpx 0 8rpx;
	}

	.login-tip-box {
		display: flex;
		align-items: flex-start;
		padding: 24rpx;
		border-radius: 16rpx;
		background: #fff8e1;
	}

	.login-tip-warn {
		background: #fff3e0;
	}

	.login-tip-text {
		flex: 1;
		margin-left: 16rpx;
		font-size: 26rpx;
		color: #e65100;
		line-height: 1.6;
	}

	.login-benefits {
		display: flex;
		justify-content: center;
		gap: 48rpx;
		margin-top: 32rpx;
	}

	.login-benefit-item {
		display: flex;
		flex-direction: column;
		align-items: center;

		text {
			font-size: 22rpx;
			color: $text3;
			margin-top: 8rpx;
		}
	}

	.login-dialog-actions {
		display: flex;
		flex-direction: column;
		gap: 16rpx;
	}

	.login-cancel-btn {
		margin-top: 0 !important;
	}

	/* ===== 版权 ===== */
</style>
