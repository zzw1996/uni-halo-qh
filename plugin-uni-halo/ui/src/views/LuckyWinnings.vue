<template>
  <div class="page">
    <div class="page-head">
      <div class="page-title">
        <span class="title-emoji">🎁</span>
        <div>
          <h2>兑换礼品 · 中奖与发放</h2>
          <p>中奖记录 · 收货信息 · 物流发放 · 站长操作台</p>
        </div>
      </div>
    </div>

    <div class="stat-grid">
      <div class="stat-card stat-a">
        <span class="stat-label">中奖总数</span>
        <span class="stat-value">{{ state.total.value }}</span>
        <span class="stat-sub">全部中奖单</span>
      </div>
      <div class="stat-card stat-b">
        <span class="stat-label">当前页待填写</span>
        <span class="stat-value">{{ statPending }}</span>
        <span class="stat-sub">等待用户填写收货信息</span>
      </div>
      <div class="stat-card stat-c">
        <span class="stat-label">当前页待发货</span>
        <span class="stat-value">{{ statSubmitted }}</span>
        <span class="stat-sub">已提交待安排发放</span>
      </div>
      <div class="stat-card stat-d">
        <span class="stat-label">当前页已发货</span>
        <span class="stat-value">{{ statShipped }}</span>
        <span class="stat-sub">发放完成</span>
      </div>
    </div>

    <div class="toolbar">
      <select v-model="state.status.value" @change="load(0)">
        <option value="">全部状态</option>
        <option value="pending">待填写收货信息</option>
        <option value="submitted">已提交待发货</option>
        <option value="shipped">已发货</option>
      </select>
      <input v-model="state.keyword.value" placeholder="🔍 搜索奖品 / 收货人 / 手机号 / openid" @keyup.enter="load(0)" />
      <button class="btn-primary" @click="load(0)">查询</button>
    </div>

    <div v-if="state.error.value" class="error-banner">⚠️ {{ state.error.value }}</div>

    <div class="table-card">
      <table class="tbl">
        <thead>
          <tr><th>奖品</th><th>用户</th><th>状态</th><th>收货人</th><th>电话</th><th>地址</th><th>中奖 / 提交</th><th>物流信息</th><th>备注</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-if="state.loading.value"><td colspan="10" class="empty">⏳ 加载中…</td></tr>
          <tr v-else-if="!state.items.value.length"><td colspan="10" class="empty">🎈 暂无中奖记录</td></tr>
          <tr v-for="(item, idx) in state.items.value" :key="idx">
            <td class="prize-cell">
              <span class="prize-emoji">{{ item.emoji }}</span>
              <span class="prize-name">{{ item.prizeName }}</span>
            </td>
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
              <span class="tag" :class="statusClass(item.status)">{{ statusLabel(item.status) }}</span>
            </td>
            <td>{{ item.contactName || "-" }}</td>
            <td class="mono">{{ item.phone || "-" }}</td>
            <td class="addr">{{ item.address || "-" }}</td>
            <td class="time-cell">
              <span class="time">中奖 {{ fmt(item.winTime) }}</span>
              <span v-if="item.submitTime" class="time">提交 {{ fmt(item.submitTime) }}</span>
            </td>
            <td class="express-cell">
              <template v-if="item.status === 'shipped'">
                <span class="express-tag">{{ item.expressCompany || "快递" }} {{ item.expressNo || "（无单号）" }}</span>
                <span class="time">发货 {{ fmt(item.shipTime) }}</span>
              </template>
              <span v-else class="muted">-</span>
            </td>
            <td class="remark-cell">{{ item.remark || "-" }}</td>
            <td class="ops-cell">
              <button class="op-btn btn-plain" @click="openEdit(item)">编辑</button>
              <button v-if="item.status === 'submitted'" class="op-btn btn-primary" @click="openShip(item)">发货</button>
              <button v-else-if="item.status === 'shipped'" class="op-btn btn-plain" @click="reopen(item)">取消发货</button>
              <span v-else class="muted">等待填写</span>
              <button class="op-btn btn-danger" @click="removeItem(item)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>

      <div class="pager">
        <button :disabled="state.page.value === 0" @click="load(state.page.value - 1)">上一页</button>
        <span>第 {{ state.page.value + 1 }} / {{ totalPages }} 页 · 共 {{ state.total.value }} 条</span>
        <button :disabled="state.page.value + 1 >= totalPages" @click="load(state.page.value + 1)">下一页</button>
      </div>
    </div>

    <!-- 编辑弹窗 -->
    <div v-if="editDialog.visible" class="mask" @click.self="closeEdit">
      <div class="dialog">
        <h3>编辑中奖单 · {{ editDialog.item?.prizeName }}</h3>
        <div class="dialog-row">
          <label>状态</label>
          <select v-model="editDialog.form.status">
            <option value="pending">待填写收货信息</option>
            <option value="submitted">已提交待发货</option>
            <option value="shipped">已发货</option>
          </select>
        </div>
        <div class="dialog-row">
          <label>收货人</label>
          <input v-model="editDialog.form.contactName" placeholder="收货人姓名（可修正）" />
        </div>
        <div class="dialog-row">
          <label>手机号</label>
          <input v-model="editDialog.form.phone" placeholder="11 位手机号（可修正）" />
        </div>
        <div class="dialog-row">
          <label>收货地址</label>
          <input v-model="editDialog.form.address" placeholder="收货地址（可修正）" />
        </div>
        <div class="dialog-row">
          <label>备注</label>
          <input v-model="editDialog.form.remark" placeholder="备注（可修正）" />
        </div>
        <p class="dialog-tip">状态改为「待填写收货信息」后，用户端会重新出现填写表单（原收货信息会清空）。</p>
        <div class="dialog-actions">
          <button class="btn-plain" :disabled="editing" @click="closeEdit">取消</button>
          <button class="btn-primary" :disabled="editing" @click="confirmEdit">{{ editing ? "保存中…" : "保存" }}</button>
        </div>
      </div>
    </div>

    <!-- 发货弹窗 -->
    <div v-if="shipDialog.visible" class="mask" @click.self="closeShip">
      <div class="dialog">
        <h3>标记已发货 · {{ shipDialog.item?.prizeName }}</h3>
        <div class="dialog-row">
          <label>快递公司</label>
          <input v-model="shipDialog.form.expressCompany" placeholder="如：顺丰速运（可不填，支持线下发放）" />
        </div>
        <div class="dialog-row">
          <label>物流单号</label>
          <input v-model="shipDialog.form.expressNo" placeholder="如：SF123456789（可不填）" />
        </div>
        <div class="dialog-row">
          <label>发放备注</label>
          <input v-model="shipDialog.form.remark" placeholder="如：线下见面领取（可不填）" />
        </div>
        <div class="dialog-actions">
          <button class="btn-plain" :disabled="shipping" @click="closeShip">取消</button>
          <button class="btn-primary" :disabled="shipping" @click="confirmShip">{{ shipping ? "发货中…" : "确认发货" }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { formatTime, useConsoleApi, useTableState } from "../api";

const { apiGet, apiPut, apiDelete } = useConsoleApi();
const state = useTableState<{
  id: string;
  wxOpenid: string;
  nickname: string;
  avatarUrl: string;
  prizeName: string;
  emoji: string;
  status: string;
  contactName: string;
  phone: string;
  address: string;
  winTime: string;
  submitTime: string;
  expressCompany: string;
  expressNo: string;
  shipTime: string;
  remark: string;
}>();

const totalPages = computed(() => Math.ceil(state.total.value / state.size.value));
const statPending = computed(() => state.items.value.filter((i) => i.status === "pending").length);
const statSubmitted = computed(() => state.items.value.filter((i) => i.status === "submitted").length);
const statShipped = computed(() => state.items.value.filter((i) => i.status === "shipped").length);

const STATUS_LABELS: Record<string, string> = {
  pending: "待填写收货信息",
  submitted: "已提交待发货",
  shipped: "已发货",
};

const shipping = ref(false);
const editing = ref(false);
const shipDialog = reactive({
  visible: false,
  item: null as { id: string; prizeName: string } | null,
  form: { expressCompany: "", expressNo: "", remark: "" },
});
const editDialog = reactive({
  visible: false,
  item: null as { id: string; prizeName: string } | null,
  form: { status: "submitted", contactName: "", phone: "", address: "", remark: "" },
});

function statusLabel(status: string) {
  return STATUS_LABELS[status] || status || "-";
}

function statusClass(status: string) {
  if (status === "shipped") return "tag-ok";
  if (status === "submitted") return "tag-submit";
  return "tag-wait";
}

function fmt(value: string | null | undefined) {
  return formatTime(value);
}

async function load(page: number) {
  state.page.value = page;
  state.loading.value = true;
  state.error.value = "";
  try {
    const data = await apiGet("/luckyWinnings", {
      status: state.status.value,
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

function openShip(item: any) {
  shipDialog.item = { id: item.id, prizeName: item.prizeName };
  shipDialog.form.expressCompany = "";
  shipDialog.form.expressNo = "";
  shipDialog.form.remark = "";
  shipDialog.visible = true;
}

function closeShip() {
  shipDialog.visible = false;
  shipDialog.item = null;
}

async function confirmShip() {
  if (!shipDialog.item || shipping.value) return;
  shipping.value = true;
  try {
    await apiPut(`/luckyWinnings/${shipDialog.item.id}/ship`, {
      expressCompany: shipDialog.form.expressCompany.trim(),
      expressNo: shipDialog.form.expressNo.trim(),
      remark: shipDialog.form.remark.trim(),
    });
    closeShip();
    await load(state.page.value);
  } catch (err: any) {
    state.error.value = err?.message || "标记发货失败";
  } finally {
    shipping.value = false;
  }
}

async function reopen(item: any) {
  if (!window.confirm(`确认取消「${item.prizeName}」的发货标记？`)) return;
  try {
    await apiPut(`/luckyWinnings/${item.id}/reopen`);
    await load(state.page.value);
  } catch (err: any) {
    state.error.value = err?.message || "取消发货失败";
  }
}

function openEdit(item: any) {
  editDialog.item = { id: item.id, prizeName: item.prizeName };
  editDialog.form.status = item.status || "submitted";
  editDialog.form.contactName = item.contactName || "";
  editDialog.form.phone = item.phone || "";
  editDialog.form.address = item.address || "";
  editDialog.form.remark = item.remark || "";
  editDialog.visible = true;
}

function closeEdit() {
  editDialog.visible = false;
  editDialog.item = null;
}

async function confirmEdit() {
  if (!editDialog.item || editing.value) return;
  editing.value = true;
  try {
    await apiPut(`/luckyWinnings/${editDialog.item.id}`, {
      status: editDialog.form.status,
      contactName: editDialog.form.contactName.trim(),
      phone: editDialog.form.phone.trim(),
      address: editDialog.form.address.trim(),
      remark: editDialog.form.remark.trim(),
    });
    closeEdit();
    await load(state.page.value);
  } catch (err: any) {
    state.error.value = err?.message || "保存失败";
  } finally {
    editing.value = false;
  }
}

async function removeItem(item: any) {
  if (!window.confirm(`确认删除「${item.prizeName}」中奖单？此操作不可恢复。`)) return;
  try {
    await apiDelete(`/luckyWinnings/${item.id}`);
    await load(state.page.value);
  } catch (err: any) {
    state.error.value = err?.message || "删除失败";
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
.toolbar select { width: 170px; }
select, .toolbar input { padding: 8px 12px; border: 1px solid #e3e7f0; border-radius: 8px; font-size: 13px; background: #f8f9fd; }
.toolbar input { flex: 1; }
button { padding: 7px 16px; border: none; border-radius: 8px; font-size: 13px; cursor: pointer; }
button:disabled { opacity: 0.5; cursor: not-allowed; }
.op-btn { padding: 4px 12px; font-size: 12px; margin-right: 6px; margin-bottom: 2px; }
.btn-primary { background: linear-gradient(90deg, #5b6ee8, #7d57e0); color: #fff; }
.btn-plain { background: #fff; color: #5b6ee8; border: 1px solid #c9d2f5; }
.btn-danger { background: #fff; color: #e05555; border: 1px solid #f0b9b9; }

.error-banner { background: #ffefef; color: #e05555; padding: 10px 14px; border-radius: 10px; margin-bottom: 12px; font-size: 13px; }

.table-card { background: #fff; border-radius: 14px; box-shadow: 0 4px 18px rgba(30, 40, 90, 0.08); overflow-x: auto; }
.tbl { width: 100%; border-collapse: collapse; font-size: 13px; }
.tbl th { text-align: left; padding: 12px 14px; color: #fff; font-weight: 600; background: linear-gradient(90deg, #5f6fe8, #8a5ce8); font-size: 12.5px; white-space: nowrap; }
.tbl td { padding: 10px 14px; border-bottom: 1px solid #f2f4fa; vertical-align: top; }
.tbl tbody tr:nth-child(even) { background: #fafbff; }
.tbl tbody tr:hover { background: #f0f4ff; }
.muted { color: #aab0c0; }
.mono { font-family: monospace; font-size: 11px; }
.time { color: #7a8095; font-size: 12px; }
.time-cell { display: flex; flex-direction: column; gap: 2px; white-space: nowrap; }

.prize-cell { display: flex; align-items: center; gap: 8px; white-space: nowrap; }
.prize-emoji { font-size: 22px; }
.prize-name { font-weight: 600; }

.user-cell { display: inline-flex; align-items: center; gap: 10px; }
.avatar { width: 36px; height: 36px; border-radius: 50%; object-fit: cover; flex-shrink: 0; border: 2px solid #eef1fb; }
.avatar-fallback { display: inline-flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #6a7bf0, #a06ceb); color: #fff; font-size: 14px; font-weight: 600; }
.user-main { display: flex; flex-direction: column; line-height: 1.35; }
.user-name { font-weight: 600; }

.tag { display: inline-block; padding: 3px 12px; border-radius: 999px; font-size: 12px; font-weight: 600; white-space: nowrap; }
.tag-wait { background: #fff3e0; color: #e8862b; }
.tag-submit { background: #e8f1ff; color: #3a6ee0; }
.tag-ok { background: #e6f7e6; color: #2aa34a; }

.addr { max-width: 220px; }
.express-cell { display: flex; flex-direction: column; gap: 2px; max-width: 190px; }
.express-tag { display: inline-block; padding: 2px 10px; border-radius: 999px; background: #e6f7e6; color: #2aa34a; font-size: 12px; word-break: break-all; }
.remark-cell { max-width: 160px; color: #777d90; word-break: break-all; }
.ops-cell { white-space: nowrap; }

.empty { text-align: center; color: #aab0c0; padding: 48px 0; font-size: 14px; }
.pager { display: flex; gap: 12px; align-items: center; justify-content: flex-end; padding: 12px 14px; color: #8b90a0; font-size: 13px; }

.mask { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.45); display: flex; align-items: center; justify-content: center; z-index: 100; }
.dialog { width: 480px; max-width: 92vw; background: #fff; border-radius: 12px; padding: 20px 22px; box-shadow: 0 8px 30px rgba(0, 0, 0, 0.18); }
.dialog h3 { margin: 0 0 16px; font-size: 15px; }
.dialog-row { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.dialog-row label { width: 72px; flex-shrink: 0; color: #606266; font-size: 13px; }
.dialog-row input, .dialog-row select { flex: 1; }
.dialog-tip { margin: 4px 0 0; font-size: 12px; color: #e8862b; line-height: 1.6; }
.dialog-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 18px; }
</style>
