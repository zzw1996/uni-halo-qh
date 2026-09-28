<template>
  <div class="page">
    <div class="page-head">
      <div class="page-title">
        <span class="title-emoji">💰</span>
        <div>
          <h2>支付看板</h2>
          <p>
            只统计已到货订单的金额与销量；待支付、需退款单独计数。券的发放与消耗来自积分流水的券记账字段。
          </p>
        </div>
        <button class="btn-primary refresh" :disabled="loading" @click="load">刷新</button>
      </div>
    </div>

    <div v-if="error" class="error-banner">{{ error }}</div>

    <div class="stat-grid">
      <div class="stat-card stat-a">
        <span class="stat-label">已到货金额（GMV）</span>
        <span class="stat-value">{{ yuan(stats.grossAmountCents) }}</span>
        <span class="stat-sub">已到货 {{ stats.deliveredOrders }} 笔 · 共 {{ stats.totalOrders }} 笔</span>
      </div>
      <div class="stat-card stat-b">
        <span class="stat-label">付费用户数</span>
        <span class="stat-value">{{ stats.payingUsers }}</span>
        <span class="stat-sub">客单价 {{ yuan(stats.avgAmountCents) }}</span>
      </div>
      <div class="stat-card stat-c">
        <span class="stat-label">券消耗率</span>
        <span class="stat-value">{{ stats.ticketConsumeRate }}%</span>
        <span class="stat-sub">
          已消耗 {{ stats.ticketsConsumed }} / 发放 {{ stats.ticketsPurchased + stats.ticketsFreeGranted }}
        </span>
      </div>
      <div class="stat-card stat-d">
        <span class="stat-label">待处理</span>
        <span class="stat-value">{{ stats.pendingOrders + stats.needsRefundOrders }}</span>
        <span class="stat-sub">待支付 {{ stats.pendingOrders }} · 待退款 {{ stats.needsRefundOrders }}</span>
      </div>
    </div>

    <div class="stat-grid">
      <div class="stat-card mini stat-e">
        <span class="stat-label">现金购买券</span>
        <span class="stat-value">{{ stats.ticketsPurchased }}</span>
      </div>
      <div class="stat-card mini stat-f">
        <span class="stat-label">免费赠出券</span>
        <span class="stat-value">{{ stats.ticketsFreeGranted }}</span>
      </div>
      <div class="stat-card mini stat-g">
        <span class="stat-label">账户沉淀券</span>
        <span class="stat-value">{{ stats.ticketsInHand }}</span>
      </div>
      <div class="stat-card mini stat-h">
        <span class="stat-label">装扮 / 会员 / 券单</span>
        <span class="stat-value">{{ stats.outfitSold }} / {{ stats.membershipSold }} / {{ stats.contentTicketOrders }}</span>
      </div>
      <div class="stat-card mini stat-h">
        <span class="stat-label">AI 定制内容</span>
        <span class="stat-value">{{ stats.aiContentSold }}</span>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <h3>近 14 天趋势</h3>
        <span class="panel-sub">柱高按当日金额，标注为订单数</span>
      </div>
      <div class="chart">
        <div v-for="(item, idx) in stats.trend" :key="idx" class="chart-col">
          <span class="chart-count">{{ item.count || "" }}</span>
          <div class="chart-bar-wrap">
            <div
              class="chart-bar"
              :style="{ height: barHeight(item.amountCents) }"
              :title="item.date + ' · ' + yuan(item.amountCents)"
            ></div>
          </div>
          <span class="chart-date">{{ item.date }}</span>
        </div>
      </div>
      <div v-if="!stats.trend.length" class="empty">暂无数据</div>
    </div>

    <div class="table-card">
      <div class="panel-head inner">
        <h3>商品销量排行</h3>
        <span class="panel-sub">按已到货金额倒序</span>
      </div>
      <table class="tbl">
        <thead>
          <tr>
            <th style="width: 60px">#</th>
            <th>商品</th>
            <th>类型</th>
            <th style="width: 110px">订单数</th>
            <th style="width: 140px">金额</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading"><td colspan="5" class="empty">⏳ 加载中…</td></tr>
          <tr v-else-if="!stats.goodsRanking.length">
            <td colspan="5" class="empty">🎈 还没有已到货的订单</td>
          </tr>
          <tr v-for="(item, idx) in stats.goodsRanking" :key="item.goodsId + idx">
            <td class="muted">{{ idx + 1 }}</td>
            <td>
              <span class="goods-name">{{ item.goodsName || item.goodsId || "-" }}</span>
              <span class="mono">{{ item.goodsId }}</span>
            </td>
            <td><span class="action-tag">{{ typeLabel(item.goodsType) }}</span></td>
            <td>{{ item.orderCount }}</td>
            <td class="money">{{ yuan(item.amountCents) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useConsoleApi } from "../api";

const { apiGet } = useConsoleApi();

interface PayStats {
  totalOrders: number;
  deliveredOrders: number;
  pendingOrders: number;
  needsRefundOrders: number;
  closedOrders: number;
  grossAmountCents: number;
  payingUsers: number;
  avgAmountCents: number;
  ticketsPurchased: number;
  ticketsFreeGranted: number;
  ticketsConsumed: number;
  ticketConsumeRate: number;
  ticketsInHand: number;
  outfitSold: number;
  membershipSold: number;
  contentTicketOrders: number;
  aiContentSold: number;
  trend: { date: string; count: number; amountCents: number }[];
  goodsRanking: {
    goodsId: string;
    goodsName: string;
    goodsType: string;
    orderCount: number;
    amountCents: number;
  }[];
}

const emptyStats: PayStats = {
  totalOrders: 0,
  deliveredOrders: 0,
  pendingOrders: 0,
  needsRefundOrders: 0,
  closedOrders: 0,
  grossAmountCents: 0,
  payingUsers: 0,
  avgAmountCents: 0,
  ticketsPurchased: 0,
  ticketsFreeGranted: 0,
  ticketsConsumed: 0,
  ticketConsumeRate: 0,
  ticketsInHand: 0,
  outfitSold: 0,
  membershipSold: 0,
  contentTicketOrders: 0,
  aiContentSold: 0,
  trend: [],
  goodsRanking: [],
};

const stats = ref<PayStats>({ ...emptyStats });
const loading = ref(false);
const error = ref("");

/** 分 → 元，保留两位 */
function yuan(cents: number | null | undefined) {
  return "￥" + (Number(cents || 0) / 100).toFixed(2);
}

const maxAmount = computed(() =>
  Math.max(1, ...stats.value.trend.map((i) => Number(i.amountCents || 0)))
);

/**
 * 柱高按当日金额比例。
 *
 * <p>最小 3% 而不是 0：金额为 0 的那天留一条细底边，柱子完全消失会让趋势图看起来断档。</p>
 */
function barHeight(amountCents: number) {
  const ratio = Number(amountCents || 0) / maxAmount.value;
  return Math.max(3, Math.round(ratio * 100)) + "%";
}

const TYPE_LABELS: Record<string, string> = {
  content_ticket: "阅读券",
  outfit: "装扮",
  membership: "会员卡",
  bundle: "套餐",
  dashboard: "数据看板",
  publish_ticket: "发布券",
};

function typeLabel(type: string) {
  return TYPE_LABELS[type] || type || "-";
}

async function load() {
  loading.value = true;
  error.value = "";
  try {
    const data = await apiGet("/payStats");
    stats.value = { ...emptyStats, ...(data || {}) };
    stats.value.trend = Array.isArray(data?.trend) ? data.trend : [];
    stats.value.goodsRanking = Array.isArray(data?.goodsRanking) ? data.goodsRanking : [];
  } catch (err: any) {
    error.value = err?.message || "加载失败";
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.page { padding: 24px 28px; width: 100%; box-sizing: border-box; }
.page-head { margin-bottom: 18px; }
.page-title { display: flex; align-items: center; gap: 12px; }
.title-emoji { font-size: 34px; }
.page-title h2 { margin: 0; font-size: 22px; background: linear-gradient(90deg, #5b6ee8, #9a4ee0); -webkit-background-clip: text; background-clip: text; color: transparent; }
.page-title p { margin: 4px 0 0; font-size: 12px; color: #8b90a0; }
.refresh { margin-left: auto; }

.stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 14px; margin-bottom: 16px; }
.stat-card { display: flex; flex-direction: column; padding: 16px 18px; border-radius: 14px; color: #fff; box-shadow: 0 6px 16px rgba(80, 100, 200, 0.18); }
.stat-a { background: linear-gradient(135deg, #5b6ee8, #7d57e0); }
.stat-b { background: linear-gradient(135deg, #2bb673, #1e9e8e); }
.stat-c { background: linear-gradient(135deg, #f2994a, #e8622c); }
.stat-d { background: linear-gradient(135deg, #4aa5f0, #4f74dd); }
.stat-e { background: linear-gradient(135deg, #7d57e0, #a06ceb); }
.stat-f { background: linear-gradient(135deg, #28b3b0, #1f8f9c); }
.stat-g { background: linear-gradient(135deg, #e8622c, #d1493f); }
.stat-h { background: linear-gradient(135deg, #5f6fe8, #4f74dd); }
.stat-label { font-size: 12px; opacity: 0.9; }
.stat-value { font-size: 28px; font-weight: 700; margin: 2px 0; }
.stat-sub { font-size: 11px; opacity: 0.75; }
.stat-card.mini .stat-value { font-size: 22px; }

button { padding: 7px 16px; border: none; border-radius: 8px; font-size: 13px; cursor: pointer; }
button:disabled { opacity: 0.5; cursor: not-allowed; }
.btn-primary { background: linear-gradient(90deg, #5b6ee8, #7d57e0); color: #fff; }
.error-banner { background: #ffefef; color: #e05555; padding: 10px 14px; border-radius: 10px; margin-bottom: 12px; font-size: 13px; }

.panel { background: #fff; border-radius: 14px; box-shadow: 0 4px 18px rgba(30, 40, 90, 0.08); padding: 18px 20px; margin-bottom: 16px; }
.panel-head { display: flex; align-items: baseline; gap: 12px; margin-bottom: 14px; }
.panel-head.inner { padding: 16px 18px 0; margin-bottom: 10px; }
.panel-head h3 { margin: 0; font-size: 15px; color: #333a52; }
.panel-sub { font-size: 12px; color: #a3a8ba; }

.chart { display: flex; align-items: flex-end; gap: 8px; height: 190px; }
.chart-col { flex: 1; display: flex; flex-direction: column; align-items: center; height: 100%; }
.chart-count { font-size: 11px; color: #7a8095; height: 16px; }
.chart-bar-wrap { flex: 1; width: 100%; display: flex; align-items: flex-end; }
.chart-bar { width: 100%; border-radius: 6px 6px 0 0; background: linear-gradient(180deg, #7d57e0, #5b6ee8); }
.chart-date { margin-top: 6px; font-size: 11px; color: #a3a8ba; white-space: nowrap; }

.table-card { background: #fff; border-radius: 14px; box-shadow: 0 4px 18px rgba(30, 40, 90, 0.08); overflow: hidden; }
.tbl { width: 100%; border-collapse: collapse; font-size: 13px; }
.tbl th { text-align: left; padding: 12px 14px; color: #fff; font-weight: 600; background: linear-gradient(90deg, #5f6fe8, #8a5ce8); font-size: 12.5px; white-space: nowrap; }
.tbl td { padding: 10px 14px; border-bottom: 1px solid #f2f4fa; vertical-align: top; }
.tbl tbody tr:nth-child(even) { background: #fafbff; }
.tbl tbody tr:hover { background: #f0f4ff; }
.muted { color: #aab0c0; }
.mono { display: block; font-family: monospace; font-size: 11px; color: #a3a8ba; }
.goods-name { display: block; font-weight: 600; color: #333a52; }
.money { font-weight: 700; color: #e8622c; white-space: nowrap; }
.action-tag { display: inline-block; padding: 3px 12px; border-radius: 999px; background: #eef1fb; color: #5b6ee8; font-size: 12px; font-weight: 600; white-space: nowrap; }
.empty { color: #a3a8ba; text-align: center; padding: 32px 0; }
</style>
