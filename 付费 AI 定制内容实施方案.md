# 付费「AI 定制内容」实施方案

> 版本：插件 1.0.156 起（小程序端需重新上传）
> 一句话：站长在「权益道具」里配一条 `ai_content` 商品，用户购买后自行勾选**主题 / 来源 / 关键词**，每天得到一份按自己方向筛出的「我的简报」。

## 1. 已确认的口径

| 项 | 结论 |
|---|---|
| 内容怎么产生 | **筛选已有 + 轻量 AI 导语**：从站长已生成的 AI 日报内容里按用户方向筛（纯函数、零成本），再对每份简报做 **1 次** AI 调用写导语与推荐理由（可关闭） |
| 权益计量 | **按天有效期**：商品「道具数量」= 天数（**填 0 = 永久买断**），与会员卡同构，续费在当前到期时间上叠加 |
| 用户可配维度 | 主题分类 / 来源站点 / 关键词（各维度上限由站长配）；**不做自定义 RSS 源** |
| 与免费「分类订阅」 | **独立新链路**：订阅管首页按分类筛站点文章；本功能管「我的简报」，互不影响 |
| 成本上限 | 每位有效用户**每天最多 1 次 AI 调用**（当天简报已生成即复用）；改关注方向会作废当天那份，下次打开重算一次 |

## 2. 站长侧：配三处

1. **虚拟支付 → 权益道具**：新增一条商品，类型选 **AI 定制内容（ai_content）**，道具数量填天数（0 = 永久）。`goodsId` 仍需与微信 MP 后台道具一致。
2. **AI 定制内容** 分组（新增）：
   - `enabled` 总开关（默认关。**关闭时小程序不显示入口、接口一律拒绝、也不允许发货该类型商品**）
   - `aiIntroEnabled` 是否调用 AI 写导语（关掉 = 零 AI 成本的纯筛选，打开即秒开）
   - `maxCategories`(5) / `maxSources`(5) / `maxKeywords`(10) / `maxItems`(10) / `windowDays`(7) / `introWords`(200)
   - `benefitNote` 权益说明文案（未开通页逐行展示，留空用通用文案）
3. **AI 日报** 分组：AI 接口地址/密钥/模型**复用这一处**，本功能不新增 AI 配置。

后台可见性：设置页可编辑上述配置；控制台「商业化配置」页已纳入该分组（页签「权益与定价」）；「支付看板」新增「AI 定制内容」销量一项。

## 3. 用户侧链路

```
购买 ai_content 商品
   └─ grantAiContent：写幂等流水 → UserPoints.aiContentExpireAt 叠加天数（0 = 永久，用远期时间表示）
配置关注方向（我的 → AI 定制内容）
   └─ GET  /ai/content/preference     权益状态 + 偏好 + 可选项 + 上限
   └─ POST /ai/content/preference     全量替换保存（需有效权益；超限/清单外分类一律报错不截断）
                                      偏好真的变了 → 作废当天简报，下次打开按新方向重算
每天一份「我的简报」
   └─ GET  /ai/content/feed[?date=]   读时生成：筛选 → 1 次 AI 导语 → 落库 AiContentIssue
   └─ GET  /ai/content/issues         往期（日期 + 导语 + 条数）
```

**筛选口径**：复用 `DigestPreferenceMatcher`（分类权重 3 / 来源 2 / 关键词命中上限 3，「或」口径）——与免费订阅同一套规则，避免两边结果互相矛盾。候选池来自最近 `windowDays` 天**已发布**的日报条目（`DigestPublicService.candidateArticles`，含文章名对齐）。

**降级**：AI 超时/返回非 JSON/字段缺失 → 记为「纯筛选结果 + 空导语」，简报照常落库与展示；候选池为空或一条都没命中时给出可读原因（并给出一键放宽的候选主题）。

## 4. 数据模型

| 存储 | 说明 |
|---|---|
| `UserPoints.aiContentExpireAt` | 权益凭据；远期时间 = 永久（`AiContentConfigHelper.PERMANENT_EXPIRE_AT`），判定与会员共用一套口径 |
| `AiContentPreference`（新） | 用户偏好，`metadata.name = "acp-" + sha256(openid)`，一人一条 |
| `AiContentIssue`（新） | 每日简报，`metadata.name = "aci-" + sha256(openid + ":" + date)`，一人一天一条（幂等 + 成本阀门） |
| `PointsRecord` | 发货流水，`action = purchase_ai_content`，`earnKey = openid:purchase:<outTradeNo>`（恰好一次发放） |

## 5. 合规与边界（与既有口径一致）

- 一次性买断 + 有效期，**不做自动续费/包月**；文案避免「订阅/包月」字样（商品名建议「AI 定制内容 · 30 天」）。
- 用户**不能**填任意 RSS 源：候选只有站长配置的分类清单与最近真实出现过的来源（避免不良内容与被当代理抓站）。
- 简报不产生新文章：内容引用已发布的日报文章，阅读量/评论/付费判定全部复用文章详情页。
- 未开通/已过期用户拿不到内容，也就不会产生任何 AI 调用（**fail-closed**：读配置或账本失败一律按无效处理，与内容拦截的 fail-open 刻意相反——误放的是一次 AI 成本）。

## 6. 验证与回归

- `./gradlew test`：677 项全过（新增 `AiContentConfigHelperTest` / `AiContentPreferenceServiceTest` / `AiContentEntitlementServiceTest` / `AiContentFeedServiceTest` / `AiContentPromptHelperTest`，并在 `VirtualPayServiceImplTest` 补了发货与幂等用例）。
- 关键回归：`aiContentConfig.enabled=false`（默认）时，入口不显示、接口拒绝、发货拒绝——等同于没有这个功能；会员卡与阅读券链路未改动。
- 真机验收：配一条沙箱价 `ai_content` 商品 → 购买 → 看配置区出现 → 勾主题保存 → 首屏等简报生成（首次几秒到十几秒）→ 再进页面应秒开且内容不变。
