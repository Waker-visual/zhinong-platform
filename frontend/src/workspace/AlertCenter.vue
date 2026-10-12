<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import { api } from "../api";
import AppIcon from "../ui/AppIcon.vue";
import ModalDialog from "../ui/ModalDialog.vue";
import { promptText } from "../ui/confirm";
import { toast, important } from "../ui/feedback";
import "./alerts.css";

const props = defineProps({
  farmId: String,
  farmName: String,
  role: String,
  revision: Number,
});
const emit = defineEmits(["navigate", "ask", "changed"]);
const alerts = ref([]),
  open = ref(false),
  loading = ref(false),
  busy = ref(""),
  error = ref(""),
  updatedAt = ref(null);
const filter = ref("ALL");
const writer = computed(() => ["ADMIN", "OPERATOR"].includes(props.role));
const unconfirmed = computed(
  () => alerts.value.filter((a) => a.status === "OPEN").length,
);
const offline = computed(
  () => alerts.value.filter((a) => a.kind === "OFFLINE").length,
);
const visible = computed(() =>
  alerts.value
    .filter((a) => filter.value === "ALL" || a.kind === filter.value)
    .sort(
      (a, b) =>
        Number(b.severity === "HIGH") - Number(a.severity === "HIGH") ||
        Number(b.status === "OPEN") - Number(a.status === "OPEN") ||
        String(b.openedAt).localeCompare(String(a.openedAt)),
    ),
);
const tabs = [
  ["ALL", "全部"],
  ["OFFLINE", "设备离线"],
  ["DATA_STALE", "数据过期"],
  ["THRESHOLD", "数值异常"],
];
const kindName = (a) =>
  ({ OFFLINE: "设备离线", DATA_STALE: "采样过期", THRESHOLD: "数值异常" })[
    a.kind
  ] || "设备预警";
const time = (value) =>
  value
    ? new Date(value).toLocaleString("zh-CN", { hour12: false })
    : "尚未收到";
let alive = true,
  generation = 0,
  pollTimer;
const seen = new Set(),
  initializedScopes = new Set();

async function load({ reset = false, notify = false } = {}) {
  if (props.role === "PLATFORM_ADMIN") return;
  const g = ++generation,
    farm = props.farmId || "";
  if (reset) {
    alerts.value = [];
    error.value = "";
    updatedAt.value = null;
    filter.value = "ALL";
  }
  loading.value = true;
  try {
    const rows = await api(
      "/alerts" + (farm ? "?farmId=" + encodeURIComponent(farm) : ""),
    );
    if (!alive || g !== generation || farm !== (props.farmId || "")) return;
    const fresh = rows.filter((a) => a.status === "OPEN" && !seen.has(a.id));
    rows.forEach((a) => seen.add(a.id));
    alerts.value = rows;
    error.value = "";
    updatedAt.value = new Date().toISOString();
    if (notify && initializedScopes.has(farm) && fresh.length && !open.value)
      important(
        `新增 ${fresh.length} 项设备预警，点击顶部“提醒”查看处理建议。`,
        6500,
      );
    initializedScopes.add(farm);
  } catch (e) {
    if (alive && g === generation) error.value = e.message;
  } finally {
    if (alive && g === generation) loading.value = false;
  }
}
function show() {
  open.value = true;
  load();
}
function onVisible() {
  if (!document.hidden && !busy.value) load({ notify: true });
}
function jump(alert) {
  open.value = false;
  emit("navigate", {
    page: "devices",
    farmId: alert.farmId,
    deviceId: alert.deviceId,
  });
}
function ask(alert) {
  open.value = false;
  emit("ask", {
    farmId: alert.farmId,
    question:
      `请帮我排查 ${alert.deviceName} 的${kindName(alert)}。告警：${alert.message}。依据：${alert.evidence || "请读取设备最新数据"}。当前排查建议：${alert.recommendation || "待分析"}。告警时间：${alert.openedAt}。请先核对最新状态，给出可能原因、现场检查顺序、恢复判据和应避免的误操作；明确缺失的信息。`.slice(
        0,
        2000,
      ),
  });
}
async function handle(alert, toIssue = false) {
  if (busy.value || !writer.value) return;
  const farm = props.farmId;
  const note = await promptText({
    title: toIssue ? "转为田间问题" : "确认设备预警",
    message: `${alert.deviceName}：${alert.message}`,
    label: toIssue ? "现场说明（最多 200 字）" : "确认说明（最多 300 字）",
    placeholder: toIssue
      ? "记录需要巡查的位置、现象和处理要求"
      : "记录已通知的人员与下一步检查安排",
    maxLength: toIssue ? 200 : 300,
    confirmLabel: toIssue ? "创建田间问题" : "确认预警",
  });
  if (!note || !alive || farm !== props.farmId) return;
  busy.value = alert.id;
  error.value = "";
  try {
    if (toIssue)
      await api(
        `/field-work/alerts/${encodeURIComponent(alert.id)}/issue`,
        "POST",
        { severity: alert.severity === "HIGH" ? "HIGH" : "NORMAL", note },
      );
    else
      await api("/alerts/" + encodeURIComponent(alert.id), "PATCH", {
        status: "ACKNOWLEDGED",
        note,
      });
    if (!alive || farm !== props.farmId) return;
    toast(
      toIssue
        ? "已转为田间问题，可在今日农场派工和复核"
        : "预警已确认，恢复情况仍会继续跟踪",
    );
    await load();
    emit("changed");
  } catch (e) {
    if (alive && farm === props.farmId) error.value = e.message;
  } finally {
    if (alive) busy.value = "";
  }
}
watch(
  () => props.farmId,
  () => load({ reset: true }),
  { immediate: true },
);
watch(
  () => props.revision,
  () => load(),
);
onMounted(() => {
  document.addEventListener("visibilitychange", onVisible);
  pollTimer = setInterval(() => {
    if (!document.hidden && !loading.value && !busy.value)
      load({ notify: true });
  }, 45000);
});
onUnmounted(() => {
  alive = false;
  generation++;
  clearInterval(pollTimer);
  document.removeEventListener("visibilitychange", onVisible);
});
defineExpose({ open: show, refresh: load });
</script>

<template>
  <button
    type="button"
    class="alert-center-trigger"
    :class="{ 'has-alerts': unconfirmed > 0, 'has-error': error }"
    :aria-label="`设备提醒，${error ? '更新失败，' : ''}${alerts.length} 项未关闭，${unconfirmed} 项待确认`"
    aria-haspopup="dialog"
    :aria-expanded="open"
    @click="show"
  >
    <AppIcon name="bell" /><span>提醒</span
    ><b v-if="alerts.length" aria-hidden="true">{{
      alerts.length >= 200 ? "200+" : alerts.length
    }}</b
    ><span v-else-if="error" class="alert-sync-error" aria-hidden="true"
      >!</span
    >
  </button>
  <ModalDialog
    v-if="open"
    class="alert-center-dialog"
    label="设备预警与处理建议"
    :locked="!!busy"
    @close="open = false"
  >
    <div class="alert-center-heading">
      <div>
        <h2>设备预警与处理建议</h2>
        <p>
          {{ farmId ? farmName || "当前农场" : "全部农场" }} ·
          {{ unconfirmed }} 项待确认<span v-if="offline">
            · {{ offline }} 台设备离线</span
          >
        </p>
      </div>
      <button
        class="close-button"
        aria-label="关闭提醒"
        :disabled="!!busy"
        @click="open = false"
      >
        ×
      </button>
    </div>
    <div class="alert-center-toolbar">
      <nav aria-label="按预警类型筛选">
        <button
          v-for="tab in tabs"
          :key="tab[0]"
          :aria-pressed="filter === tab[0]"
          @click="filter = tab[0]"
        >
          {{ tab[1] }}
        </button>
      </nav>
      <button
        class="text-button with-icon"
        :disabled="loading || !!busy"
        @click="load()"
      >
        <AppIcon name="refresh" />{{ loading ? "更新中" : "更新" }}
      </button>
    </div>
    <p v-if="error" class="alert-center-error" role="alert">
      {{ error }}。{{
        updatedAt
          ? "当前保留上次读取的预警，请更新后再判断状态。"
          : "当前无法确认设备状态，请重试。"
      }}
    </p>
    <p v-if="loading && !updatedAt" class="alert-center-empty" role="status">
      正在核对设备状态…
    </p>
    <p v-else-if="!visible.length && !error" class="alert-center-empty">
      {{ alerts.length ? "没有此类未关闭预警。" : "当前没有未关闭的设备预警。"
      }}<small
        >离线与过期检测适用于已启用的自动上报设备；数值异常按已配置的指标阈值判断。请在设备台账核对监测范围。</small
      >
    </p>
    <div class="alert-center-list">
      <article
        v-for="alert in visible"
        :key="alert.id"
        class="alert-center-item"
      >
        <div class="alert-item-heading">
          <div>
            <span
              class="alert-kind"
              :class="{ high: alert.severity === 'HIGH' }"
              >{{ kindName(alert) }}</span
            >
            <h3>{{ alert.deviceName }}</h3>
          </div>
          <span class="alert-status">{{
            alert.status === "OPEN" ? "待确认" : "已确认，待恢复"
          }}</span>
        </div>
        <p class="alert-location">
          {{ alert.farmName
          }}{{ alert.plotName ? " · " + alert.plotName : "" }} ·
          {{ time(alert.openedAt) }}
        </p>
        <p class="alert-description">{{ alert.message }}</p>
        <div v-if="alert.recommendation" class="alert-guidance">
          <strong>建议先这样排查</strong>
          <p>{{ alert.recommendation }}</p>
        </div>
        <details v-if="alert.evidence" class="alert-evidence">
          <summary>判定依据与最近上报</summary>
          <p>{{ alert.evidence }}</p>
          <small
            >最近收到上报：{{ time(alert.lastReceivedAt) }} ·
            自动规则检测</small
          >
        </details>
        <p v-if="alert.handleNote" class="alert-handle-note">
          {{ alert.handledBy || "处理记录" }}：{{ alert.handleNote }}
        </p>
        <p v-if="alert.issueId" class="alert-handle-note">
          已关联田间问题：{{
            {
              OPEN: "待安排",
              ASSIGNED: "已派工，待复核",
              RESOLVED: "已复核关闭",
            }[alert.issueStatus] || "处理中"
          }}
        </p>
        <div class="alert-item-actions">
          <button class="outline" @click="jump(alert)" :disabled="!!busy">
            查看设备</button
          ><button class="outline" @click="ask(alert)" :disabled="!!busy">
            带入 AI 排查</button
          ><button
            v-if="writer && alert.status === 'OPEN'"
            class="text-button"
            :disabled="!!busy"
            @click="handle(alert)"
          >
            {{ busy === alert.id ? "保存中…" : "确认预警" }}</button
          ><button
            v-if="writer && !alert.issueId && alert.plotId"
            class="text-button"
            :disabled="!!busy"
            @click="handle(alert, true)"
          >
            转为田间问题</button
          ><button
            v-if="alert.issueId"
            class="text-button"
            :disabled="!!busy"
            @click="
              open = false;
              emit('navigate', { page: 'daily', farmId: alert.farmId });
            "
          >
            跟进处理
          </button>
        </div>
      </article>
    </div>
    <footer class="alert-center-footer">
      <p v-if="alerts.length >= 200">
        当前显示最近 200 项未关闭预警，请切换到具体农场缩小范围。
      </p>
      <p>
        每 45
        秒检查一次，页面隐藏时暂停。确认预警只表示已接手，处理与恢复仍需继续跟踪。
      </p>
      <small v-if="updatedAt">上次读取 {{ time(updatedAt) }}</small>
    </footer>
  </ModalDialog>
</template>
