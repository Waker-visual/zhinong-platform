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
import { confirmAction, promptText } from "../ui/confirm";
import { failure, reportFailure, toast } from "../ui/feedback";
import DataChart from "./DataChart.vue";
import SelectMenu from "../ui/SelectMenu.vue";
import DeviceControl from "./DeviceControl.vue";
import DeviceIntegration from "./DeviceIntegration.vue";
import {
  typeNames,
  sourceNames,
  preferredMetric,
  stateNames,
  timeText,
  num,
  lineOption,
  exportHistory,
} from "./presentation";
const props = defineProps({
  id: String,
  role: String,
  transitionName: { type: String, default: "" },
});
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
  sequence = 0,
  refreshTimer;
const writer = computed(() => ["ADMIN", "OPERATOR"].includes(props.role));
const hoursOptions = [
  { value: 24, label: "近 24 小时" },
  { value: 168, label: "近 7 天" },
  { value: 720, label: "近 30 天" },
];
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
  if (!metric.value) metric.value = preferredMetric(result);
  result.channels.forEach((c) => {
    if (manual[c.metric] === undefined) manual[c.metric] = "";
  });
}
async function controlChanged() {
  await action(async () => {
    await refresh();
    await loadHistory();
    emit("changed");
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
    // 详情可能已滚到下方的告警区，报错用常驻提示条，不放在面板顶部
    if (alive) reportFailure(e, () => action(work));
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
    toast("模拟采集已完成");
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
    toast("监测数据已保存");
  });
}
// 凭据按 4 位分组显示，便于逐段核对；分组靠间距，选中复制得到的仍是原文
const keyGroups = computed(() => key.value.match(/.{1,4}/g) || []);
async function copyKey() {
  try {
    await navigator.clipboard.writeText(key.value);
    toast("凭据已复制到剪贴板");
  } catch {
    failure("浏览器未允许自动复制，请选中凭据文字后手动复制。");
  }
}
async function rotate() {
  if (
    device.value.credentialConfigured &&
    !(await confirmAction({
      title: "重新生成接入凭据",
      message:
        "旧凭据会立即失效，正在用它上报的设备需要换成新凭据后才能继续上报。",
      confirmLabel: "重新生成",
      danger: true,
    }))
  )
    return;
  await action(async () => {
    const result = await api("/assets/" + props.id + "/credentials", "POST");
    key.value = result.key;
    await refresh();
    emit("changed");
    toast("接入凭据已重新生成");
  });
}
async function handle(alert, status) {
  const closing = status !== "ACKNOWLEDGED";
  const text = await promptText({
    title: closing ? "记录处理结果" : "确认告警",
    message: alert.message,
    label: closing ? "处理结果（保存后关闭此告警）" : "确认说明",
    placeholder: closing
      ? "例如：已现场补水，读数恢复"
      : "例如：已通知田间人员前往查看",
    confirmLabel: closing ? "保存并关闭告警" : "确认告警",
  });
  if (!text) return;
  await action(async () => {
    await api("/alerts/" + alert.id, "PATCH", { status, note: text.trim() });
    await refresh();
    emit("changed");
    toast(closing ? "告警已关闭" : "告警已确认");
  });
}
async function reportIssue(alert) {
  const text = await promptText({
    title: "转为田间问题",
    message: alert.message,
    label: "现场说明（最多 200 字）",
    maxLength: 200,
    placeholder: "例如：请安排巡田核对探头位置和现场水位",
    confirmLabel: "创建田间问题",
  });
  if (!text) return;
  await action(async () => {
    await api(`/field-work/alerts/${alert.id}/issue`, "POST", {
      severity: "NORMAL",
      note: text.trim(),
    });
    await refresh();
    emit("changed");
    toast("已转为田间问题，可在今日农场派工和复核");
  });
}
watch([metric, hours], () => {
  history.value = null;
  if (metric.value) loadHistory();
});
onMounted(() => {
  action(refresh);
  refreshTimer = setInterval(async () => {
    if (busy.value || document.hidden) return;
    try {
      await refresh();
      if (alive && metric.value) await loadHistory();
    } catch (e) {
      if (alive) error.value = e.message;
    }
  }, 15000);
});
onBeforeUnmount(() => {
  alive = false;
  clearInterval(refreshTimer);
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
      :style="
        props.transitionName
          ? { viewTransitionName: props.transitionName }
          : null
      "
    >
      <div class="section-title">
        <div>
          <p class="eyebrow">设备详情</p>
          <h2>{{ device?.name || "正在加载设备…" }}</h2>
        </div>
        <button
          class="close-button"
          aria-label="关闭设备详情"
          @click="emit('close')"
        >
          ×
        </button>
      </div>
      <div class="device-detail-body">
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
            <dt>最近接收</dt>
            <dd>{{ timeText(device.lastReceivedAt) }}</dd>
          </div>
          <div>
            <dt>最近采样</dt>
            <dd>{{ timeText(device.lastSampledAt) }}</dd>
          </div>
          <div>
            <dt>预期上报周期</dt>
            <dd>{{ device.intervalSeconds }} 秒</dd>
          </div>
          <div>
            <dt>指标完整性</dt>
            <dd>
              {{ device.freshChannelCount }} /
              {{ device.channels.length }} 项数据新鲜，{{
                device.missingChannelCount
              }}
              项从未上报
            </dd>
          </div>
          <div>
            <dt>
              {{
                device.locationMode === "WGS84"
                  ? "WGS84 纬度 / 经度"
                  : "平面点位"
              }}
            </dt>
            <dd v-if="device.locationMode === 'WGS84'">
              {{ num(device.latitude, 6) }} / {{ num(device.longitude, 6) }}
            </dd>
            <dd v-else>
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
            ><small
              >{{ c.latest ? sourceNames[c.latest.source] : "尚无监测数据" }} ·
              {{ stateNames[c.freshness] }}</small
            ><small>{{ timeText(c.latest?.time) }}</small>
          </button>
        </div>
        <DeviceControl
          v-if="['GATE', 'PUMP'].includes(device.deviceType)"
          :key="device.id"
          :device="device"
          :role="role"
          @changed="controlChanged"
        />
        <section class="detail-trend">
          <div class="section-title">
            <h3>监测趋势</h3>
            <div class="inline-controls">
              <SelectMenu
                v-model="hours"
                :options="hoursOptions"
                aria-label="历史时间范围"
              /><button
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
            v-validate
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
            <strong>新凭据（仅本次显示）</strong
            ><code class="credential-key" :aria-label="key"
              ><span v-for="(group, i) in keyGroups" :key="i">{{
                group
              }}</span></code
            ><button class="primary" @click="copyKey">复制凭据</button
            ><button @click="key = ''">隐藏凭据</button>
          </div>
          <pre>{{ example }}</pre>
        </section>
        <DeviceIntegration
          v-if="device.protocol === 'HTTP_PUSH'"
          :key="device.id"
          :device="device"
          :role="role"
        />
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
              <p v-if="alert.issueId">
                已关联田间问题：{{
                  {
                    OPEN: "待安排",
                    ASSIGNED: "已派工，等待复核",
                    RESOLVED: "已复核关闭",
                  }[alert.issueStatus]
                }}
              </p>
            </div>
            <div v-if="writer && alert.status !== 'RESOLVED'">
              <button
                v-if="!alert.issueId && device.plotId"
                class="outline"
                @click="reportIssue(alert)"
                :disabled="busy"
              >
                转为田间问题
              </button>
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
      </div>
    </section>
  </div>
</template>
