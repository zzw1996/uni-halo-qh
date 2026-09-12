/**
 * 时间解析工具：把后端下发的各种时间形状统一成可安全格式化的毫秒时间戳。
 *
 * 为什么需要它：项目里 `formatTime` 过滤器直接 `new Date(value)`，对以下几种值是**静默错渲染**的，
 * 实测结果（Node + 该项目 filters/index.js）：
 *
 *   null / 0        → 1970-01-01 08:00:00   ← 用户会看到「参与时间是 0 / 1970 年」
 *   秒级时间戳       → 1970-01-21 16:11:28   ← 被当成毫秒解释了，整体年份错到 1970
 *   数字字符串       → 空串（并打印错误日志）
 *
 * 插件返回的时间多为 ISO-8601 字符串，但同一字段在不同插件版本/序列化配置下可能是秒级数字，
 * 因此这里按「10 位以内=秒、超过=毫秒」判定，并对无法解析或等于 0 的值返回 null，
 * 由调用方展示占位符（如「—」），**绝不把 epoch 0 当成真实时间显示**。
 */

/** 10 位（含）以内按秒处理；13 位按毫秒。1e12 是秒/毫秒的分界线（约公元 33658 年） */
const SECONDS_THRESHOLD = 1e12;

/**
 * @returns {number|null} 毫秒时间戳；无法解析或为 0 时返回 null
 */
export function toTimestamp(value) {
	if (value === null || value === undefined || value === '') {
		return null;
	}
	if (typeof value === 'number') {
		if (!Number.isFinite(value) || value <= 0) return null;
		return value < SECONDS_THRESHOLD ? value * 1000 : value;
	}
	const text = String(value).trim();
	if (!text) return null;
	// 纯数字（可能带小数，如 Jackson 的秒级时间戳 1757488920.123）
	if (/^\d+(\.\d+)?$/.test(text)) {
		const num = Number(text);
		if (!Number.isFinite(num) || num <= 0) return null;
		return num < SECONDS_THRESHOLD ? num * 1000 : num;
	}
	const parsed = Date.parse(text);
	if (Number.isNaN(parsed) || parsed <= 0) return null;
	return parsed;
}

const pad = (n) => (n < 10 ? `0${n}` : `${n}`);

/**
 * 格式化时间；无法解析时返回占位符（默认「—」），不会渲染成 1970。
 *
 * @param {*} value        后端下发的时间（ISO 字符串 / 秒级或毫秒级时间戳）
 * @param {string} pattern 'date'（yyyy-MM-dd）或 'datetime'（yyyy-MM-dd HH:mm）
 * @param {string} fallback 解析失败时的占位符
 */
export function formatTimeSafe(value, pattern = 'datetime', fallback = '—') {
	const ts = toTimestamp(value);
	if (ts === null) {
		return fallback;
	}
	const d = new Date(ts);
	const date = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
	if (pattern === 'date') {
		return date;
	}
	return `${date} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export default {
	toTimestamp,
	formatTimeSafe
};
