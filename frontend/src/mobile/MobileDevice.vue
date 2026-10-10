<script setup>
import { computed, onBeforeUnmount, ref, watch } from "vue";
import {
  canControl,
  freshness,
  number,
  requestId,
  statuses,
  time,
  typeNames,
} from "./client.mjs";
const props = defineProps({
  device: Object,
  client: Object,
  role: String,
  revision: Number,
  locked: Boolean,
});
const emit = defineEmits(["close", "confirm"]);
const detail = ref(null),
  history = ref(null),
  metric = ref(""),
  actions = ref([]),
  commands = ref([]),
  error = ref(""),
  loading = ref(false);
const selected = ref(""),
  value = ref(0),
  note = ref("手机端人工确认操作");
const showAll = ref(false);
const visibleChannels = computed(() => {
  const priority = ["PUMP_RUNNING", "GATE_OPENING", "REMOTE_ENABLED", "FAULT"];
  const channels = [...(detail.value?.channels || [])].sort(
    (a, b) =>
      (priority.includes(a.metric) ? priority.indexOf(a.metric) : 99) -
      (priority.includes(b.metric) ? priority.indexOf(b.metric) : 99),
  );
  return showAll.value ? channels : channels.slice(0, 4);
});
const action = computed(() =>
  actions.value.find((a) => a.code === selected.value),
);
let generation = 0,
  historyVersion = 0;
const route = (suffix) => `/assets/${props.device.id}${suffix}`;
async function load() {
  const version = ++generation;
  loading.value = true;
  try {
    const [asset, ledger] = await Promise.all([
      props.client.request(route("")),
      props.client.request(route("/commands")),
    ]);
    if (version !== generation) return;
    detail.value = asset;
    actions.value = ledger.actions;
    commands.value = ledger.commands;
    error.value = "";
    if (
      !selected.value ||
      !actions.value.some((a) => a.code === selected.value)
    )
      selected.value = actions.value[0]?.code || "";
    if (!metric.value) metric.value = asset.channels[0]?.metric || "";
  } catch (e) {
    if (version === generation && !e.stale) error.value = e.message;
  } finally {
    if (version === generation) loading.value = false;
  }
}
async function loadHistory() {
  const v = ++historyVersion;
  history.value = null;
  if (!metric.value) return;
  try {
    const data = await props.client.request(
      route(`/history?metric=${encodeURIComponent(metric.value)}&hours=24`),
    );
    if (v === historyVersion) history.value = data;
  } catch (e) {
    if (v === historyVersion && !e.stale) error.value = e.message;
  }
}
watch(() => [props.device.id, props.revision], load, { immediate: true });
watch([metric, () => props.revision], loadHistory);
watch(selected, () => {
  value.value = action.value?.min ?? 0;
});
onBeforeUnmount(() => {
  generation++;
  historyVersion++;
});
function confirm() {
  if (!action.value || !canControl(detail.value, action.value, props.role))
    return;
  emit("confirm", {
    title: action.value.name,
    description: `${detail.value.name} · ${detail.value.plotName || "公共区域"}${action.value.min == null ? "" : ` · ${value.value} ${action.value.unit}`}`,
    source:
      detail.value.protocol === "SIMULATED"
        ? "模拟设备：只更新演示数据，不操作实体设备。"
        : "设备指令：接收请求不代表设备已动作，请核对回执与最新反馈。",
    path: route("/commands"),
    body: {
      requestId: requestId(),
      action: selected.value,
      value: action.value.min == null ? null : Number(value.value),
      note: note.value.trim(),
    },
    lookup: route("/commands"),
    collection: "commands",
    kind: "command",
  });
}
const trend = computed(() => {
  const points = (history.value?.points || []).filter(
    (p) => p.value !== null && Number.isFinite(Number(p.value)),
  );
  if (points.length < 2) return "";
  const values = points.map((p) => Number(p.value)),
    lo = Math.min(...values),
    hi = Math.max(...values);
  const times = points.map((p) => new Date(p.time).getTime()),
    start = Math.min(...times),
    end = Math.max(...times);
  return values
    .map(
      (v, i) =>
        `${10 + (280 * (times[i] - start)) / (end - start || 1)},${85 - ((v - lo) / (hi - lo || 1)) * 65}`,
    )
    .join(" ");
});
</script>
<template>
  <section class="mobile-detail" role="region" aria-label="设备详情">
    <button class="back" @click="emit('close')">← 返回设备列表</button>
    <header class="section-heading">
      <div>
        <p class="eyebrow">
          {{ typeNames[device.deviceType] || device.deviceType }}
        </p>
        <h2>{{ device.name }}</h2>
      </div>
      <span class="tag">{{ freshness(detail || device) }}</span>
    </header>
    <p class="error" role="alert" v-if="error">
      {{ error }} <button @click="load">重新读取</button>
    </p>
    <p v-if="!detail">正在读取设备信息…</p>
    <template v-else>
      <article class="card">
        <dl class="facts">
          <dt>位置</dt>
          <dd>{{ detail.farmName }} · {{ detail.plotName || "公共区域" }}</dd>
          <dt>型号 / 编码</dt>
          <dd>{{ detail.model || "未登记" }} / {{ detail.code }}</dd>
          <dt>数据来源</dt>
          <dd>
            {{ detail.protocol === "SIMULATED" ? "模拟上报" : detail.protocol }}
          </dd>
          <dt>最近上报</dt>
          <dd>{{ time(detail.lastReceivedAt) }}</dd>
        </dl>
      </article>
      <div class="metric-grid">
        <article
          class="card metric"
          v-for="c in visibleChannels"
          :key="c.metric"
        >
          <span>{{ c.name }}</span
          ><strong
            >{{ number(c.latest?.value) }} <small>{{ c.unit }}</small></strong
          ><small
            >{{ time(c.latest?.time) }} ·
            {{ c.freshness === "FRESH" ? "近期数据" : "非近期数据" }}</small
          >
        </article>
      </div>
      <button
        v-if="detail.channels.length > 4"
        class="full"
        @click="showAll = !showAll"
      >
        {{
          showAll ? "收起附加指标" : `查看全部 ${detail.channels.length} 项指标`
        }}
      </button>
      <article class="card" v-if="detail.channels.length">
        <h3>24 小时监测趋势</h3>
        <label
          >监测指标<select v-model="metric" aria-label="监测指标">
            <option
              v-for="c in detail.channels"
              :key="c.metric"
              :value="c.metric"
            >
              {{ c.name }} / {{ c.unit }}
            </option>
          </select></label
        ><svg
          class="trend"
          viewBox="0 0 300 100"
          v-if="trend"
          role="img"
          aria-label="24 小时指标趋势"
        >
          <polyline :points="trend" />
        </svg>
        <p v-else class="muted">
          {{ history ? "暂无足够的历史数据" : "正在读取…" }}
        </p>
        <p v-if="history" class="muted">
          {{ history.points.length }} 个数据点 ·
          {{
            history.sources
              ?.map(
                (s) =>
                  `${s.source === "SIMULATED" ? "模拟数据" : s.source} ${s.count} 条`,
              )
              .join("、") || "来源未标注"
          }}
        </p>
        <details v-if="history?.points.length">
          <summary>查看最近 10 条数据</summary>
          <dl
            class="history-row"
            v-for="p in history.points.slice(-10).reverse()"
            :key="p.time"
          >
            <dt>{{ time(p.time) }}</dt>
            <dd>
              {{ number(p.value) }}
              {{ detail.channels.find((c) => c.metric === metric)?.unit }}
            </dd>
          </dl>
        </details>
      </article>
      <article class="card" v-if="actions.length">
        <h3>设备指令</h3>
        <p class="muted">
          {{
            role === "VIEWER"
              ? "当前为只读账号。"
              : "选择操作后需要人工确认，操作与电脑端共用记录。"
          }}
        </p>
        <form @submit.prevent="confirm">
          <label
            >操作<select
              v-model="selected"
              :disabled="locked"
              aria-label="操作"
            >
              <option v-for="a in actions" :value="a.code" :key="a.code">
                {{ a.name }}{{ a.role === "ADMIN" ? "（管理员）" : "" }}
              </option>
            </select></label
          ><label v-if="action?.min != null"
            >目标值 / {{ action.unit
            }}<input
              type="number"
              v-model="value"
              :min="action.min"
              :max="action.max"
              step="1"
              required
              :disabled="locked" /></label
          ><label
            >操作说明<input
              v-model="note"
              required
              maxlength="300"
              :disabled="locked" /></label
          ><button
            class="primary full"
            :disabled="
              locked ||
              loading ||
              !action ||
              !canControl(detail, action, role) ||
              !note.trim()
            "
          >
            确认操作信息
          </button>
          <p class="muted" v-if="action && !canControl(detail, action, role)">
            当前权限、设备启用状态或反馈新鲜度不满足该操作；停止操作按设备能力单独判断。
          </p>
        </form>
      </article>
      <article class="card" v-else>
        <h3>监测设备</h3>
        <p class="muted">
          该设备没有可下发的通用控制指令。农机与分区灌溉请使用“作业”入口。
        </p>
      </article>
      <article class="card">
        <h3>指令记录 <small>与电脑端共享</small></h3>
        <p v-if="!commands.length" class="muted">暂无指令记录</p>
        <div
          class="record"
          v-for="c in commands.slice(0, 20)"
          :key="c.id"
          :data-request-id="c.requestId"
        >
          <strong
            >{{ actions.find((a) => a.code === c.action)?.name || c.action }}
            <span class="tag">{{
              statuses[c.status] || c.status
            }}</span></strong
          >
          <p v-if="c.value != null">
            目标：{{ c.value }}
            {{ actions.find((a) => a.code === c.action)?.unit }}
          </p>
          <p>{{ c.resultNote || "等待服务更新执行结果" }}</p>
          <p class="muted">{{ c.note }}</p>
          <small>{{ c.actor }} · {{ time(c.createdAt) }}</small
          ><small class="request-id">{{ c.requestId }}</small>
        </div>
      </article>
    </template>
  </section>
</template>
