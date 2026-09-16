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
            <th>文章</th>
            <th>未发布原因</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading"><td colspan="9" class="empty">⏳ 加载中…</td></tr>
          <tr v-else-if="!history.length"><td colspan="9" class="empty">🎈 还没有运行记录，点「立即生成」试试</td></tr>
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

        <div v-if="detailPosts.length" class="post-list">
          <div class="post-list-title">发布明细（{{ detailPosts.length }} 篇）</div>
          <div v-for="(post, idx) in detailPosts" :key="post.postName || idx" class="post-item">
            <span class="pill" :class="publishClass(post.state)">{{ publishText(post.state) }}</span>
            <a v-if="post.url" :href="post.url" target="_blank" rel="noopener" class="link">{{ post.title || post.postName }}</a>
            <span v-else>{{ post.title || post.postName }}</span>
            <span v-if="post.message && post.state !== 'PUBLISHED'" class="post-msg">{{ post.message }}</span>
          </div>
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

const { apiGet, apiPost, apiDelete } = useConsoleApi();

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

const statItems = computed(() => {  const stats = detail.value?.stats;
  if (!stats) return [] as Array<{ label: string; value: string }>;
  const items: Array<{ label: string; value: string }> = [];
  if (stats.totalFeeds) items.push({ label: "扫描源", value: `${stats.successFeeds}/${stats.totalFeeds}` });
  if (stats.totalArticles) items.push({ label: "抓取文章", value: `${stats.totalArticles} 篇` });
  if (stats.selected) items.push({ label: "精选", value: `${stats.selected} 篇` });
  if (stats.actualAvgWords) items.push({ label: "平均字数", value: `${stats.actualAvgWords} 字` });
  if (stats.imageCount !== undefined) items.push({ label: "配图", value: `${stats.imageCount} 张` });
  return items;
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
</style>
