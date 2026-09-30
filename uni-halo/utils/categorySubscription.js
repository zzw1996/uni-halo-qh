/**
 * 分类订阅的公共读写（分类文章列表页 / 文章详情页的订阅入口共用）。
 *
 * <p>服务端是「整体替换 postCategories」的口径：任何订阅变更都要先读全量、
 * 改一份、再整体提交；提交成功后写版本号并广播事件——首页靠版本号（切回时比对）
 * 与事件（页面还活着时立刻刷新）双通道感知，两条路缺一不可。</p>
 *
 * <p>发现页（discover.vue）保留了它自己的卡片投影式订阅流程（开关状态挂在卡片上、
 * 按卡片状态整体提交），本模块与它的保存载荷、版本号、事件完全同一套约定；
 * 两处改动时务必一起改。</p>
 */

import { checkHasWxLogin } from './auth.js';

const SUBSCRIPTION_REV_KEY = 'category.subscription.rev';

/**
 * 读取当前订阅状态（含服务端算好的额度）。
 *
 * 未登录返回 loggedIn=false（订阅清单为空）；接口异常按「一个都没订」处理，
 * 让调用方安静降级，不弹错误打扰。
 */
export async function fetchCategorySubscription(httpApi) {
	if (!checkHasWxLogin()) {
		return { loggedIn: false, subscribed: [], inherited: [], limits: null };
	}
	try {
		const res = await httpApi.getDigestSubscription();
		const body = res?.data || res || {};
		const sub = body.subscription || {};
		return {
			loggedIn: true,
			subscribed: Array.isArray(sub.postCategories) ? sub.postCategories : [],
			inherited: Array.isArray(sub.inheritedCategories) ? sub.inheritedCategories : [],
			limits: sub.limits || null
		};
	} catch (err) {
		return { loggedIn: true, subscribed: [], inherited: [], limits: null };
	}
}

/** 订阅上限：以服务端下发为准，没下发时按 5（服务端仍会拦，界面不至于放开） */
export function subscriptionLimit(limits) {
	const limit = Number(limits?.limit);
	return Number.isFinite(limit) && limit > 0 ? limit : 5;
}

/** 额度是否已满（暂停的分类仍占额度位置，不能越过它再订新的） */
export function isQuotaFull(state) {
	return (state?.subscribed || []).length >= subscriptionLimit(state?.limits);
}

/** 解锁入口是否可用（「积分解锁额度」开关开着且没买满） */
export function canUnlockQuota(state) {
	const limits = state?.limits || {};
	return limits.purchaseEnabled === true && limits.canPurchase === true;
}

/**
 * 订阅一个分类：读全量 → 追加 → 整体提交。
 *
 * 返回 { ok, already?, reason?, saved?, inherited?, limits? }：
 * - reason === 'not-logged-in'：调用方先引导登录；
 * - reason === 'quota-full'：调用方引导去解锁额度（入口见 canUnlockQuota）；
 * - 其余 reason 是服务端给的可展示文案，直接 toast 即可。
 */
export async function subscribeCategory(httpApi, categoryName) {
	const state = await fetchCategorySubscription(httpApi);
	if (!state.loggedIn) {
		return { ok: false, reason: 'not-logged-in' };
	}
	if (state.subscribed.includes(categoryName)) {
		return {
			ok: true, already: true,
			saved: state.subscribed, inherited: state.inherited, limits: state.limits
		};
	}
	if (isQuotaFull(state)) {
		return { ok: false, reason: 'quota-full', limits: state.limits };
	}
	const next = state.subscribed.concat([categoryName]);
	try {
		const res = await httpApi.saveDigestSubscription({
			enabled: true,
			// 旧字段（AI 分类 / 关键词 / 来源）已经没有维护界面了，显式清空（与发现页同一载荷）
			categories: [],
			keywords: [],
			sources: [],
			postCategories: next
		});
		const body = res?.data || res || {};
		if (body.error) {
			return { ok: false, reason: body.error, limits: state.limits };
		}
		// 以服务端返回为准：本地拼的清单万一有偏差，这里对齐
		const saved = Array.isArray(body.postCategories) ? body.postCategories : next;
		const inherited = Array.isArray(body.inheritedCategories)
			? body.inheritedCategories : state.inherited;
		notifySubscriptionChanged();
		return { ok: true, saved, inherited, limits: body.limits || state.limits };
	} catch (err) {
		return { ok: false, reason: err?.error || err?.detail || '保存失败，请稍后重试' };
	}
}

/** 订阅变更后广播：版本号（切页比对）+ 事件（活着的页面立刻刷新）双通道 */
export function notifySubscriptionChanged() {
	try {
		uni.setStorageSync(SUBSCRIPTION_REV_KEY, String(Date.now()));
	} catch (e) {
		// 存储不可用就算了：事件还在，下次进页也会重取
	}
	uni.$emit('category_subscription_changed');
}
