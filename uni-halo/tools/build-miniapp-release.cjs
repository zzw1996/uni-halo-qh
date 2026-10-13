#!/usr/bin/env node
/**
 * 一条命令出微信小程序的「正式（release）包」，并顺手做体积体检。
 *
 * <p>用法（在 uni-halo 目录下执行）：</p>
 * <pre>
 *   node tools/build-miniapp-release.cjs
 * </pre>
 *
 * <p><b>为什么需要它</b>：微信开发者工具里点「运行到小程序」时，打开的是
 * {@code unpackage/dist/dev/mp-weixin}（<b>未压缩</b>的开发产物）；如果直接在那个窗口点
 * 「上传」，微信按未优化体积校验，主包会到 2.3MB 以上，必然报
 * 「代码包大小超过限制」。上传必须用 {@code unpackage/dist/build/mp-weixin}——那是
 * HBuilderX「发行 → 小程序-微信」的产物，压缩 + 摇树后主包约 1.7MB。</p>
 *
 * <p>两个产物目录的分工：</p>
 * <ul>
 *   <li>{@code dist/dev/mp-weixin}：本地预览用（运行到微信开发者工具），<b>不可上传</b>；</li>
 *   <li>{@code dist/build/mp-weixin}：上传/预览发版用，由本脚本（等价于 HBuilderX「发行」）生成。</li>
 * </ul>
 *
 * <p>本脚本直接调用 HBuilderX 自带的 uni-app 编译器（cli 的 publish 命令要求登录
 * HBuilderX 账号，本脚本不需要）。HBuilderX 的位置依次从环境变量 {@code HBX_HOME}、
 * 常见安装路径、各盘根目录扫描得到，也可以用 {@code --hbx <路径>} 指定。</p>
 */
const fs = require('fs')
const path = require('path')
const { spawnSync } = require('child_process')

const ROOT = path.resolve(__dirname, '..')
const OUT_DIR = path.join(ROOT, 'unpackage', 'dist', 'build', 'mp-weixin')

/** 解析微信开发者工具 CLI（只为在结尾打印一条可直接复制的上传命令） */
function resolveDevtoolsCli() {
  const env = process.env.WX_DEVTOOLS_CLI
  if (env && fs.existsSync(env)) return env
  const names = ['微信web开发者工具', '微信开发者工具']
  const roots = ['C:', 'D:', 'E:', 'F:']
  const subs = ['Program Files (x86)\\Tencent', 'Program Files\\Tencent', 'chengxu', 'software', '']
  for (const drive of roots) {
    for (const sub of subs) {
      for (const name of names) {
        const p = path.join(drive + '\\', sub, name, 'cli.bat')
        if (fs.existsSync(p)) return p
      }
    }
  }
  return '<微信开发者工具>\\cli.bat'
}
const CLI_BAT = resolveDevtoolsCli()

function log(msg) {
  console.log(msg)
}

/** 解析 HBuilderX 安装目录（含 HBuilderX.exe 的那一层） */
function resolveHbxHome() {
  const args = process.argv.slice(2)
  const flagIndex = args.indexOf('--hbx')
  const candidates = []
  if (flagIndex >= 0 && args[flagIndex + 1]) candidates.push(args[flagIndex + 1])
  if (process.env.HBX_HOME) candidates.push(process.env.HBX_HOME)

  // 可能是安装目录本身，也可能是它的上一级（HBuilderX.zip 解压后常见）
  const known = []
  for (const drive of ['C', 'D', 'E', 'F']) {
    known.push(`${drive}:\\HBuilderX`)
    known.push(`${drive}:\\Program Files\\HBuilderX`)
    known.push(`${drive}:\\edge\\HBuilderX`) // 本机实际位置：D:\edge\HBuilderX.5.07.2026041006
  }
  candidates.push(...known)

  // 扫描各盘根目录/一层子目录里名字带 HBuilder 的目录（含带版本号后缀的）
  for (const drive of ['C', 'D', 'E', 'F']) {
    for (const base of [`${drive}:\\`, `${drive}:\\edge`, `${drive}:\\soft`, `${drive}:\\softwares`, `${drive}:\\Program Files`]) {
      let entries = []
      try {
        entries = fs.readdirSync(base, { withFileTypes: true })
      } catch (e) {
        continue
      }
      for (const entry of entries) {
        if (entry.isDirectory() && /^HBuilderX/i.test(entry.name)) {
          candidates.push(path.join(base, entry.name))
          // 解压型目录里还套着一层同名目录
          candidates.push(path.join(base, entry.name, 'HBuilderX'))
        }
      }
    }
  }

  for (const candidate of candidates) {
    if (!candidate) continue
    const home = path.resolve(candidate)
    if (fs.existsSync(path.join(home, 'cli.exe'))
      && fs.existsSync(path.join(home, 'plugins', 'uniapp-cli', 'bin', 'uniapp-cli.js'))) {
      return home
    }
  }
  return null
}

/** HBuilderX 自带 node（优先 node18，与 IDE 编译时用的版本一致） */
function resolveNode(home) {
  for (const dir of ['node18', 'node']) {
    const exe = path.join(home, 'plugins', dir, process.platform === 'win32' ? 'node.exe' : 'node')
    if (fs.existsSync(exe)) return exe
  }
  return process.execPath
}

function main() {
  const home = resolveHbxHome()
  if (!home) {
    console.error('✗ 没找到 HBuilderX 安装目录。')
    console.error('  请用 HBX_HOME 或 --hbx 指定，例如：')
    console.error('    node tools/build-miniapp-release.cjs --hbx "D:\\edge\\HBuilderX.5.07.2026041006\\HBuilderX"')
    process.exit(1)
  }

  const cliPluginDir = path.join(home, 'plugins', 'uniapp-cli')
  const entry = path.join(cliPluginDir, 'bin', 'uniapp-cli.js')
  const node = resolveNode(home)

  log(`HBuilderX：${home}`)
  log(`node     ：${node}`)
  log(`项目     ：${ROOT}`)
  log(`输出     ：${path.relative(ROOT, OUT_DIR)}`)
  log('')
  log('开始编译（相当于 HBuilderX「发行 → 小程序-微信」，约 1~3 分钟）…')

  const started = Date.now()
  const result = spawnSync(node, [entry], {
    cwd: cliPluginDir,
    stdio: 'inherit',
    env: {
      ...process.env,
      NODE_ENV: 'production',
      UNI_PLATFORM: 'mp-weixin',
      // 正式包的关键开关：压缩 + 混淆（dev 运行产物这里是 false）
      UNI_MINIMIZE: 'true',
      UNI_INPUT_DIR: ROOT,
      UNI_OUTPUT_DIR: OUT_DIR,
      VUE_CLI_SERVICE_CONFIG_PATH: path.join(ROOT, 'vue.config.js'),
      VUE_CLI_CONTEXT: cliPluginDir,
      UNI_HBUILDERX_PLUGINS: path.join(home, 'plugins')
    }
  })

  const seconds = ((Date.now() - started) / 1000).toFixed(0)
  if (result.status !== 0) {
    console.error(`\n✗ 编译失败（退出码 ${result.status}，耗时 ${seconds}s）`)
    process.exit(result.status || 1)
  }
  log(`\n✓ 编译完成（${seconds}s）`)

  // 编译完直接体检：这步过了才谈上传
  const check = spawnSync(process.execPath, [path.join(ROOT, 'tools', 'check-miniapp-package.cjs'), 'build'], {
    stdio: 'inherit'
  })
  if (check.status === 0) {
    log('')
    log('上传步骤（开发者工具里必须打开下面这个目录，别开 dist/dev —— 见 小程序上传超限与主包体积.md）：')
    log(`  1) 打开项目：${OUT_DIR}`)
    log('  2) 点「上传」，填版本号与备注')
    log('  或者直接用命令行上传（开发者工具需在运行中且已登录）：')
    log(`     "${CLI_BAT}" upload --project "${OUT_DIR}" -v <版本号> -d "<备注>"`)
  }
  process.exit(check.status || 0)
}

main()
