<template>
  <div class="page">
    <div class="page-head">
      <div class="page-title">
        <span class="title-emoji">🧭</span>
        <div>
          <h2>用户行为</h2>
          <p>浏览 / 评论 / 点赞 / 投票 / 表情反馈 · 行为轨迹与奖励</p>
        </div>
      </div>
    </div>

    <div class="stat-grid">
      <div class="stat-card stat-a">
        <span class="stat-label">行为总数</span>
        <span class="stat-value">{{ state.total.value }}</span>
        <span class="stat-sub">全部行为记录</span>
      </div>
      <div class="stat-card stat-b">
        <span class="stat-label">本页发放积分</span>
        <span class="stat-value">{{ statPoints }}</span>
        <span class="stat-sub">当前页 · 累计 +积分</span>
      </div>
      <div class="stat-card stat-c">
        <span class="stat-label">本页浏览量</span>
        <span class="stat-value">{{ statVisits }}</span>
        <span class="stat-sub">当前页 · 行为内容总浏览</span>
      </div>
      <div class="stat-card stat-d">
        <span class="stat-label">行为类型</span>
        <span class="stat-value">{{ statTypes }}</span>
        <span class="stat-sub">当前页 · 不同行为种类</span>
      </div>
    </div>

    <div class="toolbar">
      <select v-model="state.status.value" @change="load(0)">
        <option value="">全部行为</option>
        <option value="browse">浏览</option>
        <option value="comment">评论</option>
        <option value="upvote">点赞</option>
        <option value="vote">投票</option>
        <option value="reaction">表情反馈</option>
      </select>
      <input v-model="state.keyword.value" placeholder="🔍 搜索昵称 / 文章 / openid" @keyup.enter="load(0)" />
      <button class="btn-primary" @click="load(0)">查询</button>
    </div>

    <div v-if="state.error.value" class="error-banner">⚠️ {{ state.error.value }}</div>

    <div class="table-card">
      <table class="tbl">
        <thead>
          <tr><th>用户</th><th>行为</th><th>内容</th><th>浏览</th><th>积分</th><th>时间</th></tr>
        </thead>
        <tbody>
          <tr v-if="state.loading.value"><td colspan="6" class="empty">⏳ 加载中…</td></tr>
          <tr v-else-if="!state.items.value.length"><td colspan="6" class="empty">🎈 暂无数据</td></tr>
          <tr v-for="(item, idx) in state.items.value" :key="idx">
            <td>
              <span class="user-cell">
                <img v-if="item.avatarUrl" class="avatar" :src="item.avatarUrl" :alt="item.nickname" />
                <span v-else class="avatar avatar-fallback">{{ (item.nickname || "U").slice(0, 1) }}</span>
                <span class="user-main">
                  <span class="user-name">{{ item.nickname || "-" }}</span>
                  <span class="mono">{{ item.wxOpenid }}</span>
                </span>
              </span>
            </td>
            <td>
              <span class="tag" :class="typeClass(item.type)">{{ typeLabel(item.type) }}</span>
            </td>
            <td class="content-cell">
              <span class="content-title">{{ item.title || item.articleName || "（无标题）" }}</span>
              <span v-if="item.excerpt" class="content-excerpt">{{ item.excerpt }}</span>
              <span v-if="item.publishTime" class="content-sub">{{ fmt(item.publishTime) }} 发布</span>
            </td>
            <td>
              <span v-if="item.visit != null" class="visit-badge">👁 {{ item.visit }}</span>
              <span v-else class="muted">-</span>
            </td>
            <td>
              <span v-if="item.pointsEarned != null" class="points plus">+{{ item.pointsEarned }}</span>
              <span v-else class="muted">-</span>
            </td>
            <td class="time">{{ item.recordTimeText || fmt(item.recordTime) }}</td>
          </tr>
        </tbody>
      </table>

      <div class="pager">
        <button :disabled="state.page.value === 0" @click="load(state.page.value - 1)">上一页</button>
        <span>第 {{ state.page.value + 1 }} / {{ totalPages }} 页 · 共 {{ state.total.value }} 条</span>
        <button :disabled="state.page.value + 1 >= totalPages" @click="load(state.page.value + 1)">下一页</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from "vue";
import { formatTime, useConsoleApi, useTableState } from "../api";

const { apiGet } = useConsoleApi();
const state = useTableState<{
  type: string;
  wxOpenid: string;
  nickname: string;
  avatarUrl: string;
  title: string;
  articleName: string;
  excerpt: string;
  cover: string;
  publishTime: string;
  visit: number;
  pointsEarned: number | null;
  recordTime: string;
  recordTimeText: string;
}>();

const totalPages = computed(() => Math.ceil(state.total.value / state.size.value));
const statPoints = computed(() =>
  state.items.value.reduce((sum, i) => sum + (i.pointsEarned || 0), 0)
);
const statVisits = computed(() =>
  state.items.value.reduce((sum, i) => sum + (i.visit || 0), 0)
);
const statTypes = computed(() => new Set(state.items.value.map((i) => i.type)).size);

const TYPE_LABELS: Record<string, string> = {
  browse: "浏览",
  comment: "评论",
  upvote: "点赞",
  vote: "投票",
  reaction: "表情反馈",
};

const TYPE_CLASSES: Record<string, string> = {
  browse: "tag-blue",
  comment: "tag-green",
  upvote: "tag-orange",
  vote: "tag-purple",
  reaction: "tag-pink",
};

function typeLabel(type: string) {
  return TYPE_LABELS[type] || type || "-";
}

function typeClass(type: string) {
  return TYPE_CLASSES[type] || "tag-blue";
}

function fmt(value: string | null | undefined) {
  return formatTime(value);
}

async function load(page: number) {
  state.page.value = page;
  state.loading.value = true;
  state.error.value = "";
  try {
    const data = await apiGet("/userActivities", {
      type: state.status.value,
      keyword: state.keyword.value.trim(),
      page: state.page.value,
      size: state.size.value,
    });
    state.items.value = data.items || [];
    state.total.value = data.total || 0;
  } catch (err: any) {
    state.error.value = err?.message || "加载失败";
  } finally {
    state.loading.value = false;
  }
}

onMounted(() => load(0));
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
.stat-b { background: linear-gradient(135deg, #2bb673, #1e9e8e); }
.stat-c { background: linear-gradient(135deg, #f2994a, #e8622c); }
.stat-d { background: linear-gradient(135deg, #4aa5f0, #4f74dd); }
.stat-label { font-size: 12px; opacity: 0.9; }
.stat-value { font-size: 28px; font-weight: 700; margin: 2px 0; }
.stat-sub { font-size: 11px; opacity: 0.75; }

.toolbar { display: flex; gap: 10px; background: #fff; padding: 12px; border-radius: 12px; box-shadow: 0 2px 10px rgba(30, 40, 90, 0.06); margin-bottom: 14px; }
.toolbar select { width: 130px; }
select, .toolbar input { padding: 8px 12px; border: 1px solid #e3e7f0; border-radius: 8px; font-size: 13px; background: #f8f9fd; }
.toolbar input { flex: 1; }
button { padding: 7px 16px; border: none; border-radius: 8px; font-size: 13px; cursor: pointer; }
button:disabled { opacity: 0.5; cursor: not-allowed; }
.btn-primary { background: linear-gradient(90deg, #5b6ee8, #7d57e0); color: #fff; }

.error-banner { background: #ffefef; color: #e05555; padding: 10px 14px; border-radius: 10px; margin-bottom: 12px; font-size: 13px; }

.table-card { background: #fff; border-radius: 14px; box-shadow: 0 4px 18px rgba(30, 40, 90, 0.08); overflow-x: auto; }
.tbl { width: 100%; border-collapse: collapse; font-size: 13px; }
.tbl th { text-align: left; padding: 12px 14px; color: #fff; font-weight: 600; background: linear-gradient(90deg, #5f6fe8, #8a5ce8); font-size: 12.5px; white-space: nowrap; }
.tbl td { padding: 10px 14px; border-bottom: 1px solid #f2f4fa; vertical-align: top; }
.tbl tbody tr:nth-child(even) { background: #fafbff; }
.tbl tbody tr:hover { background: #f0f4ff; }
.muted { color: #aab0c0; }
.mono { font-family: monospace; font-size: 11px; }
.time { color: #7a8095; white-space: nowrap; }

.user-cell { display: inline-flex; align-items: center; gap: 10px; }
.avatar { width: 36px; height: 36px; border-radius: 50%; object-fit: cover; flex-shrink: 0; border: 2px solid #eef1fb; }
.avatar-fallback { display: inline-flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #6a7bf0, #a06ceb); color: #fff; font-size: 14px; font-weight: 600; }
.user-main { display: flex; flex-direction: column; line-height: 1.35; }
.user-name { font-weight: 600; }

.tag { display: inline-block; padding: 3px 12px; border-radius: 999px; font-size: 12px; font-weight: 600; white-space: nowrap; }
.tag-blue { background: #e8f1ff; color: #3a6ee0; }
.tag-green { background: #e6f7e6; color: #2aa34a; }
.tag-orange { background: #fff3e0; color: #e8862b; }
.tag-purple { background: #f1e8ff; color: #8a5ce8; }
.tag-pink { background: #ffeaf3; color: #e05595; }

.content-cell { display: flex; flex-direction: column; gap: 2px; max-width: 340px; }
.content-title { font-weight: 600; color: #333a52; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.content-excerpt { font-size: 12px; color: #8b90a0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.content-sub { font-size: 11px; color: #aab0c0; }

.visit-badge { display: inline-block; padding: 2px 10px; border-radius: 999px; background: #eef1fb; color: #6a7bf0; font-size: 12px; }
.points { font-weight: 700; }
.points.plus { color: #2aa34a; }

.empty { text-align: center; color: #aab0c0; padding: 48px 0; font-size: 14px; }
.pager { display: flex; gap: 12px; align-items: center; justify-content: flex-end; padding: 12px 14px; color: #8b90a0; font-size: 13px; }
</style>
