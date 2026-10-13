/**
 * 服务端配置的刷新（带节流）。
 *
 * <p><b>为什么需要它</b>：插件后台下发的配置（底部导航、首页布局、外观开关…）原来只在
 * 冷启动时拉一次 —— 站长在后台改完「底部导航」的开关，小程序端必须杀掉进程重进才生效，
 * 实测被当成「后台改了配置但没应用」的故障报过。这里把「回到前台」与「下拉刷新」都接上，
 * 并用节流避免频繁切前后台时的重复请求。</p>
 *
 * <p>与 `pullAppSettings()`（用户自己的偏好，跟账号走）是两件事：那边同步的是「用户的设置副本」，
 * 这边刷新的是「站长下发的站点配置」。两者都可能改变底栏外观，各自负责各自的来源。</p>
 */

import { hideNativeTabBar } from '@/utils/tabbar.js';

/** 两次自动刷新之间的最小间隔：切前后台很频繁，别每次都打接口 */
const MIN_INTERVAL_MS = 3 * 60 * 1000;

let lastRefreshAt = 0;
let inFlight = false;

/**
 * 刷新站点配置。
 *
 * @param {object} [options]
 * @param {boolean} [options.force] 冷启动用 true：忽略节流，必定拉一次
 * @returns {Promise<boolean>} 是否真的刷新了（被节流/已有请求在飞时为 false）
 */
export function refreshAppConfigs(options = {}) {
	const force = options.force === true;
	const now = Date.now();
	if (!force && now - lastRefreshAt < MIN_INTERVAL_MS) {
		return Promise.resolve(false);
	}
	if (inFlight) {
		return Promise.resolve(false);
	}
	inFlight = true;
	lastRefreshAt = now;
	return uni.$tm.vx
		.actions('config/fetchConfigs')
		.then(() => {
			// 配置可能刚被改成「启用自绘底栏」：原生栏的对齐要跟着做一次，
			// 否则会出现「自绘底栏已渲染、原生栏还压着」的双层底栏
			hideNativeTabBar();
			return true;
		})
		.catch(() => false)
		.finally(() => {
			inFlight = false;
		});
}

export default { refreshAppConfigs };
