/**
 * 视频号入口工具
 *
 * 小程序端用微信开放能力 wx.openChannelsUserProfile 直接打开视频号主页；
 * 基础库低于 2.21.2（该 API 未注入）或 H5 / App 端无法跳转时，
 * 统一降级成「复制视频号 ID + 微信内搜索」的引导，而不是点一下什么反应都没有。
 *
 * 约定：
 * - 本模块**不含卡片 UI**（提示、弹窗由调用方决定），只负责跳转与降级判定；
 * - 不依赖 SDKVersion 数字比较：API 在低版本基础库里压根不存在，
 *   直接判 typeof 就能覆盖，也不会因为版本号格式差异误判。
 */

/** 视频号 ID 形如 sphcqO59YEPCvoe，必须以 sph 开头 */
const FINDER_ID_RE = /^sph[A-Za-z0-9]+$/;

/** 微信原生支持的跳转方式（H5 / App 无此能力） */
const CAN_OPEN_NATIVE = () => {
	// #ifdef MP-WEIXIN
	return typeof wx !== 'undefined' && typeof wx.openChannelsUserProfile === 'function';
	// #endif
	// #ifndef MP-WEIXIN
	return false;
	// #endif
};

const copyText = (text) => new Promise((resolve) => {
	uni.setClipboardData({
		data: text,
		success: () => resolve(true),
		fail: () => resolve(false)
	});
});

/**
 * 打开视频号主页。
 *
 * @param config 后台 channelsConfig 下发的配置
 * @returns {ok:true}                     跳转或引导成功
 *        | {ok:false, reason}            失败原因（调用方决定是否提示）
 */
export async function openChannelsProfile(config) {
	const finderUserName = (config?.finderUserName || '').trim();
	const nickname = (config?.nickname || '').trim() || '视频号';

	if (!finderUserName) {
		return { ok: false, reason: '未配置视频号 ID' };
	}
	if (!FINDER_ID_RE.test(finderUserName)) {
		// 这类错误在后台一眼看不出，但点了不跳转就会被当成小程序 bug，
		// 所以在这里明确报出来：配置里多复制了空格、粘贴成了昵称都会走到这。
		return { ok: false, reason: '视频号 ID 格式不正确（应以 sph 开头），请检查后台「视频号」配置' };
	}

	if (CAN_OPEN_NATIVE()) {
		const opened = await new Promise((resolve) => {
			// #ifdef MP-WEIXIN
			wx.openChannelsUserProfile({
				finderUserName,
				success: () => resolve(true),
				fail: (err) => {
					console.warn('[channels] openChannelsUserProfile failed', err);
					resolve(false);
				}
			});
			// #endif
			// #ifndef MP-WEIXIN
			resolve(false);
			// #endif
		});
		if (opened) {
			return { ok: true };
		}
		// 跳转失败（如用户端微信版本过低、视频号被关闭）不能再静默，
		// 否则用户只会觉得按钮坏了，这里退回复制 ID 的引导。
	}

	const copied = await copyText(finderUserName);
	uni.showModal({
		title: `打开「${nickname}」视频号`,
		content: copied
			? `视频号 ID 已复制：${finderUserName}\n\n打开微信 → 发现 → 视频号 → 搜索框粘贴，即可进入主页。`
			: `请记下视频号 ID：${finderUserName}\n\n打开微信 → 发现 → 视频号 → 搜索进入主页。`,
		showCancel: false,
		confirmText: '知道了'
	});
	return { ok: true, fallback: true };
}

export default { openChannelsProfile };
