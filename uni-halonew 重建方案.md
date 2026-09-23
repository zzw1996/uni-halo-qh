# uni-halonew 重建方案（uni-app Vue3 + wot-ui v2）

> 目标：在 `F:\developer1\uni-halo-new` 下新建独立工作区，内含 `plugin-uni-halo`（后端插件，原样迁移）
> 与 `uni-halonew`（前端小程序，用 uni-app + wot-ui v2 重新实现现有全部页面）。
>
> 勘查基线：`F:\developer1\uni-halo-qh`（uni-halo v2.0.10，Vue 2 + tm-vuetify 2.x，HBuilderX 工程）
> 编写时间：2026-09-21

---

## 1. 勘查结论（现状实测数据）

| 项目 | 实测值 | 备注 |
| --- | --- | --- |
| 前端框架 | uni-app **Vue 2**（`manifest.json: vueVersion=2`，`main.js` 用 `new Vue()`） | Vue 3 是 wot-ui v2 的硬前提，必须整体重写 |
| UI 库 | **tm-vuetify 2.x**（`tm-vuetify/` 6.1M / 215 文件，自研 Vuex `$tm.vx`） | 新项目整体移除 |
| 页面文件 | `pages` 7 + `pagesA` 28 + `pagesC` 1 = **36 个页面**，约 **22.4k 行**（`pagesA` 另有 4 个共用组件：`comment-modal`/`comment-list`/`comment-item`/`moment-interaction`） | 子包 `pagesA` 是主战场 |
| 组件 | 自有组件 19 个 + `pagesA/components` 4 个 ≈ **23 个 / 5.6k 行**；`components/mp-html` 为第三方富文本（4k 行，Vue3 可用，原样搬） | 含 `components/mp-html` 合计 174 个 vue |
| 工具层 | `utils/` 33 文件 3.5k 行、`api/v2/all.api.js` 1.9k 行、`common/` 16 文件、`store/` 4 文件、`config/` 3 文件 | 大部分可平移，仅 store 与反馈层需改 |
| tm 组件种类 | **28 种**（`tm-button` 232 次、`tm-skeleton` 222、`tm-empty` 134、`tm-tags` 94…） | 全部有 wot-ui 对应或自研替代 |
| tm 全局 API | `$tm.toast` 56 次、`$tm.vx` 42 次、`$tm.dayjs` 5 次；**27 个文件**涉及 | 迁移重点，见 §6 |
| Vue2 专有写法 | `.sync` 19 处、`$set(` 27 处、`beforeDestroy` 5 处、`mixins:` 7 处、`$refs` 20 处、`this.$http` 127 处、`this.$utils` 58 处 | 全部有确定改法，见 §5 |
| 页面生命周期 | `onLoad` 34、`onPullDownRefresh` 25、`onReachBottom` 14、`onShow` 13、`onShareAppMessage` 6 | 组合式写法需从 `@dcloudio/uni-app` 导入 |
| 依赖 uni_modules | `uni-upgrade-center-app`（App 升级）、`liu-poster`（邀请海报） | 需要 Vue3 版本或替换 |
| 后端 | `plugin-uni-halo` 版本 1.0.141，Halo 插件平台 2.20.11，**工作区有大量未提交改动** | 迁移必须带走工作区状态 |
| 联调后端 | `config/uhalo.config.js → http://117.72.189.216:8090` | 新旧前端对同一后端做对照验收 |

**环境实测**：Node v24.15.0 / npm 11.12.1 / pnpm 8.6.12 / git 2.54。
`registry.npmmirror.com` 与 `gitee.com` 可达；**`github.com` 不可达（HTTP 000）**。
→ 因此**不能靠 clone `wot-starter` 脚手架**（`starter-cleaner` 技能本次不使用），改为**手工搭建等价脚手架 + npmmirror 安装**。
→ 依赖集已实测：`@dcloudio/*@3.0.0-5020620260917001`、`@wot-ui/ui@2.3.2`、`vite@5.2.8`、`sass`、`pinia`、`dayjs`、`mp-html` 共 577 个包，**17 秒安装成功**。
→ `wot` CLI（`@wot-ui/cli`）已实测可用（离线组件知识，识别到 v2.3.2，约 80 个 `wd-*` 组件）。

---

## 2. 目标目录结构

```
F:\developer1\uni-halo-new\
├── plugin-uni-halo\            # 后端插件（自 uni-halo-qh 复制，含 .git 与未提交改动）
│   ├── src\ ...                # Gradle 产物目录不复制
│   └── ui\                     # Halo 控制台前端（源码，不含 node_modules）
├── uni-halonew\                # 新前端：uni-app Vue3 + Vite + wot-ui v2
│   ├── src\
│   │   ├── api\                # 平移 all.api.js（Halo v2 + 插件接口）
│   │   ├── components\         # 自有组件 + mp-html
│   │   ├── config\             # uhalo.config.js（域名）、mock
│   │   ├── hooks\              # 组合式逻辑（替代 mixins）
│   │   ├── pages\              # 主包：首页/分类/榜单/瞬间/图库/我的/启动页
│   │   ├── pagesA\             # 子包：31 个业务页
│   │   ├── pagesC\             # webview
│   │   ├── static\
│   │   ├── store\              # Pinia（替换 $tm.vx）
│   │   ├── styles\             # 主题 SCSS（单文件主题，见 §7.5）
│   │   ├── utils\              # 33 个工具模块（平移 + 适配）
│   │   ├── App.vue
│   │   ├── main.js
│   │   ├── pages.json          # 手写维护（保留原生 tabBar 与分包）
│   │   ├── manifest.json       # 沿用 mp-weixin appid
│   │   └── uni.scss
│   ├── vite.config.js
│   ├── package.json
│   └── .npmrc                  # registry=npmmirror
├── docs\                       # 复制 8 份业务方案 md，作为实现依据（只读参考）
└── README.md                   # 新工作区说明：怎么跑前后端
```

复制 `plugin-uni-halo` 的排除清单（体积从 1.5G 降到 ~8M）：
`.tools\`(836M JDK)、`.gradle\`、`build\`、`build-out\`、`ui\node_modules\`、`ui\dist\`、
`src\main\resources\console\`（构建生成）、`extensions\*.jar`、`hs_err_pid*.log`。
保留 `.git`（2.5M，保留提交历史）与全部未提交改动（`git status` 中 25+ 个 M 文件与 `InviteLandingDTO.java` 等新文件）。

> 注意：新旧插件若同时部署到同一个 Halo，`pluginId = pro.uhalo.uni` 与端口会冲突。本方案只做工作区复制，不同时部署；如需并存，另起插件 id。

---

## 3. 技术选型

| 维度 | 选择 | 理由 |
| --- | --- | --- |
| 框架 | uni-app **Vue 3**（`@dcloudio/*@3.0.0-5020620260917001`）+ Vite 5 | wot-ui v2 只支持 Vue 3 |
| UI 库 | **`@wot-ui/ui` 2.3.2**（npm 安装模式） | 无需 uni_modules，配合 easycom 自动引入 |
| 语言 | **JavaScript + `<script setup>`**（推荐） | 待迁移 34k 行 JS；引入 TS 会让机械迁移成本翻倍。TS 仅作为工具链（`@dcloudio/types`）保留；将来可增量 .ts 化 |
| 状态 | **Pinia** | 替换 `$tm.vx`（tm-vuetify 自带的迷你 Vuex）+ `store/*` 的 Vuex 形状模块，模块名沿用 `config/points/setting/comment` |
| 请求 | 沿用 `js_sdk/luch-request`（框架无关） | `common/http`、`api/v2/all.api.js` 几乎零改动 |
| 路由 | **手写 `pages.json`**（不用 vite-plugin-uni-pages 文件路由） | 需保留 4 个 tabBar 与 `pagesA` 分包配置，手写更可控、可 diff 对照 |
| 样式 | `sass` + wot-ui 主题变量（CSS 变量）+ 单文件主题 SCSS | 见 §7.5，`create-wot-ui-theme` 技能落地 |
| 包管理 | pnpm（已装 8.6.12），`.npmrc` 指向 npmmirror | GitHub 不可达，一律走镜像 |
| 目标平台 | **微信小程序为主** + H5 开发调试 | 现有能力集中在 mp-weixin（wx 登录/虚拟支付/分享） |

---

## 4. tm-vuetify → wot-ui 组件映射

已用 `wot list` 核对 wot-ui v2.3.2 实际组件清单，映射如下（左侧为现有用法次数）：

| 现有（次数） | 新方案 | 说明 |
| --- | --- | --- |
| `tm-button` 232 | `wd-button` | 直接替换，注意 `type/plain/block` 取值差异 |
| `tm-skeleton` 222 | `wd-skeleton` | wot-ui 支持多主题/自定义行列 |
| `tm-empty` 134 | `wd-empty` | 支持自定义图片与文案 |
| `tm-tags` 94 | `wd-tag` | 现有「标签选择」交互用 `wd-tag` + 自写选中态 |
| `tm-translate` 54 | `wd-transition` | 进出场动画 |
| `tm-flotbutton` 54 | `wd-fab` | 悬浮动作按钮（wot-ui 有 Fab） |
| `tm-icons` 44 | `wd-icon` | 图标名体系需重映射（见风险 R4） |
| `tm-switch` 19 | `wd-switch` | — |
| `tm-poup` 18 | `wd-popup` | `v-model:visible` |
| `tm-input` 16 | `wd-input` / `wd-textarea` | — |
| `tm-tabs` 14 | `wd-tabs` | 弹层内需 `updateLineStyle()` |
| `tm-listitem` 14 | `wd-cell` / `wd-cell-group` | 设置页、入口列表 |
| `tm-sheet` 10 | `wd-card` / 自写容器 | — |
| `tm-groupradio` 9 | `wd-radio` 组 / `wd-select-picker` | — |
| `tm-search` 8 | `wd-search` | — |
| `tm-pickers` 8 | `wd-picker` / `wd-datetime-picker` / `wd-select-picker` | — |
| `tm-loadding` 8 | `wd-loading` / `wd-loadmore` | — |
| `tm-form` 8 | `wd-form` + `wd-form-item` | 校验体系整体换成 wot-ui |
| `tm-radio`/`tm-checkbox`/`tm-groupcheckbox` 9 | `wd-radio`/`wd-checkbox` | — |
| `tm-images` 6 | `wd-img` + `useImagePreview()` | 预览能力用 hook |
| `tm-menubars` 5 | `wd-navbar` + 自写快捷菜单 | 自定义导航页（首页/我的） |
| `tm-stepper` 4 | `wd-input-number` | — |
| `tm-slider` 4 | `wd-slider` | — |
| `tm-rate` 4 | `wd-rate` | — |
| `tm-timeline*` 12 | `wd-steps` | 时间轴类展示 |
| `tm-grid` 3 | `wd-grid` / `wd-layout` | — |
| `tm-drop` 4 | `wd-drop-menu` | — |
| `tm-alerts` 4 | `wd-notice-bar` / `wd-curtain`（图片公告弹窗） | 公告弹窗场景 `wd-curtain` 更贴 |
| `tm-flow` 4 | `wd-steps` + 自写连线 | 无直接等价，样式自绘 |
| `tm-more` / `tm-timeline-jidian` 等 | 自写小组件 | 低频装饰性组件 |
| 其余能力 | `wd-toast`/`wd-dialog`/`wd-notify` + `useToast()`/`useDialog()`/`useNotify()`、`useImagePreview()`、`useVideoPreview()`、`wd-backtop`、`wd-sticky`、`wd-swipe-action`、`wd-qr-code`（邀请码/海报）、`wd-watermark`、`wd-table`（数据看板）、`wd-count-to`/`wd-count-down` | 见 §7.4 |

**保留自研**（wot-ui 无对应或业务强耦合，样式内部改用 wd-*）：`e-swiper`（可换 `wd-swiper`）、`article-card`、`article-min-card`、`category-mini-card`、`vote-card`、`article-vote`、`article-douban`、`barrage`（弹幕）、`lucky-box`（九宫格抽奖）、`heatmap`（热力图）、`cache-image`、`points-toast`、`notify-dialog`、`plugin-unavailable`、`restrict-read-skeleton`、`content-reaction-bar`、`scroll-btn`、`wave`、`wx-login-dialog`、`comment-item/list/modal`、`moment-interaction`、`mp-html`（原样搬）。

---

## 5. Vue 2 → Vue 3 语法迁移清单（含实测处数）

| 现状写法 | 处数 | 新写法 |
| --- | --- | --- |
| `.sync` | 19 | `v-model:xxx` |
| `this.$set(obj, k, v)` | 27 | Vue 3 响应式直接赋值 `obj[k] = v` |
| `beforeDestroy` | 5 | `onBeforeUnmount` / `onUnmounted` |
| `mixins:` | 7 | 组合式函数（`hooks/useXxx.js`） |
| `this.$http` | 127 | 顶层 `import { http } from '@/common/http'`，或 `request.js` 的 Get/Post 包装 |
| `this.$utils` | 58 | 直接 `import utils from '@/utils'`（页面里按需 import 具名函数优先） |
| `this.$refs` | 20 | `ref()` + 模板 ref（`<wd-button ref="btn">`） |
| `this.$baseApiUrl` | 6 | `import UHaloBaseConfig from '@/config/uhalo.config.js'` |
| 全局混入（`haloConfig`/`globalAppSettings` 等 computed） | 1 处全局 | `useAppSettings()` composable + Pinia getter，页面按需调用 |
| `onLoad/onShow/onReachBottom/onPullDownRefresh/onShareAppMessage/onShareTimeline` | 34/13/14/25/6 | 从 `@dcloudio/uni-app` 具名导入后调用 |
| `App.vue` 的 `globalData` | 1 | Pinia store（`momentListNeedRefresh` 等改 store 字段） |
| `uni.$on/$emit` 事件总线 | 12/5 | uni API 在 Vue3 下仍可用，原样保留（注意 `uni.$once`、解绑时机） |
| filters | 0 | 无需处理（现状未使用） |

**无破坏性项**：无 `slot-scope`、无 `$listeners`、无 `$children`、无 `Vue.filter`、无 Vuex 依赖（store 走 `$tm.vx`），迁移风险主要集中在 §6。

---

## 6. 三处「非机械」改造（重点）

### 6.1 全局反馈层（`$tm.toast` 56 次 / `$tm.dayjs` 5 次）
wot-ui 的 `useToast()`/`useDialog()` 依赖 `provide/inject`，**只能在 setup 内调用且页面要显式声明 `wd-toast` 实例**；而现有 56 处 `$tm.toast` 有相当一部分在 `utils/`、`common/http/interceptors.js` 这类非组件上下文里。
方案：
- 新增 `uni-halonew/src/utils/feedback.js`：用 `uni.showToast/showModal/showLoading/hideLoading` 实现 `toast()/confirm()/loading()/closeLoading()`，供工具层、拦截器直接 import（零组件上下文依赖）。
- 页面内需要富交互（带图标/多态）时用 `useToast()`/`useDialog()`，并在该页模板声明 `<wd-toast />`、`<wd-dialog />`（多实例用 `selector` 区分）。
- `$tm.dayjs` → 直接 `import dayjs from 'dayjs'`（保留同一套格式化行为）。

### 6.2 状态层（`$tm.vx` 42 次，`store/*` 4 个模块）
现状：`store/config.js`、`points.js`、`setting.js`、`comment.js` 是 Vuex 形状模块，但实际由 tm-vuetify 的 `$tm.vx` 承载（`uni.$tm.vx.getters()/actions()/commit()/state`）。
方案：改写为 Pinia（模块名与 getter/action 语义一一对应，降低调用点改动量）：
- `useConfigStore` ← `config`（含 `fetchConfigs`、`fetchMockJson`、`setMockJson`）
- `useSettingStore` ← `setting`（`checkAndSetDefaultAppSettings`、`updateDefaultAppSettings`）
- `usePointsStore` ← `points`
- `useCommentStore` ← `comment`
- `utils/themeSkin.js` 里 `setTmVuetifyColor` 调用改为 `useThemeStore().applySkin(...)`（见 §7.5）
页面里 `this.$tm.vx.getters().getConfigs` → `const configStore = useConfigStore()`（或 `storeToRefs`）。

### 6.3 主题皮肤（已购装扮必须不失效）
现状：`utils/themeSkin.js` 把「装扮 style」映射到 **tm-vuetify 的 8 个主题色名**（`light-blue`/`primary`/`deep-orange`…），再靠 `$tm.vx.commit('setTmVuetifyColor')` 全局换色 + tabBar 选中色。
新方案（配合 `create-wot-ui-theme` 技能）：
- 新建 `src/styles/themes/{light-blue,primary,blue,cyan,green,orange,deep-orange,blue-grey}.scss`，用 wot-ui 语义变量（`--wot-color-theme` 等）覆写主题色；`App.vue` 只 `@use` 一个入口。
- 运行时用 `wd-config-provider :theme-vars` 动态注入 + `uni.setTabBarStyle({ selectedColor })` 同步 tabBar。
- 保留 `THEME_SKINS` 白名单与 `LEGACY_GRADIENT_THEME_MAP`（历史渐变值映射），保证已购用户的 `activeTheme` 仍生效。
- 服务端 `activeTheme` 仍为权威来源：启动用本地缓存先渲染，进入「我的/身份中心」时纠正。

---

## 7. 架构落地要点

### 7.1 脚手架（手工搭建，不依赖 GitHub）
`package.json`（已实测可装）+ `vite.config.js`（`@dcloudio/vite-plugin-uni`）+ `src/main.js`（`createSSRApp`）+ `src/App.vue` + `pages.json`/`manifest.json`/`uni.scss` + `.npmrc`。

wot-ui easycom（npm 模式，写入 `pages.json`）：
```json
"easycom": {
  "autoscan": true,
  "custom": { "^wd-(.*)": "@wot-ui/ui/components/wd-$1/wd-$1.vue" }
}
```
`manifest.json`：`vueVersion:"3"`，沿用现有 `mp-weixin.appid = wx510a685c60ccb388`，`name` 改为 `uni-halonew`（UI 标题沿用「宥枫璟」）。

### 7.2 主包与分包（沿用现有 39 页）
- 主包 7：`index/index`（启动）、`tabbar/home`、`tabbar/category`、`tabbar/gallery`、`tabbar/rank`、`tabbar/moments`、`tabbar/about`
- 原生 tabBar 4 项：首页 / 分类 / 榜单 / 我的（图标沿用 `static/tabbar/*`，选中色由主题皮肤驱动）
- 子包 `pagesA` 31 页：内容类、瞬间类、投票类、积分/商业化类、设置类（清单见 §8 批次）
- 子包 `pagesC` 1 页：`website`（webview）
- 注：`pagesA/invite-landing` 是当前**未提交**的新页面，属于本次实现范围。

### 7.3 请求层
`js_sdk/luch-request` + `common/http/{index,request,interceptors}.js` + `api/v2/all.api.js` 原样搬移，仅改两处：
- `interceptors.js` 中若引用 `$tm.*` 改为 `utils/feedback.js`。
- 去掉 `Vue.prototype.$http/$httpApi`，改用 `import { http }` / `import request from '@/common/http/request'`，`api` 模块内部自闭环。
- `api` 层另有 `plugin.data-statistics.js`（数据看板）一并搬。

### 7.4 反馈与弹层规范（wot-ui 约定）
- 页面必须声明所需反馈组件实例（`wd-toast`/`wd-dialog`/`wd-notify`），多处使用加 `selector`。
- `wd-popup`/`wd-action-sheet`/`wd-drop-menu` 内的 `wd-slider`/`wd-tabs` 打开后调用 `initSlider()`/`updateLineStyle()`。
- 自定义组件内要覆盖 wot-ui 样式时，该组件设 `styleIsolation: 'shared'`。
- 全局统一：把现有 `uni.$eShowModal`（`js_sdk/fy-showModal`）替换为 `useDialog()` 或 `utils/feedback.js` 的 `confirm()`，统一交互。

### 7.5 平台能力
| 能力 | 现有实现 | 新方案 |
| --- | --- | --- |
| 微信登录 | `utils/wxLogin.js`（329 行）+ `components/wx-login-dialog`（774 行） | 逻辑平移，弹层用 `wd-dialog`/`wd-popup` 重写外观 |
| 虚拟支付 | `utils/virtualPay.js` | 逻辑平移（平台 API 不变），失败态提示改用 `feedback.js` |
| 分享/朋友圈 | `onShareAppMessage` 6 处 | 逻辑平移，组合式写法 |
| 邀请海报 | `uni_modules/liu-poster` + `utils/...` | 首选 Vue3 版 `liu-poster`；不可用则用 `wd-qr-code`（生成邀请码）+ `canvas` 自绘海报 |
| App 升级 | `uni_modules/uni-upgrade-center-app` | 仅 `#ifdef APP-PLUS` 保留；本次不作为目标平台，先保留占位 |
| 富文本 | `components/mp-html`（15 处） | 原样搬入（Vue3 兼容），含图标字体与 `static/app-plus` |
| 图表/数据看板 | `pagesA/data-visual` 自绘 | 用 `wd-table`/`wd-progress`/自绘（现有无第三方图表依赖，无需引入 echarts） |
| 热力图/弹幕/抽奖 | `heatmap`/`barrage`/`lucky-box` 自研 | 容器与动画保留，内部按钮/标签换 wd-* |

---

## 8. 实施批次（每批可独立验收）

| 批次 | 内容 | 交付物 | 验收 |
| --- | --- | --- | --- |
| **P0 工作区** | 建 `uni-halo-new`；复制 `plugin-uni-halo`（排除构建产物，含未提交改动）；复制 8 份方案 md 到 `docs/`；写 README | 目录就绪 | `./gradlew build` 在原插件目录行为不变；新副本 `git status` 与原一致 |
| **P1 脚手架** | `uni-halonew` 工程：package.json/vite/pages.json(36 页 + tabBar + easycom)/manifest/App.vue/main.js/主题入口；`pnpm i` 通过 | 空壳可编译 | `pnpm dev:h5` 与 `pnpm dev:mp-weixin` 均能跑起「首页占位」 |
| **P2 基础设施** | 请求层、api 层、`utils/*` 33 个模块、Pinia 4 store、`feedback.js`、`useAppSettings`、主题皮肤 8 套 SCSS、登录态/积分同步 | 无 UI 的地基 | H5 下能拉通 `fetchConfigs`、登录态、积分同步；主题切换改变全局主色与 tabBar |
| **P3 通用组件** | 23 个自有组件 + mp-html 搬入并按 wot-ui 重写外观；`hooks/` 补齐 | 组件库 | 组件在 H5/微信双端渲染正常，`article-card` 等以「组件预览页」验证 |
| **P4 主包页面** | home(1204)、category(980)、rank(1094)、moments(916)、gallery(400)、about/我的(2280)、index(196) | 7 页 | 逐页对照旧版：布局、分页、下拉刷新、下拉加载、tabBar 切换、弹层 |
| **P5 内容类子页** | article-detail(2416)、articles(244)、category-detail(185)、tag-detail(155)、tags(14)、archives(600)、friend-links(426)、about(99)、disclaimers(48)、website(30) | 10 页 | 富文本渲染、评论、点赞/收藏、限制阅读、订阅筛选 |
| **P6 瞬间/投票类** | moments 详情/发布、moment-detail(352)、moment-publish(400)、comment-modal/list/item(835)、moment-interaction(712)、votes(408)、vote-detail(737)、article-vote、vote-card | 8 页 + 5 组件 | 发布走通、投票/计票、互动与通知 |
| **P7 积分/商业化** | points-history(288)、points-recharge(1467)、my-orders(410)、unlocked-articles(286)、lucky-records(452)、lucky-claim(356)、task-center(406)、invite(1105)、invite-landing(500)、identity(624)、toolbox(171)、data-visual(390)、setting(383)、user-activities(207)、start(600) | 15 页 | 兑换、支付、抽奖、邀请、任务、皮肤包购买链路对账（金额/积分不变） |
| **P8 平台与收尾** | wx 登录弹窗、虚拟支付、分享、海报、埋点/错误兜底、真机回归 | 全量 | 微信开发者工具 + 真机全量走查；`pnpm build:mp-weixin` 产物提交体验版 |

> 页面行数即工作量分布：`article-detail`、`about`、`points-recharge`、`invite`、`home` 是 5 个「大件」，建议各单独排期，不要与其它页混在一批。

---

## 9. 风险与对策

| 编号 | 风险 | 影响 | 对策 |
| --- | --- | --- | --- |
| R1 | GitHub 不可达，无法直接使用 `wot-starter` 模板 | 脚手架需自建 | 已实测依赖集可从 npmmirror 安装；脚手架 10 个文件手工产出（P1） |
| R2 | Vue2→Vue3 全量重写，回归面大 | 漏改导致白屏/报错 | 用 §5 清单逐项扫；每批页面双端跑通后合并；`pnpm dev:h5` 做快速回归 |
| R3 | `useToast/useDialog` 需组件实例，56 处 `$tm.toast` 有工具层调用 | 提示丢失 | `utils/feedback.js` 用 uni API 兜底（§6.1） |
| R4 | tm-icons/iconfont → `wd-icon` 图标名体系不同 | 图标缺失 | 先建映射表（`common/icons/*` 自研字体图标可继续用 `<text class="iconfont">` 或 `wd-icon custom-class`），逐页比对补全 |
| R5 | 主题皮肤原来绑 tm-vuetify 主题名 | 已购装扮失效/客诉 | 保留白名单与 legacy 映射，8 套主题 SCSS 覆盖同一组色值；联调新老客户端同账号对照 |
| R6 | `liu-poster`、`uni-upgrade-center-app` 的 Vue3 兼容性未知 | 海报/升级功能不可用 | 先验证；海报不可用则 `wd-qr-code` + canvas 自绘；升级中心仅 App 端、本次不阻塞 |
| R7 | 微信虚拟支付/登录需真实 appid 与后台配置 | 无法端到端验证 | 沿用 `wx510a685c60ccb388`，用体验版真机验证；支付链路保留灰度开关 |
| R8 | 分包体积与首屏 | 小程序包体积超限 | `pagesA` 继续走分包；`mp-html` 与海报组件按需引入；构建后检查主包体积 |
| R9 | 后端有未提交改动，前后端契约可能仍在变 | 返工 | P0 复制工作区现状；接口以 `docs/*.md` + `api/v2/all.api.js` 为准；改动接口时同步 |

---

## 10. 待确认决策点

1. **语言**：推荐 JavaScript + `<script setup>`（省时、风险低）；若要 TS，请在 P1 前确认，后续增量 .ts 化更划算。
2. **UI 安装模式**：推荐 npm `@wot-ui/ui` + easycom（本方案默认）；若团队更习惯 HBuilderX 的 `uni_modules` 模式，需要额外引入 wot-ui 的 uni_modules 包。
3. **路由**：推荐手写 `pages.json`（保留分包与原生 tabBar）；若希望文件路由（`vite-plugin-uni-pages`），P1 需要额外配置。
4. **平台范围**：推荐 mp-weixin（主）+ H5（调试）双端；App/支付宝/抖音端本次不做（代码用条件编译保留）。
5. **插件是否并存部署**：若新旧插件要同时挂到同一 Halo，需要新的 `pluginId` 与端口，请确认。

---

## 12. 实施记录

### P0 工作区（已完成）

- `F:\developer1\uni-halo-new` 建立，含 `plugin-uni-halo/`（441 文件 / 4.81MB）、`uni-halonew/`、`docs/`（9 份方案）、`README.md`。
- 插件复制排除：`.tools/`(836M JDK)、`.gradle/`、`build/`、`build-out/`、`node_modules/`、顶层 `extensions/*.jar`；**保留 `.git` 与全部未提交改动**。
  校验：`git status` 与原工作区逐行一致（135 项），HEAD 与分支一致，352 个受控文件一致。
  注意：`/XD extensions` 这类**按名排除会误伤 `src/main/resources/extensions/`**（插件 YAML 定义），必须用全路径精确排除。
- Java 侧实测通过：`JAVA_HOME="/c/Program Files/Java/jdk-17" ./gradlew --offline compileJava -x pnpmSetup -x buildFrontend -x installDepsForUI` → 488 个 class。
  两个环境坑（已写入工作区 README）：本机 `JAVA_HOME` 指向 JDK 8 而插件要求 17；`npm` 缓存目录 `E:\nodejs\node_cache\_logs` 不可写导致 `pnpmSetup` 失败。

### P1 脚手架（已完成）

- 目录结构：`src/{api,components,config,hooks,pages,pagesA,pagesC,static,store,styles,utils}` + `App.vue/main.js/pages.json/manifest.json/uni.scss`；旧工程根目录 ≈ 新工程 `src/`，因此**原有 `@/xxx` 导入路径可原样保留**，这是后续批次能快速平移的关键。
- 版本已按 peer 约束锁定（实测无警告）：`vue 3.4.21`（uni-app 通过 `@vue/server-renderer` 硬钉）、`@dcloudio/* 3.0.0-5020620260917001`、`@dcloudio/types 3.4.31`、`pinia 2.1.7`（2.3.x 要求 vue ≥3.5.11）、`@wot-ui/ui 2.3.2`、`vite 5.2.8`、`sass 1.77.8`。
- easycom 已生效：`"^wd-(.*)": "@wot-ui/ui/components/wd-$1/wd-$1.vue"`（构建产物 `usingComponents` 已正确指向 `node-modules/@wot-ui/ui/...`）。
- 36 个页面占位已生成（首页写为 wot-ui 联调自检页），`pages.json` 含 4 项原生 tabBar 与 `pagesA`/`pagesC` 分包。
- 构建实测：`pnpm build:mp-weixin` 通过（主包 629K，`app.json` 路由/分包/tabBar 正确）；`pnpm build:h5` 通过（671K）。
- 运行时实测（H5 + 真实浏览器）：easycom 组件渲染正常；`useToast`/`useDialog` hook 生效（点击后提示/弹窗出现）；`wd-config-provider` 注入主题变量生效（按钮主色变为 `#03A9F4`，非 wot-ui 默认蓝）；`onLoad` 组合式生命周期触发。
- 已确认 **wot-ui 主色链路**：`$primary-6 = var(--wot-primary-6, $blue-6)`，`theme-vars` 的 camelCase 键经 `--wot-{kebab}` 落成 CSS 变量值，P2 皮肤包即走 `primary1..primary10` 覆盖。
- 已确认**旧工程的 tm-vuetify 主题类名在页面中零使用**（`bg-gradient-primary*`/`primary.text`/`text-primary` 均 0 处），因此 `common/theme/theme.css` 无需平移；已平移的是框架无关的 `common/styles/app.base.scss`（`uh-*` 间距/flex/文本省略工具类，页面用得很密）。
- 脚手架自带 `@wot-ui/cli 1.1.0`（devDependency），后续查组件用 `pnpm exec wot list|info|doc|token`。

### P2 基础设施（已完成）

已平移：`api/`（含 1.9k 行的 `all.api.js`）、`config/`、`js_sdk/`（luch-request + fy-showModal）、`utils/`（30 个模块）、`common/{http,icons,markdown}`。
`js_sdk/fy-showModal` 经查是纯 App 端（plus.nativeObj）的安卓按钮互换兜底，无 Vue2/tm 依赖，**原样保留**（H5/小程序走原生 showModal）；`uni.$eShowModal` 依旧在 `main.js` 注册。
`common/filters` 未平移（Vue3 已移除 filter，且旧代码 0 处使用）。

改造点：

- **新增 `utils/feedback.js`**：用 uni 原生能力实现 `toast/show/success/error/info/warning/loading/close` 与 `confirm/alert`，
  **API 形状对齐 wot-ui 的 `useToast()`/`useDialog()`**，让 `$tm.toast.*`（全项目 56 处）能一比一平移；
  无组件上下文的调用点（utils、http 拦截器）走它，页面内仍用 `useToast()` + `<wd-toast />`。
- **状态层 Vuex → Pinia**（`$tm.vx` 全项目 42 处，涉及 27 个文件）：`config`、`setting`、`points`、`comment` 四个 store，
  方法/getter 名与原模块一致（`getConfigs`/`fetchConfigs`/`getSettings`/`updateDefaultAppSettings`…），调用点改法机械。
  实查发现 `store/points.js` 与 `store/comment.js` 在旧工程**从未被任何页面调用**（积分实际由 `utils/points.js` 自管理），已按原样平移保留能力、未强行接入。
- **主题皮肤重做**：新增 `utils/colorScale.js`（单色 → wot-ui 10 阶主色标尺，混色比例由内置 blue 色阶实测反推，误差 ≤2/255）、
  `store/theme.js`（皮肤主色 + tabBar 同步 + `themeVars`）、`themes/styles/unihalo.scss`（单文件品牌主题，主色 `#03A9F4`）。
  `utils/themeSkin.js` 保留全部对外 API、`THEME_SKINS` 白名单、`LEGACY_GRADIENT_THEME_MAP` 与存储键 `setTmVuetifyColor`——
  线上配置里两件已售主题装扮（`#667eea,#764ba2` / `#f6d365,#fda085`）正是 legacy 映射的键，这条兼容是真实的在跑链路。
- **全局混入 → hooks**：`hooks/useAppConfig.js`（haloConfig / globalAppSettings 含 null 兜底）、`hooks/usePage.js`（fnSetPageTitle / fnToTopPage / calcAniWait / isWechat）、`hooks/usePluginAvailable.js`。
- **main.js 过渡期兼容桥**：`globalProperties` 上挂 `$utils/$http/$httpApi/$baseApiUrl`，让尚未改造的 Options API 组件继续可用；新代码用显式 import。

实测发现并修复的两个真实缺陷（都已在本工程修正）：

1. **请求头导致 H5 全量请求失败（旧工程潜在 bug，仅 H5 会命中）**：`common/http/index.js` 曾设置 `ContentType` / `dataType` 两个自定义请求头。
   小程序端没有跨域检查所以一直没暴露；H5 端任何自定义头都触发 CORS 预检，而后端对该非标准头返回 **403**，
   浏览器直接拦下请求，前端表现为「API接口服务异常！(HTTP 500)」。已移除伪造头（JSON 编解码由 `dataType` 负责）。
2. **uni-app 不会把 App.vue 的模板包在页面外层**：H5 实测 DOM 为 `#app → uni-app → uni-page` 直连，`App.vue` 的 `wd-config-provider` 是死代码（零个 `wot-theme-*` 节点）。
   皮肤注入改为**页面级 `wd-config-provider`**（`theme-vars` 覆盖 `--wot-primary-1..10`，CSS 变量天然向下继承，H5/小程序同理）。
   这是 P4–P7 每个页面都要遵守的约定，已在 `App.vue` 与首页写明注释。
   另注：不要 `@use '@wot-ui/ui/styles/theme/index.scss'`——它会在 `page` 上把 `--wot-primary-*` 指回内置蓝色，覆盖品牌色阶。

P2 验收（H5 真机浏览器 + 线上后端 `http://117.72.189.216:8090`）：

- 站点配置真实拉取成功：站点名「宥枫璟」、描述「人生旅途中总会遇到美好枫璟」、微信登录已开启、积分已开启、虚拟支付已开启、10 个等级 · 6 件装扮（均与后端一致，非默认值）。
- 皮肤包：点击「森林绿」后 `--wot-primary-6 = #4CAF50FF`（含 1/3/8 阶）、页面主色按钮/标签/渐变头部整体变绿、tabBar 选中色变为 `rgb(76,175,80)`、存储写入 `green`；「清除皮肤」回落默认品牌配色。
- `feedback.js` toast/confirm 生效；`useToast`/`useDialog` 页面内生效。
- `pnpm build:mp-weixin`（主包 808K / 组件 253K）与 `pnpm build:h5` 均通过。
- 依赖布局改为 `node-linker=hoisted`（`.npmrc`）：pnpm 默认嵌套布局让 `qs → side-channel` 这类传递依赖在 mp-weixin 构建中解析失败，
  扁平化后告警消失（否则 `qs.stringify` 在小程序端会直接抛错）。

### P3 组件移植（进行中）

**第一个坑：`$set` 在 H5 端不存在，会直接崩。**
实测：小程序端的 Vue 运行时 `@dcloudio/uni-mp-vue` 里有 `globalProperties.$set = set` 补丁，而 H5 端的 `@dcloudio/uni-h5-vue`
**没有注入任何 globalProperties**。旧工程有 27 处 `this.$set(`，内置的 `components/mp-html` 还有 26 处，
不补的话 H5 上一进文章详情就会抛「this.$set is not a function」。
处理：`main.js` 里补 `$set`/`$delete` 垫片（Vue3 对象属性本身即响应式，直接赋值等价）。

**第二个坑：`mp-html` 用了 Vue3 已移除的 `beforeDestroy`。**
Vue3 不再执行该钩子（只在开发期告警），意味着组件卸载时 `onDetached` 不触发、图片懒加载 observer 不释放——
在长列表里反复进出文章页会持续泄漏。已内联补丁为 `beforeUnmount`（2 处，带 `[Vue3 适配]` 注释标记）。

mp-html 已内置到 `src/components/mp-html/`（第三方原样 + 上述 2 处补丁），并**实测在 Vue3 下渲染正常**：
HTML 模式与 Markdown 模式（代码高亮 + 行号 + 语言标签 + 表格 + 引用块 + containerStyle 字形设置）全部正常。
这条链路是全项目 15 处正文渲染的核心依赖（文章详情/瞬间/公告弹窗），提前验证过才敢往下走。

组件展厅页：`src/pagesA/dev-components/dev-components.vue`（已注册进 pagesA 分包，无任何入口引用，
仅开发期逐组件对照旧版用，发布前可整页删除）。目前含 mp-html 两个模式的自检分区，其余分区待各组移植完成后追加。

顺带记录两处**保持原样的既有瑕疵**（不擅自改，避免「静默改行为」）：
`common/markdown/markdown.config.js` 里引用的 `--routine` 变量在旧工程里就无定义（悬空）；
`--classE` 只在小程序端未引入 `markdown.scss` 时会缺失（旧工程同样如此）。

### P3 组件移植（已完成）

**23 个自有组件全部移植完成并通过浏览器渲染验收**（wave / cache-image / category-mini-card / scroll-btn /
points-toast / plugin-unavailable / notify-dialog / article-card / article-min-card / vote-card /
article-douban / restrict-read-skeleton / e-swiper / heatmap / barrage / article-vote / lucky-box /
wx-login-dialog / content-reaction-bar / comment-item / comment-list / comment-modal / moment-interaction）。

验收方式：`src/pagesA/dev-components/dev-components.vue` 整页挂载全部 23 个组件 + mp-html 两个模式，
用**线上后端的真实文章数据**喂给卡片/评论/互动类组件，在真实浏览器里逐组件确认渲染与事件派发
（不是只看"构建通过"——未使用的组件根本不参与编译，构建绿说明不了任何事）。

移植中新发现并补上的三个**跨切面基础设施**：

1. **tm-vuetify 原子类兼容层 `src/styles/tm-compat.scss`（自动生成）**。
   实测旧工程页面/组件依赖 tm 的原子类共 **575 处**（flex 120、flex-center 54、w-full 38、mt-24 35、
   flex-col 33、flex-between 30、text-size-s 25、text-weight-b 25…）。逐页改写成 wd/自有工具类属于大规模
   手工改动且极易造成视觉漂移，因此把旧工程**实际用到的 72 个类按原值**搬成全局样式（含
   `.flex-center{display:flex;justify-content:center;align-items:center;align-content:center}` 这类被 tm 拆成
   两条规则的定义——抽取时必须合并同名规则，只取第一条会丢掉对齐属性）。
   页面模板里的 class 从此可以逐字保留。`bg-white`/`w-full` 在 tm-vuetify 里本来就是空类，
   由工程自带 `app.base.scss` 覆盖，同样无需改。
2. **图标映射表**：旧工程 `class="iconfont icon-xxx"` 用的是 tm-vuetify 自带图标字体（未移植），
   残留会渲染成空白方块。全工程 14 个图标名 / 12 个文件，已逐一映射到 wot-ui `wd-icon`
   （唯一例外 `icon-commentdots-fill` 用自有 `haloicon-pinglun`，因为 wot-ui 无评论图标）。
   映射表见 `uni-halonew/docs/P4-主包页面移植规格.md`。
3. **mp-html 与 `$set` 垫片**（见上节）。

各组报告里值得记录的实现取舍：

- `article-douban`/`article-vote` 的 `tm-tags` 状态色是**业务数据里的 tm 主题名**（`orange`/`green`/`red`），
  已按语义映射到 `wd-tag` 的 `warning`/`success`/`danger`，而不是硬编码颜色。
- `comment-modal` 的表单校验从 tm-form 的"提交失败回调"改为 wd-form 内的等价内容校验（提示文案、顺序、
  接口调用、关闭与刷新事件全部保持一致，已在文件头注明这处机制性差异）。
- `wx-login-dialog` 的 `chooseAvatar` / `type="nickname"` 是微信原生能力，保留原生 `<button>`/`<input>` 写法。
- 需要 `$refs` 调用的组件（`comment-list`、`moment-interaction`、`barrage`、`lucky-box`）已用 `defineExpose`
  暴露同名方法，父页面改法保持机械。
- 组件内一律用 `utils/feedback.js` 而非 `useToast()`——后者要求页面显式声明 `<wd-toast />` 实例，
  组件不能假定宿主页面做过这件事。

**移植中发现但按原样保留的既有 bug（只留 `// FIXME(port):` 注释，不擅自改行为）**，共 8 处，例如：
`category-mini-card` 的 `category` 默认值返回 `undefined`（调用方必须传参）、`points-toast` 的副文案三元
两个分支都是空串（永不显示）、`article-vote` 外层 `v-if` 让两个分支不可达、`heatmap` 无数据时把对象直接
插值成 `[object Object]`、`comment-list` 的 `uni.$on` 没有对应 `$off`。这些是旧工程既有问题，
需要业务确认后再单独修，不在移植范围内。

### P5-A 子包页面第一批（已完成，8 页）

`tags`、`disclaimers`、`pagesA/about`、`toolbox`、`setting`、`my-orders`、`points-history`、`pagesC/website`。
浏览器级验收（我在 H5 真实浏览器里逐页打开，不是只看构建）：设置页渲染出 3 张卡片 / 16 个单元格 /
开关与保存-恢复-返回按钮，文案与旧版逐条一致；`points-history` 与 `my-orders` 都正确走到
「请先登录」门禁（`toast.show('请先登录')` + 800ms 后 `navigateBack`）——**与旧工程逐字一致**
（我一度以为「骨架屏与请先登录同时出现」是 bug，对照旧源码后确认：旧版同样是提示后自动返回，
只是我用 URL 直接打开页面没有返回栈，属测试方式造成的现象，不是缺陷）。

**对 tm 原子类兼容层的第一版做了修正（我自己的失误，值得记录）**：
第一版用正则去猜类名（`^[mp][trblxy]?-\d+$` 之类），把 `pa-*`/`ma-*`（全方向 padding/margin，第三位是 `a` 而不是
`t/r/b/l/x/y`）整族漏掉了，`text-size-lg`、`text-grey-darken-*`、`border-*`、`shadow-*`、`rounded`、`bk`
等也都没进来——agent 因此在页面里就地声明了这些类（值本身抄对了，但多了一份真相来源）。
第二版改成**不猜**：扫描旧工程模板里的全部 class（1160 个），逐个到 `tm-vuetify/mian.min.css` 查"单类选择器"定义，
合并同名规则，再排除 tm/wd/uni 前缀（组件类）与新工程自有样式已定义的类 → 最终 **101 个类**，
并刻意排除 `iconfont`/`icon-*`（tm 图标字体已移除，而它是 `font-family:…!important`，
留着只会在排查时误导，且叠类时会污染其它字体图标）。
同时核对了 wot-ui 内部样式：其类名全部 `wd-` 前缀，与这 101 个通用类名无冲突。

批次 A 里两处**机制性差异**（已在文件内注明，不是静默改动）：
- `setting` 页的 `tm-pickers` 用 `selectDefault`（标签数组），`wd-picker` 用 `selectedItems`，字段形状改为
  `pickerValue`（值数组）承载 v-model；`tm-form` 校验改为 `wd-form`。
- `points-history` 的 `item.createTime | formatTime`（Vue2 filter）改为 `formatTimeSafe`，
  输出精度从「秒」变为「分」（Vue3 已移除 filter，无等价物）。

### P4 主包 7 页（已完成）

`pages/index`（启动入口）、`home`（首页 1204 行）、`category`（分类 980）、`gallery`（图库 400）、`rank`（榜单 1094）、
`moments`（瞬间 916）、`about`（我的 2280）。全部在 H5 真实浏览器里打开验证，不只是构建通过：

- **home**：自绘导航（搜索框 + 站点名）、banner 轮播（真实文章「成长记录——庆祝宥哥的一百天」）、每日一句（含复制/分享）、
  工具箱、每日任务、最新列表 5 张卡片（置顶标记 + 真实阅读数）；订阅分类多路合并分页逻辑逐字保留。
- **category**：44 个分类卡片、真实数据（工程 5 篇 / AI·ML 12 篇 / 观点 15 篇…），订阅/取消订阅按钮、邀请码分享。
- **gallery**：wd-tabs（全部/枫景）+ 空态（后端未装 Photos 插件时的正常表现）；瀑布流用 CSS `columns` 近似。
- **rank**：文章榜/用户榜/动态 + TOP 20 + 最多阅读/点赞排序，真实榜单（586 阅读 / 532 阅读 …）。
- **moments**：未登录时的登录闸门（「登录后查看瞬间 / 微信登录」），与旧版设计一致。
- **about**：未登录态完整渲染（点击登录、我的积分 `-`、7 天签到、8 项服务宫格、配置宫格）；
  **服务端 activeTheme 端到端生效**——详见 P2 节，线上真实装扮把全局主色改成了蓝紫渐变对应的 `primary`。
- **index**：启动后正确转发到首页 tab。

**主包体积**：1583K / 2048K（微信主包上限），内部分布 node-modules(wot-ui) 485K、components(mp-html 为主) 396K、
pages 209K、common 158K、utils 111K。目前安全但已到 77%，后续再加大组件需留意（必要时把仅子包用到的组件下沉）。

#### 一个需要如实说明的发现：tabBar 页面的 immediate watcher 可能不触发

`category` 与 `gallery` 两页最初在浏览器里**整页停在骨架屏**（0 内容）。定位过程：接口层正常（同一调用在页面内 37ms 返回 11 条）、
组件状态停在 `loading='loading'`、且我用探针确认「模块执行了、执行到了 watch 那一行、watch 之后的代码也执行了，
但 `immediate: true` 的 handler 一次都没被调用」——即 Vue 的 `job()` 因 `effect.active === false` 直接 return 了。

但要**准确界定范围**：我又在一个全新标签页里做了对照实验（同样的 `watch(计算属性, cb, {immediate:true})` 四种变体），
**四种全部正常触发**。所以这不是「本工程 immediate watcher 普遍失效」，而更像是**长驻 dev 会话（多次 HMR + 依赖重优化）
下 tabBar 页面实例化时机**导致的现象——`category`/`gallery` 恰好都是 tabBar 页。

处理方式：把这两页**首屏初始化**从「immediate watcher 的副作用」改为**直接调用**（`categoryConfig`/`galleryConfig` 都是静态配置，
旧 watcher 的作用就是在 created 时跑一次初始化，语义等价且不依赖该副作用），修复后两页均正常渲染。
同时对同样形态的 `home`（`fnInitNotify` 公告弹窗）与 `about`（`fnGetNavList` + 积分概览）做了同样的加固：
先直接调用一次，再保留**非 immediate** 的 `deep` watcher 跟随配置变化。

> 需要说明的是：我**没有**证据表明这个现象会在 mp-weixin 真机上复现（也无法在真机上验证），
> 但由于改动与旧语义等价，属于「不会更差」的加固。真机回归时建议重点确认这四处（含公告弹窗是否弹出）。

### P5 子包页面第二、三批（已完成，14 页）

- **批次 A（8 页）**：`tags`、`disclaimers`、`pagesA/about`、`toolbox`、`setting`、`my-orders`、`points-history`、`pagesC/website`。
- **批次 B1（6 页）**：`archives`、`data-visual`、`vote-detail`、`moment-publish`、`lucky-records`、`lucky-claim`。
- **批次 B2（6 页）**：`articles`、`category-detail`、`tag-detail`、`user-activities`、`friend-links`、`unlocked-articles`。

浏览器抽查：`archives` 渲染出「2026年 09月（共 10 篇文章）」+ 真实文章与「2026年09月21日 星期一」；
`friend-links` 正确落到「未安装/启用插件」闸门（后端未装 Link 插件）；`setting`/`points-history`/`my-orders` 见批次 A 记录。

几处值得记录的取舍：
- `category-detail` 的 `fnInviteCode` 在旧工程里是**顶层选项而非 methods**，`this.fnInviteCode()` 在真机上会抛错、
  分享一直没带上标题与邀请码。移植版按代码原意调用，**分享会真正生效**——这属于「修好了」，已标 FIXME 待人工确认。
- `moment-publish` 的「发表权限」依赖 `personalToken` 与微信 AppId 匹配，H5 下按设计显示「暂无发表权限」，需真机验收。
- `gallery` 的 `k-touch-listen` 是旧 uni_module（未移植），按原逻辑内联为 `fnTouchStart/Move/End`（30px 阈值 + 1s 内有效）。

#### 兼容层的第二处边界：主题色类（刻意不纳入全局）

`tm-vuetify/scss/theme/*.css`（19 个颜色文件，共 2090 个类）里，旧工程实际用到的只有 9 个：
`bg-gradient-amber-accent`、`bg-gradient-blue-accent`、`bg-gradient-blue-lighten-b`、`blue`、`light-blue`、
`shadow-primary-4`、`text-blue`、`text-orange`、`text-red`。

关键在于：旧 App.vue 只引入了 `mian.min.css` + `scss/theme.css`，**并没有引入这些按颜色的主题文件**——
它们是被 tm-vuetify 的换肤机制按当前主题**动态注入**的（`setTmVuetifyColor` 切换时加载对应颜色的 CSS）。
也就是说 `.light-blue` / `.bg-gradient-blue-lighten-b` 这类类名在旧工程里**是「主题激活时才存在」的**：
默认浅蓝主题下有效，换成绿色主题时就失效（因为 green.css 里没有 `*-blue-*` 这个类）。

因此**刻意不把它们放进全局兼容层**：全局定义会让这些视觉在任何皮肤下都出现，
而旧工程在非对应主题下是「什么都不显示」——那是静默的视觉变更。
当前处理方式是各页在 scoped 样式里按原值就地还原（`archives` 的 `.light-blue`+`.shadow-primary-4`、
`vote-detail` 的 `.light-blue`、`start` 的 `bg-gradient-blue-lighten-b`），每处都注明了来源，
这样「这个元素该长什么样」被钉在页面里，比放进全局兼容层更贴近旧行为。

`start` 页另有一条 FIXME：若站长把 `startConfig.bg` 配成 `bg-gradient-blue-lighten-b` 以外的 tm 类名，
新工程没有对应类、会退回默认渐变（旧工程会显示该类）。线上在用值就是 `bg-gradient-blue-lighten-b`，故线上视觉等价。

#### 内置第三方组件的一处坑：liu-poster 尾巴上多一个 `</script>`

`uni_modules/liu-poster/components/liu-poster/liu-poster.vue` **原文件第 384 行有一个游离的 `</script>`**
（我核对过：旧工程原件同样有，第 383、384 行连续两个结束标签）。Vue2/webpack 容忍它，
Vue3 + Vite 直接报 `Invalid end tag` 让**两个目标端的构建全挂**。
它在移植 `invite` 页（以及任何引用海报的页面）之前一直没暴露——因为未被引用的模块不参与编译。
已在副本里删掉这一行游离标签（无行为变化），并顺手解除了 `article-detail` 的构建阻塞。

> 这条和「未使用的组件不参与编译」是同一个教训：内置第三方件也要**在真正被引用并编译过之后**才算验证完成。

### P6–P7 剩余 9 页（已完成）

| 页面 | 行数 | 关键内容 |
| --- | --- | --- |
| `article-detail` | 2416 → 2577 | 正文 mp-html、限制阅读（403 + ContentLockedDTO + 幂等扣券）、评论三件套 + `$refs` 调用、弹幕、豆瓣卡、投票、内容互动条、浏览积分倒计时圆环、海报、分享 |
| `points-recharge` | 1467 → 1560 | 权益直购：虚拟支付、会员/阅读券/积分兑换、限时倒计时、首单礼 |
| `invite` | 1105 | 邀请码/邀请统计 + **canvas 海报（liu-poster，链路 1:1）** |
| `identity` | 624 | 等级/称号/装扮 + `applyThemeSkin` 换肤 + 头像框注册表 |
| `start` | 600 | 站点封面（配置驱动 color/image/video 三态）+ wave 波浪 + 进入逻辑 |
| `invite-landing` | 500 | 三种邀请码入口 + 静默登录 + 邀请人订阅分类展示 |
| `votes` | 408 | 搜索 + 四个筛选下拉（`wd-drop-menu`）+ 投票卡 |
| `task-center` | 406 | 每日任务三态与领取 |
| `moment-detail` | 352 | 正文 + 附件 + 互动条（**旧页本就不含评论模块**，未擅自加） |

浏览器验收：`article-detail` 用线上真实文章打开，正文与配图由 mp-html 渲染、统计条（阅读/喜欢/字数）、
分类标签、两个悬浮按钮（回顶 + 操作组）均正常；`start` 按线上配置渲染背景图 + 波浪 + 「立即体验」；
`votes` 渲染出搜索 + 四个筛选下拉 + 真实投票卡；`points-recharge` 与 `points-history`/`my-orders` 一样正确走登录门禁。

两处 agent 报回来的「移植时按原意修好了，需产品确认」（与 `category-detail` 的分享同一类）：

1. **`article-detail` 的邀请码静默登录**：旧页写的是 `tryAutoWxLogin(this.$httpApi)`，而 `$httpApi` 只是 `{ v2 }` 包装，
   `wxLogin.js` 内部直接调 `httpApi.getWxAppConfig()` → 那次调用**必抛错并被内部 catch 吞掉**，
   即旧页的「邀请码静默登录」实际从未生效。移植版按原意传 `api.v2`（与 `pages/index/index.vue` 同口径），现在会真正生效。
2. **`category-detail` 的分享**（见 P5 节）：旧版因 `fnInviteCode` 写成顶层选项而抛错，分享一直是坏的。

> 这两处如果业务上确实希望「保持旧行为（坏着）」，改回去都是一行的事；我按「按代码原意实现」处理并标了 FIXME。

`article-detail` 另有一处按原样保留的旧 bug：`fnToOriginal` 读 `result.title`（Halo 文章对象没有该字段，恒 `undefined`），
正确字段是 `result.spec.title`，未擅自修正。

### 全部页面完成后的整体状态

- **页面：41 个 .vue / 24715 行**（主包 7 + 子包 28 + pagesA 组件 4 + 组件验收页 1 + pagesC 1）；
  组件 23 个（另有内置的 mp-html 与 liu-poster）。**零占位页**。
- 项目级扫描：无残留 `<tm-`、`~@/`（webpack 别名）、`iconfont icon-*`、`$set(`、`beforeDestroy`、
  `this.$httpApi/$utils/$tm`、`.sync`——命中项全部落在中文注释里。
- 双端构建：`pnpm build:mp-weixin` 与 `pnpm build:h5` 均通过。
- **主包 1637K / 2048K（80%）**，子包 pagesA 598K。已接近微信主包上限，后续再加主包内容需要先把
  仅子包使用的组件下沉（uni-app 的 `optimization.subPackages` 已开启，但 mp-html 因主包的 moments 页也在用而必须留在主包）。
- 待办只剩 P8：真机回归（清单见 `uni-halonew/docs/P8-真机验收清单.md`）。

---

## 13. 本次不做（明确边界）

- 不改 `uni-halo-qh` 现有工程（只读取，不动文件）。
- 不重写后端插件业务逻辑（只做工作区复制 + 构建跑通）。
- 不引入 tm-vuetify/任何 Vue2 运行时；不保留 Vue2 兼容层。
- 不做 App/多端适配与 App 端升级中心联调（保留条件编译占位）。
- 不做 UI 视觉重设计：以「还原现有交互 + 换 wot-ui 视觉规范」为验收标准，涉及 wot-ui 无对应组件处允许等价替代。
