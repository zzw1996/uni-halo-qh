/**
 * 用户投稿（阅读赚积分 → 花积分发文章 / 发瞬间）。
 *
 * 约定：
 * - 「能不能发、发出来是直发还是待审」的权威结论在服务端（getUserPublishStatus），
 *   本文件只做转发与文案，不自己拼规则——否则后台一改策略，前端就会出现
 *   「按钮能点但一发就报错」。
 * - 发布走服务端：小程序端原本拿 personalToken 直连 Halo，而该 token 随匿名配置下发，
 *   沿用前端发布等于把积分门槛做成装饰品。
 */

/** 能否发（含原因），供按钮与提示使用 */
export function readableDisabledReason(status) {
	if (!status) return '投稿功能暂不可用';
	return status.articleDisabledReason || status.momentDisabledReason || '';
}

/** 入口是否展示（总开关 + 展示开关 + 积分门槛，判定都在服务端完成） */
export function shouldShowPublishEntry(status) {
	return !!(status && status.enabled && status.visible);
}

/** 未达积分门槛时的提示：还差多少 */
export function buildThresholdHint(status) {
	if (!status) return '';
	const gap = Number(status.pointsToUnlock || 0);
	if (gap <= 0) return '';
	return `再赚 ${gap} 积分即可投稿`;
}

/**
 * 取投稿状态。
 * 未登录/接口不可用时返回 null（调用方按「不展示入口」处理，不弹错误打扰用户）。
 */
export async function fetchUserPublishStatus(httpApi) {
	try {
		const res = await httpApi.getUserPublishStatus();
		const data = res?.data || res;
		if (!data || typeof data !== 'object') return null;
		if (data.error) return null;
		return data;
	} catch (err) {
		return null;
	}
}

/** 提交文章；返回 { ok, data?, reason? } */
export async function submitUserArticle(httpApi, payload) {
	return submit(() => httpApi.publishUserArticle(payload), '投稿失败，请稍后重试');
}

/** 读一篇投稿的详情（编辑回填）：data.content 是 Markdown 原文；返回 { ok, data?, reason? } */
export async function fetchUserArticleDetail(httpApi, name) {
	return submit(() => httpApi.getUserArticleDetail(name), '读取文章失败，请稍后重试');
}

/** 保存修改；返回 { ok, data?, reason? }（服务端不再扣积分） */
export async function saveUserArticle(httpApi, name, payload) {
	return submit(() => httpApi.updateUserArticle(name, payload), '保存失败，请稍后重试');
}

/** 立即发布 / 重新发布；返回 { ok, data?, reason? } */
export async function publishUserArticleNow(httpApi, name) {
	return submit(() => httpApi.publishUserArticleNow(name), '发布失败，请稍后重试');
}

/** 撤回发布（文章回到草稿态）；返回 { ok, data?, reason? } */
export async function withdrawUserArticle(httpApi, name) {
	return submit(() => httpApi.withdrawUserArticle(name), '撤回失败，请稍后重试');
}

/** 提交瞬间；返回 { ok, data?, reason? } */
export async function submitUserMoment(httpApi, payload) {
	return submit(() => httpApi.publishUserMoment(payload), '发布失败，请稍后重试');
}

/** 上传瞬间配图；返回 { ok, url?, reason? } */
export async function uploadUserPublishImage(httpApi, filePath) {
	try {
		const res = await httpApi.uploadUserPublishImage(filePath);
		const url = res?.data?.url || '';
		if (!url) {
			return { ok: false, reason: '图片上传失败' };
		}
		return { ok: true, url };
	} catch (err) {
		return { ok: false, reason: err?.error || err?.errMsg || '图片上传失败' };
	}
}

async function submit(request, fallbackReason) {
	try {
		const res = await request();
		const data = res?.data || res;
		if (data?.error) {
			return { ok: false, reason: data.error };
		}
		if (res?.statusCode && res.statusCode !== 200) {
			return { ok: false, reason: data?.error || fallbackReason };
		}
		return { ok: true, data };
	} catch (err) {
		return { ok: false, reason: err?.error || err?.message || fallbackReason };
	}
}

export default {
	readableDisabledReason,
	shouldShowPublishEntry,
	buildThresholdHint,
	fetchUserPublishStatus,
	submitUserArticle,
	fetchUserArticleDetail,
	saveUserArticle,
	publishUserArticleNow,
	withdrawUserArticle,
	submitUserMoment,
	uploadUserPublishImage
};
