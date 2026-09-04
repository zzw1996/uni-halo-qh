/**
 * 瞬间发布权限：结合 basicConfig.tokenConfig.personalToken 与 wxAppConfig
 * 仅 personalToken 对应博主、且与微信小程序配置一致的登录用户可发表瞬间
 */

import { getPersonalToken } from '@/utils/token.js';
import { checkHasWxLogin } from '@/utils/auth.js';

const OPENID_ANNOTATION = 'unihalo.openid';

function trimText(value) {
	return (value || '').trim();
}

function escapeHtml(text) {
	return String(text)
		.replace(/&/g, '&amp;')
		.replace(/</g, '&lt;')
		.replace(/>/g, '&gt;')
		.replace(/"/g, '&quot;')
		.replace(/'/g, '&#39;');
}

/** 将纯文本转为瞬间插件可渲染的 HTML */
export function plainTextToMomentHtml(text) {
	const trimmed = trimText(text);
	if (!trimmed) {
		return '';
	}
	return trimmed
		.split(/\r?\n/)
		.map((line) => `<p>${escapeHtml(line)}</p>`)
		.join('');
}

function getConfiguredPersonalToken(configs) {
	return trimText(configs?.basicConfig?.tokenConfig?.personalToken || getPersonalToken());
}

/** 从 wxAppConfig / appConfig 解析小程序 AppId（与插件后端 merge 逻辑一致） */
export function resolveConfiguredWxAppId(configs) {
	const wxAppConfig = configs?.wxAppConfig || {};
	const appInfo = configs?.appConfig?.appInfo || {};
	return trimText(wxAppConfig.appId || appInfo.appId);
}

/** 是否已配置 personalToken 且微信小程序登录已开启 */
export function isMomentPublishFeatureConfigured(configs) {
	const personalToken = getConfiguredPersonalToken(configs);
	if (!personalToken) {
		return false;
	}
	return configs?.wxAppConfig?.loginEnabled !== false;
}

/** 当前运行小程序 AppId 是否与后台配置一致 */
export function isSameWxAppConfigured(configs = {}) {
	const wxAppConfig = configs?.wxAppConfig || {};
	const appInfo = configs?.appConfig?.appInfo || {};
	const candidates = [wxAppConfig.appId, appInfo.appId]
		.map(trimText)
		.filter(Boolean);
	if (candidates.length === 0) {
		return true;
	}
	// #ifdef MP-WEIXIN
	try {
		const runtimeAppId = trimText(uni.getAccountInfoSync()?.miniProgram?.appId);
		return !!runtimeAppId && candidates.includes(runtimeAppId);
	} catch (e) {
		return false;
	}
	// #endif
	// #ifndef MP-WEIXIN
	return false;
	// #endif
}

function normalizeHaloUser(raw) {
	if (!raw) {
		return null;
	}
	if (raw.metadata || raw.spec) {
		return raw;
	}
	if (raw.user) {
		return raw.user;
	}
	return raw;
}

function extractTokenOwnerOpenid(tokenUser) {
	const user = normalizeHaloUser(tokenUser);
	return trimText(user?.metadata?.annotations?.[OPENID_ANNOTATION]);
}

function extractTokenOwnerName(tokenUser) {
	const user = normalizeHaloUser(tokenUser);
	return trimText(user?.metadata?.name || user?.name);
}

/** 当前微信用户是否为 personalToken 对应博主 */
export function isWxUserMatchingTokenOwner(wxUserInfo, tokenUser, wxAppConfig = {}) {
	if (!wxUserInfo?.openid || !tokenUser) {
		return false;
	}

	const configuredOwnerOpenid = trimText(wxAppConfig.ownerOpenid);
	if (configuredOwnerOpenid && wxUserInfo.openid === configuredOwnerOpenid) {
		return true;
	}

	const ownerOpenid = extractTokenOwnerOpenid(tokenUser);
	if (ownerOpenid && ownerOpenid === wxUserInfo.openid) {
		return true;
	}

	const ownerName = extractTokenOwnerName(tokenUser);
	const haloUserName = trimText(wxUserInfo.haloUserName);
	if (ownerName && haloUserName && ownerName === haloUserName) {
		return true;
	}

	return false;
}

async function fetchTokenOwnerUser(httpApi) {
	return httpApi.getPersonalTokenUser();
}

async function fetchWxBoundHaloUser(httpApi, haloUserName) {
	const name = trimText(haloUserName);
	if (!name) {
		return null;
	}
	try {
		return await httpApi.getHaloUserByName(name);
	} catch (err) {
		console.warn('[momentPublish] 获取 Halo 用户失败:', err);
		return null;
	}
}

async function resolveOwnerNameForUser(httpApi, wxUserInfo, tokenUser) {
	const haloUserName = trimText(wxUserInfo?.haloUserName);
	if (haloUserName) {
		return haloUserName;
	}
	if (tokenUser) {
		const ownerName = extractTokenOwnerName(tokenUser);
		if (ownerName) {
			return ownerName;
		}
	}
	try {
		const user = await fetchTokenOwnerUser(httpApi);
		return extractTokenOwnerName(user);
	} catch (err) {
		console.warn('[momentPublish] 解析博主用户名失败:', err);
		return '';
	}
}

async function buildAllowedPermission(httpApi, wxUserInfo, tokenUser) {
	return {
		allowed: true,
		reason: 'ok',
		ownerName: await resolveOwnerNameForUser(httpApi, wxUserInfo, tokenUser)
	};
}

/**
 * 解析当前用户是否可显示/使用发表瞬间
 * @returns {Promise<{ allowed: boolean, reason: string, ownerName: string }>}
 */
export async function resolveMomentPublishPermission(httpApi, configs, wxUserInfo) {
	if (!isMomentPublishFeatureConfigured(configs)) {
		return { allowed: false, reason: 'not_configured', ownerName: '' };
	}

	if (!isSameWxAppConfigured(configs)) {
		return { allowed: false, reason: 'app_mismatch', ownerName: '' };
	}

	if (!checkHasWxLogin() || !wxUserInfo?.openid) {
		return { allowed: false, reason: 'not_logged_in', ownerName: '' };
	}

	const wxAppConfig = configs?.wxAppConfig || {};
	const configuredOwnerOpenid = trimText(wxAppConfig.ownerOpenid);
	if (configuredOwnerOpenid && wxUserInfo.openid === configuredOwnerOpenid) {
		return buildAllowedPermission(httpApi, wxUserInfo, null);
	}

	let tokenUser = null;
	try {
		tokenUser = await fetchTokenOwnerUser(httpApi);
		if (isWxUserMatchingTokenOwner(wxUserInfo, tokenUser, wxAppConfig)) {
			return buildAllowedPermission(httpApi, wxUserInfo, tokenUser);
		}
	} catch (err) {
		console.warn('[momentPublish] 获取 personalToken 用户失败，尝试 Halo 绑定信息校验:', err);
	}

	const haloUserName = trimText(wxUserInfo.haloUserName);
	if (haloUserName) {
		const boundUser = await fetchWxBoundHaloUser(httpApi, haloUserName);
		if (boundUser) {
			const boundOpenid = extractTokenOwnerOpenid(boundUser);
			if (boundOpenid && boundOpenid === wxUserInfo.openid) {
				return buildAllowedPermission(httpApi, wxUserInfo, tokenUser);
			}
		}
		if (tokenUser) {
			const ownerName = extractTokenOwnerName(tokenUser);
			if (ownerName && ownerName === haloUserName) {
				return buildAllowedPermission(httpApi, wxUserInfo, tokenUser);
			}
		}
	}

	return { allowed: false, reason: 'not_owner', ownerName: '' };
}

export function canShowMomentPublish(permission) {
	return permission?.allowed === true;
}

/** 是否为当前博主自己发布的瞬间 */
export function isOwnMoment(moment, ownerName) {
	const owner = trimText(ownerName);
	if (!owner) {
		return false;
	}
	const momentOwner = trimText(moment?.spec?.owner || moment?.owner?.name);
	return !!momentOwner && momentOwner === owner;
}

/**
 * 构建瞬间插件创建接口所需的 Moment 资源体
 * POST /apis/moment.halo.run/v1alpha1/moments
 */
export function buildMomentCreatePayload({ rawText = '', imageUrls = [], ownerName = '' } = {}) {
	const owner = trimText(ownerName);
	if (!owner) {
		throw new Error('缺少瞬间所属用户');
	}
	const text = trimText(rawText);
	const html = plainTextToMomentHtml(text);
	const medium = (imageUrls || [])
		.map((url) => trimText(url))
		.filter(Boolean)
		.map((url) => ({
			type: 'PHOTO',
			url
		}));
	return {
		apiVersion: 'moment.halo.run/v1alpha1',
		kind: 'Moment',
		metadata: {
			generateName: 'moment-'
		},
		spec: {
			content: {
				raw: html || text,
				html,
				medium
			},
			owner,
			releaseTime: new Date().toISOString(),
			approved: true,
			visible: 'PUBLIC'
		}
	};
}

/**
 * 规范化瞬间列表项，兼容 content.medium / content.html 缺失的情况
 */
export function normalizeMomentListItem(item, {
	displayUser,
	checkThumbnailUrl,
	removeTagLinksCompletely,
	generateUUID
}) {
	if (!item?.spec) {
		return item;
	}
	const content = item.spec.content || {};
	const medium = Array.isArray(content.medium) ? content.medium : [];
	item.spec.content = {
		...content,
		medium
	};
	item.spec.user = displayUser;
	if (!item.stats) {
		item.stats = {
			upvote: 0,
			totalComment: 0,
			approvedComment: 0
		};
	}
	medium.forEach((media) => {
		if (media?.url) {
			media.url = checkThumbnailUrl(media.url, true);
		}
	});
	const htmlSource = content.html || content.raw || '';
	item.spec.newHtml = removeTagLinksCompletely(htmlSource);
	item.images = medium.filter((media) => media.type === 'PHOTO');
	item.videos = medium
		.filter((media) => media.type === 'VIDEO')
		.map((media) => ({
			...media,
			id: generateUUID()
		}));
	item.audios = medium.filter((media) => media.type === 'AUDIO');
	return item;
}
