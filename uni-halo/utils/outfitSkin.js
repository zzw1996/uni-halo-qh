/**
 * 装扮-头像框/昵称框样式注册表与解析工具。
 *
 * 与 themeSkin.js（主题白名单）同模式：
 * - 前端只认注册表内的样式名，未登记的样式回退为无框并返回提示标记
 * - 站长在后台新增 avatar_frame 装扮时，style 必须在此登记，否则渲染无效果
 * - 本工具集中解析，identity.vue / about.vue 等页面统一引用，消除 gold/rainbow 硬编码
 */

/**
 * 头像框样式注册表。key = 后端 OutfitItemDTO.style 值。
 * class = 对应渲染类名（加在头像外层）。
 * 新增样式只需扩展此表 + 在对应页面补样式类。
 */
export const AVATAR_FRAME_STYLES = {
  gold: { className: 'frame-gold', label: '鎏金头像框' },
  rainbow: { className: 'frame-rainbow', label: '彩虹头像框' },
  silver: { className: 'frame-silver', label: '银辉头像框' }
};

/**
 * 昵称前缀样式注册表（nick_frame 类型，可选的符号键写法）。
 *
 * 配置契约（见 setting.yaml 的 outfits help）：nick_frame 的 style **就是前缀文本本身**
 * （如 "⭐"、"👑"）。此处登记的是符号键写法，两种都支持——注册表未命中时按文本透传，
 * 否则默认配置（style="⭐"）会查不到、昵称前缀永远不显示。
 */
export const NICK_FRAME_STYLES = {
  star: { prefix: '⭐', label: '星标昵称' },
  crown: { prefix: '👑', label: '皇冠昵称' },
  fire: { prefix: '🔥', label: '火热昵称' }
};

/**
 * 前缀文本的最大长度（按 JS 字符计，emoji 代理对按 2 计）。
 * 昵称前缀是短装饰（1-2 个 emoji），过长的配置会顶掉昵称本身的展示空间，判定为无效。
 */
export const NICK_FRAME_MAX_LENGTH = 6;

/** 根据后端下发的 avatarFrame 值返回渲染类名；未登记返回空串（无框）。 */
export function avatarFrameClass(frame) {
  if (!frame) return '';
  const entry = AVATAR_FRAME_STYLES[frame];
  return entry ? entry.className : '';
}

/** 头像框展示名：登记过的显示中文名，未登记的原样展示（供页面提示配置错误）。 */
export function avatarFrameLabel(frame) {
  if (!frame) return '';
  const raw = String(frame).trim();
  if (!raw) return '';
  const entry = AVATAR_FRAME_STYLES[raw];
  return entry ? entry.label : raw;
}

/**
 * 根据后端下发的 nickFrame 值返回昵称前缀文本。
 *
 * 解析顺序：注册表符号键 → 原样透传（配置契约：style 即前缀文本）。
 * 透传同样做长度防护，超长配置判定为无效并返回空串，避免破坏评论列表布局。
 */
export function nickFramePrefix(frame) {
  if (!frame) return '';
  const raw = String(frame).trim();
  if (!raw) return '';
  const entry = NICK_FRAME_STYLES[raw];
  if (entry) return entry.prefix;
  return raw.length <= NICK_FRAME_MAX_LENGTH ? raw : '';
}

/** 判断某头像框样式是否未登记（用于页面提示站长配置错误）。 */
export function isUnregisteredAvatarFrame(frame) {
  if (!frame) return false;
  return !AVATAR_FRAME_STYLES[frame];
}

/**
 * 判断昵称框配置是否无效（用于页面提示站长配置错误）。
 *
 * 与头像框不同，nick_frame 的 style 允许任意短文本；只有超长（渲染会被丢弃）才算无效。
 */
export function isUnregisteredNickFrame(frame) {
  if (!frame) return false;
  const raw = String(frame).trim();
  if (!raw) return false;
  return !NICK_FRAME_STYLES[raw] && raw.length > NICK_FRAME_MAX_LENGTH;
}
