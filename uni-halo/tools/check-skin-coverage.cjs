// 在编译后运行，阻止旧页面作用域使主题覆盖静默失效。
const fs = require('fs');
const path = require('path');
const { collectRules, splitDecls, retint, skinSelectors, SKINS } = require('./gen-skins.cjs');
const root = path.resolve(__dirname, '..');
const generated = fs.readFileSync(path.join(root, 'common/styles/skins.generated.scss'), 'utf8');
const compact = value => value.replace(/\s+/g, '');
const normalized = compact(generated);
const missing = new Set();
let checked = 0;
for (const rule of collectRules().rules) {
  const needsTheme = splitDecls(rule.body).some(decl => {
    const idx = decl.indexOf(':');
    return idx >= 0 && retint(decl.slice(0, idx).trim().toLowerCase(), decl.slice(idx + 1).trim(), SKINS.ink.map, SKINS.ink.shadow, SKINS.ink.whiteAlpha);
  });
  if (!needsTheme) continue;
  checked++;
  if (!normalized.includes(compact(skinSelectors(rule.selector, 'ink') + '{'))) missing.add(rule.from + ': ' + rule.selector);
}
if (missing.size) {
  console.error('主题覆盖已过期，请运行 node tools/gen-skins.cjs 后重新编译：\n' + [...missing].slice(0, 12).join('\n'));
  process.exitCode = 1;
} else console.log(`PASS: ${checked} 条深色规则与当前编译页面匹配。`);
