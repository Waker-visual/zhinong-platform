<script setup>
import {
  computed,
  onMounted,
  onBeforeUnmount,
  ref,
  reactive,
  watch,
} from "vue";
import { api } from "../api";
import DataChart from "./DataChart.vue";
import {
  typeNames,
  sourceNames,
  stateNames,
  timeText,
  num,
  lineOption,
  exportHistory,
} from "./presentation";
const props = defineProps({ id: String, role: String });
const emit = defineEmits(["close", "edit", "changed"]);
const device = ref(null),
  error = ref(""),
  busy = ref(false),
  history = ref(null),
  metric = ref(""),
  hours = ref(24),
  key = ref(""),
  note = ref(""),
  manual = reactive({});
let alive = true,
  sequence = 0;
const writer = computed(() => ["ADMIN", "OPERATOR"].includes(props.role));
const chart = computed(() =>
  lineOption(history.value?.points || [], history.value?.metric?.unit || ""),
);
const example = computed(() =>
  JSON.stringify(
    {
      messageId: "reading-001",
      measuredAt: new Date().toISOString(),
      readings:
        device.value?.channels.map((c) => ({
          metric: c.metric,
          value: Math.max(c.min, Math.min(c.max, c.latest?.value ?? 0)),
        })) || [],
    },
    null,
    2,
  ),
);
async function refresh() {
  const result = await api("/assets/" + props.id);
  if (!alive) return;
  device.value = result;
  if (!metric.value) metric.value = result.channels[0]?.metric || "";
  result.channels.forEach((c) => {
    if (manual[c.metric] === undefined) manual[c.metric] = "";
  });
}
async function loadHistory() {
  const current = ++sequence;
  try {
    const result = await api(
      `/assets/${props.id}/history?metric=${encodeURIComponent(metric.value)}&hours=${hours.value}`,
    );
    if (alive && current === sequence) history.value = result;
  } catch (e) {
    if (alive) error.value = e.message;
  }
}
async function action(work) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    await work();
  } catch (e) {
    if (alive) error.value = e.message;
  } finally {
    if (alive) busy.value = false;
  }
}
async function sample() {
  await action(async () => {
    await api("/assets/" + props.id + "/collect", "POST");
    await refresh();
    await loadHistory();
    emit("changed");
  });
}
async function saveManual() {
  await action(async () => {
    await api("/assets/" + props.id + "/readings", "POST", {
      measuredAt: new Date().toISOString(),
      readings: device.value.channels
        .filter((c) => manual[c.metric] !== "")
        .map((c) => ({ metric: c.metric, value: Number(manual[c.metric]) })),
    });
    await refresh();
    await loadHistory();
    emit("changed");
    note.value = "监测数据已保存";
  });
}
async function rotate() {
  if (
    device.value.credentialConfigured &&
    !window.confirm("重新生成后，旧凭据立即失效。确定继续？")
  )
    return;
  await action(async () => {
    const result = await api("/assets/" + props.id + "/credentials", "POST");
    key.value = result.key;
    await refresh();
    emit("changed");
  });
}
async function handle(alert, status) {
  const text = window.prompt(
    status === "ACKNOWLEDGED" ? "填写确认说明" : "填写处理结果（将关闭此告警）",
  );
  if (!text?.trim()) return;
  await action(async () => {
    await api("/alerts/" + alert.id, "PATCH", { status, note: text.trim() });
    await refresh();
    emit("changed");
  });
}
watch([metric, hours], () => {
  history.value = null;
  if (metric.value) loadHistory();
});
onMounted(() => action(refresh));
onBeforeUnmount(() => {
  alive = false;
  ++sequence;
  key.value = "";
});
</script>
<template>
  <div class="modal-backdrop workspace-overlay" @click.self="emit('close')">
    <section
      class="modal device-detail"
      role="dialog"
      aria-modal="true"
      aria-label="设备详情"
    >
      <div class="section-title">
        <div>
          <p class="eyebrow">设备详情</p>
          <h2>{{ device?.name || "正在加载设备…" }}</h2>
        </div>
        <button aria-label="关闭设备详情" @click="emit('close')">×</button>
      </div>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
      <template v-if="device">
        <div class="detail-meta">
          <span class="status-chip" :class="device.freshness.toLowerCase()">{{
            stateNames[device.freshness]
          }}</span
          ><span>{{ typeNames[device.deviceType] }}</span
          ><span>{{ sourceNames[device.protocol] }}</span
          ><span>{{ device.code }}</span
          ><button
            v-if="role === 'ADMIN'"
            class="outline"
            @click="emit('edit', device)"
          >
            编辑设备
          </button>
        </div>
        <dl class="detail-facts">
          <div>
            <dt>所属农场 / 地块</dt>
            <dd>{{ device.farmName }} / {{ device.plotName || "公共区域" }}</dd>
          </div>
          <div>
            <dt>最近上报</dt>
            <dd>{{ timeText(device.lastReceivedAt) }}</dd>
          </div>
          <div>
            <dt>预期上报周期</dt>
            <dd>{{ device.intervalSeconds }} 秒</dd>
          </div>
          <div>
            <dt>平面点位</dt>
            <dd>
              {{ device.planX ?? "未设置" }} / {{ device.planY ?? "未设置" }}
            </dd>
          </div>
          <div>
            <dt>型号 / 安装说明</dt>
            <dd>
              {{ device.model || "未填写" }} · {{ device.notes || "未填写" }}
            </dd>
          </div>
        </dl>
        <div class="metric-cards">
          <button
            v-for="c in device.channels"
            :key="c.metric"
            :class="{ selected: metric === c.metric }"
            @click="metric = c.metric"
          >
            <span>{{ c.name }}</span
            ><strong
              >{{ c.latest ? num(c.latest.value, 2) : "—"
              }}<small>{{ c.unit }}</small></strong
            ><small>{{
              c.latest ? sourceNames[c.latest.source] : "尚无监测数据"
            }}</small>
          </button>
        </div>
        <section class="detail-trend">
          <div class="section-title">
            <h3>监测趋势</h3>
            <div class="inline-controls">
              <select v-model.number="hours" aria-label="历史时间范围">
                <option :value="24">近 24 小时</option>
                <option :value="168">近 7 天</option>
                <option :value="720">近 30 天</option></select
              ><button
                class="outline"
                @click="history && exportHistory(history)"
                :disabled="!history?.points.length"
              >
                导出 CSV
              </button>
            </div>
          </div>
          <DataChart
            v-if="history?.points.length"
            :option="chart"
            label="可缩放监测历史趋势图"
          />
          <div v-else class="empty">
            {{
              history
                ? "当前范围内没有记录，可切换时间范围或执行采集。"
                : "正在加载历史数据…"
            }}
          </div>
          <p class="muted" v-if="history">
            {{
              history.aggregation === "HOUR" ? "按小时" : "按分钟"
            }}汇总真实保存的记录；来源：{{
              history.sources
                .map((s) => `${sourceNames[s.source]} ${s.count} 条`)
                .join("、") || "无"
            }}。滚轮或底部滑块可缩放时间范围。
          </p>
        </section>
        <div
          v-if="writer && device.lifecycle === 'ACTIVE'"
          class="capture-panel"
        >
          <button
            v-if="device.protocol === 'SIMULATED'"
            class="primary"
            @click="sample"
            :disabled="busy"
          >
            立即模拟采集全部指标
          </button>
          <form
            v-if="device.protocol === 'MANUAL'"
            @submit.prevent="saveManual"
          >
            <h3>录入监测数据</h3>
            <div class="form-grid">
              <label v-for="c in device.channels" :key="c.metric"
                >{{ c.name }}（{{ c.unit }}）<input
                  type="number"
                  :min="c.min"
                  :max="c.max"
                  step="0.001"
                  v-model="manual[c.metric]"
              /></label>
            </div>
            <button
              class="primary"
              :disabled="busy || !Object.values(manual).some((v) => v !== '')"
            >
              保存监测记录
            </button>
            <p v-if="note" role="status">{{ note }}</p>
          </form>
        </div>
        <section
          v-if="device.protocol === 'HTTP_PUSH'"
          class="integration-panel"
        >
          <div class="section-title">
            <h3>HTTP 数据接入</h3>
            <button
              v-if="role === 'ADMIN'"
              class="outline"
              @click="rotate"
              :disabled="busy"
            >
              {{
                device.credentialConfigured
                  ? "重新生成接入凭据"
                  : "生成接入凭据"
              }}
            </button>
          </div>
          <p class="muted">
            向本系统 POST /api/ingest/telemetry，使用 X-Device-Key
            请求头。凭据绑定当前设备和租户；相同 messageId
            的相同报文可安全重试。
          </p>
          <div v-if="key" class="credential-box">
            <strong>新凭据（仅本次显示）</strong><code>{{ key }}</code
            ><button @click="key = ''">隐藏凭据</button>
          </div>
          <pre>{{ example }}</pre>
        </section>
        <section class="detail-alerts">
          <h3>告警与处理记录</h3>
          <div
            v-for="alert in device.alerts"
            :key="alert.id"
            class="alert-item"
          >
            <div>
              <strong>{{ alert.message }}</strong
              ><small
                >{{ stateNames[alert.status] }} ·
                {{ timeText(alert.openedAt) }}</small
              >
              <p v-if="alert.handleNote">
                {{ alert.handledBy }}：{{ alert.handleNote }}
              </p>
            </div>
            <div v-if="writer && alert.status !== 'RESOLVED'">
              <button
                v-if="alert.status === 'OPEN'"
                class="outline"
                @click="handle(alert, 'ACKNOWLEDGED')"
              >
                确认</button
              ><button class="outline" @click="handle(alert, 'RESOLVED')">
                记录处理
              </button>
            </div>
          </div>
          <p v-if="!device.alerts.length" class="empty">暂无阈值告警记录</p>
        </section>
      </template>
    </section>
  </div>
</template>
