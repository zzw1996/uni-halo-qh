const assert = require('node:assert/strict');
const { retint, skinSelectors, finalDeclarations, SKINS } = require('./gen-skins.cjs');
const dark = SKINS.ink;
assert.equal(retint('background', '#fff', dark.map), dark.map.card);
assert.equal(retint('background-color', '#FFFFFF', dark.map), dark.map.card);
assert.equal(retint('color', '#fff', dark.map), null, '彩色按钮的白字保持可读');
assert.equal(retint('background', '#c2410c', dark.map), dark.map.accentFill, '暗色实心按钮保持白字对比度');
assert.equal(retint('color', '#c2410c', dark.map), dark.map.accent, '暗色链接仍用亮色');
assert.equal(retint('background', '#176b5b', dark.map), dark.map.accentFill, '改版前的旧强调色仍能映射');
assert.equal(retint('background', 'rgba(255,255,255,0.8)', dark.map, null, dark.whiteAlpha), dark.whiteAlpha+'0.8)');
const scoped = skinSelectors('.app-page.data-v-1234 .title.data-v-1234', 'ink');
assert.ok(scoped.includes('.skin-ink.app-page.data-v-1234 .title.data-v-1234'), '根节点也能匹配');
assert.ok(scoped.includes('.skin-ink .app-page.data-v-1234'), '后代组件仍能匹配');
assert.ok(scoped.includes('.data-v-1234'), '保留页面作用域');
const result = finalDeclarations([
  { from: 'article.wxss', selector: '.head.data-v-aa', body: 'background-color:#fff;box-shadow:0 0 4px #fff;color:#172c27' },
  { from: 'article.wxss', selector: '.head.data-v-aa', body: 'background:transparent;box-shadow:none' },
  { from: 'other.wxss', selector: '.head.data-v-bb', body: 'background-color:#fff' }
]);
assert.equal(result[0].body, 'color:#172c27', '后续透明背景/无阴影撤销旧覆盖');
assert.equal(result[2].body, 'background-color:#fff', '另一页面的同名类保留独立配色');
console.log('暗色生成器回归检查通过');
