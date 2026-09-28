<template>
  <div class="page">
    <div class="page-head">
      <div class="page-title">
        <span class="title-emoji">⚙️</span>
        <div>
          <h2>商业化配置</h2>
          <p>
            把互相有关联的配置放在一处编辑：权益与定价、积分经济、装扮与等级。与「插件设置」写的是同一份配置，两边随时同步。
          </p>
        </div>
        <div class="head-actions">
          <span v-if="dirty" class="dirty-tip">有未保存的修改</span>
          <button class="btn-plain" :disabled="loading" @click="load">放弃修改</button>
          <button class="btn-primary" :disabled="loading || saving" @click="save">
            {{ saving ? "保存中…" : "保存" }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="error" class="error-banner">{{ error }}</div>

    <!-- 关联检查：这是本页面的核心价值，放在最上面 -->
    <div class="panel">
      <div class="panel-head">
        <h3>关联检查</h3>
        <span class="panel-sub">
          {{ checkSummary }}
        </span>
      </div>
      <div v-if="!checks.length" class="check-empty">
        ✅ 没有发现配置冲突
      </div>
      <div
        v-for="(item, idx) in checks"
        :key="idx"
        class="check-item"
        :class="'check-' + item.level"
      >
        <span class="check-level">{{ levelLabel(item.level) }}</span>
        <div class="check-main">
          <span class="check-title">{{ item.title }}</span>
          <span class="check-detail">{{ item.detail }}</span>
          <span class="check-group">相关配置：{{ groupLabel(item.group) }}</span>
        </div>
      </div>
    </div>

    <!-- 分组切换：按业务归并，而不是按 YAML 的分组顺序 -->
    <div class="tabs">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        class="tab"
        :class="{ 'tab-active': tab.key === activeTab }"
        @click="activeTab = tab.key"
      >
        {{ tab.label }}
      </button>
    </div>

    <div v-if="loading" class="panel">⏳ 加载中…</div>

    <template v-else>
      <div v-for="group in visibleGroups" :key="group.group" class="panel">
        <div class="panel-head">
          <h3>{{ group.label || group.group }}</h3>
          <span class="panel-sub">{{ group.group }}</span>
        </div>
        <SchemaField
          v-for="(node, idx) in group.formSchema"
          :key="group.group + '-' + idx"
          :node="node"
          :model="models[group.group] || {}"
          :id-map="idMaps[group.group] || {}"
          :customized="customized[group.group]"
          @check="markDirty"
        />
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useConsoleApi } from "../api";
import SchemaField from "./components/SchemaField.vue";

/**
 * 「商业化配置」页面。
 *
 * 字段定义不在这里声明，而是直接渲染后端返回的 formSchema（来自 Halo 解析好的
 * Setting.spec.forms），因此 setting.yaml 是唯一来源，不会出现两处定义不一致。
 * 本页面相对「插件设置」多出来的是：把互相耦合的分组放在一处、以及关联检查。
 */
const { apiGet, apiPut } = useConsoleApi();

interface ConfigGroup {
  group: string;
  label: string;
  formSchema: Array<Record<string, any>>;
  /** 已保存的值 */
  values: Record<string, any>;
  /** 实际生效的配置（已补齐运行时默认值） */
  effective: Record<string, any>;
}

interface ConfigCheck {
  level: string;
  group: string;
  title: string;
  detail: string;
}

/** 按业务把分组归到三个页签：互相关联的配置在同一屏 */
const TAB_DEFS = [
  { key: "commerce", label: "权益与定价", groups: ["payConfig", "aiContentConfig", "memberConfig", "contentConfig", "ticketConfig"] },
  { key: "points", label: "积分经济", groups: ["pointsConfig", "dailyTaskConfig", "mallConfig"] },
  { key: "level", label: "装扮与等级", groups: ["levelConfig"] },
];

const groups = ref<ConfigGroup[]>([]);
const checks = ref<ConfigCheck[]>([]);
const models = reactive<Record<string, Record<string, any>>>({});
const idMaps = reactive<Record<string, Record<string, string>>>({});
/** 各分组里站长保存过的字段名，用于给未自定义的字段打「默认」标记 */
const customized = reactive<Record<string, Set<string>>>({});
const activeTab = ref(TAB_DEFS[0].key);
const loading = ref(true);
const saving = ref(false);
const error = ref("");
const dirty = ref(false);

const tabs = TAB_DEFS;
const visibleGroups = computed(
  () => {
    const tab = TAB_DEFS.find((item) => item.key === activeTab.value);
    if (!tab) return groups.value;
    return groups.value.filter((group) => tab.groups.includes(group.group));
  }
);

const checkSummary = computed(() => {
  if (!checks.value.length) return "未发现问题";
  const errors = checks.value.filter((item) => item.level === "error").length;
  const warns = checks.value.filter((item) => item.level === "warn").length;
  const infos = checks.value.filter((item) => item.level === "info").length;
  const parts: string[] = [];
  if (errors) parts.push(`${errors} 个需修复`);
  if (warns) parts.push(`${warns} 个建议关注`);
  if (infos) parts.push(`${infos} 条提示`);
  return parts.join(" · ");
});

function levelLabel(level: string) {
  if (level === "error") return "需修复";
  if (level === "warn") return "建议";
  return "提示";
}

function groupLabel(group: string) {
  const found = groups.value.find((item) => item.group === group);
  return found?.label || group;
}

function markDirty() {
  dirty.value = true;
}

/**
 * 初始化表单值，取值优先级：已保存的值 → 实际生效值 → schema 里的表单默认值。
 *
 * 为什么第二级是「实际生效值」而不是 schema 默认值：配置存在两套默认值
 * （setting.yaml 表单的 value 与 Java 解析器的 defaults），两者未必一致。
 * 若按表单默认值展示，站长看到的就不是系统真正在用的数字，
 * 而且一保存就把表单里的数字固化下来，等于悄悄改了行为。
 * 以生效值为准，则「看到的 = 在用的」，保存也不会改变任何东西。
 */
function buildModel(group: ConfigGroup) {
  const stored = group.values || {};
  const effective = group.effective || {};
  const model: Record<string, any> = {};
  const idMap: Record<string, string> = {};
  (group.formSchema || []).forEach((node) => {
    if (!node || !node.name) return;
    if (node.id && node.name) {
      idMap[node.id] = node.name;
    }
    if (stored[node.name] !== undefined) {
      model[node.name] = stored[node.name];
    } else if (effective[node.name] !== undefined) {
      model[node.name] = effective[node.name];
    } else {
      model[node.name] = node.value;
    }
    // 可重复项必须是数组、子分组必须是对象，否则子组件无法渲染
    if (node.$formkit === "repeater" && !Array.isArray(model[node.name])) {
      model[node.name] = Array.isArray(node.value) ? node.value : [];
    }
    if (node.$formkit === "group"
      && (model[node.name] === null || typeof model[node.name] !== "object" || Array.isArray(model[node.name]))) {
      model[node.name] = node.value && typeof node.value === "object" && !Array.isArray(node.value)
        ? node.value
        : {};
    }
  });
  models[group.group] = model;
  idMaps[group.group] = idMap;
  customized[group.group] = new Set(Object.keys(stored));
}

async function load() {
  loading.value = true;
  error.value = "";
  try {
    const data = await apiGet("/commercialConfig");
    groups.value = Array.isArray(data?.groups) ? data.groups : [];
    checks.value = Array.isArray(data?.checks) ? data.checks : [];
    groups.value.forEach(buildModel);
    dirty.value = false;
  } catch (err: any) {
    error.value = err?.message || "加载失败";
  } finally {
    loading.value = false;
  }
}

/**
 * 保存。
 *
 * 存在「需修复」级问题时先弹确认：这类问题会导致用户付款后拿不到权益、
 * 或某个功能完全不可用，但它不一定是你这次想改的东西，所以给一条明确绕过路径，
 * 而不是直接禁止保存让你卡死在这个页面。
 */
async function save() {
  const errors = checks.value.filter((item) => item.level === "error");
  if (errors.length) {
    const list = errors.map((item, idx) => `${idx + 1}. ${item.title}`).join("\n");
    const confirmed = window.confirm(
      `以下 ${errors.length} 个问题会导致功能不可用或用户付款后拿不到权益：\n\n${list}\n\n仍要保存吗？`
    );
    if (!confirmed) return;
  }
  saving.value = true;
  error.value = "";
  try {
    const payload: Record<string, Record<string, any>> = {};
    groups.value.forEach((group) => {
      payload[group.group] = models[group.group] || {};
    });
    const data = await apiPut("/commercialConfig", { groups: payload });
    groups.value = Array.isArray(data?.groups) ? data.groups : groups.value;
    checks.value = Array.isArray(data?.checks) ? data.checks : [];
    groups.value.forEach(buildModel);
    dirty.value = false;
  } catch (err: any) {
    error.value = err?.message || "保存失败";
  } finally {
    saving.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.page { padding: 24px 28px; width: 100%; box-sizing: border-box; }
.page-head { margin-bottom: 18px; }
.page-title { display: flex; align-items: flex-start; gap: 12px; }
.title-emoji { font-size: 34px; line-height: 1.1; }
.page-title h2 { margin: 0; font-size: 22px; background: linear-gradient(90deg, #5b6ee8, #9a4ee0); -webkit-background-clip: text; background-clip: text; color: transparent; }
.page-title p { margin: 4px 0 0; font-size: 12px; color: #8b90a0; max-width: 700px; line-height: 1.7; }
.head-actions { margin-left: auto; display: flex; align-items: center; gap: 10px; }
.dirty-tip { color: #e8862b; font-size: 12px; }

button { padding: 7px 16px; border: none; border-radius: 8px; font-size: 13px; cursor: pointer; }
button:disabled { opacity: 0.5; cursor: not-allowed; }
.btn-primary { background: linear-gradient(90deg, #5b6ee8, #7d57e0); color: #fff; }
.btn-plain { background: #fff; color: #5b6178; border: 1px solid #dcdfe6; }

.error-banner { background: #ffefef; color: #e05555; padding: 10px 14px; border-radius: 10px; margin-bottom: 12px; font-size: 13px; }

.panel { background: #fff; border-radius: 14px; box-shadow: 0 4px 18px rgba(30, 40, 90, 0.08); padding: 18px 20px; margin-bottom: 16px; }
.panel-head { display: flex; align-items: baseline; gap: 12px; margin-bottom: 14px; }
.panel-head h3 { margin: 0; font-size: 15px; color: #333a52; }
.panel-sub { font-size: 12px; color: #a3a8ba; }

.check-empty { padding: 10px 0; color: #17a673; font-size: 13px; }
.check-item { display: flex; gap: 12px; padding: 12px 14px; border-radius: 10px; margin-bottom: 10px; }
.check-item:last-child { margin-bottom: 0; }
.check-error { background: #fff1f1; }
.check-warn { background: #fff8ec; }
.check-info { background: #f2f6ff; }
.check-level { flex-shrink: 0; align-self: flex-start; padding: 2px 10px; border-radius: 999px; font-size: 11.5px; font-weight: 600; }
.check-error .check-level { background: #fdd; color: #c93b3b; }
.check-warn .check-level { background: #ffe6bf; color: #a96a10; }
.check-info .check-level { background: #dce6ff; color: #3d5bbf; }
.check-main { display: flex; flex-direction: column; gap: 3px; }
.check-title { color: #2f3548; font-size: 13px; font-weight: 600; }
.check-detail { color: #6b7186; font-size: 12.5px; line-height: 1.75; }
.check-group { color: #a3a8ba; font-size: 11.5px; }

.tabs { display: flex; gap: 8px; margin-bottom: 14px; }
.tab { padding: 8px 20px; border-radius: 999px; background: #fff; color: #5b6178; border: 1px solid #e3e7f0; }
.tab-active { background: linear-gradient(90deg, #5b6ee8, #7d57e0); color: #fff; border-color: transparent; }
</style>
