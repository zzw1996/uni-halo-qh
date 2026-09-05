/**
 * 用户行为记录（评论 / 浏览 / 点赞），与微信 openid 绑定
 */

import { checkHasWxLogin, getOpenid } from '@/utils/auth.js';
import { upvote } from '@/utils/upvote.js';
import { isPointsEnabled, tryEarnPointsByAction, POINTS_ACTIONS } from '@/utils/points.js';

export const USER_ACTIVITY_TYPES = {
	COMMENT: 'comment',
	BROWSE: 'browse',
	UPVOTE: 'upvote'
};

export const USER_ACTIVITY_TITLES = {
	comment: '我的评论',
	browse: '我的浏览',
	upvote: '我的点赞'
};

/**
 * 从 Halo Post 对象解析浏览量（与首页列表一致）
 */
export function resolvePostVisitCount(post) {
	if (!post) return 0;
	if (post.stats?.visit != null) {
		return Number(post.stats.visit) || 0;
	}
	const statsAnno = post.metadata?.annotations?.['content.halo.run/stats'];
	if (statsAnno) {
		try {
			const parsed = typeof statsAnno === 'string' ? JSON.parse(statsAnno) : statsAnno;
			if (parsed?.visit != null) {
				return Number(parsed.visit) || 0;
			}
		} catch (e) {
			// ignore
		}
	}
	return 0;
}

/**
 * 将后端行为记录转为 article-card 所需结构
 */
export function mapActivityItemToArticle(item) {
	if (!item) return null;
	const name = item.articleName || item.refName || item.name || '';
	return {
		metadata: { name },
		spec: {
			title: item.title || '未命名文章',
			cover: item.cover || item.coverUrl || '',
			publishTime: item.publishTime || item.recordTime || item.createTime
		},
		status: {
			excerpt: item.excerpt || item.description || ''
		},
		stats: {
			visit: Number(item.visit ?? item.visitCount ?? 0) || 0
		},
		// 行为记录附加信息：积分数量（无领取记录时为 null）、可读时间、排序用原始时间
		_pointsEarned: item.pointsEarned != null ? Number(item.pointsEarned) : null,
		_recordTimeText: item.recordTimeText || '',
		_recordTime: item.recordTime || item.createTime || ''
	};
}

/**
 * 批量拉取文章最新浏览量，与首页列表保持一致
 */
export async function enrichActivityArticlesStats(httpApi, articles) {
	if (!Array.isArray(articles) || !articles.length) {
		return articles || [];
	}
	await Promise.all(articles.map(async (article) => {
		const name = article?.metadata?.name;
		if (!name || !article.stats) return;
		try {
			const post = await httpApi.getPostByName(name);
			article.stats.visit = resolvePostVisitCount(post);
		} catch (err) {
			console.warn('[UniHalo] 同步浏览量失败:', name, err?.error || err);
		}
	}));
	return articles;
}

/**
 * 记录用户行为（未登录时静默跳过）
 */
export function recordUserActivity(httpApi, type, articlePayload = {}) {
	if (!checkHasWxLogin() || !getOpenid()) {
		return Promise.resolve();
	}
	if (!type || !articlePayload.articleName) {
		return Promise.resolve();
	}
	return httpApi.recordUserActivity({
		type,
		...articlePayload
	}).then(() => {
		if (!isPointsEnabled()) return;
		const actionMap = {
			[USER_ACTIVITY_TYPES.COMMENT]: POINTS_ACTIONS.COMMENT,
			[USER_ACTIVITY_TYPES.UPVOTE]: POINTS_ACTIONS.UPVOTE
		};
		const action = actionMap[type];
		if (action) {
			return tryEarnPointsByAction(httpApi, action, articlePayload);
		}
	}).catch((err) => {
		console.warn('[UniHalo] recordUserActivity failed:', err?.error || err);
	});
}

/**
 * 取消用户行为记录（如取消点赞）
 */
export function removeUserActivity(httpApi, type, articlePayload = {}) {
	if (!checkHasWxLogin() || !getOpenid()) {
		return Promise.reject({ error: '请先登录' });
	}
	if (!type || !articlePayload.articleName) {
		return Promise.reject({ error: '参数不完整' });
	}
	return httpApi.removeUserActivity({
		type,
		...articlePayload
	}).then((res) => {
		if (res?.success || res?.upvoted === false) {
			return res;
		}
		return Promise.reject({ error: '取消失败' });
	});
}

/**
 * 从后端同步当前用户对某篇文章的点赞状态
 */
export async function syncArticleUpvoteStatus(httpApi, articleName) {
	if (!checkHasWxLogin() || !getOpenid() || !articleName) {
		return false;
	}
	try {
		const res = await httpApi.getArticleUpvoteStatus(articleName);
		const upvoted = !!res?.upvoted;
		upvote.sync('post', articleName, upvoted);
		return upvoted;
	} catch (err) {
		console.warn('[UniHalo] syncArticleUpvoteStatus failed:', err?.error || err);
		return false;
	}
}

/**
 * 从后端同步当前用户对某条瞬间的点赞状态
 */
export async function syncMomentUpvoteStatus(httpApi, momentName) {
	if (!checkHasWxLogin() || !getOpenid() || !momentName) {
		return upvote.has('moment', momentName);
	}
	try {
		const res = await httpApi.getMomentUpvoteStatus(momentName);
		const upvoted = !!res?.upvoted;
		upvote.sync('moment', momentName, upvoted);
		return upvoted;
	} catch (err) {
		console.warn('[UniHalo] syncMomentUpvoteStatus failed:', err?.error || err);
		return upvote.has('moment', momentName);
	}
}

/**
 * 记录瞬间相关用户行为
 */
export function recordMomentUserActivity(httpApi, type, momentPayload = {}) {
	if (!checkHasWxLogin() || !getOpenid()) {
		return Promise.resolve();
	}
	const momentName = momentPayload.momentName || momentPayload.articleName;
	if (!type || !momentName) {
		return Promise.resolve();
	}
	return httpApi.recordUserActivity({
		type,
		articleName: momentName,
		contentType: 'moment',
		title: momentPayload.title || '',
		cover: momentPayload.cover || '',
		excerpt: momentPayload.excerpt || ''
	}).catch((err) => {
		console.warn('[UniHalo] recordMomentUserActivity failed:', err?.error || err);
	});
}

/**
 * 取消瞬间相关用户行为（如取消点赞）
 */
export function removeMomentUserActivity(httpApi, type, momentPayload = {}) {
	if (!checkHasWxLogin() || !getOpenid()) {
		return Promise.reject({ error: '请先登录' });
	}
	const momentName = momentPayload.momentName || momentPayload.articleName;
	if (!type || !momentName) {
		return Promise.reject({ error: '参数不完整' });
	}
	return httpApi.removeUserActivity({
		type,
		articleName: momentName,
		contentType: 'moment'
	}).then((res) => {
		if (res?.success || res?.upvoted === false) {
			return res;
		}
		return Promise.reject({ error: '取消失败' });
	});
}
