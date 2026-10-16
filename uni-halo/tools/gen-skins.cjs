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
const DIST = process.argv[2] ? path.resolve(process.argv[2]) : path.join(ROOT, 'unpackage/dist/dev/mp-weixin');
const OUT = path.join(ROOT, 'common/styles/skins.generated.scss');

/** 设计系统调色板（与 uni.scss 一致）——只认这些值，其余颜色（金色/彩虹/危险色等）一律不动 */
const PALETTE = {
    frameworkDark:'#121212',frameworkDark2:'#212121',frameworkDark3:'#424242',
	ink1Legacy: '#1f1f1f',
	ink2Legacy: '#5c5c5c',
	ink3Legacy: '#9a9a94',
	ink4Legacy: '#c6c6c0',
	bgLegacy: '#f2f2ec',
	fillLegacy: '#f0f0e9',
	lineLegacy: '#e9e9e2',
	accentLegacy: '#2f54eb',
	accentAltLegacy: '#4c6ef5',
	accentWeakLegacy: '#eef2fe',

	ink1: '#172c27',
	ink2: '#445c52',
	ink3: '#65736d',
	ink4: '#a4afa8',
	bg: '#f6f7f2',
	card: '#ffffff',
	fill: '#edf1eb',
	line: '#e3e8e2',
	accent: '#176b5b',
	accentAlt: '#247d6b',
	accentWeak: '#e7f0e9',
	// 设计系统里三类语义浅底（成功/警告/危险）。深色皮肤必须把它们也翻成暗色，
	// 否则深色下的成功提示、告警标签会是一块刺眼的浅色（新加的底栏/徽标都用到它们）
	successWeak: '#eaf4ec',
	warningWeak: '#faf0dd',
	dangerWeak: '#faecea',
	/* ── 以下是各页面历史上直接写死的浅色（不在设计 Token 里）──
	   深色模式下它们会留成白块（实测：我的页的快捷入口/签到卡/抽奖卡、文章页正文卡等）。
	   登记进调色板后，只有下面的 ink 皮肤给它们映射，浅色皮肤（青瓷/晨光）完全不受影响。 */
	fillAlt: '#f0f0f5',
	fillAlt2: '#f0f1f5',
	fillAlt3: '#f5f7fa',
	tintAmber: '#fff2d6',
	tintAmber2: '#fff8e1',
	tintAmber3: '#fff8ec',
	tintAmber4: '#fff3e0',
	tintOrange: '#ffe9e0',
	tintOrange2: '#fff0e0',
	tintPurple: '#f1eaff',
	tintPurple2: '#f3f0ff',
	tintPurple3: '#ece7ff',
	tintBlue: '#e5efff',
	tintBlue2: '#eef4ff',
	tintBlue3: '#e8f0ff',
	tintGreen: '#e5f7ec',
	tintGreen2: '#eef8ea',
	tintGreen3: '#e2f2dd',
	tintGreen4: '#f0f9eb',
	tintRed: '#ffe9e9',
	tintRed2: '#ffebee',
	/* ── 第四轮补充：扫全仓 `background` 得到的其余浅色 ──
	   它们散在榜单/抽奖/积分/身份/任务/热力图等页面，深色下都会留成浅块。
	   同样只在上面的 ink 皮肤给映射，浅色皮肤（青瓷/晨光/宣纸）不动。 */
	panel1: '#fafbfc',
	panel2: '#fafbff',
	panel3: '#f6f8fa',
	panel4: '#f5f6fa',
	panel5: '#f2f4fa',
	panel6: '#f2f3f5',
	panel7: '#f3f4f6',
	panel8: '#f1f5f9',
	panel9: '#f0f2f7',
	panel10: '#ebedf0',
	deep1: '#e8eaef',
	deep2: '#eaedf3',
	deep3: '#e0e0e0',
	amber1: '#fff7e6',
	amber2: '#fff8e6',
	amber3: '#fff7e8',
	orange1: '#ffefe0',
	orange2: '#fff1e8',
	rose: '#fff8f8',
	sky1: '#e1f5fe',
	sky2: '#eef7fe',
	blue1: '#e8f1ff',
	blue2: '#d9ecff',
	blue3: '#e3f2fd',
	blue4: '#e8f3ff',
	green1: '#e6f7e6',
	green2: '#eaf7f1',
	slate1: '#c9dcf6',
	slate2: '#bfe9ef',
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
		// 半透明白（卡片/浮层压在渐变上时的写法）在这里统一换成半透明深色：
		// 颜色是 rgba 字面量，不在调色板里，只能单独处理
		whiteAlpha: 'rgba(30, 30, 38,',
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
			// 亮色用于链接/选中态；实心按钮用深蓝，保证白字对比度。
			accentFill: '#4054aa',
			accentAltFill: '#5267bf',
			accentWeak: '#232a45',
			// 语义浅底 → 暗色底：底色变深、文字仍用对应的亮色（success/warning/danger 未在 map 里，
			// 所以文字色不受影响）
			successWeak: '#1f2e24',
			warningWeak: '#33291a',
			dangerWeak: '#33211a',
			fillAlt: '#262630',
			fillAlt2: '#262630',
			fillAlt3: '#262630',
			tintAmber: '#33291a',
			tintAmber2: '#33291a',
			tintAmber3: '#33291a',
			tintAmber4: '#33291a',
			tintOrange: '#33251c',
			tintOrange2: '#33251c',
			tintPurple: '#2a2643',
			tintPurple2: '#2a2643',
			tintPurple3: '#2a2643',
			tintBlue: '#222c44',
			tintBlue2: '#222c44',
			tintBlue3: '#222c44',
			tintGreen: '#1f2e24',
			tintGreen2: '#1f2e24',
			tintGreen3: '#1f2e24',
			tintGreen4: '#1f2e24',
			tintRed: '#33211a',
			tintRed2: '#33211a',
			// 第四轮补充的浅色底：近白 → fill/line，暖色/蓝/绿沿用已登记的暗色家族
			panel1: '#262630',
			panel2: '#262630',
			panel3: '#262630',
			panel4: '#262630',
			panel5: '#262630',
			panel6: '#262630',
			panel7: '#262630',
			panel8: '#262630',
			panel9: '#262630',
			panel10: '#262630',
			deep1: '#31313d',
			deep2: '#31313d',
			deep3: '#31313d',
			amber1: '#33291a',
			amber2: '#33291a',
			amber3: '#33291a',
			orange1: '#33251c',
			orange2: '#33251c',
			rose: '#33211a',
			sky1: '#222c44',
			sky2: '#222c44',
			blue1: '#222c44',
			blue2: '#222c44',
			blue3: '#222c44',
			blue4: '#222c44',
			green1: '#1f2e24',
			green2: '#1f2e24',
			slate1: '#2c3550',
			slate2: '#2c3550',
		}
	}
};


// Four UI themes, generated from the existing scoped token overrides.
SKINS.sky = {...(SKINS.sky || {}),label:"晴空日记", map:{...(SKINS.sky?.map || {}),...{"bg": "#eef7ff", "card": "#ffffff", "ink1": "#20374e", "ink2": "#425f77", "ink3": "#647a91", "ink4": "#8596a8", "fill": "#e4f3ff", "line": "#d9eafa", "accent": "#0874d5", "accentAlt": "#168bfa", "accentWeak": "#e4f3ff"}}};
SKINS.mint = {...(SKINS.mint || {}),label:"东方雅集", map:{...(SKINS.mint?.map || {}),...{"bg": "#f4f0e7", "card": "#fffdf7", "ink1": "#313d36", "ink2": "#556255", "ink3": "#7c8174", "ink4": "#999b90", "fill": "#efe9dc", "line": "#ded8c9", "accent": "#a83e32", "accentAlt": "#b95747", "accentWeak": "#f3e6df"}}};
SKINS.sunrise = {...(SKINS.sunrise || {}),label:"时光手账", map:{...(SKINS.sunrise?.map || {}),...{"bg": "#f6ecdd", "card": "#fffaf0", "ink1": "#5c3c2c", "ink2": "#775441", "ink3": "#8f7864", "ink4": "#aa9580", "fill": "#f4e5d2", "line": "#ddc6a9", "accent": "#a34f31", "accentAlt": "#bc6745", "accentWeak": "#f4e5d2"}}};
SKINS.ink = {...(SKINS.ink || {}),label:"极夜流光", map:{...(SKINS.ink?.map || {}),...{"bg": "#081528", "card": "#10213b", "ink1": "#eaf3ff", "ink2": "#c4d7ee", "ink3": "#9bafcc", "ink4": "#7187a4", "fill": "#192d4a", "line": "#254465", "accent": "#56e3ef", "accentAlt": "#b496ff", "accentWeak": "#192d4a", "accentFill": "#176783", "accentAltFill": "#6653a5"}}};
Object.assign(SKINS.ink.map,{frameworkDark:'#10213b',frameworkDark2:'#10213b',frameworkDark3:'#192d4a'});
SKINS.ink.whiteAlpha = 'rgba(16, 33, 59,';
/** 影子色（$uh-shadow-* 里的 rgba(31, 31, 31, …)）整体替换，含不透明度 */
const SHADOW_RE = /rgba\(\s*31\s*,\s*31\s*,\s*31\s*,\s*[\d.]+\s*\)/g;

const hexOf = (token) => PALETTE[token];

/** 收集编译产物里所有 wxss 文件的顶层规则 */
for (const skin of Object.values(SKINS)) {
	for (const token of ['ink1', 'ink2', 'ink3', 'ink4', 'bg', 'fill', 'line', 'accent', 'accentAlt', 'accentWeak'] ) {
		if (skin.map[token]) skin.map[token + 'Legacy'] = skin.map[token];
	}
}

function collectRules(acceptFile) {
	const rules = [];
	const skipped = { atRule: 0, host: 0, page: 0, deadGradient: 0 };

	function walk(dir) {
		for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
			const full = path.join(dir, entry.name);
			if (entry.isDirectory()) { walk(full); continue; }
			if (!entry.name.endsWith('.wxss')) continue;
			if (acceptFile && !acceptFile(full)) continue;
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
				// 不从已生成的覆盖层再次生成；否则多轮编译会累积跨皮肤规则。
				if (/\.skin-(?:ink|mint|sunrise|sky)\b|:root/.test(selector)) continue;
				if (selector.startsWith('@')) { skipped.atRule++; continue; }
				if (selector.includes(':host')) { skipped.host++; continue; }
				if (/^page\b/.test(selector)) { skipped.page++; continue; }
				// 渐变描边类全仓零引用（已从调色板里删掉，见 tools/strip-unused-gradient-css.py）。
				// 用旧产物重新生成时这批死规则会再被抄进皮肤包，这里挡掉。
				if (selector.includes('border-bg-gradient')) { skipped.deadGradient++; continue; }
				rules.push({ selector, body, from: path.relative(ROOT, full) });
			}
		}
	}
	walk(DIST);
	return { rules: finalDeclarations(rules), skipped };
}

// 只转换同一文件/作用域下最终生效的声明。后面的 transparent/none 也要
// 撤销旧声明，否则旧白底被转换成 !important 后会反压新布局。
function finalDeclarations(rules) {
	const seen = new Map();
	return rules.slice().reverse().map((rule) => {
		const key = rule.from + '|' + rule.selector;
		const props = seen.get(key) || new Set();
		seen.set(key, props);
		const body = splitDecls(rule.body).reverse().filter((decl) => {
			const prop = decl.slice(0, decl.indexOf(':')).trim().toLowerCase();
			if (props.has(prop) || (prop.startsWith('background-') && props.has('background'))) return false;
			props.add(prop);
			return true;
		}).reverse().join(';');
		return { ...rule, body };
	}).reverse();
}

function skinSelectors(selector, id) {
	return selector.split(',').map((s) => s.trim()).filter(Boolean).flatMap((s) => {
		const ancestor = `.skin-${id} ${s}`;
		if (s.startsWith('.')) return [ancestor, `.skin-${id}${s}`];
		if (/^view(?=[.#:\s]|$)/.test(s)) return [ancestor, s.replace(/^view/, `view.skin-${id}`)];
		return [ancestor];
	}).join(', ');
}

/** 把一条声明值里的调色板取值换成皮肤取值；无变化返回 null */
/** 半透明白（rgba(255,255,255,α)）——只用于背景类属性，文字色上的白不参与替换 */
const WHITE_ALPHA_RE = /rgba\(\s*255\s*,\s*255\s*,\s*255\s*,\s*([\d.]+)\s*\)/g;

/**
 * 作为**文字色**时不参与替换的 Token。
 *
 * <p>这些都是「浅色底」家族的取值，映射目标是深底色调；一旦被当成文字色写进深色皮肤，
 * 就成了深底上的深字（实测 tm-vuetify 的 `.text-grey-lighten-*` 会命中）。
 * 浅色文字在深底上本来就清晰，保持原值即可。</p>
 */
const TEXT_KEEP_TOKENS = new Set([
	'panel1', 'panel2', 'panel3', 'panel4', 'panel5',
	'panel6', 'panel7', 'panel8', 'panel9', 'panel10',
	'deep1', 'deep2', 'deep3',
	'amber1', 'amber2', 'amber3', 'orange1', 'orange2', 'rose1',
	'sky1', 'sky2', 'blue1', 'blue2', 'blue3', 'blue4', 'green1', 'green2',
	'slate1', 'slate2'
]);

function retint(prop, value, map, shadow, whiteAlpha) {
	// Sass/压缩器会把 #ffffff 写成 #fff；先规范化才能覆盖白卡片等短色值。
	let out = value.replace(/#([0-9a-f]{3})(?![0-9a-f])/gi, (_, rgb) => '#' + rgb.split('').map((c) => c + c).join(''));
	let changed = false;

	for (const [token, hex] of Object.entries(PALETTE)) {
		const baseToken = token.replace(/Legacy$/, '');
		const to = /^background/.test(prop) && map[baseToken + 'Fill'] ? map[baseToken + 'Fill'] : map[token];
		if (!to) continue;
		// color: #ffffff 是「彩色底上的白字」，不能跟着卡片底色一起翻
		if (prop === 'color' && token === 'card') continue;
		// 近白/浅色底的 Token 也会被当成**文字色**用（tm-vuetify 的 .text-grey-lighten-* 之类）。
		// 它们映射到的都是很深的底色调（#262630/#31313d），当文字色写上去就成了「深底上的深字」，
		// 比不换更糟；浅色文字在深底上本来就清晰，所以这些 Token 不碰 color。
		if (prop === 'color' && TEXT_KEEP_TOKENS.has(token)) continue;
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
	// 半透明白底：只认背景类属性。文字色（color/…）上的白是「彩色底上的白字」，
	// 换了会让深色下的按钮文字变暗色，属于典型误伤
	if (whiteAlpha && /^background/.test(prop) && WHITE_ALPHA_RE.test(out)) {
		out = out.replace(WHITE_ALPHA_RE, whiteAlpha + '$1)');
		changed = true;
	}
	WHITE_ALPHA_RE.lastIndex = 0;
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
				const next = retint(prop, value, skin.map, skin.shadow, skin.whiteAlpha);
				// 原声明可能自带 !important（tm-vuetify 编译出来的是 `color:#xxx!important` 这种无空格写法）：
				// 直接再拼一个会得到 `#xxx!important !important` —— 整条声明非法，浏览器/小程序会丢掉它，
				// 覆盖静默失效。先剥掉原来的，由这里统一补一个。
				if (next) decls.push(`${prop}: ${next.replace(/!\s*important\s*$/i, '').trim()} !important;`);
			}
			if (!decls.length) continue;

			// 保留作用域，防止不同页面的 .head/.content 等同名类互相覆盖。
			// 皮肤既可能在祖先上，也可能就在当前页面/组件根节点上。
			const selector = skinSelectors(rule.selector, id);
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
	console.log(`扫描到规则 ${rules.length} 条（跳过 @规则 ${skipped.atRule} / :host ${skipped.host} / page ${skipped.page} / 死渐变类 ${skipped.deadGradient}）`);
	console.log(`含调色板取值的规则 ${paletteDecls} 条`);
	for (const [id, n] of Object.entries(stats)) console.log(`  ${id.padEnd(8)} 生成 ${n} 条覆盖规则`);
	console.log(`输出：${path.relative(ROOT, OUT)}  ${(fs.statSync(OUT).size / 1024).toFixed(1)}KB`);
}

if (require.main === module) build();
module.exports = { retint, skinSelectors, finalDeclarations, collectRules, splitDecls, SKINS };
