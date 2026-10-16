const fs=require('fs'),path=require('path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'..'),dist=path.join(root,'unpackage/dist/dev/mp-weixin');
let count=0;
function walk(dir){for(const e of fs.readdirSync(dir,{withFileTypes:true})){
 if(e.name==='components')continue;
 const p=path.join(dir,e.name);if(e.isDirectory()){walk(p);continue;}if(!p.endsWith('.vue'))continue;
 const source=fs.readFileSync(p,'utf8');
 const importPath=source.match(/@import '@\/(common\/styles\/redesign\/[^']+-themes.scss)'/)?.[1];
 assert(importPath,`缺少页面主题: ${p}`);
 const local=fs.readFileSync(path.join(root,importPath),'utf8');
 const ns=local.match(/\.([\w-]+)\.skin-ink\{/)[1];
 assert(source.split('</template>')[0].includes(ns),`缺少主题根类: ${p}`);
 const rel=path.relative(root,p).replace(/\.vue$/,'');
 const wxml=fs.readFileSync(path.join(dist,rel+'.wxml'),'utf8'),css=fs.readFileSync(path.join(dist,rel+'.wxss'),'utf8');
 assert(wxml.includes(ns),`编译模板缺少主题根类: ${rel}`);
 for(const id of ['mint','sunrise','ink','sky'])assert(css.includes(`.${ns}.skin-${id}`),`编译样式缺少 ${id}: ${rel}`);
 count++;
}}
for(const d of ['pages','pagesA','pagesC'])walk(path.join(root,d));
console.log(`PASS: ${count} 个页面的主题根类、导入和四种主题编译覆盖均有效。`);
