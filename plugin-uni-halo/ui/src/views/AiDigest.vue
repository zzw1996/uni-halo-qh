<template>
  <div class="page">
    <div class="page-head">
      <div class="page-title">
        <span class="title-emoji">📰</span>
        <div>
          <h2>AI 日报 · 生成与发布</h2>
          <p>每天定时抓取技术博客 → AI 精选成稿 → 通过 Halo 官方接口发布文章</p>
        </div>
      </div>
    </div>

    <div class="stat-grid">
      <div class="stat-card stat-a">
        <span class="stat-label">功能状态</span>
        <span class="stat-value">{{ overview?.enabled ? "已开启" : "已关闭" }}</span>
        <span class="stat-sub">{{ overview?.enabled ? (overview?.scheduleEnabled ? "定时执行中" : "仅手动生成") : "请在插件设置中开启" }}</span>
      </div>
      <div class="stat-card stat-b">
        <span class="stat-label">下次执行</span>
        <span class="stat-value">{{ nextRunText }}</span>
        <span class="stat-sub">{{ scheduleText }}</span>
      </div>
      <div class="stat-card stat-c">
        <span class="stat-label">最近一次结果</span>
        <span class="stat-value">{{ lastStatusText }}</span>
        <span class="stat-sub">{{ lastReportSub }}</span>
      </div>
      <div class="stat-card stat-d">
        <span class="stat-label">运行状态</span>
        <span class="stat-value">{{ overview?.running ? "生成中" : "空闲" }}</span>
        <span class="stat-sub">{{ overview?.running ? "预计需要 1-3 分钟" : `历史记录 ${history.length} 条` }}</span>
      </div>
    </div>

    <div v-if="historyCostText" class="cost-summary">💰 {{ historyCostText }}</div>

    <div class="toolbar">
      <button class="btn-primary" :disabled="busy || !overview?.enabled" @click="triggerRun(true)">
        ⚡ 立即生成并发布
      </button>
      <button class="btn-ghost" :disabled="busy || !overview?.enabled" @click="triggerRun(false)">
        📝 只生成不发布
      </button>
      <button class="btn-ghost" :disabled="busy" @click="load()">🔄 刷新</button>
      <button class="btn-ghost" :disabled="busy" @click="runPreflight">🩺 发布环境自检</button>
      <button class="btn-ghost" :disabled="busy" @click="cleanupResidues">🧹 清理残留文章</button>
      <span class="toolbar-tip">{{ tip }}</span>
    </div>

    <div v-if="error" class="error-banner">⚠️ {{ error }}</div>

    <div v-if="progressText" class="table-card">
      <div class="progress-head">
        <span class="progress-title">{{ progressTitle }}</span>
        <span class="progress-text">{{ progressText }}</span>
      </div>
      <div class="progress-bar">
        <div
          class="progress-fill"
          :class="{ indeterminate: progressPercent === null }"
          :style="progressPercent === null ? {} : { width: progressPercent + '%' }"
        ></div>
      </div>
      <div class="progress-meta">
        <span v-if="progressPercent !== null">{{ progressPercent }}%（{{ progressDone }}/{{ progressTotal }}）</span>
        <span v-else>处理中，请稍候…</span>
      </div>
    </div>

    <!--
      大模型通道：清单顺序即故障转移顺序，每条的开关决定它是否参与，
      「测试」用一次极小的请求回答「这条通道到底通不通」。
      清单留空时这里显示的是旧的主 / 兜底通道，点「导入现有通道」即可接管。
    -->
    <div class="table-card">
      <div class="classify-head">
        <div class="classify-title-box">
          <span class="classify-title">🔌 大模型通道</span>
          <span class="classify-summary">{{ modelSummary }}</span>
        </div>
        <div class="classify-ops">
          <button class="btn-ghost" :disabled="modelBusy" @click="loadModels">刷新</button>
          <button
            class="btn-ghost"
            :disabled="modelBusy || !canImportLegacy"
            :title="canImportLegacy
              ? '把旧的主 / 兜底通道搬进清单（保存后旧字段不再生效）'
              : '清单非空时不需要导入：清空清单后这里会重新可用'"
            @click="importLegacy"
          >
            导入现有通道
          </button>
          <button class="btn-ghost" :disabled="modelBusy || priceBusy" @click="refreshPrices">
            {{ priceBusy ? "读取中…" : "🔄 从官网刷新单价" }}
          </button>
          <button class="btn-ghost" :disabled="modelBusy" @click="loadBalances">
            {{ balanceBusy ? "查询中…" : "💰 查账户余额" }}
          </button>
          <button class="btn-ghost" :disabled="modelBusy || !modelRows.length" @click="testAllModels">
            🩺 全部测试
          </button>
          <button class="btn-primary" :disabled="modelBusy || !modelDirty" @click="saveModels">
            {{ modelBusy ? "处理中…" : "保存通道" }}
          </button>
        </div>
      </div>

      <p class="classify-hint" :class="{ 'hint-bad': !modelReady }">{{ modelHint }}</p>

      <!--
        任务生效表：勾选框表达的是「这条通道愿意干什么」，这一行表达的是「结果落在哪」。
        没有它，站长就得自己在脑子里做一次匹配，很容易以为评分走的是便宜通道。
      -->
      <div v-if="modelTaskLines.length" class="model-taskbar">
        <span
          v-for="line in modelTaskLines"
          :key="line.key"
          class="model-task-chip"
          :class="{ 'chip-bad': !line.usable, 'chip-warn': line.usable && line.fallback }"
          :title="line.note"
        >
          {{ line.label }} →
          <template v-if="line.usable">
            {{ line.channel }}<span class="chip-model">· {{ line.model }}</span>
          </template>
          <template v-else>没有可用通道</template>
          <template v-if="line.fallback">（回退）</template>
        </span>
      </div>

      <div v-if="balances.length" class="model-balances">
        <span v-for="item in balances" :key="item.key" class="model-balance">
          <b>{{ item.label }}</b>
          <template v-if="item.ok">
            余额 {{ moneySymbol(item.currency) }}{{ item.totalBalance }}
            <span v-if="item.grantedBalance !== '0.00'" class="model-balance-sub">
              （赠送 {{ item.grantedBalance }} / 充值 {{ item.toppedUpBalance }}）
            </span>
          </template>
          <span v-else class="msg-bad">{{ item.message }}</span>
        </span>
      </div>

      <div v-if="modelPrices" class="model-prices">
        <span class="model-prices-head">
          单价：{{ modelPrices.source }}
          <template v-if="modelPrices.fetchedAt">（读取于 {{ fetchedAtText(modelPrices.fetchedAt) }}）</template>
        </span>
        <span v-for="row in modelPrices.items" :key="row.model" class="model-price-chip" :title="row.description">
          {{ row.model }}：{{ moneySymbol(row.currency) }}命中 {{ row.cacheHit }} / 未命中 {{ row.cacheMiss }} / 输出 {{ row.output }}
          <span class="model-price-sub">空闲 ×{{ row.offPeakRatio }}</span>
        </span>
        <span class="model-price-sub">{{ modelPrices.peakRule }}</span>
        <a :href="modelPrices.officialUrl" target="_blank" rel="noopener" class="link">官方价目表</a>
      </div>

      <ul v-if="modelHints.length" class="model-hints">
        <li v-for="(hint, idx) in modelHints" :key="idx">{{ hint }}</li>
      </ul>

      <div class="classify-body">
        <table class="tbl">
          <thead>
            <tr>
              <th>启用</th>
              <th>用途（勾选后只接这些活）</th>
              <th>显示名</th>
              <th>API Base</th>
              <th>模型</th>
              <th>API Key</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!modelRows.length">
              <td colspan="8" class="empty">
                还没有通道：点下面的「＋ 添加通道」，或把旧的主 / 兜底通道导入进来
              </td>
            </tr>
            <tr v-for="(row, idx) in modelRows" :key="row.rowId">
              <td>
                <input v-model="row.enabled" type="checkbox" @change="markModelDirty" />
              </td>
              <td>
                <label
                  v-for="task in taskOptions"
                  :key="task.key"
                  class="model-task"
                  :title="task.hint"
                >
                  <input
                    type="checkbox"
                    :checked="row.tasks.includes(task.key)"
                    @change="toggleTask(row, task.key)"
                  />
                  {{ task.label }}
                </label>
                <span v-if="!row.tasks.length" class="model-task-none">全部</span>
              </td>
              <td>
                <input
                  v-model="row.label"
                  class="model-input"
                  placeholder="如 DeepSeek 主用"
                  @input="markModelDirty"
                />
              </td>
              <td>
                <select
                  class="model-input model-preset"
                  :value="presetKeyOf(row.apiBase)"
                  title="选一家供应商即自动填 API Base；没有你的供应商就保持「自定义」手填"
                  @change="applyPreset(row, $event)"
                >
                  <option value="">自定义</option>
                  <option v-for="preset in providerPresets" :key="preset.key" :value="preset.key">
                    {{ preset.label }}
                  </option>
                </select>
                <input
                  v-model="row.apiBase"
                  class="model-input model-input-wide"
                  placeholder="https://api.deepseek.com/v1"
                  @input="markModelDirty"
                />
                <span v-if="presetNoteOf(row.apiBase)" class="model-preset-note">{{ presetNoteOf(row.apiBase) }}</span>
              </td>
              <td>
                <input
                  v-model="row.model"
                  class="model-input"
                  placeholder="deepseek-chat"
                  @input="markModelDirty"
                />
              </td>
              <td>
                <input
                  v-model="row.apiKeyInput"
                  class="model-input model-input-wide"
                  :placeholder="row.apiKeyMasked ? `${row.apiKeyMasked}（留空 = 不修改）` : 'sk-…'"
                  @input="markModelDirty"
                />
              </td>
              <td class="model-state">
                <span class="pill" :class="rowStateClass(row)">{{ rowStateText(row) }}</span>
                <span v-if="row.test" class="model-test-msg" :class="{ 'msg-bad': !row.test.ok }">
                  {{ row.test.message }}
                  <template v-if="row.test.ok && row.test.latencyMs">
                    · {{ row.test.latencyMs }}ms
                    <template v-if="row.test.servedModel">· {{ row.test.servedModel }}</template>
                  </template>
                  <span v-if="row.test.url" class="model-test-url">（实际请求：{{ row.test.url }}）</span>
                </span>
              </td>
              <td class="ops">
                <button class="btn-link" :disabled="idx === 0 || modelBusy" @click="moveModel(idx, -1)">
                  上移
                </button>
                <button
                  class="btn-link"
                  :disabled="idx === modelRows.length - 1 || modelBusy"
                  @click="moveModel(idx, 1)"
                >
                  下移
                </button>
                <button class="btn-link" :disabled="modelBusy || row.testing" @click="testModel(row)">
                  {{ row.testing ? "测试中…" : "测试" }}
                </button>
                <button class="btn-link btn-link-danger" :disabled="modelBusy" @click="removeModel(idx)">
                  删除
                </button>
              </td>
            </tr>
          </tbody>
        </table>

        <div class="model-foot">
          <button class="btn-ghost" :disabled="modelBusy" @click="addModel">＋ 添加通道</button>
          <span class="model-foot-tip">
            顺序即优先级（第一条失败自动跳下一条）；关掉的通道不参与调用。
            用途留空 = 什么活都接；某任务的通道全不可用时会回退到全部可用通道，并在上面标出来。
            「测试」只发一次 8 token 的请求，会真实产生极少量费用。
          </span>
        </div>
        <p v-if="modelLegacyNote" class="model-legacy">{{ modelLegacyNote }}</p>
      </div>
    </div>

    <div v-if="preflight" class="table-card">
      <div class="table-title">🩺 发布环境自检结果</div>
      <div class="preflight-list">
        <div v-for="(entry, idx) in preflight" :key="idx" class="preflight-item">
          <span class="preflight-flag" :class="entry.ok ? 'flag-ok' : 'flag-bad'">
            {{ entry.ok ? "✔" : "✘" }}
          </span>
          <span class="preflight-name">{{ entry.item }}</span>
          <span class="preflight-msg" :class="{ 'msg-bad': !entry.ok }">{{ entry.message }}</span>
        </div>
      </div>
      <p class="preflight-tip">
        自检只读取信息、不会发布任何内容；不通过的项按提示改完再点「发布」。
      </p>
    </div>

    <!--
      AI 文章归类：只服务插件自己发布的日报文章。
      你自己写的文章不在这里分析（你归好的类机器不该重判），
      这里要做的只是把「发布时漏掉分类的那批 AI 文章」补齐。
    -->
    <div class="table-card">
      <div class="classify-head">
        <div class="classify-title-box">
          <span class="classify-title">🗂 AI 文章归类</span>
          <span class="classify-summary">{{ classifySummary }}</span>
        </div>
        <div class="classify-ops">
          <button class="btn-ghost" :disabled="busy || classifyLoading" @click="loadClassifyStatus">
            刷新统计
          </button>
          <button
            class="btn-primary"
            :disabled="busy || classifyLoading || !canPreview"
            :title="previewHint"
            @click="previewClassify"
          >
            {{ classifyLoading ? "分析中…" : `🤖 分析下一批（${classifyStatus?.batchSize || 15} 篇）` }}
          </button>
        </div>
      </div>

      <p class="classify-hint" :class="{ 'hint-bad': classifyBlocked }">{{ classifyHint }}</p>

      <div v-if="classifyItems.length" class="classify-body">
        <div class="classify-bar">
          <label class="classify-check">
            <input type="checkbox" :checked="allAdopted" @change="toggleAllAdopt" />
            全选（已勾选 {{ adoptedCount }} 篇）
          </label>
          <button class="btn-primary" :disabled="busy || !adoptedCount" @click="applyClassify">
            应用到勾选的 {{ adoptedCount }} 篇
          </button>
        </div>
        <table class="tbl">
          <thead>
            <tr>
              <th>采纳</th>
              <th>文章</th>
              <th>现有分类</th>
              <th>建议主题</th>
              <th>建议标签</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(item, idx) in classifyItems" :key="item.name || idx">
              <td><input v-model="item.adopted" type="checkbox" /></td>
              <td class="classify-post">{{ item.title || "（无标题）" }}</td>
              <td>{{ (item.categories || []).join("、") || "—" }}</td>
              <td>
                <span v-if="item.suggestedCategoryLabel" class="pill pill-ok">
                  {{ item.suggestedCategoryLabel }}
                </span>
                <span v-else class="mono">—</span>
              </td>
              <td>{{ (item.suggestedKeywords || []).join("、") || "—" }}</td>
              <td class="classify-note">{{ item.note || "—" }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="classifyResult" class="classify-result">
        <div class="post-list-title">{{ classifyResult.message }}</div>
        <div
          v-for="(item, idx) in classifyResult.items || []"
          :key="item.name || idx"
          class="post-item"
        >
          <span class="pill" :class="classifyStateClass(item.state)">
            {{ classifyStateText(item.state) }}
          </span>
          <span>{{ item.title || item.name }}</span>
          <span v-if="item.category" class="mono">分类：{{ item.category }}</span>
          <span v-if="(item.tags || []).length" class="mono">标签：{{ (item.tags || []).join("、") }}</span>
          <span v-if="item.message" class="post-msg">{{ item.message }}</span>
        </div>
      </div>
    </div>

    <div class="table-card">
      <div class="table-title">📚 运行历史（最新在前的 {{ history.length }} 条）</div>
      <table class="tbl">
        <thead>
          <tr>
            <th>日期</th>
            <th>触发</th>
            <th>状态</th>
            <th>发布</th>
            <th>精选</th>
            <th>耗时</th>
            <th>花费</th>
            <th>文章</th>
            <th>未发布原因</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading"><td colspan="10" class="empty">⏳ 加载中…</td></tr>
          <tr v-else-if="!history.length"><td colspan="10" class="empty">🎈 还没有运行记录，点「立即生成」试试</td></tr>
          <tr v-for="item in history" :key="item.name">
            <td class="time">{{ item.date }}</td>
            <td class="time">{{ triggerText(item.slot) }}</td>
            <td><span class="pill" :class="statusClass(item.status)">{{ statusText(item.status) }}</span></td>
            <td>
              <span class="pill" :class="publishClass(item.publishState, overview?.running)">
                {{ publishText(item.publishState, overview?.running) }}
              </span>
            </td>
            <td>{{ item.articleCount ?? "-" }}</td>
            <td class="time">{{ item.durationMs ? Math.round(item.durationMs / 1000) + "s" : "-" }}</td>
            <td
              class="time"
              :title="item.aiCostText || '这次运行没有采集到用量（1.0.165 之前的记录）'"
            >
              {{ item.aiCostText || "-" }}
            </td>
            <td>
              <a v-if="primaryPostUrl(item)" :href="primaryPostUrl(item)" target="_blank" rel="noopener" class="link">打开</a>
              <span v-if="postsOf(item).length > 1" class="mono"> 共 {{ postsOf(item).length }} 篇</span>
              <span v-else-if="!primaryPostUrl(item)" class="mono">-</span>
            </td>
            <td class="reason-cell">
              <span v-if="item.error" class="fail-reason" :title="item.error">{{ item.error }}</span>
              <span v-else class="mono">-</span>
            </td>
            <td class="ops">
              <button class="btn-link" @click="openDetail(item.name)">详情</button>
              <button
                class="btn-link"
                :disabled="busy || item.status === 'RUNNING'"
                :title="publishActionHint(item)"
                @click="republish(item.name)"
              >
                {{ publishActionLabel(item) }}
              </button>
              <button
                class="btn-link btn-link-danger"
                :disabled="busy || item.status === 'RUNNING'"
                title="删除这条运行记录（只删记录，已发布的文章不受影响）"
                @click="removeReport(item)"
              >
                删除
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="detail" class="drawer-mask" @click.self="detail = null">
      <div class="drawer">
        <div class="drawer-head">
          <div>
            <strong>{{ detail.summary.date }} · {{ triggerText(detail.summary.slot) }}</strong>
            <span class="pill" :class="statusClass(detail.summary.status)">
              {{ statusText(detail.summary.status) }}
            </span>
            <span class="pill" :class="publishClass(detail.summary.publishState)">
              {{ publishText(detail.summary.publishState) }}
            </span>
          </div>
          <button class="btn-ghost" @click="detail = null">关闭</button>
        </div>

        <div v-if="detail.summary.error" class="error-banner">⚠️ {{ detail.summary.error }}</div>
        <div v-if="missingCategoryText" class="warn-banner">⚠️ {{ missingCategoryText }}</div>

        <div v-if="detailPosts.length" class="post-list">
          <div class="post-list-title">发布明细（{{ detailPosts.length }} 篇）</div>
          <div v-for="(post, idx) in detailPosts" :key="post.postName || idx" class="post-item">
            <span class="pill" :class="publishClass(post.state)">{{ publishText(post.state) }}</span>
            <a v-if="post.url" :href="post.url" target="_blank" rel="noopener" class="link">{{ post.title || post.postName }}</a>
            <span v-else>{{ post.title || post.postName }}</span>
            <span v-if="post.message && post.state !== 'PUBLISHED'" class="post-msg">{{ post.message }}</span>
          </div>
        </div>

        <div v-if="detailCost" class="cost-block">
          <div class="post-list-title">
            💰 本轮 AI 花费（估算）
            <span v-if="detailCostText" class="cost-total">{{ detailCostText }}</span>
          </div>
          <table class="tbl cost-tbl">
            <thead>
              <tr>
                <th>任务</th>
                <th>调用</th>
                <th>输入 token</th>
                <th>输出 token</th>
                <th>金额</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in detailCost.tasks || []" :key="row.task">
                <td>{{ row.label || row.task }}</td>
                <td>{{ row.calls }}</td>
                <td class="time">{{ row.inputTokens }}</td>
                <td class="time">{{ row.outputTokens }}</td>
                <td>
                  <template v-if="row.priced">{{ moneySymbol(row.currency) }}{{ row.amount }}</template>
                  <template v-else>未配置单价<span v-if="row.model" class="cost-note">（模型 {{ row.model }}）</span></template>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-for="row in detailCost.channels || []" :key="row.key" class="cost-line">
            <span class="mono">{{ row.label }}</span>
            <span v-if="row.priced">估算 {{ moneySymbol(row.currency) }}{{ row.amount }} · {{ row.totalTokens }} token · {{ row.calls }} 次</span>
            <span v-else>{{ row.totalTokens }} token · {{ row.calls }} 次（未配置单价）</span>
            <span v-if="row.balanceBefore && row.balanceAfter" class="cost-balance">
              余额 {{ row.balanceBefore }} → {{ row.balanceAfter }}
              <b v-if="row.balanceDelta">（账号实际扣减 {{ moneySymbol(row.balanceCurrency) }}{{ row.balanceDelta }}）</b>
            </span>
            <span v-else-if="row.balanceNote" class="cost-note">{{ row.balanceNote }}</span>
          </div>
          <p class="cost-note">{{ detailCost.note }}</p>
        </div>

        <div v-if="statItems.length" class="stat-inline">
          <span v-for="stat in statItems" :key="stat.label" class="stat-chip">
            {{ stat.label }}：<b>{{ stat.value }}</b>
          </span>
        </div>

        <pre class="markdown-view">{{ detail.markdown || "（没有正文）" }}</pre>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { useConsoleApi } from "../api";

interface DigestPost {
  title: string | null;
  postName: string | null;
  url: string | null;
  state: string;
  message: string | null;
}

interface PreflightItem {
  item: string;
  ok: boolean;
  message: string;
}

interface DigestSummary {
  name: string;
  date: string;
  slot: string;
  status: string;
  publishState: string;
  articleCount: number | null;
  postCount: number | null;
  posts: DigestPost[] | null;
  postName: string | null;
  postUrl: string | null;
  cover: string | null;
  error: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  durationMs: number | null;
  /** 本轮 AI 花费（按币种），老记录为空数组 */
  aiCosts: Money[] | null;
  /** 花费的一句话写法，如 ≈¥0.42；老记录为空串 */
  aiCostText: string | null;
}

interface Money {
  currency: string;
  amount: string;
}

/** 一轮运行的花费块（后端 DigestAiCostDTO） */
interface DigestAiCost {
  tasks: Array<{
    task: string;
    label: string;
    /** 实际用的模型名：缺价时要能说出是哪个模型没配价 */
    model: string | null;
    calls: number;
    inputTokens: number;
    outputTokens: number;
    totalTokens: number;
    amount: string | null;
    currency: string;
    priced: boolean;
  }>;
  channels: Array<{
    key: string;
    label: string;
    model: string;
    calls: number;
    totalTokens: number;
    amount: string | null;
    currency: string;
    priced: boolean;
    balanceCurrency: string | null;
    balanceBefore: string | null;
    balanceAfter: string | null;
    balanceDelta: string | null;
    balanceNote: string | null;
  }>;
  totals: Money[];
  calls: number;
  unpricedCalls: number;
  unknownUsageCalls: number;
  note: string;
}

interface DigestOverview {
  enabled: boolean;
  scheduleEnabled: boolean;
  scheduleTimes: string;
  timezone: string;
  nextRunAt: string | null;
  running: boolean;
  progress: DigestProgress | null;
  totalReports: number;
  lastReport: DigestSummary | null;
}

interface DigestProgress {
  running: boolean;
  stage: string | null;
  message: string | null;
  total: number;
  done: number;
  currentTitle: string | null;
  updatedAt: string | null;
}

interface ClassifyStatus {
  digestPosts: number;
  classifiedPosts: number;
  pendingPosts: number;
  batchSize: number;
  aiReady: boolean;
  autoCategoryEnabled: boolean;
  autoTagEnabled: boolean;
}

interface ClassifyCandidate {
  name: string;
  title: string | null;
  excerpt: string | null;
  categories: string[] | null;
  tags: string[] | null;
  publishTime: string | null;
  suggestedCategory: string | null;
  suggestedCategoryLabel: string | null;
  suggestedKeywords: string[] | null;
  note: string | null;
  /** 页面上勾选状态：默认勾选「AI 给出了建议」的那些 */
  adopted: boolean;
}

/** 一条通道（后端 DigestAiModelViewDTO.Row）；apiKey 只以掩码下发 */
interface ModelRowView {
  key: string;
  label: string;
  apiBase: string;
  model: string;
  enabled: boolean;
  hasKey: boolean;
  apiKeyMasked: string;
  usable: boolean;
  source: string;
  /** 承担的任务标识；空 = 全部任务 */
  tasks: string[];
  message: string;
}

/** 当前生效的单价表（后端 DigestAiModelViewDTO.Prices）：只读展示，站长不必手填 */
interface ModelPrices {
  source: string;
  fetchedAt: string | null;
  officialUrl: string;
  peakRule: string;
  items: Array<{
    model: string;
    currency: string;
    cacheHit: string;
    cacheMiss: string;
    output: string;
    offPeakRatio: string;
    description: string;
  }>;
}

/** 「任务 → 生效通道」表的一行：勾选框表达意愿，这一行表达结果 */
interface ModelTaskLine {
  key: string;
  label: string;
  channel: string;
  model: string;
  fallback: boolean;
  usable: boolean;
  note: string;
}

/** 旧的主 / 兜底通道现状：面板顶部提示与「导入」按钮要用 */
interface ModelLegacy {
  apiBase: string;
  model: string;
  hasKey: boolean;
  apiKeyMasked: string;
  fallbackApiBase: string;
  fallbackModel: string;
  hasFallbackKey: boolean;
  fallbackApiKeyMasked: string;
  configured: boolean;
}

interface ModelView {
  fromList: boolean;
  summary: string;
  providers: ModelRowView[];
  channels: ModelRowView[];
  active: string | null;
  /** 每个任务最终会用哪条通道（含「没有通道勾选它，已回退」的结论） */
  tasks: ModelTaskLine[];
  /** 当前生效的单价表与来源 */
  prices: ModelPrices | null;
  legacy: ModelLegacy;
  hints: string[];
}

interface ModelTestResult {
  ok: boolean;
  key: string | null;
  label: string | null;
  apiBase: string | null;
  url: string | null;
  model: string | null;
  status: number | null;
  latencyMs: number | null;
  reply: string | null;
  servedModel: string | null;
  totalTokens: number | null;
  message: string;
  detail: string | null;
}

/**
 * 可编辑的通道行。
 *
 * apiKeyInput 只有「这次新填的 Key」才有值：留空表示沿用已保存的那把
 * （后端按掩码规则处理），这样站长改个模型名不用重新粘贴密钥。
 */
interface EditableModelRow extends ModelRowView {
  rowId: number;
  apiKeyInput: string;
  testing: boolean;
  test: ModelTestResult | null;
}

const { apiGet, apiPost, apiPut, apiDelete } = useConsoleApi();

const overview = ref<DigestOverview | null>(null);
const history = ref<DigestSummary[]>([]);
const detail = ref<{ summary: DigestSummary; markdown: string; stats: any } | null>(null);
const preflight = ref<PreflightItem[] | null>(null);
const loading = ref(false);
const busy = ref(false);
const error = ref("");
const tip = ref("定时执行依赖服务器出网能力；正文较长时发布可能需要几分钟才会确认完成");

const classifyStatus = ref<ClassifyStatus | null>(null);
const classifyItems = ref<ClassifyCandidate[]>([]);
const classifyResult = ref<any>(null);
const classifyLoading = ref(false);

// ── 大模型通道 ──

/**
 * 用途选项：与后端 DigestAiTask 一一对应（key 是存储值，改它等于让老配置失效）。
 * hint 说明为什么值得分开——评分省钱、成稿看质量，这是「按任务绑定」的全部动机。
 */
const taskOptions = [
  { key: "score", label: "评分", hint: "给候选文章打分选材：一轮要跑几十次、每次只回一小段，适合便宜模型" },
  { key: "write", label: "成稿", hint: "逐篇写摘要与论点：一轮只跑两三次，但单次输出上千字、直接决定读者观感，建议留给强模型" },
  { key: "overview", label: "看点", hint: "日报开头的今日看点与背景铺垫" },
  { key: "classify", label: "归类", hint: "旧文章归类（人工点击触发）" },
  { key: "content", label: "简报", hint: "付费「AI 定制内容」的简报导语与推荐理由" },
  { key: "mp", label: "公众号稿", hint: "公众号文章的标题 / 摘要 / 导语（单次产出三件套，建议绑强模型）" },
  { key: "toutiao", label: "头条稿", hint: "今日头条号的标题候选与整篇正文：单次输出上千字，建议绑强模型" },
];

const modelView = ref<ModelView | null>(null);
const modelRows = ref<EditableModelRow[]>([]);
const modelDirty = ref(false);
const modelBusy = ref(false);
/** 行标识：给 v-for 用，与后端通道标识无关（草稿行还没有后端标识） */
let modelRowSeq = 0;

let timer: number | undefined;
/** 触发生成/发布后的自动刷新截止时间：后台任务在跑，但 running 标记可能还没翻过来 */
let graceUntil = 0;

const nextRunText = computed(() => {
  const value = overview.value?.nextRunAt;
  if (!value) return "未开启";
  const date = new Date(value);
  if (isNaN(date.getTime())) return "未开启";
  return formatDateTime(date);
});

const scheduleText = computed(() => {
  const value = overview.value;
  if (!value) return "";
  return `每天 ${value.scheduleTimes}（${value.timezone}）`;
});

const lastStatusText = computed(() => statusText(overview.value?.lastReport?.status));

const lastReportSub = computed(() => {
  const last = overview.value?.lastReport;
  if (!last) return "暂无运行记录";
  const parts = [last.date, `精选 ${last.articleCount ?? 0} 篇`];
  if (last.durationMs) parts.push(`${Math.round(last.durationMs / 1000)} 秒`);
  if (last.posts?.length) parts.push(`发布 ${last.posts.length} 篇`);
  parts.push(publishText(last.publishState));
  return parts.join(" · ");
});

/**
 * 进度标题。
 *
 * <p>必须区分三种状态：原先只判断 running，于是失败时标题仍是「进行中」，
 * 而旁边的说明文字写着失败原因——自相矛盾，也让人以为任务卡死了。</p>
 */
const progressTitle = computed(() => {
  const progress = overview.value?.progress;
  if (!progress) return "";
  if (progress.running) return "⏳ 进行中";
  return progress.stage === "FAILED" ? "❌ 生成失败" : "✅ 已完成";
});

const progressText = computed(() => {
  const progress = overview.value?.progress;
  if (!progress?.message) return "";
  // 已结束的提示只保留 5 分钟，避免长期挂着一条历史进度让人误以为还在跑
  if (!progress.running && progress.updatedAt) {
    const age = Date.now() - new Date(progress.updatedAt).getTime();
    if (age > 5 * 60 * 1000) return "";
  }
  return progress.message;
});

// ── 大模型通道 ──

const modelSummary = computed(() => {
  const view = modelView.value;
  if (!view) return "读取中…";
  if (!view.providers.length) {
    return view.channels.length ? "当前离线：沿用旧的主 / 兜底通道" : "当前离线：还没有可用的通道";
  }
  return `清单 ${view.providers.length} 条，可用 ${view.providers.filter((row) => row.usable).length} 条`;
});

/** 顶层提示：服务端算好的结论 + 「有未保存的修改」这一句页面才知道的信息 */
const modelHint = computed(() => {
  const view = modelView.value;
  if (!view) return "读取通道配置中…";
  const saved = view.summary;
  return modelDirty.value ? `${saved}（列表有未保存的修改，点「保存通道」后才会生效）` : saved;
});

const modelHints = computed(() => modelView.value?.hints || []);

const modelTaskLines = computed(() => modelView.value?.tasks || []);

const modelReady = computed(() => (modelView.value?.channels || []).some((row) => row.usable));

/** 清单非空就没必要导入（导入会把清单整个替换掉，容易丢掉刚配好的几条） */
const canImportLegacy = computed(
  () => !!modelView.value?.legacy?.configured && !modelRows.value.length
);

const modelLegacyNote = computed(() => {
  const legacy = modelView.value?.legacy;
  if (!legacy?.configured) return "";
  const parts: string[] = [];
  if (legacy.hasKey) {
    parts.push(`主：${legacy.apiBase || "（未填端点）"} / ${legacy.model || "（自动推断）"} / ${legacy.apiKeyMasked}`);
  }
  if (legacy.hasFallbackKey) {
    parts.push(`兜底：${legacy.fallbackApiBase || "https://api.openai.com/v1"} / ${legacy.fallbackModel || "（自动推断）"} / ${legacy.fallbackApiKeyMasked}`);
  }
  if (!parts.length) return "";
  const tail = modelView.value?.fromList
    ? "（清单已生效，下面这些旧字段不再被使用）"
    : "（清单为空时，实际调用走的就是这两条）";
  return `旧的主 / 兜底通道：${parts.join("；")}${tail}`;
});

function toEditableRow(row: ModelRowView): EditableModelRow {
  return {
    ...row,
    // 后端保证是数组；这里再兜一次，避免旧响应或缓存让 .includes 直接抛错
    tasks: Array.isArray(row.tasks) ? [...row.tasks] : [],
    rowId: ++modelRowSeq,
    apiKeyInput: "",
    testing: false,
    test: null,
  };
}

function applyModelView(view: ModelView) {
  modelView.value = view;
  modelRows.value = (view.providers || []).map(toEditableRow);
  modelDirty.value = false;
}

/** 内置供应商预设（后端 /digest/models/presets 下发）：选一家即自动填 API Base */
interface ProviderPreset {
  key: string;
  label: string;
  apiBase: string;
  models: string;
  note: string;
}

const providerPresets = ref<ProviderPreset[]>([]);

function presetByApiBase(apiBase: string): ProviderPreset | null {
  let normalized = (apiBase || "").trim().replace(/\/+$/, "").toLowerCase();
  if (normalized.endsWith("/chat/completions")) {
    normalized = normalized.slice(0, -"/chat/completions".length).replace(/\/+$/, "");
  }
  if (!normalized) return null;
  return (
    providerPresets.value.find((preset) => preset.apiBase.toLowerCase() === normalized) || null
  );
}

function presetKeyOf(apiBase: string): string {
  return presetByApiBase(apiBase)?.key || "";
}

function presetNoteOf(apiBase: string): string {
  const preset = presetByApiBase(apiBase);
  if (!preset) return "";
  const firstModel = (preset.models || "").split(" / ")[0];
  return `模型名如 ${firstModel}${preset.note ? "；" + preset.note : ""}`;
}

function applyPreset(row: EditableModelRow, event: Event) {
  const key = (event.target as HTMLSelectElement).value;
  const preset = providerPresets.value.find((item) => item.key === key);
  if (!preset) return;
  row.apiBase = preset.apiBase;
  if (!row.model.trim()) {
    row.model = (preset.models || "").split(" / ")[0] || "";
  }
  if (!row.label.trim()) {
    row.label = preset.label;
  }
  markModelDirty();
}

async function loadPresets() {
  try {
    const data: any = await apiGet("/digest/models/presets");
    providerPresets.value = data?.presets || [];
  } catch (e: any) {
    // 预设拉不到不影响配置（下拉为空 = 手填模式）
    providerPresets.value = [];
  }
}

function markModelDirty() {
  modelDirty.value = true;
}

/** 勾/去掉一条通道的某个用途；勾空 = 什么活都接（与后端「空 = 全部任务」一致） */
function toggleTask(row: EditableModelRow, key: string) {
  const index = row.tasks.indexOf(key);
  if (index >= 0) {
    row.tasks.splice(index, 1);
  } else {
    row.tasks.push(key);
  }
  modelDirty.value = true;
}

function addModel() {
  modelRows.value.push({
    key: "",
    label: "",
    apiBase: "",
    model: "",
    enabled: true,
    hasKey: false,
    apiKeyMasked: "",
    usable: false,
    source: "list",
    tasks: [],
    message: "",
    rowId: ++modelRowSeq,
    apiKeyInput: "",
    testing: false,
    test: null,
  });
  modelDirty.value = true;
}

function removeModel(index: number) {
  modelRows.value.splice(index, 1);
  modelDirty.value = true;
}

function moveModel(index: number, delta: number) {
  const target = index + delta;
  if (target < 0 || target >= modelRows.value.length) return;
  const rows = modelRows.value;
  const [moved] = rows.splice(index, 1);
  rows.splice(target, 0, moved);
  modelDirty.value = true;
}

/** 导入旧的主 / 兜底通道：把服务端解析出的兼容通道转成可编辑的行（Key 用掩码占位，保存时不会被覆盖） */
function importLegacy() {
  const legacyChannels = (modelView.value?.channels || []).filter((row) => row.source === "legacy");
  if (!legacyChannels.length) {
    tip.value = "旧的通道里没有可导入的内容（至少要有 Key）";
    return;
  }
  modelRows.value = legacyChannels.map(toEditableRow);
  modelDirty.value = true;
  tip.value = "已把旧的主 / 兜底通道填进清单，确认后点「保存通道」生效（保存后旧字段不再参与调用）";
}

function rowStateText(row: EditableModelRow): string {
  if (row.testing) return "测试中";
  if (row.test) return row.test.ok ? "连通" : "不通";
  if (!row.enabled) return "已关闭";
  if (!row.hasKey && !row.apiKeyInput.trim()) return "缺 Key";
  if (!row.apiBase.trim()) return "缺端点";
  return "已启用";
}

function rowStateClass(row: EditableModelRow): string {
  if (row.test) return row.test.ok ? "pill-ok" : "pill-bad";
  if (!row.enabled) return "pill-idle";
  if (!row.hasKey && !row.apiKeyInput.trim()) return "pill-bad";
  return "pill-ok";
}

async function loadModels() {
  loadPresets();
  try {
    applyModelView((await apiGet("/digest/models")) as ModelView);
  } catch (err: any) {
    // 通道读不到不该让整页不可用（运行历史仍要看），只在提示条里说明
    tip.value = err?.message ? `通道配置读取失败：${err.message}` : "通道配置读取失败";
  }
}

async function saveModels() {
  modelBusy.value = true;
  error.value = "";
  try {
    const payload = {
      providers: modelRows.value.map((row) => ({
        key: row.key,
        label: row.label,
        apiBase: row.apiBase,
        // 留空 = 不修改已保存的 Key（后端按掩码规则处理）
        apiKey: row.apiKeyInput.trim(),
        model: row.model,
        enabled: row.enabled,
        // 用途必须带上：它是可编辑字段，漏传等于「保存一下勾选就没了」
        tasks: [...row.tasks],
      })),
    };
    const view = (await apiPut("/digest/models", payload)) as ModelView;
    applyModelView(view);
    tip.value = view.summary || "通道已保存";
  } catch (err: any) {
    error.value = err?.message || "保存通道失败";
  } finally {
    modelBusy.value = false;
  }
}

const priceBusy = ref(false);

const modelPrices = computed(() => modelView.value?.prices || null);

/** 单价读取时间：解析失败就原样显示，不显示 Invalid Date */
function fetchedAtText(value: string): string {
  const date = new Date(value);
  return isNaN(date.getTime()) ? value : formatDateTime(date);
}

/**
 * 从官网读一次单价。
 *
 * <p>官方不提供价格 API，只能读那份价目表页面。这不是「让站长填价格」的手动入口，
 * 而是「价格涨了立刻同步」的按钮；内置表已经能开箱算钱。</p>
 */
async function refreshPrices() {
  priceBusy.value = true;
  error.value = "";
  try {
    const result: any = await apiPost("/digest/models/prices/refresh", {});
    if (result?.prices && modelView.value) {
      modelView.value = { ...modelView.value, prices: result.prices };
    }
    tip.value = result?.message || "已从官网刷新单价";
  } catch (err: any) {
    error.value = err?.message || "读取官网单价失败";
  } finally {
    priceBusy.value = false;
  }
}

const balances = ref<
  Array<{
    key: string;
    label: string;
    ok: boolean;
    currency: string | null;
    totalBalance: string | null;
    grantedBalance: string | null;
    toppedUpBalance: string | null;
    message: string;
  }>
>([]);
const balanceBusy = ref(false);

/**
 * 查账户余额（DeepSeek 官方接口，不消耗 token）。
 *
 * <p>余额是账号级数据，用来校验详细报告里的估算；不支持的端点会在结果里写「不支持」。</p>
 */
async function loadBalances() {
  balanceBusy.value = true;
  try {
    const result: any = await apiGet("/digest/models/balance");
    balances.value = result?.items || [];
    tip.value = result?.message || "";
  } catch (err: any) {
    error.value = err?.message || "余额查询失败";
  } finally {
    balanceBusy.value = false;
  }
}

async function testModel(row: EditableModelRow) {
  row.testing = true;
  row.test = null;
  try {
    row.test = (await apiPost("/digest/models/test", {
      key: row.key,
      label: row.label,
      apiBase: row.apiBase,
      apiKey: row.apiKeyInput.trim(),
      model: row.model,
      enabled: row.enabled,
      // 连通性与用途无关，带上只是为了让请求体与保存时保持同一形状
      tasks: [...row.tasks],
    })) as ModelTestResult;
  } catch (err: any) {
    row.test = {
      ok: false,
      key: row.key,
      label: row.label,
      apiBase: row.apiBase,
      model: row.model,
      status: null,
      latencyMs: null,
      reply: null,
      servedModel: null,
      totalTokens: null,
      message: err?.message || "测试失败",
      detail: null,
    };
  } finally {
    row.testing = false;
  }
}

/** 全部测试：串行跑，避免同时向多个端点发请求（也会让响应更难对上号） */
async function testAllModels() {
  modelBusy.value = true;
  try {
    for (const row of modelRows.value) {
      await testModel(row);
    }
    const failed = modelRows.value.filter((row) => row.test && !row.test.ok).length;
    tip.value = failed
      ? `测试完成：${failed} 条不通（看每行状态列的结论）`
      : `测试完成：${modelRows.value.length} 条通道都连通`;
  } finally {
    modelBusy.value = false;
  }
}

// ── 旧文章归类 ──

const classifySummary = computed(() => {
  const status = classifyStatus.value;
  if (!status) return "读取中…";
  if (status.digestPosts === 0) return "还没有 AI 日报生成的文章";
  return `AI 日报文章 ${status.digestPosts} 篇，已归类 ${status.classifiedPosts} 篇，还剩 ${status.pendingPosts} 篇未归类`;
});

/** 归类能不能用：没配密钥、开关关着、已经归完，三种情况各说各的原因 */
const classifyBlocked = computed(() => {
  const status = classifyStatus.value;
  if (!status) return false;
  return !status.aiReady || !status.autoCategoryEnabled || !status.autoTagEnabled;
});

const classifyHint = computed(() => {
  const status = classifyStatus.value;
  if (!status) return "读取归类进度中…";
  if (!status.aiReady) {
    return "还没配置 AI 接口密钥（插件设置 - AI 日报 - API Key），配置后才能判定文章主题";
  }
  if (!status.autoCategoryEnabled || !status.autoTagEnabled) {
    return "插件设置里关闭了「自动分类 / 自动打标」，这里不会给出建议。想用归类就先在 "
      + "「插件设置 - AI 日报 - 发布」里把这两项打开。";
  }
  if (status.pendingPosts === 0) {
    return "AI 日报生成的文章都已有分类，没有需要补的文章了";
  }
  return "只分析 AI 日报生成、且一篇分类都没有的文章；你自己写的文章、"
    + "以及已经归好类的文章都不会出现在下面的清单里。"
    + "分析只花 AI 调用、不改任何文章，勾选采纳后再点「应用」才落库。";
});

const previewHint = computed(() => {
  if (!classifyStatus.value?.aiReady) return "请先在插件设置里配置 AI 接口密钥";
  if (!classifyStatus.value?.pendingPosts) return "没有需要补分类的 AI 文章了";
  return "让 AI 判定下一批待归类 AI 文章属于哪个主题（只给建议，不改文章）";
});

const adoptedCount = computed(() => classifyItems.value.filter((item) => item.adopted).length);

const allAdopted = computed(
  () => classifyItems.value.length > 0 && classifyItems.value.every((item) => item.adopted)
);

const canPreview = computed(
  () => !!classifyStatus.value?.aiReady
    && (classifyStatus.value?.pendingPosts ?? 0) > 0
    && !!classifyStatus.value?.autoCategoryEnabled
);

function toggleAllAdopt(event: Event) {
  const checked = (event.target as HTMLInputElement).checked;
  classifyItems.value.forEach((item) => {
    item.adopted = checked;
  });
}

function classifyStateClass(state: string): string {
  if (state === "APPLIED") return "pill-ok";
  return state === "FAILED" ? "pill-bad" : "pill-idle";
}

function classifyStateText(state: string): string {
  if (state === "APPLIED") return "已归类";
  return state === "FAILED" ? "失败" : "跳过";
}

async function loadClassifyStatus() {
  try {
    classifyStatus.value = (await apiGet("/digest/classify/status")) as ClassifyStatus;
  } catch (err: any) {
    // 归类统计取不到不该影响整页（运行历史仍然可用），只在提示条里说一声
    tip.value = err?.message ? `归类进度读取失败：${err.message}` : "归类进度读取失败";
  }
}

/** 分析下一批：只预览不写库 */
async function previewClassify() {
  classifyLoading.value = true;
  error.value = "";
  classifyResult.value = null;
  try {
    const batchSize = classifyStatus.value?.batchSize || 15;
    const result: any = await apiPost(`/digest/classify/preview?limit=${batchSize}`, {});
    // 默认只勾选「AI 确实给出了建议」的那些：判不出来的留给站长自己看
    classifyItems.value = (result?.items || []).map((item: any) => ({
      ...item,
      adopted: !!(item.suggestedCategory || (item.suggestedKeywords || []).length),
    }));
    tip.value = result?.message || "";
    await loadClassifyStatus();
  } catch (err: any) {
    error.value = err?.message || "分析失败";
  } finally {
    classifyLoading.value = false;
  }
}

/** 应用勾选的建议：这是唯一会写文章的步骤，所以先确认 */
async function applyClassify() {
  const adopted = classifyItems.value.filter((item) => item.adopted);
  if (!adopted.length) {
    tip.value = "还没有勾选任何条目";
    return;
  }
  const names = adopted
    .map((item) => item.title || item.name)
    .slice(0, 8)
    .join("、");
  const confirmed = window.confirm(
    `将给 ${adopted.length} 篇文章追加分类/标签：
${names}${adopted.length > 8 ? " 等" : ""}

只追加，不影响文章原有的分类标签、标题与正文。确认应用？`
  );
  if (!confirmed) {
    tip.value = "已取消，未改动任何文章";
    return;
  }
  busy.value = true;
  error.value = "";
  try {
    const result: any = await apiPost("/digest/classify/apply", {
      items: adopted.map((item) => ({
        name: item.name,
        category: item.suggestedCategory || "",
        keywords: item.suggestedKeywords || [],
      })),
    });
    classifyResult.value = result;
    classifyItems.value = [];
    tip.value = result?.message || "已应用";
    await loadClassifyStatus();
  } catch (err: any) {
    error.value = err?.message || "应用失败";
  } finally {
    busy.value = false;
  }
}

const progressDone = computed(() => overview.value?.progress?.done ?? 0);

const progressTotal = computed(() => overview.value?.progress?.total ?? 0);

const progressPercent = computed(() => {
  const progress = overview.value?.progress;
  if (!progress || !progress.total) return null;
  return Math.min(100, Math.max(0, Math.round((progress.done / progress.total) * 100)));
});

const detailPosts = computed(() => (detail.value?.summary?.posts || []) as DigestPost[]);

/** 详情里的花费块：老记录没有这个字段（显示空，而不是 0） */
const detailCost = computed<DigestAiCost | null>(() => {
  const cost = detail.value?.stats?.aiCost as DigestAiCost | undefined;
  return cost && (cost.calls || 0) > 0 ? cost : null;
});

const detailCostText = computed(() => (detailCost.value ? formatMoney(detailCost.value.totals) : ""));

/**
 * 最近 30 次运行的合计花费。
 *
 * <p>在前端按已取回的列表求和：后端不必为此再加统计接口，列表里已经带了每次的币种金额。
 * 老记录没有花费（1.0.165 之前），直接跳过。</p>
 */
const historyCostText = computed(() => {
  const totals = new Map<string, number>();
  for (const item of history.value) {
    for (const money of item.aiCosts || []) {
      const parsed = Number(money.amount);
      if (!isNaN(parsed)) {
        totals.set(money.currency, (totals.get(money.currency) || 0) + parsed);
      }
    }
  }
  if (!totals.size) return "";
  const parts = [...totals.entries()].map(
    ([currency, amount]) => `${moneySymbol(currency)}${round4(amount)}`
  );
  return `最近 ${history.value.length} 次运行合计 ≈${parts.join(" + ")}（估算，按设置里的模型单价）`;
});

function formatMoney(items?: Money[] | null): string {
  if (!items?.length) return "";
  return `≈${items.map((money) => `${moneySymbol(money.currency)}${money.amount}`).join(" + ")}`;
}

/** 币种符号：与后端 DigestAiCostDTO.symbol 保持同一口径 */
function moneySymbol(currency?: string | null): string {
  const value = (currency || "").trim().toUpperCase();
  if (value === "CNY" || value === "RMB" || value === "¥") return "¥";
  if (value === "USD" || value === "$") return "$";
  return value ? `${value} ` : "";
}

function round4(value: number): string {
  return String(Number(value.toFixed(4)));
}

const statItems = computed(() => {  const stats = detail.value?.stats;
  if (!stats) return [] as Array<{ label: string; value: string }>;
  const items: Array<{ label: string; value: string }> = [];
  if (stats.totalFeeds) items.push({ label: "扫描源", value: `${stats.successFeeds}/${stats.totalFeeds}` });
  if (stats.totalArticles) items.push({ label: "抓取文章", value: `${stats.totalArticles} 篇` });
  if (stats.dedupedCount) items.push({ label: "去重", value: `-${stats.dedupedCount} 篇` });
  if (stats.selected) items.push({ label: "精选", value: `${stats.selected} 篇` });
  if (stats.minPerCategory > 0 && stats.requiredCategories?.length) {
    items.push({ label: "分类覆盖", value: coverageText(stats) });
  }
  if (stats.categorySummary) items.push({ label: "分类分布", value: stats.categorySummary });
  if (stats.supplementCount) items.push({ label: "补位", value: `${stats.supplementCount} 篇` });
  if (stats.actualAvgWords) items.push({ label: "平均字数", value: `${stats.actualAvgWords} 字` });
  if (stats.imageCount !== undefined) items.push({ label: "配图", value: `${stats.imageCount} 张` });
  if (stats.placeholderCoverCount) items.push({ label: "标题卡片", value: `${stats.placeholderCoverCount} 张` });
  return items;
});

/** 分类覆盖 X/Y：凑够「每个分类至少 N 篇」的分类数 */
function coverageText(stats: any) {
  const required = (stats.requiredCategories || []) as string[];
  const missing = (stats.missingCategories || []) as string[];
  return `${required.length - missing.length}/${required.length}`;
}

/** 本期没凑够篇数的分类（显示原始 key：清单是站长自己填的，他认得） */
const missingCategoryText = computed(() => {
  const stats = detail.value?.stats;
  const missing = (stats?.missingCategories || []) as string[];
  return missing.length ? `${missing.length} 个分类没凑够（${missing.join("、")}）` : "";
});

function formatDateTime(date: Date) {
  const pad = (n: number) => (n < 10 ? "0" : "") + n;
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function triggerText(slot: string | null | undefined) {
  if (!slot) return "-";
  return slot.startsWith("manual") ? `手动 ${slot.slice(7, 9)}:${slot.slice(9, 11)}` : slot;
}

function statusText(status: string | null | undefined) {
  switch (status) {
    case "RUNNING":
      return "运行中";
    case "SUCCESS":
      return "成功";
    case "FAILED":
      return "失败";
    default:
      return "待运行";
  }
}

function statusClass(status: string | null | undefined) {
  switch (status) {
    case "SUCCESS":
      return "pill-ok";
    case "FAILED":
      return "pill-bad";
    case "RUNNING":
      return "pill-run";
    default:
      return "pill-idle";
  }
}

function publishText(state: string | null | undefined, running = false) {
  switch (state) {
    case "PUBLISHING":
      // 进程重启等情况下可能残留「发布中」，没有任务在跑就按未发布显示
      return running ? "发布中" : "未发布";
    case "PUBLISHED":
      return "已发布";
    case "PARTIAL":
      return "部分成功";
    case "PENDING":
      return "待确认";
    case "FAILED":
      return "发布失败";
    case "SKIPPED":
      return "未发布";
    default:
      return "-";
  }
}

function publishClass(state: string | null | undefined, running = false) {
  switch (state) {
    case "PUBLISHING":
      return running ? "pill-run" : "pill-idle";
    case "PUBLISHED":
      return "pill-ok";
    case "FAILED":
      return "pill-bad";
    case "PARTIAL":
    case "PENDING":
      return "pill-run";
    default:
      return "pill-idle";
  }
}

function postsOf(item: DigestSummary): DigestPost[] {
  return item.posts || [];
}

/** 按钮文案要跟状态走：没发过是「发布」，发了一部分是「补发」，全发过了才是「重新发布」 */
function publishActionLabel(item: DigestSummary): string {
  const posts = postsOf(item);
  const created = posts.filter((post) => post.postName).length;
  if (created === 0) {
    return "发布";
  }
  const published = posts.filter((post) => post.state === "PUBLISHED").length;
  return published === posts.length ? "重新发布" : "补发";
}

function publishActionHint(item: DigestSummary): string {
  if (item.status === "RUNNING") {
    return "生成中，请稍候";
  }
  switch (publishActionLabel(item)) {
    case "发布":
      return "把这一期备好的文章写入 Halo（逐篇模式下一篇一篇文章）";
    case "补发":
      return "只补发还没成功的那几篇；已建稿的只重新触发发布";
    default:
      return "全部重新触发发布（不会重复建稿，也不会改回旧正文）";
  }
}

/** 清理未发布成功的残留文章：先预览，确认后再删（删除时会自动摘掉终态器） */
async function cleanupResidues() {
  busy.value = true;
  error.value = "";
  try {
    const preview: any = await apiPost("/digest/cleanup?dryRun=true", {});
    const count = preview?.count || 0;
    if (!count) {
      tip.value = "没有发现需要清理的残留文章";
      return;
    }
    const names = (preview.items || [])
      .map((item: any) => item.title || item.name)
      .slice(0, 8)
      .join("、");
    const confirmed = window.confirm(
      `找到 ${count} 篇未发布成功的残留文章：
${names}

确认删除？（会自动摘除终态器，否则会卡在「删除中」）`
    );
    if (!confirmed) {
      tip.value = `已取消，共 ${count} 篇残留（未删除）`;
      return;
    }
    const result: any = await apiPost("/digest/cleanup?dryRun=false", {});
    tip.value = result?.message || "清理完成";
  } catch (err: any) {
    error.value = err?.message || "清理失败";
  } finally {
    busy.value = false;
    await load();
  }
}

/** 删除一条运行记录（只删记录，不动已发布的文章） */
async function removeReport(item: DigestSummary) {
  const label = `${item.date} ${triggerText(item.slot)}`;
  if (!window.confirm(`确认删除这条运行记录？
${label}

只删除记录，已经发布的文章不受影响。`)) {
    return;
  }
  busy.value = true;
  error.value = "";
  try {
    await apiDelete(`/digest/reports/${item.name}`);
    if (detail.value?.summary?.name === item.name) {
      detail.value = null;
    }
    tip.value = `已删除运行记录：${label}`;
  } catch (err: any) {
    error.value = err?.message || "删除失败";
  } finally {
    busy.value = false;
    await load();
  }
}

async function runPreflight() {
  busy.value = true;
  error.value = "";
  try {
    const result: any = await apiGet("/digest/preflight");
    preflight.value = (result?.items || []) as PreflightItem[];
    const failed = preflight.value.filter((entry) => !entry.ok).length;
    tip.value = failed ? `自检发现 ${failed} 项不通过，按提示处理后即可发布` : "自检全部通过";
  } catch (err: any) {
    error.value = err?.message || "自检失败";
  } finally {
    busy.value = false;
  }
}

function primaryPostUrl(item: DigestSummary): string {
  const posts = postsOf(item);
  const withUrl = posts.find((post) => post.url);
  return (withUrl && withUrl.url) || item.postUrl || "";
}

async function load() {
  loading.value = true;
  error.value = "";
  try {
    const [overviewData, listData] = await Promise.all([
      apiGet("/digest/overview"),
      apiGet("/digest/reports", { limit: 30 }),
    ]);
    overview.value = overviewData as DigestOverview;
    history.value = (listData.items || []) as DigestSummary[];
    if (overview.value?.enabled === false) {
      tip.value = "AI 日报当前是关闭状态，请到「插件设置 - AI 日报」中开启后再生成";
    }
    // 归类统计与运行历史一起读：两者是同一页面的两块信息，分开刷新反而容易出现"历史变了、进度没变"
    await loadClassifyStatus();
  } catch (err: any) {
    error.value = err?.message || "加载失败";
  } finally {
    loading.value = false;
  }
}

async function triggerRun(publish: boolean) {
  busy.value = true;
  error.value = "";
  try {
    const result: any = await apiPost("/digest/run", { publish });
    tip.value = result?.message || "已开始生成";
    // 生成与发布都是后台异步跑的，触发后一段时间内保持自动刷新
    graceUntil = Date.now() + 10 * 60 * 1000;
  } catch (err: any) {
    error.value = err?.message || "触发失败";
  } finally {
    busy.value = false;
    await load();
  }
}

async function republish(name: string) {
  busy.value = true;
  error.value = "";
  try {
    const result: any = await apiPost(`/digest/reports/${name}/publish`, {});
    tip.value = result?.message || "已开始发布";
    graceUntil = Date.now() + 10 * 60 * 1000;
  } catch (err: any) {
    error.value = err?.message || "发布失败";
  } finally {
    busy.value = false;
    await load();
  }
}

async function openDetail(name: string) {
  error.value = "";
  try {
    detail.value = (await apiGet(`/digest/reports/${name}`)) as any;
  } catch (err: any) {
    error.value = err?.message || "详情加载失败";
  }
}

onMounted(() => {
  load();
  // 通道配置只在进页面时读一次：定时刷新会把正在编辑的清单冲掉
  loadModels();
  // 生成一次要 1-3 分钟、发布确认还可能更久，运行期间自动刷新，站长不用手动点
  timer = window.setInterval(() => {
    if (overview.value?.running || busy.value || Date.now() < graceUntil) {
      load();
    }
  }, 4000);
});

onUnmounted(() => {
  if (timer) window.clearInterval(timer);
});
</script>

<style scoped>
.page { padding: 24px 28px; width: 100%; box-sizing: border-box; }
.page-head { margin-bottom: 18px; }
.page-title { display: flex; align-items: center; gap: 12px; }
.title-emoji { font-size: 34px; }
.page-title h2 { margin: 0; font-size: 22px; background: linear-gradient(90deg, #5b6ee8, #9a4ee0); -webkit-background-clip: text; background-clip: text; color: transparent; }
.page-title p { margin: 4px 0 0; font-size: 12px; color: #8b90a0; }

.stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 14px; margin-bottom: 16px; }
.stat-card { display: flex; flex-direction: column; padding: 16px 18px; border-radius: 14px; color: #fff; box-shadow: 0 6px 16px rgba(80, 100, 200, 0.18); }
.stat-a { background: linear-gradient(135deg, #5b6ee8, #7d57e0); }
.stat-b { background: linear-gradient(135deg, #f2994a, #e8622c); }
.stat-c { background: linear-gradient(135deg, #4aa5f0, #4f74dd); }
.stat-d { background: linear-gradient(135deg, #2bb673, #1e9e8e); }
.stat-label { font-size: 12px; opacity: 0.9; }
.stat-value { font-size: 26px; font-weight: 700; margin: 2px 0; }
.stat-sub { font-size: 11px; opacity: 0.8; }

.toolbar { display: flex; align-items: center; gap: 10px; background: #fff; padding: 12px; border-radius: 12px; box-shadow: 0 2px 10px rgba(30, 40, 90, 0.06); margin-bottom: 14px; flex-wrap: wrap; }
.toolbar-tip { font-size: 12px; color: #8b90a0; margin-left: auto; }

button { padding: 7px 16px; border: none; border-radius: 8px; font-size: 13px; cursor: pointer; }
button:disabled { opacity: 0.5; cursor: not-allowed; }
.btn-primary { background: linear-gradient(90deg, #5b6ee8, #7d57e0); color: #fff; }
.btn-ghost { background: #eef1fb; color: #4c5480; }
.btn-link { background: none; color: #5b6ee8; padding: 2px 6px; font-size: 12.5px; }
/* 自定义类名：Halo 控制台全局有 .btn-danger，会把它渲染成红色实心按钮 */
.btn-link-danger { color: #d94a4a; }

.error-banner { background: #ffefef; color: #e05555; padding: 10px 14px; border-radius: 10px; margin-bottom: 12px; font-size: 13px; }
.warn-banner { background: #fff8e6; color: #b8860b; padding: 10px 14px; border-radius: 10px; margin-bottom: 12px; font-size: 13px; }

.table-card { background: #fff; border-radius: 14px; box-shadow: 0 4px 18px rgba(30, 40, 90, 0.08); overflow-x: auto; margin-bottom: 16px; }
.table-title { padding: 12px 14px 0; font-size: 14px; font-weight: 700; color: #3c4160; }
.tbl { width: 100%; border-collapse: collapse; font-size: 13px; }
.tbl th { text-align: left; padding: 12px 14px; color: #fff; font-weight: 600; background: linear-gradient(90deg, #5f6fe8, #8a5ce8); font-size: 12.5px; white-space: nowrap; }
.tbl td { padding: 10px 14px; border-bottom: 1px solid #f2f4fa; vertical-align: middle; }
.tbl tbody tr:nth-child(even) { background: #fafbff; }
.tbl tbody tr:hover { background: #f0f4ff; }
.empty { text-align: center; color: #9aa1b5; padding: 26px 0; }
.mono { font-family: monospace; font-size: 11px; color: #aab0c0; }
.time { color: #7a8095; font-size: 12px; white-space: nowrap; }
.ops { white-space: nowrap; }
.link { color: #5b6ee8; text-decoration: none; }
.link:hover { text-decoration: underline; }

.pill { display: inline-block; padding: 2px 9px; border-radius: 999px; font-size: 11.5px; line-height: 18px; }
.pill-ok { background: #e5f8ee; color: #17a05e; }
.pill-bad { background: #ffecec; color: #d94a4a; }
.pill-run { background: #fff4e5; color: #d1851a; }
.pill-idle { background: #eef1fb; color: #6b7290; }

.drawer-mask { position: fixed; inset: 0; background: rgba(20, 25, 45, 0.45); display: flex; justify-content: flex-end; z-index: 999; }
.drawer { width: min(860px, 92vw); height: 100%; background: #fff; padding: 18px 22px; box-sizing: border-box; overflow-y: auto; display: flex; flex-direction: column; gap: 12px; }
.drawer-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; flex-wrap: wrap; }
.drawer-head strong { margin-right: 8px; }
.stat-inline { display: flex; flex-wrap: wrap; gap: 8px; }
.stat-chip { background: #f4f6ff; color: #4c5480; border-radius: 8px; padding: 4px 10px; font-size: 12.5px; }

.post-list { background: #fbfcff; border: 1px solid #eef1fb; border-radius: 10px; padding: 10px 12px; }
.post-list-title { font-size: 12.5px; font-weight: 700; color: #3c4160; margin-bottom: 6px; }
.post-item { display: flex; align-items: baseline; gap: 8px; padding: 3px 0; font-size: 12.5px; flex-wrap: wrap; }
.post-msg { color: #b0b5c0; font-size: 11.5px; }

.reason-cell { max-width: 300px; }
.progress-head { display: flex; align-items: baseline; gap: 10px; padding: 12px 14px 8px; }
.progress-title { font-size: 13px; font-weight: 700; color: #3c4160; }
.progress-text { font-size: 13px; color: #5b6ee8; }
.progress-bar { margin: 0 14px 14px; height: 8px; border-radius: 999px; background: #eef1fb; overflow: hidden; }
.progress-fill { height: 100%; border-radius: 999px; background: linear-gradient(90deg, #5b6ee8, #9a4ee0); transition: width 0.4s ease; }
/* 生成阶段没有总量：用来回滑动的动画表示"正在进行" */
.progress-fill.indeterminate { width: 35%; animation: digest-slide 1.2s ease-in-out infinite; }
@keyframes digest-slide { 0% { margin-left: -35%; } 100% { margin-left: 100%; } }
.progress-meta { padding: 0 14px 12px; font-size: 12px; color: #9aa1b5; }
.fail-reason { display: inline-block; color: #d94a4a; font-size: 12px; line-height: 1.5; }
.preflight-list { padding: 12px 14px 4px; }
.preflight-item { display: flex; align-items: baseline; gap: 8px; padding: 5px 0; font-size: 13px; flex-wrap: wrap; }
.preflight-flag { font-weight: 700; }
.flag-ok { color: #17a05e; }
.flag-bad { color: #d94a4a; }
.preflight-name { color: #3c4160; font-weight: 600; min-width: 130px; }
.preflight-msg { color: #7a8095; }
.msg-bad { color: #d94a4a; }
.preflight-tip { padding: 0 14px 12px; font-size: 12px; color: #9aa1b5; }
.markdown-view { flex: 1; margin: 0; background: #fbfcff; border: 1px solid #eef1fb; border-radius: 10px; padding: 14px; font-size: 12.5px; line-height: 1.7; white-space: pre-wrap; word-break: break-word; color: #3c4160; }

/* 旧文章归类 */
.classify-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 14px 0; flex-wrap: wrap; }
.classify-title-box { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
.classify-title { font-size: 14px; font-weight: 700; color: #3c4160; }
.classify-summary { font-size: 12.5px; color: #6b7290; }
.classify-ops { display: flex; gap: 8px; }
.classify-hint { padding: 6px 14px 0; margin: 0; font-size: 12px; color: #9aa1b5; line-height: 1.6; }
.hint-bad { color: #d1851a; }
.classify-body { padding-top: 10px; }
.classify-bar { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 0 14px 10px; flex-wrap: wrap; }
.classify-check { display: inline-flex; align-items: center; gap: 6px; font-size: 12.5px; color: #4c5480; cursor: pointer; }
.classify-check input { cursor: pointer; }
.classify-post { max-width: 300px; }
.classify-note { max-width: 260px; color: #b0b5c0; font-size: 12px; }
.classify-result { padding: 4px 14px 14px; }

/* 大模型通道：表格里放输入框，所以要压住默认宽度，否则一整行会被撑得没法看 */
.model-prices { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; padding: 8px 14px 0; font-size: 12px; color: #6b7290; }
.model-prices-head { font-weight: 600; color: #4c5480; }
.model-price-chip { background: #f4f6ff; border-radius: 8px; padding: 3px 9px; }
.model-price-sub { color: #aab0c0; }
.model-balances { display: flex; flex-wrap: wrap; gap: 10px; padding: 8px 14px 0; font-size: 12.5px; color: #4c5480; }
.model-balance { background: #f4f6ff; border-radius: 8px; padding: 4px 10px; }
.model-balance-sub { color: #8b90a0; }
.model-taskbar { display: flex; flex-wrap: wrap; gap: 6px; padding: 8px 14px 0; }
.model-task-chip { background: #eef1fb; color: #4c5480; border-radius: 999px; padding: 3px 10px; font-size: 12px; }
.model-task-chip .chip-model { color: #8b90a0; }
/* 回退与无通道要一眼看出来：前者是「还能跑但花了不该花的钱」，后者是「这一步会失败」 */
.model-task-chip.chip-warn { background: #fff8e6; color: #b8860b; }
.model-task-chip.chip-bad { background: #ffefef; color: #d94a4a; }
.model-task { display: inline-flex; align-items: center; gap: 3px; margin-right: 10px; font-size: 12px; color: #4c5480; cursor: pointer; white-space: nowrap; }
.model-task input { cursor: pointer; }
.model-task-none { font-size: 12px; color: #aab0c0; }
.model-hints { margin: 6px 14px 0; padding-left: 18px; font-size: 12px; color: #9aa1b5; line-height: 1.7; }
.model-input { width: 130px; padding: 5px 8px; border: 1px solid #e2e6f3; border-radius: 7px; font-size: 12.5px; color: #3c4160; outline: none; }
.model-input:focus { border-color: #8a9cf0; box-shadow: 0 0 0 2px rgba(120, 140, 240, 0.14); }
.model-input-wide { width: 210px; }
.model-state { max-width: 260px; }
.model-test-msg { display: block; margin-top: 3px; font-size: 11.5px; line-height: 1.5; color: #6b7290; word-break: break-word; }
.model-preset { width: 100%; margin-bottom: 4px; padding: 4px 6px; border: 1px solid #e2e6f3; border-radius: 7px; font-size: 12px; color: #3c4160; background: #fbfcff; }
.model-preset-note { display: block; margin-top: 3px; font-size: 11px; line-height: 1.5; color: #8a90a4; max-width: 260px; }
.model-test-url { display: block; margin-top: 2px; color: #9aa1b5; font-family: monospace; font-size: 11px; }
.model-foot { display: flex; align-items: center; gap: 12px; padding: 12px 14px; flex-wrap: wrap; }
.model-foot-tip { font-size: 12px; color: #9aa1b5; }
.model-legacy { margin: 0; padding: 0 14px 14px; font-size: 12px; color: #8b90a0; line-height: 1.6; word-break: break-word; }

/* AI 花费：历史合计一行 + 详情里的按任务表与余额差 */
.cost-summary { margin: 0 0 10px; padding: 8px 12px; background: #fff8e6; color: #b8860b; border-radius: 10px; font-size: 12.5px; }
.cost-block { background: #fbfcff; border: 1px solid #eef1fb; border-radius: 10px; padding: 10px 12px; }
.cost-total { margin-left: 8px; color: #17a05e; font-weight: 600; }
.cost-tbl { font-size: 12.5px; }
.cost-tbl th { padding: 8px 10px; }
.cost-tbl td { padding: 6px 10px; }
.cost-line { display: flex; flex-wrap: wrap; gap: 10px; align-items: baseline; padding: 4px 0; font-size: 12.5px; color: #4c5480; }
.cost-balance { color: #17a05e; }
.cost-note { margin: 6px 0 0; font-size: 11.5px; color: #9aa1b5; line-height: 1.7; }</style>
