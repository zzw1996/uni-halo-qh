<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useConsoleApi, formatTime } from "../api";

const { apiGet, apiPost, apiDelete } = useConsoleApi();

interface MpStatus {
  enabled: boolean;
  configured: boolean;
  appIdMasked: string;
  author: string;
  deliveryModeLabel: string;
  contentSourceModeLabel: string;
  contentGranularityLabel: string;
  needOpenComment: boolean;
  uploadImages: boolean;
  postToSite: boolean;
  scheduleEnabled: boolean;
  scheduleTimes: string;
  timezone: string;
  nextRunAt: string | null;
  generating: boolean;
  stage: string;
  generateStartedAt: string | null;
  generateError: string;
  pushing: boolean;
  pushStage: string;
  pushCurrent: number;
  pushTotal: number;
  pushError: string;
  pushResults: { name: string; title: string; ok: boolean; message: string }[];
  selfChecked: boolean;
  pushAvailable: boolean;
  selfCheckSummary: string;
  directions: { key: string; label: string; emoji: string; desc: string }[];
  counts: Record<string, number>;
  lastGeneratedAt: string | null;
  titleLimit: number;
  digestLimit: number;
}

interface MpIssue {
  name: string;
  sourceKind: string;
  date: string;
  direction: string;
  directionLabel: string;
  trigger: string;
  title: string;
  digest: string;
  digestWords: number | null;
  articleCount: number | null;
  state: string;
  pushMode: string;
  articleUrl: string;
  sitePostUrl: string;
  cover: string;
  imageNote: string;
  /** 内容完整性说明：哪几篇没拿到完整成稿、缺哪几件（空 = 完整） */
  contentNote: string;
  error: string;
  errcode: number | null;
  createdAt: string;
  updatedAt: string;
}

interface CheckItem {
  item: string;
  ok: boolean;
  message: string;
}

interface BackfillRow {
  name: string;
  date: string;
  slot: string;
  status: string;
  articleCount: number | null;
  postUrl: string;
  error: string;
  mpIssues: MpIssue[];
}

interface DateGroup {
  date: string;
  issues: MpIssue[];
}

const status = ref<MpStatus | null>(null);
const issues = ref<MpIssue[]>([]);
const reports = ref<BackfillRow[]>([]);
const checks = ref<CheckItem[]>([]);
const loading = ref(false);
const busy = ref("");
const error = ref("");
const notice = ref("");
const logs = ref<Record<string, string>>({});
const preview = ref<{ title: string; html: string; digest: string; digestWords: number | null } | null>(null);

let timer: ReturnType<typeof setTimeout> | null = null;
/** 手动收起「上次推送结果」（下一次推送开始时会自动重新出现） */
const pushDismissed = ref(false);

const stats = computed(() => {
  const counts = status.value?.counts || {};
  return [
    { label: "稿件总数", value: counts.total || 0, sub: "最近 200 条记录" },
    { label: "待推送", value: counts.GENERATED || 0, sub: "已生成、还没进草稿箱" },
    {
      label: "已推送",
      value: (counts.DRAFTED || 0) + (counts.PUBLISHED || 0) + (counts.MASSED || 0),
      sub: "草稿箱 / 已发布 / 已群发",
    },
    { label: "空稿或失败", value: (counts.EMPTY || 0) + (counts.FAILED || 0), sub: "看下方原因" },
  ];
});

/** 稿件按日期分组（同一天 = 同一次生成的各方向，整组可一键推送） */
const groups = computed<DateGroup[]>(() => {
  const map = new Map<string, MpIssue[]>();
  for (const issue of issues.value) {
    const key = issue.date || "-";
    if (!map.has(key)) map.set(key, []);
    map.get(key)!.push(issue);
  }
  return Array.from(map.entries()).map(([date, list]) => ({ date, issues: list }));
});

const pushableCount = (group: DateGroup) =>
  group.issues.filter((issue) => !issue.draftMediaId && issue.state !== "EMPTY").length;

function stateText(state: string) {
  const map: Record<string, string> = {
    GENERATING: "生成中",
    GENERATED: "已生成",
    DRAFTED: "已进草稿箱",
    PUBLISHED: "已发布",
    MASSED: "已群发",
    EMPTY: "空稿",
    FAILED: "失败",
  };
  return map[state] || state || "-";
}

function stateClass(state: string) {
  if (state === "PUBLISHED" || state === "MASSED" || state === "DRAFTED") return "tag-on";
  if (state === "FAILED" || state === "EMPTY") return "tag-off";
  return "tag-mid";
}

function flash(message: string, ok = true) {
  notice.value = ok ? message : "";
  error.value = ok ? "" : message;
}

async function loadStatus() {
  try {
    status.value = await apiGet("/mp/status");
    if (status.value?.pushing) {
      pushDismissed.value = false;
    }
    scheduleRefresh(!!(status.value?.generating || status.value?.pushing));
  } catch (e: any) {
    error.value = e?.message || String(e);
  }
}

async function loadIssues() {
  try {
    issues.value = await apiGet("/mp/issues", { limit: 60 });
  } catch (e: any) {
    error.value = e?.message || String(e);
  }
  // 列表刷新后把已展开的推送日志重新取一遍（推送刚结束时日志才有内容）
  for (const name of Object.keys(logs.value)) {
    loadLog(name, true);
  }
}

async function loadReports() {
  try {
    const data: any = await apiGet("/mp/backfill", { limit: 20 });
    reports.value = data?.reports || [];
  } catch (e: any) {
    error.value = e?.message || String(e);
  }
}

async function refreshAll() {
  loading.value = true;
  error.value = "";
  await Promise.all([loadStatus(), loadIssues(), loadReports()]);
  loading.value = false;
}

function scheduleRefresh(active: boolean) {
  if (timer) {
    clearTimeout(timer);
    timer = null;
  }
  if (active) {
    timer = setTimeout(() => {
      loadStatus();
      loadIssues();
    }, 4000);
  }
}

async function generate() {
  try {
    const r: any = await apiPost("/mp/issues/generate");
    flash(r?.message || "已开始生成");
    setTimeout(() => {
      loadStatus();
      loadIssues();
    }, 1500);
  } catch (e: any) {
    flash(e?.message || String(e), false);
  }
}

async function selfCheck(deep: boolean) {
  busy.value = deep ? "deep" : "check";
  try {
    const data: any = await apiGet("/mp/selfcheck", deep ? { deep: "true" } : {});
    checks.value = data?.items || [];
    const failed = checks.value.filter((item) => !item.ok).length;
    flash(failed ? `自检有 ${failed} 项需要注意（见下方结果）` : "自检全部通过");
  } catch (e: any) {
    flash(e?.message || String(e), false);
  } finally {
    busy.value = "";
  }
}

async function pushIssue(issue: MpIssue, force = false) {
  if (force && !confirm("重新推送会在公众号草稿箱新建一份（旧草稿如还在，需你到后台自行删除）。继续？")) return;
  busy.value = issue.name;
  try {
    const r: any = await apiPost(`/mp/issues/${encodeURIComponent(issue.name)}/push${force ? "?force=true" : ""}`);
    flash(r?.message || "已开始推送");
    setTimeout(() => {
      loadStatus();
      loadIssues();
    }, 1500);
  } catch (e: any) {
    flash(e?.message || String(e), false);
  } finally {
    busy.value = "";
  }
}

/** 推送当天全部方向：每方向一篇独立草稿 */
async function pushAll(group: DateGroup, force = false) {
  const count = force ? group.issues.length : pushableCount(group);
  if (count === 0) {
    flash("这一天的稿件都已经推送过了", false);
    return;
  }
  if (!confirm(`把 ${group.date} 的 ${count} 个方向分别推成独立草稿？${force ? "（会新建草稿，旧的需你自行删除）" : ""}`)) return;
  busy.value = group.date;
  try {
    const r: any = await apiPost(`/mp/issues/push-all?date=${encodeURIComponent(group.date)}${force ? "&force=true" : ""}`);
    flash(r?.message || "已开始推送");
    setTimeout(() => {
      loadStatus();
      loadIssues();
    }, 1500);
  } catch (e: any) {
    flash(e?.message || String(e), false);
  } finally {
    busy.value = "";
  }
}

async function removeIssue(issue: MpIssue) {
  if (!confirm("删除这条稿件记录？公众号那边的草稿/文章不受影响。")) return;
  try {
    await apiDelete(`/mp/issues/${encodeURIComponent(issue.name)}`);
    flash("已删除");
    loadIssues();
  } catch (e: any) {
    flash(e?.message || String(e), false);
  }
}

async function openPreview(issue: MpIssue) {
  try {
    const d: any = await apiGet(`/mp/issues/${encodeURIComponent(issue.name)}`);
    preview.value = {
      title: d?.summary?.title || "(无标题)",
      html: d?.wechatHtml || d?.html || "<p>（没有正文）</p>",
      digest: d?.summary?.digest || "",
      digestWords: d?.summary?.digestWords ?? null,
    };
  } catch (e: any) {
    flash(e?.message || String(e), false);
  }
}

function closePreview() {
  preview.value = null;
}

/** 展开/收起推送日志（按需拉详情） */
async function toggleLog(issue: MpIssue) {
  if (logs.value[issue.name] !== undefined) {
    const copy = { ...logs.value };
    delete copy[issue.name];
    logs.value = copy;
    return;
  }
  await loadLog(issue.name, false);
}

async function loadLog(name: string, silent: boolean) {
  try {
    const d: any = await apiGet(`/mp/issues/${encodeURIComponent(name)}`);
    const text = d?.pushLog || "";
    if (text || !silent) {
      logs.value = { ...logs.value, [name]: text || "（还没有推送记录：点「推草稿箱」后会逐步记下来）" };
    }
  } catch (e: any) {
    if (!silent) flash(e?.message || String(e), false);
  }
}

async function copyBody(issue: MpIssue) {
  try {
    flash("正在准备正文（把图片换成微信图床地址，可用时）…");
    const prepared: any = await apiPost(`/mp/issues/${encodeURIComponent(issue.name)}/prepare?force=true`);
    const ok = await copyRich(prepared?.wechatHtml || "", "");
    const note = prepared?.imageNote || "";
    if (ok) flash(note ? `已复制正文；${note}` : "已复制正文（富文本）：到公众号编辑器 Ctrl+V 粘贴即可；摘要请单独复制");
    else flash("复制失败：浏览器拒绝了剪贴板写入，请打开预览手动全选复制", false);
    loadIssues();
  } catch (e: any) {
    flash(e?.message || String(e), false);
  }
}

/** 打开封面图（手动发布时在公众号编辑器里上传它） */
async function downloadCover(issue: MpIssue) {
  try {
    const d: any = await apiGet(`/mp/issues/${encodeURIComponent(issue.name)}`);
    const url = d?.summary?.cover;
    if (!url) {
      flash("这一期没有封面图", false);
      return;
    }
    window.open(url, "_blank");
    flash("封面已在新标签页打开：右键另存为，或在公众号编辑器里直接上传它");
  } catch (e: any) {
    flash(e?.message || String(e), false);
  }
}

async function copyText(text: string, what: string) {
  try {
    await navigator.clipboard.writeText(text || "");
    flash(`已复制${what}`);
  } catch (e: any) {
    flash("复制失败：浏览器拒绝了剪贴板写入", false);
  }
}

async function copyRich(html: string, plain: string) {
  const text = plain || html.replace(/<[^>]+>/g, "");
  try {
    if (window.ClipboardItem) {
      await navigator.clipboard.write([
        new ClipboardItem({
          "text/html": new Blob([html], { type: "text/html" }),
          "text/plain": new Blob([text], { type: "text/plain" }),
        }),
      ]);
      return true;
    }
  } catch (e) {
    // 落到下面的兼容分支
  }
  return false;
}

async function buildBackfill(row: BackfillRow, mode: string) {
  busy.value = row.name;
  try {
    const r: any = await apiPost("/mp/backfill", { reportName: row.name, mode });
    flash(r?.message || "已生成公众号稿");
    await Promise.all([loadReports(), loadIssues(), loadStatus()]);
  } catch (e: any) {
    flash(e?.message || String(e), false);
  } finally {
    busy.value = "";
  }
}

onMounted(refreshAll);
onBeforeUnmount(() => {
  if (timer) clearTimeout(timer);
});
</script>

<template>
  <div class="page mp-push-root">
    <div class="page-head">
      <div class="page-title">
        <span class="title-emoji">📮</span>
        <div>
          <h2>公众号</h2>
          <p>
            按方向（默认 AI 发展 / AI 安全 / Vibe Coding）<b>每个方向生成一篇独立文章</b>，每篇保证带一张符合微信要求的配图。
            生成只在点「立即生成」时执行（定时生成默认关闭）；推送默认只进草稿箱，也可用「复制正文」把排版好的富文本粘进公众号编辑器手动发布。
            配置项在「插件设置 → 微信公众号」。
          </p>
        </div>
      </div>
      <button class="btn-primary refresh" :disabled="loading" @click="refreshAll">刷新</button>
    </div>

    <div v-if="error" class="error-banner">⚠️ {{ error }}</div>
    <div v-if="notice" class="notice-banner">✅ {{ notice }}</div>

    <div class="panel">
      <div class="panel-head">
        <h3>接入状态</h3>
        <span class="panel-sub" v-if="status">
          总开关：{{ status.enabled ? "已开启" : "未开启" }} ·
          凭据：{{ status.configured ? status.appIdMasked || "已配置" : "未填写 AppID/AppSecret" }} ·
          推送落点：{{ status.deliveryModeLabel }} ·
          阅读原文：{{ status.contentSourceModeLabel }} ·
          文章粒度：{{ status.contentGranularityLabel }} ·
          定时生成：{{ status.scheduleEnabled ? `已开启（${status.scheduleTimes}，仅该时刻后 30 分钟内触发）` : "已关闭（只点按钮才生成）" }}
        </span>
        <button class="btn-ghost" :disabled="busy === 'check'" @click="selfCheck(false)">🩺 接入自检</button>
        <button class="btn-ghost" :disabled="busy === 'deep'" @click="selfCheck(true)">🔬 深度自检</button>
        <button class="btn-primary" :disabled="status?.generating" @click="generate">⚡ 立即生成</button>
      </div>
      <p class="panel-sub" style="display: block; line-height: 1.8">
        方向：<b>{{ (status?.directions || []).map((d) => `${d.emoji} ${d.label}`).join("　") || "-" }}</b>
        　·　生成约需 1-3 分钟（抓取 92 个源 + AI 评分 + 逐方向成稿），触发后本页会自动刷新。
        「深度自检」会创建一条<b>带图</b>测试草稿并立即删除，用来确认草稿箱接口对当前账号是否可用。
      </p>
      <div v-if="status?.generating" class="progress-line">
        <span class="spinner"></span> 正在生成：{{ status.stage || "进行中…" }}
      </div>
      <p v-if="status?.generateError" class="err">上次生成失败：{{ status.generateError }}</p>
      <div v-if="status?.selfChecked && !status?.pushAvailable" class="warn-banner">
        ⚠️ 最近一次自检结论：{{ status.selfCheckSummary }}
        <br />草稿箱这条路走不通时，用「📋 复制正文」+「封面上传」手动发布即可（不依赖任何微信接口）。
      </div>

      <div
        v-if="!pushDismissed && (status?.pushing || (status?.pushResults?.length ?? 0) > 0 || !!status?.pushError)"
        class="push-progress"
      >
        <div class="push-progress-head">
          <span class="spinner" v-if="status?.pushing"></span>
          <b>{{ status?.pushing ? "正在推送" : "上次推送结果" }}</b>
          <span v-if="status?.pushTotal">{{ status.pushCurrent }}/{{ status.pushTotal }}</span>
          <span class="push-stage" v-if="status?.pushing">{{ status?.pushStage }}</span>
          <button v-if="!status?.pushing" class="btn-ghost mini dismiss" @click="pushDismissed = true">收起</button>
        </div>
        <div class="push-bar" v-if="status?.pushing && status?.pushTotal">
          <div class="push-bar-inner" :style="{ width: Math.round(((status?.pushCurrent || 0) / (status?.pushTotal || 1)) * 100) + '%' }"></div>
        </div>
        <div v-if="status?.pushError" class="err">推送失败：{{ status.pushError }}</div>
        <div v-for="item in status?.pushResults || []" :key="item.name" class="push-item">
          <span :class="item.ok ? 'pf-ok' : 'pf-bad'">{{ item.ok ? "✔" : "✘" }}</span>
          <span class="push-item-title">{{ item.title || item.name }}</span>
          <span class="push-item-msg" :class="{ bad: !item.ok }">{{ item.message }}</span>
        </div>
      </div>
    </div>

    <div class="stat-grid">
      <div v-for="(item, idx) in stats" :key="idx" class="stat-card" :class="`stat-${idx}`">
        <span class="stat-label">{{ item.label }}</span>
        <span class="stat-value">{{ item.value }}</span>
        <span class="stat-sub">{{ item.sub }}</span>
      </div>
    </div>

    <div v-if="checks.length" class="panel">
      <div class="panel-head">
        <h3>自检结果</h3>
        <span class="panel-sub">逐项真实调用微信接口；不通过的按提示处理（多为 IP 白名单或账号权限）</span>
        <button class="btn-ghost" @click="checks = []">收起</button>
      </div>
      <div class="check-list">
        <div v-for="(item, idx) in checks" :key="idx" class="check-item">
          <span class="check-flag" :class="item.ok ? 'ok' : 'bad'">{{ item.ok ? "✔" : "✘" }}</span>
          <span class="check-name">{{ item.item }}</span>
          <span class="check-msg" :class="{ bad: !item.ok }">{{ item.message }}</span>
        </div>
      </div>
    </div>

    <div v-for="group in groups" :key="group.date" class="panel">
      <div class="panel-head">
        <h3>{{ group.date }} 的稿件</h3>
        <span class="panel-sub">{{ group.issues.length }} 个方向 · 每方向一篇独立文章</span>
        <button class="btn-ghost" @click="loadIssues">刷新</button>
        <button
          class="btn-primary"
          :disabled="busy === group.date || !pushableCount(group)"
          @click="pushAll(group)"
        >
          📤 推送当天全部方向（各自独立草稿）
        </button>
      </div>
      <div class="table-card">
        <table class="tbl">
          <thead>
            <tr>
              <th style="min-width: 130px">方向</th>
              <th>标题 / 配图</th>
              <th>摘要</th>
              <th>状态</th>
              <th style="min-width: 280px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="issue in group.issues" :key="issue.name">
              <td>
                <span class="direction-chip">{{ issue.directionLabel || (issue.sourceKind === "BACKFILL" ? "历史补推" : "-") }}</span>
                <span class="mono">{{ issue.trigger === "scheduled" ? "定时触发" : issue.trigger === "manual" ? "手动触发" : "" }}</span>
              </td>
              <td>
                <span class="issue-title">{{ issue.title || "(无标题)" }}</span>
                <span class="muted">{{ issue.articleCount != null ? `精选 ${issue.articleCount} 篇` : "" }}</span>
                <span v-if="issue.imageNote" class="warn">🖼 {{ issue.imageNote }}</span>
                <span v-if="issue.contentNote" class="warn">⚠️ {{ issue.contentNote }}</span>
              </td>
              <td style="max-width: 220px">
                <span class="digest">{{ (issue.digest || "").slice(0, 56) }}</span>
                <span class="mono">摘要 {{ issue.digestWords ?? "-" }}/120 字</span>
              </td>
              <td>
                <span class="tag" :class="stateClass(issue.state)">{{ stateText(issue.state) }}</span>
                <a v-if="issue.articleUrl" :href="issue.articleUrl" target="_blank" class="link">公众号文章</a>
                <a v-if="issue.sitePostUrl" :href="issue.sitePostUrl" target="_blank" class="link">站点文章</a>
                <span v-if="issue.error" class="err">{{ issue.error }}</span>
              </td>
              <td class="actions">
                <button class="btn-ghost mini" @click="openPreview(issue)">👁 预览</button>
                <button class="btn-ghost mini" @click="copyBody(issue)">📋 复制正文</button>
                <button class="btn-ghost mini" @click="copyText(issue.digest, '摘要')">📋 摘要</button>
                <button class="btn-ghost mini" @click="copyText(issue.title, '标题')">📋 标题</button>
                <button class="btn-ghost mini" @click="downloadCover(issue)">⬇ 封面</button>
                <button class="btn-primary mini" :disabled="busy === issue.name" @click="pushIssue(issue)">📤 推草稿箱</button>
                <button class="btn-ghost mini" @click="pushIssue(issue, true)">↻ 重推</button>
                <button class="btn-ghost mini" @click="toggleLog(issue)">📜 日志</button>
                <button class="btn-ghost mini danger" @click="removeIssue(issue)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-for="issue in group.issues" :key="`log-${issue.name}`">
        <pre v-if="logs[issue.name] !== undefined" class="push-log">{{ logs[issue.name] }}</pre>
      </div>
    </div>

    <div v-if="!groups.length" class="panel">
      <div class="empty">🎈 还没有稿件：点右上角「立即生成」产出今天的第一批</div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <h3>历史 AI 日报补推</h3>
        <span class="panel-sub">用那一期当时固化的内容生成公众号稿（不重新抓取、不花 AI 额度）；默认逐篇，每篇一篇文章</span>
        <button class="btn-ghost" @click="loadReports">刷新期次</button>
      </div>
      <div class="table-card">
        <table class="tbl">
          <thead>
            <tr>
              <th>日期 / 时段</th>
              <th>状态</th>
              <th>站点文章</th>
              <th>公众号稿</th>
              <th style="min-width: 210px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!reports.length">
              <td colspan="5" class="empty">🎈 没有找到日报记录</td>
            </tr>
            <tr v-for="row in reports" :key="row.name">
              <td>
                <span>{{ row.date || "-" }}</span>
                <span class="mono">{{ row.slot || "" }}</span>
              </td>
              <td>
                <span>{{ row.status || "-" }}</span>
                <span class="mono">{{ row.articleCount != null ? `${row.articleCount} 篇` : "" }}</span>
                <span v-if="row.error" class="err">{{ row.error }}</span>
              </td>
              <td>
                <a v-if="row.postUrl" :href="row.postUrl" target="_blank" class="link">打开</a>
                <span v-else class="muted">—</span>
              </td>
              <td>
                <template v-if="row.mpIssues?.length">
                  <span v-for="item in row.mpIssues" :key="item.name" class="mp-chip">
                    <span class="tag" :class="stateClass(item.state)">{{ stateText(item.state) }}</span>
                    <a href="javascript:void(0)" @click="openPreview(item)">{{ (item.title || "").slice(0, 20) }}</a>
                  </span>
                </template>
                <span v-else class="muted">未生成</span>
              </td>
              <td class="actions">
                <button class="btn-ghost mini" :disabled="busy === row.name" @click="buildBackfill(row, 'per-article')">
                  逐篇生成
                </button>
                <button class="btn-ghost mini" :disabled="busy === row.name" @click="buildBackfill(row, 'merged')">
                  整期合成一篇
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="preview" class="preview-mask" @click.self="closePreview">
      <div class="preview-panel">
        <div class="preview-head">
          <span class="preview-title">{{ preview.title }}</span>
          <button class="btn-primary mini" @click="copyRich(preview.html, '')">📋 复制正文（富文本）</button>
          <button class="btn-ghost mini" @click="closePreview">关闭</button>
        </div>
        <iframe class="preview-frame" :srcdoc="preview.html"></iframe>
        <div class="preview-foot">
          预览即公众号正文的渲染效果（图片仍是站点地址；「复制正文」会自动换成微信图床地址）。
          摘要 {{ preview.digestWords ?? "-" }}/120 字：{{ preview.digest }}
        </div>
      </div>
    </div>
  </div>
</template>

<style>
/*
 * 故意不用 <style scoped>：Halo 给插件的 console 静态资源打的是
 * Cache-Control: max-age=31536000（一年），而 style.css 的 URL 不随插件版本变化 ——
 * 用户浏览器会一直用旧样式表。scoped 的 data-v-xxxx 随 .vue 内容变化，哈希一变旧样式表
 * 就整页匹配不上，页面看起来「样式全丢」（线上踩过：公众号页只剩纯文本、表格串列）。
 * 所以改用「根类名前缀」这种与构建无关的作用域：旧样式表照样能套上新页面。
 */
.mp-push-root.page { padding: 24px 28px; width: 100%; box-sizing: border-box; }
.mp-push-root .page-head { margin-bottom: 18px; }
.mp-push-root .page-title { display: flex; align-items: center; gap: 12px; }
.mp-push-root .title-emoji { font-size: 34px; }
.mp-push-root .page-title h2 { margin: 0; font-size: 22px; background: linear-gradient(90deg, #5b6ee8, #9a4ee0); -webkit-background-clip: text; background-clip: text; color: transparent; }
.mp-push-root .page-title p { margin: 6px 0 0; font-size: 12.5px; color: #8a90a4; max-width: 900px; line-height: 1.7; }
.mp-push-root .refresh { margin-left: auto; }

.mp-push-root .panel { background: #fff; border-radius: 14px; box-shadow: 0 4px 18px rgba(30, 40, 90, 0.08); padding: 18px 20px 14px; margin-bottom: 16px; }
.mp-push-root .panel-head { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.mp-push-root .panel-head h3 { margin: 0; font-size: 15px; color: #333a52; }
.mp-push-root .panel-sub { font-size: 12px; color: #a3a8ba; }
.mp-push-root .panel-head .btn-primary:last-child { margin-left: auto; }

.mp-push-root .stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 16px; }
.mp-push-root .stat-card { border-radius: 14px; padding: 16px 18px; color: #fff; display: flex; flex-direction: column; gap: 6px; box-shadow: 0 6px 20px rgba(30, 40, 90, 0.15); }
.mp-push-root .stat-0 { background: linear-gradient(135deg, #5b6ee8, #7d57e0); }
.mp-push-root .stat-1 { background: linear-gradient(135deg, #3fb6a8, #2f9e8f); }
.mp-push-root .stat-2 { background: linear-gradient(135deg, #e8a33d, #d9822b); }
.mp-push-root .stat-3 { background: linear-gradient(135deg, #e8622c, #d1471d); }
.mp-push-root .stat-label { font-size: 12px; opacity: 0.9; }
.mp-push-root .stat-value { font-size: 26px; font-weight: 700; }
.mp-push-root .stat-sub { font-size: 11.5px; opacity: 0.85; }

.mp-push-root .table-card { background: #fff; border-radius: 14px; box-shadow: 0 4px 18px rgba(30, 40, 90, 0.08); overflow: hidden; }
.mp-push-root .tbl { width: 100%; border-collapse: collapse; font-size: 13px; }
.mp-push-root .tbl th { text-align: left; padding: 12px 14px; color: #fff; font-weight: 600; background: linear-gradient(90deg, #5f6fe8, #8a5ce8); font-size: 12.5px; white-space: nowrap; }
.mp-push-root .tbl td { padding: 10px 14px; border-bottom: 1px solid #f2f4fa; vertical-align: top; }
.mp-push-root .tbl tbody tr:nth-child(even) { background: #fafbff; }
.mp-push-root .tbl tbody tr:hover { background: #f0f4ff; }

.mp-push-root .muted { color: #aab0c0; display: block; font-size: 11.5px; }
.mp-push-root .mono { display: block; font-family: monospace; font-size: 11px; color: #a3a8ba; }
.mp-push-root .issue-title { font-weight: 600; color: #333a52; display: block; }
.mp-push-root .digest { color: #6b7290; display: block; }
.mp-push-root .warn { display: block; margin-top: 4px; color: #d9822b; font-size: 11.5px; max-width: 320px; }
.mp-push-root .err { display: block; margin-top: 4px; color: #e05555; font-size: 11.5px; max-width: 320px; line-height: 1.6; }
.mp-push-root .link { display: block; margin-top: 4px; font-size: 12px; color: #5b6ee8; text-decoration: underline; }
.mp-push-root .tag { display: inline-block; padding: 3px 10px; border-radius: 999px; font-size: 11.5px; font-weight: 600; white-space: nowrap; }
.mp-push-root .tag-on { background: #e8f7ee; color: #2f9e6b; }
.mp-push-root .tag-off { background: #ffefef; color: #e05555; }
.mp-push-root .tag-mid { background: #fff3e0; color: #d9822b; }
.mp-push-root .direction-chip { display: inline-block; padding: 3px 10px; border-radius: 8px; background: #eef1fb; color: #4054b2; font-size: 12px; font-weight: 600; }
.mp-push-root .mp-chip { display: inline-flex; align-items: center; gap: 6px; margin-right: 8px; margin-bottom: 4px; font-size: 12px; }
.mp-push-root .mp-chip a { color: #5b6ee8; }

.mp-push-root .actions { display: flex; flex-wrap: wrap; gap: 6px; }
.mp-push-root .btn-primary, .mp-push-root .btn-ghost { border: none; cursor: pointer; border-radius: 10px; font-size: 13px; padding: 8px 16px; }
.mp-push-root .btn-primary { background: linear-gradient(90deg, #5b6ee8, #7d57e0); color: #fff; }
.mp-push-root .btn-ghost { background: #f2f4fa; color: #5b6ee8; }
.mp-push-root .btn-primary.mini, .mp-push-root .btn-ghost.mini { padding: 4px 10px; font-size: 12px; border-radius: 8px; }
.mp-push-root .btn-ghost.danger { color: #e05555; background: #ffefef; }
.mp-push-root .btn-primary:disabled, .mp-push-root .btn-ghost:disabled { opacity: 0.5; cursor: not-allowed; }

.mp-push-root .notice-banner { background: #e8f7ee; color: #2f9e6b; padding: 10px 14px; border-radius: 10px; margin-bottom: 12px; font-size: 13px; }
.mp-push-root .warn-banner { background: #fff8e6; border: 1px solid #f5dfa8; color: #8a6100; padding: 10px 14px; border-radius: 10px; margin-top: 10px; font-size: 12.5px; line-height: 1.8; }
.mp-push-root .error-banner { background: #ffefef; color: #e05555; padding: 10px 14px; border-radius: 10px; margin-bottom: 12px; font-size: 13px; }
.mp-push-root .progress-line { color: #d9822b; font-size: 12.5px; margin: 6px 0 0; }
.mp-push-root .empty { color: #a3a8ba; text-align: center; padding: 32px 0; }

.mp-push-root .push-progress { margin-top: 12px; background: #f7f9ff; border: 1px solid #e8ecfa; border-radius: 10px; padding: 10px 12px; }
.mp-push-root .push-progress-head { display: flex; align-items: center; gap: 10px; font-size: 12.5px; color: #333a52; flex-wrap: wrap; }
.mp-push-root .push-stage { color: #5b6ee8; }
.mp-push-root .push-progress-head .dismiss { margin-left: auto; }
.mp-push-root .push-bar { margin-top: 8px; height: 6px; border-radius: 999px; background: #e6eafb; overflow: hidden; }
.mp-push-root .push-bar-inner { height: 100%; background: linear-gradient(90deg, #5b6ee8, #9a4ee0); transition: width 0.4s ease; }
.mp-push-root .push-item { display: flex; align-items: baseline; gap: 8px; font-size: 12px; margin-top: 6px; flex-wrap: wrap; }
.mp-push-root .push-item-title { font-weight: 600; color: #333a52; }
.mp-push-root .push-item-msg { color: #7a8095; flex: 1; min-width: 200px; line-height: 1.6; }
.mp-push-root .push-item-msg.bad { color: #e05555; }
.mp-push-root .pf-ok { color: #2f9e6b; font-weight: 700; }
.mp-push-root .pf-bad { color: #e05555; font-weight: 700; }
.mp-push-root .spinner { width: 12px; height: 12px; border-radius: 50%; border: 2px solid #cfd8ff; border-top-color: #5b6ee8; display: inline-block; animation: spin 0.9s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.mp-push-root .push-log { margin: 0 0 12px; padding: 10px 14px; background: #0f172a; color: #cbd5e1; border-radius: 10px; font-size: 11.5px; line-height: 1.8; white-space: pre-wrap; }

.mp-push-root .check-list { display: flex; flex-direction: column; gap: 6px; }
.mp-push-root .check-item { display: flex; align-items: baseline; gap: 10px; font-size: 12.5px; flex-wrap: wrap; }
.mp-push-root .check-flag { font-weight: 700; }
.mp-push-root .check-flag.ok { color: #2f9e6b; }
.mp-push-root .check-flag.bad { color: #e05555; }
.mp-push-root .check-name { color: #333a52; font-weight: 600; min-width: 210px; }
.mp-push-root .check-msg { color: #7a8095; flex: 1; min-width: 240px; line-height: 1.6; }
.mp-push-root .check-msg.bad { color: #e05555; }

.mp-push-root .preview-mask { position: fixed; inset: 0; background: rgba(20, 22, 34, 0.55); display: flex; align-items: center; justify-content: center; z-index: 99; }
.mp-push-root .preview-panel { background: #fff; border-radius: 14px; width: min(820px, 94vw); height: min(88vh, 900px); display: flex; flex-direction: column; overflow: hidden; }
.mp-push-root .preview-head { padding: 14px 18px; border-bottom: 1px solid #f0f1f5; display: flex; align-items: center; gap: 10px; }
.mp-push-root .preview-title { font-size: 14px; font-weight: 700; color: #333a52; flex: 1; }
.mp-push-root .preview-frame { flex: 1; border: none; width: 100%; background: #fff; }
.mp-push-root .preview-foot { padding: 10px 18px; border-top: 1px solid #f0f1f5; font-size: 12px; color: #909399; line-height: 1.7; }
</style>
