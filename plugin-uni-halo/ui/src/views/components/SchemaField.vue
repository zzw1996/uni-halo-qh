<template>
  <!--
    原始 HTML 块（setting.yaml 里的 $el: div）：只在分组顶部放说明文案，
    内容来自插件自己的 YAML，非用户输入。
  -->
  <div v-if="isRawBlock" class="raw-block" v-html="node.innerHTML"></div>

  <div v-else-if="visible" class="field">
    <label class="field-label">
      {{ node.label || node.name }}
      <span v-if="required" class="field-required">*</span>
      <span v-if="isDefault" class="field-default" title="该字段没有单独保存过，这里显示的是系统实际生效的默认值">默认</span>
    </label>

    <!--
      ⚠️ 下面这一支必须是 v-if：v-else-if 只能紧跟 v-if，中间也不能夹注释。
      带 children 的节点有两种，必须显式区分（靠「有没有 children」推断是错的）：
      - $formkit: group 是对象（如「积分规则 rules」下的浏览/评论/点赞/签到），
        渲染成带小标题的一片字段；
      - $formkit: repeater 是数组（goods / outfits / tasks …），每项一张卡片可增删排序。
      早期实现把 group 也当数组，于是「积分规则」被画成空列表 + 「添加一项」按钮，
      站长根本没法编辑。
    -->
    <div v-if="isGroup" class="sub-group">
      <div class="sub-group-title">{{ node.label || node.name }}</div>
      <SchemaField
        v-for="child in node.children || []"
        :key="child.name"
        :node="child"
        :model="groupModel"
        :id-map="idMap"
        :customized="customized"
      />
    </div>

    <div v-else-if="isRepeater" class="repeater">
      <div v-for="(item, index) in repeaterItems" :key="index" class="repeater-item">
        <div class="repeater-head">
          <span class="repeater-index">#{{ index + 1 }}</span>
          <span class="repeater-actions">
            <button :disabled="index === 0" @click="moveUp(index)">上移</button>
            <button :disabled="index === repeaterItems.length - 1" @click="moveDown(index)">下移</button>
            <button class="danger" @click="removeItem(index)">删除</button>
          </span>
        </div>
        <SchemaField
          v-for="child in node.children || []"
          :key="child.name"
          :node="child"
          :model="item"
          :id-map="idMap"
          :customized="customized"
        />
      </div>
      <button class="add-btn" @click="addItem">＋ 添加一项</button>
    </div>

    <!-- 开关 -->
    <label v-else-if="node.$formkit === 'switch'" class="switch-row">
      <input type="checkbox" :checked="model[node.name] === onValue" @change="toggleSwitch" />
      <span class="switch-text">{{ switchText }}</span>
    </label>

    <!-- 下拉（选项里可能带 help，作为选项下的补充说明） -->
    <select v-else-if="node.$formkit === 'select'" v-model="model[node.name]" class="input">
      <option v-for="option in node.options || []" :key="option.value" :value="option.value">
        {{ option.label }}
      </option>
    </select>

    <!-- 多行文本 -->
    <textarea
      v-else-if="node.$formkit === 'textarea'"
      v-model="model[node.name]"
      class="input textarea"
      :rows="node.rows || 3"
    ></textarea>

    <!-- 其余按 input 处理：text / number / password / datetime-local 等 -->
    <input
      v-else
      v-model="model[node.name]"
      class="input"
      :type="inputType"
      :placeholder="node.placeholder || ''"
    />

    <!--
      帮助文案原样透传 YAML 里的说明。它含有 <b> 等标签（是站长自己看的配置说明），
      因此用 v-html 渲染；内容来自插件自己的 setting.yaml，不是用户输入。
    -->
    <p v-if="node.help" class="field-help" v-html="node.help"></p>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";

/**
 * 渲染 setting.yaml 里的一个 formSchema 节点，并递归渲染其子项。
 *
 * 存在的意义是「字段定义只有一份」：表单结构直接来自 Halo 解析好的 Setting.spec.forms，
 * 本组件只负责把 schema 画成可编辑的表单，不重新声明任何字段。
 * 因此 setting.yaml 新增字段时这个页面自动就有，不会出现两处定义不一致。
 */
const props = defineProps<{
  node: Record<string, any>;
  model: Record<string, any>;
  /** id → name 映射：setting.yaml 的 if 条件写作 $get(payEnabled).value，用的是 id */
  idMap: Record<string, string>;
  /** 该分组里站长保存过的字段名，用于给未自定义的字段打「默认」标记 */
  customized?: Set<string>;
}>();

const emit = defineEmits<{ (e: "check"): void }>();

const isRawBlock = computed(() => Boolean(props.node?.$el));

/**
 * 可重复项：只看 $formkit 声明，不再靠「有没有 children」推断。
 *
 * setting.yaml 里既有 repeater（数组，如 goods / outfits / tasks），
 * 也有 group（对象，如积分规则 rules），两者都带 children。
 * 靠 children 推断会把 group 误当成数组渲染成空列表。
 */
const isRepeater = computed(() => props.node?.$formkit === "repeater");

/** 子分组：显式声明为 group，或带 children 但不是 repeater */
const isGroup = computed(
  () => !isRepeater.value && Boolean(props.node?.children?.length)
);

/** 未单独保存过 = 显示的是运行时默认值 */
const isDefault = computed(() => {
  const name = props.node?.name;
  if (!name || !props.customized) return false;
  return !props.customized.has(name);
});

const onValue = computed(() =>
  props.node?.onValue === undefined ? true : props.node.onValue
);
const offValue = computed(() =>
  props.node?.offValue === undefined ? false : props.node.offValue
);

/** switch 右侧的解释文本：优先取 YAML 里的 enableText/disableText */
const switchText = computed(() => {
  const on = props.model?.[props.node?.name] === onValue.value;
  if (on) {
    return props.node?.enableText || "已开启";
  }
  return props.node?.disableText || "已关闭";
});

const required = computed(() => {
  const validation = props.node?.validation;
  return typeof validation === "string" && validation.includes("required");
});

/**
 * 把 FormKit 的 input 类型映射到原生 input type。
 * datetime-local 是原生支持的，直接用即可（值形如 2030-06-01T12:00）。
 */
const inputType = computed(() => {
  const type = String(props.node?.$formkit || "text");
  if (type === "number") return "number";
  if (type === "password") return "password";
  if (type === "datetime-local") return "datetime-local";
  if (type === "date") return "date";
  if (type === "time") return "time";
  return "text";
});

/**
 * 条件显示：setting.yaml 里用的是 `$get(xxx).value === true/false`。
 *
 * 不认识的表达式一律按「显示」处理：把字段藏起来会让站长根本没法配置它，
 * 而多显示一个字段最多只是页面长一点。
 */
const visible = computed(() => {
  const expr = props.node?.if;
  if (!expr || typeof expr !== "string") {
    return true;
  }
  const matched = /^\$get\(\s*([A-Za-z0-9_]+)\s*\)\.value\s*===\s*(true|false)$/.exec(expr.trim());
  if (!matched) {
    return true;
  }
  const fieldName = props.idMap[matched[1]] || matched[1];
  const actual = props.model?.[fieldName] === true;
  return matched[2] === "true" ? actual : !actual;
});

/** 子分组对应的对象；缺失时由父级 buildModel 保证已补齐 */
const groupModel = computed<Record<string, any>>(() => {
  const value = props.model?.[props.node.name];
  return value && typeof value === "object" && !Array.isArray(value) ? value : {};
});

const repeaterItems = computed<any[]>(() => {
  const value = props.model?.[props.node.name];
  return Array.isArray(value) ? value : [];
});

function ensureArray() {
  if (!Array.isArray(props.model[props.node.name])) {
    props.model[props.node.name] = [];
  }
  return props.model[props.node.name];
}

/** 新增一项：用 children 的 value 作为初始值，避免新建出空壳对象让必填项全空 */
function addItem() {
  const items = ensureArray();
  const item: Record<string, any> = {};
  (props.node.children || []).forEach((child: any) => {
    if (child?.name) {
      item[child.name] = child.value === undefined ? "" : child.value;
    }
  });
  items.push(item);
}

function removeItem(index: number) {
  ensureArray().splice(index, 1);
}

function moveUp(index: number) {
  const items = ensureArray();
  if (index <= 0) return;
  const [moved] = items.splice(index, 1);
  items.splice(index - 1, 0, moved);
}

function moveDown(index: number) {
  const items = ensureArray();
  if (index >= items.length - 1) return;
  const [moved] = items.splice(index, 1);
  items.splice(index + 1, 0, moved);
}

function toggleSwitch(event: Event) {
  const checked = (event.target as HTMLInputElement).checked;
  props.model[props.node.name] = checked ? onValue.value : offValue.value;
  emit("check");
}
</script>

<style scoped>
.raw-block {
  margin: 4px 0 14px;
  padding: 12px 14px;
  border-radius: 10px;
  background: #f7f8fd;
  color: #5b6178;
  font-size: 12.5px;
  line-height: 1.75;
}
.raw-block :deep(code) {
  padding: 1px 5px;
  border-radius: 4px;
  background: #e9ecf7;
  font-family: monospace;
  font-size: 12px;
}

.field {
  margin-bottom: 16px;
}

.field-label {
  display: block;
  margin-bottom: 6px;
  color: #333a52;
  font-size: 13px;
  font-weight: 600;
}

.field-required {
  margin-left: 4px;
  color: #e05555;
}

.input {
  width: 100%;
  box-sizing: border-box;
  padding: 8px 12px;
  border: 1px solid #e3e7f0;
  border-radius: 8px;
  background: #f8f9fd;
  color: #333a52;
  font-size: 13px;
  outline: none;
}

.input:focus {
  border-color: #667eea;
  background: #fff;
}

.textarea {
  resize: vertical;
  font-family: inherit;
  line-height: 1.6;
}

.field-help {
  margin: 6px 0 0;
  color: #8b90a0;
  font-size: 11.5px;
  line-height: 1.75;
}

.field-help :deep(b) {
  color: #5b6178;
}

.field-help :deep(code) {
  padding: 1px 5px;
  border-radius: 4px;
  background: #eef1fb;
  font-family: monospace;
}

.switch-row {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}

.switch-row input {
  width: 16px;
  height: 16px;
  accent-color: #667eea;
  cursor: pointer;
}

.switch-text {
  color: #5b6178;
  font-size: 12.5px;
}

.sub-group {
  margin: 0 0 18px;
  padding: 14px 16px;
  border: 1px solid #eef0f7;
  border-radius: 10px;
  background: #fbfcff;
}

.sub-group-title {
  margin-bottom: 12px;
  padding-bottom: 8px;
  border-bottom: 1px dashed #e6e9f3;
  color: #4a5170;
  font-size: 12.5px;
  font-weight: 700;
}

.field-default {
  margin-left: 6px;
  padding: 1px 7px;
  border-radius: 999px;
  background: #eef1fb;
  color: #8b90a0;
  font-size: 10.5px;
  font-weight: 400;
}

.repeater {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.repeater-item {
  padding: 14px 16px;
  border: 1px solid #eef0f7;
  border-radius: 10px;
  background: #fbfcff;
}

.repeater-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  padding-bottom: 10px;
  border-bottom: 1px dashed #e6e9f3;
}

.repeater-index {
  color: #8b90a0;
  font-size: 12px;
  font-weight: 600;
}

.repeater-actions {
  display: flex;
  gap: 6px;
}

.repeater-actions button,
.add-btn {
  padding: 4px 12px;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  background: #fff;
  color: #5b6178;
  font-size: 12px;
  cursor: pointer;
}

.repeater-actions button:hover:not(:disabled),
.add-btn:hover {
  border-color: #667eea;
  color: #667eea;
}

.repeater-actions button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.repeater-actions .danger:hover {
  border-color: #e05555;
  color: #e05555;
}

.add-btn {
  align-self: flex-start;
  padding: 6px 16px;
  border-style: dashed;
}
</style>
