/**
 * 主题皮肤：把用户已穿戴的 theme 装扮应用到全局。
 *
 * 背景：tm-vuetify 已内置全局换肤能力（Vuex tmVuetify.color + fllowTheme 默认 true），
 * 但项目此前从未把装扮里的 theme 字段接进来，导致用户购买主题后界面毫无变化。
 * 本模块负责打通「服务端 activeTheme → 全局配色 → tabBar」这条链路。
 *
 * 约定：levelConfig.outfits 中 type=theme 的装扮，style 字段填 tm-vuetify 主题名
 * （如 light-blue / deep-orange），而非 CSS 渐变值。旧的渐变值通过
 * LEGACY_GRADIENT_THEME_MAP 自动映射，保证已购买用户的装扮不失效。
 */

/** 主题皮肤白名单：只有登记在此的主题才允许应用。 */
import { SKINS, isSkinId, applySkin, restoreSkin, skinState } from '@/utils/skin.js';

export const THEME_SKINS = {
	// 项目既有主视觉，tabBar 选中色 #03a9f4 与之匹配
	'light-blue': { label: '晴空蓝', color: '#03A9F4' },
	primary: { label: '静谧蓝紫', color: '#3B5CF0' },
	blue: { label: '经典蓝', color: '#2196F3' },
	cyan: { label: '深海青', color: '#00BCD4' },
	green: { label: '森林绿', color: '#4CAF50' },
	orange: { label: '暖阳橙', color: '#FF9800' },
	'deep-orange': { label: '落日橙', color: '#FF5722' },
	'blue-grey': { label: '雾霭灰蓝', color: '#607D8B' }
};

/**
 * 未穿戴任何主题时的 tabBar 选中色。
 * 取自 pages.json 的 tabBar.selectedColor（品牌靛青），保持升级前后视觉一致。
 */
const DEFAULT_TABBAR_SELECTED_COLOR = '#C2410C';

/**
 * 旧格式 style（CSS 渐变值）→ 新格式主题名的映射。
 *
 * 早期 theme 装扮的 style 存的是 "#667eea,#764ba2" 这类渐变值，
 * 框架换肤需要的是主题名，两者不兼容。此处按色相就近映射，
 * 避免已购买用户的主题装扮升级后失效。
 */
const LEGACY_GRADIENT_THEME_MAP = {
	// 蓝紫渐变（原 theme_dusk「静谧蓝紫主题」）→ 与新默认值 primary 一致
	'#667eea,#764ba2': 'primary',
	// 橙黄渐变（原 theme_sunset「落日橙主题」）→ 与新默认值 deep-orange 一致，
	// 保证已配置旧值的站点与新装站点渲染出同一个主题
	'#f6d365,#fda085': 'deep-orange'
};

const STORAGE_KEY = 'setTmVuetifyColor';

/**
 * 把装扮里的 style 归一化为合法主题名。
 *
 * @param {string} style 装扮配置的 style 字段
 * @returns {string} 合法主题名；无法识别时返回空字符串（表示回退默认）
 */
export const normalizeThemeStyle = (style) => {
	const raw = String(style || '').trim();
	if (!raw) return '';
	// 新格式：直接是主题名
	if (THEME_SKINS[raw]) return raw;
	// 旧格式：CSS 渐变值，尝试映射（忽略空格差异）
	const compact = raw.replace(/\s+/g, '');
	if (LEGACY_GRADIENT_THEME_MAP[compact]) {
		return LEGACY_GRADIENT_THEME_MAP[compact];
	}
	// 无法识别（含未登记的渐变值）：不猜测，回退默认主题
	return '';
};

/** 主题名对应的 tabBar 选中色；未登记时回退项目原有选中色。 */
const tabbarSelectedColor = (themeName) => {
	const skin = THEME_SKINS[themeName];
	return skin ? skin.color : DEFAULT_TABBAR_SELECTED_COLOR;
};

/**
 * 应用主题装扮到全局。
 *
 * 支持两类取值（后端「装扮商店」的 style 字段）：
 * - 皮肤包 id（paper/mint/sunrise/ink）：页面底、卡片、文字、分割线、强调色一起换，见 utils/skin.js
 * - 旧的框架主题名（light-blue/primary/orange…）：只换强调色，兼容已购用户
 *
 * @param {string} style 装扮的 style 字段
 * @returns {string} 实际生效的皮肤 id 或主题名（空字符串表示未应用任何主题）
 */
export const applyThemeSkin = (style) => {
	const raw = String(style || '').trim();

	// 皮肤包：整站外观一起换
	if (isSkinId(raw)) {
		return applySkin(raw);
	}

	// 非皮肤取值：先恢复默认外观（可能刚从某个皮肤切过来），再只换强调色
	applySkin('paper');
	const target = normalizeThemeStyle(raw);

	try {
		// tm-vuetify 的全局主题色，fllowTheme 默认为 true 的组件会自动跟随；
		// 置空时组件回落到自身 theme 属性，等价于换肤前的默认表现
		if (uni.$tm && uni.$tm.vx) {
			uni.$tm.vx.commit('setTmVuetifyColor', target);
		}
		uni.setStorageSync(STORAGE_KEY, target);
	} catch (err) {
		console.warn('[themeSkin] 应用主题失败:', err);
	}

	// tabBar 选中色不在框架换肤范围内（setTmVuetifyBlack 只处理深色模式的黑白），
	// 需单独同步，否则换肤后 tabBar 仍是旧色，视觉割裂。
	try {
		uni.setTabBarStyle({
			selectedColor: tabbarSelectedColor(target)
		});
	} catch (err) {
		// 非 tabBar 页面调用会失败（如启动页、分包页），属正常情况，忽略
	}

	return target;
};

/**
 * 从本地存储恢复主题（App 启动时调用）。
 * 未设置过时保持项目原有配色，不做任何染色。
 */
export const restoreThemeSkin = () => {
	// 先恢复皮肤：皮肤 id 存在自己的键里，且皮肤生效时 STORAGE_KEY 存的是它对应的
	// 框架主题色名（如 teal），那不是合法装扮值，直接走旧逻辑会把皮肤重置掉
	const skinId = restoreSkin();
	if (skinId !== 'paper') {
		return skinId;
	}

	let stored = '';
	try {
		stored = uni.getStorageSync(STORAGE_KEY) || '';
	} catch (err) {
		stored = '';
	}
	return applyThemeSkin(stored);
};

/** 清除主题皮肤，恢复项目原有配色。 */
export const clearThemeSkin = () => applyThemeSkin('');

/**
 * 供装扮列表预览用的色值。
 * 皮肤包返回代表色/渐变；新格式主题名返回其主色；旧格式渐变值仍返回渐变，保证预览不空白。
 */
export const themeSwatchOf = (style) => {
	const raw = String(style || '').trim();
	if (!raw) return DEFAULT_TABBAR_SELECTED_COLOR;
	// 皮肤包：用皮肤自身的代表色/渐变，用户在购买前就能看出和「只换个色」的区别
	if (isSkinId(raw)) return SKINS[raw].swatch || DEFAULT_TABBAR_SELECTED_COLOR;
	if (THEME_SKINS[raw]) return THEME_SKINS[raw].color;
	if (raw.includes(',')) {
		const [from, to] = raw.split(',');
		return `linear-gradient(135deg, ${from.trim()}, ${to.trim()})`;
	}
	return raw;
};

/** 主题皮肤的展示名，未登记时回退原始值。 */
export const themeLabelOf = (style) => {
	const raw = String(style || '').trim();
	if (isSkinId(raw)) return SKINS[raw].label;
	const normalized = normalizeThemeStyle(raw);
	if (normalized && THEME_SKINS[normalized]) return THEME_SKINS[normalized].label;
	return raw;
};

/**
 * 判断 style 是否为「无法识别的主题配置」。
 *
 * 用于装扮列表显式提示站长配置错误：填了非法主题名（如拼错成 gree）时，
 * applyThemeSkin 会静默回退默认配色，用户花了积分却看不到变化。
 * 与其无声失败，不如在列表上直接标出来。
 *
 * @param {string} style 装扮配置的 style 字段
 * @returns {boolean} true 表示该配置无效（穿戴后不会有预期效果）
 */
export const isUnregisteredThemeSkin = (style) => {
	const raw = String(style || '').trim();
	if (!raw) return true;
	// 皮肤包 id 是有效配置
	if (isSkinId(raw)) return false;
	// 能归一化成合法主题名（含旧渐变值映射成功）即为有效
	return normalizeThemeStyle(raw) === '';
};

export default {
	THEME_SKINS,
	normalizeThemeStyle,
	applyThemeSkin,
	restoreThemeSkin,
	clearThemeSkin,
	themeSwatchOf,
	themeLabelOf,
	isUnregisteredThemeSkin
};
