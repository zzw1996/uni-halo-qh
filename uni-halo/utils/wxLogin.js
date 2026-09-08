/**
 * 微信小程序登录工具
 * UniHalo v1.0.37：微信登录与 Halo 绑定解耦，unbound 不阻塞登录
 */

import { applyWxLoginSuccess } from '@/utils/auth.js';
import { persistWxLoginUser, isHaloBindFeatureEnabled } from '@/utils/haloUser.js';

/**
 * 从接口响应 data 中提取 error 字段文案
 */
export function extractApiError(data) {
	if (!data) return '';
	if (typeof data === 'string') return data;
	if (data.error) return data.error;
	if (data.detail) return data.detail;
	if (data.message) return data.message;
	return '';
}

/** 后端已保存完整资料（openid + 昵称） */
export function isProfileComplete(data) {
	if (!data?.openid) return false;
	if (data.profileComplete === true) return true;
	if (data.profileComplete === false) return false;
	return !!(data.nickname && String(data.nickname).trim());
}

function uniLogin() {
	return new Promise((resolve, reject) => {
		uni.login({ success: resolve, fail: reject });
	});
}

/** 读取待消费的邀请码（分享卡片/邀请海报扫码时由 index.vue 写入），登录成功后才清除 */
function peekPendingInviteCode() {
	let code = '';
	try {
		code = uni.getStorageSync('invite.pending') || '';
	} catch (e) {
		// 忽略存储异常
	}
	return code || '';
}

/** 登录成功后清除待消费邀请码（首次登录注册时由后端消费发奖） */
function clearPendingInviteCode() {
	try {
		uni.removeStorageSync('invite.pending');
	} catch (e) {
		// 忽略存储异常
	}
}

/** 组装登录请求体：code + 可选的昵称与邀请码 */
function buildLoginPayload(code, nickname) {
	const payload = { code };
	if (nickname) {
		payload.nickname = nickname;
	}
	const inviteCode = peekPendingInviteCode();
	if (inviteCode) {
		payload.inviteCode = inviteCode;
	}
	return payload;
}

/**
 * 微信登录成功：持久化 UserDTO，返回是否需要引导 Halo 绑定
 * @returns {{ ok: true, data: object, needHaloBind?: boolean }}
 */
function handlePostWxLogin(loginData) {
	const data = persistWxLoginUser(loginData);
	const needHaloBind = !!data.needHaloBind && isHaloBindFeatureEnabled();
	return {
		ok: true,
		data,
		needHaloBind
	};
}

/**
 * 检查登录服务是否可用
 */
export async function checkWxLoginAvailable(httpApi) {
	try {
		const configRes = await httpApi.getWxAppConfig();
		if (configRes?.isHtml || configRes?.statusCode === 404) {
			return {
				ok: false,
				reason: '后端登录接口尚未部署。请确认 Halo 已安装并启用 UniHalo 插件。'
			};
		}
		if (configRes?.statusCode === 200 && configRes.data?.loginEnabled === false) {
			return {
				ok: false,
				reason: '站长尚未开启小程序登录。请在 Halo 后台 → UniHalo 配置 → 微信小程序中启用登录。'
			};
		}
		if (configRes?.statusCode === 200) {
			return { ok: true };
		}
		if (configRes?.data?.error) {
			return { ok: false, reason: configRes.data.error };
		}
		return {
			ok: false,
			reason: '无法连接登录服务（状态码 ' + (configRes?.statusCode || '未知') + '），请稍后重试。'
		};
	} catch (err) {
		return {
			ok: false,
			reason: parseWxLoginError(err)
		};
	}
}

/**
 * 静默登录
 * @returns {{ ok, data?, autoLogin?, needProfile?, needHaloBind?, reason? }}
 */
export async function tryAutoWxLogin(httpApi) {
	// #ifndef MP-WEIXIN
	return { ok: false, reason: '当前平台暂不支持登录，请使用微信小程序访问。' };
	// #endif

	// #ifdef MP-WEIXIN
	const check = await checkWxLoginAvailable(httpApi);
	if (!check.ok) return check;

	let loginRes;
	try {
		loginRes = await uniLogin();
	} catch (e) {
		return { ok: false, reason: '获取微信授权失败' };
	}
	if (!loginRes?.code) {
		return { ok: false, reason: '获取微信授权失败' };
	}

	try {
		const res = await httpApi.wxLogin(buildLoginPayload(loginRes.code));
		const apiError = extractApiError(res?.data);
		if (apiError) {
			return { ok: false, reason: apiError };
		}
		if (!(res?.statusCode === 200 && res?.data?.openid)) {
			return { ok: false, reason: '登录失败，服务端返回异常，请稍后重试。' };
		}
		clearPendingInviteCode();
		if (isProfileComplete(res.data)) {
			const postResult = handlePostWxLogin(res.data);
			return { ...postResult, autoLogin: true };
		}
		return { ok: false, needProfile: true, data: res.data };
	} catch (err) {
		return { ok: false, reason: parseWxLoginError(err) };
	}
	// #endif
}

/**
 * 首次登录 / 完善资料
 */
export async function performWxLoginWithProfile(httpApi, profile = {}) {
	// #ifndef MP-WEIXIN
	return { ok: false, reason: '当前平台暂不支持登录，请使用微信小程序访问。' };
	// #endif

	// #ifdef MP-WEIXIN
	const nickname = (profile.nickname || '').trim();
	if (!nickname) {
		return { ok: false, reason: '请先填写昵称' };
	}

	const check = await checkWxLoginAvailable(httpApi);
	if (!check.ok) return check;

	let loginRes;
	try {
		loginRes = await uniLogin();
	} catch (e) {
		return { ok: false, reason: '获取微信授权失败' };
	}
	if (!loginRes?.code) {
		return { ok: false, reason: '获取微信授权失败' };
	}

	try {
		const res = await httpApi.wxLogin(buildLoginPayload(loginRes.code, nickname));
		const apiError = extractApiError(res?.data);
		if (apiError) {
			return { ok: false, reason: apiError };
		}
		if (!(res?.statusCode === 200 && res?.data?.openid)) {
			return { ok: false, reason: '登录失败，服务端返回异常，请稍后重试。' };
		}
		clearPendingInviteCode();

		let loginData = res.data;

		const avatarTempPath = profile.avatarTempPath || '';
		if (avatarTempPath) {
			applyWxLoginSuccess(loginData);
			try {
				const uploadRes = await httpApi.uploadWxAvatar(avatarTempPath);
				if (uploadRes?.data?.avatarUrl) {
					const updateRes = await httpApi.updateProfile({
						nickname,
						avatarUrl: uploadRes.data.avatarUrl
					});
					const updateError = extractApiError(updateRes?.data);
					if (!updateError && updateRes?.statusCode === 200 && updateRes?.data?.openid) {
						loginData = updateRes.data;
					}
				}
			} catch (uploadErr) {
				console.warn('[UniHalo] 头像上传失败，登录仍成功:', uploadErr);
			}
		}

		return handlePostWxLogin(loginData);
	} catch (err) {
		return { ok: false, reason: parseWxLoginError(err) };
	}
	// #endif
}

/** @deprecated 请使用 tryAutoWxLogin / performWxLoginWithProfile */
export async function performWxLogin(httpApi, profile) {
	if (profile && profile.nickname) {
		return performWxLoginWithProfile(httpApi, profile);
	}
	return tryAutoWxLogin(httpApi);
}

export function parseWxLoginError(err) {
	if (!err) return '网络请求失败，请检查网络后重试。';
	if (err.isHtml) return '后端返回异常页面，登录接口可能未正确部署。';
	if (err.statusCode === 404) return '登录接口不存在（404），请升级 UniHalo 插件后重试。';
	if (err.error) {
		if (err.error.includes('域名') || err.error.includes('白名单')) {
			return '请求域名未加入微信小程序白名单，请联系管理员配置。';
		}
		return err.error;
	}
	return '暂时无法访问登录服务，请检查网络或联系管理员。';
}

export async function performUpdateProfile(httpApi, profile) {
	try {
		const res = await httpApi.updateProfile(profile);
		const apiError = extractApiError(res?.data);
		if (apiError) {
			return { ok: false, reason: apiError };
		}
		if (res?.statusCode === 200 && res?.data?.openid) {
			const data = persistWxLoginUser(res.data);
			return { ok: true, data };
		}
		return { ok: false, reason: '更新失败，请稍后重试。' };
	} catch (err) {
		return { ok: false, reason: parseWxLoginError(err) };
	}
}

export function isWxLoginRequired(configs) {
	return configs?.wxAppConfig?.loginEnabled === true;
}

export function getProfileModifyState(userInfo, wxAppConfig) {
	const limit = Number(wxAppConfig?.profileModifyLimit ?? 0);
	const count = Number(userInfo?.profileModifyCount ?? 0);
	if (!limit || limit <= 0) {
		return {
			ok: true,
			unlimited: true,
			remaining: -1,
			limit: 0,
			count,
			hint: '资料修改不限次数'
		};
	}
	const remaining = limit - count;
	if (remaining <= 0) {
		return {
			ok: false,
			unlimited: false,
			remaining: 0,
			limit,
			count,
			hint: `资料修改次数已用尽（上限 ${limit} 次）`
		};
	}
	return {
		ok: true,
		unlimited: false,
		remaining,
		limit,
		count,
		hint: `还可修改 ${remaining} 次（上限 ${limit} 次）`
	};
}

export async function performSaveWxProfile(httpApi, { nickname, avatarTempPath, currentAvatarUrl }) {
	const nick = (nickname || '').trim();
	if (!nick) {
		return { ok: false, reason: '请输入昵称' };
	}

	let avatarUrl = currentAvatarUrl || '';
	if (avatarTempPath) {
		try {
			const uploadRes = await httpApi.uploadWxAvatar(avatarTempPath);
			if (uploadRes?.data?.avatarUrl) {
				avatarUrl = uploadRes.data.avatarUrl;
			}
		} catch (uploadErr) {
			return { ok: false, reason: uploadErr?.error || '头像上传失败' };
		}
	}

	const payload = { nickname: nick };
	if (avatarUrl) {
		payload.avatarUrl = avatarUrl;
	}

	return performUpdateProfile(httpApi, payload);
}
