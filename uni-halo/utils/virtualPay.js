/**
 * 虚拟支付（微信道具直购）通用工具。
 *
 * 背景：装扮的现金购买入口从「权益直购页」迁到了「身份中心 → 装扮商店」，
 * 两个页面都要能走完一模一样的支付链路。这里集中实现，避免两处各写一份
 * 而在后续维护中出现行为漂移（尤其是发货判定与终态处理这类资金相关逻辑）。
 *
 * 约定：
 * - 本模块**不含任何 UI**（不弹窗、不 showLoading），提示与加载态由调用方决定；
 * - 支付成功的**前端回调只作展示**，发货一律以服务端推送/查单为准；
 * - `payVirtualGoods` 把结果归一成 {ok} / {cancelled} / {terminal} / {pending} / {envBlocked} / {error}，
 *   调用方只需按类别给提示，不需要理解底层细节。
 */

const WX_IOS_MIN_VERSION = '8.0.68';
const SDK_MIN_VERSION = '2.19.2';
/** 到账轮询：15 次 × 2 秒 */
const POLL_TOTAL = 15;
const POLL_INTERVAL = 2000;

export function compareVersion(a, b) {
	const pa = String(a || '').split('.').map(Number);
	const pb = String(b || '').split('.').map(Number);
	for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
		const va = pa[i] || 0;
		const vb = pb[i] || 0;
		if (va !== vb) return va > vb ? 1 : -1;
	}
	return 0;
}

/**
 * iOS 端需微信客户端 >= 8.0.68 才能调用 wx.requestVirtualPayment；
 * 基础库需 >= 2.19.2。
 */
export function checkPayEnvironment() {
	// #ifdef MP-WEIXIN
	const sys = uni.getSystemInfoSync();
	if (sys.platform === 'ios' && compareVersion(sys.version, WX_IOS_MIN_VERSION) < 0) {
		return { ok: false, reason: '请将微信更新至最新版后再进行支付' };
	}
	if (compareVersion(sys.SDKVersion, SDK_MIN_VERSION) < 0 || typeof wx.requestVirtualPayment !== 'function') {
		return { ok: false, reason: '当前微信版本过低，不支持虚拟支付，请升级微信后重试' };
	}
	return { ok: true };
	// #endif
	// #ifndef MP-WEIXIN
	return { ok: false, reason: '当前平台暂不支持虚拟支付，请使用微信小程序访问' };
	// #endif
}

/** 调起虚拟支付；用户取消也 resolve（由调用方决定提示文案）。 */
export function requestVirtualPayment(payData) {
	// #ifdef MP-WEIXIN
	return new Promise((resolve) => {
		wx.requestVirtualPayment({
			signData: payData.signData,
			paySig: payData.paySig,
			signature: payData.signature,
			mode: payData.mode,
			success: () => resolve({ ok: true }),
			fail: (err) => resolve({ ok: false, reason: (err && err.errMsg) || '支付取消' })
		});
	});
	// #endif
	// #ifndef MP-WEIXIN
	return Promise.resolve({ ok: false, reason: '当前平台暂不支持虚拟支付' });
	// #endif
}

/** 解包项目请求封装的 {statusCode, data}；非 200 抛出 {error} 供上层提示。 */
export function unwrapPayResponse(res) {
	if (res && res.statusCode && res.statusCode !== 200) {
		throw { error: res?.data?.error || res?.data?.detail || '加载失败' };
	}
	return res?.data || res || {};
}

/**
 * 轮询查单直到到账。
 *
 * @returns {ok:true, goodsType, goodsName}
 *        | {ok:false, terminal:true, message}  终态不可发货（如重复购买，需人工退款）
 *        | {ok:false}                          轮询结束仍未到账（后端 5 分钟兜底查单会补发）
 */
export async function waitPayDelivered(apiV2, outTradeNo) {
	for (let i = 0; i < POLL_TOTAL; i++) {
		await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL));
		try {
			const res = await apiV2.queryPayOrder(outTradeNo);
			const body = unwrapPayResponse(res);
			if (body.delivered) {
				// 道具直购只发放具体权益，不改变积分余额
				return { ok: true, goodsType: body.goodsType || '', goodsName: body.goodsName || '' };
			}
			// 终态不可发货（如重复购买已拥有的装扮）：立刻停止轮询并带上原因，
			// 否则用户要空等满 15 次轮询才看到笼统的「稍后自动到账」。
			if (body.status === 'needs_refund' || body.message) {
				return { ok: false, terminal: true, message: body.message || '' };
			}
		} catch (err) {
			// 轮询失败继续重试
		}
	}
	return { ok: false };
}

/**
 * 完整走一遍现金直购：环境校验 → 下单 → 调起支付 → 轮询到账。
 *
 * @param {Object}   options.apiV2      this.$httpApi.v2
 * @param {string}   options.goodsId    支付商品 ID（来自 payConfig.goods[].goodsId）
 * @param {Function} options.onWaiting  支付成功、开始等待到账时回调（用于显示「确认到账中...」）
 */
export async function payVirtualGoods({ apiV2, goodsId, onWaiting } = {}) {
	if (!apiV2 || !goodsId) {
		return { ok: false, error: '下单参数缺失，请稍后重试' };
	}
	const envCheck = checkPayEnvironment();
	if (!envCheck.ok) {
		return { ok: false, envBlocked: true, reason: envCheck.reason };
	}

	let order;
	try {
		order = unwrapPayResponse(await apiV2.createPayOrder(goodsId));
	} catch (err) {
		// 后端会在下单前拦下「装扮未登记」「已拥有」等配置/状态问题，原文最准确，直接透传
		return { ok: false, error: err?.error || '下单失败，请稍后重试' };
	}
	if (!order?.payData || !order?.outTradeNo) {
		return { ok: false, error: order?.error || '下单失败，请稍后重试' };
	}

	const payResult = await requestVirtualPayment(order.payData);
	if (!payResult.ok) {
		return { ok: false, cancelled: true, reason: payResult.reason || '支付已取消' };
	}

	if (typeof onWaiting === 'function') {
		onWaiting(order.outTradeNo);
	}
	const delivered = await waitPayDelivered(apiV2, order.outTradeNo);
	if (delivered.ok) {
		return { ok: true, goodsType: delivered.goodsType, goodsName: delivered.goodsName };
	}
	if (delivered.terminal) {
		return { ok: false, terminal: true, message: delivered.message };
	}
	return { ok: false, pending: true };
}

export default {
	compareVersion,
	checkPayEnvironment,
	requestVirtualPayment,
	unwrapPayResponse,
	waitPayDelivered,
	payVirtualGoods
};
