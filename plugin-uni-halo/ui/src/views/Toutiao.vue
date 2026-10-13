<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { useConsoleApi, formatTime } from "../api";

const { apiGet, apiPost, apiDelete } = useConsoleApi();

interface ToutiaoDefaults {
  words: number;
  titleCount: number;
  styles: string[];
  defaultStyle: string;
}

interface ToutiaoStatus {
  enabled: boolean;
  defaults: ToutiaoDefaults;
  taskKey: string;
  running: boolean;
  stage: string;
  startedAt: string | null;
  finishedAt: string | null;
  error: string;
  articleName: string | null;
}

interface ToutiaoSummary {
  name: string;
  keyword: string;
  style: string;
  words: number | null;
  title: string;
  state: string;
  note: string;
  error: string;
  channel: string;
  bodyLength: number;
  imageCount: number;
  imageNote: string;
  createdAt: string;
  finishedAt: string | null;
}

interface ToutiaoDetail extends ToutiaoSummary {
  configuredTitleCount: number | null;
  titleCandidates: string[];
  body: string;
  tags: string[];
  images: string[];
  aiCost: any;
}

/** 正文按「【配图N：画面描述】」标记切块：文本块直接展示，图位块内嵌对应图片 */
interface BodyBlock {
  type: "text" | "image";
  text: string;
  description: string;
  /** 第几个图位（0 起，对应 images 数组下标） */
  slotIndex: number;
}

const status = ref<ToutiaoStatus | null>(null);
const history = ref<ToutiaoSummary[]>([]);
const current = ref<ToutiaoDetail | null>(null);
const loading = ref(false);
const generating = ref(false);
const error = ref("");
const notice = ref("");
const resultAnchor = ref<HTMLElement | null>(null);

/** 生成参数（默认值来自插件设置 toutiaoConfig，首次拿到状态时填一次） */
const form = reactive({ keyword: "", style: "", words: 0, titleCount: 0 });
let defaultsApplied = false;

let timer: ReturnType<typeof setTimeout> | null = null;

const canGenerate = computed(
  () => !!status.value?.enabled && !status.value?.running && form.keyword.trim().length > 0,
);

/** 正文切块：按标记把正文分成文本段与图位，图位按出现顺序对应 images 数组 */
const bodyBlocks = computed<BodyBlock[]>(() => {
  const body = current.value?.body || "";
  const blocks: BodyBlock[] = [];
  const slotPattern = /【配图\s*\d*\s*[:：]?\s*([^】]*)】/g;
  let last = 0;
  let slotIndex = 0;
  const pushText = (raw: string) => {
    const text = raw.trim();
    if (text) blocks.push({ type: "text", text, description: "", slotIndex: -1 });
  };
  let match: RegExpExecArray | null;
  while ((match = slotPattern.exec(body)) !== null) {
    pushText(body.slice(last, match.index));
    blocks.push({
      type: "image",
      text: match[0],
      description: (match[1] || "").trim(),
      slotIndex: slotIndex++,
    });
    last = match.index + match[0].length;
  }
  pushText(body.slice(last));
  return blocks;
});

const imageCount = computed(() => (current.value?.images || []).length);

function slotUrl(block: BodyBlock): string {
  if (block.type !== "image") return "";
  return (current.value?.images || [])[block.slotIndex] || "";
}

const costText = computed(() => {
  const cost = current.value?.aiCost;
  if (!cost) return "";
  const parts: string[] = [];
  const task = (cost.tasks || [])[0];
  if (task) {
    parts.push(
      `${task.model || "模型未知"} · 输入 ${task.inputTokens ?? 0} / 输出 ${task.outputTokens ?? 0} tokens`,
    );
  }
  const totals = (cost.totals || []) as { currency: string; amount: string }[];
  if (totals.length) {
    parts.push(
      `估算花费 ${totals.map((item) => `${item.currency === "USD" ? "$" : "¥"}${item.amount}`).join(" / ")}`,
    );
  } else if ((cost.unpricedCalls || 0) > 0) {
    parts.push("没能估算花费（价格表里没有该模型，可在「AI 日报 → 模型单价」里补上）");
  }
  if ((cost.unknownUsageCalls || 0) > 0) {
    parts.push(`${cost.unknownUsageCalls} 次调用对方未上报用量`);
  }
  return parts.join(" · ");
});

function stateText(state: string) {
  const map: Record<string, string> = {
    READY: "已生成",
    GENERATING: "生成中",
    INTERRUPTED: "已中断",
    FAILED: "失败",
  };
  return map[state] || state || "-";
}

function stateClass(state: string) {
  if (state === "READY") return "tag-on";
  if (state === "FAILED") return "tag-off";
  if (state === "INTERRUPTED") return "tag-mid";
  return "tag-mid";
}

function flash(message: string, ok = true) {
  notice.value = ok ? message : "";
  error.value = ok ? "" : message;
}

function applyDefaults() {
  const defaults = status.value?.defaults;
  if (!defaults || defaultsApplied) return;
  defaultsApplied = true;
  form.style = defaults.defaultStyle || defaults.styles?.[0] || "";
  form.words = defaults.words;
  form.titleCount = defaults.titleCount;
}

async function loadStatus() {
  try {
    const wasRunning = !!status.value?.running;
    const data = (await apiGet("/toutiao/status")) as ToutiaoStatus;
    status.value = data;
    applyDefaults();
    if (wasRunning && !data.running) {
      // 刚跑完：把结果直接捞到下方结果区，不用站长自己去历史里翻
      await loadHistory();
      if (data.articleName) {
        await openArticle(data.articleName);
      }
      flash(data.error ? `生成失败：${data.error}` : "生成完成，结果已显示在下方", !data.error);
    }
    scheduleRefresh(!!data.running);
  } catch (e: any) {
    error.value = e?.message || String(e);
  }
}

async function loadHistory() {
  try {
    const data: any = await apiGet("/toutiao/history", { limit: 50 });
    history.value = data?.items || [];
  } catch (e: any) {
    error.value = e?.message || String(e);
  }
}

async function refreshAll() {
  loading.value = true;
  error.value = "";
  try {
    await Promise.all([loadStatus(), loadHistory()]);
  } finally {
    loading.value = false;
  }
}

function scheduleRefresh(active: boolean) {
  if (timer) {
    clearTimeout(timer);
    timer = null;
  }
  if (active) {
    timer = setTimeout(async () => {
      await loadStatus();
      await loadHistory();
    }, 4000);
  }
}

async function generate() {
  if (!form.keyword.trim()) {
    flash("请先填写主题关键词", false);
    return;
  }
  generating.value = true;
  try {
    // 空输入框会得到空串：不传这两个字段，让后端用配置里的默认值
    const words = Number(form.words);
    const titleCount = Number(form.titleCount);
    const r: any = await apiPost("/toutiao/generate", {
      keyword: form.keyword.trim(),
      style: form.style,
      words: Number.isFinite(words) && words > 0 ? words : undefined,
      titleCount: Number.isFinite(titleCount) && titleCount > 0 ? titleCount : undefined,
    });
    flash(r?.message || "已开始生成");
    // 触发后先抢一次状态，再交给 4 秒轮询
    await loadStatus();
    scheduleRefresh(true);
  } catch (e: any) {
    flash(e?.message || String(e), false);
  } finally {
    generating.value = false;
  }
}

async function openArticle(name: string) {
  try {
    current.value = (await apiGet(`/toutiao/history/${encodeURIComponent(name)}`)) as ToutiaoDetail;
    await nextTickScroll();
  } catch (e: any) {
    flash(e?.message || String(e), false);
  }
}

async function nextTickScroll() {
  // 结果区在长页面下方：点「查看」后滚过去，省得以为没反应
  setTimeout(() => {
    resultAnchor.value?.scrollIntoView({ behavior: "smooth", block: "start" });
  }, 60);
}

async function removeArticle(row: ToutiaoSummary) {
  if (!confirm(`删除「${row.keyword || row.name}」这条生成记录？`)) return;
  try {
    await apiDelete(`/toutiao/history/${encodeURIComponent(row.name)}`);
    flash("已删除");
    if (current.value?.name === row.name) {
      current.value = null;
    }
    await loadHistory();
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

function copyTitle(title: string) {
  return copyText(title, "标题");
}

function copyBodyPlain() {
  // 纯文本保留【配图N】标记行：万一富文本的图片没跟过去，这些行就是插图位置提示
  return copyText(current.value?.body || "", "正文（纯文本，含配图位置标记）");
}

function copyTags() {
  const tags = current.value?.tags || [];
  if (!tags.length) {
    flash("这条记录没有标签", false);
    return;
  }
  return copyText(tags.join("，"), "标签");
}

function copyTitleAndBody() {
  const article = current.value;
  if (!article) return;
  const title = article.titleCandidates?.[0] || article.title || "";
  return copyText(`${title}\n\n${article.body || ""}`, "标题 + 正文");
}

/**
 * 复制配图（位图进剪贴板）：到头条编辑器里 Ctrl+V 就能贴上去。
 *
 * <p>剪贴板只接受 PNG，附件库里的 jpg/webp 先用画布转一次；
 * 图在跨域 CDN 上时画布会被污染，转码失败就提示改用「打开原图」另存。</p>
 */
async function copyImage(url: string, index: number) {
  try {
    const res = await fetch(url, { cache: "no-store" });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const blob = await res.blob();
    const png = blob.type === "image/png" ? blob : await toPngBlob(blob);
    await navigator.clipboard.write([new ClipboardItem({ "image/png": png })]);
    flash(`已复制配图 ${index + 1}：到头条编辑器里 Ctrl+V 粘贴即可`);
  } catch (e: any) {
    flash("复制图片失败：点「打开原图」后在浏览器里右键另存（或直接拖拽上传）", false);
  }
}

function toPngBlob(blob: Blob): Promise<Blob> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    const src = URL.createObjectURL(blob);
    img.onload = () => {
      try {
        const canvas = document.createElement("canvas");
        canvas.width = img.naturalWidth;
        canvas.height = img.naturalHeight;
        const ctx = canvas.getContext("2d");
        if (!ctx) throw new Error("画布不可用");
        ctx.drawImage(img, 0, 0);
        canvas.toBlob((out) => (out ? resolve(out) : reject(new Error("转码失败"))), "image/png");
      } catch (err) {
        reject(err);
      } finally {
        URL.revokeObjectURL(src);
      }
    };
    img.onerror = () => {
      URL.revokeObjectURL(src);
      reject(new Error("图片加载失败"));
    };
    img.src = src;
  });
}

/**
 * 一键复制全文（正文 + 配图）：文本段落包 <p>，配图按正文里标记的位置内嵌成 <figure>，
 * 粘进头条编辑器就是一篇排好版的图文。
 */
function buildRichHtml(): string {
  const article = current.value;
  if (!article) return "";
  const html: string[] = [];
  for (const block of bodyBlocks.value) {
    if (block.type === "text") {
      for (const para of block.text.split(/\n{2,}/)) {
        const text = para.trim();
        if (text) html.push(`<p>${escapeHtml(text).replace(/\n/g, "<br>")}</p>`);
      }
      continue;
    }
    const url = slotUrl(block);
    if (url) {
      html.push(
        `<figure style="margin:16px 0"><img src="${escapeHtml(url)}" style="max-width:100%" /></figure>`,
      );
    }
  }
  return html.join("");
}

async function copyFullArticle() {
  const article = current.value;
  if (!article?.body) {
    flash("这条记录没有正文", false);
    return;
  }
  const ok = await copyRich(buildRichHtml(), article.body);
  if (ok) {
    flash("已复制全文（正文 + 按位置内嵌的配图）：到头条号编辑器 Ctrl+V 粘贴即可；图片若没跟过去，用正文里每张图下的「复制图片」手动贴");
  } else {
    flash("当前浏览器不支持富文本复制，请用「复制正文（纯文本）」+ 逐张「复制图片」", false);
  }
}

async function copyRich(html: string, plain: string) {
  try {
    if (window.ClipboardItem) {
      await navigator.clipboard.write([
        new ClipboardItem({
          "text/html": new Blob([html], { type: "text/html" }),
          "text/plain": new Blob([plain], { type: "text/plain" }),
        }),
      ]);
      return true;
    }
  } catch (e) {
    // 落到下面的失败分支
  }
  return false;
}

function escapeHtml(text: string) {
  return text
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

onMounted(refreshAll);
onBeforeUnmount(() => {
  if (timer) clearTimeout(timer);
});
</script>

<template>
  <div class="page toutiao-root">
    <div class="page-head">
      <div class="page-title">
        <span class="title-emoji">📰</span>
        <div>
          <h2>今日头条号</h2>
          <p>
            输入一个关键词，用 <b>AI 日报配置的大模型通道</b>直接成稿——写成<b>头条自媒体图文</b>
            （聊一件事的连续叙述，不是日报的「概述+论点+看法」、也不是公众号的「导语+精选清单」），
            产出多个标题候选、正文、标签与<b>至少 3 张配图</b>：模型会在正文里标注「这里该配什么图」，
            插件按标注逐张生图并<b>内嵌到正文对应位置</b>，点「一键复制全文（正文 + 配图）」粘进头条编辑器即可。
            生成记录会保存在站点里，可随时回看与再次复制。
            想单独指定写稿模型（建议留给强模型），到 <b>UniHalo → AI 日报 → 大模型通道</b> 给某条通道的用途勾上
            <code>toutiao</code>（不勾则按日报的兜底规则走全部可用通道）。
          </p>
          <p>
            <b>去「AI 味」+ 篇幅</b>：提示词里已经禁掉了「在当今…时代」「首先/其次/最后」「综上所述」「赋能」这类套话与
            机械结构，并默认用第一人称的普通人视角来写。<b>字数是硬要求</b>——生成后会体检初稿：正文低于目标字数的下限
            （85%）就自动补写，套话命中 4 处以上就去 AI 味重写，两件事合并成一次调用，且只在修复版确实更好时才替换
            （结果会写在下方提示里）。觉得还是不够像自己号的口吻，就到
            <b>插件设置 → 今日头条号 → 口吻 / 视角</b> 改成你自己账号的说法。
          </p>
        </div>
      </div>
      <button class="btn-primary refresh" :disabled="loading" @click="refreshAll">刷新</button>
    </div>

    <div v-if="error" class="error-banner">⚠️ {{ error }}</div>
    <div v-if="notice" class="notice-banner">✅ {{ notice }}</div>

    <div class="panel">
      <div class="panel-head">
        <h3>生成设置</h3>
        <span class="panel-sub" v-if="status">
          总开关：{{ status.enabled ? "已开启" : "未开启（到插件设置「今日头条号」打开）" }} ·
          模板字数：{{ status.defaults?.words ?? "-" }} 字 ·
          标题候选：{{ status.defaults?.titleCount ?? "-" }} 个
        </span>
        <button
          class="btn-primary"
          :disabled="generating || !canGenerate"
          @click="generate"
        >
          ⚡ 开始生成
        </button>
      </div>

      <div class="form-grid">
        <label class="field field-wide">
          <span class="field-label">主题关键词（必填）</span>
          <input
            v-model="form.keyword"
            class="input"
            type="text"
            maxlength="100"
            placeholder="例如：AI 编程助手怎么选、35 岁转行还来得及吗"
            :disabled="status?.running"
            @keyup.enter="generate"
          />
        </label>
        <label class="field">
          <span class="field-label">写作风格</span>
          <select v-model="form.style" class="input" :disabled="status?.running">
            <option v-for="style in status?.defaults?.styles || []" :key="style" :value="style">
              {{ style }}
            </option>
          </select>
        </label>
        <label class="field">
          <span class="field-label">正文字数</span>
          <input v-model.number="form.words" class="input" type="number" min="300" max="3000" step="100" />
        </label>
        <label class="field">
          <span class="field-label">标题候选个数</span>
          <input v-model.number="form.titleCount" class="input" type="number" min="1" max="5" />
        </label>
      </div>

      <div v-if="status?.running" class="progress-line">
        <span class="spinner"></span> 正在生成：{{ status.stage || "进行中…" }}
        <span class="muted-inline">（写稿 + 配图，约 1~3 分钟；本页会自动刷新）</span>
      </div>
      <p v-if="status?.error" class="err">上次生成失败：{{ status.error }}</p>
      <p v-if="!status?.enabled" class="warn-text">
        功能未开启：到「插件设置 → 今日头条号」打开总开关后再生成（历史记录仍可查看与复制）。
      </p>
    </div>

    <div v-if="current" ref="resultAnchor" class="panel">
      <div class="panel-head">
        <h3>生成结果</h3>
        <span class="panel-sub">
          {{ current.keyword }} ·
          {{ current.style || "默认风格" }} ·
          {{ formatTime(current.createdAt) }} ·
          正文 {{ current.bodyLength }} 字 / 目标 {{ current.words ?? "-" }} 字
          <template v-if="current.channel"> · 通道：{{ current.channel }}</template>
        </span>
        <button class="btn-ghost" @click="copyTitleAndBody">📋 复制标题 + 正文</button>
        <button class="btn-ghost" @click="current = null">收起</button>
      </div>

      <p v-if="current.note" class="warn-banner">⚠️ {{ current.note }}</p>
      <p v-if="costText" class="cost-line">💰 {{ costText }}</p>

      <div class="panel" style="box-shadow: none; border: 1px solid #eef1fb">
        <div class="sub-head">
          <h4>标题候选（{{ (current.titleCandidates || []).length }} 个）</h4>
          <span class="panel-sub">头条号标题上限 30 字，插件已按上限收敛</span>
        </div>
        <div v-if="(current.titleCandidates || []).length" class="title-list">
          <div v-for="(title, idx) in current.titleCandidates" :key="idx" class="title-row">
            <span class="title-index">{{ idx + 1 }}</span>
            <span class="title-text">{{ title }}</span>
            <span class="title-len">{{ title.length }} 字</span>
            <button class="btn-ghost mini" @click="copyTitle(title)">📋 复制</button>
          </div>
        </div>
        <p v-else class="muted">这条记录没有标题候选（生成时模型没返回，可重生成）</p>
      </div>

      <div class="panel" style="box-shadow: none; border: 1px solid #eef1fb">
        <div class="sub-head">
          <h4>正文（配图已按标记位置内嵌）</h4>
          <span class="panel-sub">配图 {{ imageCount }} 张 · 共 {{ (current.images || []).length }} 张成功</span>
          <button class="btn-ghost mini" @click="copyBodyPlain">📋 复制正文（纯文本）</button>
          <button class="btn-primary mini" @click="copyFullArticle">📋 一键复制全文（正文 + 配图）</button>
        </div>
        <p v-if="current.imageNote" class="muted-inline" style="display: block; margin: 0 0 10px">
          {{ current.imageNote }}
        </p>
        <div class="body-flow">
          <template v-for="(block, idx) in bodyBlocks" :key="idx">
            <p v-if="block.type === 'text'" class="body-para">{{ block.text }}</p>
            <figure v-else class="inline-image">
              <img v-if="slotUrl(block)" class="inline-img" :src="slotUrl(block)"
                :alt="`配图 ${block.slotIndex + 1}`" loading="lazy" />
              <div v-else class="image-placeholder">
                🖼 这张图没生成成功{{ block.description ? `（${block.description}）` : "" }}；正文照常可用
              </div>
              <figcaption class="image-actions">
                <span class="image-index">配图 {{ block.slotIndex + 1 }}<template v-if="block.description"> · {{ block.description }}</template></span>
                <button v-if="slotUrl(block)" class="btn-ghost mini" @click="copyImage(slotUrl(block), block.slotIndex)">📋 复制图片</button>
                <a v-if="slotUrl(block)" class="btn-ghost mini" :href="slotUrl(block)" target="_blank" rel="noopener">↗ 原图</a>
              </figcaption>
            </figure>
          </template>
          <p v-if="!bodyBlocks.length" class="muted">（没有正文）</p>
        </div>
      </div>

      <div class="panel" style="box-shadow: none; border: 1px solid #eef1fb">
        <div class="sub-head">
          <h4>标签</h4>
          <button class="btn-ghost mini" @click="copyTags">📋 复制标签</button>
        </div>
        <div class="tag-list">
          <span v-for="tag in current.tags || []" :key="tag" class="tag-chip">{{ tag }}</span>
          <span v-if="!(current.tags || []).length" class="muted">这条记录没有标签</span>
        </div>
      </div>

      <p class="panel-sub" style="display: block; margin-top: 8px">
        发布路径：头条号后台 → 发文章 → 从上面的标题候选里选一个粘进标题框 →
        点「📋 一键复制全文（正文 + 配图）」粘进正文框（图片已按 AI 标注的位置内嵌好）；
        若编辑器没把图片带过去，就点「复制正文（纯文本）」，再按正文里每张图的「📋 复制图片」逐张贴到对应位置。
        生成内容由模型直接产出，发布前请自己过一遍事实与口径。
      </p>
    </div>

    <div class="panel">
      <div class="panel-head">
        <h3>生成历史</h3>
        <span class="panel-sub">最近 50 条；正文按纯文本保存，回看与复制不用重跑 AI</span>
        <button class="btn-ghost" @click="loadHistory">刷新</button>
      </div>
      <div class="table-card">
        <table class="tbl">
          <thead>
            <tr>
              <th style="min-width: 130px">时间</th>
              <th>关键词 / 标题</th>
              <th>风格 / 字数</th>
              <th>状态</th>
              <th style="min-width: 150px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!history.length">
              <td colspan="5" class="empty">🎈 还没有生成记录：填个关键词，点「开始生成」</td>
            </tr>
            <tr v-for="row in history" :key="row.name">
              <td>
                <span>{{ formatTime(row.createdAt) }}</span>
                <span class="mono">{{ row.name }}</span>
              </td>
              <td>
                <span class="row-keyword">{{ row.keyword || "-" }}</span>
                <span class="muted">{{ row.title || "（无标题）" }}</span>
                <span v-if="row.note" class="warn">⚠️ {{ row.note }}</span>
                <span v-if="row.error" class="err">{{ row.error }}</span>
              </td>
              <td>
                <span>{{ row.style || "默认" }}</span>
                <span class="mono">{{ row.words ?? "-" }} 字 · 正文 {{ row.bodyLength }} 字 · 配图 {{ row.imageCount }} 张</span>
                <span v-if="row.channel" class="mono">{{ row.channel }}</span>
              </td>
              <td>
                <span class="tag" :class="stateClass(row.state)">{{ stateText(row.state) }}</span>
              </td>
              <td class="actions">
                <button
                  class="btn-ghost mini"
                  :disabled="row.state === 'GENERATING'"
                  @click="openArticle(row.name)"
                >
                  👁 查看
                </button>
                <button class="btn-ghost mini danger" @click="removeArticle(row)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
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
.toutiao-root.page { padding: 24px 28px; width: 100%; box-sizing: border-box; }
.toutiao-root .page-head { margin-bottom: 18px; }
.toutiao-root .page-title { display: flex; align-items: center; gap: 12px; }
.toutiao-root .title-emoji { font-size: 34px; }
.toutiao-root .page-title h2 { margin: 0; font-size: 22px; background: linear-gradient(90deg, #5b6ee8, #9a4ee0); -webkit-background-clip: text; background-clip: text; color: transparent; }
.toutiao-root .page-title p { margin: 6px 0 0; font-size: 12.5px; color: #8a90a4; max-width: 960px; line-height: 1.7; }
.toutiao-root .page-title code { background: #eef1fb; color: #4054b2; padding: 1px 6px; border-radius: 6px; font-size: 12px; }
.toutiao-root .refresh { margin-left: auto; }

.toutiao-root .panel { background: #fff; border-radius: 14px; box-shadow: 0 4px 18px rgba(30, 40, 90, 0.08); padding: 18px 20px 14px; margin-bottom: 16px; }
.toutiao-root .panel-head { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.toutiao-root .panel-head h3 { margin: 0; font-size: 15px; color: #333a52; }
.toutiao-root .panel-sub { font-size: 12px; color: #a3a8ba; line-height: 1.7; }
.toutiao-root .panel-head .btn-primary:last-child { margin-left: auto; }
.toutiao-root .sub-head { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; flex-wrap: wrap; }
.toutiao-root .sub-head h4 { margin: 0; font-size: 13.5px; color: #333a52; }
.toutiao-root .sub-head .btn-ghost, .toutiao-root .sub-head .btn-primary { margin-left: 0; }
.toutiao-root .sub-head button:first-of-type { margin-left: auto; }

.toutiao-root .form-grid { display: grid; grid-template-columns: repeat(3, minmax(160px, 1fr)); gap: 12px 16px; }
.toutiao-root .field { display: flex; flex-direction: column; gap: 6px; }
.toutiao-root .field-wide { grid-column: span 3; }
.toutiao-root .field-label { font-size: 12.5px; color: #6b7290; }
.toutiao-root .input { border: 1px solid #dfe3f0; border-radius: 10px; padding: 8px 12px; font-size: 13px; color: #333a52; background: #fbfcff; outline: none; }
.toutiao-root .input:focus { border-color: #5b6ee8; background: #fff; }

.toutiao-root .title-list { display: flex; flex-direction: column; gap: 8px; }
.toutiao-root .title-row { display: flex; align-items: center; gap: 10px; background: #f7f9ff; border: 1px solid #e8ecfa; border-radius: 10px; padding: 8px 12px; }
.toutiao-root .title-index { width: 22px; height: 22px; border-radius: 50%; background: #eef1fb; color: #4054b2; font-size: 12px; font-weight: 700; display: inline-flex; align-items: center; justify-content: center; flex: none; }
.toutiao-root .title-text { flex: 1; font-size: 13.5px; color: #333a52; font-weight: 600; line-height: 1.6; }
.toutiao-root .title-len { font-size: 11.5px; color: #a3a8ba; flex: none; }

.toutiao-root .body-flow { display: flex; flex-direction: column; gap: 12px; }
.toutiao-root .body-para { margin: 0; font-size: 13.5px; line-height: 1.9; color: #333a52; white-space: pre-wrap; word-break: break-word; }
.toutiao-root .inline-image { margin: 0; background: #f7f9ff; border: 1px solid #e8ecfa; border-radius: 12px; overflow: hidden; }
.toutiao-root .inline-img { width: 100%; max-height: 380px; object-fit: contain; background: #eef1fb; display: block; }
.toutiao-root .image-placeholder { padding: 26px 14px; text-align: center; color: #a3a8ba; font-size: 12px; background: #f2f4fa; }

.toutiao-root .tag-list { display: flex; flex-wrap: wrap; gap: 8px; }
.toutiao-root .tag-chip { display: inline-block; padding: 3px 12px; border-radius: 999px; background: #eef1fb; color: #4054b2; font-size: 12px; font-weight: 600; }

.toutiao-root .image-actions { display: flex; align-items: center; gap: 6px; padding: 8px 10px; flex-wrap: wrap; }
.toutiao-root .image-index { font-size: 11.5px; color: #a3a8ba; margin-right: auto; }
.toutiao-root a.btn-ghost { text-decoration: none; display: inline-block; }

.toutiao-root .table-card { background: #fff; border-radius: 14px; box-shadow: 0 4px 18px rgba(30, 40, 90, 0.08); overflow: hidden; }
.toutiao-root .tbl { width: 100%; border-collapse: collapse; font-size: 13px; }
.toutiao-root .tbl th { text-align: left; padding: 12px 14px; color: #fff; font-weight: 600; background: linear-gradient(90deg, #5f6fe8, #8a5ce8); font-size: 12.5px; white-space: nowrap; }
.toutiao-root .tbl td { padding: 10px 14px; border-bottom: 1px solid #f2f4fa; vertical-align: top; }
.toutiao-root .tbl tbody tr:nth-child(even) { background: #fafbff; }
.toutiao-root .tbl tbody tr:hover { background: #f0f4ff; }

.toutiao-root .row-keyword { display: block; font-weight: 600; color: #333a52; }
.toutiao-root .muted { color: #aab0c0; display: block; font-size: 11.5px; }
.toutiao-root .muted-inline { color: #aab0c0; font-size: 11.5px; }
.toutiao-root .mono { display: block; font-family: monospace; font-size: 11px; color: #a3a8ba; }
.toutiao-root .warn { display: block; margin-top: 4px; color: #d9822b; font-size: 11.5px; max-width: 340px; }
.toutiao-root .warn-text { color: #d9822b; font-size: 12.5px; margin: 8px 0 0; }
.toutiao-root .err { display: block; margin-top: 4px; color: #e05555; font-size: 11.5px; max-width: 340px; line-height: 1.6; }
.toutiao-root .cost-line { margin: 0 0 12px; font-size: 12px; color: #7a8095; }

.toutiao-root .tag { display: inline-block; padding: 3px 10px; border-radius: 999px; font-size: 11.5px; font-weight: 600; white-space: nowrap; }
.toutiao-root .tag-on { background: #e8f7ee; color: #2f9e6b; }
.toutiao-root .tag-off { background: #ffefef; color: #e05555; }
.toutiao-root .tag-mid { background: #fff3e0; color: #d9822b; }

.toutiao-root .actions { display: flex; flex-wrap: wrap; gap: 6px; }
.toutiao-root .btn-primary, .toutiao-root .btn-ghost { border: none; cursor: pointer; border-radius: 10px; font-size: 13px; padding: 8px 16px; }
.toutiao-root .btn-primary { background: linear-gradient(90deg, #5b6ee8, #7d57e0); color: #fff; }
.toutiao-root .btn-ghost { background: #f2f4fa; color: #5b6ee8; }
.toutiao-root .btn-primary.mini, .toutiao-root .btn-ghost.mini { padding: 4px 10px; font-size: 12px; border-radius: 8px; }
.toutiao-root .btn-ghost.danger { color: #e05555; background: #ffefef; }
.toutiao-root .btn-primary:disabled, .toutiao-root .btn-ghost:disabled { opacity: 0.5; cursor: not-allowed; }

.toutiao-root .notice-banner { background: #e8f7ee; color: #2f9e6b; padding: 10px 14px; border-radius: 10px; margin-bottom: 12px; font-size: 13px; }
.toutiao-root .warn-banner { background: #fff8e6; border: 1px solid #f5dfa8; color: #8a6100; padding: 10px 14px; border-radius: 10px; margin: 0 0 12px; font-size: 12.5px; line-height: 1.8; }
.toutiao-root .error-banner { background: #ffefef; color: #e05555; padding: 10px 14px; border-radius: 10px; margin-bottom: 12px; font-size: 13px; }
.toutiao-root .progress-line { color: #d9822b; font-size: 12.5px; margin: 10px 0 0; display: flex; align-items: center; gap: 8px; }
.toutiao-root .empty { color: #a3a8ba; text-align: center; padding: 32px 0; }
.toutiao-root .spinner { width: 12px; height: 12px; border-radius: 50%; border: 2px solid #cfd8ff; border-top-color: #5b6ee8; display: inline-block; animation: toutiao-spin 0.9s linear infinite; }
@keyframes toutiao-spin { to { transform: rotate(360deg); } }
</style>
