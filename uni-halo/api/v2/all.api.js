/**
 * 功能：全部 API 接口统一管理
 * 说明：包含 Halo v2 核心 API + UniHalo 插件 API
 *       所有接口集中在此文件管理，方便维护和调用
 * 文档版本：v1.0.37
 *
 * 接口分类（按文档规范）：
 *   - 登录接口：{halo站点地址}/apis/api.uni.uhalo.pro/v1alpha1/uni-halo/login（主）
 *             {halo站点地址}/apis/api.uni.uhalo.pro/v1alpha1/plugins/plugin-uni-halo/login（备用）
 *   - 其他接口：{halo站点地址}/apis/api.uni.uhalo.pro/v1alpha1/plugins/plugin-uni-halo
 */

import {
	getPersonalToken
} from '@/utils/token.js'
import HttpHandler from '@/common/http/request.js'
import qs from 'qs'

import { getNologinEmail, getOpenid } from "@/utils/auth";
import { getPointsApiPathCandidates, isPointsApiError } from '@/utils/points.js';
import UHaloBaseConfig from '@/config/uhalo.config.js'

/**
 * 构建 UniHalo 插件 API 完整 URL
 * 适用于：获取配置、二维码等其他接口
 * 基础路径：{halo站点地址}/apis/api.uni.uhalo.pro/v1alpha1/plugins/plugin-uni-halo
 */
const buildUniHaloUrl = (path) => {
	const baseUrl = UHaloBaseConfig.BASE_API.replace(/\/+$/, '');
	return `${baseUrl}/apis/api.uni.uhalo.pro/v1alpha1/plugins/plugin-uni-halo${path}`;
}

/**
 * 构建 UniHalo 登录 API 完整 URL
 * 适用于：微信小程序登录接口
 * 主 URL：{halo站点地址}/apis/api.uni.uhalo.pro/v1alpha1/uni-halo/login
 */
const buildUniHaloLoginUrl = () => {
	const baseUrl = UHaloBaseConfig.BASE_API.replace(/\/+$/, '');
	return `${baseUrl}/apis/api.uni.uhalo.pro/v1alpha1/uni-halo/login`;
}

/**
 * 构建 UniHalo 登录 API 备用 URL
 * 备用 URL：{halo站点地址}/apis/api.uni.uhalo.pro/v1alpha1/plugins/plugin-uni-halo/login
 * 功能与主 URL 完全相同
 */
const buildUniHaloLoginUrlFallback = () => {
	const baseUrl = UHaloBaseConfig.BASE_API.replace(/\/+$/, '');
	return `${baseUrl}/apis/api.uni.uhalo.pro/v1alpha1/plugins/plugin-uni-halo/login`;
}

const JSON_CONTENT_TYPE = 'application/json; charset=utf-8';

/**
 * 从 Halo / 插件响应中提取可读错误信息
 */
const extractResponseError = (data) => {
	if (!data) return '';
	if (typeof data === 'string') return data;
	if (data.error) return data.error;
	if (data.detail) return data.detail;
	if (data.message) return data.message;
	if (data.title && data.title !== 'Bad Request') return data.title;
	return '';
};

/**
 * 解析 directRequest 原始响应体（兼容 DELETE 204、空 body、纯文本）
 */
const normalizeDirectResponseData = (rawData, statusCode) => {
	if (rawData === undefined || rawData === null) {
		return null;
	}
	if (typeof rawData !== 'string') {
		return rawData;
	}
	const str = rawData.trim();
	if (!str) {
		return null;
	}
	if (
		str.startsWith('<!DOCTYPE') ||
		str.startsWith('<html') ||
		str.includes('<title>') ||
		(str.startsWith('<') && str.includes('<meta') && str.includes('redirect'))
	) {
		return { __html: true, raw: str };
	}
	try {
		return JSON.parse(str);
	} catch (e) {
		if (statusCode >= 200 && statusCode < 300) {
			return { raw: str };
		}
		return { __parseError: true, raw: str };
	}
};

const isDirectRequestSuccess = (statusCode, { allow404 = false } = {}) => {
	if (statusCode >= 200 && statusCode < 300) return true;
	if (allow404 && statusCode === 404) return true;
	return false;
};

/**
 * POST/PUT 时显式 JSON 序列化请求体
 * 微信小程序 + Spring WebFlux 对 body 编码较敏感，需 charset=utf-8
 */
const buildRequestBody = (data, method) => {
	if (data === undefined || data === null) return undefined;
	const m = (method || 'GET').toUpperCase();
	if (m === 'GET' || m === 'HEAD') return data;
	if (typeof data === 'string') return data;
	return JSON.stringify(data);
};

const buildContentApiUrl = (path) => {
	const baseUrl = UHaloBaseConfig.BASE_API.replace(/\/+$/, '');
	return `${baseUrl}${path.startsWith('/') ? path : `/${path}`}`;
};

/**
 * 文章详情直连（绕过 luch-request，避免空响应被误报为 500）
 */
const requestPostDetail = (name, withWxHeaders = false) => {
	const encodedName = encodeURIComponent((name || '').trim());
	const url = buildContentApiUrl(`/apis/api.content.halo.run/v1alpha1/posts/${encodedName}`);
	const header = {};
	if (withWxHeaders) {
		const openid = getOpenid();
		if (openid) {
			header['Wechat-Session-Id'] = openid;
			const email = getNologinEmail();
			if (email) {
				header['nologin-email'] = email;
			}
		}
	}
	return new Promise((resolve, reject) => {
		uni.request({
			url,
			method: 'GET',
			header: {
				'Content-Type': JSON_CONTENT_TYPE,
				...header
			},
			success: (res) => {
				let data = res.data;
				if (typeof data === 'string') {
					const str = data.trim();
					if (str.startsWith('<!DOCTYPE') || str.startsWith('<html') || str.includes('<title>')) {
						reject({
							error: '文章接口被拦截，请检查站点配置',
							message: '文章接口被拦截，请检查站点配置',
							statusCode: res.statusCode,
							isHtml: true
						});
						return;
					}
					try {
						data = JSON.parse(data);
					} catch (e) {
						reject({
							error: '文章响应解析失败',
							message: '文章响应解析失败',
							statusCode: res.statusCode
						});
						return;
					}
				}
				if (res.statusCode === 200 && data?.metadata?.name) {
					resolve(data);
					return;
				}
				const errText = extractResponseError(data) || `文章加载失败 (HTTP ${res.statusCode || '未知'})`;
				reject({
					error: errText,
					message: errText,
					statusCode: res.statusCode,
					data
				});
			},
			fail: (err) => {
				const errText = err?.errMsg || '网络请求失败';
				reject({
					error: errText,
					message: errText,
					detail: err
				});
			}
		});
	});
};

/**
 * 使用 uni.request 直接调用（绕过 luch-request 拦截器）
 *
 * 原因：luch-request 拦截器链存在以下问题：
 * 1. 全局 header 使用 ContentType（非标准名）而非 Content-Type
 * 2. 响应拦截器对非200状态码触发401跳转逻辑，干扰登录流程
 *
 * 仅用于需要完整控制请求/响应的场景（如登录、获取小程序配置）
 * 其他接口仍使用 HttpHandler.Get/Post
 */
const directRequest = ({ url, method = 'GET', data, header }) => {
	const reqMethod = (method || 'GET').toUpperCase();
	const reqHeader = {
		'Content-Type': JSON_CONTENT_TYPE,
		...(header || {})
	};
	const reqData = buildRequestBody(data, reqMethod);

	return new Promise((resolve, reject) => {
		uni.request({
			url,
			method: reqMethod,
			data: reqData,
			header: reqHeader,
			timeout: 30000,
			success: (res) => {
				// 重定向：主登录路由未放行时 Halo 会 302 到后台登录页
				if (res.statusCode === 301 || res.statusCode === 302) {
					reject({
						error: '登录接口被重定向，请确认插件已部署并配置匿名访问',
						statusCode: res.statusCode,
						isRedirect: true
					});
					return;
				}

				const parsedData = normalizeDirectResponseData(res.data, res.statusCode);
				if (parsedData?.__html) {
					reject({
						error: '接口被拦截或插件未启用，请确认插件已升级并已启用',
						isHtml: true,
						statusCode: res.statusCode
					});
					return;
				}
				if (parsedData?.__parseError) {
					reject({ error: '响应数据解析失败', statusCode: res.statusCode, data: parsedData.raw });
					return;
				}
				res.data = parsedData;

				// 成功 2xx；400 为业务错误仍返回给调用方判断
				if ((res.statusCode >= 200 && res.statusCode < 300) || res.statusCode === 400) {
					resolve({ statusCode: res.statusCode, data: res.data });
				} else if (res.statusCode === 404) {
					reject({ error: '接口不存在(404)，请确认插件已升级', statusCode: 404, data: res.data });
				} else {
					const apiError = extractResponseError(res.data);
					reject({
						error: apiError || `服务端返回异常状态码 (HTTP ${res.statusCode})`,
						statusCode: res.statusCode,
						data: res.data
					});
				}
			},
			fail: (err) => {
				const errMsg = err.errMsg || '';
				let error = '网络请求失败';
				if (errMsg.includes('not in domain list') || errMsg.includes('url not in domain list')) {
					error = '域名未加入微信小程序白名单，请在微信公众平台配置服务器域名';
				} else if (errMsg.includes('timeout')) {
					error = '请求超时，请检查网络连接';
				} else if (errMsg.includes('fail') && errMsg.includes('ssl')) {
					error = 'SSL证书验证失败，请检查站点HTTPS配置';
				}
				reject({ error, detail: err });
			}
		});
	});
}

/**
 * 构建评论相关请求的微信身份 Header
 */
/** 只有登录时才带 Wechat-Session-Id：订阅读取、分类订阅等接口允许匿名访问，未登录就不带头 */
const buildOpenidHeader = () => {
	const openid = getOpenid();
	return openid ? { 'Wechat-Session-Id': openid } : {};
};

const buildCommentAuthHeader = () => {
	const header = {};
	const openid = getOpenid();
	if (openid) {
		header['Wechat-Session-Id'] = openid;
	}
	const nologinEmail = getNologinEmail();
	if (nologinEmail) {
		header['nologin-email'] = nologinEmail;
	}
	return header;
};

/**
 * 评论 API 直连（绕过 luch-request 拦截器，兼容 201/204 响应）
 */
const requestCommentApi = async (path, { method = 'POST', data } = {}) => {
	const url = buildContentApiUrl(path);
	const res = await directRequest({
		url,
		method,
		data,
		header: buildCommentAuthHeader()
	});
	if (res?.statusCode === 200 || res?.statusCode === 201 || res?.statusCode === 204) {
		return res.data;
	}
	const action = method === 'DELETE' ? '删除' : '提交';
	const errText = extractResponseError(res?.data) || `评论${action}失败 (HTTP ${res?.statusCode || '未知'})`;
	return Promise.reject({
		error: errText,
		message: errText,
		statusCode: res?.statusCode,
		data: res?.data
	});
};

/**
 * 积分 API 请求（支持多路径 fallback）
 */
const requestPointsApi = async ({ apiKey, method = 'GET', data, params }) => {
	const openid = getOpenid();
	if (!openid) {
		return Promise.reject({ error: '请先登录' });
	}
	const candidates = getPointsApiPathCandidates(apiKey);
	let lastError = null;
	for (const path of candidates) {
		let url = buildUniHaloUrl(path);
		if (params && method === 'GET') {
			const query = qs.stringify(params);
			if (query) {
				url = `${url}?${query}`;
			}
		}
		try {
			return await directRequest({
				url,
				method,
				data,
				header: {
					'Wechat-Session-Id': openid
				}
			});
		} catch (err) {
			lastError = err;
			if (!isPointsApiError(err)) {
				throw err;
			}
		}
	}
	return Promise.reject(lastError || {
		error: '积分接口未部署或未开放访问，请确认插件已升级并重启 Halo',
		isHtml: true
	});
};

/**
 * 阅读券（付费内容）API 请求，支持多路径 fallback。
 *
 * <p>不复用 requestPointsApi 的原因：那个函数在缺少 openid 时直接 reject「请先登录」，
 * 而付费文章的状态查询<b>必须允许未登录调用</b>——否则未登录用户打开付费文章时
 * 页面只能显示登录错误态，拿不到「本文需阅读券」的引导信息。</p>
 *
 * @param {object}  options        { apiKey, method, data, params }
 * @param {boolean} allowAnonymous true 时无 openid 也发请求（状态/余额查询用）
 */
const requestContentApi = async ({ apiKey, method = 'GET', data, params }, allowAnonymous = false) => {
	const openid = getOpenid();
	if (!openid && !allowAnonymous) {
		return Promise.reject({ error: '请先登录' });
	}
	const candidates = getPointsApiPathCandidates(apiKey);
	let lastError = null;
	for (const path of candidates) {
		let url = buildUniHaloUrl(path);
		if (params && method === 'GET') {
			const query = qs.stringify(params);
			if (query) {
				url = `${url}?${query}`;
			}
		}
		try {
			const res = await directRequest({
				url,
				method,
				data,
				header: openid ? { 'Wechat-Session-Id': openid } : {}
			});
			// directRequest 对 400 也按 resolve 返回（项目约定：业务错误交给调用方判断）。
			// 但解锁/兑换失败（券不足、功能未开启、文章不存在）若原样 resolve，
			// 页面 .then() 会误报「解锁成功」。这里统一转成 reject，让页面 .catch() 正确提示。
			if (isDirectRequestSuccess(res?.statusCode)) {
				return res;
			}
			if (res?.statusCode === 400) {
				throw {
					error: extractResponseError(res.data) || '操作失败',
					message: extractResponseError(res.data) || '操作失败',
					statusCode: 400,
					data: res.data
				};
			}
			return res;
		} catch (err) {
			lastError = err;
			// 404/被拦截说明该路径不可用，继续尝试下一个候选；
			// 其余错误（如 400 业务错误）直接抛出，避免无意义的重复请求
			if (!isPointsApiError(err)) {
				throw err;
			}
		}
	}
	return Promise.reject(lastError || {
		error: '阅读券接口未部署或未开放访问，请确认插件已升级并重启 Halo',
		isHtml: true
	});
};

export default {
	// ==================== Halo v2 核心 API ====================

	getPostList: (params) => {
		return HttpHandler.Get(`/apis/api.content.halo.run/v1alpha1/posts`, params)
	},

	getPostByName: async (name) => {
		const postName = (name || '').trim();
		if (!postName) {
			return Promise.reject({ error: '缺少文章参数', message: '缺少文章参数' });
		}
		const hasOpenid = !!getOpenid();
		try {
			return await requestPostDetail(postName, hasOpenid);
		} catch (err) {
			// 付费文章锁定（插件 ContentTicketFilter 返回 403 + ContentLockedDTO）
			// 必须原样抛给页面渲染解锁卡片，绝不能走下面的降级重试：
			// 降级会去掉 Wechat-Session-Id，插件因此无法识别用户身份与解锁状态，
			// 已解锁用户会被当成未登录再次收到 403，表现为文章永远打不开。
			if (err?.statusCode === 403 && err?.data && err.data.locked === true) {
				throw Object.assign({}, err, { isContentLocked: true });
			}
			if (!hasOpenid) {
				throw err;
			}
			// 带微信头失败时降级重试（避免部分环境下请求异常）
			return requestPostDetail(postName, false);
		}
	},

	getPostListByKeyword: (params) => {
		return HttpHandler.Post(`/apis/api.halo.run/v1alpha1/indices/-/search`, params)
	},

	getCategoryList: (params) => {
		const param = qs.stringify(params, {
			allowDots: true,
			encodeValuesOnly: true,
			skipNulls: true,
			encode: false,
			arrayFormat: 'repeat'
		})
		return HttpHandler.Get(`/apis/api.content.halo.run/v1alpha1/categories?${param}`, {})
	},

	getCategoryPostList: (name, params) => {
		return HttpHandler.Get(`/apis/api.content.halo.run/v1alpha1/categories/${name}/posts`, params)
	},

	getPostCommentList: (params) => {
		return HttpHandler.Get(`/apis/api.halo.run/v1alpha1/comments`, params)
	},

	getPostCommentReplyList: (commentName, params) => {
		return HttpHandler.Get(`/apis/api.halo.run/v1alpha1/comments/${commentName}/reply`, params)
	},

	addPostComment: (data) => {
		return requestCommentApi('/apis/api.halo.run/v1alpha1/comments', { method: 'POST', data })
	},

	addPostCommentReply: (commentName, data) => {
		return requestCommentApi(`/apis/api.halo.run/v1alpha1/comments/${encodeURIComponent(commentName)}/reply`, {
			method: 'POST',
			data
		})
	},

	deletePostComment: (commentName) => {
		const name = (commentName || '').trim();
		if (!name) {
			return Promise.reject({ error: '缺少评论参数', message: '缺少评论参数' });
		}
		return requestCommentApi(`/apis/api.halo.run/v1alpha1/comments/${encodeURIComponent(name)}`, {
			method: 'DELETE'
		})
	},

	getTagList: (params) => {
		return HttpHandler.Get(`/apis/api.content.halo.run/v1alpha1/tags`, params)
	},

	getPostByTagName: (tagName, params) => {
		return HttpHandler.Get(`/apis/api.content.halo.run/v1alpha1/tags/${tagName}/posts`, params)
	},

	getMomentList: (params) => {
		return HttpHandler.Get(`/apis/api.moment.halo.run/v1alpha1/moments`, params, {
			custom: {
				personalToken: getPersonalToken()
			}
		})
	},

	getMomentByName: (name) => {
		return HttpHandler.Get(`/apis/api.moment.halo.run/v1alpha1/moments/${name}`, {}, {
			custom: {
				personalToken: getPersonalToken()
			}
		})
	},

	/** 获取 personalToken 对应的 Halo 用户（用于瞬间发布权限校验） */
	getPersonalTokenUser: async () => {
		const token = getPersonalToken();
		if (!token) {
			return Promise.reject({ error: '未配置个人令牌' });
		}
		const paths = [
			'/apis/api.console.halo.run/v1alpha1/users/-',
			'/apis/api.halo.run/v1alpha1/users/-'
		];
		let lastErr = null;
		for (const path of paths) {
			try {
				return await HttpHandler.Get(path, {}, {
					custom: {
						personalToken: token
					}
				});
			} catch (err) {
				lastErr = err;
			}
		}
		return Promise.reject(lastErr || { error: '获取博主信息失败' });
	},

	/** 按用户名获取 Halo 用户（personalToken 需具备用户读取权限） */
	getHaloUserByName: (userName) => {
		const token = getPersonalToken();
		const name = (userName || '').trim();
		if (!token) {
			return Promise.reject({ error: '未配置个人令牌' });
		}
		if (!name) {
			return Promise.reject({ error: '缺少用户名' });
		}
		return HttpHandler.Get(`/apis/api.halo.run/v1alpha1/users/${encodeURIComponent(name)}`, {}, {
			custom: {
				personalToken: token
			}
		});
	},

	/**
	 * 发表瞬间（瞬间插件扩展 API）
	 * POST /apis/moment.halo.run/v1alpha1/moments
	 * 请求体需包含 apiVersion / kind / metadata / spec（spec.owner 必填）
	 */
	createMoment: (moment) => {
		const token = getPersonalToken();
		if (!token) {
			return Promise.reject({ error: '未配置个人令牌' });
		}
		if (!moment?.spec?.owner) {
			return Promise.reject({ error: '缺少瞬间所属用户' });
		}
		return HttpHandler.Post('/apis/moment.halo.run/v1alpha1/moments', moment, {
			custom: {
				personalToken: token
			}
		});
	},

	/**
	 * 删除瞬间（瞬间插件扩展 API）
	 * DELETE /apis/moment.halo.run/v1alpha1/moments/{name}
	 */
	deleteMoment: (name) => {
		const token = getPersonalToken();
		const momentName = (name || '').trim();
		if (!token) {
			return Promise.reject({ error: '未配置个人令牌' });
		}
		if (!momentName) {
			return Promise.reject({ error: '缺少瞬间标识' });
		}
		return HttpHandler.Delete(
			`/apis/moment.halo.run/v1alpha1/moments/${encodeURIComponent(momentName)}`,
			{},
			{
				custom: {
					personalToken: token
				}
			}
		);
	},

	/**
	 * 上传附件（瞬间图片等）
	 * POST /apis/api.console.halo.run/v1alpha1/attachments/upload
	 * formData: file, policyName, groupName(可选)
	 */
	uploadAttachment: (filePath, { policyName = 'default-policy', groupName = '' } = {}) => {
		const token = getPersonalToken();
		if (!token) {
			return Promise.reject({ error: '未配置个人令牌' });
		}
		if (!filePath) {
			return Promise.reject({ error: '缺少上传文件' });
		}
		const baseUrl = UHaloBaseConfig.BASE_API.replace(/\/+$/, '');
		const formData = {
			policyName: policyName || 'default-policy'
		};
		if (groupName) {
			formData.groupName = groupName;
		}
		return new Promise((resolve, reject) => {
			uni.uploadFile({
				url: `${baseUrl}/apis/api.console.halo.run/v1alpha1/attachments/upload`,
				filePath,
				name: 'file',
				formData,
				header: {
					Authorization: `Bearer ${token}`
				},
				success: (res) => {
					let data = res.data;
					if (typeof data === 'string') {
						try {
							data = JSON.parse(data);
						} catch (e) {
							reject({ error: '附件上传响应解析失败', statusCode: res.statusCode });
							return;
						}
					}
					if (res.statusCode === 200 && data) {
						resolve(data);
						return;
					}
					const apiError = extractResponseError(data);
					reject({ error: apiError || '图片上传失败', statusCode: res.statusCode, data });
				},
				fail: (err) => {
					reject({ error: err.errMsg || '图片上传失败', detail: err });
				}
			});
		});
	},

	getMomentCommentList: (momentName, params = {}) => {
		const name = (momentName || '').trim();
		if (!name) {
			return Promise.reject({ error: '缺少瞬间参数', message: '缺少瞬间参数' });
		}
		return HttpHandler.Get(`/apis/api.halo.run/v1alpha1/comments`, {
			group: 'moment.halo.run',
			kind: 'Moment',
			version: 'v1alpha1',
			name,
			page: params.page || 1,
			size: params.size || 50,
			withReplies: params.withReplies !== false,
			replySize: params.replySize || 10
		});
	},

	submitMomentUpvote: (momentName) => {
		const name = (momentName || '').trim();
		if (!name) {
			return Promise.reject({ error: '缺少瞬间参数', message: '缺少瞬间参数' });
		}
		return HttpHandler.Post(`/apis/api.halo.run/v1alpha1/trackers/upvote`, {
			group: 'moment.halo.run',
			plural: 'moments',
			name
		});
	},

	submitMomentDownvote: (momentName) => {
		const name = (momentName || '').trim();
		if (!name) {
			return Promise.reject({ error: '缺少瞬间参数', message: '缺少瞬间参数' });
		}
		return HttpHandler.Post(`/apis/api.halo.run/v1alpha1/trackers/downvote`, {
			group: 'moment.halo.run',
			plural: 'moments',
			name
		});
	},

	/**
	 * 查询当前用户是否已点赞某条瞬间
	 * GET /userActivities/upvote/status?articleName=xxx&contentType=moment
	 */
	getMomentUpvoteStatus: async (momentName) => {
		const openid = getOpenid();
		if (!openid) {
			return Promise.reject({ error: '请先登录' });
		}
		const name = (momentName || '').trim();
		if (!name) {
			return Promise.reject({ error: '缺少瞬间参数' });
		}
		const query = qs.stringify({ articleName: name, contentType: 'moment' });
		try {
			const res = await directRequest({
				url: `${buildUniHaloUrl('/userActivities/upvote/status')}?${query}`,
				method: 'GET',
				header: {
					'Wechat-Session-Id': openid
				}
			});
			if (isDirectRequestSuccess(res.statusCode)) {
				const data = res.data || {};
				return {
					upvoted: !!(data.upvoted ?? data.liked ?? data.hasUpvoted)
				};
			}
			if (res.statusCode === 400) {
				return Promise.reject({
					error: extractResponseError(res.data) || '查询点赞状态失败',
					statusCode: res.statusCode,
					data: res.data
				});
			}
			return { upvoted: false };
		} catch (err) {
			if (err?.statusCode === 404) {
				return { upvoted: false };
			}
			throw err;
		}
	},

	getBlogStatistics: () => {
		return HttpHandler.Get(`/apis/api.halo.run/v1alpha1/stats/-`, {})
	},

	getPhotoGroupList: (params) => {
		return HttpHandler.Get(`/apis/api.photo.halo.run/v1alpha1/photogroups`, params)
	},

	getPhotoListByGroupName: (params) => {
		return HttpHandler.Get(`/apis/api.photo.halo.run/v1alpha1/photos`, params)
	},

	getFriendLinkGroupList: (params) => {
		return HttpHandler.Get(`/apis/api.link.halo.run/v1alpha1/linkgroups`, params)
	},

	getFriendLinkList: (params) => {
		return HttpHandler.Get(`/apis/api.link.halo.run/v1alpha1/links`, params)
	},

	submitUpvote(data) {
		return HttpHandler.Post(`/apis/api.halo.run/v1alpha1/trackers/upvote`, data, {})
	},

	getVoteList: (params) => {
		return HttpHandler.Get(`/apis/api.vote.kunkunyu.com/v1alpha1/votes`, params)
	},

	getVoteDetail: (name) => {
		return HttpHandler.Get(`/apis/api.vote.kunkunyu.com/v1alpha1/votes/${name}/detail`, {})
	},

	getVoteUserList: (name) => {
		return HttpHandler.Get(`/apis/api.vote.kunkunyu.com/v1alpha1/votes/${name}/user-list`, {})
	},

	submitVote: (name, data, canAnonymously = true) => {
		return HttpHandler.Post(`/apis/api.vote.kunkunyu.com/v1alpha1/votes/${name}/submit`, data, {
			custom: {
				personalToken: canAnonymously ? undefined : getPersonalToken()
			}
		})
	},

	checkPluginAvailable: (name) => {
		return HttpHandler.Get(`/apis/api.plugin.halo.run/v1alpha1/plugins/${name}/available`, {})
	},

	getDoubanDetail: (url) => {
		return HttpHandler.Get(`/apis/api.douban.moony.la/v1alpha1/doubanmovies/-/getDoubanDetail`, { url })
	},

	postTrackersCounter: async (data) => {
		const baseUrl = UHaloBaseConfig.BASE_API.replace(/\/+$/, '');
		const url = `${baseUrl}/apis/api.halo.run/v1alpha1/trackers/counter`;
		const res = await directRequest({
			url,
			method: 'POST',
			data
		});
		if (res?.statusCode === 200) {
			return res.data;
		}
		const errText = extractResponseError(res?.data) || `浏览量上报失败 (HTTP ${res?.statusCode || '未知'})`;
		return Promise.reject({ error: errText, statusCode: res?.statusCode, data: res?.data });
	},

	// ==================== UniHalo 插件 API ====================
	// 文档版本：v1.0.37
	//
	// URL 结构：
	//   - 登录接口（主）：/apis/api.uni.uhalo.pro/v1alpha1/uni-halo/login
	//   - 登录接口（备用）：/apis/api.uni.uhalo.pro/v1alpha1/plugins/plugin-uni-halo/login
	//   - 其他接口：/apis/api.uni.uhalo.pro/v1alpha1/plugins/plugin-uni-halo
	//
	// wxLogin 成功响应（UserDTO）：
	//   { id, openid, unionid, nickname, avatarUrl, haloUserName?, haloBindStatus: unbound|bound|created }

	/**
	 * 获取全部配置
	 * 接口：GET /getConfigs
	 * 鉴权：无需鉴权（公开接口）
	 * 返回：所有配置分组（basicConfig, appConfig, shareConfig, wxAppConfig 等）
	 */
	getAppConfigs: () => {
		return HttpHandler.Get(`/apis/api.uni.uhalo.pro/v1alpha1/plugins/plugin-uni-halo/getConfigs`, {})
	},

	/**
	 * 按分组获取配置
	 * 接口：GET /getConfigs/{groupName}
	 * 鉴权：无需鉴权（公开接口）
	 * 参数：groupName - 配置分组名称（如 wxAppConfig, shareConfig 等）
	 * 返回：指定分组配置
	 */
	getConfigsByGroup: (groupName) => {
		return HttpHandler.Get(`/apis/api.uni.uhalo.pro/v1alpha1/plugins/plugin-uni-halo/getConfigs/${groupName}`, {})
	},

	/**
	 * 获取微信小程序配置（公开接口，不含 AppSecret）
	 *
	 * 接口：GET /getWxAppConfig
	 * 鉴权：无需鉴权
	 * URL：{halo站点}/apis/api.uni.uhalo.pro/v1alpha1/plugins/plugin-uni-halo/getWxAppConfig
	 * 返回：{ appId, loginEnabled, haloBindEnabled, defaultAvatar, defaultNickname }
	 *
	 * 使用 uni.request 直接调用，避免拦截器干扰
	 * 失败时降级尝试 getConfigsByGroup('wxAppConfig')
	 * 返回 { statusCode, data } 格式
	 */
	getWxAppConfig: async () => {
		try {
			const res = await directRequest({
				url: buildUniHaloUrl('/getWxAppConfig'),
				method: 'GET'
			});
			return res;
		} catch (err) {
			// 降级：尝试通过 getConfigsByGroup 获取
			console.warn('[UniHalo] getWxAppConfig 直连失败，降级尝试 getConfigsByGroup:', err && err.error);
			try {
				const res = await HttpHandler.Get(
					`/apis/api.uni.uhalo.pro/v1alpha1/plugins/plugin-uni-halo/getConfigs/wxAppConfig`,
					{}
				);
				return { statusCode: 200, data: res };
			} catch (fallbackErr) {
				throw err; // 抛出原始错误
			}
		}
	},

	/**
	 * 微信小程序登录
	 *
	 * 请求体：{ code: string, nickname?: string, avatarUrl?: string }
	 * 响应 UserDTO：含 haloBindStatus、haloUserName（v1.0.37 不再自动创建 Halo 用户）
	 */
	wxLogin: async (payload) => {
		const body = typeof payload === 'string' ? { code: payload } : (payload || {});
		const loginUrls = [
			buildUniHaloLoginUrlFallback(),
			buildUniHaloLoginUrl()
		];
		let lastErr = null;

		for (let i = 0; i < loginUrls.length; i++) {
			try {
				const res = await directRequest({
					url: loginUrls[i],
					method: 'POST',
					data: body
				});
				if (res?.data?.error || res?.data?.detail) {
					return res;
				}
				if (res?.statusCode === 200 && res?.data?.openid) {
					return res;
				}
				return res;
			} catch (err) {
				lastErr = err;
				const canRetry = err && (
					err.isHtml ||
					err.isRedirect ||
					err.statusCode === 404 ||
					err.statusCode === 302 ||
					err.statusCode === 301
				);
				if (canRetry && i < loginUrls.length - 1) {
					console.warn('[UniHalo] 登录 URL 失败，尝试备用:', err.error);
					continue;
				}
				throw err;
			}
		}
		throw lastErr || { error: '登录请求失败' };
	},

	/**
	 * 上传微信 chooseAvatar 临时头像
	 * 接口：POST /uploadWxAvatar（multipart，字段名 file）
	 * 鉴权：Header Wechat-Session-Id: {openid}
	 * 返回：{ avatarUrl: string }
	 */
	uploadWxAvatar: (filePath) => {
		const openid = getOpenid();
		if (!openid) {
			return Promise.reject({ error: '请先登录后再上传头像' });
		}
		return new Promise((resolve, reject) => {
			uni.uploadFile({
				url: buildUniHaloUrl('/uploadWxAvatar'),
				filePath,
				name: 'file',
				header: {
					'Wechat-Session-Id': openid
				},
				success: (res) => {
					let data = res.data;
					if (typeof data === 'string') {
						try {
							data = JSON.parse(data);
						} catch (e) {
							reject({ error: '头像上传响应解析失败', statusCode: res.statusCode });
							return;
						}
					}
					if (res.statusCode === 200 && data?.avatarUrl) {
						resolve({ statusCode: 200, data });
						return;
					}
					const apiError = extractResponseError(data);
					reject({ error: apiError || '头像上传失败', statusCode: res.statusCode, data });
				},
				fail: (err) => {
					reject({ error: err.errMsg || '头像上传失败', detail: err });
				}
			});
		});
	},

	/**
	 * 更新微信用户资料
	 *
	 * 接口：PUT /updateProfile
	 * 鉴权：Header Wechat-Session-Id: {openid}
	 * 请求体：{ nickname?: string, avatarUrl?: string }
	 *
	 * 成功响应（200）：
	 *   { id, openid, sessionKey, unionid, nickname, avatarUrl }
	 *
	 * 错误响应（400）：
	 *   { error: "错误描述" }
	 *
	 * 使用 uni.request 直接调用，与登录接口保持一致
	 * 返回 { statusCode, data } 格式
	 */
	updateProfile: async ({ nickname, avatarUrl }) => {
		const openid = getOpenid();
		if (!openid) {
			return Promise.reject({ error: '请先登录后再更新资料' });
		}
		const body = {};
		if (nickname) body.nickname = nickname;
		if (avatarUrl) body.avatarUrl = avatarUrl;
		return directRequest({
			url: buildUniHaloUrl('/updateProfile'),
			method: 'PUT',
			data: body,
			header: {
				'Content-Type': 'application/json',
				'Wechat-Session-Id': openid
			}
		});
	},

	/**
	 * 记录用户行为（评论 / 浏览 / 点赞）
	 * POST /userActivities
	 * 请求体：{ type: 'comment'|'browse'|'upvote', articleName, title?, cover?, excerpt? }
	 */
	recordUserActivity: async (data) => {
		const openid = getOpenid();
		if (!openid) {
			return Promise.reject({ error: '请先登录' });
		}
		const payload = data || {};
		// 仅发送后端 RecordUserActivityRequest 定义的字段，避免 visit 等多余字段导致 Jackson 400
		const body = {
			type: payload.type,
			articleName: payload.articleName
		};
		if (payload.contentType) body.contentType = String(payload.contentType);
		if (payload.title) body.title = String(payload.title);
		if (payload.cover) body.cover = String(payload.cover);
		if (payload.excerpt) body.excerpt = String(payload.excerpt);
		return directRequest({
			url: buildUniHaloUrl('/userActivities'),
			method: 'POST',
			data: body,
			header: {
				'Wechat-Session-Id': openid
			}
		});
	},

	/**
	 * 取消用户行为记录（如取消点赞）
	 * DELETE /userActivities
	 * 请求体：{ type: 'upvote', articleName }
	 * 成功：200 + { success: true, upvoted: false }；记录不存在时也返回 200（幂等）
	 */
	removeUserActivity: async (data) => {
		const openid = getOpenid();
		if (!openid) {
			return Promise.reject({ error: '请先登录' });
		}
		const payload = data || {};
		const body = {
			type: payload.type,
			articleName: payload.articleName
		};
		if (payload.contentType) body.contentType = String(payload.contentType);
		try {
			const res = await directRequest({
				url: buildUniHaloUrl('/userActivities'),
				method: 'DELETE',
				data: body,
				header: {
					'Content-Type': 'application/json',
					'Wechat-Session-Id': openid
				}
			});
			if (isDirectRequestSuccess(res.statusCode)) {
				return {
					success: true,
					upvoted: false,
					...(res.data || {})
				};
			}
			if (res.statusCode === 400) {
				return Promise.reject({
					error: extractResponseError(res.data) || '取消失败',
					statusCode: res.statusCode,
					data: res.data
				});
			}
			return {
				success: true,
				upvoted: false,
				alreadyRemoved: true
			};
		} catch (err) {
			// 记录已不存在视为取消成功，避免前端状态与后端不一致
			if (err?.statusCode === 404) {
				return { success: true, upvoted: false, alreadyRemoved: true };
			}
			throw err;
		}
	},

	/**
	 * 查询内容表情反馈状态（counts + mine）
	 * GET /userActivities/reactions/status?contentType=&articleName=
	 */
	getContentReactionStatus: async (contentType, articleName) => {
		const query = qs.stringify({
			articleName: (articleName || '').trim(),
			contentType: String(contentType || 'post')
		});
		const openid = getOpenid();
		const res = await directRequest({
			url: `${buildUniHaloUrl('/userActivities/reactions/status')}?${query}`,
			method: 'GET',
			header: openid
				? { 'Wechat-Session-Id': openid }
				: undefined
		});
		if (!isDirectRequestSuccess(res.statusCode)) {
			return Promise.reject({
				error: extractResponseError(res.data) || '查询表情失败',
				statusCode: res.statusCode
			});
		}
		const data = res.data || {};
		return {
			counts: data.counts || {},
			mine: data.mine || ''
		};
	},

	/**
	 * 提交表情反馈（同内容换表情 = 替换）
	 * POST /userActivities/reactions
	 */
	submitContentReaction: async (contentType, articleName, kind) => {
		const openid = getOpenid();
		if (!openid) {
			return Promise.reject({ error: '请先登录' });
		}
		const body = {
			type: 'reaction',
			contentType: String(contentType || 'post'),
			articleName: (articleName || '').trim(),
			reactionKind: String(kind || '')
		};
		const res = await directRequest({
			url: buildUniHaloUrl('/userActivities/reactions'),
			method: 'POST',
			data: body,
			header: {
				'Wechat-Session-Id': openid
			}
		});
		if (!isDirectRequestSuccess(res.statusCode)) {
			return Promise.reject({
				error: extractResponseError(res.data) || '表情反馈失败',
				statusCode: res.statusCode
			});
		}
		return res.data || {};
	},

	/**
	 * 取消我的表情反馈
	 * DELETE /userActivities/reactions
	 */
	cancelContentReaction: async (contentType, articleName) => {
		const openid = getOpenid();
		if (!openid) {
			return Promise.reject({ error: '请先登录' });
		}
		const body = {
			type: 'reaction',
			contentType: String(contentType || 'post'),
			articleName: (articleName || '').trim()
		};
		const res = await directRequest({
			url: buildUniHaloUrl('/userActivities/reactions'),
			method: 'DELETE',
			data: body,
			header: {
				'Content-Type': 'application/json',
				'Wechat-Session-Id': openid
			}
		});
		if (!isDirectRequestSuccess(res.statusCode)) {
			return Promise.reject({
				error: extractResponseError(res.data) || '取消表情失败',
				statusCode: res.statusCode
			});
		}
		return res.data || {};
	},

	/**
	 * 查询当前用户是否已点赞某篇文章
	 * GET /userActivities/upvote/status?articleName=xxx
	 * 返回：{ upvoted: boolean }
	 */
	getArticleUpvoteStatus: async (articleName) => {
		const openid = getOpenid();
		if (!openid) {
			return Promise.reject({ error: '请先登录' });
		}
		const name = (articleName || '').trim();
		if (!name) {
			return Promise.reject({ error: '缺少文章参数' });
		}
		const query = qs.stringify({ articleName: name });
		try {
			const res = await directRequest({
				url: `${buildUniHaloUrl('/userActivities/upvote/status')}?${query}`,
				method: 'GET',
				header: {
					'Wechat-Session-Id': openid
				}
			});
			if (isDirectRequestSuccess(res.statusCode)) {
				const data = res.data || {};
				return {
					upvoted: !!(data.upvoted ?? data.liked ?? data.hasUpvoted)
				};
			}
			if (res.statusCode === 400) {
				return Promise.reject({
					error: extractResponseError(res.data) || '查询点赞状态失败',
					statusCode: res.statusCode,
					data: res.data
				});
			}
			return { upvoted: false };
		} catch (err) {
			if (err?.statusCode === 404) {
				return { upvoted: false };
			}
			throw err;
		}
	},

	/**
	 * 获取用户行为文章列表
	 * GET /userActivities?type=comment|browse|upvote&page=0&size=10
	 */
	getUserActivityList: async (params = {}) => {
		const openid = getOpenid();
		if (!openid) {
			return Promise.reject({ error: '请先登录' });
		}
		const query = qs.stringify(params);
		const url = query
			? `${buildUniHaloUrl('/userActivities')}?${query}`
			: buildUniHaloUrl('/userActivities');
		return directRequest({
			url,
			method: 'GET',
			header: {
				'Wechat-Session-Id': openid
			}
		});
	},

	/**
	 * 获取文章二维码图片 URL
	 * 接口：GET /getQRCodeImg/{postId}
	 * 鉴权：无需鉴权
	 * 参数：postId - 文章 ID
	 * 返回：小程序码图片 URL 字符串
	 */
	getQRCodeImg: (postId) => {
		return HttpHandler.Get(`/apis/api.uni.uhalo.pro/v1alpha1/plugins/plugin-uni-halo/getQRCodeImg/${postId}`, null, {})
	},

	/**
	 * 获取二维码关联信息
	 * 接口：GET /getQRCodeInfo/{key}
	 * 鉴权：无需鉴权
	 * 参数：key - 二维码 key
	 * 返回：关联的文章信息（用于跳转详情）
	 */
	getQRCodeInfo: (key) => {
		return HttpHandler.Get(`/apis/api.uni.uhalo.pro/v1alpha1/plugins/plugin-uni-halo/getQRCodeInfo/${key}`, null, {})
	},

	/**
	 * 获取邀请场景小程序码（scene = inv:邀请码）
	 * 接口：GET /getInviteQRCode/{inviteCode}
	 * 鉴权：无需鉴权
	 * 参数：inviteCode - 当前用户邀请码
	 * 返回：{ imageUrl, base64 }（base64 供前端写本地文件绘制，绕开域名白名单）
	 */
	getInviteQRCodeImg: (inviteCode) => {
		return HttpHandler.Get(`/apis/api.uni.uhalo.pro/v1alpha1/plugins/plugin-uni-halo/getInviteQRCode/${encodeURIComponent(inviteCode)}`, null, {})
	},

	// ==================== Halo 账号绑定（v1.0.37 邮箱验证码） ====================

	/**
	 * 发送 Halo 账号绑定验证码（邮件由 Halo 后台发送）
	 * POST /sendHaloBindCode
	 */
	sendHaloBindCode: async ({ account }) => {
		const openid = getOpenid();
		if (!openid) {
			return Promise.reject({ error: '请先完成微信登录' });
		}
		const acc = (account || '').trim();
		if (!acc) {
			return Promise.reject({ error: '请输入 Halo 邮箱或用户名' });
		}
		return directRequest({
			url: buildUniHaloUrl('/sendHaloBindCode'),
			method: 'POST',
			data: { account: acc },
			header: {
				'Wechat-Session-Id': openid
			}
		});
	},

	/**
	 * 邮箱验证码确认绑定已有 Halo 账号
	 * POST /bindHaloAccountByCode
	 */
	bindHaloAccountByCode: async ({ account, code }) => {
		const openid = getOpenid();
		if (!openid) {
			return Promise.reject({ error: '请先完成微信登录' });
		}
		return directRequest({
			url: buildUniHaloUrl('/bindHaloAccountByCode'),
			method: 'POST',
			data: {
				account: (account || '').trim(),
				code: (code || '').trim()
			},
			header: {
				'Wechat-Session-Id': openid
			}
		});
	},

	/**
	 * 无 Halo 账号时一键创建
	 * POST /createHaloAccount
	 * 后端自动生成简短用户名（建议 wx + 6 位数字，如 wx482913）
	 */
	createHaloAccount: async () => {
		const openid = getOpenid();
		if (!openid) {
			return Promise.reject({ error: '请先完成微信登录' });
		}
		return directRequest({
			url: buildUniHaloUrl('/createHaloAccount'),
			method: 'POST',
			data: {},
			header: {
				'Wechat-Session-Id': openid
			}
		});
	},

	/**
	 * 解绑当前微信用户与 Halo 账号的关联
	 * POST /unbindHaloAccount
	 * 鉴权：Header Wechat-Session-Id: {openid}
	 * 返回：UserDTO（haloBindStatus: unbound，haloUserName 清空）
	 */
	unbindHaloAccount: async () => {
		const openid = getOpenid();
		if (!openid) {
			return Promise.reject({ error: '请先完成微信登录' });
		}
		return directRequest({
			url: buildUniHaloUrl('/unbindHaloAccount'),
			method: 'POST',
			data: {},
			header: {
				'Wechat-Session-Id': openid
			}
		});
	},

	// ==================== 积分系统 ====================

	/**
	 * 获取积分概览（余额 + 签到状态 + 今日已获得）
	 * GET /points/overview
	 */
	getPointsOverview: async () => {
		return requestPointsApi({ apiKey: 'overview', method: 'GET' });
	},

	/**
	 * 查询某文章某行为今日是否已领取积分
	 * GET /points/earn/status?action=browse&articleName=xxx
	 */
	getPointsEarnStatus: async (params = {}) => {
		return requestPointsApi({ apiKey: 'earnStatus', method: 'GET', params: params || {} });
	},

	/**
	 * 按行为领取积分
	 * POST /points/earn
	 */
	earnPointsByAction: async (data = {}) => {
		const payload = data || {};
		const body = {
			action: payload.action
		};
		if (payload.articleName) body.articleName = String(payload.articleName);
		if (payload.title) body.title = String(payload.title);
		if (payload.refId) body.refId = String(payload.refId);
		return requestPointsApi({ apiKey: 'earn', method: 'POST', data: body });
	},

	/**
	 * 每日签到
	 * POST /points/sign-in
	 */
	postPointsSignIn: async () => {
		return requestPointsApi({ apiKey: 'signIn', method: 'POST', data: {} });
	},

	/**
	 * 新用户见面礼（永久一次，幂等）
	 * POST /points/new-user-gift
	 */
	claimNewUserGift: async () => {
		return requestPointsApi({ apiKey: 'newUserGift', method: 'POST', data: {} });
	},

	/**
	 * 每日翻牌抽奖（每天一次）
	 * POST /points/draw
	 */
	claimPointsDraw: async () => {
		return requestPointsApi({ apiKey: 'draw', method: 'POST', data: {} });
	},

	/**
	 * 积分兑换：扣除积分发放权益
	 * POST /points/exchange
	 */
	exchangePointsItem: async (item) => {
		return requestPointsApi({
			apiKey: 'exchange',
			method: 'POST',
			data: { item: String(item || '') }
		});
	},

	/**
	 * 兑换中心余额/配额
	 * GET /points/exchange/balance
	 */
	getExchangeBalance: async () => {
		return requestPointsApi({ apiKey: 'exchangeBalance', method: 'GET' });
	},

	/**
	 * 今日翻牌状态（不消耗积分）
	 * GET /points/draw/status
	 */
	getPointsDrawStatus: async () => {
		return requestPointsApi({ apiKey: 'drawStatus', method: 'GET' });
	},

	/**
	 * 积分明细
	 * GET /points/history?page=0&size=10
	 */
	getPointsHistory: async (params = {}) => {
		return requestPointsApi({ apiKey: 'history', method: 'GET', params: params || {} });
	},

	/**
	 * 每日任务列表与今日进度
	 * GET /points/tasks/daily
	 */
	getDailyTasks: async () => {
		return requestPointsApi({ apiKey: 'dailyTasks', method: 'GET' });
	},

	/**
	 * 领取每日任务今日奖励
	 * POST /points/tasks/claim
	 */
	claimDailyTask: async (type) => {
		return requestPointsApi({
			apiKey: 'claimTask',
			method: 'POST',
			data: { type: String(type || '') }
		});
	},

	/**
	 * 等级状态（等级、佩戴称号、称号进度）
	 * GET /points/level
	 */
	getLevelStatus: async () => {
		return requestPointsApi({ apiKey: 'levelStatus', method: 'GET' });
	},

	/**
	 * 佩戴称号
	 * POST /points/level/title
	 */
	wearLevelTitle: async (titleId) => {
		return requestPointsApi({
			apiKey: 'wearTitle',
			method: 'POST',
			data: { titleId: String(titleId || '') }
		});
	},

	/**
	 * 装扮商店与持有状态
	 * GET /points/outfit
	 */
	getOutfitStatus: async () => {
		return requestPointsApi({ apiKey: 'outfitStatus', method: 'GET' });
	},

	/**
	 * 购买装扮（消耗积分）
	 * POST /points/outfit/buy
	 */
	buyOutfitItem: async (key) => {
		return requestPointsApi({
			apiKey: 'buyOutfit',
			method: 'POST',
			data: { key: String(key || '') }
		});
	},

	/**
	 * 穿戴/脱下装扮（key 为空表示脱下）
	 * POST /points/outfit/equip
	 */
	equipOutfitItem: async (key) => {
		return requestPointsApi({
			apiKey: 'equipOutfit',
			method: 'POST',
			data: { key: key || null }
		});
	},

	/**
	 * 邀请信息（邀请码、已邀请人数、奖励）
	 * GET /points/invite/info
	 */
	getInviteInfo: async () => {
		return requestPointsApi({ apiKey: 'inviteInfo', method: 'GET' });
	},

	/**
	 * 上报邀请海报已生成（管理端汇总展示用）
	 * POST /points/invite/poster/generate
	 */
	markInvitePosterGenerated: async () => {
		return requestPointsApi({ apiKey: 'invitePosterGenerate', method: 'POST', data: {} });
	},

	/**
	 * 上传邀请海报到后台附件（multipart，字段名 file）
	 * POST /points/invite/poster/upload
	 * 鉴权：Header Wechat-Session-Id: {openid}
	 * 返回：{ imageUrl: string }
	 */
	uploadInvitePoster: (filePath) => {
		const openid = getOpenid();
		if (!openid) {
			return Promise.reject({ error: '请先登录后再上传邀请海报' });
		}
		return new Promise((resolve, reject) => {
			uni.uploadFile({
				url: buildUniHaloUrl('/points/invite/poster/upload'),
				filePath,
				name: 'file',
				header: {
					'Wechat-Session-Id': openid
				},
				success: (res) => {
					let data = res.data;
					if (typeof data === 'string') {
						try {
							data = JSON.parse(data);
						} catch (e) {
							reject({ error: '邀请海报上传响应解析失败', statusCode: res.statusCode });
							return;
						}
					}
					if (res.statusCode === 200 && data?.imageUrl) {
						resolve({ statusCode: 200, data });
						return;
					}
					reject({
						error: data?.error || '邀请海报上传失败',
						statusCode: res.statusCode,
						data
					});
				},
				fail: (err) => {
					reject({ error: err.errMsg || '邀请海报上传失败', detail: err });
				}
			});
		});
	},

	/**
	 * 幸运抽奖配置（规则公示）
	 * GET /points/lucky/config
	 */
	getLuckyConfig: async () => {
		return requestPointsApi({ apiKey: 'luckyConfig', method: 'GET' });
	},

	/**
	 * 参与一次幸运抽奖（消耗积分）
	 * POST /points/lucky/draw
	 */
	drawLuckyGift: async () => {
		return requestPointsApi({ apiKey: 'luckyDraw', method: 'POST', data: {} });
	},

	/**
	 * 中奖后提交收货信息
	 * POST /points/lucky/submit
	 */
	submitLuckyClaim: async (data = {}) => {
		const body = {
			winningId: String(data.winningId || ''),
			contactName: String(data.contactName || ''),
			phone: String(data.phone || ''),
			address: String(data.address || '')
		};
		return requestPointsApi({ apiKey: 'luckySubmit', method: 'POST', data: body });
	},

	/**
	 * 我的中奖单列表（含待填写收货信息的提醒）
	 * GET /points/lucky/mine
	 */
	getMyLuckyClaims: async () => {
		return requestPointsApi({ apiKey: 'luckyMine', method: 'GET' });
	},

	/**
	 * 站点数据看板（插件自身统计，不依赖第三方插件）
	 * GET /dashboard/summary
	 */
	getDashboardSummary: async () => {
		return directRequest({
			url: buildUniHaloUrl('/dashboard/summary'),
			method: 'GET'
		});
	},

	/**
	 * 热榜（按浏览量 Top N）
	 * GET /rank/articles?limit=10
	 */
	getRankArticles: async (limit = 10) => {
		return directRequest({
			url: `${buildUniHaloUrl('/rank/articles')}?limit=${limit || 10}`,
			method: 'GET'
		});
	},

	/**
	 * 用户榜（榜单公开可读）
	 * GET /rank/users?type=points|active|invite&range=all|week&limit=20
	 * 带 Wechat-Session-Id 时额外返回 me（我的排名）
	 */
	getUserRank: async ({ type = 'points', range = 'all', limit = 20 } = {}) => {
		const openid = getOpenid();
		const query = `type=${encodeURIComponent(type)}&range=${encodeURIComponent(range)}&limit=${limit || 20}`;
		return directRequest({
			url: `${buildUniHaloUrl('/rank/users')}?${query}`,
			method: 'GET',
			header: openid ? { 'Wechat-Session-Id': openid } : {}
		});
	},

	/**
	 * 互动动态（最近用户的评论/点赞/投票，榜单公开可读）
	 * GET /rank/activities?limit=10
	 */
	getRankActivities: async (limit = 10) => {
		return directRequest({
			url: `${buildUniHaloUrl('/rank/activities')}?limit=${limit || 10}`,
			method: 'GET'
		});
	},

	// ==================== 虚拟支付（积分充值） ====================

	/**
	 * 虚拟支付公开配置（商品列表）
	 * GET /pay/config
	 */
	getVirtualPayConfig: async () => {
		const openid = getOpenid();
		if (!openid) {
			return Promise.reject({ error: '请先登录' });
		}
		return directRequest({
			url: buildUniHaloUrl('/pay/config'),
			method: 'GET',
			header: {
				'Wechat-Session-Id': openid
			}
		});
	},

	/**
	 * 下单：服务器生成 signData/paySig/signature（mode=short_series_goods 道具直购）
	 * POST /pay/order
	 */
	createPayOrder: async (goodsId) => {
		const openid = getOpenid();
		if (!openid) {
			return Promise.reject({ error: '请先登录' });
		}
		return directRequest({
			url: buildUniHaloUrl('/pay/order'),
			method: 'POST',
			data: {
				goodsId: String(goodsId || '')
			},
			header: {
				'Wechat-Session-Id': openid
			}
		});
	},

	/**
	 * 查询本地订单状态（pending 时服务端会顺带调 query_order 兜底）
	 * POST /pay/query
	 */
	queryPayOrder: async (outTradeNo) => {
		const openid = getOpenid();
		if (!openid) {
			return Promise.reject({ error: '请先登录' });
		}
		return directRequest({
			url: buildUniHaloUrl('/pay/query'),
			method: 'POST',
			data: {
				outTradeNo: String(outTradeNo || '')
			},
			header: {
				'Wechat-Session-Id': openid
			}
		});
	},

	// ==================== 阅读券（付费内容） ====================

	/**
	 * 查询文章的付费锁定状态（不扣券）。
	 *
	 * <p>未登录也可调用（第二参 true = allowAnonymous），返回 locked / unlocked /
	 * tickets / costTickets 供详情页渲染解锁卡片。若强制登录，未登录用户打开付费文章时
	 * 只能看到登录错误态，拿不到「本文需阅读券」的引导。</p>
	 */
	getContentStatus: async (articleName) => {
		const name = String(articleName || '').trim();
		if (!name) {
			return Promise.reject({ error: '缺少文章参数' });
		}
		return requestContentApi({
			apiKey: 'contentStatus',
			method: 'GET',
			params: { articleName: name }
		}, true);
	},

	/**
	 * 用阅读券解锁一篇付费文章（幂等，已解锁不会重复扣券）。需登录。
	 */
	unlockContent: async (articleName) => {
		const name = String(articleName || '').trim();
		if (!name) {
			return Promise.reject({ error: '缺少文章参数' });
		}
		return requestContentApi({
			apiKey: 'contentUnlock',
			method: 'POST',
			data: { articleName: name }
		});
	},

	/** 查询当前用户的阅读券余额；未登录返回 0 而不报错。 */
	getContentTickets: async () => {
		return requestContentApi({ apiKey: 'contentTickets', method: 'GET' }, true);
	},

	/**
	 * 我已解锁的付费文章（解锁时间倒序，附当前券余额）。需登录。
	 *
	 * <p>券花掉之后此前无处可查自己买过什么，券就只是一次性消耗品；
	 * 列出解锁历史后券变成可回溯的资产。</p>
	 */
	getMyUnlockedArticles: async (params = {}) => {
		return requestContentApi({
			apiKey: 'contentUnlocked',
			method: 'GET',
			params: params || {}
		});
	},

	/**
	 * 当前用户的会员状态（单次购买的有效期权益）。
	 *
	 * <p>未登录也允许调用：服务端返回「未开通」而不是报错，
	 * 前端据此决定展示会员入口还是已开通状态。</p>
	 */
	getMembershipStatus: async () => {
		return requestContentApi({ apiKey: 'membership', method: 'GET' }, true);
	},

	// ==================== 分类订阅 ====================
	//
	// 订阅只维护一套数据：postCategories（站点分类的 metadata.name）。
	// 记录仍存在日报的订阅存储里（服务端按 openid 唯一），因此接口路径沿用
	// /digest/subscription——换存储要迁移老数据，收益却只是路径好看，不值得。

	/**
	 * 读取当前用户订阅的分类（无需登录也能调，返回空列表）。
	 */
	getDigestSubscription: async () => {
		return directRequest({
			url: buildUniHaloUrl('/digest/subscription'),
			method: 'GET',
			header: buildOpenidHeader()
		});
	},

	/**
	 * 保存订阅分类（全量替换）。
	 *
	 * <p>四个字段都显式提交：服务端是整体替换这份偏好，而 categories / keywords /
	 * sources 是早年「AI 分类订阅」留下的字段，现在界面只维护 postCategories。
	 * 显式传空数组，让「清掉旧字段」是写明的意图，而不是靠省略参数被当成空值。</p>
	 *
	 * <p>服务端会做数量与长度校验，超限时返回可读原因（不会静默截断）。</p>
	 */
	saveDigestSubscription: async (payload = {}) => {
		return directRequest({
			url: buildUniHaloUrl('/digest/subscription'),
			method: 'POST',
			data: {
				enabled: payload.enabled !== false,
				categories: payload.categories || [],
				keywords: payload.keywords || [],
				sources: payload.sources || [],
				// 订阅的站点分类：决定首页主列表显示哪些文章
				postCategories: payload.postCategories || []
			},
			header: buildOpenidHeader()
		});
	},

	// ==================== 我的订单 ====================

	/**
	 * 我的订单列表（仅当前 openid 自己的订单，创建时间倒序）。
	 * GET /pay/orders?page=0&size=20
	 */
	getMyPayOrders: async (params = {}) => {
		const openid = getOpenid();
		if (!openid) {
			return Promise.reject({ error: '请先登录' });
		}
		const query = qs.stringify({
			page: params.page != null ? params.page : 0,
			size: params.size != null ? params.size : 20
		});
		return directRequest({
			url: `${buildUniHaloUrl('/pay/orders')}?${query}`,
			method: 'GET',
			header: {
				'Wechat-Session-Id': openid
			}
		});
	},
}
