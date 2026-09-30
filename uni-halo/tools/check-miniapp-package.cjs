#!/usr/bin/env node
/**
 * 小程序发版前体检：主包体积 + 产物里的「不该有的杂物」。
 *
 * <p>用法（在 uni-halo 目录下执行）：</p>
 * <pre>
 *   node tools/check-miniapp-package.cjs            # 检查 dev 产物
 *   node tools/check-miniapp-package.cjs build      # 检查 release 产物（发版用这个）
 * </pre>
 *
 * <p><b>为什么需要它</b>：微信主包上限 2048KB，而「产物目录里混进杂物」比「代码写多了」
 * 更容易悄悄撑爆它——2026-09-29 就踩过一次：unpackage/dist/build/mp-weixin 里残留了一份
 * 1.7MB 的 .git 目录（早期手工复制留下），主包因此从 1.6MB 涨到 2.4MB，
 * 上传直接报「代码包大小超过限制」。代码本身没问题。</p>
 *
 * <p>退出码：0 = 通过；1 = 有阻塞项（超限或发现杂物）。可直接接进发版流程。</p>
 */
const fs = require('fs')
const path = require('path')

const ROOT = path.resolve(__dirname, '..')
const which = (process.argv[2] || 'dev') === 'build' ? 'build' : 'dev'
const OUT = path.join(ROOT, 'unpackage', 'dist', which, 'mp-weixin')

/** 微信限制：单个包（主包/分包）2048KB */
const PACKAGE_LIMIT_KB = 2048
/** 总包上限 20MB */
const TOTAL_LIMIT_KB = 20 * 1024
/** 产物里不该出现的东西：命中即报错（它们会原样打进上传包） */
const FORBIDDEN = ['.git', 'node_modules', '.gitignore', '.svn', '.hg']
const FORBIDDEN_EXT = ['.bak', '.py', '.zip', '.log', '.rar', '.map']

function dirSize(target) {
  let total = 0
  for (const entry of fs.readdirSync(target, { withFileTypes: true })) {
    const full = path.join(target, entry.name)
    if (entry.isDirectory()) {
      total += dirSize(full)
    } else {
      try {
        total += fs.statSync(full).size
      } catch (e) /* 忽略读取失败的文件 */ {}
    }
  }
  return total
}

/** 读 app.json 拿分包根目录（主包 = 全部 - 各分包） */
function subRoots() {
  const appJson = path.join(OUT, 'app.json')
  if (!fs.existsSync(appJson)) return []
  try {
    const parsed = JSON.parse(fs.readFileSync(appJson, 'utf8'))
    const list = parsed.subPackages || parsed.subpackages || []
    return list.map((s) => String(s.root || '').replace(/\/+$/, '')).filter(Boolean)
  } catch (e) {
    return []
  }
}

function findJunk(target, found = []) {
  for (const entry of fs.readdirSync(target, { withFileTypes: true })) {
    const full = path.join(target, entry.name)
    const rel = path.relative(OUT, full).replace(/\\/g, '/')
    if (entry.isDirectory()) {
      if (FORBIDDEN.includes(entry.name)) {
        found.push({ rel, size: dirSize(full), kind: '目录' })
        continue
      }
      findJunk(full, found)
    } else if (FORBIDDEN_EXT.includes(path.extname(entry.name).toLowerCase())) {
      found.push({ rel, size: fs.statSync(full).size, kind: '文件' })
    }
  }
  return found
}

/**
 * 找「产物里存在、但 app.json 没注册」的页面。
 *
 * <p>构建是增量的（uni-app 传 clean:false），删掉 pages.json 里的页面后旧产物文件不会被清掉，
 * 而微信上传是「整目录打包」——这些孤儿页面会被一起上传并计入体积（曾经删了三个抽奖页面，
 * 产物里残留近 30KB）。</p>
 *
 * <p>判据是「.wxml 的相对路径（去扩展名）是否在 app.json 的注册集里」，
 * 而不是「目录名」——分包注册的是 {@code root + 页面路径}，两者不一定同名
 * （例如 {@code {"root":"pagesC","pages":["website/website"]}} 对应目录 pagesC/website）。
 * 组件目录（路径含 components/）不参与判断。</p>
 */
function findOrphanPages() {
  const appJson = path.join(OUT, 'app.json')
  if (!fs.existsSync(appJson)) return []
  let parsed
  try {
    parsed = JSON.parse(fs.readFileSync(appJson, 'utf8'))
  } catch (e) {
    return []
  }
  const registered = new Set()
  for (const p of parsed.pages || []) registered.add(String(p))
  const roots = []
  for (const sp of parsed.subPackages || parsed.subpackages || []) {
    const root = String(sp.root || '').replace(/\/+$/, '')
    roots.push(root)
    for (const p of sp.pages || []) {
      registered.add(root + '/' + String(typeof p === 'string' ? p : p.path || ''))
    }
  }
  const scanDirs = ['pages', ...roots].filter((d) => fs.existsSync(path.join(OUT, d)))
  const orphans = []
  const shells = []
  const seen = new Set()
  for (const dir of scanDirs) {
    for (const wxml of walkFiles(path.join(OUT, dir), '.wxml')) {
      const rel = path.relative(OUT, wxml).replace(/\\/g, '/')
      if (rel.includes('/components/')) continue
      const pagePath = rel.slice(0, -'.wxml'.length)
      if (registered.has(pagePath)) continue
      const pageDir = path.dirname(wxml)
      const dirRel = path.relative(OUT, pageDir).replace(/\\/g, '/')
      if (seen.has(dirRel)) continue
      seen.add(dirRel)
      orphans.push({ rel: dirRel, size: dirSize(pageDir) })
    }
  }
  // 空壳目录：有 .js/.json 却没有 .wxml。
  // 这是增量构建最阴的一种残留——开发者工具看到 .js/.json 就当它是页面、
  // 去找同名 .wxml 找不到，直接报「WXML 文件编译错误 ENOENT」把整个项目卡住。
  for (const dir of scanDirs) {
    const base = path.join(OUT, dir)
    let entries = []
    try {
      entries = fs.readdirSync(base, { withFileTypes: true })
    } catch (e) {
      continue
    }
    for (const entry of entries) {
      if (!entry.isDirectory()) continue
      // uni-app 会把分包共用的代码放进 <分包>/common（只有 .js，没有 .wxml），
      // 那是正常的公共依赖目录，不是页面的空壳
      if (entry.name === 'common' || entry.name === 'components') continue
      const pageDir = path.join(base, entry.name)
      let files = []
      try {
        files = fs.readdirSync(pageDir)
      } catch (e) {
        continue
      }
      const hasWxml = files.some((f) => f.endsWith('.wxml'))
      if (hasWxml) continue
      if (!files.some((f) => f.endsWith('.js') || f.endsWith('.json'))) continue
      const dirRel = path.relative(OUT, pageDir).replace(/\\/g, '/')
      shells.push({ rel: dirRel, size: dirSize(pageDir) })
    }
  }
  return { orphans, shells }
}

/** 递归收集指定扩展名的文件 */
function walkFiles(dir, ext, found = []) {
  let entries = []
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true })
  } catch (e) {
    return found
  }
  for (const entry of entries) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) walkFiles(full, ext, found)
    else if (entry.name.endsWith(ext)) found.push(full)
  }
  return found
}

/**
 * 找产物 components/ 下「源码已经不存在的组件目录」。
 *
 * <p>与页面同样的增量构建问题：删掉组件后产物里的旧文件不会被清。
 * 判据是「产物目录名不在源码 components/ 里」——比反向判断安全，
 * 因为未被引用的源码组件本来就不会出现在产物里（uni-app 会 tree-shake）。</p>
 */
function findOrphanComponents() {
  const srcDir = path.join(ROOT, 'components')
  const outDir = path.join(OUT, 'components')
  if (!fs.existsSync(srcDir) || !fs.existsSync(outDir)) return []
  const srcNames = new Set(fs.readdirSync(srcDir))
  const orphans = []
  for (const entry of fs.readdirSync(outDir, { withFileTypes: true })) {
    if (!entry.isDirectory() || srcNames.has(entry.name)) continue
    orphans.push({ name: entry.name, size: dirSize(path.join(outDir, entry.name)) })
  }
  return orphans
}

function main() {
  if (!fs.existsSync(OUT)) {
    console.error(`✗ 找不到产物目录：${path.relative(ROOT, OUT)}`)
    console.error(`  请先在 HBuilderX 里「运行/发行」一次（当前检查的是 ${which} 产物）`)
    process.exit(1)
  }

  console.log(`检查产物：${path.relative(ROOT, OUT)}（${which}）\n`)

  const roots = subRoots()
  const totalKb = dirSize(OUT) / 1024
  let subTotalKb = 0
  const subs = []
  for (const root of roots) {
    const dir = path.join(OUT, root)
    if (!fs.existsSync(dir)) continue
    const kb = dirSize(dir) / 1024
    subs.push({ root, kb })
    subTotalKb += kb
  }
  const mainKb = totalKb - subTotalKb

  // dev 产物未压缩（JS 约为 release 的 5 倍），微信不接受上传，因此体积只作对比、不判超限
  const sizeBlocking = which === 'build'
  const line = (label, kb, limit) => {
    const pct = ((kb / limit) * 100).toFixed(0)
    const over = sizeBlocking && kb > limit
    const mark = over ? '✗' : '·'
    console.log(`${mark} ${label.padEnd(28)} ${kb.toFixed(0).padStart(6)} KB / ${limit} KB（${pct}%）`)
    return over
  }

  if (!sizeBlocking) {
    console.log('（dev 产物未压缩、不可上传，以下体积仅供与 release 对比）')
    console.log('')
  }

  let blocked = false
  blocked = line('主包', mainKb, PACKAGE_LIMIT_KB) || blocked
  for (const s of subs) {
    blocked = line(`分包 ${s.root}`, s.kb, PACKAGE_LIMIT_KB) || blocked
  }
  blocked = line('合计', totalKb, TOTAL_LIMIT_KB) || blocked

  // 杂物检查
  const junk = findJunk(OUT)
  if (junk.length) {
    blocked = true
    console.log('\n✗ 产物里发现了不该打进的杂物（它们会被原样上传、直接计入体积）：')
    for (const j of junk) {
      console.log(`   ${j.kind}  ${j.rel}  ${(j.size / 1024).toFixed(0)} KB`)
    }
    console.log('\n   处理：删掉它们后重新上传；若反复出现，检查是否有手工复制/同步脚本把')
    console.log('   这些目录带进了 unpackage/dist（HBuilderX 自己生成时不会带）。')
  } else {
    console.log('\n✓ 产物里没有 .git / node_modules 之类的杂物')
  }

  // 孤儿页面 / 空壳目录检查
  const { orphans, shells } = findOrphanPages()
  if (orphans.length) {
    blocked = true
    console.log('\n✗ 产物里有已从 pages.json 删除、但文件仍残留的页面（会被一起上传并计入体积）：')
    for (const o of orphans) {
      console.log(`   ${o.rel}  ${(o.size / 1024).toFixed(0)} KB`)
    }
    console.log('\n   处理：删掉这些目录后重新上传。')
    console.log('   成因：构建是增量的（clean: false），删页面时旧产物不会被清掉。')
  } else {
    console.log('✓ 产物里没有未注册的孤儿页面')
  }
  if (shells.length) {
    blocked = true
    console.log('\n✗ 产物里有「只有 .js/.json、没有 .wxml」的空壳页面目录——会让开发者工具直接报')
    console.log('   「WXML 文件编译错误 ENOENT」并卡住整个项目：')
    for (const s of shells) {
      console.log(`   ${s.rel}  ${(s.size / 1024).toFixed(0)} KB`)
    }
    console.log('\n   处理：删掉这些目录（最好整个产物目录重建）后重新编译。')
  } else {
    console.log('✓ 产物里没有空壳页面目录')
  }

  // 孤儿组件检查
  const orphanComps = findOrphanComponents()
  if (orphanComps.length) {
    blocked = true
    console.log('\n✗ 产物里有源码中已不存在的组件目录（同样会被上传并计入体积）：')
    for (const o of orphanComps) {
      console.log(`   components/${o.name}  ${(o.size / 1024).toFixed(0)} KB`)
    }
    console.log('\n   处理：删掉这些目录后重新上传。')
  } else {
    console.log('✓ 产物里没有已删组件的残留')
  }

  console.log()
  if (blocked) {
    console.log('✗ 发版前请先处理上面的问题')
    process.exit(1)
  }
  console.log('✓ 体积检查通过')
}

main()
