/**
 * 微信订阅消息授权（小程序侧）。
 *
 * <p><b>必须先理解的规则</b>：普通类目只能用「一次性订阅消息」——用户点一次授权，
 * 服务端只能发一条。所以这个模块做的不只是「弹个窗」：授权成功后必须把结果
 * <b>上报给服务端记账</b>（微信不提供额度查询，不记账就等于额度丢了）。</p>
 *
 * <p>两条约定（与 utils/virtualPay.js 保持一致）：</p>
 * <ul>
 *   <li><b>模块不含任何 UI</b>：不弹窗、不 showLoading，提示与加载态由调用方决定；</li>
 *   <li><b>结果归一</b>：调用方只按类别给文案，不需要理解微信的返回结构。</li>
 * </ul>
 *
 * <p>调用前提：wx.requestSubscribeMessage 必须在<b>用户点击等交互回调里</b>调用，
 * 不能在没有用户操作时静默调用（官方要求）。用户勾选「总是保持以上选择」后，
 * 后续调用不再弹窗、直接返回之前的选择，因此「用户每次主动点击时调用一次」
 * 就能无感累积额度。</p>
 */

import { getAppConfigs } from '@/config/index.js';
import { checkHasWxLogin } from '@/utils/auth.js';

/** 消息类型 → 文案（与插件后端 PushConfigHelper.KIND_* 对应） */
export const PUSH_KINDS = {
	newPost: 'newPost',
	aiDigest: 'aiDigest'
};

/** 站长配置里的推送开关与模板 ID（读不到就按未开启处理） */
function readPushConfig() {
	const push = getAppConfigs()?.pushConfig || {};
	return { enabled: push.enabled === true, templateId: push.templateId || '' };
}

/** 基础库版本要求（requestSubscribeMessage 从 2.4.4 起提供） */
const MIN_SDK_VERSION = '2.4.4';

/**
 * 本地记账用的存储键。
 *
 * <p>微信侧不提供「还剩几条」的查询，而一次性订阅的额度本来就该由我们替用户记着——
 * 记在本地是为了让「已经同意过」这件事可判断：只有同意过的用户才值得在后续点击里静默续额，
 * 没同意过的人不能被静默调用（那才是诱导订阅）。</p>
 */
const KEY_AGREED = 'push.quota.agreed';
const KEY_BANNED = 'push.quota.banned';
const KEY_LAST_TRY = 'push.quota.lastTryAt';
const KEY_STATUS_CACHE = 'push.quota.statusCache';

/** 续额节流：两次尝试之间至少隔这么久（每人每天最多收一条提醒，不需要更勤） */
const ACCUMULATE_INTERVAL_MS = 12 * 60 * 60 * 1000;

/** 额度低于该值才去续：够用就不打扰（避免无意义的系统弹窗） */
const QUOTA_ENOUGH = 2;

/** 额度查询结果的本地缓存时长：一次点击最多打一次接口 */
const STATUS_CACHE_MS = 5 * 60 * 1000;

function readNumber(key) {
	try {
		return Number(uni.getStorageSync(key) || 0) || 0;
	} catch (e) {
		return 0;
	}
}

function writeStorage(key, value) {
	try {
		uni.setStorageSync(key, value);
	} catch (e) {
		// 存储写失败只影响下次判断，不影响本次流程
	}
}

/**
 * 当前的提醒状态（给页面决定「要不要出现入口、写什么文案」）。
 *
 * @returns {{agreed:boolean, banned:boolean, needsAction:boolean}}
 */
export function pushRemindState() {
	const agreed = readNumber(KEY_AGREED) > 0;
	const banned = readNumber(KEY_BANNED) > 0;
	return { agreed, banned, needsAction: !agreed || banned };
}

/** 授权成功时调用：记住「已同意」，并清掉「被禁用」的旧标记 */
export function markPushAgreed() {
	writeStorage(KEY_AGREED, 1);
	writeStorage(KEY_BANNED, 0);
}

function markPushBanned() {
	writeStorage(KEY_BANNED, 1);
}

function clearStatusCache() {
	writeStorage(KEY_STATUS_CACHE, 0);
}

function compareVersion(v1, v2) {
	const a = String(v1 || '0').split('.').map((n) => parseInt(n, 10) || 0);
	const b = String(v2 || '0').split('.').map((n) => parseInt(n, 10) || 0);
	const len = Math.max(a.length, b.length);
	for (let i = 0; i < len; i++) {
		const left = a[i] || 0;
		const right = b[i] || 0;
		if (left > right) return 1;
		if (left < right) return -1;
	}
	return 0;
}

/** 环境自检：不支持时返回原因，调用方据此决定是提示还是静默跳过 */
export function checkSubscribeEnv() {
	// #ifdef MP-WEIXIN
	const sys = uni.getSystemInfoSync();
	if (typeof wx.requestSubscribeMessage !== 'function') {
		return { ok: false, reason: '当前微信版本过低，无法接收订阅消息，请升级微信后重试' };
	}
	if (compareVersion(sys.SDKVersion, MIN_SDK_VERSION) < 0) {
		return { ok: false, reason: '当前微信基础库版本过低，无法接收订阅消息' };
	}
	return { ok: true };
	// #endif
	// #ifndef MP-WEIXIN
	return { ok: false, reason: '当前平台暂不支持微信订阅消息' };
	// #endif
}

/**
 * 把授权成功的类型上报服务端记账（+1 条额度）。
 *
 * @returns {Promise<{ok:boolean, error?:string}>}
 */
export async function reportQuota(apiV2, kinds) {
	if (!apiV2 || !Array.isArray(kinds) || kinds.length === 0) {
		return { ok: false, error: '没有需要记账的授权' };
	}
	try {
		const res = await apiV2.reportPushQuota(kinds);
		const body = res?.data || res || {};
		if (body.error) {
			return { ok: false, error: body.error };
		}
		return { ok: true, quota: body };
	} catch (err) {
		return { ok: false, error: err?.error || '额度记录失败' };
	}
}

/**
 * 请求订阅授权并上报。
 *
 * <p>上报失败也算授权成功（用户确实点了同意，微信那边额度已经产生）——只是我们没记住，
 * 返回里带 reportError 让调用方决定要不要提示。</p>
 *
 * @param {Object} apiV2  this.$httpApi.v2
 * @param {Array}  pairs  [{ kind: 'newPost', tmplId: '模板ID' }, ...]（最多 3 个）
 * @returns {Promise<{ok, accepted:string[], denied:string[], banned:string[], error?, reportError?}>}
 */
export async function requestSubscribe(apiV2, pairs) {
	const list = (Array.isArray(pairs) ? pairs : []).filter((item) => item && item.tmplId);
	if (list.length === 0) {
		return { ok: false, error: '推送模板未配置，请联系站长' };
	}
	const envCheck = checkSubscribeEnv();
	if (!envCheck.ok) {
		return { ok: false, error: envCheck.reason };
	}

	// #ifdef MP-WEIXIN
	let result;
	try {
		result = await new Promise((resolve, reject) => {
			wx.requestSubscribeMessage({
				tmplIds: list.slice(0, 3).map((item) => item.tmplId),
				success: resolve,
				fail: reject
			});
		});
	} catch (err) {
		return { ok: false, error: err?.errMsg || '订阅请求失败，请稍后重试' };
	}

	const accepted = [];
	const denied = [];
	const banned = [];
	list.slice(0, 3).forEach((item) => {
		const value = result?.[item.tmplId];
		if (value === 'accept') {
			accepted.push(item.kind);
		} else if (value === 'ban') {
			banned.push(item.kind);
		} else {
			denied.push(item.kind);
		}
	});

	if (accepted.length === 0) {
		// ban = 用户在小程序设置里关掉了订阅消息（且不再询问），这时只能引导去设置页
		return {
			ok: false,
			accepted,
			denied,
			banned,
			error: banned.length ? '你已关闭该小程序的订阅消息，可在设置里重新打开' : '已取消订阅'
		};
	}

	const report = await reportQuota(apiV2, accepted);
	// 用户已经同意过：后续可以在他点击的动作里静默续额，不必再让他回来点按钮
	markPushAgreed();
	clearStatusCache();
	if (banned.length) {
		// 「拒绝且不再询问」：记下来，之后不再静默尝试（否则每次点击都会白发一次调用）
		markPushBanned();
	}
	return {
		ok: true,
		accepted,
		denied,
		banned,
		reportError: report.ok ? '' : (report.error || '额度记录失败')
	};
	// #endif
	// #ifndef MP-WEIXIN
	return { ok: false, error: '当前平台暂不支持微信订阅消息' };
	// #endif
}

/**
 * 读取该用户的推送状态（额度、总开关），带 5 分钟本地缓存。
 *
 * <p>只在「要不要续额」的判断里用：一次点击最多打一次接口，页面本身不依赖它。</p>
 */
async function loadStatus(apiV2) {
	const cached = readNumber(KEY_STATUS_CACHE);
	if (cached > Date.now()) {
		const cachedBody = (() => {
			try {
				return uni.getStorageSync(KEY_STATUS_CACHE + '.body') || null;
			} catch (e) {
				return null;
			}
		})();
		if (cachedBody) {
			return cachedBody;
		}
	}
	try {
		const res = await apiV2.getPushStatus();
		const body = res?.data || res || {};
		if (body.error) {
			return null;
		}
		writeStorage(KEY_STATUS_CACHE, Date.now() + STATUS_CACHE_MS);
		try {
			uni.setStorageSync(KEY_STATUS_CACHE + '.body', body);
		} catch (e) {
			// 缓存写失败：下次重新查，不影响本次
		}
		return body;
	} catch (err) {
		return null;
	}
}

/**
 * 在用户点击的动作里静默续一次额度（「同意过之后就不用再管」的实现）。
 *
 * <p><b>为什么能静默</b>：微信的一次性订阅在用户勾了「总是保持以上选择」之后，
 * 再次调用 {@code wx.requestSubscribeMessage} 不再弹窗、直接返回同意，所以
 * 「在用户本来就要做的点击里顺带调一次」就能持续补上额度；服务端每人每天最多发一条，
 * 因此一两条的余量就够用。这也是微信允许的用法：必须在点击回调里调用、且与订阅场景相关
 * （用户正在读文章 → 新文章提醒），不弹窗也不打扰。</p>
 *
 * <p><b>绝不打扰用户的几道闸门</b>：平台/站长开关/模板/登录态都满足、用户明确同意过、
 * 没被「拒绝且不再询问」、距上次尝试超过 {@link ACCUMULATE_INTERVAL_MS}、
 * 且服务端额度确实不够用了（{@link QUOTA_ENOUGH}）——任何一条不满足就直接跳过。
 * 全程不弹自己的 UI，失败也不提示。</p>
 *
 * @returns {Promise<{ok:boolean, skipped?:string}>} skipped 只用于日志/排查
 */
export async function accumulatePushQuota(apiV2) {
	if (!apiV2) {
		return { ok: false, skipped: 'no-api' };
	}
	const envCheck = checkSubscribeEnv();
	if (!envCheck.ok) {
		return { ok: false, skipped: 'env' };
	}
	const config = readPushConfig();
	if (!config.enabled || !config.templateId) {
		return { ok: false, skipped: 'config' };
	}
	try {
		if (!checkHasWxLogin()) {
			return { ok: false, skipped: 'anonymous' };
		}
	} catch (e) {
		return { ok: false, skipped: 'anonymous' };
	}
	const state = pushRemindState();
	// 没同意过：不能替用户做决定（那是诱导订阅），入口交给页面上的按钮
	if (!state.agreed || state.banned) {
		return { ok: false, skipped: state.banned ? 'banned' : 'not-agreed' };
	}
	if (Date.now() - readNumber(KEY_LAST_TRY) < ACCUMULATE_INTERVAL_MS) {
		return { ok: false, skipped: 'throttled' };
	}
	const status = await loadStatus(apiV2);
	if (!status) {
		return { ok: false, skipped: 'no-status' };
	}
	// 用户在「我的 → 接收更新提醒」里关掉了：尊重这个开关，不偷偷续额
	if (status.userEnabled === false) {
		return { ok: false, skipped: 'user-off' };
	}
	if (Number(status.newPostQuota || 0) >= QUOTA_ENOUGH) {
		return { ok: false, skipped: 'enough' };
	}
	writeStorage(KEY_LAST_TRY, Date.now());
	const result = await requestSubscribe(apiV2, [
		{ kind: PUSH_KINDS.newPost, tmplId: config.templateId }
	]);
	return { ok: !!result.ok, skipped: result.ok ? '' : 'denied', result };
}

/** 打开小程序设置页的「订阅消息」开关（用户点了「拒绝且不再询问」时的唯一出路） */
export function openSubscribeSetting() {
	// #ifdef MP-WEIXIN
	return new Promise((resolve) => {
		wx.openSetting({
			// withSubscriptions 为 true 时设置页里才会出现「订阅消息」分组
			withSubscriptions: true,
			success: resolve,
			fail: () => resolve(null)
		});
	});
	// #endif
	// #ifndef MP-WEIXIN
	return Promise.resolve(null);
	// #endif
}

export default {
	PUSH_KINDS,
	checkSubscribeEnv,
	requestSubscribe,
	reportQuota,
	openSubscribeSetting,
	accumulatePushQuota,
	pushRemindState,
	markPushAgreed
};
