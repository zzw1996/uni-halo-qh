// 将页面主题覆盖放回各页 scoped style，避免不同编译器/页面更新改变 scope 后失配。
const fs = require('fs'), path = require('path');
const { collectRules, splitDecls, retint, SKINS } = require('./gen-skins.cjs');
const root = path.resolve(__dirname, '..');

const pages = [];
function scan(dir) {
 for (const entry of fs.readdirSync(dir,{withFileTypes:true})) {
  if (entry.name === 'components') continue;
  const full = path.join(dir,entry.name);
  if (entry.isDirectory()) scan(full);
  else if (entry.name.endsWith('.vue')) pages.push(full);
 }
}
for (const dir of ['pages','pagesA','pagesC']) scan(path.join(root,dir));
const rules = collectRules(full => /\/(pages|pagesA|pagesC)\//.test(full) && !full.includes('/components/') && !/\s[0-9]+\.wxss$/.test(full)).rules;
let total=0;
for (const file of pages) {
 let source=fs.readFileSync(file,'utf8');
 const rel=path.relative(root,file).replace(/\\/g,'/').replace(/\.vue$/,'');
 const page=path.basename(rel);
 const namespace=['ai-content','points-history'].includes(page) ? `${page}-theme` : `theme-page-${rel.replace(/\//g,'-')}`;
 const tag=source.match(/<view\b[^>]*>/)?.[0];
 if (!tag || !tag.includes('skinClass')) throw new Error('缺少主题根节点: '+rel);
 const originalClass=tag.match(/(?<!:)class="([^"]+)"/)?.[1]?.split(/\s+/)[0] || (page==='article-publish' ? 'publish-page' : null);
 const cssRules=rules.filter(r=>r.from.replace(/\\/g,'/').endsWith('/'+rel+'.wxss'));
 if (!cssRules.length && page!=='tags') throw new Error('缺少编译样式: '+rel);
 const output=['// 自动生成：node tools/gen-page-themes.cjs；请在页面样式更新后重新生成。'];
 for (const [id,skin] of Object.entries(SKINS)) {
  output.push(`.${namespace}.skin-${id}{background-color:${skin.map.bg} !important;color:${skin.map.ink1};min-height:100vh;box-sizing:border-box}`);
  for (const rule of cssRules) {
   if (/skin-|theme-page-|ai-content-theme|points-history-theme|discover-dark/.test(rule.selector)) continue;
   const decls=splitDecls(rule.body).flatMap(decl=>{
    const i=decl.indexOf(':');if(i<0)return [];
    const prop=decl.slice(0,i).trim().toLowerCase();
    const value=retint(prop,decl.slice(i+1).trim(),skin.map,skin.shadow,skin.whiteAlpha);
    return value?[`${prop}:${value.replace(/!\s*important\s*$/i,'').trim()} !important`]:[];
   });
   if(!decls.length)continue;
   const selector=rule.selector.split(',').map(raw=>{
    const s=raw.trim().replace(/\.data-v-[a-f0-9]+/g,'');
    const prefix='.'+originalClass;
    return originalClass && (s===prefix || s.startsWith(prefix+' ') || s.startsWith(prefix+'.') || s.startsWith(prefix+':'))
      ? s.replace(prefix,`.${namespace}.skin-${id}`) : `.${namespace}.skin-${id} ${s}`;
   }).join(',');
   output.push(`${selector}{${decls.join(';')}}`);
  }
 }
 const styleName=['ai-content','points-history'].includes(page) ? page : rel.replace(/\//g,'-');
 const outRel=`common/styles/redesign/${styleName}-themes.scss`;
 if (!['ai-content','points-history'].includes(page)) source=source.replaceAll(`@import '@/common/styles/redesign/${page}-themes.scss';\n`, '');
 fs.writeFileSync(path.join(root,outRel),output.join('\n')+'\n');
 if (!tag.includes(namespace)) {
  const updated=/(?<!:)class="/.test(tag) ? tag.replace(/(?<!:)class="/,`class="${namespace} `) : tag.replace('<view',`<view class="${namespace}"`);
  source=source.replace(tag,updated);
 }
 const imp=`@import '@/${outRel}';`;
 if (!source.includes(imp)) {
  const at=source.lastIndexOf('</style>');
  source=at>=0 ? source.slice(0,at)+imp+'\n'+source.slice(at) : source+`\n<style scoped lang="scss">\n${imp}\n</style>\n`;
 }
 source=source.replace(/<style\b([^>]*)>/g,(tag,attrs)=> /lang=/.test(attrs) ? tag : `<style${attrs} lang="scss">`);
 if(source!==fs.readFileSync(file,'utf8'))fs.writeFileSync(file,source);
 total+=output.length-1;
}
console.log(`Generated local theme rules for ${pages.length} pages (${total} rules).`);
