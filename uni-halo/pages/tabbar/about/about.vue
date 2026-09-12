<template>
	<view class="profile-page">
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
						<tm-icons v-else name="icon-user" :size="52" color="#c0c4cc"></tm-icons>
						<view v-if="isLoggedIn" class="online-dot"></view>
					</view>
					<view class="uc-info">
						<text class="uc-name">{{ isLoggedIn ? calcUserNickname : '点击登录' }}</text>
						<text v-if="isLoggedIn && calcLevelText" class="uc-level">{{ calcLevelText }}</text>
						<text class="uc-desc">{{ isLoggedIn ? '点击编辑资料' : '登录后享受更多功能' }}</text>
					</view>
					<tm-icons v-if="!isLoggedIn" name="icon-angle-right" :size="22" color="#c0c4cc"></tm-icons>
				</view>

				<!-- 快捷入口 -->
				<view class="quick-links">
					<view class="quick-link" @click="fnToIdentityCenter">
						<view class="ql-icon ql-icon--crown">👑</view>
						<view class="ql-main">
							<text class="ql-title">我的身份</text>
							<text class="ql-sub">等级 · 称号 · 装扮</text>
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
							<tm-icons name="icon-angle-right" :size="20" color="#909399"></tm-icons>
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

		<!-- ========== 2.6 开启盲盒（当前唯一的抽奖玩法） ========== -->
		<!-- 抽奖类活动要求公示全部奖品与中奖概率，因此概率直接印在每个奖品上，
		     不折叠、不跳转——公示与展示合一，避免「公示藏在二级页」的合规风险 -->
		<view v-if="calcPointsEnabled && luckyEnabled" class="section-card lucky-card">
			<view class="lk-head">
				<view class="lk-title-wrap">
					<text class="lk-emoji">🎁</text>
					<view>
						<text class="lk-title">开启盲盒</text>
						<text class="lk-sub">抽完即知结果，奖品由站长发放</text>
					</view>
				</view>
				<text class="lk-cost">{{ luckyCost }} 积分/次</text>
			</view>

			<scroll-view scroll-x class="lk-prizes-scroll" :show-scrollbar="false">
				<view class="lk-prizes">
					<view v-for="(prize, idx) in luckyPrizes" :key="idx" class="lk-prize">
						<text class="lk-prize-emoji">{{ prize.emoji }}</text>
						<text class="lk-prize-name">{{ prize.name }}</text>
						<text v-if="prize.probabilityText" class="lk-prize-prob">{{ prize.probabilityText }}</text>
					</view>
				</view>
			</scroll-view>

			<tm-button theme="orange" :block="true" :loading="luckyBoxShow" @click="fnOpenLucky">
				立即开启
			</tm-button>

			<!-- 抽奖记录与消耗明细（中奖信息、物流进度、每盒积分扣减都在这一页） -->
			<view class="lk-entry" hover-class="lk-entry-hover" @click="fnToLuckyRecords">
				<text class="lk-entry-icon">📜</text>
				<view class="lk-entry-main">
					<text class="lk-entry-title">抽奖记录与消耗明细</text>
					<text class="lk-entry-sub">中奖信息 · 发货进度 · 每次开盒消耗</text>
				</view>
				<text v-if="pendingClaimCount > 0" class="lk-entry-badge">{{ pendingClaimCount }}</text>
				<text class="lk-entry-arrow">›</text>
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
						<tm-icons name="icon-times" :size="28" color="#909399"></tm-icons>
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
						theme="bg-gradient-light-blue-accent"
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
						theme="bg-gradient-light-blue-accent"
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
		<!-- 开盒动画（与「积分兑换」页共用同一组件，交互完全一致） -->
		<lucky-box :show.sync="luckyBoxShow" @drawn="fnOnLuckyDrawn"></lucky-box>
	</view>
</template>

<script>
	import {
		checkHasAdminLogin,
		checkHasWxLogin,
		getWxLoginInfo,
		clearWxLoginInfo,
	} from '@/utils/auth.js';
	import { getProfileModifyState, performSaveWxProfile } from '@/utils/wxLogin.js';
	import { resolveMomentPublishPermission, canShowMomentPublish } from '@/utils/momentPublish.js';
	import { shouldPromptHaloBind, shouldShowHaloBindBoundInfo, isHaloBindFeatureEnabled, performUnbindHaloAccount } from '@/utils/haloUser.js';
	import { resolvePointsEnabled, performPointsSignIn, fetchPointsOverview, syncPointsAfterLogin, isPointsApiReady, getPointsApiMode, buildWeekSignDisplay, emitPointsUpdated } from '@/utils/points.js';
	import { applyThemeSkin, clearThemeSkin } from '@/utils/themeSkin.js';
import { avatarFrameClass } from '@/utils/outfitSkin.js';
import { cacheOwnOutfit, clearOwnOutfit } from '@/utils/ownOutfit.js';
	// #ifdef APP-PLUS
	import CheckAppUpdate from '@/uni_modules/uni-upgrade-center-app/utils/check-update';
	// #endif
	import { CheckWxUpdate } from '@/utils/update.js';
	import {
		NeedPluginIds,
		checkNeedPluginAvailable
	} from "@/utils/plugin.js"

	import tmButton from '@/tm-vuetify/components/tm-button/tm-button.vue';
	import tmIcons from '@/tm-vuetify/components/tm-icons/tm-icons.vue';
	import tmInput from '@/tm-vuetify/components/tm-input/tm-input.vue';
	import wxLoginDialog from '@/components/wx-login-dialog/wx-login-dialog.vue';
	import PointsToast from '@/components/points-toast/points-toast.vue';
	import LuckyBox from '@/components/lucky-box/lucky-box.vue';

	export default {
		components: {
			tmButton,
			tmIcons,
			tmInput,
			wxLoginDialog,
			PointsToast,
			LuckyBox,
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
				/** 盲盒：入口配置 + 开盒弹窗开关（开盒过程由 lucky-box 组件负责） */
				luckyEnabled: false,
				luckyCost: 20,
				luckyPrizes: [],
				luckyBoxShow: false,
				/** 我的中奖礼品（发放进度与物流），抽奖记录的唯一入口 */
				myClaims: [],
				pointsApiReady: null,
				pointsLocalMode: false,
					navList: [],
					levelStatus: null,
					outfitStatus: null,
					/** 「我的瞬间」入口是否展示：仅博主本人，判定口径与瞬间页一致 */
					canShowMyMoments: false,
				};
		},
		computed: {
			haloConfigs() {
				return this.$tm.vx.getters().getConfigs
			},
			/**
			 * 待处理的中奖单数量（待填写收货信息）。
			 *
			 * 中奖列表已移到「抽奖记录」页，这里只用于入口行上的角标提醒——
			 * 「待填写」是唯一需要用户操作的状态，漏掉会让礼品一直发不出去。
			 */
			pendingClaimCount() {
				return (this.myClaims || []).filter((claim) => claim && claim.status === 'pending').length;
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
			calcVotePluginEnabled() {
				return !!this.haloConfigs?.pluginIntegrations?.vote;
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
				return 'bg-gradient-light-blue-accent';
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
			// 盲盒配置属站点公开信息（未登录也能取），与登录态无关，启动时即拉
			this.fnLoadLucky();
			uni.$on('wx_login_status_changed', this.fnCheckLoginStatus);
			uni.$on('points_updated', this.fnOnPointsUpdated);
			uni.$on('points_config_ready', this.fnOnPointsConfigReady);
		},
		onShow() {
			this.fnSyncPointsApiStateFromCache();
			this.fnCheckLoginStatus();
			this.fnProbePayEnabled();
			this.fnRefreshIdentity();
			if (this.calcPointsEnabled && this.isLoggedIn) {
				this.fnRefreshPointsOverview({ silent: true });
			}
			// 从中奖填写页返回时刷新发放状态，避免列表显示过期进度
			this.fnLoadLuckyClaims();
		},
		onUnload() {
			uni.$off('wx_login_status_changed', this.fnCheckLoginStatus);
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
					this.fnLoadLucky();
					// 中奖记录需要登录态才能查，登录成功后补拉一次
					this.fnLoadLuckyClaims();
				}
				if (!this.isLoggedIn) {
					this.pointsBalance = 0;
					this.pointsTodayEarned = 0;
					this.signInStatus = null;
					this.pointsApiReady = null;
					this.pointsLocalMode = false;
					// 抽奖配置属于站点公开信息，不需要登录；此处重置开盒状态与中奖记录
					this.luckyBoxShow = false;
					this.myClaims = [];
				}
				// 登录态变化会影响博主判定，此处一并刷新「我的瞬间」入口
				this.fnRefreshMomentPermission();
			},

			/**
			 * 「我的瞬间」入口仅博主可见：发表瞬间的权限判定与瞬间页共用同一套逻辑
			 * （ownerOpenid / personalToken 对应用户 / 绑定 Halo 账号三种匹配）。
			 */
			async fnRefreshMomentPermission() {
				if (!this.isLoggedIn || this.calcAuditModeEnabled) {
					this.canShowMyMoments = false;
					return;
				}
				try {
					const permission = await resolveMomentPublishPermission(
						this.$httpApi.v2,
						this.haloConfigs,
						this.wxUserInfo || getWxLoginInfo()
					);
					this.canShowMyMoments = canShowMomentPublish(permission);
				} catch (err) {
					console.warn('[about] 判定瞬间发表权限失败:', err);
					this.canShowMyMoments = false;
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

			/**
			 * 拉取盲盒配置（开启才显示专区；失败静默，不影响本页其它功能）。
			 * 配置属站点公开信息，未登录也能拿到，因此不放在登录回调里。
			 */
			async fnLoadLucky() {
				try {
					const res = await this.$httpApi.v2.getLuckyConfig();
					const data = res?.data || res || {};
					if (!data?.enabled) {
						this.luckyEnabled = false;
						return;
					}
					this.luckyEnabled = true;
					this.luckyCost = Number(data.costPoints || 20);
					this.luckyPrizes = (Array.isArray(data.prizes) ? data.prizes : [])
						.filter((prize) => prize && prize.name)
						.map((prize) => {
							const raw = prize.probability;
							// 概率由服务端统一计算下发（与抽取实现同口径），前端只做展示；
							// 0 或缺失时不显示，避免出现「中奖概率 0%」这类误导文案
							const hasProbability = typeof raw === 'number' && isFinite(raw) && raw > 0;
							return {
								name: prize.name,
								emoji: prize.emoji || '🎁',
								desc: prize.desc || '',
								probabilityText: hasProbability ? `${raw}%` : ''
							};
						});
				} catch (err) {
					// 静默：接口不可用时不显示盲盒专区
					this.luckyEnabled = false;
				}
			},

			/** 我的中奖礼品（发放进度与物流），失败静默 */
			async fnLoadLuckyClaims() {
				if (!this.isLoggedIn) {
					this.myClaims = [];
					return;
				}
				try {
					const res = await this.$httpApi.v2.getMyLuckyClaims();
					const raw = Array.isArray(res) ? res : (res?.data || res || {});
					const list = Array.isArray(raw) ? raw : (Array.isArray(raw.items) ? raw.items : []);
					this.myClaims = list.map((item) => {
						const normalized = Object.assign({}, item);
						// 兼容新版 winningId / 旧接口 id 两种单号字段
						if (!normalized.winningId && item && item.id) {
							normalized.winningId = item.id;
						}
						return normalized;
					});
				} catch (err) {
					// 静默：拿不到中奖记录不影响页面其它部分
					this.myClaims = [];
				}
			},

			/** 抽奖记录页（中奖信息 + 消耗明细） */
			fnToLuckyRecords() {
				if (!this.fnEnsureLogin()) return;
				uni.navigateTo({
					url: '/pagesA/lucky-records/lucky-records',
					fail: () => uni.showToast({ title: '页面打开失败，请重试', icon: 'none' })
				});
			},

			/** 打开开盒弹窗（开盒过程与结果都在 lucky-box 组件内） */
			fnOpenLucky() {
				if (!this.fnEnsureLogin()) return;
				this.luckyBoxShow = true;
			},

			/**
			 * 开盒结果回调：同步余额并刷新中奖记录。
			 *
			 * 余额变化同时通过 emitPointsUpdated 广播，让其它页面（身份中心、积分明细）
			 * 不会残留过期余额——开盒是积分消耗点，这一步不能省。
			 */
			async fnOnLuckyDrawn(data) {
				if (data?.balance != null && this.isLoggedIn) {
					const balance = Number(data.balance);
					this.fnOnPointsUpdated({ balance });
					emitPointsUpdated({ balance });
				}
				await this.fnLoadLuckyClaims();
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
				this.fnRefreshMomentPermission();
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
					key: 'my-moments',
					title: '我的瞬间',
					icon: 'haloicon-wenzhang1',
					iconColor: 'blue',
					// 瞬间页已从 tabBar 摘除，博主在此进入发表/管理
					path: '/pages/tabbar/moments/moments',
					isAdmin: false,
					type: 'page',
					requireLogin: true,
					show: this.canShowMyMoments
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
					key: 'vote',
					title: '投票中心',
					icon: 'halocoloricon-edit',
					iconColor: 'orange',
					path: '/pagesA/votes/votes',
					isAdmin: false,
					type: 'page',
					requireLogin: true,
					show: !this.calcAuditModeEnabled && !!this.calcVotePluginEnabled,
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
							confirmColor: '#03a9f4'
						})
						.then(() => { uni.navigateTo({ url: '/pagesB/login/login' }); })
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
							content: '确定清除缓存？可能影响登录状态。',
							showCancel: true,
							cancelText: '否',
							cancelColor: '#999999',
							confirmText: '是',
							confirmColor: '#03a9f4'
						}).then(() => {
							uni.clearStorageSync();
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
	$bg: #f5f6fa;
	$card: #fff;
	$text1: #1a1a2e;
	$text2: #303133;
	$text3: #909399;

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
		background: linear-gradient(135deg, #667eea 0%, #764ba2 50%, #f093fb 100%);
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
		box-shadow: 0 4rpx 24rpx rgba(102, 126, 234, 0.15);
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
			border-color: #667eea;
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
		color: #667eea;
		background: rgba(102, 126, 234, 0.1);
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
		color: #303133;
	}

	.ql-sub {
		display: block;
		margin-top: 2rpx;
		font-size: 20rpx;
		color: #909399;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		max-width: 100%;
	}

	.ql-arrow {
		font-size: 30rpx;
		color: #c0c4cc;
		margin-left: 4rpx;
		flex-shrink: 0;
	}

	/* ===== 通用卡片 ===== */
	.section-card {
		margin: 16rpx 28rpx;
		background: $card;
		border-radius: 20rpx;
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
			background: #e5f4ff;
			color: #2aa2e0;
		}

		&.service-icon--green {
			background: #e5f7ec;
			color: #35a35f;
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
		color: #ff9800;
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
		/* 「每日翻牌」已下线（抽奖玩法收敛为盲盒），三宫格改两宫格 */
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
		background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
		border-radius: 999rpx;
		padding: 8rpx 24rpx;
		line-height: 1;

		&.is-done {
			background: #c9dcf6;
			color: #5a7fbe;
		}
	}

	/* ===== 开启盲盒专区（当前唯一的抽奖玩法） =====
	   用暖色渐变把这张卡与周围白卡区分开，视觉重心落在唯一玩法上；
	   正文保持深色以保证可读性（深色底 + 白字在浅色主题下观感更差）。 */
	.lucky-card {
		padding: 26rpx 28rpx 28rpx;
		background: linear-gradient(160deg, #fff8ec 0%, #ffeedd 100%);
		border: 2rpx solid #ffe0c2;
	}

	.lk-head {
		display: flex;
		align-items: center;
		justify-content: space-between;
	}

	.lk-title-wrap {
		display: flex;
		align-items: center;
		min-width: 0;
	}

	.lk-emoji {
		font-size: 48rpx;
		margin-right: 16rpx;
		line-height: 1;
	}

	.lk-title {
		display: block;
		font-size: 32rpx;
		font-weight: 700;
		color: #8a5a12;
	}

	.lk-sub {
		display: block;
		margin-top: 6rpx;
		font-size: 21rpx;
		color: #b08a56;
	}

	.lk-cost {
		flex-shrink: 0;
		font-size: 22rpx;
		font-weight: 600;
		color: #e8862b;
		background: rgba(255, 255, 255, 0.85);
		padding: 6rpx 16rpx;
		border-radius: 999rpx;
	}

	.lk-prizes-scroll {
		margin: 20rpx 0 6rpx;
		white-space: nowrap;
	}

	.lk-prizes {
		display: inline-flex;
		padding-bottom: 4rpx;
	}

	/* 奖品项：概率直接印在奖品上，公示与展示合一（抽奖类活动必须公示全部奖品与概率） */
	.lk-prize {
		display: flex;
		flex-direction: column;
		align-items: center;
		width: 132rpx;
		margin-right: 14rpx;
		padding: 16rpx 8rpx 12rpx;
		border-radius: 16rpx;
		background: rgba(255, 255, 255, 0.92);
		box-shadow: 0 4rpx 14rpx rgba(200, 140, 60, 0.08);
	}

	.lk-prize-emoji {
		font-size: 44rpx;
		line-height: 1.2;
	}

	.lk-prize-name {
		margin-top: 6rpx;
		font-size: 20rpx;
		color: #7a6a52;
		max-width: 100%;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.lk-prize-prob {
		margin-top: 8rpx;
		font-size: 19rpx;
		font-weight: 600;
		color: #e8862b;
		background: #fff3e0;
		padding: 2rpx 12rpx;
		border-radius: 999rpx;
	}

	/* ===== 抽奖记录入口行（中奖列表已移到该页，专区只留入口 + 待处理角标） ===== */
	.lk-entry {
		display: flex;
		align-items: center;
		margin-top: 22rpx;
		padding: 18rpx 20rpx;
		border-radius: 16rpx;
		background: rgba(255, 255, 255, 0.9);
	}

	.lk-entry-hover {
		background: rgba(255, 255, 255, 0.7);
	}

	.lk-entry-icon {
		font-size: 34rpx;
		margin-right: 14rpx;
	}

	.lk-entry-main {
		flex: 1;
		min-width: 0;
	}

	.lk-entry-title {
		display: block;
		font-size: 26rpx;
		font-weight: 600;
		color: #8a5a12;
	}

	.lk-entry-sub {
		display: block;
		margin-top: 4rpx;
		font-size: 20rpx;
		color: #b08a56;
	}

	.lk-entry-badge {
		flex-shrink: 0;
		min-width: 34rpx;
		height: 34rpx;
		line-height: 34rpx;
		text-align: center;
		margin-right: 8rpx;
		padding: 0 10rpx;
		border-radius: 999rpx;
		background: #e53935;
		color: #fff;
		font-size: 20rpx;
	}

	.lk-entry-arrow {
		flex-shrink: 0;
		font-size: 30rpx;
		color: #cbbba3;
	}

	/* 周签到点阵：7 个点横排一行。
	   注意：这条规则曾在上一次调整样式时被误删（删块时多切了一条），
	   丢掉 display:flex 后 7 个点会竖向堆叠、把卡片撑得很高。 */
	.points-week-row {
		display: flex;
		justify-content: space-between;
		margin-top: 14rpx;
		padding-top: 12rpx;
		border-top: 1rpx dashed #f0f0f0;
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
			color: #c0c4cc;
		}

		&.is-signed .points-week-dot {
			background: #ffb300;
		}

		&.is-signed .points-week-label {
			color: #ff9800;
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
		background: #fff;
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
		color: #909399;
		padding: 16rpx 24rpx;
		margin-bottom: 24rpx;
		border-radius: 12rpx;
		background: #f5f7fa;

		&.is-disabled {
			color: #e53935;
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
		color: #667eea;
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
		background: #f5f6fa;

		// 头像框（与个人中心右上角、评论列表同一套色值）
		&.frame-gold {
			border: 6rpx solid #f5c542;
			box-shadow: 0 0 18rpx rgba(245, 197, 66, 0.5);
		}

		&.frame-rainbow {
			border: 6rpx solid transparent;
			background: linear-gradient(#f5f6fa, #f5f6fa) padding-box,
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
		border-bottom: 1rpx solid #f0f0f0;
		margin-bottom: 8rpx;
	}

	.profile-nickname-label {
		width: 120rpx;
		font-size: 28rpx;
		color: #303133;
		flex-shrink: 0;
	}

	.profile-nickname-input {
		flex: 1;
		font-size: 28rpx;
		color: #303133;
		text-align: right;

		&[disabled] {
			color: #c0c4cc;
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
		background: #fff;
		border-radius: 24rpx;
		padding: 48rpx 40rpx 40rpx;
		box-shadow: 0 16rpx 48rpx rgba(0, 0, 0, 0.12);
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
		background: linear-gradient(135deg, rgba(102, 126, 234, 0.15), rgba(240, 147, 251, 0.15));
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
		color: #c0c4cc;
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
