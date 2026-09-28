/**
 * 应用设置跟账号走：本地优先 + 后台同步。
 *
 * <p>要解决的问题：底栏显示项、外观模式、首页布局这些设置此前只存在本机，
 * 而「丢设置」的路径并不冷门——换设备、清小程序缓存，以及
 * <b>每次版本更新时的清缓存</b>（utils/update.js）都会回到默认。</p>
 *
 * <p><b>为什么首屏不受影响</b>：首帧照旧同步读本地（store/setting.js 与
 * common/mixins 的 globalAppSettings 都不动），服务端副本在 App.vue onLaunch 里
 * <b>并行拉取、不 await</b>；到货后若有差异才应用，设置页保存时本地也立即生效，
 * 上传在后台做（去抖 + 失败保留待上传、下次启动补传）。</p>
 *
 * <p><b>冲突口径：按字段合并</b>。提交只带本次改动的叶子键，服务端只覆盖这些键，
 * 因此「一台设备改底栏、另一台改卡片样式」不会互相抹掉，也就不需要
 * 「谁的时间戳更新」这类判定。</p>
 *
 * <p><b>回声抑制</b>：应用服务端下发值时会 commit 一次 store，那会触发同一个 diff 逻辑；
 * 用 applyingRemote 标志把这一次的推送挡掉，否则会出现「拉下来 → 又推回去」的循环。</p>
 */
import v2Api from '@/api/v2/all.api.js';
import { getAppSettings, _AppSettingsKey } from '@/utils/app.js';
import { appearanceState, applyAppearanceMode, normalizeAppearanceMode } from '@/utils/skin.js';
import { getOpenid } from '@/utils/auth.js';

/** 本地同步元数据：服务端副本版本号 + 还没传上去的改动 */
const SYNC_KEY = 'unihalo_app_settings_sync';

/**
 * 外观模式的扁平键。
 *
 * 它在本地是独立存储键（unihalo_appearance_mode），并不在 APP_GLOBAL_SETTINGS 里，
 * 但服务端白名单里同样登记了它——「换设备后模式丢了」是最容易被察觉的一项。
 */
const APPEARANCE_LEAF = 'appearance.mode';

/**
 * 可同步的叶子键：与插件端 AppSettingsCatalog 一一对应。
 *
 * <p>这里多留一份清单不是为了校验服务端（服务端才是权威），而是为了两件事：
 * ① 知道该 diff 哪些叶子；② <b>服务端下发了本地不认识的值时不应用</b>——
 * 新旧版本交叉时（服务端先升级），把未来的枚举值写进当前界面会渲染不出来，
 * 宁可这一项这次不同步。</p>
 */
const BOOLEAN_LEAVES = [
	'articleCard.showCover',
	'articleDetail.showStats',
	'flotbutton.show',
	'banner.useDot',
	'tabbar.useCustom',
	'tabbar.show.category',
	'tabbar.show.moments',
	'tabbar.show.rank',
	'tabbar.show.gallery',
	'tabbar.show.store'
];

/** 枚举叶子的可选值：与 utils/skin.js 的 APPEARANCE_MODES、设置页的选项列表一致 */
const ENUM_LEAVES = {
	'banner.dotPosition': ['right', 'bottom'],
	'layout.home': ['h_row_col1', 'h_row_col2'],
	'layout.cardType': ['lr_image_text', 'lr_text_image', 'tb_image_text', 'tb_text_image', 'only_text'],
	[APPEARANCE_LEAF]: ['auto', 'light', 'dark']
};

const ALL_LEAVES = BOOLEAN_LEAVES.concat(Object.keys(ENUM_LEAVES));

/** 提交去抖：设置页里连点几个开关只发一次请求 */
const PUSH_DEBOUNCE_MS = 500;
/** 失败后的重试间隔；只重试一次，其余等下次启动补传 */
const PUSH_RETRY_MS = 5000;

/** 正在应用服务端下发的值（此时不产生推送） */
let applyingRemote = false;
/** 待推送的键（内存镜像，落盘在 SYNC_KEY 里） */
let pushTimer = null;
let retryTimer = null;
/** 正在进行的拉取 + 最近一次拉取时间：登录事件与启动拉取可能挨得很近，别拉两次 */
let pullInFlight = null;
let lastPullAt = 0;
/** 两次拉取的合并窗口：冷启动时「先拉一次（可能还没登录）→ 登录成功再拉一次」就落在这个窗口里 */
const PULL_DEDUPE_MS = 5000;

const readByPath = (source, path) => {
	let node = source;
	const parts = String(path).split('.');
	for (let i = 0; i < parts.length; i += 1) {
		if (node === null || node === undefined || typeof node !== 'object') {
			return undefined;
		}
		node = node[parts[i]];
	}
	return node;
};

/** 把叶子写进目标对象（不覆盖对象本身，只写叶子） */
const writeByPath = (target, path, value) => {
	const parts = String(path).split('.');
	let node = target;
	for (let i = 0; i < parts.length - 1; i += 1) {
		const part = parts[i];
		if (node[part] === null || typeof node[part] !== 'object') {
			node[part] = {};
		}
		node = node[part];
	}
	node[parts[parts.length - 1]] = value;
};

/** 这个键的这个值，本机认识吗 */
const accepts = (key, value) => {
	if (BOOLEAN_LEAVES.indexOf(key) >= 0) {
		return typeof value === 'boolean';
	}
	const allowed = ENUM_LEAVES[key];
	if (!allowed) {
		return false;
	}
	return typeof value === 'string' && allowed.indexOf(value) >= 0;
};

const readMeta = () => {
	try {
		const raw = uni.getStorageSync(SYNC_KEY);
		if (!raw) {
			return { revision: 0, pending: {} };
		}
		const parsed = typeof raw === 'string' ? JSON.parse(raw) : raw;
		if (!parsed || typeof parsed !== 'object') {
			return { revision: 0, pending: {} };
		}
		return {
			revision: Number(parsed.revision) || 0,
			pending: parsed.pending && typeof parsed.pending === 'object' ? parsed.pending : {}
		};
	} catch (err) {
		return { revision: 0, pending: {} };
	}
};

const writeMeta = (meta) => {
	try {
		uni.setStorageSync(SYNC_KEY, JSON.stringify({
			revision: Number(meta.revision) || 0,
			pending: meta.pending || {}
		}));
	} catch (err) {
		// 存储写不进去只影响「下次启动补传」，不影响本次已经生效的设置
	}
};

/** 记下改动（待上传），并安排一次去抖提交 */
const queuePush = (changes) => {
	const keys = Object.keys(changes || {});
	if (!keys.length) {
		return;
	}
	const meta = readMeta();
	keys.forEach((key) => {
		meta.pending[key] = changes[key];
	});
	writeMeta(meta);
	schedulePush();
};

const schedulePush = (delay) => {
	if (pushTimer || retryTimer) {
		return;
	}
	pushTimer = setTimeout(() => {
		pushTimer = null;
		flushPending();
	}, typeof delay === 'number' ? delay : PUSH_DEBOUNCE_MS);
};

/**
 * 把待上传的改动发出去。
 *
 * <p>三个分支的取舍：</p>
 * <ul>
 *   <li>网络/5xx 失败 → <b>保留待上传</b>并重试一次，下次启动还会补传（本地已经生效，不影响使用）；</li>
 *   <li>400 → <b>丢掉这些键</b>并告警：这类错误重发多少次都一样，而「卡住的待上传键」
 *       会让 pull 永远跳过它们（本地优先），那项设置就再也同步不动了；</li>
 *   <li>成功 → 清掉本次发出去的键（若期间又被改过则保留新值），并记下服务端版本号。</li>
 * </ul>
 */
const flushPending = async () => {
	if (!getOpenid()) {
		// 未登录（游客）没有账号可挂：留着待上传，等登录后（通常同一会话内就会登录）再传
		return;
	}
	const meta = readMeta();
	const payload = Object.assign({}, meta.pending);
	const keys = Object.keys(payload);
	if (!keys.length) {
		return;
	}
	try {
		const res = await v2Api.saveAppSettings(payload);
		if (res && res.statusCode === 400) {
			const message = (res.data && res.data.error) || '服务端不接受这些设置项';
			console.warn('[appSettingsSync] 设置被服务端拒绝，已放弃本次上传：', message, payload);
			clearPending(payload);
			return;
		}
		const view = (res && res.data) || {};
		const rejected = view.rejected || [];
		if (rejected.length) {
			// 客户端与服务端白名单不一致的唯一线索：不报错，但要在控制台看得见
			console.warn('[appSettingsSync] 这些设置项不在服务端白名单里，未同步：', rejected);
		}
		clearPending(payload);
		const latest = readMeta();
		latest.revision = Number(view.revision) || latest.revision;
		writeMeta(latest);
	} catch (err) {
		console.warn('[appSettingsSync] 设置上传失败，稍后重试：', err && (err.error || err.errMsg || err));
		if (!retryTimer) {
			retryTimer = setTimeout(() => {
				retryTimer = null;
				flushPending();
			}, PUSH_RETRY_MS);
		}
	}
};

/**
 * 清账：只清掉「值没再变过」的键。
 *
 * <p>上传期间用户又改了同一项（值不同）时必须留着——否则新改动会被当成
 * 「已经传过了」而永远同步不上去。</p>
 */
const clearPending = (sent) => {
	const latest = readMeta();
	Object.keys(sent || {}).forEach((key) => {
		if (Object.prototype.hasOwnProperty.call(latest.pending, key)
			&& String(latest.pending[key]) === String(sent[key])) {
			delete latest.pending[key];
		}
	});
	writeMeta(latest);
};

/**
 * store 的 setSettings 提交后调用：算出改动的叶子并排队上传。
 *
 * <p>应用服务端下发值时（applyingRemote）直接返回，避免「拉下来又推回去」。</p>
 */
export const queueAppSettingsDiff = (previous, next) => {
	if (applyingRemote || !next) {
		return;
	}
	const changes = {};
	ALL_LEAVES.forEach((key) => {
		const after = readByPath(next, key);
		if (after === undefined) {
			return;
		}
		const before = readByPath(previous || {}, key);
		if (before !== after) {
			changes[key] = after;
		}
	});
	if (Object.keys(changes).length) {
		queuePush(changes);
	}
};

/**
 * 外观模式改动后调用（设置页选择后）。
 *
 * <p>为什么不写在 utils/skin.js 的 applyAppearanceMode 里：本模块要 import
 * applyAppearanceMode 来应用服务端的值，写进 skin.js 就成了循环依赖。</p>
 */
export const pushAppearanceMode = (mode) => {
	queuePush({ [APPEARANCE_LEAF]: normalizeAppearanceMode(mode) });
};

/**
 * 启动时拉取服务端副本并应用（并行调用，不阻塞首屏）。
 *
 * <p>三种情况都不动本地：没有 openid（游客）、请求失败、云端还没有副本
 * （synced=false）。只有「服务端有值、本机认识、且本机没有该键的待上传改动」
 * 才覆盖——本地刚改的改动永远优先于云端旧值。</p>
 *
 * <p><b>为什么登录后还要再拉一次</b>：冷启动时先跑的是这里的第一次拉取，
 * 而静默登录（pages/index 的 tryAutoWxLogin）往往还没完成——那时没有 openid，
 * 拉取会直接跳过。所以「我的」页登录成功（wx_login_status_changed）时也要拉一次，
 * 否则新设备首次进入永远等不到设置回来。同一会话内换账号也会走到那里。</p>
 *
 * @returns {Promise<boolean>} 是否应用了至少一项
 */
export const pullAppSettings = async () => {
	if (pullInFlight) {
		return pullInFlight;
	}
	if (Date.now() - lastPullAt < PULL_DEDUPE_MS) {
		// 刚拉过：启动拉取与登录事件的间隔通常只有几百毫秒，不必再拉一次
		return false;
	}
	pullInFlight = doPullAppSettings().finally(() => {
		pullInFlight = null;
		lastPullAt = Date.now();
	});
	return pullInFlight;
};

const doPullAppSettings = async () => {
	// 先把上次没传成功的改动补传（与下面的拉取并行，互不阻塞）
	flushPending();
	if (!getOpenid()) {
		return false;
	}
	let res;
	try {
		res = await v2Api.getAppSettings();
	} catch (err) {
		// 拿不到副本时按「本地优先」继续用本地设置，不打扰用户
		console.warn('[appSettingsSync] 读取设置副本失败，继续用本地设置：',
			err && (err.error || err.errMsg || err));
		return false;
	}
	const view = (res && res.data) || {};
	if (!view.synced) {
		return false;
	}
	const remote = view.settings || {};
	const meta = readMeta();
	const local = getAppSettings() || {};
	const changes = {};
	let appearanceTarget = null;

	Object.keys(remote).forEach((key) => {
		const value = remote[key];
		if (Object.prototype.hasOwnProperty.call(meta.pending, key)) {
			// 本机改过还没传上去：以本机为准（本地优先）
			return;
		}
		if (!accepts(key, value)) {
			if (ALL_LEAVES.indexOf(key) < 0) {
				console.warn('[appSettingsSync] 服务端下发了本地不认识的设置项，已忽略：', key);
			} else {
				console.warn('[appSettingsSync] 服务端下发了本机不认识的值，已忽略：', key, value);
			}
			return;
		}
		if (key === APPEARANCE_LEAF) {
			const current = normalizeAppearanceMode(appearanceState.mode);
			if (current !== value) {
				appearanceTarget = value;
			}
			return;
		}
		if (readByPath(local, key) === value) {
			return;
		}
		changes[key] = value;
	});

	const hasBundleChanges = Object.keys(changes).length > 0;
	if (!hasBundleChanges && appearanceTarget === null) {
		return false;
	}

	applyingRemote = true;
	try {
		if (hasBundleChanges) {
			// 深拷贝本地设置再写叶子：其他键（含本站自有的默认值）原样保留
			const merged = JSON.parse(JSON.stringify(local));
			Object.keys(changes).forEach((key) => {
				writeByPath(merged, key, changes[key]);
			});
			const commit = uni.$tm && uni.$tm.vx && uni.$tm.vx.commit;
			if (typeof commit === 'function') {
				// 走 store：底栏、首页卡片等所有读 globalAppSettings 的地方一起跟着变
				uni.$tm.vx.commit('setting/setSettings', merged);
			} else {
				uni.setStorageSync(_AppSettingsKey, JSON.stringify(merged));
			}
		}
		if (appearanceTarget !== null) {
			applyAppearanceMode(appearanceTarget);
		}
	} catch (err) {
		console.warn('[appSettingsSync] 应用服务端设置失败，继续用本地设置：', err);
		return false;
	} finally {
		applyingRemote = false;
	}
	return true;
};

/** 诊断用：当前同步状态（排查「这台设备为什么没同步上」时看它） */
export const appSettingsSyncDebug = () => {
	const meta = readMeta();
	return {
		revision: meta.revision,
		pendingKeys: Object.keys(meta.pending)
	};
};
