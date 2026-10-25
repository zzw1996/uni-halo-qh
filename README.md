# uni-halo-qh

基于 [Halo 2.x](https://halo.run/) 开源博客系统的微信小程序工作区，包含两个子项目：

- `uni-halo/`：uni-app（Vue 2 + tm-vuetify）小程序前端，编译为微信小程序，当前 `versionName 4.0.3`。
- `plugin-uni-halo/`：配套的 Halo 插件（Java 17 / Gradle，`pluginId=pro.uhalo.uni`），当前 `version 1.0.219`，负责微信登录用户体系、积分、权益与虚拟支付、AI 日报/定制内容、分类订阅与推送、榜单、底栏配置等后台能力。

两个子项目各自在本地维护独立 git 历史（原远端 Gitee / GitHub），本仓库是工作区的整体快照（不含构建产物）。

> **本文是这个项目唯一的「历史决策文档」。** 原先根目录下 26 份「XX 方案.md / XX 记录.md」已逐篇蒸馏成第 4 节的条目，只保留结论、契约与坑，过程日志全部删除。需要逐章推导过程时，用 `git show 4edfacf -- <原文件名>` 取回原文。
> 「现在到哪了、接下来做什么」不在这里——见 `uni-halo-next-plan.md`（现状总结 + 分档待办）。做完整理之外，两份文档的分工是：README 记**已经定过的事和为什么**，plan 记**未定的事**。
> `uni-halo/README.md`、`uni-halo/CHANGELOG.md`、`uni-halo/GIT_README.md`、`plugin-uni-halo/README.md` 是子项目自带的说明文件，不在合并范围内。

---

## 1. 环境搭建与运行

仓库里**不含**任何依赖与工具链（`node_modules/`、`unpackage/`、`build/`、`.gradle/`、`.tools/` 均已 gitignore）。

### 1.1 需要预装的软件

| 软件 | 版本 | 用途 | 备注 |
| --- | --- | --- | --- |
| Node.js | 18+（实测 24.18.0） | 前端依赖 + 插件控制台 UI 构建 | |
| JDK | **17**（必须） | 编译 Halo 插件 | 插件用 Java 17 toolchain，JDK 8/11 会失败 |
| pnpm | 8.x（实测 8.6.12） | 插件 `ui/` 依赖 | 由 Gradle 自动下载，无需手装 |
| HBuilderX | 最新 | 编译微信小程序 | **必须**，见 §1.3 |
| Docker | 可选 | 本地起 Halo 调插件（`haloServer`） | 不跑本地 Halo 可不装 |
| 微信开发者工具 | 最新 | 预览与上传小程序 | |

> ⚠️ **`JAVA_HOME` 务必指向 JDK 17。** 原开发机上 `JAVA_HOME` 指 JDK 8 而 `PATH` 里的 `java` 是 17，表现为 `./gradlew` 报「无效的源发行版」。先 `java -version` 确认。

### 1.2 后端插件 `plugin-uni-halo`

Gradle Wrapper 已入库，**不需要预装 Gradle**（自动从腾讯镜像下载 Gradle 8.0）。

```bash
cd plugin-uni-halo

# 仅编译 Java（跳过前端 UI 构建，最快）
./gradlew --offline compileJava -x pnpmSetup -x buildFrontend -x installDepsForUI

# 跑测试（提交前基线：103 类 / 963 用例全绿）
./gradlew --offline test -x pnpmSetup -x buildFrontend -x installDepsForUI

# 全量构建：先装 ui/ 依赖（约 544MB）再出包，产物 build/libs/*.jar
./gradlew build

# 本地起一个带本插件的 Halo（需要 Docker）
./gradlew haloServer
```

`build.gradle` 里 `compileJava` 依赖 `buildFrontend`，后者依赖 `installDepsForUI`（对 `ui/` 执行 pnpm install），首次全量构建下载量大属正常。

部署：把 `build/libs/*.jar` 上传到 Halo 后台「插件 → 安装」，或按 Halo 开发文档配置 `fixedPluginPath`。
**每次改动必须同步升 `gradle.properties` 的 `version` 和 `plugin.yaml` 的 `spec.version`，否则站点后台看不到升级。**

### 1.3 前端小程序 `uni-halo`

```bash
cd uni-halo
npm install          # 唯一依赖 qs
```

编译必须用 HBuilderX（本工程是 HBuilderX 工程，没有 Vite/CLI 构建链）：

1. HBuilderX → 文件 → 打开目录 → 选 `uni-halo/`
2. 运行 → 运行到小程序模拟器 → 微信开发者工具
3. 微信开发者工具里导入 `unpackage/dist/dev/mp-weixin`

### 1.4 `uni-halo/tools/` 脚本（有 CLI，别手测）

| 脚本 | 用途 |
| --- | --- |
| `node tools/build-miniapp-release.cjs` | 直接调 HBuilderX 自带的 `uniapp-cli` 出**正式包**，绕开需要登录的 `cli publish`，约 25s；产物 `unpackage/dist/build/mp-weixin`。HBuilderX 装在标准路径会自动找到，否则 `set HBX_HOME=D:\HBuilderX` |
| `node tools/check-miniapp-package.cjs` | 发版前体检：主包/分包体积、产物杂物 |
| `node tools/gen-skins.cjs` | 从编译后 WXSS 反向生成 `common/styles/skins.generated.scss`。**改了皮肤配色或暗色覆盖必须重编译 + 重跑它** |
| `tools/strip-unused-gradient-css.py` | 清 `.border-bg-gradient-*` 死 CSS（−103KB） |
| `node tools/miniapp-version.cjs` | 读 `manifest.json` 的 `versionName` 算 `versionCode` |
| `python tools/check-undefined-methods.py` | 扫描「调了 `this.fnXxx()` 但本文件没定义」，防点击才炸。**每次批量改完前端方法名都要跑一遍** |

### 1.5 域名配置（单点硬编码）

```
uni-halo/config/uhalo.config.js  →  BASE_API: "https://www.lxfzzw.com"
```

换博客站点改这一处即可（**结尾不能带斜杠**）。
`config/index.js` 里 `tabbarConfig.items` 必须保持**空数组**——`utils.deepMerge` 对数组是 concat 语义，写默认项会把 5 项渲染成 10 项并双重高亮。

### 1.6 拉下来之后的验收清单

```bash
cd plugin-uni-halo && ./gradlew --offline compileJava -x pnpmSetup -x buildFrontend -x installDepsForUI
./gradlew --offline test -x pnpmSetup -x buildFrontend -x installDepsForUI
cd ../uni-halo && npm install
```

之后用 HBuilderX 跑一次小程序，能出首页即算环境就绪。

### 1.7 已知环境注意事项

- 仓库**不含** `plugin-uni-halo/.tools/jdk-17/`（836MB 便携 JDK，已 gitignore）。原开发机靠它编译；新设备自行装 JDK 17 并设 `JAVA_HOME`。
- **`github.com` 在原开发机不可达**，`registry.npmmirror.com` 与 `gitee.com` 正常。插件 `origin` 指向 GitHub，`git push` 失败时改用 Gitee 镜像或本地远端。
- **npm 缓存目录权限**：若 `npm config get cache` 指向不可写的 `_logs` 目录，`gradlew` 的 `pnpmSetup` 会失败。执行 `npm config set cache <可写目录>`，或给 pnpm 加 `--registry=https://registry.npmmirror.com`。
- 本仓库是工作区快照：`uni-halo/` 与 `plugin-uni-halo/` 各自在本地另有独立 `.git`，clone 本仓库得到的是源码副本，子目录的独立 git 历史不在本仓库内。

### 1.8 Git 双远端（同时推 Gitee 和 GitHub）

编辑 `.git/config`，把单个 `origin` 换成两个命名远端：

```ini
[remote "github"]
	url = https://github.com/<你的账号>/<repo>.git
	fetch = +refs/heads/*:refs/remotes/github/*
[remote "gitee"]
	url = https://gitee.com/<你的账号>/<repo>.git
	fetch = +refs/heads/*:refs/remotes/gitee/*
```

之后 `git push github main` / `git push gitee main` 分别推。

---

## 2. 出包与上传（体积问题必读）

- **上传源只能是 `unpackage/dist/build/mp-weixin`。** 微信开发者工具的「上传」永远用**当前窗口打开的那个目录**：在 `dist/dev/mp-weixin` 窗口点上传必然报超限（dev 产物未压缩，工具按约 0.65 样式压缩比、整体约 2.1 倍折算），且详情里**没有分包一栏**。
- 自查上传源：`%LOCALAPPDATA%\微信开发者工具\User Data\<hash>\WeappLog\logs\*.log` 搜 `DevtoolsFileWatcher`（当前目录）和 `main package source size`。用砍掉的 KB 数与实掉数是否吻合可反推上传源。
- 版本号两套**互相独立**：小程序读 `uni-halo/manifest.json` 的 `versionName`（`versionCode` = 主×100 + 次×10 + 修），插件 jar 版本在 `plugin-uni-halo/gradle.properties`。
- 主包上限 2048KB。最近一次已知读数：**主包 1622KB（79%）/ pagesA 633KB**（按 4.0.2 上传成功）。加主包内容前先考虑把仅子包使用的组件下沉。
- `一键发版上传.bat` 三条硬约束：文件必须 **GBK + CRLF**（UTF-8 报 `'ho' is not recognized`，只有 LF 会把一行从中间劈开）；**禁用 `setlocal enabledelayedexpansion`**（`cli.bat` 的 `BOOTSTRAP_JS` 含 `!` 会被吃掉）；`if ( ... )` 块内 `echo` 不能出现半角括号；`cli.bat` 会 `chcp 65001`，每次调用后要 `chcp 936` 复原。

---

## 3. 优化分级进度（P0–P3）

P0 体积 / P1 运行时性能 / P2 图片与网络 / P3 架构（事件总线治理、全局混入开销、tab 页 `onShow` 请求去重）均已代码完成并随 `4edfacf` 提交。

**全部未经构建验证**——只有能在 HBuilderX 里出包的人可验。验收清单：

- [ ] 主包 < 2048KB
- [ ] 列表入场动画仍播放（P1 把 `tm-translate` 换成了 CSS 动画）
- [ ] 登录前后、拿到积分后「写文章」入口显隐正确
- [ ] 默认封面图仍每次请求轮换（P2 去掉了 cache-busting 时间戳，改了随机图 API 的语义）
- [ ] 列表封面走 CDN 缩放参数后仍渲染
- [ ] 文章详情 / 瞬间详情的「表情反馈」条现在能显示（P3 修了小程序端组件里写 `onLoad` 不执行的静默失效）

---

## 4. 方案决策索引

每篇一条：做了什么 / 为什么这么定 / 以后改这块会踩的坑 / 落地状态与版本。

### 4.1 支付与权益

#### 虚拟支付合规整改记录
- **做了什么**：按微信《虚拟支付：个人》官方规则切断「现金 → 积分」充值通道，补齐 `payConfig` 后台分组，修复密钥泄露与 3 个资损级支付缺陷。
- **为什么**：官方接入范围明确「只做道具直购，不引入代币等额外功能」，原实现的 现金→积分→抽奖→实物 链路属**强制移除**——积分自此只能靠浏览/评论/签到等行为免费获得，不可现金购买。幸运礼盒的实物奖品（`needsAddress: true`，默认 `mallConfig.luckyEnabled=true`）必须删除或关抽奖，不是可选设计。
- **关键约束与坑**：
  - 个人主体只有 `mode=short_series_goods`（道具直购）合法，无代币/无订阅；月支付限额 10 万元、费率 Android 1% / iOS 12%。官方排除「信息发布平台」，本项目是博客 + UGC，**第一步 MP 后台能否开通必须先确认，否则整套支付白做**（appid `wx510a685c60ccb388`，OfferID / 现网 AppKey 从 MP 后台拿）。
  - `payConfig` 是第 16 个分组：`enabled`(默认 false) / `offerId` / `appKey`(password) / `env`(0=正式，官方口径要求固定填 0) / `zoneId` / `goods[]`(`goodsId/name/price/type/value/style`)；`type` 做成 select 只允许 `dashboard`/`publish_ticket`/`outfit`；`price` 单位为**分**全程不换算，且须与 MP 后台道具价一致（否则 −15013）；道具需创建并**发布**、约 10 分钟生效（否则 −15010 / −15014）。
  - 新增 `payConfig` 会让匿名可访问的 `GET /getConfigs` 把 AppKey 明文下发进小程序本地存储 → 任何人可自算 paySig 伪造下单。必须 `PAY_SECRET_FIELDS={appKey,offerId}` + `stripPaySecrets()`（deepCopy，不污染源对象），只留 `enabled/goods/env/zoneId`。
  - 查单 URL 与签名路径必须同为 `https://api.weixin.qq.com/xpay/query_order`（原 `/wxa/business/xpay/query_order` 与 paySig 路径不一致 → 268490003，发货前验真与 5 分钟兜底查单全部失效、永不发货）；发货判定改显式白名单 `DELIVERABLE_ORDER_STATUSES=Set.of(2,3,4)`（status=8「用户退款完成」原被判已支付 → 退钱仍发货）；发货改「先写幂等流水再改账户」，锁键统一 `outTradeNo`；限流键改 TCP `remoteAddress()`，不再信 `X-Forwarded-For`；待支付订单 15 分钟失效**只在下发时派生、不落库**。
- **状态与版本**：插件 1.0.93 → 1.0.94（202 tests/36 类）。剩余：幂等锚点改 `wx_order_id` 未做；MP 后台开通与小额真单全链路待验。

#### 皮肤包与阅读券实施方案
- **做了什么**：把前端从未渲染的 `theme` 装扮升级成可付费皮肤包（1.0.95）；数据看板降级为纯积分兑换，落地阅读券前后端全链路（1.0.96）。
- **为什么**：皮肤复用现有 `outfit` 道具类型，支付链路零改动、合规最稳。阅读券既不选「每篇一个道具」（MP 后台要逐个创建发布、约 10 分钟生效，无法逐篇运营）也不选「包月订阅」（个人主体无该 mode），只做 `short_series_goods` 通用券。内容拦截弃用「缓冲并改写响应体」（content-length 出错就泄正文），改**短路返回 403**。
- **关键约束与坑**：
  - 两处 `style` 语义完全不同、极易填混：`levelConfig.outfits[].style` = tm-vuetify **主题名**，且必须是**纯色名**并落在 8 套白名单（`light-blue/primary/blue/cyan/green/orange/deep-orange/blue-grey`）——框架只为纯色主题生成 `shadow-{name}-N`，渐变名会让按钮阴影静默失效；`payConfig.goods[].style` = 装扮 **key**。旧渐变值走 `LEGACY_GRADIENT_THEME_MAP` 就近映射，映射目标必须与新默认 style 同值（`#f6d365,#fda085` → `deep-orange` 而非 `orange`）。
  - 未穿戴主题时必须 `setTmVuetifyColor('')` **清空**全局色，不能兜底成 light-blue（`fllowTheme` 默认 true，否则什么都没买的用户升级即变色）；`red/grey/white/blue-grey` 需显式 `:fllow-theme="false"`（共 29 处，检索必须递归三层目录，`pages\*\*.vue` 会漏）。
  - 阅读券契约：字段 `UserPoints.contentTickets`；接口参数一律 **articleName（`metadata.name`）不是 slug**（详情页 10 处跳转都传 name，Filter 必须同口径）；付费标记用 Halo 原生标签，`post.spec.tags` 存的是 Tag 的 name，故按 `paidTagSlug`（默认 `paid`）反查 + 60s TTL 缓存（在拦截热路径上，不缓存每篇都全表扫）；分组 `contentConfig`：`enabled` 默认 **false**、`costTickets`≤0 强制回落 1、`freeForOwner=true`；判定异常/超时 3s 一律 **fail-open** 放行。
  - **付费墙只保护小程序 API，Halo 网页版 permalink 仍可读全文**（已知边界，不是 bug）。
  - 解锁「先写 `ContentUnlock`（`unlockKey=openid:articleName`）占幂等位 → 再扣券 → 扣券失败删记录补偿」；`Mono.empty().then(deduct)` 在 create 冲突时**同样执行** then 分支（曾并发重复扣券），必须 `.map(created → Boolean)` 显式分叉。
  - 动作码三分且前后端双写：`purchase_content_ticket` / `exchange_content_ticket` / `unlock_content`（服务端 `PointsAction` + `utils/points.js` 各一套，漏改就显示成「积分变动」）；积分商城兑换项 `key` 必须**精确等于** `content_ticket`/`publish_ticket` 才真发券（曾「扣积分不发券」）。下架类道具（`dashboard`、`publish_ticket`）的规矩是「配置侧阻断（`setting.yaml` 下拉 + `parseConfig` 白名单 + 前端 `ALLOWED`）+ `grantGoods` 发货分支**保留**」，否则已支付未发货的存量订单永远拿不到权益。
  - ⚠️ 券可累积、跨篇通用，语义接近代币，属**残留合规灰区**，边界取决于审核人员。
- **状态与版本**：皮肤包 ✅、看板降级 ✅、阅读券后端 ✅、阅读券前端 ✅（文档开头「实施状态速览」表里的「前端未实施」已过时，以 7.8 节为准）。插件 1.0.96，249 测试通过；**前端全程未真机验证**。遗留：发布券无消费点、兑换项无「数量」字段（固定发 1 张）。

#### 阅读券前端联调交付说明
- **做了什么**：补齐阅读券前端（`article-detail.vue` 新增 `locked` 加载态 + 解锁卡片、三个接口封装），修复联调发现的 6 个缺陷（含 1 个资损级）。
- **为什么**：后端 1.0.96 已完成但前端未接通、付费墙开关默认关闭；同时把发布券下架——它**无任何消费点**（`momentPublish.js` 只认 personalToken 博主身份，从不查 `publishTickets`）。
- **关键约束与坑**：
  - 🔴 资损级：Filter 契约是「`Mono.empty()` = 放行 / 非空 DTO = 无条件写 403」，而 `resolveLockIfRequired` 对博主本人与已解锁用户都返回非空 DTO（`locked=true, unlocked=true`）→ **花了券仍永远 403**。两类放行改返回 `empty()`，Filter 另加 `unlocked=true` 强制放行闸门（双层防御）。
  - `getPostByName` 失败时会去掉 `Wechat-Session-Id` 降级重试一次；必须先判 `statusCode===403 && data.locked===true` 打 `isContentLocked` 标记并**跳过降级**，否则已解锁用户被当成未登录再次吃 403（死循环）。
  - 本项目约定 `directRequest` 把 HTTP 400 也 `resolve`，解锁失败（券不足/未开启/文章不存在）会被 `.then()` 误报「解锁成功」→ 在 `requestContentApi` 统一转 reject；`getStatus` 需补 `.defaultIfEmpty(readableFallback)`（内部 `fetchPost` 已把失败吞成 empty，`onErrorResume` 永不触发）。
  - 模板顺序与响应式：`locked` 分支必须排在骨架屏（`loading !== 'success'`）**之前**；登录态写 `lockIsLogin` data 快照（写 computed 会永久缓存首值，登录后按钮卡在旧态）；锁定态下 `result` 为 null，`onShareAppMessage`/`onShareTimeline` 需用锁定标题封面兜底；登录成功**不自动扣券**，`pendingLockUnlock` 只重拉锁定态。
  - 接口路径（两端已核对）：`GET /points/content/status?articleName=xxx`（可未登录）、`POST /points/content/unlock`（需登录）、`GET /points/content/tickets`；匿名由 `role-points-anonymous.yaml` 的 `plugins/points` 首段规则覆盖，无需改 RBAC。
- **状态与版本**：插件 1.0.96；代码完成，**待真机验收后才可按 7.9 顺序开启 `contentConfig.enabled`**。最高优先待验项：「文章列表接口是否也返回正文」（本地只有 compileOnly 的 `api-2.20.11.jar`，离线无法确认，必须真机抓包）。

#### 装扮购买链路整合方案
- **做了什么**：1.0.98 补齐现金/积分两条装扮链路的校验、失败兜底与 13 项缺陷修复；1.0.99 做**入口合并**——装扮只在身份中心售卖，卡片上「积分兑换 + 现金直购」双按钮并列。
- **为什么**：两条链路早就写同一个字段 `account.outfitOwned`，整合不是「打通数据」而是消除两套配置源（`payConfig.goods[]` vs `levelConfig.outfits[]`）错配。站长决策：充值页移出装扮且不加引导入口、积分为主现金为次、不支持纯现金装扮。
- **关键约束与坑**：
  - `levelConfig.outfits[].style` 三类语义各不同：头像框 = 样式名、**昵称框 = 前缀文本本身**（`⭐`/`👑`）、主题 = tm-vuetify 主题名。昵称框曾因前端注册表用 `star/crown/fire` 做键、与后台配置契约不一致，导致前缀**永远不渲染**；现解析顺序「注册表命中优先 → 否则透传文本」+ `NICK_FRAME_MAX_LENGTH=6`（UTF-16，与 Java `String.length()` 同）。「前端注册表 ≠ 后台配置契约」这类缺陷本项目已出现第三次，结果都是「付了钱看不到」。
  - 重复购买卡单：`grantOutfit` 已拥有分支在幂等流水写入**之前**抛异常 → 订单永久 pending + 每 5 分钟兜底查单无限重试。改抛 `NonDeliverableOrderException` → 订单置 `needs_refund` 终态 + ERROR 日志 + 后台人工退款清单页 `{站点}/apis/api.uni.uhalo.pro/v1alpha1/plugins/plugin-uni-halo/refund-admin`（Personal Token 鉴权，展示微信平台单号）。**不做自动退款**：个人主体退款接口可用性未核实，别把不确定接口写进资金链路。
  - 响应式链里禁 `.block()`（事件循环线程直接抛 `IllegalStateException`，被外层 `catch(Exception)` 吞成「按永久处理」→ 站长配了限时实际发永久）。时效由 `OwnedOutfitParser` 对象数组 + 惰性迁移实现，旧纯 key 数组按永久。
  - 小程序与插件**独立发版** → 状态字段只新增不改形状：`OutfitStatusDTO.owned` 保持 key 列表，新增 `ownedDetails[{key,expireAt,permanent}]` 与 `orphanOwned`（孤儿已购 key 单独列「已下架装扮」，**不自动移除**，那是用户已购资产）；旧插件不下发时前端回落「永久」/空数组。
  - 身份页现金价在前端用 `/pay/config` 的 `goods[]` 建 `装扮key → {goodsId, price}` 映射，**无需后端新增接口**；`fnLoadCashGoods()` 刻意不放进 `Promise.all`（旧插件 404 / 未开支付不能拖垮装扮列表）。新增 `utils/virtualPay.js`（纯支付原语，不含 UI）与 `utils/ownOutfit.js`（storage + 5 分钟 TTL + 登出清理，服务端权威、先渲染后纠正）；`uni storage` 非响应式，必须写进 `data` 而非 `computed`。
- **状态与版本**：1.0.98、1.0.99 已实施并构建（270 测试全绿）；**前端未经真机验证**。明确不做：海报 canvas / 瞬间 / 点赞墙 / 邀请页等他人可见点位、自动退款。

### 4.2 AI 内容与外发

#### AI 日报分类可配置方案
- **做了什么**：把日报分类从硬编码六类改为站长可配清单（`digestCategories` repeater + `categoryMode` + `unknownCategoryPolicy`），配套三种落库方式与自动分类清理接口。
- **为什么**：改造前清单写死在 `DigestAiHelper` / `DigestRenderer` / `PostClassifyPromptHelper` 三处，分类页永远只有那六类，且日报自动创建的分类混进站长的体系里删不掉。
- **关键约束与坑**：单一事实来源 `DigestCategoryCatalog`，四条链路都从它取名/图标/说明，改一处全链路生效；清单留空 = 升级前内置六类行为，零迁移；key 只允许 `[a-z0-9_-]`、最多 12 条，消毒后为空即回落内置；`reuse`/`map` 匹配失败是**静默跳过**（只留日志），靠 `GET /digest/categories` 面板与发布自检列问题行；`create` 模式命中的已有分类不改动，新建的打 `uni.uhalo.pro/auto-generated` 标签，`DELETE /digest/categories/auto-created` 只清无文章引用的自动分类；`DigestPreferenceMatcher` / `/digest/my` 旧链路刻意不动。
- **状态与版本**：插件 1.0.155 起，已实施（620 项测试，含 `DigestCategoryCatalogTest` 与三模式/留空回归）。

#### AI 日报多模型通道配置方案
- **做了什么**：主/兜底两条硬编码通道改为可增删排序的模型清单（`DigestAiProviderDTO` + `aiProviders`，上限 10 条），叠加连通性测试、按任务绑定用途（`DigestAiTask`）、token 花费统计与供应商预设/地址纠偏。
- **为什么**：加第三条通道要改代码、没法关掉某一条、配错要等整轮日报跑完才在日志里看到 401/404；这份配置被日报、旧文归类、付费简报三处共用，改一处三处受益。
- **关键约束与坑**：
  - 只有清单**为空**时才回落旧的 `aiApiBase`/`fallbackApiBase`（老站点零迁移）；清单非空但全部不可用要**明确报错**而非静默降级。配置仍只存 ConfigMap `data.aiDigestConfig` 一个分组，不进 `CommercialConfigChecks.MANAGED_GROUPS`。
  - `PUT /digest/models` 只合并 `aiProviders`（读-改-写 + 乐观锁重试 3 次）；`apiKey` 为空或等于掩码串视为保留原 Key，清空需删整行。`extensions/role-digest-console.yaml` 必须补 `digest/models`、`digest/models/*` 资源。
  - `DigestAiClient.call(config, prompt, task)` 故意不提供 2 参重载——漏改调用点必须**编译失败**，而不是悄悄用评分的便宜模型写正文。`PostTaxonomyService` 的 `aiReady` 判断要换成 `resolver.hasUsableChannel`，否则归类页一直误显示「没配密钥」。
  - 连通性测试用极小真实调用（`max_tokens: 8`）而非 `/models`——很多中转不实现 `/models`，会误报。
  - 计价必须逐次在调用那一刻算（日报可能横跨高峰/空闲时段；`DigestAiPeakWindow` 按北京时间工作日判定，节假日按高峰高估）；内置价目表靠 `DigestAiPriceFetcher.parsePricingHtml` 语义标签解析，**失败绝不猜数字**。1.0.219 起思考型模型返回 200 空正文时按 chat/completions 结构判定为连通，`<think>…</think>` 会被剥掉再解析。
  - **思考参数按模型族分档**（`AiThinkingPolicy`，1.0.220 上线、1.0.221 纠正）：GLM-4.5~5.2 发 `thinking.type=disabled`；**GLM-5.3/5.3-Flash 起思考强制开启，发 `disabled` 不是被忽略而是整批 400（业务码 1210）**，改发 `thinking.type=enabled` + 顶层 `reasoning_effort=low`（5.3 系只有 low/high/max 且默认 `max`）；glm-4 那一代与其它供应商不带该字段。思考 token 与正文**共享** `max_tokens`，关不掉的这一档同时把上限抬到 16384（探测请求 8→512），否则成稿 JSON 照样拦腰断。表判错时撞一次 1210 就把该模型记成强制思考并就地重发，跨轮不清空。判错的代价不对称：该关的没关只是多花 token，该发 enabled 的发了 disabled 会让整批成稿塌到 RSS 描述兜底——**表现正是「配了强模型，日报反而更差」**。
- **状态与版本**：1.0.163（清单+测试）→ 1.0.164（按任务绑定）→ 1.0.165（花费统计）→ 1.0.166（官网自动读价）→ 1.0.218（供应商预设与地址纠偏）→ 1.0.219（思考型模型判定）→ 1.0.220/1.0.221（思考参数分档与 GLM-5.3 纠正）→ 1.0.222（智谱价目表、缓存命中计价与余额口径），均已实施。

#### 智谱价目表与余额口径方案
- **做了什么**：按 [docs.bigmodel.cn/cn/guide/start/pricing](https://docs.bigmodel.cn/cn/guide/start/pricing) 把智谱接入「价格说明 + 费用计算 + 余额显示」三处——内置表从 2 条扩到 8 条（新增 6 条 GLM 价格）、官网刷新同时读两家、面板显示计价口径说明与两个价目表链接、GLM 通道查余额改为直接回控制台位置。
- **为什么**：用 GLM 跑日报的站长此前看到的是「算不出来」或按 DeepSeek 口径算出的错数；余额更是每次都发一个注定失败的请求，报「查询失败」，而真实原因不是网络而是智谱根本没有这个接口。
- **关键约束与坑**：
  - **智谱没有峰谷差价**，每条 GLM 内置价必须显式带 `offPeakRatio = 1`（`DigestAiPricing.NO_PEAK_RATIO`）。留空会继承 `DigestAiPriceDTO` 的默认 0.5，那是 DeepSeek 的口径，等于凭空给 GLM 账单打对折——面板上「空闲时段」那一栏对智谱要显示「不分时段」。
  - **内置 DeepSeek 表存的是高峰价**（`0.04/2/8`、`0.3/9/27`）。官网把「空闲时段」排在前面，1.0.222 之前抄的是那一列，所有估算少一半；`DigestAiPeakWindowTest` 现在同时钉住高峰值和「×0.5 推出来的空闲值」，别再改回去。
  - **分档计价（按输入长度分档）的模型取最低档**，日报单篇提示词基本落在 32K 以内；这是有意的低估方向，面板口径说明里写清了。`glm-4.7-flash` 这类「免费」模型内置为 `0/0/0`，让「0 元」和「算不出来」继续是两个不同状态。
  - **缓存命中字段两家不同**：DeepSeek 直接给 `prompt_cache_hit_tokens`/`prompt_cache_miss_tokens`，智谱按 OpenAI 写法只在 `usage.prompt_tokens_details.cached_tokens` 里给。不接这个字段就把命中的那部分按未命中价算——GLM-5.3-Flash 是 0.23 对 0.8，高估三倍多。显式字段优先，缺了才读 `cached_tokens`。思考 token 按输出计价。
  - **智谱价目表页的解析按表头文字定位列**（`缓存命中` / `单价`+`输入` / `单价`+`输出`），不能用列序：同一页还有一张私有化「刊例价」表，列序完全不同、每行价格都在千位级，按列序读会把刊例价当单价。遇到「不支持」的命中列按未命中价计（不假设命中）；同名模型多档只取第一档；模型名带 `<sup>` 脚注要先去掉才能匹配。
  - **余额**：官方确认没有用 API Key 查余额的接口（余额/消费只在控制台「财务总览」，扣费顺序先资源包后现金，GLM Coding Plan 套餐不按量扣余额）。`DigestAiClientImpl.balanceUnsupportedHint` 按 `AiProviderPresets` 识别出智谱通道后**在发请求之前**短路，返回位置与口径说明；这段文字会进 `DigestAiUsageLog.balanceNote`，所以运行报告的估算旁也带着它。不要为此加猜的 URL，也不要用控制台的登录态 Cookie 去抓页面（那等价于交出会话）。
  - **官网刷新改成多源、允许部分失败**（`fetchAllSources`）：一家读失败只记失败原因、另一家照旧写入；两家全失败才报错，且错误信息明说「可继续使用内置价目表，不影响运行」。
- **状态与版本**：1.0.222，已实施（1186 项测试，含智谱真实页面片段解析、时段无关性、`cached_tokens` 兜底与余额短路四条链路）。

#### AI 日报每日分类配额与封面兜底方案
- **做了什么**：精选改两轮制（`minPerCategory` 保底轮 + `topN` 填充轮，受 `maxPerCategory` 约束），候选池按类预留、链接归一化去重、分类缺素材时补抓，并加 Java2D 标题卡片封面兜底（`coverFallbackMode`）。
- **为什么**：原先只有一条「全局按 AI 分数取前 N」，分类只影响正文分组标题；候选池只取全局前 `topN*2`、`requireImage=true` 丢掉约四成无图文章、评分批次失败全塌到「其他」，导致每天篇数完全不可预期。
- **关键约束与坑**：候选池必须**先钉「每类最高分 `max(3, 保底×2)` 篇」**，否则冷门类整类进不了池子、配额再高也选不出东西；兜底分类 `other` 与空串分类**不参与配额**；保底名额优先于 `topN`；补抓失败不阻断本轮（宁少一类不能整期发不出）。封面字体走插件内置子集 `digest/cover-font.otf`（Noto Sans SC 6763 字，约 1.4MB，许可见同目录 `cover-font-LICENSE.txt`）——Halo 精简容器常无中文字体，顺序为「内置 → 系统 CJK → 逻辑字体」，生僻字逐字省略；标题卡片只作封面，`DigestRenderer.appendImage` 会跳过 `placeholderCover` 不在正文重复插图；去重放评分前是为省 AI 调用，`?p=123` 这类有身份的参数刻意保留；`minPerCategory=0` 即回到老行为。
- **状态与版本**：插件 1.0.160 起，已实施（753 项测试，含 `DigestCoverGeneratorTest` 像素比对与断行用例）。

#### 付费 AI 定制内容实施方案
- **做了什么**：新增 `ai_content` 权益商品类型，用户购买后勾选主题/来源/关键词，每天得到一份按自己方向筛出的「我的简报」（`GET/POST /ai/content/preference`、`GET /ai/content/feed`、`GET /ai/content/issues`）。
- **为什么**：选「筛选已发布日报内容 + 每份简报 1 次轻量 AI 导语」而不是重新生成文章，零边际成本可控；与免费「分类订阅」刻意做成两条独立链路，互不影响。
- **关键约束与坑**：`aiContentConfig.enabled` 默认关——关闭时小程序不显示入口、接口一律拒绝、且**禁止发货该类型商品**；商品「道具数量」= 天数，填 0 为永久买断（内部用 `AiContentConfigHelper.PERMANENT_EXPIRE_AT` 远期时间表示），权益凭据 `UserPoints.aiContentExpireAt` 续费叠加；幂等靠 `PointsRecord` `action=purchase_ai_content` + `earnKey=openid:purchase:<outTradeNo>` 恰好一次发放；`AiContentPreference`/`AiContentIssue` 的 `metadata.name` 分别是 `acp-`+sha256(openid)、`aci-`+sha256(openid+":"+date)，一人一天一条既是幂等也是成本阀门；**未开通/读配置或账本失败一律 fail-closed**（与内容拦截的 fail-open 刻意相反，误放的是 AI 成本）；筛选复用 `DigestPreferenceMatcher`（分类权重 3 / 来源 2 / 关键词命中上限 3，「或」口径）；AI 超时或非 JSON 降级为「纯筛选 + 空导语」照常落库；用户不能自填 RSS 源；`goodsId` 必须与微信 MP 后台道具一致；文案避免「订阅/包月」字样。
- **状态与版本**：插件 1.0.156 起，已实施（677 项测试）；小程序端需重新上传。

#### AI 定制内容实时素材与推送修复
- **做了什么**：付费简报素材改为现抓（`liveHours`「实时素材时间窗」默认 48 小时），手动推送与自动推送判定分离并按 outcome 分别给文案，取消「来源」维度，海报重做，生成改为用户触发 + 服务端后台任务 + 进度轮询，并引入「生成次数」计费与后台用量页、用户关键词联网检索。
- **为什么**：来自 2026-10-08 实测四条反馈——推的是旧日报、页面显示「剩余 3 条」却报「额度不足」（判定复用了 `hasQuota` = 额度>0 且总开关开，五种失败原因还共用同一句话）、来源维度无价值、海报简陋。
- **关键约束与坑**：
  - 判「要不要重新生成」只能看 `inFlight` 在途任务，**不能看记录里是否已有内容**，否则「改完关键词再点生成」会被静默跳过（线上「今日简报已是最新」即此因）；结果必须写回那条已存在的 `RUNNING` 记录而不是 create（create 会撞名回读到空内容）；`RUNNING` 超 5 分钟按失败处理，且已有条目/导语时按 `READY` 显示。`DigestAiIssue` 状态机 `NONE → RUNNING → READY/FAILED`，打开页面只读不自动生成。
  - `liu-poster` 只对含 `http` 的地址走 `downloadFile`，相对路径码图会让 `start()` 的 `Promise.all` 永不落地、`showLoading('正在绘制...')` 永不收起——修法是先 `$utils.checkUrl()` 补成绝对地址下载 `tempFilePath`，失败则跳过码图并加 20 秒兜底超时。画布约束：`\n` 不换行（要分行就拆多个 text）、实际绘制在 `y + lineHeight × (行号+1)`、高度按 `lineHeight` 整数倍。
  - 检索命中的原文图必须转存进 Halo 附件库（小程序只加载白名单域名）；联网检索失败/无命中刻意降级为空并回落内置素材池，不阻断生成；历史偏好里的来源一律置空，`maxSources` 已从 `setting.yaml` 移除。
  - 每日次数上限靠新扩展类型 `AiContentGenerationLog`（`dayKey = openid:日期` 带索引）计数，赠送次数续费累加不覆盖、单次上限截断，站长/管理员走 `PrivilegeService` 豁免。
- **状态与版本**：插件 1.0.196，已实施，无数据迁移（新增字段均可空）。

#### 微信公众号同步方案
- **做了什么**：AI 日报链路改成「按方向各出一篇公众号稿」（默认 AI 发展 / AI 安全 / Vibe Coding，最多 6 个），带标题卡片配图，推微信草稿箱或「复制正文」手动发布，支持当天全方向批量推送与历史日报补推。
- **为什么**：公众号侧要逐方向独立成篇且不抢站点日报素材（`mp-` 去重命名空间）；官方 2025-07 起 freepublish / mass sendall 仅企业主体已认证可调，所以默认落点降为「只进草稿箱」，并把「复制正文」当作任何账号类型都可用的**主路径**。
- **关键约束与坑**：
  - 摘要必须显式生成：`digest` 上限 120 字，不填时微信自动抓正文前 54 字；另有标题 32 字、作者 16 字、正文 2 万字符（`MpTextUtils`）。
  - 正文外链图会被微信过滤 → 生成阶段预检转 jpg（≤900KB、宽 ≤1080，留 1MB 余量），推送时经 `media/uploadimg` 换 `mmbiz.qpic.cn`；草稿 `thumb_media_id` 必须是永久素材 `material/add_material`，个人订阅号常在此报 48001/48002（改走复制正文 + 手动封面）。
  - 上传 multipart 必须显式 `Content-Length`，否则前置网关空响应体回 HTTP 412（413 才是反代 `client_max_body_size`）；服务器公网 IP 不加进公众号后台 IP 白名单报 40164；**公众号 AppID/AppSecret 与小程序不是同一个**。
  - 扩展字段名禁止叫 `kind`（`AbstractExtension` 保留名，会写 500 `Scheme not found .../ISSUE`），改用 `sourceKind`。护栏是 `HaloSchemeFieldGuardTest`——`PushRecord` 曾因此静默丢推送记录、幂等失效。
- **状态与版本**：插件 1.0.212（1.0.203 首发 → 1.0.204/205/208/211 逐版修复），已实施；免缓存管理页 `{站点}/apis/api.uni.uhalo.pro/v1alpha1/plugins/plugin-uni-halo/mp-admin`，控制台页 `ui/src/views/MpPush.vue`（改 `ui/` 需 `pnpm build`）。

#### 今日头条号（插件后台）方案
- **做了什么**：控制台管理员写稿台——输入关键词一次 AI 产出 3 个标题候选 + 约 1500 字正文 + 3–5 标签 + 花费快照，正文内嵌【配图N】按位生图，一键复制粘进头条编辑器，历史落库可回看。
- **为什么**：产出物发到第三方平台、只服务站长，故**不做**小程序侧额度/积分/权益；不联网检索（成本与延迟最短），改为提示词硬禁编造数据/版本号/事件。
- **关键约束与坑**：
  - 长调用必须「触发即返回 + 轮询」：`POST /toutiao/generate` 立即返回 `{started:true}`，页面每 4 秒轮 `GET /toutiao/status`（网关/nginx 常见 60s 会掐断同步请求）；进度不落库、结果落库，超 10 分钟的 GENERATING 显示「已中断」；同时只允许一个生成任务。
  - AI 用途标识 `toutiao`（`DigestAiTask.TOUTIAO`），前端三处「用途清单」是**手工同步**、漏一处站长就勾不到：`ui/src/views/AiDigest.vue` 的 `taskOptions`、`static/ai-digest-admin.html` 的 `MODEL_TASKS`、`setting.yaml` tasks help。
  - 权限串复用 `plugin:plugin-uni-halo:users:view`（新权限串需用户重新登录才聚合）；接口放行在 `extensions/role-digest-console.yaml`；`toutiaoConfig` 整组**不下发小程序**。字数下限为目标 85%，套话命中 ≥4 处或偏短触发一次修复（写稿 0.8 / 修复 0.9 温度）；配图下限固定 3 张，生图失败用本地文案卡片顶上，生图费用不计入 token 花费。
- **状态与版本**：插件 1.0.217（1.0.215/216 治 AI 味与字数，1.0.217 把图位交给模型），已实施；入口「插件设置 → 今日头条号」+ 控制台 UniHalo → 今日头条号（`/uni-halo/toutiao`），扩展类型 `ToutiaoArticle`，资源名 `tt-<时间戳>-<散列>`。

#### 微信订阅消息推送实施方案
- **做了什么**：小程序订阅消息两条推送线——分类新文章（免费，按「用户+日期」合并成一条）与 AI 简报就绪（付费权益，「今日精选 · N 篇」），额度自记账 + 读文章/点分类静默续额。
- **为什么**：长期订阅仅政务民生/医疗/交通/金融/教育类开放且无线上申请入口 → 用「一次授权发一条 + 用户勾过『总是保持以上选择』后调用不弹窗」做**准长期订阅**；推送阶段用纯筛选算 N（`DigestPreferenceMatcher`），`PushDispatchService` 不依赖 AI 客户端，由构造保证零 AI 成本。
- **关键约束与坑**：
  - 模板 `wTqRnFopbwyBQU_8gJvPdTpOLzS2h9xgikQxcSuUf4k`（`thing4` 标题 / `thing6` 摘要 / `time3` 时间 / `thing7` 来源 / `thing5` 提示）必须先在小程序后台「订阅消息」启用**并绑定服务类目**，否则下发报 43102 一条都发不出去；`thing` 类 20 字超限报 47003。
  - `requestSubscribeMessage` 只能在用户点击等**交互回调**里调用、一次最多 3 个模板 ID；43101 表示用户拒收/无额度需清零；**不得诱导订阅**（「订阅送积分」违规）；「跳转的小程序版本」测体验版时必须选 `trial`，否则点开跳线上正式版——上线前记得改回正式版。
  - 幂等：`PushRecord` 资源名 = `push-<sha256(openid:类型:业务键)>`，每人每天每类最多一条；`PushSubscription` 额度上限 20，整站日上限 500。
  - `access_token` 收口到 `WxAccessTokenProvider`，**`@EnableScheduling` 就挂在这个类上**——删它等于静默关掉插件所有定时任务。
- **状态与版本**：插件 1.0.157 起，1.0.162 加静默续额，已实施（761 项测试）；新增 `utils/subscribeMessage.js` 与授权入口，小程序需重新上传。

### 4.3 分类订阅与「发现」页

这三篇是一条演进线，**今天真正生效的形态是第三篇 + 第四篇**，前两篇是未落地的决策记录，保留只为解释命名由来。

#### 分类订阅与付费专栏方案（未实施，被取代）
- **做了什么**：纯决策文档（明确「未改任何代码」）：定分类订阅的三条推送通道 + 数据模型 + 付费专栏形态与 P1–P4 分期。
- **为什么**：微信长期订阅/限频消息不对个人博客类目开放，一次性订阅「授权一次只发一条」（次数可累计），机制上给不了「订阅后一直推送」，所以**不向用户承诺持续推送**；服务号模板消息需已认证服务号（300 元/年）+ 用户关注，列为 P4 可选。
- **关键约束与坑**：默认策略必须是**每日汇总**而非每篇即推（配额利用率高一个数量级）；付费专栏与阅读券**同构**（一次性买断分类解锁权，含购后新增文章），合规红线是不得做成包月/会员、不得与「关注公众号」捆绑、推送内不夹带营销话术；同一篇可能同时命中「付费标签」与「付费分类」，必须在**同一个** `ContentTicketFilter` 内定序「已购专栏 → 已用券解锁本篇 → 付费分类未购 → 付费标签未购」，否则拦两次扣两次；订阅开关不得依赖微信授权才能生效（诱导授权红线），且订阅开关/消息入口/红点全部受 `auditConfig.auditModeEnabled` 控制并**不发请求**。
- **状态与版本**：📝 2026-09-10 方案待定。落地分片：订阅以 `DigestSubscription`（不是文中新建的 `CategorySubscription`）实现；推送按《微信订阅消息推送实施方案》以 `PushSubscription/PushState/PushRecord` + `pushConfig`、「定时轮询 + 水位推进」（不是本文设想的 `PostPublishedEvent` 事件驱动）落地；**站内消息中心（`pagesA/messages`、`siteNotify`）与付费专栏（`columnConfig`/`ColumnPurchase`/goods type `column`）全仓零实现**。

#### 分类页重构方案（未实施，被取代）
- **做了什么**：诊断分类页「太单一」的三个代码成因，给出 A（视觉分级）/ B（A + 标签云，当时推荐）/ C（页内双 tab）三案。
- **为什么**：「单一」有具体代码成因——无封面分类共用唯一一套写死渐变的 `.cat-bg--fallback`、描述只在 `index === 0` 渲染、卡片完全同构；同时标签这条内容维度在小程序里是**断的**（`pagesA/tags/tags.vue` 只有 14 行占位且无入口，`getTagList` 全项目零调用）。
- **关键约束与坑**：最大技术债是 `list` 与 `list-post` 两套模式共用 `dataList`/`categoryList`/`hasNext`/`loadMoreText`/`loading`，切换靠 `handleInitPage()` 全量重置、`fnGetData()` 里按 `calcShowType === 'list'` 分叉——**加功能极易改坏另一套**；取色必须是 `displayName` 码点求和取模 → 预设色板的**确定性** hash（刷新不变色）；`getTagList` 的 `postCount` / `spec.color` 字段名至今**未实测**，开工第一步必须打一次真实请求再写映射；新增区块必须同样走审核分支（`mockJson.category.list` 没有标签假数据）。
- **状态与版本**：📝 方案待定、未改代码。**整篇被《分类发现页「发现」重构方案》取代**：`pages/tabbar/category/category.vue` 已删除，标签云与 `pagesA/tags/tags.vue` 仍是 14 行占位、`getTagList` 仍零调用。

#### 分类发现页「发现」重构方案
- **做了什么**：把分类页重做成面向**发现**的 `pages/tabbar/discover/discover.vue`（吸顶搜索 + 🔥热门订阅榜 + 三列磁贴 + 订阅管理 + 人气/文章/名称排序），并新增后端「分类订阅热度榜」聚合服务与接口。
- **为什么**：旧页同时干「订阅管理」和「分类浏览」、两件都没干好且共用一份 `dataList`；分类上百个后没有搜索/排序/「别人都在订什么」的信号，一次性全渲染分类墙光是 setData 就卡。热度取站内主动订阅数，**不需要站长手工排序或运营干预**。
- **关键约束与坑**：
  - 热度口径 = **主动订阅了该分类的用户数**：排除从邀请人继承的分类、`enabled=false`、无 openid 的记录。必须复用 `DigestSubscriptionService.ownCategoriesOf()`（含老数据回落到 `categories` 字段）——**首页筛选、推送目标、热度榜三处口径必须同源**，不能各写一套。
  - Halo 只给 openid 建索引、分类存在用户记录的数组里 → **没法按分类查**，唯一路径是 `client.listAll(DigestSubscription.class)` 全表聚合 + 内存累加。缓存是 `volatile` 单值 + 5 分钟 TTL，且**判断必须写在 `Mono.defer` 内**（写外面，同一请求先取两个 Mono 再依次订阅会两次都看到空缓存，缓存白做——单测抓出的坑）；聚合失败不抛错，有旧缓存给旧缓存、否则空榜。
  - `GET /digest/categories/ranking`（公开只读）→ `{items:[{name,label,subscribers}], totalSubscribers, updatedAt, metric:"own"}`，**必须在 `role-digest-anonymous.yaml` 放行，漏了直接 403**。分类清单仍直连 Halo `/apis/api.content.halo.run/v1alpha1/categories`，热度走插件接口，两者**按 `metadata.name` 合并**；磁贴先渲染 24 个再上拉追加；搜索为本地过滤（显示名 + 描述）；热度接口失败 → 隐藏热门榜、磁贴只留篇数。
  - 改名 6 处必须同步：`pages.json`（路径 + 标题 + tabBar 第 2 项）、`utils/tabbar.js` 的 `FALLBACK_TABBAR_ITEMS`、`common/http/interceptors.js` 里分类密码弹窗后的 `reLaunch` 路径、`UniHaloServiceImpl.java` 的 `TabbarPreset`、`extensions/setting.yaml` 底栏开关 label、删除 `category.vue`。**tab key 保持 `category`**（改 key 会让 `showCategory` 开关、本地偏好 `app.tabbar.show.category`、设置页开关全部失配，收益为零），底栏图标沿用 `select_category.png`。
- **状态与版本**：已实施（插件 1.0.161 + 小程序同批，761 项测试通过）。「上限 5」的写死口径随后被下篇扩展；十万级用户时需改分批读或另建计数记录。

#### 分类订阅额度化方案
- **做了什么**：把写死的「最多订 5 个」改成「免费额度 + 积分买额度 + 会员放宽」，首页取数换成一次请求的服务端并集接口，零订阅/游客首页改为热门精选 + 引导条。
- **为什么**：上限是**服务端权威**（`DigestSubscriptionService.sanitize` 超限直接 400，不静默截断），客户端 `maxSubscribe: 5` 只是提前拦，改额度必须两端一起动；订阅数放开后首页「每分类一路 `Promise.all` 并发」会线性增长，必须换取数方式。额度是**容量**不是商品——取消订阅只腾位置、不退还，因此不存在退款链路。否掉了「按分类买断」和「协同过滤/猜你喜欢」。
- **关键约束与坑**：
  - 最大的坑：`effectiveOf()` 把「自己订阅」硬截到 5 个，**首页取数与消息推送都只看它**——只放开保存上限而不改它 = 存进去了但首页不生效。生效集合 = 自己订阅按顺序取前 N + 继承 ≤3（继承不占额度），首页与推送共用同一集合。
  - 契约：`UserPoints.subscribeQuota`（null 视为 0，老记录无需迁移）；新分组 `subscriptionConfig`：`enabled` 默认**关**（没配价不该出现能花积分的入口）、`freeQuota=5`、`maxQuota=10`、`memberMaxQuota=30`、`homeFeedPerCategory=20`、`hotCategoryCount=3`、`homeFeedCacheSeconds=60`；动作码 `subscribe_quota` 前后端**双写**；`load()` 下发 `limits`，客户端不再硬编码上限/价格/暂停列表。⚠️ 文档里 `quotaPrice` 默认 50 **已过期**——现默认 300，且由 `PricingPresets` 按定价档位覆写。
  - 会员到期后超出额度的订阅只是「暂停」（记录与顺序保留，不进生效集合，续费/解锁自动恢复）；读额度失败一律**降级成免费额度**（宁可少给，绝不让用户看到自己订阅「全暂停」）。
  - `POST /digest/subscription/quota` 幂等键 = `openid:subscribe_quota:quota-<买完后总份数>`，流程照抄积分兑换：**先写流水拿到幂等位，才改账户**；余额不足/开关未开/超最大份数一律报错且账户不动。
  - `GET /digest/feed?scope=mine|hot&page&size` 的**分类由服务端解析，不接受客户端传**（否则退化成任意分类的通用查询）；实现按分类并发调 Halo 官方 `categories/{name}/posts`（排序/可见性/已发布由 Halo 保证，不赌数组字段能否当索引）→ 合并去重（置顶优先、时间倒序）→ 短 TTL 缓存 → 按页切片，单分类失败只跳过；两条路由都要在 `role-digest-anonymous.yaml` 放行。前端兜底链：并集失败 → 原「按分类并发」→ 仍无内容 → 站内默认列表，**首页永不白屏**。
- **状态与版本**：已实施，2026-09-28 随插件 1.0.171 落地（`SubscriptionQuotaService`、`HomeFeedService` 均在仓，877 项单测全绿）。本期不做：额度退款与转让、**按分类买断（即付费专栏，仍未落地）**、并集排序可配、暂停状态的消息推送提醒。

### 4.4 底栏与页面结构

#### TabBar 可配置化与微信小店方案
- **做了什么**：插件后台新增 `tabbarConfig` 分组，站长从 7 候选项里配出显示哪 3–5 项（首页/我的锁死）；自绘 `uh-tabbar` 隐藏原生栏接管底栏，新增微信小店内嵌页（`store-home` 组件直接下单）；后又补用户级本地偏好，图库/小店收编为瞬间宿主页的视图。
- **为什么**：原生 `tabBar.list` 编译期静态、最多 5 项、没有「隐藏单项」的 API，配置化只能走 `hideTabBar` + 自绘；小店要「搬功能」不跳出；`enabled=false` 即整体回退原生 5 tab（一键兜底）。
- **关键约束与坑**：
  - `pages.json` 原生 5 项**一行未改**（保 `switchTab`）；iOS 首帧 `hideTabBar` 无效，须 onReady + `items` watch + 200ms×2 重试——已收敛到全局 mixin，新增 tab 页不用再各写。
  - `utils.deepMerge` 对数组是 concat：`DefaultAppConfigs.tabbarConfig.items` 必须留空数组（兜底在 `utils/tabbar.js` 的 `FALLBACK_TABBAR_ITEMS`，读取时按 key 去重），否则本地 + 服务端拼成 10 项双重高亮。
  - 归一化常量有两处要一起改：服务端 `UniHaloServiceImpl.TABBAR_MIN_ITEMS` + 前端 `utils/tabbar.js`；下限 3 上限 5，补齐序「分类/发现 → 瞬间 → 榜单 → 图库 → 小店」、截断逆序；老 jar < 1.0.148 不下发 `candidates[]` 时要隐藏用户自定义入口。
  - `store-home` 需基础库 ≥ 3.5.5、必须 `v-if` 拿到 appid 后再挂载（appid 后到会渲染空值且不可补）、禁改样式/遮挡（底栏用 static 模式）；开通判断分三层：配置（appId 空）→ 环境（端/版本）→ 渲染（`createSelectorQuery` 量高，启发式）；`active=false` 时不探测，否则隐藏态量到 0 会误判「未开通」。
  - 小程序端**父子通信两条禁令**：`$refs.xxx.method()`（ref 名被编译器改写）和 `uni.$emit`（销毁后残留监听报 `fn.apply` undefined）都不可用，只用递增 tick 属性 / `Vue.observable`；页面钩子改名成组件方法必须挪进 `methods`；「我的服务」去重入口按底栏 key 判断而非路径。
  - 配置原本仅冷启动拉取导致「后台改了不生效」，现由 `utils/configRefresh.js#refreshAppConfigs()`（回前台 3 分钟节流 + 首页下拉）刷新；匿名可 `curl …/getConfigs/tabbarConfig` 自查下发项。
- **状态与版本**：P1–P3 及九轮真机修复已实施（插件 1.0.147 归一化 → 1.0.148 用户级偏好），**小店内嵌仍待真机验证**；皮肤需重跑 `tools/gen-skins.cjs`。

#### 瞬间页替换与榜单广场方案
- **做了什么**：tabBar 中间格由「瞬间」换为主包新页「榜单」（`pages/tabbar/rank/rank.vue`，用户榜/文章榜/动态三页签）；后端新增 `GET /rank/users`、`GET /rank/activities`、周榜奖励自动结算与 `rankConfig` 站长开关。
- **为什么**：发瞬间被三重身份校验锁死仅博主可发，普通用户只能围观，黄金位是负贡献；产品缺「用户互见」，榜单用已有积分数据零内容风险补上。v1 的「互动聚合页」因与「我的」页数据重复 + 带登录前置被废弃。
- **关键约束与坑**：
  - `/rank/users`：`type=points|active|invite`、`range=all|week`、`limit≤50`，匿名可读；积分口径用 `PointsRecord` 累计获得而**非** `UserPoints.balance`（兑换掉分会让用户莫名掉名次）；`me` 仅在带 `Wechat-Session-Id` 时返回。
  - 权限靠 `role-rank-anonymous.yaml` 对资源 `plugins/rank` 首段路径自动放行，新增路由无需改权限文件（但上线前须匿名实测 200）。
  - 周榜奖励默认关闭、**只结算上一自然周不补发**，幂等锚点 earnKey `openid:rank_reward:2026-W36`；新动作码 `rank_reward` 需后端 `PointsAction.labelOf` 与前端 `POINTS_ACTION_LABELS` 双侧同步。
  - `rankConfig`：`enabled=false` 不影响 `/rank/articles`；`showScore=false` 时后端把 value 置 0；动态墙 `wallIncludeUpvote` 默认 false（点赞不公开）、浏览永不上墙；头像框/昵称框仅在 `OwnedOutfitParser.isOwnedAndValid` 时下发。
  - 返工教训：`userRankReady` 曾要求 total≥5 才渲染，把真实数据藏了——改为 `items.length>0` 即显示、<5 人补一句提示；进页距上次请求 >60s（与后端缓存同口径）才静默重取。**tabBar 页必须在主包**（pagesA 分包里的投票/任务页无法上 tab）；瞬间页文件与注册保留未删便于回滚，发表入口迁「我的 → 站点功能 → 我的瞬间」仅博主可见；不要反向做用户 UGC 广场（虚拟支付合规 + `momentPublish.js` 写死 `approved:true` 无审核）。首页热榜块与精选分类块已连带移除。
- **状态与版本**：P1/P1.5/P2/P3 全部实施，插件 1.0.101（当时未发布），待真机验收。

#### 我的页改版与抽奖玩法收敛
- **做了什么**：八轮迭代——撤下统计卡与每日翻牌、盲盒卡上「我的」页且可直接开盒（抽共享组件 `lucky-box`）；整页删除「积分兑换」；新增「抽奖记录」页；装扮商店整体迁入权益直购页；修复周签到错乱、身份页白屏、中奖填写页与 1970 时间等缺陷。
- **为什么**：翻牌与幸运礼盒同为「耗积分随机得奖」，只留盲盒一个玩法；两页各写一份开盒逻辑必然漂移，所以组件化。
- **关键约束与坑**：
  - 开盒成功必须 `emitPointsUpdated({balance})` 全局广播，否则身份中心/积分明细残留过期余额。
  - 中奖概率只渲染服务端 `LuckyDrawConfigHelper.withProbabilities` 下发值（与抽取同口径、有测试锁定），**前端不推算**，0/缺失不显示；`lucky-claim.vue` 的 `needForm` 只有 `submitted/shipped` 才收起表单（曾写反导致中奖后无表单可填）。
  - `/points/history` 的 `action` 过滤必须在排序分页**之前**；新增 `totalPoints` 取流水本身合计（站长改价后历史仍准）；老插件忽略 `action`。`/points/draw` 与 `pointsConfig.rules.draw` 刻意保留（删了历史「翻牌」流水文案会退化），停用 = 后台把消耗改 0。
  - 阅读券此前 `getContentTickets` **无任何调用点**（用户以为没到账），现权益直购页徽标展示，`contentTickets:null` 时不渲染以防显示「0 张」；装扮购买/穿戴迁入后 `applyThemeSkin` + `cacheOwnOutfit` 两处副作用必须跟着走；`payConfig.enabled=false` 时不再 `navigateBack`（否则积分兑装扮也进不去）。盲盒无每日次数上限（仅 10 次/分钟限流）——已知风险。
  - 渲染插件下发的 `Instant` 时间字段一律用 `utils/time.js`（<1e12 判秒否则毫秒，解析失败返回 null 显示「—」），**禁用 `| formatTime`**（null/0/秒级会渲染成 1970）；删代码禁用行区间脚本（曾误删整条 `.points-week-row`、留孤立逗号），改完校验四项：标签平衡、括号 + 关键类各定义一次、`this.xxx()` 调用完整性（正则要含 `(?:async\s+)?`）、模板类与样式一致。
- **状态与版本**：八轮全部已实施，插件 1.0.99 → 1.0.100（`/points/history` 扩展），待真机验收。⚠️ **本篇描述的抽奖玩法此后被整体撤下**——`5c15f7d`（插件 1.0.193）移除了盲盒/`lucky-box`/`lucky-claim` 与「积分兑换」页，前端现已搜不到这些组件；本篇仍有价值的部分是那几条通用坑（`emitPointsUpdated` 广播、`/points/history` 的 `action` 过滤顺序、`Instant` 时间渲染与四项删除校验清单）。

#### 我的投稿页功能增强方案
- **做了什么**：投稿管理页从「只能删除」补齐为编辑（复用投稿页 `?name=xxx`）、立即发布/重新发布、撤回发布、stats 数据行（阅读/点赞/评论）、状态筛选 + 标题搜索；插件新增 4 条 `/userPublish` 路由。
- **为什么**：对齐 Halo 后台文章管理能力；待审投稿保存**不自动上线**是为堵「先发无害内容过审、再改成广告」的漏洞；撤回只给直发权限，是因为普通用户撤回后再也发不回来（死路）。
- **关键约束与坑**：
  - Halo 控制台 `PUT /apis/api.console.halo.run/v1alpha1/posts/{name}` 是**整体替换**：必须先读原文、只改本插件字段、整份回传，否则 `content.halo.run/published` 标签、三个快照名、author 被清空、文章从前台消失；slug 不随标题改（永久链接防 404）、owner 不随代改；读不到正文直接报错，**绝不提交空表单**（会清空正文）。
  - 回填读单篇用核心组 `content.halo.run/v1alpha1/posts/{name}`（草稿可读），正文取 `api.console…/head-content` 的 `raw`；公开读接口 `api.content.halo.run` 对未发布文章 404，不可用；发布带 `?headSnapshot=…&async=true`。
  - RBAC：PUT → `update` 动词，`role-user-publish-anonymous.yaml` 动词要给全（缺 update 会 302 到 /login）；Halo 只按第一段路径 `plugins/userPublish` 判资源，新增接口不用改资源清单。
  - 权限矩阵：发布/撤回/保存即生效仅直发（`resolveModeFor` direct/privileged）；普通用户改已发布文章落 head 快照，列表打「有修改待生效」；编辑不再扣分（投稿时已扣）。编辑复用投稿页刻意跳过本机草稿与默认预选分类；列表不做服务端分页（本机筛选，5 页/500 篇封顶）。
- **状态与版本**：已实施，插件 1.0.195，无新增配置项、无数据迁移；**需后台升级 jar 后四条路由才存在**。

### 4.5 外观与账号

#### 暗色模式与切页白闪修复
- **做了什么**：加「跟随系统 / 始终浅色 / 始终深色」外观模式，并修掉切页面时先闪一帧浅色底的问题（四轮迭代，含全站白底区域清扫）。
- **为什么**：白闪是三层叠加——微信在页面**绘制前**按 `pages.json` 静态值画窗口底色与导航栏，旧实现只在 `onShow`（首帧之后）运行时改；三层缺一层就仍闪。强制深色那条路 darkmode 帮不上，只能把同步提前到 `onLoad`。
- **关键约束与坑**：
  - `darkmode` + `themeLocation` 必须写在 **`manifest.json` 的 `mp-weixin` 节点**。写进 `pages.json` 会被 uni-app 丢掉 `darkmode` → 产物 `app.json` 里 `@变量` 无人解析，启动报 `module 'xxx.js' is not defined` / `wx://not-found`，**报错页面和改动无关**。`theme.json` 必须在项目根目录；只有 `navigationBarBackgroundColor/navigationBarTextStyle/backgroundColor/backgroundTextStyle` 与 tabBar 的 `color/selectedColor/backgroundColor/borderStyle` 能用 `@`，`backgroundColorTop/Bottom` 不行。
  - **必须删掉 `page { background-color: $uh-bg }` 静态底色**：皮肤生成器明确跳过 `page` 选择器（拿不到皮肤类前缀），深色下这层永远浅色，是「切页顶部白闪」的真正元凶。底色交给 `backgroundColor: @bgColor` + 运行时按「生效皮肤」调 `uni.setBackgroundColor`，所以**每个皮肤都得配 `bgColor`**。
  - 皮肤生成器只替换编译产物里的字面量：JS 字符串 / `:style` 里的色（`common/markdown/markdown.config.js` 的 `containStyle`/`tagStyle`）只能在 `common/styles/skins.scss`（app.wxss 才命中自定义组件内部节点）用 `.skin-ink` + `!important` 手写覆盖；CSS 里不在 `tools/gen-skins.cjs` 的 `PALETTE` 中的色必须换成 `$uh-*` Token；**改完必须重新编译 + 重跑 `node tools/gen-skins.cjs`**。ink 覆盖 318 → 478 → 563 条，浅色皮肤规则数不动。
  - 存储/同步点：`unihalo_appearance_mode`（同步读，首帧可取）、`unihalo_active_skin`、`unihalo_system_dark`、框架 `setTmVuetifyBlack`。皮肤类必须写进 **pageRoot**（`mixins/index.js` 的 `pageRootClass`，`<tm-app>` 根节点）而不是 `main.js` 包装层的 `rootClass`。`resolveSkinId(皮肤, 模式, 系统深色)` 是纯函数：强制深色 → 墨韵；强制浅色 → 用户皮肤，买的是深色皮肤则回落宣纸白。原生栏 / `setBackgroundColor` / tm 开关统一由 `syncNativeChrome` 驱动，`onLoad`/`onShow`/`setPageSkin`/`onThemeChange`/watch 五个触发点都调它。`syncTmDark` 在 onLoad 阶段 `commit('setTmVuetifyBlack', …)` 会命中 `vu-persist` 的 `store.state[...]` 未定义崩栈，已 try/catch 降级（只丢冷启动一次状态栏染色）。
  - 生成器自身两个缺陷：源声明已带 `!important` 时不能再拼第二个（整条非法会被静默丢弃）；`TEXT_KEEP_TOKENS` 必须把浅底 Token 排除在 `color:` 之外（否则深底深字）。`tabbar.vue` 的 `setTabBarStyle` 取色也必须走「生效皮肤」而非 `activeSkin`。
- **状态与版本**：已实现。**未解决**：发现/榜单/瞬间三页是原生导航栏，「系统浅色 + App 强制深色」下仍有一帧浅色（平台限制，只能改自绘导航栏）。自检：产物 `app.json` 的 `darkmode=True`、`themeLocation=theme.json`。

#### 应用设置跟账号走方案
- **做了什么**：14 个应用设置键（底栏/外观/首页布局/卡片/悬浮按钮/轮播点）上云按账号同步——本地优先 + 冷启动并行拉取 + 改动去抖 500ms 增量上传；顺带修掉热更新清空全部存储、外观模式从不恢复两个 bug。
- **为什么**：设置原只存本机，热更新 `uni.clearStorageSync()` 每次发版会把设置连登录态全清；`restoreAppearance()` 从未被 onLaunch 调用，导致「始终深色」只写不读。否决了「谁时间戳新谁赢」——按字段合并不需要全局比较。
- **关键约束与坑**：
  - Scheme `UserAppSettings`（wxOpenid/payload/revision/updatedAt），资源名 `uas-<sha256(openid)>`，一用户一条；**payload 必须是服务端生成的 JSON 字符串而非 Map**（Halo 对 Object 字段序列化不友好）。
  - `utils/AppSettingsCatalog` 是白名单唯一事实来源：非法值**丢弃**（不回落默认值，否则改掉用户没动过的设置），丢弃键在响应 `rejected` 里回报；`load` 无记录返回 `synced=false`，客户端不得当作「设置被清空」；`merge` 只覆盖请求里出现的键。每次写入用当前白名单重新清洗存量 payload。
  - 写重试覆盖 `Index is building` 与乐观锁 `concurrently` 两类，且是「重读 → 重合并 → 再写」。接口 `GET/POST /app/settings` 为 public，openid **只从 `Wechat-Session-Id` 头取**，权限文件 `role-app-settings-anonymous.yaml`。
  - 客户端 `utils/appSettingsSync.js`：首帧仍同步读本地（store/计算属性不动），待上传键本地优先、400 即丢键防卡死 pull、`applyingRemote` 回声抑制、登录后 `wx_login_status_changed` 补拉一次（5s 去重）；接入点只有三处，**推送不写进 `skin.js`**（会与 sync 成环）；`appearance.mode` 是独立键 `unihalo_appearance_mode`，不在 `APP_GLOBAL_SETTINGS`。
  - `update.js` 的 `clearStorageSync()` 已改保留清单式 `clearVolatileStorage()`（保登录态/设置/外观/`invite.*`/启动页标记）——用保留清单而不是丢弃清单，防新增缓存键漏补。**加 scheme 必须同步改 `UniHaloPluginTest` 里硬编码的类型数**（15 → 16，漏改导致索引建不起来）；Mockito 桩返回的 Flux 在重试时须重订阅，要包 `Flux.defer`。
- **状态与版本**：已实施并全量测试通过（服务端 904 测试，含新增 19 个），插件 1.0.170 → 1.0.171。本期不做跨设备推送实时化，也不同步 `ad`/`barrage` 等无入口的键。

#### 「枫光」默认主题改版（2026-10）

- **结论**：默认外观从「深青绿」换成「枫光」：纸色底 `#F6F3EE`、枫红强调 `#C2410C`（文字/实底）+ `#E2502A`（大色块）、夜墨特色卡 `#1E1B2E`、日光黄奖励 `#FFC53D`。设计稿在 Claude 画布「宥枫璟小程序 UI 改版」（主页面 6 张 + 二级页面 24 张 + 规范 1 张）。
- **默认皮肤**：`resolveSkinId` 的兜底从 `sky` 改为 `paper`（不挂皮肤类 = 基础样式本身 = 枫光）；`THEMES[0]` 新增 `paper/枫光`，其余四套（晴空/东方/极夜/手账）保留为可选主题。
- **改色只改 `uni.scss`**，并同步 `tools/gen-skins.cjs` 的 `PALETTE`（旧青绿值以 `*Prev` 留作映射）。新增 token：`$uh-accent-bright / $uh-reward* / $uh-night* / $uh-blue* / $uh-green* / $uh-radius-xl / $uh-font-num / $uh-shadow-accent`。
- **结构改动**：首页（问候 + 签到入口、四宫格、AI 夜墨卡）、我的（积分签到夜墨卡）、榜单（用户榜前三领奖台）、发现（分类照片墙）、二级页共用 `redesign/maple-sub.scss`（资产/身份/汇总卡夜墨化）。
- **坑**：夜墨卡在其他四套主题下要手动还原卡片底色（各文件末尾的 `.skin-*` 段），否则会出现浅色主题里的深卡；新加元素在重新编译 + 重跑 `gen-skins.cjs` / `gen-page-themes.cjs` 之前，只有手写的 `.skin-ink` 兜底。
- **图库重写**（`components/gallery-view`）：去掉 tm-tabs / tm-flowLayout-custom / k-touch-listen，改为「最新上传 + 相册横滑 + 分组筛选 + 瀑布/按月切换」，相册详情是组件内子视图（宿主页的下拉/触底转发照旧）；点图进自绘大图查看（标题/描述/相册/日期/保存/分享），点大图再交给原生预览缩放。相册封面与张数靠每组一次 `size=1` 请求补齐（最多 12 组），布局偏好存 `uhalo_gallery_layout`。
- **文章分享海报重做**：弃用 `liu-poster`（它 `Promise.all` 并行绘制，图片下载快慢决定叠放顺序，分层效果画不稳），新写 `utils/posterRenderer.js`（先预取全部图片，再按顺序画在同一 context、一次 `draw()`）+ `components/uh-poster-sheet`（三套模板：夜墨封面 / 枫光纸笺 / 金句卡，左右滑切换、按需生成并缓存、记住上次选择；保存相册 + `wx.showShareImageMenu` 发好友）。逻辑尺寸 375×680，导出 ×pixelRatio（≤3）。金句取摘要第一句；**不要用正则后行断言**，iOS 16.4 以下 JSCore 会整模块解析失败。
- **图标**：`static/tabbar/paper_*` 与 `static/ui-icons/*.png`（无后缀版）按 alpha 重新着色为枫红，未动矢量源；以后用 `generate-ui-icons.py` 重生成时要把 `light` 色表改成枫光取值。

### 4.6 架构演进

#### uni-halonew 重建方案
- **做了什么**：在 `F:\developer1\uni-halo-new` 另起工作区，`plugin-uni-halo` 原样复制，前端用 uni-app **Vue 3 + Vite 5 + wot-ui v2** 从零重写全部页面（36 → 41 页 / 约 24.7k 行、23 个自有组件），彻底移除 tm-vuetify 与 Vue 2 运行时。不改本仓 `uni-halo-qh`，不做视觉重设计，验收标准是「还原旧交互」。
- **为什么**：wot-ui v2 只支持 Vue 3，Vue 2 → Vue 3 无法增量升级只能整体重写；因 GitHub 不可达（HTTP 000）否决 clone `wot-starter` 脚手架，改手工脚手架 + npmmirror；否决 TS（34k 行 JS 机械迁移成本翻倍，仅留 `@dcloudio/types`）；否决文件路由（要保留原生 tabBar + `pagesA` 分包，手写 `pages.json` 可 diff 对照）。
- **关键约束与坑**：
  - **版本被 peer 硬钉，别乱升**：`vue 3.4.21`（uni-app 经 `@vue/server-renderer` 钉死）、`pinia 2.1.7`（2.3.x 要求 vue≥3.5.11）、`@dcloudio/* 3.0.0-5020620260917001`、`@wot-ui/ui 2.3.2`、`vite 5.2.8`、`sass 1.77.8`；`.npmrc` 必带 `node-linker=hoisted`，否则 `qs → side-channel` 在 mp-weixin 构建解析失败、`qs.stringify` 端上直接抛错。旧工程根目录 ≈ 新工程 `src/`，`@/xxx` 导入路径原样可用。新旧插件 `pluginId=pro.uhalo.uni` 相同，同挂一个 Halo 会 id/端口冲突。
  - **皮肤必须页面级 `wd-config-provider`**：uni-app 不把 App.vue 模板包在页面外层，App.vue 里的 provider 是死代码；且禁止 `@use '@wot-ui/ui/styles/theme/index.scss'`（会把 `--wot-primary-*` 指回内置蓝）。`utils/themeSkin.js` 的对外 API、`THEME_SKINS` 白名单、`LEGACY_GRADIENT_THEME_MAP` 和**存储键名 `setTmVuetifyColor` 全部保留**——线上两件已售装扮的渐变色值正是 legacy 映射键。
  - `$tm.toast`(56 处) / `$tm.vx`(42 处) 不能照搬：`useToast()/useDialog()` 依赖 setup + 页面显式 `<wd-toast selector>`，故非组件上下文（utils、`common/http/interceptors.js`）与组件内部一律走新增的 `utils/feedback.js`。实查 `points`/`comment` 两个 store 在旧工程**从未被页面调用**（积分真身是 `utils/points.js`）。
  - tm 原子类兼容层 `src/styles/tm-compat.scss` **只能「扫」不能「猜」**：扫模板全量 class(1160) → 到 `tm-vuetify/mian.min.css` 查单类定义 → **合并同名规则**（`.flex-center` 被拆两条，只取第一条会丢对齐）→ 排除 `iconfont`/`icon-*`。但 9 个**主题色类**（`.light-blue`、`shadow-primary-4`…）刻意不入全局——tm 原来按主题动态注入，进全局等于任何皮肤下都显示，是静默视觉变更。
  - Vue 3 运行时静默坑：H5 端 `@dcloudio/uni-h5-vue` 不注入任何 `globalProperties`，`this.$set`(27 处 + mp-html 26 处) 一进文章详情就崩，需补 `$set/$delete` 垫片；mp-html 的 `beforeDestroy` 在 Vue 3 不执行 → observer 不释放、长列表持续泄漏；`liu-poster.vue` 第 384 行有游离 `</script>`（原件就有，Vue 2/webpack 容忍），Vue 3 + Vite 报 `Invalid end tag` 双端构建全挂。**未被引用的组件根本不参与编译，所以「构建绿」不算验证**；`common/http/index.js` 的 `ContentType`/`dataType` 伪头在小程序无跨域一直没暴露，H5 触发 CORS 预检被后端判 403。
  - 两处「按代码原意修好了」= 改了旧行为，需产品确认（已标 FIXME）：`category-detail` 的 `fnInviteCode` 旧版写成顶层选项而非 methods，真机抛错、分享一直坏；`article-detail` 邀请码静默登录传的是 `{v2}` 包装的 `$httpApi`，必抛错被内部 catch 吞掉即从未生效。另有 8 处旧 bug 只留 `// FIXME(port):` 不擅自改。
- **状态与版本**：⚠️ **这篇描述的成果不在本仓库里。** 文档 2026-09-21 写于基线「uni-halo v2.0.10 / 插件 1.0.141 / Halo 2.20.11」，§12 实施记录称 P0–P7 全部完成、浏览器级验收通过、仅剩 P8 真机回归（最终 41 vue / 24715 行、主包 1637K）。但本机上 `F:\developer1\uni-halo-new` **目录并不存在**，本仓除本文档外无任何 wot-ui 引用，`uni-halo/manifest.json` 仍是 `vueVersion:"2"` + `tm-vuetify/`，插件已到 1.0.219（基线早已漂移）。**本仓的 Vue 2 工程是当前唯一可信的落地代码**；若将来真要继续这次重建，必须以本文的坑清单为准重做，并先解决新旧插件同 id 冲突。

---

## 5. 反复出现的坑（跨模块通用红线）

这些不是某一页的事，是同一个错在四五个模块里犯过：

1. **「前端注册表 ≠ 后台配置契约」**：`style` 字段在 `payConfig.goods[]` 里是装扮 key，在 `levelConfig.outfits[]` 里是主题名 / 昵称前缀文本 / 头像框样式名。填错的后果一律是「用户付了钱但界面没变化」。已知同类事故三次（主题渐变值、头像框、昵称框）。
2. **动作码必须双写**：新增积分动作要同时改服务端 `PointsAction.labelOf` 与前端 `utils/points.js` 的 `POINTS_ACTION_LABELS`，漏一边历史流水就显示成「积分变动」，无法对账。
3. **Halo RBAC 只看第一段路径**：`plugins/<资源>` 首段规则覆盖该前缀下所有路由，所以插件新增接口通常不用改 `role-*.yaml`——**但 public 路由必须显式补进对应的 `role-*-anonymous.yaml`，漏了直接 403/302**。上线前用匿名 curl 实测一遍。
4. **幂等一律「先写流水拿到幂等位，才改账户」**。反过来写在扣减之后，并发或重试就重复发放/重复扣减。
5. **失败方向要按成本选**：内容权益判定失败 **fail-open**（误放的是一篇文章），AI 定制内容判定失败 **fail-closed**（误放的是真金白银的 AI 调用成本）。两者刻意相反，别顺手统一。
6. **`uni storage` 不是响应式的**：需要随事件更新的值必须写进 `data` 快照；写进 `computed` 会永久缓存首次结果，表现为「登录后按钮还卡在旧状态」。
7. **小程序端的组件里写页面钩子（`onLoad`/`onUnload`）不会执行**。组件只能用 `created`/`beforeDestroy`，依赖异步 props 的场景用带 `immediate` 的 watcher。
8. **`uni.$off(event)` 不带 handler 会移除全 App 该事件的所有监听**。`$on` 必须绑命名方法、`$off` 必须传同一个函数引用。
9. **tab 页的 `onShow` 是无条件成本**：home / moments / about / discover / rank 整个会话都活着，每次切 tab 都会跑。现在的规矩是每个关注点 30s 窗口 + 事件总线里只「作废窗口」而不是「立刻请求」（事件到达时本页通常在后台），用户主动动作（签到、下拉刷新、登录弹窗成功）走显式 `force`。
10. **`fetchPointsOverview()` 内部自己会 emit `points_updated`**——任何以该事件为触发源的节流都会被它自己击穿，需要 `applying` 标志位护栏。
11. **渲染插件下发的 `Instant` 时间戳一律走 `utils/time.js`**（<1e12 判秒否则毫秒），禁用 `| formatTime`，否则 null/0/秒级都渲染成 1970。
12. **改前端批量删/移代码后跑 `python tools/check-undefined-methods.py`**（exit 0 才算过），它专治「点击才炸」的漏定义。
13. **改插件必升两处版本号**（`gradle.properties` + `plugin.yaml` 的 `spec.version`），否则站长后台看不到升级；小程序版本号只在 `manifest.json` 的 `versionName`，两套数字互相无关。
14. **上传永远看当前开发者工具窗口开的是哪个目录**（见 §2）：`dist/dev` 出包必报超限。

---

## 6. 明确未实施 / 已否决的清单

避免有人以为这些是「忘了做」或「应该补上」：

| 项 | 状态 | 原因 |
| --- | --- | --- |
| 现金 → 积分充值 | **强制移除** | 个人主体虚拟支付官方规则禁止代币类功能 |
| 抽奖 / 盲盒 / 积分兑换玩法 | 曾实施（1.0.99–1.0.100），**已于 1.0.193 整体撤下** | 合规：耗积分随机得奖 + 实物奖品；前端已无 `lucky-box`/`lucky-claim`/积分兑换页 |
| 装扮自动退款 | 不做 | 个人主体退款接口可用性未核实，不放不确定接口进资金链路 |
| 付费专栏（按分类买断，`columnConfig`/`ColumnPurchase`） | 未实施 | 分类订阅额度化明确列为「本期不做」，合规上要走「道具直购」口径 |
| 标签云 / `pagesA/tags/tags.vue` | 14 行占位、无入口，`getTagList` 零调用 | 分类页最终选了「发现」磁贴方案 |
| 站内消息中心（`pagesA/messages`、`siteNotify`） | 全仓零实现 | 推送改走微信订阅消息一套 |
| 服务号模板消息通道 | 未做 | 需已认证服务号（300 元/年）+ 用户关注，非个人主体路径 |
| 现金 → 积分充值 | **强制移除** | 个人主体虚拟支付官方规则禁止代币类功能 |
| 装扮自动退款 | 不做 | 个人主体退款接口可用性未核实，不放不确定接口进资金链路 |
| 发布券（`publish_ticket`）消费点 | 无消费点，仅保留发货分支 | `momentPublish.js` 只认博主身份，从不查 `publishTickets` |
| 激励视频广告 | 未接入 | 变现路线待定 |
| 猜你喜欢 / 协同过滤 | 不做 | 热度榜已用站内主动订阅数，不需运营干预 |

---

## 7. 当前待验收与已知风险

**待真机/构建验收（优先级从高到低）**

1. P0–P3 优化的构建验证（§3 清单六项）——目前所有体积/延迟数字都是推算。
2. 「文章列表接口是否也返回正文」——决定付费墙是否可整体开启，必须真机抓包。
3. 阅读券全链路（先真机验，再按 7.9 顺序开 `contentConfig.enabled`）。
4. 装扮身份页现金直购、榜单页三页签、权益直购页的阅读券徽标。
5. 微信小店内嵌（依赖基础库 ≥ 3.5.5 与店铺开通状态）。
6. 智谱价目表与余额提示（插件 1.0.222）——解析器已拿真实页面（957 KB、32 个模型）离线验过，但「读取官网单价」按钮和余额按钮在真实 Halo 后台的表现还没点过一次；装完 jar 请在 AI 日报设置页各点一次核对显示。

**已知风险与灰区**

- 阅读券可累积、跨篇通用，语义接近代币，属虚拟支付**合规灰区**，取决于审核人员判断。
- 本项目是「博客 + UGC」，MP 后台能否为个人主体开通虚拟支付本身就是前置不确定项。
- 付费墙只挡小程序 API，Halo 网页版 permalink 仍可全文。
- 盲盒无每日次数上限，只有 10 次/分钟限流。
- 「系统浅色 + App 强制深色」在发现/榜单/瞬间三页仍有一帧白闪（原生导航栏，平台限制）。
- `uni-halo-new`（Vue 3 重建）的成果不在本仓，基线已漂移 78 个插件版本，重启需重估。

> 上面这些风险的**处置顺序和是否要做**，在 `uni-halo-next-plan.md`（现状总结 + 待办计划）。本文只记已定的事。
