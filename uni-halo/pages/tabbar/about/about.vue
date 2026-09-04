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
					<view class="uc-avatar" :class="{ online: isLoggedIn }">
						<image v-if="isLoggedIn && calcUserAvatar" :src="calcUserAvatar" mode="aspectFill"></image>
						<tm-icons v-else name="icon-user" :size="52" color="#c0c4cc"></tm-icons>
						<view v-if="isLoggedIn" class="online-dot"></view>
					</view>
					<view class="uc-info">
						<text class="uc-name">{{ isLoggedIn ? calcUserNickname : '点击登录' }}</text>
						<text class="uc-desc">{{ isLoggedIn ? '点击编辑资料' : '登录后享受更多功能' }}</text>
					</view>
					<tm-icons v-if="!isLoggedIn" name="icon-angle-right" :size="22" color="#c0c4cc"></tm-icons>
				</view>
			</view>
		</view>

		<!-- ========== 2. 数据统计 ========== -->
		<view class="section-card stats-card">
			<view
				v-for="item in statList"
				:key="item.key"
				class="stat-item"
				@click="fnOnStatClick(item)"
			>
				<view class="stat-badge" :class="item.bgClass">
					<tm-flop
						v-if="isLoggedIn"
						:end-val="item.value"
						:duration="1200"
						class="stat-num"
						:class="'text-' + item.color"
					></tm-flop>
					<text v-else class="stat-num stat-num--placeholder">-</text>
				</view>
				<text class="stat-label">{{ item.label }}</text>
			</view>
		</view>

		<!-- ========== 2.5 积分签到 ========== -->
		<view v-if="calcPointsEnabled" class="section-card points-card">
			<view class="points-card-head">
				<view class="points-card-title-wrap">
					<text class="points-card-title">我的积分</text>
					<text class="points-card-balance">{{ calcPointsBalance }}</text>
				</view>
				<view class="points-card-link" @click="fnToPointsHistory">
					<text>明细</text>
					<tm-icons name="icon-angle-right" :size="20" color="#909399"></tm-icons>
				</view>
			</view>
			<view class="points-sign-row">
				<view class="points-sign-info">
					<text class="points-sign-days">已连续签到 {{ calcConsecutiveDays }} 天</text>
					<text class="points-sign-tip">{{ calcSignInTip }}</text>
				</view>
				<tm-button
					size="m"
					:theme="calcSignInButtonTheme"
					:shadow="calcSignInButtonShadow"
					:loading="signInLoading"
					:disabled="calcSignInButtonDisabled"
					@click="fnHandleSignIn"
				>{{ calcSignInButtonText }}</tm-button>
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
			<tm-grid
				:list="serviceList"
				:grid="4"
				:height="140"
				:icon-size="48"
				font-color="grey-darken-3"
				@change="fnOnServiceClick"
			></tm-grid>
		</view>

		<!-- ========== 4. 站点功能 ========== -->
		<view class="section-card">
			<view class="sc-title">站点功能</view>
			<view class="func-grid">
				<view
					v-for="(item, index) in siteFunctionList"
					:key="item.key || index"
					class="func-grid-item"
				>
					<button
						v-if="item.openType"
						class="func-grid-btn"
						:open-type="item.openType"
					>
						<view class="func-grid-cell">
							<view class="func-grid-icon-wrap">
								<tm-icons :name="item.icon" :size="48" :color="item.color"></tm-icons>
							</view>
							<text class="func-grid-text">{{ item.text }}</text>
						</view>
					</button>
					<view
						v-else
						class="func-grid-cell"
						hover-class="func-grid-cell--active"
						:data-index="index"
						@click="fnOnSiteFunctionClick(index)"
					>
						<view class="func-grid-icon-wrap">
							<tm-icons :name="item.icon" :size="48" :color="item.color"></tm-icons>
						</view>
						<text class="func-grid-text">{{ item.text }}</text>
					</view>
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
						:shadow="0"
						@click="fnHandleLogout"
					>退出登录</tm-button>
					<tm-button block plan theme="grey" :shadow="0" @click="fnCloseProfileDialog">返回</tm-button>
				</view>
			</view>
		</view>
		<points-toast></points-toast>
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
	import { shouldPromptHaloBind, shouldShowHaloBindBoundInfo, isHaloBindFeatureEnabled, performUnbindHaloAccount } from '@/utils/haloUser.js';
	import { resolvePointsEnabled, performPointsSignIn, fetchPointsOverview, syncPointsAfterLogin, isPointsApiReady, getPointsApiMode, buildWeekSignDisplay } from '@/utils/points.js';
	// #ifdef APP-PLUS
	import CheckAppUpdate from '@/uni_modules/uni-upgrade-center-app/utils/check-update';
	// #endif
	import { CheckWxUpdate } from '@/utils/update.js';
	import {
		NeedPluginIds,
		checkNeedPluginAvailable
	} from "@/utils/plugin.js"

	import tmGrid from '@/tm-vuetify/components/tm-grid/tm-grid.vue';
	import tmButton from '@/tm-vuetify/components/tm-button/tm-button.vue';
	import tmFlop from '@/tm-vuetify/components/tm-flop/tm-flop.vue';
	import tmIcons from '@/tm-vuetify/components/tm-icons/tm-icons.vue';
	import tmInput from '@/tm-vuetify/components/tm-input/tm-input.vue';
	import wxLoginDialog from '@/components/wx-login-dialog/wx-login-dialog.vue';
	import PointsToast from '@/components/points-toast/points-toast.vue';

	export default {
		components: {
			tmGrid,
			tmButton,
			tmFlop,
			tmIcons,
			tmInput,
			wxLoginDialog,
			PointsToast,
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
				profileForm: {
					nickname: '',
					avatarUrl: '',
					avatarTempPath: ''
				},
				statistics: {
					post: 0,
					comment: 0,
					category: 0,
					visit: 0,
					upvote: 0
				},
				pointsBalance: 0,
				pointsTodayEarned: 0,
				signInStatus: null,
				signInLoading: false,
				pointsApiReady: null,
				pointsLocalMode: false,
				navList: [],
			};
		},
		computed: {
			haloConfigs() {
				return this.$tm.vx.getters().getConfigs
			},
			bloggerInfo() {
				return this.haloConfigs?.authorConfig?.blogger || { avatar: '', nickname: '', description: '' };
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
				return !!this.haloConfigs?.pluginConfig?.votePlugin?.enabled;
			},
			calcLinksPluginEnabled() {
				return !!this.haloConfigs?.pluginConfig?.linksPlugin?.enabled;
			},
			postDetailConfig() {
				return this.haloConfigs?.basicConfig?.postDetailConfig || { showComment: true };
			},
			calcIsShowComment() {
				return this.postDetailConfig.showComment !== false;
			},
			calcDefaultAvatar() {
				return this.$utils.checkAvatarUrl(this.bloggerInfo.avatar);
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
			statList() {
				const fourthStat = this.calcIsShowComment ? {
					key: 'comment',
					label: '评论',
					value: this.statistics.comment,
					color: 'blue',
					bgClass: 'stat-bg-indigo'
				} : {
					key: 'upvote',
					label: '点赞',
					value: this.statistics.upvote,
					color: 'red',
					bgClass: 'stat-bg-red'
				};
				return [{
					key: 'post',
					label: '文章',
					value: this.statistics.post,
					color: 'light-blue',
					bgClass: 'stat-bg-blue',
					path: '/pagesA/archives/archives'
				}, {
					key: 'visit',
					label: '访客',
					value: this.statistics.visit,
					color: 'green',
					bgClass: 'stat-bg-green'
				}, {
					key: 'category',
					label: '分类',
					value: this.statistics.category,
					color: 'orange',
					bgClass: 'stat-bg-orange'
				}, fourthStat];
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
			serviceList() {
				const firstService = this.calcIsShowComment ? {
					text: '我的评论',
					icon: 'haloicon-pinglun',
					color: 'light-blue',
					path: '/pagesA/user-activities/user-activities?type=comment',
					requireLogin: true
				} : {
					text: '搜索文章',
					icon: 'icon-search',
					color: 'cyan',
					path: '/pagesA/articles/articles',
					requireLogin: false
				};
				const list = [firstService, {
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
				}];
				if (this.calcPointsEnabled) {
					list.push({
						text: '积分明细',
						icon: 'haloicon-rizhishenji',
						color: 'purple',
						path: '/pagesA/points-history/points-history',
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
			this.fnGetData();
			uni.$on('wx_login_status_changed', this.fnCheckLoginStatus);
			uni.$on('points_updated', this.fnOnPointsUpdated);
			uni.$on('points_config_ready', this.fnOnPointsConfigReady);
		},
		onShow() {
			this.fnSyncPointsApiStateFromCache();
			this.fnCheckLoginStatus();
			if (this.calcPointsEnabled && this.isLoggedIn) {
				this.fnRefreshPointsOverview({ silent: true });
			}
		},
		onUnload() {
			uni.$off('wx_login_status_changed', this.fnCheckLoginStatus);
			uni.$off('points_updated', this.fnOnPointsUpdated);
			uni.$off('points_config_ready', this.fnOnPointsConfigReady);
		},
		onPullDownRefresh() {
			uni.$tm.vx.actions('config/fetchConfigs').finally(() => {
				this.fnGetData();
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

			fnOnStatClick(item) {
				if (!this.fnEnsureLogin()) return;
				if (item.path) {
					this.fnOnNav({ type: 'page', path: item.path, requireLogin: true });
				}
			},

			fnOnServiceClick(e) {
				const item = e.data;
				if (item.action === 'settings') {
					this.fnToSettings();
					return;
				}
				if (item.requireLogin && !this.fnEnsureLogin()) return;
				if (item.path) {
					this.fnOnNav({ type: 'page', path: item.path, requireLogin: item.requireLogin });
				}
			},

			fnOnSiteFunctionClick(arg) {
				let idx = arg;
				if (arg && typeof arg === 'object' && arg.currentTarget) {
					idx = arg.currentTarget.dataset.index;
				}
				if (idx == null || idx === '') return;
				const item = this.siteFunctionList[Number(idx)];
				if (!item) return;
				if (item.requireLogin && !this.fnEnsureLogin()) return;
				this.fnOnNav({
					type: item.type,
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
					this.fnGetData();
					if (this.calcPointsEnabled) {
						this.fnRefreshPointsOverview({ forceRemote: true });
					}
				}
				if (!this.isLoggedIn) {
					this.statistics = {
						post: 0,
						comment: 0,
						category: 0,
						visit: 0,
						upvote: 0
					};
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
				this.fnGetData();
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
					show: await checkNeedPluginAvailable(NeedPluginIds.PluginDataStatistics)
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
					key: 'love',
					title: '恋爱日记',
					icon: 'halocoloricon-attent',
					iconColor: 'red',
					path: '/pagesA/love/love',
					isAdmin: false,
					type: 'page',
					show: !!this.haloConfigs?.loveConfig?.loveEnabled
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
					key: 'contact-blogger',
					title: '联系博主',
					icon: 'icon-paper-plane',
					iconColor: 'orange',
					path: '/pagesA/contact/contact',
					isAdmin: false,
					type: 'page',
					show: !!this.haloConfigs?.authorConfig?.social?.enabled
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

			fnGetData() {
				if (!this.isLoggedIn) {
					uni.stopPullDownRefresh();
					return;
				}
				this.$httpApi.v2
					.getBlogStatistics()
					.then(res => { this.statistics = res; })
					.catch(() => { this.$tm.toast('统计数据加载失败'); })
					.finally(() => { uni.stopPullDownRefresh(); });
				this.fnRefreshPointsOverview({ silent: isPointsApiReady() === true });
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

	.uc-desc {
		display: block;
		font-size: 24rpx;
		color: $text3;
		margin-top: 4rpx;
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

	/* ===== 统计 ===== */
	.stats-card {
		display: flex;
		padding: 32rpx 0;
	}

	.stat-item {
		flex: 1;
		display: flex;
		flex-direction: column;
		align-items: center;
	}

	.stat-badge {
		min-width: 80rpx;
		padding: 8rpx 20rpx;
		border-radius: 12rpx;
		text-align: center;
	}

	.stat-bg-blue { background: rgba(3, 169, 244, .1); }
	.stat-bg-green { background: rgba(67, 160, 71, .1); }
	.stat-bg-orange { background: rgba(251, 140, 0, .1); }
	.stat-bg-indigo { background: rgba(30, 136, 229, .1); }
	.stat-bg-red { background: rgba(229, 57, 53, .1); }

	.stat-num {
		font-size: 40rpx;
		font-weight: 700;
		line-height: 48rpx;

		&--placeholder {
			color: #c0c4cc;
		}
	}

	.stat-label {
		font-size: 22rpx;
		color: $text3;
		margin-top: 8rpx;
	}

	/* ===== 积分签到 ===== */
	.points-card {
		padding: 24rpx 28rpx 28rpx;
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

	.points-card-link {
		display: flex;
		align-items: center;
		gap: 4rpx;
		font-size: 24rpx;
		color: $text3;
	}

	.points-sign-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 20rpx;
		padding: 20rpx 0 8rpx;
		border-top: 1rpx solid #f0f0f0;
	}

	.points-sign-info {
		flex: 1;
		min-width: 0;
	}

	.points-sign-days {
		display: block;
		font-size: 26rpx;
		font-weight: 600;
		color: $text2;
	}

	.points-sign-tip {
		display: block;
		margin-top: 6rpx;
		font-size: 22rpx;
		color: $text3;
		line-height: 1.4;
	}

	.points-week-row {
		display: flex;
		justify-content: space-between;
		margin-top: 20rpx;
		padding-top: 16rpx;
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
