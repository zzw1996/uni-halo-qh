<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useConsoleApi } from "../api";

const { apiGet } = useConsoleApi();

interface UsageRow {
  openid: string;
  nickname: string;
  avatarUrl: string;
  expireAt: string;
  daysRemaining: number;
  active: boolean;
  remainingGenerations: number;
  todayGenerations: number;
  totalGenerations: number;
  lastGenerateAt: string;
  lastTrigger: string;
}

interface LogRow {
  openid: string;
  time: string;
  date: string;
  trigger: string;
  status: string;
  itemCount: number;
  titles: string[];
  creditsAfter: number | null;
  creditsCharged: boolean;
  liveHours: number;
  error: string;
}

interface Paged<T> {
  items: T[];
  total: number;
}

const users = ref<Paged<UsageRow>>({ items: [], total: 0 });
const logs = ref<Paged<LogRow>>({ items: [], total: 0 });
const loading = ref(false);
const error = ref("");
const keyword = ref("");
const userPage = ref(0);
const logPage = ref(0);
const pageSize = 20;
/** 明细按用户筛（点表格里的「明细」按钮） */
const logFilterOpenid = ref("");

const highlight = ref("");

const triggerText = (trigger: string) => {
  switch (trigger) {
    case "manual":
      return "用户点击生成";
    case "scheduled":
      return "整点定时推送";
    case "push":
      return "用户点立即推送";
    default:
      return trigger || "-";
  }
};

const stat = computed(() => {
  const rows = users.value.items;
  return {
    users: users.value.total,
    active: rows.filter((row) => row.active).length,
    creditsLeft: rows.reduce((sum, row) => sum + (row.remainingGenerations || 0), 0),
    todayGenerations: rows.reduce((sum, row) => sum + (row.todayGenerations || 0), 0),
  };
});

async function loadUsers(page = userPage.value) {
  loading.value = true;
  error.value = "";
  try {
    const res: any = await apiGet("/aiContentUsage", {
      page,
      size: pageSize,
      keyword: keyword.value || undefined,
    });
    users.value = res?.users || { items: [], total: 0 };
    logs.value = res?.logs || { items: [], total: 0 };
    userPage.value = page;
    logPage.value = 0;
  } catch (e: any) {
    error.value = e?.message || "读取失败";
  } finally {
    loading.value = false;
  }
}

async function loadLogs(page = logPage.value, openid = logFilterOpenid.value) {
  loading.value = true;
  error.value = "";
  try {
    const res: any = await apiGet("/aiContentLogs", {
      page,
      size: pageSize,
      openid: openid || undefined,
    });
    logs.value = res || { items: [], total: 0 };
    logPage.value = page;
    logFilterOpenid.value = openid;
  } catch (e: any) {
    error.value = e?.message || "读取失败";
  } finally {
    loading.value = false;
  }
}

function showLogsOf(row: UsageRow) {
  loadLogs(0, row.openid);
}

onMounted(() => loadUsers(0));
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div class="page-title">
        <span class="title-emoji">🧠</span>
        <div>
          <h2>AI 定制内容</h2>
          <p>
            付费用户的剩余生成次数、到期时间与每次生成的明细（谁触发的、生成了什么、扣了几次）。
            次数规则在「插件设置 → AI 定制内容」里配置：每个权益日赠送几次 / 单次赠送上限 / 每天最多生成几次。
          </p>
        </div>
        <button class="btn-primary refresh" :disabled="loading" @click="loadUsers(userPage)">
          刷新
        </button>
      </div>
    </div>

    <div v-if="error" class="error-banner">⚠️ {{ error }}</div>

    <div class="stat-grid">
      <div class="stat-card stat-a">
        <span class="stat-label">用户数</span>
        <span class="stat-value">{{ stat.users }}</span>
        <span class="stat-sub">小程序用户总数</span>
      </div>
      <div class="stat-card stat-b">
        <span class="stat-label">本页有效权益</span>
        <span class="stat-value">{{ stat.active }}</span>
        <span class="stat-sub">已购买 / 已兑换且未过期</span>
      </div>
      <div class="stat-card stat-c">
        <span class="stat-label">本页剩余次数</span>
        <span class="stat-value">{{ stat.creditsLeft }}</span>
        <span class="stat-sub">所有人加起来还剩几次生成</span>
      </div>
      <div class="stat-card stat-d">
        <span class="stat-label">本页今日生成</span>
        <span class="stat-value">{{ stat.todayGenerations }}</span>
        <span class="stat-sub">今天已消耗的生成次数</span>
      </div>
    </div>

    <div class="toolbar">
      <input
        v-model="keyword"
        placeholder="🔍 搜索昵称 / openid"
        @keyup.enter="loadUsers(0)"
      />
      <button class="btn-primary" @click="loadUsers(0)">查询</button>
      <span v-if="highlight" class="filter-tip">
        正在看 {{ highlight }} 的明细
        <a href="javascript:void(0)" @click="loadLogs(0, ''); highlight = ''">清除</a>
      </span>
    </div>

    <div class="table-card">
      <table class="tbl">
        <thead>
          <tr>
            <th>用户</th>
            <th>权益到期</th>
            <th>剩余次数</th>
            <th>今日生成</th>
            <th>累计生成</th>
            <th>最近生成</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading"><td colspan="7" class="empty">⏳ 加载中…</td></tr>
          <tr v-else-if="!users.items.length"><td colspan="7" class="empty">🎈 暂无数据</td></tr>
          <tr v-for="row in users.items" :key="row.openid">
            <td>
              <span class="user-cell">
                <img v-if="row.avatarUrl" class="avatar" :src="row.avatarUrl" :alt="row.nickname" />
                <span v-else class="avatar avatar-fallback">{{ (row.nickname || "U").slice(0, 1) }}</span>
                <span class="user-main">
                  <span class="user-name">{{ row.nickname }}</span>
                  <span class="mono">{{ row.openid }}</span>
                </span>
              </span>
            </td>
            <td>
              <span v-if="row.active" class="tag tag-on">{{ row.expireAt }}</span>
              <span v-else class="muted">{{ row.expireAt || "未开通" }}</span>
              <span v-if="row.active && row.daysRemaining > 0" class="muted">
                剩 {{ row.daysRemaining }} 天
              </span>
            </td>
            <td>
              <span class="credits">{{ row.remainingGenerations }}</span>
              <span class="muted">次</span>
            </td>
            <td>{{ row.todayGenerations }}</td>
            <td>{{ row.totalGenerations }}</td>
            <td>
              <span v-if="row.lastGenerateAt">{{ row.lastGenerateAt }}</span>
              <span v-else class="muted">—</span>
              <span v-if="row.lastTrigger" class="muted">{{ triggerText(row.lastTrigger) }}</span>
            </td>
            <td>
              <button class="btn-link" @click="showLogsOf(row)">明细</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="pager">
      <button class="btn-ghost" :disabled="userPage === 0" @click="loadUsers(userPage - 1)">
        上一页
      </button>
      <span class="muted">第 {{ userPage + 1 }} 页 · 共 {{ users.total }} 人</span>
      <button
        class="btn-ghost"
        :disabled="(userPage + 1) * pageSize >= users.total"
        @click="loadUsers(userPage + 1)"
      >
        下一页
      </button>
    </div>

    <div class="panel">
      <div class="panel-head">
        <h3>生成明细</h3>
        <span class="panel-sub">
          最近 {{ logs.total }} 次 · 每次生成扣 1 次（特权用户不扣）
        </span>
        <button class="btn-ghost" @click="loadLogs(logPage)">刷新明细</button>
      </div>
      <div class="table-card">
        <table class="tbl">
          <thead>
            <tr>
              <th>时间</th>
              <th>用户</th>
              <th>触发</th>
              <th>结果</th>
              <th>生成内容</th>
              <th>剩余次数</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!logs.items.length"><td colspan="6" class="empty">🎈 还没有生成记录</td></tr>
            <tr v-for="(log, idx) in logs.items" :key="idx">
              <td>
                <span>{{ log.time }}</span>
                <span class="mono">{{ log.date }}</span>
              </td>
              <td><span class="mono">{{ log.openid }}</span></td>
              <td>{{ triggerText(log.trigger) }}</td>
              <td>
                <span v-if="log.status === 'READY'" class="tag tag-on">成功 · {{ log.itemCount }} 条</span>
                <span v-else class="tag tag-off">失败</span>
                <span v-if="log.status !== 'READY' && log.error" class="err">{{ log.error }}</span>
              </td>
              <td>
                <span v-for="(title, ti) in log.titles.slice(0, 3)" :key="ti" class="log-title">
                  · {{ title }}
                </span>
                <span v-if="log.titles.length > 3" class="muted">
                  等 {{ log.titles.length }} 条
                </span>
                <span v-if="!log.titles.length" class="muted">—</span>
              </td>
              <td>
                <span v-if="log.creditsCharged">{{ log.creditsAfter ?? "-" }} 次</span>
                <span v-else class="muted">未扣（特权）</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="pager">
        <button class="btn-ghost" :disabled="logPage === 0" @click="loadLogs(logPage - 1)">
          上一页
        </button>
        <span class="muted">第 {{ logPage + 1 }} 页 · 共 {{ logs.total }} 条</span>
        <button
          class="btn-ghost"
          :disabled="(logPage + 1) * pageSize >= logs.total"
          @click="loadLogs(logPage + 1)"
        >
          下一页
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page { padding: 24px 28px; width: 100%; box-sizing: border-box; }
.page-head { margin-bottom: 18px; }
.page-title { display: flex; align-items: center; gap: 12px; }
.title-emoji { font-size: 34px; }
.page-title h2 { margin: 0; font-size: 22px; background: linear-gradient(90deg, #5b6ee8, #9a4ee0); -webkit-background-clip: text; background-clip: text; color: transparent; }
.page-title p { margin: 6px 0 0; font-size: 12.5px; color: #8a90a4; max-width: 900px; line-height: 1.7; }
.refresh { margin-left: auto; }

.stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 16px; }
.stat-card { border-radius: 14px; padding: 16px 18px; color: #fff; display: flex; flex-direction: column; gap: 6px; box-shadow: 0 6px 20px rgba(30, 40, 90, 0.15); }
.stat-a { background: linear-gradient(135deg, #5b6ee8, #7d57e0); }
.stat-b { background: linear-gradient(135deg, #3fb6a8, #2f9e8f); }
.stat-c { background: linear-gradient(135deg, #e8a33d, #d9822b); }
.stat-d { background: linear-gradient(135deg, #e8622c, #d1471d); }
.stat-label { font-size: 12px; opacity: 0.9; }
.stat-value { font-size: 26px; font-weight: 700; }
.stat-sub { font-size: 11.5px; opacity: 0.85; }

.toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.toolbar input { flex: 0 0 280px; padding: 9px 12px; border-radius: 10px; border: 1px solid #e2e6f3; font-size: 13px; }
.filter-tip { font-size: 12.5px; color: #5b6ee8; }
.filter-tip a { color: #e8622c; margin-left: 6px; }

.table-card { background: #fff; border-radius: 14px; box-shadow: 0 4px 18px rgba(30, 40, 90, 0.08); overflow: hidden; }
.tbl { width: 100%; border-collapse: collapse; font-size: 13px; }
.tbl th { text-align: left; padding: 12px 14px; color: #fff; font-weight: 600; background: linear-gradient(90deg, #5f6fe8, #8a5ce8); font-size: 12.5px; white-space: nowrap; }
.tbl td { padding: 10px 14px; border-bottom: 1px solid #f2f4fa; vertical-align: top; }
.tbl tbody tr:nth-child(even) { background: #fafbff; }
.tbl tbody tr:hover { background: #f0f4ff; }
.muted { color: #aab0c0; display: block; font-size: 11.5px; }
.mono { display: block; font-family: monospace; font-size: 11px; color: #a3a8ba; }
.user-cell { display: flex; align-items: center; gap: 10px; }
.avatar { width: 32px; height: 32px; border-radius: 50%; object-fit: cover; }
.avatar-fallback { display: flex; align-items: center; justify-content: center; background: #eef1fb; color: #5b6ee8; font-weight: 700; }
.user-main { display: flex; flex-direction: column; }
.user-name { font-weight: 600; color: #333a52; }
.credits { font-weight: 700; color: #e8622c; font-size: 15px; }
.tag { display: inline-block; padding: 3px 10px; border-radius: 999px; font-size: 11.5px; font-weight: 600; white-space: nowrap; }
.tag-on { background: #e8f7ee; color: #2f9e6b; }
.tag-off { background: #ffefef; color: #e05555; }
.err { display: block; margin-top: 4px; color: #e05555; font-size: 11.5px; max-width: 320px; }
.log-title { display: block; color: #333a52; }

.pager { display: flex; align-items: center; justify-content: center; gap: 12px; margin: 14px 0; }
.btn-primary, .btn-ghost, .btn-link { border: none; cursor: pointer; border-radius: 10px; font-size: 13px; padding: 8px 16px; }
.btn-primary { background: linear-gradient(90deg, #5b6ee8, #7d57e0); color: #fff; }
.btn-ghost { background: #f2f4fa; color: #5b6ee8; }
.btn-link { background: transparent; color: #5b6ee8; padding: 4px 6px; text-decoration: underline; }
.btn-primary:disabled, .btn-ghost:disabled { opacity: 0.5; cursor: not-allowed; }

.panel { background: #fff; border-radius: 14px; box-shadow: 0 4px 18px rgba(30, 40, 90, 0.08); padding: 18px 20px 6px; margin-top: 18px; }
.panel-head { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.panel-head h3 { margin: 0; font-size: 15px; color: #333a52; }
.panel-sub { font-size: 12px; color: #a3a8ba; }
.panel-head .btn-ghost { margin-left: auto; }
.error-banner { background: #ffefef; color: #e05555; padding: 10px 14px; border-radius: 10px; margin-bottom: 12px; font-size: 13px; }
.empty { color: #a3a8ba; text-align: center; padding: 32px 0; }
</style>
