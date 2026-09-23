/**
 * 皮肤样式生成器（一次性工具，改版后需要重跑）
 *
 * 思路：皮肤的「更多页面样式更改」本质是「把全站用到的设计 Token 取值整体换一套」。
 * 手写覆盖必然漏元素，所以这里从**编译产物**（unpackage/dist/dev/mp-weixin/**\/*.wxss）
 * 逐条抽取含调色板取值的声明，按皮肤映射表生成覆盖规则。
 *
 * 用法：HBuilderX 编译出 dev 产物后，node tools/gen-skins.cjs
 * 产物：common/styles/skins.generated.scss
 *
 * 覆盖规则带 !important：皮肤是有意的覆盖层（同暗色模式的 override sheet），
 * 小程序里 app.wxss 先于页面 wxss 加载，同等特异性会被页面样式压过去，
 * 与其和 40 个页面的选择器比特异性，不如在皮肤层显式声明优先级。
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const DIST = path.join(ROOT, 'unpackage/dist/dev/mp-weixin');
const OUT = path.join(ROOT, 'common/styles/skins.generated.scss');

/** 设计系统调色板（与 uni.scss 一致）——只认这些值，其余颜色（金色/彩虹/危险色等）一律不动 */
const PALETTE = {
	ink1: '#1f1f1f',
	ink2: '#5c5c5c',
	ink3: '#9a9a94',
	ink4: '#c6c6c0',
	bg: '#f2f2ec',
	card: '#ffffff',
	fill: '#f0f0e9',
	line: '#e9e9e2',
	accent: '#2f54eb',
	accentAlt: '#4c6ef5',
	accentWeak: '#eef2fe'
};

/** 每个皮肤：token → 新值。未列出的 token 保持不变（不生成覆盖）。 */
const SKINS = {
	mint: {
		label: '青瓷',
		shadow: 'rgba(24, 62, 52, 0.06)',
		map: {
			bg: '#eef3f1',
			fill: '#e6efeb',
			line: '#dde8e4',
			accent: '#0e8a72',
			accentAlt: '#2bb39a',
			accentWeak: '#e2f3ee'
		}
	},
	sunrise: {
		label: '晨光',
		shadow: 'rgba(74, 46, 12, 0.06)',
		map: {
			bg: '#f8f1e7',
			fill: '#f2e8d9',
			line: '#ecdfcb',
			accent: '#c25a06',
			accentAlt: '#e08a1e',
			accentWeak: '#fbeedb'
		}
	},
	ink: {
		label: '墨韵深色',
		// 深色底上 4% 黑的阴影等于没有，这里给足不透明度才有层次
		shadow: 'rgba(0, 0, 0, 0.5)',
		map: {
			ink1: '#f2f2f6',
			ink2: '#b9b9c5',
			ink3: '#8b8b98',
			ink4: '#63636f',
			bg: '#14141a',
			card: '#1e1e26',
			fill: '#262630',
			line: '#31313d',
			accent: '#7d95ff',
			accentAlt: '#9db0ff',
			accentWeak: '#232a45'
		}
	}
};

/** 影子色（$uh-shadow-* 里的 rgba(31, 31, 31, …)）整体替换，含不透明度 */
const SHADOW_RE = /rgba\(\s*31\s*,\s*31\s*,\s*31\s*,\s*[\d.]+\s*\)/g;

const hexOf = (token) => PALETTE[token];

/** 收集编译产物里所有 wxss 文件的顶层规则 */
function collectRules() {
	const rules = [];
	const skipped = { atRule: 0, host: 0, page: 0 };

	function walk(dir) {
		for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
			const full = path.join(dir, entry.name);
			if (entry.isDirectory()) { walk(full); continue; }
			if (!entry.name.endsWith('.wxss')) continue;
			// 必须先剥注释：编译产物保留了 SCSS 注释，而注释里含 { } 会破坏花括号配对，
			// 也会被误当成选择器的一部分（实测生成出 `view.skin-x /* … */ .foo` 这种规则）
			// @charset/@import 是「无块语句」，留着会把它后面第一条规则误判成 @ 规则而整条跳过
			const css = fs.readFileSync(full, 'utf8')
				.replace(/@(charset|import)\s[^;]*;/g, '')
				.replace(/\/\*[\s\S]*?\*\//g, '');
			let i = 0;
			while (i < css.length) {
				const open = css.indexOf('{', i);
				if (open < 0) break;
				const selector = css.slice(i, open).trim();
				// 花括号配对
				let depth = 1, j = open + 1;
				while (j < css.length && depth > 0) {
					if (css[j] === '{') depth++;
					else if (css[j] === '}') depth--;
					j++;
				}
				const body = css.slice(open + 1, j - 1);
				i = j;
				if (!selector) continue;
				if (selector.startsWith('@')) { skipped.atRule++; continue; }
				if (selector.includes(':host')) { skipped.host++; continue; }
				if (/^page\b/.test(selector)) { skipped.page++; continue; }
				rules.push({ selector, body, from: path.relative(ROOT, full) });
			}
		}
	}
	walk(DIST);
	return { rules, skipped };
}

/** 把一条声明值里的调色板取值换成皮肤取值；无变化返回 null */
function retint(prop, value, map, shadow) {
	let out = value;
	let changed = false;

	for (const [token, hex] of Object.entries(PALETTE)) {
		const to = map[token];
		if (!to) continue;
		// color: #ffffff 是「彩色底上的白字」，不能跟着卡片底色一起翻
		if (prop === 'color' && token === 'card') continue;
		const re = new RegExp(hex.replace('#', '#'), 'gi');
		if (re.test(out)) {
			out = out.replace(re, to);
			changed = true;
		}
	}
	if (SHADOW_RE.test(value) && shadow) {
		out = out.replace(SHADOW_RE, shadow);
		changed = true;
	}
	// 上面 test 用了带 g 的正则，会记住 lastIndex，重置以免影响下一个值
	SHADOW_RE.lastIndex = 0;
	return changed ? out : null;
}

/** 拆分声明块（保持顺序，跳过变量与 url() 里的分号风险） */
function splitDecls(body) {
	return body.split(';').map((s) => s.trim()).filter(Boolean);
}

function build() {
	const { rules, skipped } = collectRules();
	const chunks = [];
	const stats = {};

	chunks.push(`/**
 * 皮肤包样式（自动生成，勿手改）
 *
 * 由 tools/gen-skins.cjs 从编译产物逐条抽取生成：全站含设计 Token 取值的声明
 * 都会被替换成该皮肤的取值。页面改版后请重新编译并重跑生成器。
 *
 * 生成时间：${new Date().toISOString().slice(0, 10)}
 * 覆盖规则带 !important 的原因见生成器头部说明。
 */
`);

	for (const [id, skin] of Object.entries(SKINS)) {
		const emitted = [];
		const seen = new Set();

		for (const rule of rules) {
			const decls = [];
			for (const decl of splitDecls(rule.body)) {
				const idx = decl.indexOf(':');
				if (idx < 0) continue;
				const prop = decl.slice(0, idx).trim().toLowerCase();
				const value = decl.slice(idx + 1).trim();
				const next = retint(prop, value, skin.map, skin.shadow);
				if (next) decls.push(`${prop}: ${next} !important;`);
			}
			if (!decls.length) continue;

			// 编译产物的选择器带作用域类（.data-v-xxx），去掉后由皮肤类前缀提供特异性
			const selector = rule.selector
				.split(',')
				.map((s) => s.replace(/\.data-v-[0-9a-f]+/gi, '').trim())
				.filter(Boolean)
				.map((s) => `view.skin-${id} ${s}`)
				.join(', ');
			if (!selector) continue;

			const key = selector + '|' + decls.join('');
			if (seen.has(key)) continue;
			seen.add(key);
			emitted.push(`\t${selector} {\n${decls.map((d) => '\t\t' + d).join('\n')}\n\t}`);
		}

		stats[id] = emitted.length;
		chunks.push(`\n/* ===== ${skin.label}（skin-${id}）===== */\n${emitted.join('\n')}\n`);
	}

	const scss = chunks.join('\n');
	fs.writeFileSync(OUT, scss);

	// 覆盖率报告：扫描对象里含调色板取值的声明总数，与生成条数对照
	let paletteDecls = 0;
	for (const rule of rules) {
		for (const decl of splitDecls(rule.body)) {
			const idx = decl.indexOf(':');
			if (idx < 0) continue;
			const value = decl.slice(idx + 1).toLowerCase();
			if (Object.values(PALETTE).some((h) => value.includes(h))) { paletteDecls++; break; }
		}
	}
	console.log(`扫描到规则 ${rules.length} 条（跳过 @规则 ${skipped.atRule} / :host ${skipped.host} / page ${skipped.page}）`);
	console.log(`含调色板取值的规则 ${paletteDecls} 条`);
	for (const [id, n] of Object.entries(stats)) console.log(`  ${id.padEnd(8)} 生成 ${n} 条覆盖规则`);
	console.log(`输出：${path.relative(ROOT, OUT)}  ${(fs.statSync(OUT).size / 1024).toFixed(1)}KB`);
}

build();
