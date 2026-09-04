/**
 *  功能：认证与授权工具
 *  作者：小莫唐尼
 *  邃箱：studio@925i.cn
 *  时间：2022年07月21日 18:00:05
 *  版本：v0.2.0
 */

import {
	getCache
} from "./storage";

const TokenAuthKey = "EvanIAlley_App_Token";

/**
 * 获取登录token
 */
export function getAdminAccessToken() {
	const tokens = getCache('APP_ADMIN_LOGIN_TOKEN');
	if (tokens) {
		return tokens.access_token;
	}
	return null
}

/**
 * 获取刷新token
 */
export function getAdminRefreshToken() {
	const tokens = getCache('APP_ADMIN_LOGIN_TOKEN');
	if (tokens) {
		return tokens.refresh_token;
	}
	return null
}

/**
 * 设置微信登录信息（JSON.stringify 存储）
 * v1.0.37 UserDTO：{ id, openid, unionid, nickname, avatarUrl, haloUserName?, haloBindStatus?: unbound|bound|created }
 * 直接使用 openid 字段存储
 */
export function setWxLoginInfo(info) {
	if (!info) return;
	// sessionKey 仅服务端使用，不在客户端持久化
	const toStore = { ...info };
	delete toStore.sessionKey;
	uni.setStorageSync('APP_WX_LOGIN_INFO', JSON.stringify(toStore));
	if (info.openid) {
		uni.setStorageSync('openid', info.openid);
	}
}

/**
 * 将微信用户资料同步到评论 Visitor 缓存
 */
export function syncVisitorFromWxUser(info) {
	if (!info) return;
	uni.setStorageSync('Visitor', JSON.stringify({
		author: info.nickname || '',
		avatar: info.avatarUrl || '',
		email: '',
		authorUrl: ''
	}));
}

/**
 * 登录成功：写入本地并同步评论资料
 */
export function applyWxLoginSuccess(info) {
	setWxLoginInfo(info);
	syncVisitorFromWxUser(info);
	uni.$emit('wx_login_status_changed', info);
}

/**
 * 获取微信登录信息
 */
export function getWxLoginInfo() {
	let wxLoginInfo = uni.getStorageSync('APP_WX_LOGIN_INFO');
	if (wxLoginInfo) {
		return JSON.parse(wxLoginInfo)
	} else {
		return null;
	}
}

/**
 * 更新微信登录信息中的昵称和头像
 */
export function updateWxLoginProfile({ nickname, avatarUrl }) {
	const info = getWxLoginInfo();
	if (info) {
		if (nickname) info.nickname = nickname;
		if (avatarUrl) info.avatarUrl = avatarUrl;
		setWxLoginInfo(info);
	}
}

/**
 * 清除微信登录信息
 */
export function clearWxLoginInfo() {
	uni.removeStorageSync('APP_WX_LOGIN_INFO');
	uni.removeStorageSync('openid');
	uni.$emit('wx_login_status_changed', null);
}

/**
 * 检查是否已经微信授权
 */
export function checkHasWxLogin() {
	return !!uni.getStorageSync('APP_WX_LOGIN_INFO')
}

/**
 * 检查超管是否已经登录
 */
export function checkHasAdminLogin() {
	return !!getCache('APP_ADMIN_LOGIN_TOKEN')
}

/**
 * 获取openid
 */
export function getOpenid() {
	return uni.getStorageSync('openid');
}

/**
 * 获取nologin-email
 */
export function getNologinEmail() {
	const wxUser = getWxLoginInfo();
	if (wxUser?.openid) {
		return `${wxUser.openid}@mini.unihalo.local`;
	}
	const openid = getOpenid();
	if (openid && openid.length > 20) {
		return `${openid}@mini.unihalo.local`;
	}
	let Visitor = uni.getStorageSync('Visitor');
	if (!Visitor) return '';
	Visitor = JSON.parse(Visitor);
	return Visitor.email || Visitor.author || '';
}
