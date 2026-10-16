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
 *
 * 与「外观模式」的分工：皮肤是用户买到的**配色**（浅色系三套 + 墨韵深色），
 * 外观模式是**明暗偏好**（跟随系统 / 始终浅色 / 始终深色）。两者合成「生效皮肤」：
 * 强制深色时用墨韵那套深色配色，其余情况尊重用户买的皮肤。
 * 这样站长不必为暗色模式再维护第二套配色，用户买的皮肤也不会因为切到深色而"失效"——
 * 切回浅色就回来（皮肤偏好本身没被改写）。
 */
import Vue from 'vue';
import { THEMES } from '@/common/themes/index.js';

/** 皮肤注册表。accent 供原生栏位（导航栏 / tabBar）取色用，与生成的 CSS 保持一致。 */
export const SKINS = {
	/** 默认：完全不覆盖，保持设计系统的原色 */
	paper: {
		label: '宣纸白',
		desc: '默认配色，暖白纸面 + 白卡片 + 深青绿强调',
		dark: false,
		// 页面/窗口底色：page 元素不设静态底之后，下拉回弹与切页留白都靠它
		bgColor: '#f6f7f2',
		tabSelectedColor: '#176b5b',
		tmColor: '',
		swatch: '#176b5b'
	},
	mint: {
		label: '青瓷',
		desc: '冷调页面底 + 青绿强调色，分割线更淡',
		dark: false,
		bgColor: '#eef3f1',
		tabSelectedColor: '#0e8a72',
		tmColor: 'teal',
		swatch: 'linear-gradient(135deg, #0e8a72 0%, #2bb39a 100%)'
	},
	sunrise: {
		label: '晨光',
		desc: '暖调页面底 + 琥珀强调色，卡片更柔和',
		dark: false,
		bgColor: '#f8f1e7',
		tabSelectedColor: '#c25a06',
		tmColor: 'amber',
		swatch: 'linear-gradient(135deg, #c25a06 0%, #e08a1e 100%)'
	},
	ink: {
		label: '墨韵深色',
		desc: '深墨底 + 亮字 + 亮靛青强调，夜晚护眼',
		dark: true,
		bgColor: '#14141a',
		navBg: '#14141a',
		navFront: '#ffffff',
		tabBg: '#14141a',
		tabColor: '#8b8b98',
		tabSelectedColor: '#7d95ff',
		tmColor: 'indigo',
		swatch: 'linear-gradient(135deg, #1e1e26 0%, #7d95ff 100%)'
	}
};

// 四套 UI 主题沿用既有皮肤 id，保留服务端装扮兼容。
THEMES.forEach(t => {
 SKINS[t.id] = {label:t.name,desc:t.description,dark:t.dark,bgColor:t.bg,navBg:t.bg,navFront:t.dark?'#ffffff':'#000000',tabBg:t.card,tabColor:t.muted,tabSelectedColor:t.accentText,tmColor:t.dark?'cyan':'',swatch:t.hero};
});
const UI_THEME_KEY = 'unihalo_ui_theme';
export const uiThemeState = Vue.observable({id:''});
export const chooseUiTheme = (id) => {
 const t=THEMES.find(t=>t.id===id) || THEMES[0];
 uiThemeState.id=t.id;
 try { uni.setStorageSync(UI_THEME_KEY,t.id); } catch(e) {}
 applyAppearanceMode(t.dark?'dark':'light');
 return t.id;
};
export const restoreUiTheme = () => {
 let id=''; try { id=uni.getStorageSync(UI_THEME_KEY); } catch(e) {}
 uiThemeState.id=THEMES.some(t=>t.id===id)?id:'';
};

/** 深色配色（外观模式为深色时使用）：复用已登记的墨韵皮肤，不另维护一套暗色变量 */
export const DARK_SKIN_ID = 'ink';

/** 外观模式：明暗偏好。改这里就够了，设置页与解析逻辑都从它读。 */
export const APPEARANCE_MODES = [{
	id: 'auto',
	label: '跟随系统',
	desc: '系统切到深色时自动用深色外观'
}, {
	id: 'light',
	label: '始终浅色'
}, {
	id: 'dark',
	label: '始终深色',
	desc: '不跟随系统，界面始终用深色外观'
}];

const APPEARANCE_KEY = 'unihalo_appearance_mode';

/**
 * 响应式外观状态。
 *
 * systemDark 也放进响应式对象：跟随系统时它一变，页面的根类名要跟着重算，
 * 否则系统切了主题而页面还是旧配色（切回前台才变）。
 */
export const appearanceState = Vue.observable({
	mode: 'auto',
	systemDark: false
});

export const normalizeAppearanceMode = (mode) => {
	const value = String(mode || '').trim();
	return APPEARANCE_MODES.some((item) => item.id === value) ? value : 'auto';
};

export const appearanceLabelOf = (mode) =>
	(APPEARANCE_MODES.find((item) => item.id === normalizeAppearanceMode(mode)) || {}).label || '跟随系统';

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

/**
 * 解析「生效皮肤」：把「用户买的皮肤」与「明暗偏好」合成一个皮肤 id。
 *
 * 规则刻意简单（三条、无例外）：
 * 1. 强制深色 → 深色配色；
 * 2. 强制浅色 → 用户买的皮肤（若那套本身是深色，退回默认浅色皮肤，否则"浅色"点了没反应）；
 * 3. 跟随系统 → 系统深色时同 1，否则同 2。
 *
 * 纯函数：不读存储、不看全局状态，因此可以在页面里就地推算，也便于以后加单测。
 */
export const resolveSkinId = (skinId, mode, systemDark) => {
	const raw = isSkinId(skinId) ? String(skinId).trim() : 'sky';
	const base = raw === 'paper' ? 'sky' : raw;
	const resolved = normalizeAppearanceMode(mode);
	if (resolved === 'dark') {
		return DARK_SKIN_ID;
	}
	const lightBase = base === DARK_SKIN_ID ? 'sky' : base;
	if (resolved === 'light') {
		return lightBase;
	}
	return systemDark ? DARK_SKIN_ID : lightBase;
};

/** 当前生效皮肤 id（页面根类名、原生栏同步都用它） */
export const effectiveSkinId = () =>
	resolveSkinId(uiThemeState.id || skinState.id, appearanceState.mode, appearanceState.systemDark);

/**
 * 设置外观模式并立即生效。
 *
 * 只改「原生栏 + 根类名」所依赖的状态，不写框架主题色：
 * 那个键属于用户买的主题装扮，外观切换不该改写它（切回浅色时要原样回来）。
 */
export const applyAppearanceMode = (mode) => {
	const target = normalizeAppearanceMode(mode);
	appearanceState.mode = target;
	try {
		uni.setStorageSync(APPEARANCE_KEY, target);
	} catch (err) {
		// 存储不可用不影响本次渲染
	}
	syncNativeChrome();
	return target;
};

/**
 * 启动时恢复外观状态：读本地偏好 + 读系统主题。
 *
 * 必须在首个页面渲染前调用（App.vue onLaunch）：这里读的是同步存储与同步系统信息，
 * 所以首帧就拿得到正确配色，不会先浅后深地闪一下。
 */
export const restoreAppearance = () => {
	let stored = '';
	try {
		stored = uni.getStorageSync(APPEARANCE_KEY) || 'light';
	} catch (err) {
		stored = '';
	}
	appearanceState.mode = normalizeAppearanceMode(stored);
	appearanceState.systemDark = detectSystemDark();
	// 冷启动就同步一次框架深色开关（此时页面还没渲染，不会闪）
	syncTmDark(SKINS[effectiveSkinId()] || SKINS.paper);
	return appearanceState.mode;
};

/**
 * 系统是否深色。
 *
 * <p>三级兜底，因为拿不到它会直接表现为「跟随系统时界面还是浅色」：
 * ① {@code uni.getAppBaseInfo()}（新版 API，字段就是 theme）；
 * ② {@code uni.getSystemInfoSync()}（旧版；官方已标记废弃但仍在用）；
 * ③ H5 的 media query。</p>
 *
 * <p>注意 theme 字段只在**声明了 darkmode 的小程序**里才返回：本项目在
 * manifest.json 的 mp-weixin 下开了 darkmode ✓ 否则这里会一直拿到空值。</p>
 */
export const detectSystemDark = () => {
	try {
		if (typeof uni.getAppBaseInfo === 'function') {
			const base = uni.getAppBaseInfo();
			if (base && base.theme) {
				return String(base.theme).toLowerCase() === 'dark';
			}
		}
	} catch (err) {
		// 落到下一级
	}
	try {
		const info = uni.getSystemInfoSync();
		if (info && info.theme) {
			return String(info.theme).toLowerCase() === 'dark';
		}
	} catch (err) {
		// 落到下一级
	}
	try {
		if (typeof window !== 'undefined' && window.matchMedia) {
			return window.matchMedia('(prefers-color-scheme: dark)').matches === true;
		}
	} catch (err) {
		// 读不到就当浅色
	}
	return false;
};

/**
 * 重新检测系统主题；跟随系统且结果变了才同步（切回前台时调用）。
 *
 * <p>用户去系统设置里切主题、再切回小程序是常见路径：onThemeChange 在部分机型/版本上
 * 不会补发，所以切回前台时再对一次。</p>
 */
export const refreshSystemTheme = () => {
	const dark = detectSystemDark();
	if (dark === appearanceState.systemDark) {
		return false;
	}
	appearanceState.systemDark = dark;
	if (appearanceState.mode === 'auto') {
		syncNativeChrome();
	}
	return true;
};

/**
 * 监听系统主题变化（跟随系统时才有意义）。
 *
 * 冷启动之后用户切了系统深色，页面应当立刻跟着变；
 * 只认一次监听（重复调用会叠出多个回调）。
 */
let systemThemeWatching = false;
export const watchSystemTheme = () => {
	if (systemThemeWatching || typeof uni.onThemeChange !== 'function') {
		return;
	}
	systemThemeWatching = true;
	try {
		uni.onThemeChange((res) => {
			const dark = String(res && res.theme || '').toLowerCase() === 'dark';
			const changed = appearanceState.systemDark !== dark;
			appearanceState.systemDark = dark;
			if (changed) {
				syncNativeChrome();
			}
		});
	} catch (err) {
		systemThemeWatching = false;
	}
};

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
	// 同步原生栏要按「生效皮肤」算：外观模式是强制深色时，即使刚恢复的是浅色皮肤，
	// 导航栏/窗口底色也必须立刻是深色，否则会先浅后深闪一下
	syncNativeChrome();
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
let nativeIconDark = null;
export const applyNativeChrome = (id) => {
	const skin = SKINS[id] || SKINS.paper;
	if (nativeIconDark !== id && typeof uni.setTabBarItem === 'function') {
		['home', 'category', 'gallery', 'rank', 'mine'].forEach((name, index) => {
            const names={home:'house',category:'compass',gallery:'images',rank:'chart-no-axes-combined',mine:'user-round'};
            if (['sky','mint','ink','sunrise'].includes(id)) {
                uni.setTabBarItem({index,iconPath:`static/ui-icons/${names[name]}--${id}--muted.png`,selectedIconPath:`static/ui-icons/${names[name]}--${id}--active.png`});
            } else {
                uni.setTabBarItem({index,iconPath:`static/tabbar/paper_${name}.png`,selectedIconPath:`static/tabbar/paper_${name}_active.png`});
            }
		});
		nativeIconDark = id;
	}
	try {
		if (skin.dark) {
			uni.setNavigationBarColor({
				backgroundColor: skin.navBg,
				frontColor: '#ffffff'
			});
			uni.setBackgroundColor({
				backgroundColor: skin.bgColor || skin.navBg,
				backgroundColorTop: skin.bgColor || skin.navBg,
				backgroundColorBottom: skin.bgColor || skin.navBg
			});
			uni.setTabBarStyle({
				backgroundColor: skin.tabBg,
				color: skin.tabColor,
				selectedColor: skin.tabSelectedColor,
				borderStyle: 'black'
			});
		} else {
			uni.setNavigationBarColor({
				backgroundColor: skin.bgColor || '#f6f7f2',
				frontColor: '#000000'
			});
			// 浅色皮肤各自的页面底色（青瓷/晨光与宣纸白不同）：窗口留白不再固定用宣纸白，
			// 否则换皮肤后下拉回弹会露出一条不搭的底色
			uni.setBackgroundColor({
				backgroundColor: skin.bgColor || '#f6f7f2',
				backgroundColorTop: skin.bgColor || '#f6f7f2',
				backgroundColorBottom: skin.bgColor || '#f6f7f2'
			});
			// 浅色皮肤下 tabBar 恢复默认（选中色仍由主题色接管，见 themeSkin.js）
			uni.setTabBarStyle({
				backgroundColor: skin.tabBg || '#ffffff',
				color: skin.tabColor || '#65736D',
				selectedColor: skin.tabSelectedColor || '#176b5b',
				borderStyle: 'white'
			});
		}
	} catch (err) {
		// 非 tabBar 页面调用 tabBar API 会失败，属正常情况，忽略
	}
};

/**
 * 把「当前是否深色」同步给 tm-vuetify。
 *
 * <p>框架有一整套深色渲染：`tmVuetify.black` 一开，tm-sheet / tm-menubars / tm-actionSheet
 * 等组件会自己换成 grey-darken-5 之类的深色，**连写了 color="white" 的地方也会被压过去**
 * （黑色模式的类在样式表里排在后面）。不打开它，即使皮肤类挂对了，
 * 这些组件仍是白底——首页顶部的白色条就是这么来的。</p>
 *
 * <p>顺带写一份到本地存储：store 初始化会读它，冷启动先于 JS 就是正确配色。</p>
 */
export const syncTmDark = (skin) => {
	const dark = !!(skin && skin.dark);
	try {
		const vx = uni.$tm && uni.$tm.vx;
		// 全局混入在每个页面 onLoad/onShow 都会同步一次原生栏，也就顺带走到这里；
		// 框架状态已经是目标值时直接返回，省下 commit + setStorageSync（后者是阻塞的磁盘写）。
		const state = vx && vx.state ? vx.state() : null;
		const current = state && state.tmVuetify ? state.tmVuetify.black : null;
		if (current === dark) return dark;
		if (vx && vx.commit) {
			vx.commit('setTmVuetifyBlack', dark);
		}
		uni.setStorageSync('setTmVuetifyBlack', dark);
	} catch (err) {
		// 框架不可用不影响本次渲染
	}
	return dark;
};

/**
 * 按「生效皮肤」同步外观相关的所有外部状态：tm 组件的深色开关 + 原生栏位。
 *
 * 默认皮肤也同步纸面导航与窗口底色，从深色切回浅色时恢复整套栏位。
 */
export const syncNativeChrome = () => {
	const effective = effectiveSkinId();
	const skin = SKINS[effective] || SKINS.paper;
	// 先设框架深色开关：它内部也会动 tabBar 样式，随后 applyNativeChrome 再按皮肤覆盖一次
	const wasDark = !!uni?.$tm?.vx?.state?.()?.tmVuetify?.black;
	syncTmDark(skin);
	applyNativeChrome(effective);
	return effective;
};

export default {
	SKINS,
	skinState,
	appearanceState,
	APPEARANCE_MODES,
	DARK_SKIN_ID,
	isSkinId,
	normalizeAppearanceMode,
	appearanceLabelOf,
	resolveSkinId,
	effectiveSkinId,
	refreshSystemTheme,
	syncTmDark,
	applyAppearanceMode,
	restoreAppearance,
	detectSystemDark,
	watchSystemTheme,
	syncNativeChrome,
	skinClassOf,
	applySkin,
	restoreSkin,
	applyNativeChrome
};
