# AI 日报分类可配置方案（清单 + 映射）

> 版本：插件 1.0.155 起
> 目标：站长能在后台自行定义日报的分类体系（AI 只能从这份清单里选），并决定这些分类怎么落到站点上。

## 1. 为什么改

改造前分类清单**硬编码在三处**，站长只能开关「是否自动归类」，不能改清单：

- `utils/DigestAiHelper`：写死的六类 + 给 AI 的判定说明（prompt 白名单）；
- `services/DigestRenderer`：六类的 emoji / 中文名 / 描述（渲染 + 建站点分类时用）；
- `utils/PostClassifyPromptHelper`：存量归类 prompt 与校验。

结果：分类页永远只有那六类；日报还会自动创建分类，混进站长的分类体系里删不掉。

## 2. 设计

### 2.1 单一事实来源：`DigestCategoryCatalog`

一个不可变对象，由配置构建，对外提供 `labelOf / emojiOf / descriptionOf / contains / normalize /
promptSection / sampleKey / fallbackKey`。四条链路（AI prompt、日报渲染、存量归类、站点分类实体创建）
现在都从这里取名字、图标、说明——**改一处就全链路生效**。

消毒规则（配置是不可信输入）：key 只允许 `[a-z0-9_-]`（大写归一化成小写）、重复 key 只取第一条、
label/emoji/description 留空时按「内置表 → key/图标兜底」补齐、最多 12 条。
清单为空或全被消毒丢弃 → **回落内置六类**。

### 2.2 新增配置（插件设置 → AI 日报）

| 字段 | 说明 |
|---|---|
| `digestCategories`（repeater） | 分类清单：`key` / `label` / `emoji` / `desc`（给 AI 的判定说明）/ `targetCategory`（可选，映射目标） |
| `categoryMode`（select） | `create`（默认，不存在则创建）/ `reuse`（只复用已有站点分类）/ `map`（按 `targetCategory` 映射） |
| `unknownCategoryPolicy`（select） | AI 给出清单外分类时：`other`（默认，落兜底分类）/ `drop`（不打分类） |

**留空 = 升级前行为**：清单留空 + `create` + `other`，与改造前的分类结果一致，不需要迁移。

### 2.3 三种落库方式（`DigestTaxonomyService.resolveAiCategories`）

| 模式 | 行为 | 适用 |
|---|---|---|
| `create`（默认） | 命中已有分类就复用（按 name / slug / 显示名匹配，命中后**不改动**已有分类），没有就按清单里的中文名/描述创建，打 `uni.uhalo.pro/auto-generated` 标签 | 不想手工维护分类的站点 |
| `reuse` | 只复用已有分类，匹配不到就不打分类 | 分类体系自己做主，不想让插件多造分类 |
| `map` | 按清单里的「映射到已有分类名」把 key 换成站点分类名，只匹配不创建 | 已有分类名字与 AI 主题对不上，需要显式对照 |

后两种模式匹配失败是**静默跳过**（只留日志）：站长选了「不自动创建」，笔误就不该造出分类。
为了不让这变成「配置好像没生效」，后台面板与发布自检会把匹配不上的行列出来。

### 2.4 后台可见性与清理

- 管理页（`/static/ai-digest-admin.html`）新增「🏷 日报分类」面板：显示生效清单
  （含来源：后台配置 / 内置六类）、落库方式、逐行结论，问题行标红；
- 新增接口：
  - `GET /digest/categories` — 生效清单 + 逐行落库结论；
  - `GET /digest/categories/auto-created` — 插件自动创建的分类 + 引用它的文章数；
  - `DELETE /digest/categories/auto-created` — 清理**没有文章引用**的自动分类（有引用的跳过并回报原因，
    站长自己建的分类永不处理）；
- 发布自检新增「日报分类」项：模式、清单来源、需处理的行都会列出来。

## 3. 影响面

- **小程序端不用改**：分类页的订阅卡片墙读的是 Halo 站点分类，所以改成 `reuse` / `map` 之后，
  日报不会再在分类页里多出自动创建的分类（这正是可配清单带来的收益）；
- 旧分类不会被自动删除，清理入口在后台面板；
- 遗留链路 `DigestPreferenceMatcher` / `/digest/my`（按旧 AI 分类 key 匹配）本次不动，
  它已无前端消费，改清单后自然失效。

## 4. 升级与验证

- 升级后**什么都不配**：`spec.categories` 与站点分类实体与升级前一致（内置六类 + 自动创建）；
  AI prompt 的主题清单同样是内置六类，只有个别条目的措辞统一（原先 prompt 与渲染各写一份略有差异的说明，
  现在合并为一份 `desc`），以及 JSON 示例里的 `category` 取清单第一项（`ai-ml`，原为 `engineering`）；
- 验证方式：`./gradlew test`（620 项，含 `DigestCategoryCatalogTest` / `DigestCategoryViewDTOTest` /
  三模式用例 / 留空配置回归），后台页点「🩺 发布环境自检」与「🏷 日报分类」各看一遍。

## 5. 不做 / 边界

- 不改 RSS 源清单（92 个源仍内置）；
- 不做清单拖拽排序、不做「一个 key 映射到多个分类」；
- 不动 `/digest/my` 个性化流与订阅存储结构。
