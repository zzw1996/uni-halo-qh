/**
 * 文章分享海报渲染器（枫光改版 2026-10）
 *
 * 为什么不用 liu-poster：它用 Promise.all 并行画所有元素，图片下载快慢决定叠放顺序，
 * 「文字压在封面渐暗上」这类分层效果画不稳。这里先把所有图片预取成本地路径，
 * 再严格按顺序在同一个 context 上画完、一次 draw()，最后导出。
 *
 * 坐标系：逻辑尺寸 375 × 680（px），导出时按 pixelRatio（上限 3）放大。
 * 设计稿：Claude 画布「宥枫璟小程序 UI 改版」→ 分享海报页（夜墨封面 / 枫光纸笺 / 金句卡）。
 */

export const POSTER_W = 375;
export const POSTER_H = 680;

export const POSTER_TEMPLATES = [
	{ id: 'night', name: '夜墨封面' },
	{ id: 'paper', name: '枫光纸笺' },
	{ id: 'quote', name: '金句卡' }
];

const C = {
	night: '#1E1B2E',
	ink: '#1A1A1F',
	ink2: '#45424B',
	ink3: '#6A6672',
	paper: '#F6F3EE',
	line: '#ECE6DD',
	accent: '#C2410C',
	bright: '#E2502A',
	reward: '#FFC53D'
};

/* ---------------- 图片预取 ---------------- */

function loadImage(src) {
	return new Promise((resolve) => {
		if (!src) return resolve(null);
		uni.getImageInfo({
			src,
			success: (res) => resolve({ path: res.path, width: res.width, height: res.height }),
			fail: () => resolve(null)
		});
	});
}

/* ---------------- 绘制工具 ---------------- */

function roundRect(ctx, x, y, w, h, r) {
	const rr = Math.min(r, w / 2, h / 2);
	ctx.beginPath();
	ctx.moveTo(x + rr, y);
	ctx.arcTo(x + w, y, x + w, y + h, rr);
	ctx.arcTo(x + w, y + h, x, y + h, rr);
	ctx.arcTo(x, y + h, x, y, rr);
	ctx.arcTo(x, y, x + w, y, rr);
	ctx.closePath();
}

function fillRound(ctx, x, y, w, h, r, color) {
	ctx.setFillStyle(color);
	roundRect(ctx, x, y, w, h, r);
	ctx.fill();
}

function vGradient(ctx, x, y, w, h, from, to) {
	const g = ctx.createLinearGradient(x, y, x, y + h);
	g.addColorStop(0, from);
	g.addColorStop(1, to);
	ctx.setFillStyle(g);
	ctx.fillRect(x, y, w, h);
}

/** object-fit: cover，可选圆角裁切 */
function drawCover(ctx, img, x, y, w, h, r = 0) {
	if (!img) return false;
	const scale = Math.max(w / img.width, h / img.height);
	const sw = w / scale;
	const sh = h / scale;
	const sx = (img.width - sw) / 2;
	const sy = (img.height - sh) / 2;
	ctx.save();
	if (r > 0) {
		roundRect(ctx, x, y, w, h, r);
		ctx.clip();
	}
	ctx.drawImage(img.path, sx, sy, sw, sh, x, y, w, h);
	ctx.restore();
	return true;
}

function drawCircleImage(ctx, img, cx, cy, radius, fallbackColor) {
	ctx.save();
	ctx.beginPath();
	ctx.arc(cx, cy, radius, 0, Math.PI * 2);
	ctx.closePath();
	if (img) {
		ctx.clip();
		drawCover(ctx, img, cx - radius, cy - radius, radius * 2, radius * 2);
	} else {
		ctx.setFillStyle(fallbackColor || C.line);
		ctx.fill();
	}
	ctx.restore();
}

function setFont(ctx, size, weight) {
	ctx.font = `${weight === 'bold' ? 'bold ' : ''}${size}px sans-serif`;
	ctx.setFontSize(size);
}

/** 按宽度折行，超出行数在最后一行加省略号；返回实际行数 */
function drawText(ctx, text, x, y, opts) {
	const { maxWidth, lineHeight, size, color, weight, maxLines = 1, align = 'left' } = opts;
	const str = String(text || '').replace(/\s+/g, ' ').trim();
	if (!str) return 0;
	setFont(ctx, size, weight);
	ctx.setFillStyle(color);
	ctx.setTextBaseline('top');
	ctx.setTextAlign(align);
	const lines = [];
	let line = '';
	for (const ch of str) {
		const test = line + ch;
		if (ctx.measureText(test).width > maxWidth && line) {
			lines.push(line);
			line = ch;
			if (lines.length === maxLines) break;
		} else {
			line = test;
		}
	}
	if (lines.length < maxLines && line) lines.push(line);
	const truncated = lines.join('').length < str.length;
	if (truncated && lines.length) {
		let last = lines[lines.length - 1];
		while (last && ctx.measureText(last + '…').width > maxWidth) last = last.slice(0, -1);
		lines[lines.length - 1] = last + '…';
	}
	const offset = (lineHeight - size) / 2;
	lines.forEach((l, i) => ctx.fillText(l, x, y + i * lineHeight + offset));
	ctx.setTextAlign('left');
	return lines.length;
}

/** 枫叶标志（与小程序品牌一致的八角叶形） */
function drawLeaf(ctx, x, y, size, color, stemColor) {
	const s = size / 28;
	const pts = [[14, 3], [17, 9], [23, 7], [20, 13], [25, 16], [19, 17], [20, 23], [14, 19], [8, 23], [9, 17], [3, 16], [8, 13], [5, 7], [11, 9]];
	ctx.beginPath();
	pts.forEach(([px, py], i) => (i ? ctx.lineTo(x + px * s, y + py * s) : ctx.moveTo(x + px * s, y + py * s)));
	ctx.closePath();
	ctx.setFillStyle(color);
	ctx.fill();
	if (stemColor) {
		ctx.beginPath();
		ctx.moveTo(x + 14 * s, y + 13 * s);
		ctx.lineTo(x + 14 * s, y + 26 * s);
		ctx.setStrokeStyle(stemColor);
		ctx.setLineWidth(Math.max(1, 1.8 * s));
		ctx.setLineCap('round');
		ctx.stroke();
	}
}

function drawQr(ctx, img, x, y, size, bg, round) {
	if (round) {
		ctx.save();
		ctx.beginPath();
		ctx.arc(x + size / 2, y + size / 2, size / 2, 0, Math.PI * 2);
		ctx.setFillStyle(bg);
		ctx.fill();
		ctx.restore();
	} else {
		fillRound(ctx, x, y, size, size, 12, bg);
	}
	if (img) {
		const pad = size * 0.09;
		drawCover(ctx, img, x + pad, y + pad, size - pad * 2, size - pad * 2, round ? 0 : 8);
	}
}

/* ---------------- 三套模板 ---------------- */

function drawNight(ctx, d, img) {
	ctx.setFillStyle(C.night);
	ctx.fillRect(0, 0, POSTER_W, POSTER_H);
	if (!drawCover(ctx, img.cover, 0, 0, POSTER_W, 400)) {
		vGradient(ctx, 0, 0, POSTER_W, 400, '#3A2F4A', C.night);
	}
	vGradient(ctx, 0, 200, POSTER_W, 201, 'rgba(30,27,46,0)', C.night);
	vGradient(ctx, 0, 0, POSTER_W, 90, 'rgba(30,27,46,0.55)', 'rgba(30,27,46,0)');

	drawLeaf(ctx, 20, 22, 20, C.bright, C.reward);
	drawText(ctx, d.siteName, 46, 23, { maxWidth: 180, lineHeight: 18, size: 14, color: '#FFFFFF', weight: 'bold' });
	drawText(ctx, d.dateDots, POSTER_W - 20, 25, { maxWidth: 140, lineHeight: 14, size: 11, color: 'rgba(255,255,255,0.85)', weight: 'bold', align: 'right' });

	if (d.category) {
		setFont(ctx, 12, 'bold');
		const cw = ctx.measureText(d.category).width + 20;
		fillRound(ctx, 24, 372, cw, 24, 12, C.bright);
		drawText(ctx, d.category, 34, 372, { maxWidth: cw, lineHeight: 24, size: 12, color: '#FFFFFF', weight: 'bold' });
	}
	const titleLines = drawText(ctx, d.title, 24, 408, { maxWidth: 327, lineHeight: 36, size: 26, color: '#FFFFFF', weight: 'bold', maxLines: 3 });
	const barY = 408 + titleLines * 36 + 12;
	fillRound(ctx, 24, barY, 32, 4, 2, C.reward);
	drawText(ctx, d.excerpt, 24, barY + 16, { maxWidth: 327, lineHeight: 21, size: 13, color: 'rgba(255,255,255,0.72)', maxLines: Math.max(1, Math.floor((590 - barY - 16) / 21)) });

	fillRound(ctx, 16, 600, 343, 64, 18, 'rgba(255,255,255,0.08)');
	drawCircleImage(ctx, img.avatar, 48, 632, 20, '#3A3550');
	drawText(ctx, `${d.sharer} 推荐你读`, 78, 614, { maxWidth: 196, lineHeight: 20, size: 14, color: '#FFFFFF', weight: 'bold' });
	drawText(ctx, d.metaLine, 78, 636, { maxWidth: 196, lineHeight: 16, size: 11, color: 'rgba(255,255,255,0.6)' });
	drawQr(ctx, img.qr, 289, 604, 56, '#FFFFFF', false);
}

function drawPaper(ctx, d, img) {
	ctx.setFillStyle(C.paper);
	ctx.fillRect(0, 0, POSTER_W, POSTER_H);
	ctx.save();
	roundRect(ctx, 16, 16, 343, 648, 24);
	ctx.clip();
	ctx.setFillStyle('#FFFFFF');
	ctx.fillRect(16, 16, 343, 648);
	if (!drawCover(ctx, img.cover, 28, 28, 319, 240, 16)) {
		fillRound(ctx, 28, 28, 319, 240, 16, C.line);
	}
	fillRound(ctx, 40, 40, 52, 56, 12, C.night);
	drawText(ctx, d.day, 66, 44, { maxWidth: 52, lineHeight: 30, size: 22, color: '#FFFFFF', weight: 'bold', align: 'center' });
	drawText(ctx, d.monthEn, 66, 74, { maxWidth: 52, lineHeight: 14, size: 10, color: C.reward, weight: 'bold', align: 'center' });

	drawText(ctx, d.category ? `${d.category}` : d.siteName, 28, 288, { maxWidth: 300, lineHeight: 16, size: 12, color: C.accent, weight: 'bold' });
	const tl = drawText(ctx, d.title, 28, 308, { maxWidth: 319, lineHeight: 34, size: 24, color: C.ink, weight: 'bold', maxLines: 3 });
	const qy = 308 + tl * 34 + 6;
	drawText(ctx, '“', 24, qy - 8, { maxWidth: 40, lineHeight: 64, size: 64, color: C.bright, weight: 'bold' });
	drawText(ctx, d.excerpt, 56, qy + 12, { maxWidth: 291, lineHeight: 23, size: 14, color: C.ink2, maxLines: Math.max(1, Math.floor((526 - qy - 12) / 23)) });

	ctx.setStrokeStyle(C.line);
	ctx.setLineWidth(1.5);
	ctx.setLineDash([4, 4], 0);
	ctx.beginPath();
	ctx.moveTo(28, 536);
	ctx.lineTo(347, 536);
	ctx.stroke();
	ctx.setLineDash([], 0);

	drawCircleImage(ctx, img.avatar, 46, 578, 18, C.line);
	drawText(ctx, `${d.sharer} 分享给你`, 72, 561, { maxWidth: 190, lineHeight: 20, size: 14, color: C.ink, weight: 'bold' });
	drawText(ctx, '长按识别小程序码，读完整篇', 72, 582, { maxWidth: 190, lineHeight: 16, size: 11, color: C.ink3 });
	drawQr(ctx, img.qr, 275, 548, 72, C.paper, true);

	ctx.setFillStyle(C.accent);
	ctx.fillRect(16, 636, 343, 28);
	drawText(ctx, `${d.siteName} · ${d.slogan}`, POSTER_W / 2, 636, { maxWidth: 320, lineHeight: 28, size: 11, color: '#FFFFFF', align: 'center' });
	ctx.restore();
}

function drawQuote(ctx, d, img) {
	const g = ctx.createLinearGradient(0, 0, POSTER_W, POSTER_H);
	g.addColorStop(0, C.accent);
	g.addColorStop(1, C.bright);
	ctx.setFillStyle(g);
	ctx.fillRect(0, 0, POSTER_W, POSTER_H);
	ctx.setGlobalAlpha(0.14);
	drawLeaf(ctx, 165, -60, 300, '#FFFFFF');
	ctx.setGlobalAlpha(1);
	vGradient(ctx, 0, 500, POSTER_W, 180, 'rgba(30,27,46,0)', 'rgba(30,27,46,0.25)');

	drawLeaf(ctx, 24, 26, 20, '#FFFFFF', C.reward);
	drawText(ctx, `${d.siteName} · 金句`, 50, 27, { maxWidth: 220, lineHeight: 18, size: 14, color: '#FFFFFF', weight: 'bold' });
	drawText(ctx, '“', 18, 66, { maxWidth: 100, lineHeight: 130, size: 130, color: 'rgba(255,255,255,0.3)', weight: 'bold' });
	const ql = drawText(ctx, d.quote, 28, 172, { maxWidth: 319, lineHeight: 40, size: 25, color: '#FFFFFF', weight: 'bold', maxLines: 6 });
	const by = 172 + ql * 40 + 18;
	fillRound(ctx, 28, by, 32, 3, 2, C.reward);
	drawText(ctx, `摘自《${d.title}》`, 28, by + 16, { maxWidth: 319, lineHeight: 22, size: 14, color: 'rgba(255,255,255,0.88)', maxLines: 2 });

	fillRound(ctx, 16, 560, 343, 104, 20, '#FFFFFF');
	drawQr(ctx, img.qr, 32, 576, 72, C.paper, true);
	drawText(ctx, '长按识别，读完整篇', 118, 582, { maxWidth: 230, lineHeight: 22, size: 16, color: C.ink, weight: 'bold' });
	drawText(ctx, d.metaLine, 118, 607, { maxWidth: 230, lineHeight: 16, size: 12, color: C.ink3 });
	drawText(ctx, `${d.sharer} 推荐给你`, 118, 628, { maxWidth: 230, lineHeight: 16, size: 12, color: C.accent, weight: 'bold' });
}

const DRAWERS = { night: drawNight, paper: drawPaper, quote: drawQuote };

/* ---------------- 数据整理 ---------------- */

const MONTH_EN = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC'];

function formatCount(n) {
	const v = Number(n) || 0;
	return v >= 10000 ? (v / 10000).toFixed(1) + 'w' : v >= 1000 ? (v / 1000).toFixed(1) + 'k' : String(v);
}

/** 金句：优先取摘要里第一句完整的话（20–70 字），取不到就截摘要 */
function pickQuote(text) {
	const s = String(text || '').replace(/\s+/g, ' ').trim();
	if (!s) return '';
	// 不用后行断言：iOS 16.4 以下的 JSCore 不支持，会让整个模块解析失败
	let acc = '';
	for (const ch of s) {
		acc += ch;
		if (acc.length >= 20 && '。！？!?'.indexOf(ch) >= 0) break;
	}
	acc = acc.trim();
	return acc.length > 70 ? acc.slice(0, 68) + '…' : acc;
}

/**
 * @param {object} raw { title, excerpt, cover, category, publishTime, visit, upvote, siteName, slogan, sharer, avatar, qr }
 */
export function normalizePosterData(raw) {
	const d = new Date(raw.publishTime || Date.now());
	const ok = !isNaN(d.getTime());
	const pad = (n) => String(n).padStart(2, '0');
	const siteName = raw.siteName || '宥枫璟';
	const parts = [siteName];
	if (raw.visit) parts.push(`阅读 ${formatCount(raw.visit)}`);
	if (raw.upvote) parts.push(`赞 ${formatCount(raw.upvote)}`);
	const excerpt = String(raw.excerpt || '').trim() || '点开阅读全文，看看这篇写了什么。';
	return {
		title: raw.title || '未命名文章',
		excerpt,
		quote: pickQuote(excerpt) || raw.title || '',
		category: raw.category || '',
		siteName,
		slogan: raw.slogan || '记录爱与成长的每一程',
		sharer: raw.sharer || '朋友',
		metaLine: parts.join(' · '),
		dateDots: ok ? `${d.getFullYear()} · ${pad(d.getMonth() + 1)} · ${pad(d.getDate())}` : '',
		day: ok ? pad(d.getDate()) : '',
		monthEn: ok ? MONTH_EN[d.getMonth()] : '',
		cover: raw.cover || '',
		avatar: raw.avatar || '',
		qr: raw.qr || ''
	};
}

/* ---------------- 主入口 ---------------- */

/** 图片按 URL 缓存：切换模板时不重复下载 */
const imageCache = {};
async function loadCached(url) {
	if (!url) return null;
	if (!imageCache[url]) imageCache[url] = loadImage(url);
	return imageCache[url];
}

/**
 * 渲染海报并导出临时文件路径。
 * @param {string} canvasId 页面/组件里 <canvas canvas-id> 的值
 * @param {object} component canvas 所在组件实例（组件内必须传，否则取不到 canvas）
 * @param {string} templateId night | paper | quote
 * @param {object} data normalizePosterData 的结果
 */
export async function renderPoster(canvasId, component, templateId, data) {
	const [cover, avatar, qr] = await Promise.all([loadCached(data.cover), loadCached(data.avatar), loadCached(data.qr)]);
	const ctx = uni.createCanvasContext(canvasId, component);
	ctx.clearRect(0, 0, POSTER_W, POSTER_H);
	(DRAWERS[templateId] || drawNight)(ctx, data, { cover, avatar, qr });
	let pixelRatio = 2;
	try {
		const info = uni.getWindowInfo ? uni.getWindowInfo() : uni.getSystemInfoSync();
		pixelRatio = Math.min(3, Math.max(2, info.pixelRatio || 2));
	} catch (e) {}
	return new Promise((resolve, reject) => {
		ctx.draw(false, () => {
			// 部分安卓机 draw 回调早于真正上屏，留一点余量再导出
			setTimeout(() => {
				uni.canvasToTempFilePath({
					canvasId,
					x: 0,
					y: 0,
					width: POSTER_W,
					height: POSTER_H,
					destWidth: POSTER_W * pixelRatio,
					destHeight: POSTER_H * pixelRatio,
					fileType: 'jpg',
					quality: 0.95,
					success: (res) => resolve(res.tempFilePath),
					fail: reject
				}, component);
			}, 120);
		});
	});
}
