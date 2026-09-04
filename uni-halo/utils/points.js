/**
 * 积分系统工具
 */

import { checkHasWxLogin } from '@/utils/auth.js';
import { getAppConfigs } from '@/config/index.js';
import {
	getLocalPointsOverview,
	localEarnByAction,
	localSignIn,
	getLocalPointsHistory,
	hasLocalPointsEarned
} from '@/utils/pointsLocal.js';

export const POINTS_ACTIONS = {
	BROWSE: 'browse',
	COMMENT: 'comment',
	UPVOTE: 'upvote',
	SIGN_IN: 'sign_in'
};

export const POINTS_ACTION_LABELS = {
	browse: '浏览文章',
	comment: '发表评论',
	upvote: '点赞文章',
	sign_in: '每日签到',
	admin_adjust: '系统调整'
};

/** 根据连续签到天数生成 7 日周期展示数据（索引 0 = 第 1 天） */
export const buildWeekSignDisplay = (signInStatus) => {
	const consecutive = Number(signInStatus?.consecutiveDays || 0);
	const signedToday = !!signInStatus?.signedToday;
	const records = Array(7).fill(false);
	if (consecutive <= 0) {
		return { records, todayIndex: 0 };
	}
	const dayInCycle = consecutive % 7 || 7;
	for (let i = 0; i < dayInCycle && i < 7; i++) {
		records[i] = true;
	}
	const todayIndex = signedToday
		? Math.min(dayInCycle - 1, 6)
		: Math.min(dayInCycle, 6);
	return { records, todayIndex };
};

const parseDirectApiBody = (res) => {
	if (!res) return {};
	if (res.data !== undefined && res.statusCode !== undefined) {
		return res.data || {};
	}
	return res;
};

const coerceBool = (val) => {
	if (val === true || val === 1 || val === '1' || val === 'true') return true;
	if (val === false || val === 0 || val === '0' || val === 'false') return false;
	return !!val;
};

/** 从配置对象中解析 pointsConfig（兼容不同层级） */
export const resolvePointsConfig = (configs) => {
	const source = configs || getAppConfigs() || {};
	return source.pointsConfig || source.pluginConfig?.pointsConfig || {};
};

/** 判断积分功能是否在后台开启 */
export const resolvePointsEnabled = (pointsConfig) => {
	if (!pointsConfig || typeof pointsConfig !== 'object') return false;
	if (coerceBool(pointsConfig.enabled)) return true;
	if (pointsConfig.enabled === false || pointsConfig.enabled === 'false' || pointsConfig.enabled === 0) {
		return false;
	}
	// 后台已下发 rules 但未写 enabled 字段时，视为已开启
	return !!(pointsConfig.rules && Object.keys(pointsConfig.rules).length);
};

export const getPointsConfig = (configs) => {
	return resolvePointsConfig(configs);
};

export const isPointsEnabled = (configs) => {
	return resolvePointsEnabled(getPointsConfig(configs));
};

/** @deprecated 与 isPointsEnabled 等价，保留兼容 */
export const isPointsFeatureActive = (configs) => {
	return isPointsEnabled(configs);
};

export const isPointsApiError = (err) => {
	if (!err) return false;
	if (err.isHtml || err.statusCode === 404) return true;
	const msg = String(err.error || err.message || '');
	return msg.includes('接口不存在')
		|| msg.includes('插件未启用')
		|| msg.includes('被拦截')
		|| msg.includes('未部署')
		|| msg.includes('尚未部署');
};

export const resolvePointsApiPaths = (configs) => {
	const pointsConfig = resolvePointsConfig(configs);
	const custom = pointsConfig?.apiPaths || {};
	return {
		overview: custom.overview || '/points/overview',
		earn: custom.earn || '/points/earn',
		signIn: custom.signIn || '/points/sign-in',
		history: custom.history || '/points/history',
		earnStatus: custom.earnStatus || '/points/earn/status'
	};
};

const POINTS_API_FALLBACKS = {
	overview: ['/points/overview', '/getPointsOverview', '/pointsOverview'],
	earn: ['/points/earn', '/pointsEarn', '/earnPoints'],
	signIn: ['/points/sign-in', '/points/signIn', '/pointsSignIn', '/dailySignIn'],
	history: ['/points/history', '/getPointsHistory', '/pointsHistory'],
	earnStatus: ['/points/earn/status', '/getPointsEarnStatus', '/pointsEarnStatus']
};

export const getPointsApiPathCandidates = (key, configs) => {
	const paths = resolvePointsApiPaths(configs);
	const primary = paths[key];
	const fallbacks = POINTS_API_FALLBACKS[key] || [];
	const list = [primary, ...fallbacks].filter(Boolean);
	return [...new Set(list)];
};

export const normalizePointsApiError = (err) => {
	if (!err) return '操作失败';
	if (err.isHtml || String(err.error || '').includes('被拦截')) {
		return '积分接口未部署或未开放访问，请确认插件已升级并重启 Halo';
	}
	return err.error || err.message || '操作失败';
};

let _pointsApiReady = null;
let _pointsApiMode = null; // 'remote' | 'local'

export const isPointsApiReady = () => _pointsApiReady;
export const getPointsApiMode = () => _pointsApiMode;

/** API 不可用时是否降级本地（默认 true，服务端就绪后自动切回） */
const useLocalFallback = () => {
	const cfg = getPointsConfig();
	if (cfg.localFallback === true) return true;
	if (cfg.localFallback === false && cfg.fallbackOnApiError === false) return false;
	return cfg.fallbackOnApiError !== false;
};

export const resetPointsSession = () => {
	_pointsApiReady = null;
	_pointsApiMode = null;
};

/** 登录后从服务端拉取积分（清缓存后恢复数据） */
export const syncPointsAfterLogin = async (httpApi) => {
	if (!checkHasWxLogin() || !isPointsEnabled()) {
		resetPointsSession();
		return null;
	}
	resetPointsSession();
	const api = httpApi?.v2 || httpApi;
	return fetchPointsOverview(api);
};

const applyOverviewPayload = (data, mode) => {
	_pointsApiReady = true;
	_pointsApiMode = mode;
	emitPointsUpdated({
		balance: data?.balance,
		signInStatus: data?.signInStatus,
		todayEarned: data?.todayEarned,
		localMode: mode === 'local'
	});
	return data;
};

export const fetchPointsOverview = async (httpApi) => {
	if (!isPointsEnabled() || !checkHasWxLogin()) {
		_pointsApiReady = false;
		_pointsApiMode = null;
		return null;
	}
	if (!httpApi?.getPointsOverview) {
		if (useLocalFallback()) {
			const local = getLocalPointsOverview();
			if (local) return applyOverviewPayload(local, 'local');
		}
		_pointsApiReady = false;
		_pointsApiMode = null;
		return null;
	}
	try {
		const res = await httpApi.getPointsOverview();
		if (res?.statusCode && res.statusCode !== 200) {
			throw { isHtml: true };
		}
		const data = parseDirectApiBody(res);
		return applyOverviewPayload(data, 'remote');
	} catch (err) {
		if (useLocalFallback() && isPointsApiError(err)) {
			const local = getLocalPointsOverview();
			if (local) return applyOverviewPayload(local, 'local');
		}
		if (isPointsApiError(err)) {
			_pointsApiReady = false;
			_pointsApiMode = null;
		}
		return null;
	}
};

export const probePointsApi = (httpApi) => fetchPointsOverview(httpApi);

export const showPointsToast = (amount, message, duration = 2000) => {
	const points = Number(amount) || 0;
	if (points <= 0 && !message) return;
	uni.$emit('points_toast_show', {
		amount: points,
		message: message || (points > 0 ? `+${points} 积分` : `${points} 积分`),
		duration
	});
};

export const emitPointsUpdated = (payload = {}) => {
	uni.$emit('points_updated', payload);
};

export const handlePointsEarnResponse = (body) => {
	const data = parseDirectApiBody(body);
	const earned = Number(data?.earned ?? data?.pointsEarned ?? 0) || 0;
	const balance = data?.balance != null ? Number(data.balance) : null;

	if (earned !== 0) {
		const tip = data?.message || (earned > 0 ? `+${earned} 积分` : `${earned} 积分`);
		showPointsToast(earned, tip);
	} else if (data?.message && data?.capped) {
		showPointsToast(0, data.message);
	}

	if (balance != null) {
		emitPointsUpdated({ balance, signInStatus: data?.signInStatus, todayEarned: data?.todayEarned });
	}

	return {
		earned,
		balance,
		capped: !!data?.capped,
		message: data?.message || '',
		signInStatus: data?.signInStatus || null
	};
};

const runLocalEarnByAction = (action, payload = {}) => {
	return handlePointsEarnResponse(localEarnByAction(action, payload));
};

export const checkPointsEarnStatus = async (httpApi, action, articleName) => {
	if (!isPointsEnabled() || !checkHasWxLogin() || !action || !articleName) {
		return { earnedToday: false };
	}
	if (useLocalFallback() && (_pointsApiMode === 'local' || !httpApi?.getPointsEarnStatus)) {
		return {
			earnedToday: hasLocalPointsEarned(action, articleName),
			localMode: true
		};
	}
	try {
		const res = await httpApi.getPointsEarnStatus({ action, articleName });
		const data = parseDirectApiBody(res);
		return {
			earnedToday: !!(data?.earnedToday ?? data?.alreadyEarned),
			earned: Number(data?.earned || 0) || 0,
			capped: !!data?.capped,
			message: data?.message || ''
		};
	} catch (err) {
		if (useLocalFallback() && isPointsApiError(err)) {
			_pointsApiMode = 'local';
			return {
				earnedToday: hasLocalPointsEarned(action, articleName),
				localMode: true
			};
		}
		return { earnedToday: false };
	}
};

export const tryEarnPointsByAction = async (httpApi, action, payload = {}) => {
	if (!isPointsEnabled() || !checkHasWxLogin()) {
		return { earned: 0 };
	}
	if (useLocalFallback() && (_pointsApiMode === 'local' || !httpApi?.earnPointsByAction)) {
		return runLocalEarnByAction(action, payload);
	}
	try {
		const res = await httpApi.earnPointsByAction({
			action,
			articleName: payload.articleName || payload.refName || '',
			title: payload.title || '',
			refId: payload.refId || ''
		});
		return handlePointsEarnResponse(res);
	} catch (err) {
		if (useLocalFallback() && isPointsApiError(err)) {
			_pointsApiMode = 'local';
			return runLocalEarnByAction(action, payload);
		}
		return { earned: 0, error: normalizePointsApiError(err) };
	}
};

export const performPointsSignIn = async (httpApi) => {
	if (!checkHasWxLogin()) {
		return { ok: false, reason: '请先登录' };
	}
	if (!isPointsEnabled()) {
		return { ok: false, reason: '积分功能未开启' };
	}
	if (useLocalFallback() && _pointsApiMode === 'local') {
		const result = localSignIn();
		if (!result.ok) return result;
		handlePointsEarnResponse(result);
		return { ok: true, ...result };
	}
	if (!httpApi?.postPointsSignIn) {
		if (useLocalFallback()) {
			_pointsApiMode = 'local';
			const result = localSignIn();
			if (!result.ok) return result;
			handlePointsEarnResponse(result);
			return { ok: true, ...result };
		}
		return { ok: false, reason: '签到接口未就绪' };
	}
	try {
		const res = await httpApi.postPointsSignIn();
		const data = parseDirectApiBody(res);
		if (data?.error && !data?.earned && data?.signedToday !== true) {
			return { ok: false, reason: data.error };
		}
		const result = handlePointsEarnResponse(data);
		_pointsApiMode = 'remote';
		return {
			ok: true,
			...result,
			signInStatus: data?.signInStatus || null
		};
	} catch (err) {
		if (useLocalFallback() && isPointsApiError(err)) {
			_pointsApiMode = 'local';
			const result = localSignIn();
			if (!result.ok) return result;
			handlePointsEarnResponse(result);
			return { ok: true, ...result };
		}
		return {
			ok: false,
			reason: normalizePointsApiError(err)
		};
	}
};

export const fetchPointsHistory = async (httpApi, params = {}) => {
	if (!checkHasWxLogin()) {
		return Promise.reject({ error: '请先登录' });
	}
	if (useLocalFallback() && _pointsApiMode === 'local') {
		return getLocalPointsHistory(params);
	}
	try {
		const res = await httpApi.getPointsHistory(params);
		if (res?.statusCode && res.statusCode !== 200) {
			throw { isHtml: true };
		}
		return parseDirectApiBody(res);
	} catch (err) {
		if (useLocalFallback() && isPointsApiError(err)) {
			_pointsApiMode = 'local';
			return getLocalPointsHistory(params);
		}
		throw err;
	}
};

export const formatPointsHistoryItem = (item) => {
	if (!item) return null;
	const action = item.action || item.type || '';
	const points = Number(item.points ?? item.amount ?? 0) || 0;
	return {
		id: item.id || `${action}-${item.createTime || Date.now()}`,
		action,
		actionLabel: item.actionLabel || POINTS_ACTION_LABELS[action] || '积分变动',
		points,
		pointsText: points > 0 ? `+${points}` : `${points}`,
		balance: item.balance != null ? Number(item.balance) : null,
		description: item.description || item.refTitle || item.title || '',
		refName: item.refName || item.articleName || '',
		createTime: item.createTime || item.recordTime || ''
	};
};
