<template>
  <div class="page">
    <div class="page-head">
      <div class="page-title">
        <span class="title-emoji">🤝</span>
        <div>
          <h2>邀请好友 · 关系与奖励</h2>
          <p>谁邀请谁 · 每人获得多少积分 · 按邀请人汇总积分</p>
        </div>
      </div>
    </div>

    <div class="stat-grid">
      <div class="stat-card stat-a">
        <span class="stat-label">邀请成功次数</span>
        <span class="stat-value">{{ state.total.value }}</span>
        <span class="stat-sub">全部邀请达成</span>
      </div>
      <div class="stat-card stat-b">
        <span class="stat-label">累计发放积分</span>
        <span class="stat-value">{{ statTotalPoints }}</span>
        <span class="stat-sub">全部邀请奖励</span>
      </div>
      <div class="stat-card stat-c">
        <span class="stat-label">邀请人数量</span>
        <span class="stat-value">{{ summaryItems.length }}</span>
        <span class="stat-sub">至少邀请成功 1 人</span>
      </div>
      <div class="stat-card stat-d">
        <span class="stat-label">单人最高累计积分</span>
        <span class="stat-value">{{ statMaxPoints }}</span>
        <span class="stat-sub">按汇总积分排序</span>
      </div>
    </div>

    <div class="toolbar">
      <input v-model="state.keyword.value" placeholder="🔍 搜索邀请人 / 被邀请人（昵称 / openid / Halo 用户名）" @keyup.enter="load(0)" />
      <button class="btn-primary" @click="load(0)">查询</button>
    </div>

    <div v-if="state.error.value" class="error-banner">⚠️ {{ state.error.value }}</div>

    <!-- 汇总：一人邀请多人，累计邀请人数 + 汇总积分 -->
    <div class="table-card">
      <div class="table-title">📊 邀请人汇总</div>
      <table class="tbl">
        <thead>
          <tr><th>#</th><th>邀请人</th><th>累计成功邀请</th><th>汇总积分</th><th>最近邀请时间</th></tr>
        </thead>
        <tbody>
          <tr v-if="state.loading.value"><td colspan="5" class="empty">⏳ 加载中…</td></tr>
          <tr v-else-if="!summaryItems.length"><td colspan="5" class="empty">🎈 暂无邀请记录</td></tr>
          <tr v-for="(item, idx) in summaryItems" :key="item.inviterOpenid">
            <td class="rank-no">{{ idx + 1 }}</td>
            <td>
              <span class="user-cell">
                <img v-if="item.inviterAvatar" class="avatar" :src="item.inviterAvatar" :alt="item.inviterNickname" />
                <span v-else class="avatar avatar-fallback">{{ (item.inviterNickname || "U").slice(0, 1) }}</span>
                <span class="user-main">
                  <span class="user-name">{{ item.inviterNickname || "-" }}</span>
                  <span class="mono">{{ item.inviterOpenid }}</span>
                </span>
              </span>
            </td>
            <td><span class="stat-number">{{ item.invitedCount }}</span> 人</td>
            <td><span class="points-cell">+{{ item.totalPoints }}</span> 积分</td>
            <td class="time">{{ item.lastInviteTime || "-" }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 明细：谁邀请了谁，获得了多少积分 -->
    <div class="table-card">
      <div class="table-title">📋 邀请明细</div>
      <table class="tbl">
        <thead>
          <tr><th>邀请人</th><th>被邀请人</th><th>获得积分</th><th>发放后余额</th><th>邀请时间</th></tr>
        </thead>
        <tbody>
          <tr v-if="state.loading.value"><td colspan="5" class="empty">⏳ 加载中…</td></tr>
          <tr v-else-if="!state.items.value.length"><td colspan="5" class="empty">🎈 暂无邀请明细</td></tr>
          <tr v-for="(item, idx) in state.items.value" :key="idx">
            <td>
              <span class="user-cell">
                <img v-if="item.inviterAvatar" class="avatar" :src="item.inviterAvatar" :alt="item.inviterNickname" />
                <span v-else class="avatar avatar-fallback">{{ (item.inviterNickname || "U").slice(0, 1) }}</span>
                <span class="user-main">
                  <span class="user-name">{{ item.inviterNickname || "-" }}</span>
                  <span class="mono">{{ item.inviteeOpenid }}</span>
                </span>
              </span>
            </td>
            <td>
              <span class="user-cell">
                <img v-if="item.inviteeAvatar" class="avatar" :src="item.inviteeAvatar" :alt="item.inviteeNickname" />
                <span v-else class="avatar avatar-fallback">{{ (item.inviteeNickname || "U").slice(0, 1) }}</span>
                <span class="user-main">
                  <span class="user-name">{{ item.inviteeNickname || "-" }}</span>
                  <span class="mono">{{ item.inviteeOpenid }}</span>
                </span>
              </span>
            </td>
            <td><span class="points-cell">+{{ item.points }}</span> 积分</td>
            <td>{{ item.balance }}</td>
            <td class="time">{{ item.createTimeText || "-" }}</td>
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
  inviterOpenid: string;
  inviterNickname: string;
  inviterAvatar: string;
  inviteeOpenid: string;
  inviteeNickname: string;
  inviteeAvatar: string;
  points: number;
  balance: number;
  createTimeText: string;
}>();

const summaryItems = computed(() => (state.summary.value as any[]) || []);
const totalPages = computed(() => Math.ceil(state.total.value / state.size.value));
const statTotalPoints = computed(() => summaryItems.value.reduce((sum, i) => sum + (Number(i.totalPoints) || 0), 0));
const statMaxPoints = computed(() => summaryItems.value.reduce((max, i) => Math.max(max, Number(i.totalPoints) || 0), 0));

function fmt(value: string | null | undefined) {
  return formatTime(value);
}

async function load(page: number) {
  state.page.value = page;
  state.loading.value = true;
  state.error.value = "";
  try {
    const data = await apiGet("/inviteRecords", {
      keyword: state.keyword.value.trim(),
      page: state.page.value,
      size: state.size.value,
    });
    state.items.value = data.items || [];
    state.summary.value = data.summary || [];
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
.stat-b { background: linear-gradient(135deg, #f2994a, #e8622c); }
.stat-c { background: linear-gradient(135deg, #4aa5f0, #4f74dd); }
.stat-d { background: linear-gradient(135deg, #2bb673, #1e9e8e); }
.stat-label { font-size: 12px; opacity: 0.9; }
.stat-value { font-size: 28px; font-weight: 700; margin: 2px 0; }
.stat-sub { font-size: 11px; opacity: 0.75; }

.toolbar { display: flex; gap: 10px; background: #fff; padding: 12px; border-radius: 12px; box-shadow: 0 2px 10px rgba(30, 40, 90, 0.06); margin-bottom: 14px; }
.toolbar input { flex: 1; padding: 8px 12px; border: 1px solid #e3e7f0; border-radius: 8px; font-size: 13px; background: #f8f9fd; }
button { padding: 7px 16px; border: none; border-radius: 8px; font-size: 13px; cursor: pointer; }
button:disabled { opacity: 0.5; cursor: not-allowed; }
.btn-primary { background: linear-gradient(90deg, #5b6ee8, #7d57e0); color: #fff; }

.error-banner { background: #ffefef; color: #e05555; padding: 10px 14px; border-radius: 10px; margin-bottom: 12px; font-size: 13px; }

.table-card { background: #fff; border-radius: 14px; box-shadow: 0 4px 18px rgba(30, 40, 90, 0.08); overflow-x: auto; margin-bottom: 16px; }
.table-title { padding: 12px 14px 0; font-size: 14px; font-weight: 700; color: #3c4160; }
.tbl { width: 100%; border-collapse: collapse; font-size: 13px; }
.tbl th { text-align: left; padding: 12px 14px; color: #fff; font-weight: 600; background: linear-gradient(90deg, #5f6fe8, #8a5ce8); font-size: 12.5px; white-space: nowrap; }
.tbl td { padding: 10px 14px; border-bottom: 1px solid #f2f4fa; vertical-align: top; }
.tbl tbody tr:nth-child(even) { background: #fafbff; }
.tbl tbody tr:hover { background: #f0f4ff; }
.mono { font-family: monospace; font-size: 11px; color: #aab0c0; }
.time { color: #7a8095; font-size: 12px; white-space: nowrap; }

.user-cell { display: inline-flex; align-items: center; gap: 10px; }
.avatar { width: 36px; height: 36px; border-radius: 50%; object-fit: cover; flex-shrink: 0; border: 2px solid #eef1fb; }
.avatar-fallback { display: inline-flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #6a7bf0, #a06ceb); color: #fff; font-size: 14px; font-weight: 600; }
.user-main { display: flex; flex-direction: column; line-height: 1.35; }
.user-name { font-weight: 600; }

.rank-no { color: #aab0c0; font-weight: 700; }
.stat-number { font-size: 16px; font-weight: 700; color: #3a6ee0; }
.points-cell { color: #e8862b; font-weight: 700; }

.empty { text-align: center; color: #aab0c0; padding: 48px 0; font-size: 14px; }
.pager { display: flex; gap: 12px; align-items: center; justify-content: flex-end; padding: 12px 14px; color: #8b90a0; font-size: 13px; }
</style>
