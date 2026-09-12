<template>
	<view v-if="visible" class="wx-login-mask" @click="fnClose">
		<view class="wx-login-dialog" @click.stop="">
			<view class="wx-login-close" @click="fnClose">
				<tm-icons name="icon-times" :size="28" color="#909399"></tm-icons>
			</view>

			<text class="wx-login-title">{{ dialogTitle }}</text>

			<view v-if="state === 'unavailable'" class="wx-login-body">
				<view class="wx-login-tip-box">
					<tm-icons name="icon-exclamation-circle" :size="32" color="#fb8c00"></tm-icons>
					<text class="wx-login-tip-text">{{ unavailableReason }}</text>
				</view>
			</view>

			<view v-else-if="state === 'loading'" class="wx-login-body wx-login-loading">
				<tm-loadding color="light-blue" :size="48"></tm-loadding>
				<text class="wx-login-status">{{ loadingText }}</text>
				<text class="wx-login-hint">{{ loadingDesc }}</text>
			</view>

			<view v-else-if="state === 'profile'" class="wx-login-body">
				<text class="wx-login-desc">首次登录，请填写头像和昵称</text>

				<!-- #ifdef MP-WEIXIN -->
				<view class="wx-profile-form">
					<button class="wx-avatar-btn" open-type="chooseAvatar" @chooseavatar="fnOnChooseAvatar">
						<image
							v-if="profileForm.avatarTempPath"
							:src="profileForm.avatarTempPath"
							class="wx-avatar-img"
							mode="aspectFill"
						></image>
						<view v-else class="wx-avatar-placeholder">
							<tm-icons name="icon-user" :size="44" color="#c0c4cc"></tm-icons>
							<text class="wx-avatar-hint">点击选择头像</text>
						</view>
					</button>
					<view class="wx-nickname-row">
						<text class="wx-nickname-label">昵称</text>
						<input
							type="nickname"
							class="wx-nickname-input"
							v-model="profileForm.nickname"
							placeholder="点击输入或选择微信昵称"
							@blur="fnOnNicknameBlur"
						/>
					</view>
				</view>
				<!-- #endif -->

				<!-- #ifndef MP-WEIXIN -->
				<text class="wx-login-hint">请使用微信小程序完成登录</text>
				<!-- #endif -->
			</view>

			<view v-else-if="state === 'success'" class="wx-login-body wx-login-success">
				<tm-icons name="icon-check-circle" :size="64" color="#19be6b"></tm-icons>
				<text class="wx-login-status">{{ successTitle }}</text>
				<text v-if="successDesc" class="wx-login-hint wx-login-hint--center">{{ successDesc }}</text>
			</view>

			<view v-else-if="state === 'haloBind'" class="wx-login-body">
				<text class="wx-login-desc">微信登录已成功，关联 Halo 账号后可同步博客身份</text>

				<view class="wx-halo-bind-form">
					<view class="wx-form-row">
						<text class="wx-form-label">账号</text>
						<input
							class="wx-form-input"
							v-model="haloBindForm.account"
							placeholder="Halo 邮箱或用户名"
						/>
					</view>
					<view class="wx-form-row wx-form-row--code">
						<text class="wx-form-label">验证码</text>
						<input
							class="wx-form-input wx-form-input--code"
							v-model="haloBindForm.code"
							type="number"
							maxlength="6"
							placeholder="6 位数字"
						/>
						<view
							class="wx-send-code-btn"
							:class="{ 'wx-send-code-btn--disabled': bindCodeSending || bindCodeCountdown > 0 }"
							@click="fnSendBindCode"
						>
							<text>{{ bindCodeBtnText }}</text>
						</view>
					</view>
					<text v-if="haloBindForm.maskedEmail" class="wx-login-hint wx-login-hint--left">
						验证码已发送至 {{ haloBindForm.maskedEmail }}，{{ bindCodeExpireHint }} 内有效
					</text>
					<text v-else class="wx-login-hint wx-login-hint--left">
						验证码将发送至该 Halo 账号的注册邮箱（由 Halo 后台发送）。若用户名发送失败，请改用注册邮箱
					</text>
				</view>

				<view class="wx-halo-create-link" @click="fnCreateHaloAccount">
					<text>没有 Halo 账号？一键创建</text>
				</view>
			</view>
		</view>

		<view v-if="state === 'profile'" class="wx-login-actions-wrap" @click.stop="">
			<view class="wx-login-actions">
				<tm-button
					block
					theme="bg-gradient-light-blue-accent"
					:shadow="4"
					@click="fnConfirmLogin"
				>
					{{ confirmText }}
				</tm-button>
				<tm-button
					block
					plan
					theme="grey"
					:fllowTheme="false"
					:shadow="0"
					class="wx-login-cancel-btn"
					@click="fnClose"
				>
					{{ cancelText }}
				</tm-button>
			</view>
		</view>

		<view v-if="state === 'haloBind'" class="wx-login-actions-wrap" @click.stop="">
			<view class="wx-login-actions">
				<tm-button
					block
					theme="bg-gradient-light-blue-accent"
					:shadow="4"
					@click="fnConfirmHaloBind"
				>
					确认绑定
				</tm-button>
				<tm-button
					block
					plan
					theme="grey-lighten-1"
					:shadow="0"
					@click="fnSkipHaloBind"
				>
					稍后再说
				</tm-button>
			</view>
		</view>
	</view>
</template>

<script>
import tmButton from '@/tm-vuetify/components/tm-button/tm-button.vue';
import tmLoadding from '@/tm-vuetify/components/tm-loadding/tm-loadding.vue';
import tmIcons from '@/tm-vuetify/components/tm-icons/tm-icons.vue';
import {
	checkWxLoginAvailable,
	tryAutoWxLogin,
	performWxLoginWithProfile
} from '@/utils/wxLogin.js';
import {
	performBindHaloAccountByCode,
	performSendHaloBindCode,
	performCreateHaloAccount,
	finalizeLoginAfterHaloBind,
	needsHaloBindChoice,
	isHaloBindFeatureEnabled,
	shouldPromptHaloBind
} from '@/utils/haloUser.js';
import { getWxLoginInfo } from '@/utils/auth.js';

export default {
	name: 'WxLoginDialog',
	components: { tmButton, tmLoadding, tmIcons },
	props: {
		show: {
			type: Boolean,
			default: false
		},
		title: {
			type: String,
			default: '微信快捷登录'
		},
		desc: {
			type: String,
			default: '请填写头像和昵称后完成授权登录'
		},
		confirmText: {
			type: String,
			default: '确认登录'
		},
		cancelText: {
			type: String,
			default: '暂不登录'
		},
		autoCheck: {
			type: Boolean,
			default: true
		},
		/** auto=完整登录流程；haloBind=直接进入 Halo 账号关联（需已微信登录） */
		startMode: {
			type: String,
			default: 'auto'
		}
	},
	data() {
		return {
			visible: false,
			state: 'loading',
			unavailableReason: '',
			loadingText: '正在登录',
			loadingDesc: '正在识别您的账号...',
			pendingUserData: null,
			wxLoginReady: false,
			bindCodeExpireSeconds: 300,
			profileForm: {
				nickname: '',
				avatarTempPath: ''
			},
			haloBindForm: {
				account: '',
				code: '',
				maskedEmail: ''
			},
			bindCodeSending: false,
			bindCodeCountdown: 0,
			bindCodeTimer: null,
			successTitle: '',
			successDesc: '',
			successCloseTimer: null
		};
	},
	computed: {
		dialogTitle() {
			if (this.state === 'success') {
				return '操作成功';
			}
			if (this.state === 'haloBind') {
				return '关联 Halo 账号';
			}
			return this.title;
		},
		bindCodeBtnText() {
			if (this.bindCodeSending) return '发送中';
			if (this.bindCodeCountdown > 0) return `${this.bindCodeCountdown}s`;
			return '发送验证码';
		},
		bindCodeExpireHint() {
			const sec = this.bindCodeExpireSeconds || 300;
			return sec >= 60 ? `${Math.round(sec / 60)} 分钟` : `${sec} 秒`;
		}
	},
	watch: {
		show: {
			immediate: true,
			handler(val) {
				this.visible = val;
				if (val) {
					this.fnStartLoginFlow();
				}
			}
		}
	},
	methods: {
		resetProfileForm() {
			this.profileForm = {
				nickname: '',
				avatarTempPath: ''
			};
		},
		resetHaloBindForm() {
			this.fnClearBindCodeTimer();
			this.haloBindForm = {
				account: '',
				code: '',
				maskedEmail: ''
			};
			this.bindCodeSending = false;
			this.bindCodeCountdown = 0;
			this.bindCodeExpireSeconds = 300;
			this.wxLoginReady = false;
			this.pendingUserData = null;
		},
		fnClearBindCodeTimer() {
			if (this.bindCodeTimer) {
				clearInterval(this.bindCodeTimer);
				this.bindCodeTimer = null;
			}
		},
		fnStartBindCodeCountdown(seconds = 60) {
			this.fnClearBindCodeTimer();
			this.bindCodeCountdown = seconds;
			this.bindCodeTimer = setInterval(() => {
				if (this.bindCodeCountdown <= 1) {
					this.bindCodeCountdown = 0;
					this.fnClearBindCodeTimer();
					return;
				}
				this.bindCodeCountdown -= 1;
			}, 1000);
		},
		async fnSendBindCode() {
			if (this.bindCodeSending || this.bindCodeCountdown > 0) return;

			const account = (this.haloBindForm.account || '').trim();
			if (!account) {
				return uni.showToast({ title: '请先输入 Halo 账号', icon: 'none' });
			}

			this.bindCodeSending = true;
			const result = await performSendHaloBindCode(this.$httpApi.v2, { account });
			this.bindCodeSending = false;

			if (result.ok) {
				this.haloBindForm.maskedEmail = result.maskedEmail || '';
				this.bindCodeExpireSeconds = result.expireSeconds || 300;
				this.fnStartBindCodeCountdown(60);
				uni.showToast({ title: '验证码已发送', icon: 'success' });
				return;
			}
			uni.showToast({ title: result.reason || '发送失败', icon: 'none' });
		},
		fnOnChooseAvatar(e) {
			const avatarUrl = e?.detail?.avatarUrl;
			if (avatarUrl) {
				this.profileForm.avatarTempPath = avatarUrl;
			}
		},
		fnOnNicknameBlur(e) {
			const val = e?.detail?.value;
			if (val) {
				this.profileForm.nickname = val;
			}
		},
		fnClearSuccessCloseTimer() {
			if (this.successCloseTimer) {
				clearTimeout(this.successCloseTimer);
				this.successCloseTimer = null;
			}
		},
		fnBuildSuccessDesc(data, toastTitle) {
			const haloUserName = (data?.haloUserName || '').trim();
			if (toastTitle === '创建成功' && haloUserName) {
				return `Halo 登录用户名：${haloUserName}\n请妥善记住，可用于 Halo 后台登录`;
			}
			if (haloUserName) {
				return `已关联 Halo 账号：${haloUserName}`;
			}
			if (toastTitle === '绑定成功' || toastTitle === '创建成功') {
				return 'Halo 账号已关联，可在个人中心查看';
			}
			return '';
		},
		fnLoginSuccess(data, toastTitle = '登录成功') {
			this.fnClearSuccessCloseTimer();
			this.successTitle = toastTitle;
			this.successDesc = this.fnBuildSuccessDesc(data, toastTitle);
			this.state = 'success';
			this.$emit('success', data);
			uni.showToast({ title: toastTitle, icon: 'success', duration: 2000 });
			const closeDelay = toastTitle === '创建成功' ? 2800 : 1600;
			this.successCloseTimer = setTimeout(() => {
				this.fnFinishAndClose();
			}, closeDelay);
		},
		fnFinishAndClose() {
			this.fnClearSuccessCloseTimer();
			this.visible = false;
			this.state = 'loading';
			this.successTitle = '';
			this.successDesc = '';
			this.resetHaloBindForm();
			this.$emit('update:show', false);
		},
		fnEnterHaloBind(userData) {
			if (!shouldPromptHaloBind(userData)) {
				this.fnLoginSuccess(userData);
				return;
			}
			this.pendingUserData = userData || null;
			this.wxLoginReady = true;
			this.state = 'haloBind';
			this.$emit('login-ready', userData);
		},
		async fnStartLoginFlow() {
			this.resetProfileForm();
			this.resetHaloBindForm();
			this.state = 'loading';
			this.loadingText = '正在登录';
			this.loadingDesc = '正在识别您的账号...';
			this.unavailableReason = '';

			// #ifndef MP-WEIXIN
			this.state = 'unavailable';
			this.unavailableReason = '当前平台暂不支持登录，请使用微信小程序访问。';
			return;
			// #endif

			if (this.startMode === 'haloBind') {
				if (!isHaloBindFeatureEnabled()) {
					this.state = 'unavailable';
					this.unavailableReason = '站长未开启 Halo 账号关联功能。';
					return;
				}
				const wxUser = getWxLoginInfo();
				if (!wxUser?.openid) {
					this.state = 'unavailable';
					this.unavailableReason = '请先完成微信登录后再关联 Halo 账号。';
					return;
				}
				if (!needsHaloBindChoice(wxUser)) {
					this.fnLoginSuccess(wxUser, '已关联 Halo 账号');
					return;
				}
				this.fnEnterHaloBind(wxUser);
				return;
			}

			if (this.autoCheck) {
				const check = await checkWxLoginAvailable(this.$httpApi.v2);
				if (!check.ok) {
					this.state = 'unavailable';
					this.unavailableReason = check.reason;
					return;
				}
			}

			const autoResult = await tryAutoWxLogin(this.$httpApi.v2);
			if (autoResult.ok) {
				if (autoResult.needHaloBind) {
					this.fnEnterHaloBind(autoResult.data);
					return;
				}
				this.fnLoginSuccess(autoResult.data);
				return;
			}
			if (autoResult.needProfile) {
				this.state = 'profile';
				return;
			}

			this.state = 'unavailable';
			this.unavailableReason = autoResult.reason || '登录失败，请稍后重试。';
		},
		fnClose() {
			if (this.state === 'loading' || this.state === 'success') return;
			this.fnClearBindCodeTimer();
			this.fnClearSuccessCloseTimer();
			this.visible = false;
			this.state = 'loading';
			this.resetHaloBindForm();
			this.$emit('update:show', false);
			this.$emit('close');
		},
		async fnConfirmLogin() {
			if (this.state !== 'profile') return;

			const nickname = (this.profileForm.nickname || '').trim();
			if (!nickname) {
				return uni.showToast({ title: '请先填写昵称', icon: 'none' });
			}

			this.state = 'loading';
			this.loadingText = '正在登录';
			this.loadingDesc = '正在保存您的资料...';

			const result = await performWxLoginWithProfile(this.$httpApi.v2, {
				nickname,
				avatarTempPath: this.profileForm.avatarTempPath
			});

			if (result.ok) {
				if (result.needHaloBind) {
					this.fnEnterHaloBind(result.data);
					return;
				}
				this.fnLoginSuccess(result.data);
				return;
			}

			this.state = 'unavailable';
			this.unavailableReason = result.reason || '登录失败，请稍后重试。';
		},
		async fnConfirmHaloBind() {
			if (this.state !== 'haloBind') return;

			this.state = 'loading';
			this.loadingText = '正在关联';
			this.loadingDesc = '正在验证邮箱验证码...';

			const result = await performBindHaloAccountByCode(this.$httpApi.v2, this.haloBindForm);

			if (result.ok) {
				this.fnLoginSuccess(result.data, '绑定成功');
				return;
			}

			this.state = 'haloBind';
			uni.showToast({ title: result.reason || '操作失败', icon: 'none' });
		},
		async fnCreateHaloAccount() {
			if (this.state !== 'haloBind') return;

			this.state = 'loading';
			this.loadingText = '正在创建';
			this.loadingDesc = '正在创建 Halo 账号...';

			const result = await performCreateHaloAccount(this.$httpApi.v2);

			if (result.ok) {
				this.fnLoginSuccess(result.data, '创建成功');
				return;
			}

			this.state = 'haloBind';
			uni.showToast({ title: result.reason || '创建失败', icon: 'none' });
		},
		fnSkipHaloBind() {
			const data = finalizeLoginAfterHaloBind(this.pendingUserData || {}, { skipped: true });
			this.fnLoginSuccess(data, '登录成功');
		}
	},
	beforeDestroy() {
		this.fnClearBindCodeTimer();
		this.fnClearSuccessCloseTimer();
	}
};
</script>

<style lang="scss" scoped>
.wx-login-mask {
	position: fixed;
	top: 0;
	left: 0;
	right: 0;
	bottom: 0;
	z-index: 1001;
	background: rgba(0, 0, 0, 0.45);
	display: flex;
	flex-direction: column;
	align-items: center;
	justify-content: center;
	padding: 48rpx;
	box-sizing: border-box;
}

.wx-login-dialog {
	position: relative;
	width: 100%;
	max-width: 620rpx;
	background: #fff;
	border-radius: 24rpx;
	padding: 48rpx 40rpx 40rpx;
	box-shadow: 0 16rpx 48rpx rgba(0, 0, 0, 0.12);
	box-sizing: border-box;
}

.wx-login-actions-wrap {
	width: 100%;
	max-width: 620rpx;
	margin-top: 24rpx;
}

.wx-login-close {
	position: absolute;
	top: 24rpx;
	right: 24rpx;
	padding: 8rpx;
}

.wx-login-title {
	display: block;
	text-align: center;
	font-size: 36rpx;
	font-weight: 700;
	color: #1a1a2e;
	margin-bottom: 24rpx;
}

.wx-login-body {
	margin-bottom: 8rpx;
}

.wx-login-desc {
	display: block;
	text-align: center;
	font-size: 26rpx;
	color: #909399;
	line-height: 1.6;
	margin-bottom: 24rpx;
}

.wx-login-hint {
	display: block;
	text-align: center;
	font-size: 24rpx;
	color: #c0c4cc;
	margin-top: 16rpx;
}

.wx-login-hint--left {
	text-align: left;
	margin-top: 8rpx;
	line-height: 1.6;
}

.wx-login-status {
	display: block;
	text-align: center;
	font-size: 30rpx;
	font-weight: 600;
	color: #303133;
	margin-top: 24rpx;
}

.wx-login-loading {
	display: flex;
	flex-direction: column;
	align-items: center;
	padding: 16rpx 0 8rpx;
}

.wx-login-success {
	display: flex;
	flex-direction: column;
	align-items: center;
	padding: 16rpx 0 8rpx;
}

.wx-login-hint--center {
	margin-top: 20rpx;
	line-height: 1.6;
}

.wx-login-tip-box {
	display: flex;
	align-items: flex-start;
	padding: 24rpx;
	border-radius: 16rpx;
	background: #fff3e0;
}

.wx-login-tip-text {
	flex: 1;
	margin-left: 16rpx;
	font-size: 26rpx;
	color: #e65100;
	line-height: 1.5;
}

.wx-profile-form {
	display: flex;
	flex-direction: column;
	align-items: center;
	gap: 24rpx;
}

.wx-avatar-btn {
	padding: 0;
	margin: 0;
	background: transparent;
	border: none;
	line-height: normal;
}

.wx-avatar-btn::after {
	border: none;
}

.wx-avatar-img,
.wx-avatar-placeholder {
	width: 128rpx;
	height: 128rpx;
	border-radius: 50%;
}

.wx-avatar-placeholder {
	display: flex;
	flex-direction: column;
	align-items: center;
	justify-content: center;
	background: #f5f6fa;
}

.wx-avatar-hint {
	font-size: 20rpx;
	color: #909399;
	margin-top: 8rpx;
}

.wx-nickname-row,
.wx-form-row {
	display: flex;
	align-items: center;
	width: 100%;
	padding: 16rpx 0;
	border-bottom: 1rpx solid #f0f0f0;
}

.wx-nickname-label,
.wx-form-label {
	width: 100rpx;
	font-size: 28rpx;
	color: #303133;
	flex-shrink: 0;
}

.wx-nickname-input,
.wx-form-input {
	flex: 1;
	font-size: 28rpx;
	color: #303133;
	text-align: right;
}

.wx-form-row--code {
	gap: 16rpx;
}

.wx-form-input--code {
	flex: 1;
	text-align: left;
	min-width: 0;
}

.wx-send-code-btn {
	flex-shrink: 0;
	padding: 8rpx 20rpx;
	border-radius: 8rpx;
	background: #e3f2fd;
}

.wx-send-code-btn text {
	font-size: 24rpx;
	color: #0288d1;
	white-space: nowrap;
}

.wx-send-code-btn--disabled {
	opacity: 0.5;
}

.wx-halo-bind-form {
	width: 100%;
}

.wx-halo-create-link {
	margin-top: 24rpx;
	text-align: center;
}

.wx-halo-create-link text {
	font-size: 26rpx;
	color: #0288d1;
	text-decoration: underline;
}

.wx-login-actions {
	display: flex;
	flex-direction: column;
	gap: 16rpx;
	background: #fff;
	border-radius: 24rpx;
	padding: 24rpx 40rpx;
	box-shadow: 0 16rpx 48rpx rgba(0, 0, 0, 0.12);
}

.wx-login-cancel-btn {
	margin-top: 0 !important;
}
</style>
