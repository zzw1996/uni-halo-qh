#!/usr/bin/env node
/**
 * 打印 uni-halo/manifest.json 里的 versionName（发给微信的版本号就用它）。
 *
 * <p>manifest.json 带 /* *\/ 与 // 注释，不能直接 JSON.parse，这里先剥注释。</p>
 */
const fs = require('fs')
const path = require('path')

const file = path.join(__dirname, '..', 'manifest.json')
const raw = fs.readFileSync(file, 'utf8')
const stripped = raw
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/^[ \t]*\/\/.*$/gm, '')

let version = ''
try {
  version = JSON.parse(stripped).versionName || ''
} catch (e) {
  process.stderr.write(`解析 manifest.json 失败：${e.message}\n`)
  process.exit(1)
}
process.stdout.write(version)
