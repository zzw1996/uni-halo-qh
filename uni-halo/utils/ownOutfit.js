/**
 * 当前用户自己的装扮缓存（头像框 / 昵称框 / 主题）。
 *
 * 为什么需要缓存：装扮的渲染点分散在多个页面（评论输入弹窗、个人资料弹窗、
 * 充值页预览等），逐页调用 getOutfitStatus 会重复发请求。这里按 TTL 缓存一次，
 * 各页面同步读取。
 *
 * 与 themeSkin.js 同一套约定：
 * - 服务端下发值是权威来源，本缓存只用于「先渲染、后纠正」，不参与业务判断
 * - uni storage 不是 Vue 响应式依赖，调用方必须把结果存进 data 再渲染
 *   （阅读券联调时踩过这个坑：写成 computed 会永久缓存首次结果）
 * - 登出/解绑时必须 clear，避免装扮残留给下一个使用者
 */
import { getOpenid } from '@/utils/auth.js';

const STORAGE_KEY = 'unihalo_own_outfit';

/** 缓存有效期（5 分钟）：超过则重新拉取 */
const CACHE_TTL_MS = 5 * 60 * 1000;

const EMPTY = { avatarFrame: '', nickFrame: '', theme: '', fetchAt: 0 };

/** 同步读取缓存（storage 不可用时返回空值，调用方无需 try/catch）。 */
export function getCachedOwnOutfit() {
	try {
		const raw = uni.getStorageSync(STORAGE_KEY);
		if (!raw) return { ...EMPTY };
		const data = typeof raw === 'string' ? JSON.parse(raw) : raw;
		return {
			avatarFrame: data?.avatarFrame || '',
			nickFrame: data?.nickFrame || '',
			theme: data?.theme || '',
			fetchAt: Number(data?.fetchAt || 0)
		};
	} catch (err) {
		return { ...EMPTY };
	}
}

export function getOwnAvatarFrame() {
	return getCachedOwnOutfit().avatarFrame;
}

export function getOwnNickFrame() {
	return getCachedOwnOutfit().nickFrame;
}

/** 写入缓存，返回规范化后的对象（供调用方直接渲染）。 */
export function cacheOwnOutfit(data) {
	const payload = {
		avatarFrame: data?.avatarFrame || '',
		nickFrame: data?.nickFrame || '',
		theme: data?.theme || '',
		fetchAt: Date.now()
	};
	try {
		uni.setStorageSync(STORAGE_KEY, JSON.stringify(payload));
	} catch (err) {
		// 存储失败不影响本次渲染
	}
	return payload;
}

/** 登出 / 解绑账号时清空，避免装扮残留给下一个使用者。 */
export function clearOwnOutfit() {
	try {
		uni.removeStorageSync(STORAGE_KEY);
	} catch (err) {
		// ignore
	}
}

/**
 * 确保缓存新鲜：未登录或缓存未过期时直接返回缓存，否则拉取一次并更新。
 *
 * @param apiV2 this.$httpApi.v2（与 syncPointsAfterLogin 的入参口径一致）
 * @returns 规范化后的装扮对象；调用方需写入 data 再渲染
 */
export async function ensureOwnOutfit(apiV2, { maxAgeMs = CACHE_TTL_MS, force = false } = {}) {
	const cached = getCachedOwnOutfit();
	if (!force && cached.fetchAt && Date.now() - cached.fetchAt < maxAgeMs) {
		return cached;
	}
	if (!getOpenid() || !apiV2?.getOutfitStatus) {
		return cached;
	}
	try {
		const res = await apiV2.getOutfitStatus();
		const body = res?.data || res || {};
		return cacheOwnOutfit(body);
	} catch (err) {
		// 拉取失败沿用旧缓存：宁可用旧头像框，也不要因为网络抖动闪掉
		return cached;
	}
}

export default {
	getCachedOwnOutfit,
	getOwnAvatarFrame,
	getOwnNickFrame,
	cacheOwnOutfit,
	clearOwnOutfit,
	ensureOwnOutfit
};
