/**
 * 底部导航（自绘 TabBar）工具
 *
 * 背景：小程序底部导航默认由微信原生渲染，但原生 `tabBar.list` 是编译期静态的、
 * 最多 5 项、运行期无法增删（微信只提供 setTabBarItem/hideTabBar 这类接口）。
 * 站长想在「首页/分类/瞬间/榜单/图库/小店/我的」这 7 个候选里配置显示 3~5 项，
 * 就只能在保留原生声明（保证 switchTab 可用）的同时隐藏原生栏、自己画一条。
 *
 * 职责划分：
 * - 服务端（插件 tabbarConfig）负责「显示哪些、什么顺序」的归一化与最终 items[]；
 * - 本模块负责读取配置、判断是否接管、以及隐藏原生栏这类公共动作；
 * - 渲染与切换交给 components/uh-tabbar。
 */

import Vue from 'vue';
import { getAppConfigs, DefaultAppConfigs } from '@/config/index.js';

/** 底栏内容高度（rpx）。取 100rpx = 50px，与微信原生底栏高度对齐，切换时页面不跳。 */
export const TABBAR_BAR_HEIGHT_RPX = 100;

/** 旧导出名保持兼容：纸面底栏满宽、零悬浮间距。 */
export const TABBAR_GLASS_GAP_RPX = 0;
export const TABBAR_GLASS_SIDE_RPX = 0;

/**
 * 兜底底栏项：只在「后台已开启，但配置里没有 items」时用（例如服务端返回被截断、
 * 或首次启动时本地存储里只有旧配置）。内容与 pages.json 的原生 5 项一致。
 *
 * 注意：不要把这些默认项写进 config/index.js 的 DefaultAppConfigs——配置合并用的是
 * utils.deepMerge，它对数组做 concat，本地默认项会和服务端项拼起来变成 10 项。
 */
const FALLBACK_TABBAR_ITEMS = [
	{ key: 'home', label: '首页', icon: '/static/tabbar/select_home.png', activeIcon: '/static/tabbar/select_home_active.png', path: '/pages/tabbar/home/home', nativeTab: true },
	{ key: 'category', label: '发现', icon: '/static/tabbar/select_category.png', activeIcon: '/static/tabbar/select_category_active.png', path: '/pages/tabbar/discover/discover', nativeTab: true },
	{ key: 'moments', label: '瞬间', icon: '/static/tabbar/select_gallery.png', activeIcon: '/static/tabbar/select_gallery_active.png', path: '/pages/tabbar/moments/moments', nativeTab: true },
	{ key: 'rank', label: '榜单', icon: '/static/tabbar/select_rank.png', activeIcon: '/static/tabbar/select_rank_active.png', path: '/pages/tabbar/rank/rank', nativeTab: true },
	{ key: 'about', label: '我的', icon: '/static/tabbar/select_mine.png', activeIcon: '/static/tabbar/select_mine_active.png', path: '/pages/tabbar/about/about', nativeTab: true }
];

/**
 * 读取底部导航配置（含本地兜底，避免存储损坏时整个底栏消失）。
 *
 * 优先读运行中的 store（vuex getter）：它是配置拉取后 deepMerge 的权威结果；
 * 本地存储只作兜底——两者万一不同步（存储写失败、旧缓存），以 store 为准，
 * 否则会出现「自绘底栏渲染了、但隐藏原生栏的判断认为是关闭」这种自相矛盾的画面。
 */
export function getTabbarConfig() {
	const fromStore = uni?.$tm?.vx?.getters?.()?.getConfigs;
	const tabbar = fromStore?.tabbarConfig || getAppConfigs()?.tabbarConfig;
	if (tabbar && typeof tabbar === 'object') {
		return tabbar;
	}
	return DefaultAppConfigs.tabbarConfig;
}

/**
 * 是否由自绘底栏接管（关闭时保持微信原生底栏，行为与历史版本一致）。
 *
 * @param configs 可选的配置对象（页面里传 this.haloConfig，走响应式的 store 数据）
 */
export function isCustomTabbarEnabled(configs) {
	const tabbar = configs?.tabbarConfig || getTabbarConfig();
	return tabbar?.enabled === true;
}

/**
 * 当前要显示的底栏项。
 *
 * 数据来源分两层：
 * - 站长在插件后台配的默认（服务端已归一化到 3~5 项，下发 items[] 与 candidates[]）；
 * - 用户在「我的 → 系统设置 → 底部导航」里的本地偏好（appSettings.tabbar），
 *   只在用户显式打开「自定义」后生效，并同样受 3~5 项规则约束。
 *
 * 另外做三道保险：
 * 1. 去重：历史版本把本地默认项与服务端项 concat 在一起（deepMerge 的数组语义），
 *    缓存里可能残留 key 重复的项——重复项会让同一个 tab 在底栏出现两次、两处都高亮；
 * 2. 校验：用户偏好里引用的 key 若已不在候选清单里（站长下掉了某项），直接忽略；
 * 3. 兜底：后台已开启但一项都没有时，用 FALLBACK_TABBAR_ITEMS，避免出现空底栏。
 *
 * @param configs 配置对象（页面/组件里传 this.haloConfigs，走响应式的 store 数据）
 * @param pref    用户偏好（this.globalAppSettings.tabbar），可选
 */
export function getTabbarItems(configs, pref) {
	const tabbar = configs?.tabbarConfig || getTabbarConfig();
	if (tabbar?.enabled !== true) return [];

	const candidates = getTabbarCandidates(configs);
	const adminItems = sanitizeItems(tabbar.items);

	// 用户没开自定义（或服务端还没下发候选清单）：用站长那份
	if (pref?.useCustom !== true || !candidates.length) {
		return adminItems.length ? adminItems : FALLBACK_TABBAR_ITEMS;
	}

	// 用户自定义：固定项必留，可选项按用户开关收集，再按同一套 3~5 规则收敛。
	// 勾选范围必须先与站长的开关求交集（adminOn=false 的项不参与）——
	// 否则用户先开了某项、站长随后关掉，残留的偏好会让它重新出现在底栏，
	// 表现为「后台明明关了，用户端还能把它开回来」。
	const allowedCandidates = candidates.filter((item) => item.locked || item.adminOn !== false);
	const picked = allowedCandidates.filter((item) => {
		if (item.locked) return true;
		return pref.show?.[item.key] === true;
	});
	const normalized = normalizeKeys(picked, allowedCandidates);
	return normalized.length ? sanitizeItems(normalized) : (adminItems.length ? adminItems : FALLBACK_TABBAR_ITEMS);
}

/**
 * 视图切换请求（响应式共享状态）。
 *
 * <p><b>为什么不用 uni.$emit</b>：全局事件是常驻监听，宿主页/组件销毁后监听器可能仍在，
 * 结果就是「Cannot read properties of undefined (reading 'apply')」这类陈旧回调报错
 * （小程序端的组件销毁时机与 H5 不一致，beforeDestroy 不保证执行）。
 * 换成响应式对象后，宿主页用 watch 响应、实例销毁后自然失效，不会留残骸。</p>
 *
 * <p>只在「底栏点图库/小店，而宿主页已经在显示」时用得上——那种情况 switchTab
 * 到同一页不会触发 onShow，只能靠状态变化驱动换视图。</p>
 */
export const tabbarViewRequest = Vue.observable({ mode: '', seq: 0 });

export function requestTabbarView(mode) {
	tabbarViewRequest.mode = mode || '';
	tabbarViewRequest.seq += 1;
}

/**
 * 待切换的视图模式。
 *
 * 图库/小店与瞬间共用同一个原生 tab 页（微信原生 tab 页上限 5 个，候选有 7 个），
 * 底栏点它们时先 switchTab 到宿主页，再由宿主页在 onShow 里取走这个值切视图。
 * 只存内存：切完即清，不落盘（重启后回默认视图是合理的）。
 */
let pendingTabbarMode = '';

export function setPendingTabbarMode(mode) {
	pendingTabbarMode = mode || '';
}

/** 取走待切换模式（取一次就清空，避免下次进页面又被切走） */
export function consumePendingTabbarMode() {
	const mode = pendingTabbarMode;
	pendingTabbarMode = '';
	return mode;
}

/**
 * 候选项（服务端下发全量 7 项，含站长关掉的），用于设置页给用户勾选。
 * 老版本服务端没有这个字段时返回空数组，此时设置页不展示自定义入口。
 */
export function getTabbarCandidates(configs) {
	const tabbar = configs?.tabbarConfig || getTabbarConfig();
	const list = Array.isArray(tabbar?.candidates) ? tabbar.candidates : [];
	return list.filter((item) => item && item.key && item.path);
}

/**
 * 哪些可选项（非 locked）能被用户勾选，供设置页渲染开关。
 *
 * <p>站长在后台关掉的项（{@code adminOn === false}）不在这里出现：设置页的开关
 * 只应该控制「站长允许显示的项」里显示哪几个，而不是能把站长关掉的项重新打开——
 * 两层开关必须串成一条线（站长定范围 → 用户在范围内挑），否则后台配置形同虚设。</p>
 */
export function getToggleableCandidates(configs) {
	return getTabbarCandidates(configs)
		.filter((item) => !item.locked && item.adminOn !== false);
}

/**
 * 底栏总项数区间：**微信只规定原生 tabBar.list 是 2~5 项**，
 * 这里是产品口径——3 是「不至于空得不像底栏」的下限，5 是上限。
 * 下调这个值只需要改这里与服务端 normalizeTabbarConfig 的常量。
 */
export const TABBAR_MIN_ITEMS = 3;
export const TABBAR_MAX_ITEMS = 5;

/** 用户已勾选的可选项个数（设置页用来做上下限提示与拦截） */
export function countUserPicked(candidates, pref) {
	return candidates.filter((item) => !item.locked && pref?.show?.[item.key] === true).length;
}

/** 设置页允许的可选项数量区间（固定项恒为 2 席） */
export const TABBAR_MIN_OPTIONAL = TABBAR_MIN_ITEMS - 2;
export const TABBAR_MAX_OPTIONAL = TABBAR_MAX_ITEMS - 2;

/**
 * 把一组项收敛到 3~5 项：多了按候选逆序裁（小店 → 图库 → 榜单 → 瞬间 → 分类），
 * 少了按候选顺序补。与服务端 normalizeTabbarConfig 的规则保持一致——
 * 前端这份是给「用户偏好」用的，服务端那份管「站长默认」。
 */
function normalizeKeys(picked, candidates) {
	const locked = candidates.filter((item) => item.locked);
	const optional = candidates.filter((item) => !item.locked);
	const result = [...locked, ...picked.filter((item) => !item.locked)];

	for (let i = optional.length - 1; i >= 0 && result.length > TABBAR_MAX_ITEMS; i--) {
		const index = result.indexOf(optional[i]);
		if (index >= 0) result.splice(index, 1);
	}
	for (let i = 0; i < optional.length && result.length < TABBAR_MIN_ITEMS; i++) {
		if (!result.includes(optional[i])) result.push(optional[i]);
	}
	// 输出顺序按候选清单（首页 → … → 我的），避免补进来的项排到末尾
	return candidates.filter((item) => result.includes(item));
}

/** 去掉重复项与缺少关键字段的脏数据 */
function sanitizeItems(raw) {
	const list = Array.isArray(raw) ? raw : [];
	const seen = new Set();
	const items = [];
	for (const item of list) {
		if (!item || !item.key || !item.path || seen.has(item.key)) continue;
		seen.add(item.key);
		items.push(item);
	}
	return items;
}

/**
 * 隐藏微信原生底栏。
 *
 * <p><b>为什么要重试</b>：iOS 上首次进入页面时，在 onLoad/onShow 阶段调用
 * `wx.hideTabBar()` 经常不生效——此时视图还没 ready，原生栏随后才被创建出来，
 * 结果就是原生栏与自绘栏同时出现（安卓上一般正常）。所以除了在 onReady/onShow
 * 里调用，这里再用定时器补两次，覆盖「原生栏晚于调用创建」的时机。</p>
 *
 * <p>非 tab 页调用会失败（该页本来就没有原生栏），fail 一律吞掉。</p>
 */
export function hideNativeTabBar(configs, retries = 2) {
	if (!isCustomTabbarEnabled(configs)) return;
	uni.hideTabBar({
		animation: false,
		fail: () => {}
	});
	if (retries > 0) {
		setTimeout(() => hideNativeTabBar(configs, retries - 1), 200);
	}
}

/**
 * 悬浮按钮需要抬高的像素值。
 *
 * 原生底栏存在时，页面窗口高度不含底栏，fixed 元素自然落在栏上方；
 * 隐藏原生栏后窗口占满全屏，固定定位的悬浮按钮会被自绘底栏压住，所以要顶起来。
 */
export function tabbarBottomOffsetPx() {
	if (!isCustomTabbarEnabled()) return 0;
	// 内容区 + 胶囊下沿间距：底栏是悬浮的，抬高量要按它的实际占用算
	return uni.upx2px(TABBAR_BAR_HEIGHT_RPX + TABBAR_GLASS_GAP_RPX);
}

export default {
	TABBAR_BAR_HEIGHT_RPX,
	TABBAR_GLASS_GAP_RPX,
	TABBAR_GLASS_SIDE_RPX,
	TABBAR_MIN_ITEMS,
	TABBAR_MAX_ITEMS,
	TABBAR_MIN_OPTIONAL,
	TABBAR_MAX_OPTIONAL,
	getTabbarConfig,
	getTabbarCandidates,
	getToggleableCandidates,
	countUserPicked,
	isCustomTabbarEnabled,
	getTabbarItems,
	setPendingTabbarMode,
	consumePendingTabbarMode,
	requestTabbarView,
	tabbarViewRequest,
	hideNativeTabBar,
	tabbarBottomOffsetPx
};
