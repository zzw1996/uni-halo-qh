/**
 * 皮肤包（Skin）——比「主题色」更进一步的整站外观。
 *
 * 背景：装扮商店里 type=theme 的装扮过去只支持填一个框架主题名（如 light-blue），
 * 用户花积分买到的只是「按钮变个色」，页面底色、卡片、分割线、渐变全都没变，
 * 观感上像买了个开关。这里把 theme 装扮升级为「皮肤包」：
 * 一个皮肤同时改页面底、卡片面、文字层级、分割线、强调色与渐变。
 *
 * 配置约定（后端「装扮商店」→ style 字段）：
 * - 填皮肤 id：paper / mint / sunrise / ink；
 * - 仍兼容旧的框架主题名（light-blue / primary / orange …），那时只换强调色；
 * - 服务端下发值是权威来源，本地 storage 只用于「先渲染、后纠正」。
 *
 * 皮肤样式表由 tools/gen-skins.cjs 从编译产物生成（全站逐条覆盖，
 * 见 common/styles/skins.generated.scss 的说明），本文件只负责「状态 + 应用」。
 */
import Vue from 'vue';

/** 皮肤注册表。accent 供原生栏位（导航栏 / tabBar）取色用，与生成的 CSS 保持一致。 */
export const SKINS = {
	/** 默认：完全不覆盖，保持设计系统的原色 */
	paper: {
		label: '宣纸白',
		desc: '默认配色，暖纸底 + 白卡片 + 靛青强调',
		dark: false,
		tmColor: '',
		swatch: '#2f54eb'
	},
	mint: {
		label: '青瓷',
		desc: '冷调页面底 + 青绿强调色，分割线更淡',
		dark: false,
		tmColor: 'teal',
		swatch: 'linear-gradient(135deg, #0e8a72 0%, #2bb39a 100%)'
	},
	sunrise: {
		label: '晨光',
		desc: '暖调页面底 + 琥珀强调色，卡片更柔和',
		dark: false,
		tmColor: 'amber',
		swatch: 'linear-gradient(135deg, #c25a06 0%, #e08a1e 100%)'
	},
	ink: {
		label: '墨韵深色',
		desc: '深墨底 + 亮字 + 亮靛青强调，夜晚护眼',
		dark: true,
		navBg: '#14141a',
		navFront: '#ffffff',
		tabBg: '#14141a',
		tabColor: '#8b8b98',
		tabSelectedColor: '#7d95ff',
		tmColor: 'indigo',
		swatch: 'linear-gradient(135deg, #1e1e26 0%, #7d95ff 100%)'
	}
};

/** 皮肤 id 白名单，用于校验服务端下发值（填错时不静默改外观）。 */
export const isSkinId = (id) => Object.prototype.hasOwnProperty.call(SKINS, String(id || '').trim());

const STORAGE_KEY = 'unihalo_skin';

/**
 * 响应式皮肤状态。
 *
 * 必须响应式：穿戴皮肤后要立刻整站重绘，而 uni storage 不是 Vue 的响应式依赖
 * （项目里踩过这个坑：写成 computed 读 storage 会永久缓存首次结果）。
 */
export const skinState = Vue.observable({ id: 'paper' });

/** 页面根节点要挂的类名；默认皮肤返回空串，不产生任何额外样式。 */
export const skinClassOf = (id) => {
	const skin = String(id || '').trim();
	return skin && skin !== 'paper' ? `skin-${skin}` : '';
};

/**
 * 应用皮肤。
 *
 * @param {string} id 皮肤 id；非法值按默认皮肤处理（不猜测、不半应用）
 * @returns {string} 实际生效的皮肤 id
 */
export const applySkin = (id) => {
	const target = isSkinId(id) ? String(id).trim() : 'paper';
	skinState.id = target;
	try {
		uni.setStorageSync(STORAGE_KEY, target);
		// tm-vuetify 组件的主题色跟皮肤走（组件带 theme 属性时不跟随，属已知边界）
		const tmColor = SKINS[target].tmColor || '';
		if (uni.$tm && uni.$tm.vx) {
			uni.$tm.vx.commit('setTmVuetifyColor', tmColor);
		}
		uni.setStorageSync('setTmVuetifyColor', tmColor);
	} catch (err) {
		// 存储/框架不可用不影响本次渲染
	}
	applyNativeChrome(target);
	return target;
};

/** 从本地缓存恢复（App 启动时调用，避免首屏闪回默认外观）。 */
export const restoreSkin = () => {
	let stored = '';
	try {
		stored = uni.getStorageSync(STORAGE_KEY) || '';
	} catch (err) {
		stored = '';
	}
	return applySkin(stored);
};

/**
 * 同步原生栏位：导航栏与 tabBar 不在 CSS 可控范围内，只能走 API。
 *
 * 导航栏颜色是「按页面」生效的，设置后只影响当前页——所以全局混入会在
 * 每个页面 onShow 时重新调用一次（见 common/mixins/index.js）。
 */
export const applyNativeChrome = (id) => {
	const skin = SKINS[id] || SKINS.paper;
	try {
		if (skin.dark) {
			uni.setNavigationBarColor({
				backgroundColor: skin.navBg,
				frontColor: '#ffffff'
			});
			uni.setBackgroundColor({
				backgroundColor: skin.navBg,
				backgroundColorTop: skin.navBg,
				backgroundColorBottom: skin.navBg
			});
			uni.setTabBarStyle({
				backgroundColor: skin.tabBg,
				color: skin.tabColor,
				selectedColor: skin.tabSelectedColor,
				borderStyle: 'black'
			});
		} else {
			uni.setNavigationBarColor({
				backgroundColor: '#ffffff',
				frontColor: '#000000'
			});
			uni.setBackgroundColor({
				backgroundColor: '#f2f2ec',
				backgroundColorTop: '#f2f2ec',
				backgroundColorBottom: '#f2f2ec'
			});
			// 浅色皮肤下 tabBar 恢复默认（选中色仍由主题色接管，见 themeSkin.js）
			uni.setTabBarStyle({
				backgroundColor: '#ffffff',
				color: '#9A9A94',
				borderStyle: 'white'
			});
		}
	} catch (err) {
		// 非 tabBar 页面调用 tabBar API 会失败，属正常情况，忽略
	}
};

export default {
	SKINS,
	skinState,
	isSkinId,
	skinClassOf,
	applySkin,
	restoreSkin,
	applyNativeChrome
};
