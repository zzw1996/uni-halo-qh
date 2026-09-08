<template>
  <div class="page">
    <div class="page-head">
      <div class="page-title">
        <span class="title-emoji">💎</span>
        <div>
          <h2>积分记录</h2>
          <p>积分收支明细 · 行为 / 签到 / 任务 / 兑换 / 抽奖 / 邀请 / 充值</p>
        </div>
      </div>
    </div>

    <div class="stat-grid">
      <div class="stat-card stat-a">
        <span class="stat-label">记录总数</span>
        <span class="stat-value">{{ state.total.value }}</span>
        <span class="stat-sub">全部积分流水</span>
      </div>
      <div class="stat-card stat-b">
        <span class="stat-label">本页净额</span>
        <span class="stat-value">{{ statNet }}</span>
        <span class="stat-sub">当前页 · 收 - 支 = 净</span>
      </div>
      <div class="stat-card stat-c">
        <span class="stat-label">本页收入</span>
        <span class="stat-value">{{ statIn }}</span>
        <span class="stat-sub">当前页 · 正向积分</span>
      </div>
      <div class="stat-card stat-d">
        <span class="stat-label">本页支出</span>
        <span class="stat-value">{{ statOut }}</span>
        <span class="stat-sub">当前页 · 消耗积分</span>
      </div>
    </div>

    <div class="toolbar">
      <select v-model="state.status.value" @change="load(0)">
        <option value="">全部类型</option>
        <option value="browse">浏览</option>
        <option value="comment">评论</option>
        <option value="upvote">点赞</option>
        <option value="vote">投票</option>
        <option value="sign_in">签到</option>
        <option value="task">每日任务</option>
        <option value="exchange_dashboard">兑换·看板</option>
        <option value="lucky_draw">积分抽奖</option>
        <option value="invite">邀请奖励</option>
        <option value="recharge">支付充值</option>
      </select>
      <input v-model="state.keyword.value" placeholder="🔍 搜索昵称 / 说明 / openid" @keyup.enter="load(0)" />
      <button class="btn-primary" @click="load(0)">查询</button>
    </div>

    <div v-if="state.error.value" class="error-banner">⚠️ {{ state.error.value }}</div>

    <div class="table-card">
      <table class="tbl">
        <thead>
          <tr><th>用户</th><th>行为</th><th>说明</th><th>内容标题</th><th>积分</th><th>余额</th><th>时间</th></tr>
        </thead>
        <tbody>
          <tr v-if="state.loading.value"><td colspan="7" class="empty">⏳ 加载中…</td></tr>
          <tr v-else-if="!state.items.value.length"><td colspan="7" class="empty">🎈 暂无数据</td></tr>
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
              <span class="action-tag">{{ item.actionLabel || item.action || "-" }}</span>
            </td>
            <td class="desc-cell">
              <span class="desc-main">{{ item.description || "-" }}</span>
              <span v-if="item.refName" class="desc-sub">↳ {{ item.refName }}</span>
            </td>
            <td class="content-title">{{ item.contentTitle || "-" }}</td>
            <td>
              <span class="points" :class="Number(item.points) < 0 ? 'minus' : 'plus'">{{ Number(item.points) < 0 ? item.points : "+" + item.points }}</span>
            </td>
            <td class="mono balance">{{ item.balance }}</td>
            <td class="time">{{ item.createTimeText || fmt(item.createTime) }}</td>
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
  action: string;
  actionLabel: string;
  wxOpenid: string;
  nickname: string;
  avatarUrl: string;
  points: number;
  balance: number;
  description: string;
  refName: string;
  contentTitle: string;
  createTime: string;
  createTimeText: string;
}>();

const totalPages = computed(() => Math.ceil(state.total.value / state.size.value));
const statNet = computed(() => state.items.value.reduce((sum, i) => sum + Number(i.points || 0), 0));
const statIn = computed(() =>
  state.items.value.reduce((sum, i) => sum + Math.max(Number(i.points || 0), 0), 0)
);
const statOut = computed(() =>
  state.items.value.reduce((sum, i) => sum + Math.min(Number(i.points || 0), 0), 0)
);

function fmt(value: string | null | undefined) {
  return formatTime(value);
}

async function load(page: number) {
  state.page.value = page;
  state.loading.value = true;
  state.error.value = "";
  try {
    const data = await apiGet("/pointsRecords", {
      action: state.status.value,
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
.toolbar select { width: 150px; }
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

.action-tag { display: inline-block; padding: 3px 12px; border-radius: 999px; background: #eef1fb; color: #5b6ee8; font-size: 12px; font-weight: 600; white-space: nowrap; }
.desc-cell { display: flex; flex-direction: column; gap: 2px; max-width: 300px; }
.desc-main { color: #333a52; }
.desc-sub { font-size: 11px; color: #8b90a0; }
.content-title { max-width: 220px; color: #555c72; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.points { font-weight: 700; font-size: 14px; }
.points.plus { color: #2aa34a; }
.points.minus { color: #e05555; }
.balance { color: #777d90; }

.empty { text-align: center; color: #aab0c0; padding: 48px 0; font-size: 14px; }
.pager { display: flex; gap: 12px; align-items: center; justify-content: flex-end; padding: 12px 14px; color: #8b90a0; font-size: 13px; }
</style>
