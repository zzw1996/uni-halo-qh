<template>
  <div class="page">
    <div class="page-head">
      <div class="page-title">
        <span class="title-emoji">👥</span>
        <div>
          <h2>小程序用户</h2>
          <p>微信小程序注册用户一览 · 头像 / 资料 / 绑定状态</p>
        </div>
      </div>
    </div>

    <div class="stat-grid">
      <div class="stat-card stat-a">
        <span class="stat-label">用户总数</span>
        <span class="stat-value">{{ state.total.value }}</span>
        <span class="stat-sub">全部注册用户</span>
      </div>
      <div class="stat-card stat-b">
        <span class="stat-label">资料完整</span>
        <span class="stat-value">{{ statComplete }}</span>
        <span class="stat-sub">当前页 · 资料完整</span>
      </div>
      <div class="stat-card stat-c">
        <span class="stat-label">待完善</span>
        <span class="stat-value">{{ statIncomplete }}</span>
        <span class="stat-sub">当前页 · 未完善资料</span>
      </div>
      <div class="stat-card stat-d">
        <span class="stat-label">绑定 Halo 帐号</span>
        <span class="stat-value">{{ statBound }}</span>
        <span class="stat-sub">当前页 · 已绑定</span>
      </div>
    </div>

    <div class="toolbar">
      <input v-model="state.keyword.value" placeholder="🔍 搜索昵称 / openid / Halo 账号 / unionid" @keyup.enter="load(0)" />
      <button class="btn-primary" @click="load(0)">查询</button>
      <button class="btn-plain" @click="resetFilters">重置</button>
    </div>

    <div v-if="state.error.value" class="error-banner">⚠️ {{ state.error.value }}</div>

    <div class="table-card">
      <table class="tbl">
        <thead>
          <tr><th>用户</th><th>Halo 账号</th><th>unionid</th><th>资料</th><th>注册时间</th></tr>
        </thead>
        <tbody>
          <tr v-if="state.loading.value"><td colspan="5" class="empty">⏳ 加载中…</td></tr>
          <tr v-else-if="!state.items.value.length"><td colspan="5" class="empty">🎈 暂无数据</td></tr>
          <tr v-for="item in state.items.value" :key="item.id">
            <td>
              <span class="user-cell">
                <img v-if="item.avatarUrl" class="avatar" :src="item.avatarUrl" :alt="item.nickname" />
                <span v-else class="avatar avatar-fallback">{{ (item.nickname || "U").slice(0, 1) }}</span>
                <span class="user-main">
                  <span class="user-name">{{ item.nickname || "-" }}</span>
                  <span class="mono">{{ item.openid }}</span>
                </span>
              </span>
            </td>
            <td>
              <span v-if="item.haloUserName" class="halo-tag">@ {{ item.haloUserName }}</span>
              <span v-else class="muted">未绑定</span>
            </td>
            <td class="mono muted">{{ item.unionid || "-" }}</td>
            <td>
              <span class="tag" :class="item.profileComplete ? 'tag-ok' : 'tag-wait'">{{ item.profileComplete ? "完整" : "未完善" }}</span>
              <span v-if="item.profileModifyCount" class="count-tag">改了 {{ item.profileModifyCount }} 次</span>
            </td>
            <td class="time">{{ fmt(item.creationTimestamp) }}</td>
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
  id: string;
  openid: string;
  unionid: string;
  nickname: string;
  avatarUrl: string;
  profileComplete: boolean;
  profileModifyCount: number;
  haloUserName: string;
  creationTimestamp: string;
}>();

const totalPages = computed(() => Math.ceil(state.total.value / state.size.value));
const statComplete = computed(() => state.items.value.filter((i) => i.profileComplete).length);
const statIncomplete = computed(() => state.items.value.filter((i) => !i.profileComplete).length);
const statBound = computed(() => state.items.value.filter((i) => i.haloUserName).length);

function fmt(value: string | null | undefined) {
  return formatTime(value);
}

async function load(page: number) {
  state.page.value = page;
  state.loading.value = true;
  state.error.value = "";
  try {
    const data = await apiGet("/wxUsers", {
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

function resetFilters() {
  state.keyword.value = "";
  load(0);
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
.toolbar input { flex: 1; padding: 8px 12px; border: 1px solid #e3e7f0; border-radius: 8px; font-size: 13px; background: #f8f9fd; }
button { padding: 7px 16px; border: none; border-radius: 8px; font-size: 13px; cursor: pointer; }
button:disabled { opacity: 0.5; cursor: not-allowed; }
.btn-primary { background: linear-gradient(90deg, #5b6ee8, #7d57e0); color: #fff; }
.btn-plain { background: #fff; color: #5b6ee8; border: 1px solid #c9d2f5; }

.error-banner { background: #ffefef; color: #e05555; padding: 10px 14px; border-radius: 10px; margin-bottom: 12px; font-size: 13px; }

.table-card { background: #fff; border-radius: 14px; box-shadow: 0 4px 18px rgba(30, 40, 90, 0.08); overflow-x: auto; }
.tbl { width: 100%; border-collapse: collapse; font-size: 13px; }
.tbl th { text-align: left; padding: 12px 14px; color: #fff; font-weight: 600; background: linear-gradient(90deg, #5f6fe8, #8a5ce8); font-size: 12.5px; white-space: nowrap; }
.tbl td { padding: 10px 14px; border-bottom: 1px solid #f2f4fa; }
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

.halo-tag { display: inline-block; padding: 2px 10px; border-radius: 999px; background: #e8f1ff; color: #3a6ee0; font-size: 12px; }
.tag { display: inline-block; padding: 3px 12px; border-radius: 999px; font-size: 12px; font-weight: 600; }
.tag-ok { background: #e6f7e6; color: #2aa34a; }
.tag-wait { background: #fff3e0; color: #e8862b; }
.count-tag { display: inline-block; margin-left: 6px; color: #9aa0b5; font-size: 11px; }

.empty { text-align: center; color: #aab0c0; padding: 48px 0; font-size: 14px; }
.pager { display: flex; gap: 12px; align-items: center; justify-content: flex-end; padding: 12px 14px; color: #8b90a0; font-size: 13px; }
</style>
