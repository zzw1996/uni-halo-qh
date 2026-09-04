/**
 * 积分本地降级（仅 pointsConfig.localFallback=true 时启用，清缓存会丢失，正式环境请用服务端持久化）
 * 数据按 openid 存于 Storage，规则来自 pointsConfig.rules
 */

import { getOpenid } from '@/utils/auth.js';
import { getAppConfigs } from '@/config/index.js';

const STORAGE_KEY = 'APP_POINTS_LOCAL_DATA';

const POINTS_ACTION_LABELS = {
	browse: '浏览文章',
	comment: '发表评论',
	upvote: '点赞文章',
	sign_in: '每日签到'
};

const getPointsConfig = () => {
	const configs = getAppConfigs() || {};
	return configs.pointsConfig || configs.pluginConfig?.pointsConfig || {};
};

const getTodayKey = () => {
	const d = new Date();
	const m = `${d.getMonth() + 1}`.padStart(2, '0');
	const day = `${d.getDate()}`.padStart(2, '0');
	return `${d.getFullYear()}-${m}-${day}`;
};

const readStore = () => {
	try {
		const raw = uni.getStorageSync(STORAGE_KEY);
		return raw ? JSON.parse(raw) : {};
	} catch (e) {
		return {};
	}
};

const writeStore = (store) => {
	uni.setStorageSync(STORAGE_KEY, JSON.stringify(store || {}));
};

const createDefaultUserState = () => ({
	balance: 0,
	todayEarned: 0,
	todayDate: getTodayKey(),
	signInStatus: {
		signedToday: false,
		consecutiveDays: 0,
		weekRecords: [false, false, false, false, false, false, false],
		lastSignInDate: ''
	},
	history: [],
	dailyCounts: { browse: 0, comment: 0, upvote: 0 },
	dailyPoints: { browse: 0, comment: 0, upvote: 0 },
	earnKeys: {}
});

export const getLocalPointsUserState = (openid) => {
	const id = openid || getOpenid();
	if (!id) return null;
	const store = readStore();
	if (!store[id]) {
		store[id] = createDefaultUserState();
		writeStore(store);
	}
	return store[id];
};

const saveLocalPointsUserState = (openid, state) => {
	const id = openid || getOpenid();
	if (!id || !state) return;
	const store = readStore();
	store[id] = state;
	writeStore(store);
};

const resetDailyIfNeeded = (state) => {
	const today = getTodayKey();
	if (state.todayDate === today) return state;
	state.todayDate = today;
	state.todayEarned = 0;
	state.dailyCounts = { browse: 0, comment: 0, upvote: 0 };
	state.dailyPoints = { browse: 0, comment: 0, upvote: 0 };
	if (state.signInStatus) {
		state.signInStatus.signedToday = state.signInStatus.lastSignInDate === today;
	}
	return state;
};

const pushHistory = (state, item) => {
	state.history = [item, ...(state.history || [])].slice(0, 200);
};

const getRule = (action) => {
	const rules = getPointsConfig()?.rules || {};
	if (action === 'sign_in') return rules.signIn || { points: 10, consecutiveBonus: [] };
	return rules[action] || { points: 0, dailyMaxCount: 0, dailyMaxPoints: 0 };
};

const calcSignInPoints = (state, signInRule) => {
	const base = Number(signInRule?.points || 10);
	let bonus = 0;
	const nextDays = Number(state.signInStatus?.consecutiveDays || 0) + 1;
	const bonuses = Array.isArray(signInRule?.consecutiveBonus) ? signInRule.consecutiveBonus : [];
	bonuses.forEach((item) => {
		if (Number(item?.days) === nextDays) {
			bonus += Number(item?.bonus || 0);
		}
	});
	return base + bonus;
};

const applyEarn = (state, action, points, description, refName = '') => {
	const earned = Number(points) || 0;
	if (earned <= 0) return { earned: 0, capped: true, message: '今日该项积分已达上限' };
	state.balance = Number(state.balance || 0) + earned;
	state.todayEarned = Number(state.todayEarned || 0) + earned;
	state.dailyPoints[action] = Number(state.dailyPoints?.[action] || 0) + earned;
	pushHistory(state, {
		id: `${action}-${Date.now()}`,
		action,
		actionLabel: POINTS_ACTION_LABELS[action] || '积分变动',
		points: earned,
		balance: state.balance,
		description,
		refName,
		createTime: new Date().toISOString()
	});
	return {
		earned,
		balance: state.balance,
		capped: false,
		message: `+${earned} 积分`
	};
};

const canEarnAction = (state, action, articleName, rule) => {
	const maxCount = Number(rule?.dailyMaxCount ?? 0);
	const maxPoints = Number(rule?.dailyMaxPoints ?? 0);
	const perPoints = Number(rule?.points ?? 0);
	if (perPoints <= 0) return { ok: false, reason: '未配置积分规则' };
	if (articleName) {
		const key = `${action}:${articleName}`;
		if (state.earnKeys?.[key]) return { ok: false, reason: '已领取过' };
	}
	if (maxCount > 0 && Number(state.dailyCounts?.[action] || 0) >= maxCount) {
		return { ok: false, reason: '今日次数已达上限' };
	}
	if (maxPoints > 0 && Number(state.dailyPoints?.[action] || 0) + perPoints > maxPoints) {
		return { ok: false, reason: '今日积分已达上限' };
	}
	return { ok: true, points: perPoints };
};

export const getLocalPointsOverview = (openid) => {
	const state = getLocalPointsUserState(openid);
	if (!state) return null;
	resetDailyIfNeeded(state);
	saveLocalPointsUserState(openid, state);
	return {
		balance: state.balance,
		todayEarned: state.todayEarned,
		signInStatus: state.signInStatus,
		localMode: true
	};
};

export const localEarnByAction = (action, payload = {}, openid) => {
	const state = getLocalPointsUserState(openid);
	if (!state) return { earned: 0 };
	resetDailyIfNeeded(state);
	const articleName = payload.articleName || payload.refName || '';
	const check = canEarnAction(state, action, articleName, getRule(action));
	if (!check.ok) {
		saveLocalPointsUserState(openid, state);
		return { earned: 0, capped: true, message: check.reason };
	}
	if (articleName) {
		state.earnKeys[`${action}:${articleName}`] = true;
	}
	state.dailyCounts[action] = Number(state.dailyCounts[action] || 0) + 1;
	const result = applyEarn(
		state,
		action,
		check.points,
		payload.title || payload.description || '',
		articleName
	);
	saveLocalPointsUserState(openid, state);
	return { ...result, signInStatus: state.signInStatus, localMode: true };
};

export const localSignIn = (openid) => {
	const state = getLocalPointsUserState(openid);
	if (!state) return { ok: false, reason: '请先登录' };
	resetDailyIfNeeded(state);
	const today = getTodayKey();
	if (state.signInStatus?.lastSignInDate === today) {
		return { ok: false, reason: '今日已签到' };
	}
	const yesterday = new Date();
	yesterday.setDate(yesterday.getDate() - 1);
	const yKey = `${yesterday.getFullYear()}-${`${yesterday.getMonth() + 1}`.padStart(2, '0')}-${`${yesterday.getDate()}`.padStart(2, '0')}`;
	const consecutive = state.signInStatus?.lastSignInDate === yKey
		? Number(state.signInStatus?.consecutiveDays || 0) + 1
		: 1;
	const earned = calcSignInPoints(state, getRule('sign_in'));
	state.balance = Number(state.balance || 0) + earned;
	state.todayEarned = Number(state.todayEarned || 0) + earned;
	state.signInStatus = {
		signedToday: true,
		consecutiveDays: consecutive,
		lastSignInDate: today,
		weekRecords: (() => {
			const records = [false, false, false, false, false, false, false];
			const dayInCycle = consecutive % 7 || 7;
			for (let i = 0; i < dayInCycle && i < 7; i++) {
				records[i] = true;
			}
			return records;
		})()
	};
	pushHistory(state, {
		id: `sign_in-${Date.now()}`,
		action: 'sign_in',
		actionLabel: POINTS_ACTION_LABELS.sign_in,
		points: earned,
		balance: state.balance,
		description: `连续签到 ${consecutive} 天`,
		createTime: new Date().toISOString()
	});
	saveLocalPointsUserState(openid, state);
	return {
		ok: true,
		earned,
		balance: state.balance,
		message: `+${earned} 积分`,
		signInStatus: state.signInStatus,
		localMode: true
	};
};

export const hasLocalPointsEarned = (action, articleName, openid) => {
	if (!action || !articleName) return false;
	const state = getLocalPointsUserState(openid);
	if (!state) return false;
	resetDailyIfNeeded(state);
	return !!state.earnKeys?.[`${action}:${articleName}`];
};

export const getLocalPointsHistory = ({ page = 0, size = 15, openid } = {}) => {
	const state = getLocalPointsUserState(openid);
	if (!state) return { items: [], total: 0, hasNext: false };
	const list = state.history || [];
	const start = page * size;
	const items = list.slice(start, start + size);
	return {
		items,
		total: list.length,
		hasNext: start + size < list.length,
		localMode: true
	};
};
