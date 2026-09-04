/**
 * 评论作者信息工具：与微信用户 openid 绑定，无需手动填写邮箱/网站
 */
import { getWxLoginInfo, getOpenid, checkHasWxLogin } from '@/utils/auth.js';
import { getAppConfigs } from '@/config/index.js';

const MINIAPP_EMAIL_DOMAIN = 'mini.unihalo.local';
const OPENID_ANNOTATION_KEY = 'unihalo.openid';

/**
 * 根据 openid 生成 Halo 评论所需邮箱（用户无需填写）
 */
export function buildCommentEmail(openid) {
	if (!openid) return '';
	return `${openid}@${MINIAPP_EMAIL_DOMAIN}`;
}

/**
 * 获取当前用户的评论作者信息（昵称、头像、邮箱、openid）
 */
export function getCommentAuthorInfo() {
	const wxUser = getWxLoginInfo();
	const openid = getOpenid() || wxUser?.openid || '';
	const wxAppConfig = getAppConfigs()?.wxAppConfig || {};
	const imagesConfig = getAppConfigs()?.imagesConfig || {};

	const displayName = (wxUser?.nickname || wxAppConfig.defaultNickname || '微信用户').trim();
	const avatar = wxUser?.avatarUrl || wxAppConfig.defaultAvatar || imagesConfig.defaultAvatarUrl || '';

	return {
		openid,
		displayName,
		avatar,
		email: buildCommentEmail(openid),
		website: ''
	};
}

/**
 * 是否具备发表评论所需的身份标识
 */
export function canSubmitComment() {
	return !!getOpenid() && !!getCommentAuthorInfo().email;
}

export const COMMENT_SUBJECT_POST = {
	group: 'content.halo.run',
	kind: 'Post',
	version: 'v1alpha1'
};

export const COMMENT_SUBJECT_MOMENT = {
	group: 'moment.halo.run',
	kind: 'Moment',
	version: 'v1alpha1'
};

/**
 * 构建 Halo 官方评论 POST 请求体
 */
export function buildCommentPayload({
	content,
	postName,
	isReply = false,
	quoteReply = '',
	subjectRef = COMMENT_SUBJECT_POST
} = {}) {
	const author = getCommentAuthorInfo();
	const payload = {
		allowNotification: false,
		raw: content,
		content: content,
		owner: {
			avatar: author.avatar,
			displayName: author.displayName,
			email: author.email,
			website: author.website
		}
	};

	if (isReply) {
		payload.quoteReply = quoteReply;
	} else {
		payload.subjectRef = {
			...subjectRef,
			name: postName
		};
	}

	return payload;
}

/**
 * 构建瞬间评论 POST 请求体
 */
export function buildMomentCommentPayload(options = {}) {
	return buildCommentPayload({
		...options,
		subjectRef: COMMENT_SUBJECT_MOMENT
	});
}

/**
 * 后端「评论响应序列化失败」时，评论可能已写入 Halo，需刷新列表
 */
export function isCommentSerializationError(err) {
	const msg = err?.error || err?.message || err?.detail || '';
	return typeof msg === 'string' && msg.includes('序列化失败');
}

/**
 * 从评论 owner.email 解析 openid（小程序评论邮箱格式）
 */
export function parseOpenidFromCommentEmail(email) {
	if (!email || typeof email !== 'string') return '';
	if (!email.endsWith(`@${MINIAPP_EMAIL_DOMAIN}`)) return '';
	return email.slice(0, -(MINIAPP_EMAIL_DOMAIN.length + 1));
}

/**
 * 从评论对象解析展示用作者信息（兼容旧数据）
 */
export function resolveCommentOwner(comment) {
	if (!comment?.owner) {
		return { displayName: '匿名用户', avatar: '' };
	}
	const owner = comment.owner;
	const annoOpenid = comment.metadata?.annotations?.[OPENID_ANNOTATION_KEY];
	const emailOpenid = parseOpenidFromCommentEmail(owner.email);
	return {
		displayName: owner.displayName || '匿名用户',
		avatar: owner.avatar || '',
		openid: annoOpenid || emailOpenid || ''
	};
}

/**
 * 当前登录用户是否可删除该评论（仅本人）
 */
export function canDeleteComment(comment) {
	const currentOpenid = getOpenid();
	if (!comment?.metadata?.name) return false;
	if (!currentOpenid && !checkHasWxLogin()) return false;

	const owner = resolveCommentOwner(comment);
	if (currentOpenid && owner.openid && owner.openid === currentOpenid) return true;

	const author = getCommentAuthorInfo();
	const commentEmail = comment?.owner?.email || '';
	if (author.email && commentEmail && commentEmail === author.email) return true;

	// 部分环境评论列表不返回 email，用昵称兜底（需已登录）
	if (author.openid && author.displayName) {
		const ownerName = (comment?.owner?.displayName || owner.displayName || '').trim();
		if (ownerName && ownerName === author.displayName.trim()) return true;
	}

	return false;
}
