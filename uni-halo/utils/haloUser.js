/**
 * Halo 账号绑定（UniHalo 插件 v1.0.37）
 * - wxLogin 不再自动创建/绑定 Halo 用户
 * - 已有账号：邮箱验证码绑定（sendHaloBindCode + bindHaloAccountByCode）
 * - 无账号：createHaloAccount 一键创建
 */

import { applyWxLoginSuccess, getWxLoginInfo } from '@/utils/auth.js';
import { getAppConfigs } from '@/config/index.js';

function extractApiError(data) {
	if (!data) return '';
	if (typeof data === 'string') return data;
	if (data.error) return data.error;
	if (data.detail) return data.detail;
	if (data.message) return data.message;
	if (data.title && data.title !== 'Bad Request') return data.title;
	if (Array.isArray(data.errors) && data.errors.length) {
		return data.errors.map((e) => e.message || e).join('；');
	}
	if (Array.isArray(data.violations) && data.violations.length) {
		return data.violations.map((v) => v.message || `${v.field}: ${v.message}`).join('；');
	}
	return '';
}

/** @typedef {'unbound'|'bound'|'created'} HaloBindStatus */

/**
 * UserDTO（v1.0.37）
 * @typedef {Object} WxUserDTO
 * @property {number|string} [id]
 * @property {string} openid
 * @property {string} [unionid]
 * @property {string} [nickname]
 * @property {string} [avatarUrl]
 * @property {string} [haloUserName]
 * @property {HaloBindStatus} [haloBindStatus]
 * @property {boolean} [profileComplete]
 * @property {number} [profileModifyCount]
 * @property {boolean} [haloBindSkipped]
 */

/** 插件是否开启 Halo 账号关联功能（wxAppConfig.haloBindEnabled） */
export function isHaloBindFeatureEnabled(configs) {
	const wxAppConfig = configs?.wxAppConfig || getAppConfigs()?.wxAppConfig || {};
	return wxAppConfig.haloBindEnabled === true;
}

/** 是否需要展示 Halo 绑定引导/入口 */
export function shouldPromptHaloBind(wxUserInfo, configs) {
	return isHaloBindFeatureEnabled(configs) && needsHaloBindChoice(wxUserInfo);
}

/** 是否展示已关联 Halo 账号状态 */
export function shouldShowHaloBindBoundInfo(wxUserInfo, configs) {
	if (!isHaloBindFeatureEnabled(configs)) return false;
	const haloUserName = (wxUserInfo?.haloUserName || '').trim();
	return !!haloUserName && !needsHaloBindChoice(wxUserInfo);
}

export const HALO_BIND_STATUS = {
	UNBOUND: 'unbound',
	BOUND: 'bound',
	CREATED: 'created'
};

/**
 * 解析 haloBindStatus（优先使用后端 v1.0.37 字段）
 * @param {WxUserDTO|null|undefined} wxUserInfo
 * @returns {HaloBindStatus}
 */
export function resolveHaloBindStatus(wxUserInfo) {
	if (!wxUserInfo?.openid) return HALO_BIND_STATUS.UNBOUND;
	const status = wxUserInfo.haloBindStatus;
	if (status === HALO_BIND_STATUS.BOUND || status === HALO_BIND_STATUS.CREATED) {
		return status;
	}
	if (status === HALO_BIND_STATUS.UNBOUND) {
		return HALO_BIND_STATUS.UNBOUND;
	}
	if (wxUserInfo.haloUserName) {
		return HALO_BIND_STATUS.BOUND;
	}
	return HALO_BIND_STATUS.UNBOUND;
}

/** 是否需要引导绑定 Halo 账号 */
export function needsHaloBindChoice(wxUserInfo) {
	return resolveHaloBindStatus(wxUserInfo) === HALO_BIND_STATUS.UNBOUND;
}

function mergeAndPersistWxUser(partial) {
	const merged = {
		...getWxLoginInfo(),
		...partial
	};
	applyWxLoginSuccess(merged);
	return merged;
}

/**
 * 微信登录成功后持久化用户态，unbound 时不阻塞登录
 * @returns {WxUserDTO & { needHaloBind?: boolean }}
 */
export function persistWxLoginUser(wxUserInfo) {
	applyWxLoginSuccess(wxUserInfo);
	if (needsHaloBindChoice(wxUserInfo)) {
		return { ...wxUserInfo, needHaloBind: true };
	}
	return wxUserInfo;
}

/**
 * 发送 Halo 绑定验证码
 */
export async function performSendHaloBindCode(httpApi, { account }) {
	const acc = (account || '').trim();
	if (!acc) {
		return { ok: false, reason: '请输入 Halo 邮箱或用户名' };
	}

	try {
		const res = await httpApi.sendHaloBindCode({ account: acc });
		if (res?.statusCode !== 200) {
			console.warn('[UniHalo] sendHaloBindCode 非 200:', res?.statusCode, res?.data);
		}
		const apiError = extractApiError(res?.data);
		if (apiError) {
			return { ok: false, reason: apiError };
		}
		if (res?.statusCode === 200 && res?.data?.success !== false) {
			return {
				ok: true,
				maskedEmail: res?.data?.maskedEmail || '',
				expireSeconds: res?.data?.expireSeconds || 300
			};
		}
		return { ok: false, reason: extractApiError(res?.data) || '验证码发送失败，请稍后重试' };
	} catch (err) {
		return { ok: false, reason: err?.error || '验证码发送失败' };
	}
}

/**
 * 邮箱验证码确认绑定
 */
export async function performBindHaloAccountByCode(httpApi, { account, code }) {
	const acc = (account || '').trim();
	const verifyCode = (code || '').trim();
	if (!acc) {
		return { ok: false, reason: '请输入 Halo 邮箱或用户名' };
	}
	if (!verifyCode) {
		return { ok: false, reason: '请输入 6 位邮箱验证码' };
	}
	if (!/^\d{6}$/.test(verifyCode)) {
		return { ok: false, reason: '请输入 6 位数字验证码' };
	}

	try {
		const res = await httpApi.bindHaloAccountByCode({ account: acc, code: verifyCode });
		const apiError = extractApiError(res?.data);
		if (apiError) {
			return { ok: false, reason: apiError };
		}
		if (res?.statusCode === 200 && res?.data?.haloUserName) {
			const merged = mergeAndPersistWxUser({
				...res.data,
				haloBindStatus: HALO_BIND_STATUS.BOUND
			});
			return { ok: true, data: merged };
		}
		return { ok: false, reason: extractApiError(res?.data) || '绑定失败，请检查验证码是否正确或已过期' };
	} catch (err) {
		return { ok: false, reason: err?.error || '绑定失败，请稍后重试' };
	}
}

/**
 * 无 Halo 账号时一键创建（仅调用插件 createHaloAccount）
 */
export async function performCreateHaloAccount(httpApi) {
	const wxUserInfo = getWxLoginInfo();
	if (!wxUserInfo?.openid) {
		return { ok: false, reason: '请先完成微信登录' };
	}

	try {
		const res = await httpApi.createHaloAccount();
		const apiError = extractApiError(res?.data);
		if (apiError) {
			return { ok: false, reason: apiError };
		}
		if (res?.statusCode === 200 && res?.data?.haloUserName) {
			const merged = mergeAndPersistWxUser({
				...wxUserInfo,
				...res.data,
				haloBindStatus: HALO_BIND_STATUS.CREATED
			});
			return { ok: true, data: merged };
		}
		return { ok: false, reason: extractApiError(res?.data) || '创建 Halo 账号失败' };
	} catch (err) {
		return { ok: false, reason: err?.error || '创建 Halo 账号失败' };
	}
}

/**
 * 解绑 Halo 账号关联
 */
export async function performUnbindHaloAccount(httpApi) {
	const wxUserInfo = getWxLoginInfo();
	if (!wxUserInfo?.openid) {
		return { ok: false, reason: '请先完成微信登录' };
	}
	if (needsHaloBindChoice(wxUserInfo)) {
		return { ok: false, reason: '当前未关联 Halo 账号' };
	}

	try {
		const res = await httpApi.unbindHaloAccount();
		const apiError = extractApiError(res?.data);
		if (apiError) {
			return { ok: false, reason: apiError };
		}
		if (res?.statusCode === 200 && res?.data?.openid) {
			const merged = mergeAndPersistWxUser({
				...wxUserInfo,
				...res.data,
				haloUserName: '',
				haloBindStatus: HALO_BIND_STATUS.UNBOUND
			});
			return { ok: true, data: merged };
		}
		return { ok: false, reason: extractApiError(res?.data) || '解绑失败，请稍后重试' };
	} catch (err) {
		return { ok: false, reason: err?.error || '解绑失败，请稍后重试' };
	}
}

/**
 * 跳过 Halo 绑定（微信登录已完成）
 */
export function finalizeLoginAfterHaloBind(data, { skipped = false } = {}) {
	return mergeAndPersistWxUser({
		...data,
		haloBindSkipped: skipped
	});
}
