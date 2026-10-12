<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { api } from "../api";
import AppIcon from "../ui/AppIcon.vue";
import DataChart from "../workspace/DataChart.vue";
import AssistantText from "../ai/AssistantText.vue";
import SatelliteFarmMap from "./SatelliteFarmMap.vue";
import { satelliteGeometry } from "./satellite-model.js";
import { diagnosticLabels } from "../ai/diagnostics";
import { stateNames } from "../workspace/presentation";
import {
  canReadFarm,
  deviceCounts,
  hasDeviceAlert,
  metricSummary,
  moistureOption,
  moisturePoints,
  numberText,
  pendingTasks,
  productionTotal,
  timeText,
} from "./screen-model.js";
import "./display.css";

const props = defineProps({
  farmId: String,
  farms: { type: Array, default: () => [] },
  role: String,
});
const emit = defineEmits([
  "close",
  "update:farmId",
  "navigate",
  "ask",
  "alerts",
]);
const dialog = ref(null),
  screen = ref(null),
  view = ref("overview"),
  selectedPlot = ref("");
const workspace = ref(null),
  operations = ref(null),
  analysis = ref(null),
  briefing = ref(null),
  geo = ref(null),
  spatial = ref(null);
const loading = ref(false),
  failures = ref([]),
  readAt = ref(null),
  clock = ref(Date.now()),
  fullscreen = ref(false),
  fullscreenError = ref(""),
  reducedMotion = ref(false);
let alive = true,
  sequence = 0,
  controller,
  poll,
  clockTimer,
  motionPreference,
  opener;
const accessible = computed(() =>
  canReadFarm(props.role, props.farmId, props.farms),
);
const availableFarms = computed(() =>
  props.farms.filter((farm) => !farm.archived),
);
const farm = computed(
  () =>
    workspace.value?.farm ||
    availableFarms.value.find((farm) => farm.id === props.farmId),
);
const map = computed(() => {
  const geometry = satelliteGeometry(
    workspace.value,
    geo.value,
    spatial.value?.parcels,
  );
  return {
    ...geometry,
    devices: geometry.devices.map((device) => ({
      ...device,
      alertCount: hasDeviceAlert(device, alerts.value) ? 1 : 0,
    })),
  };
});
const plot = computed(() =>
  workspace.value?.plots?.find((item) => item.id === selectedPlot.value),
);
const devices = computed(() =>
  deviceCounts(workspace.value?.devices, clock.value),
);
const work = computed(() => pendingTasks(workspace.value?.tasks));
const alerts = computed(() =>
  (workspace.value?.alerts || []).filter((item) => item.status !== "RESOLVED"),
);
const alertCount = computed(() =>
  !workspace.value
    ? "—"
    : alerts.value.length >= 200
      ? "200+"
      : String(alerts.value.length),
);
const pendingCount = computed(
  () =>
    operations.value?.summary?.openTasks ?? workspace.value?.farm?.pendingTasks,
);
const soilOption = computed(() => ({
  ...moistureOption(operations.value, view.value === "overview"),
  animation: !reducedMotion.value,
}));
const hasSoilHistory = computed(() =>
  moisturePoints(operations.value).some((point) => point[1] !== null),
);
const metricCards = computed(() =>
  [
    { metric: "TEMPERATURE", title: "空气温度", unit: "℃", icon: "today" },
    { metric: "HUMIDITY", title: "空气湿度", unit: "%", icon: "irrigation" },
    {
      metric: "SOIL_MOISTURE",
      title: "土壤水分",
      unit: "%",
      icon: "plantings",
    },
  ].map((item) => ({
    ...item,
    ...metricSummary(analysis.value?.sensors, item.metric, clock.value),
  })),
);
const sourceName = computed(() =>
  briefing.value?.source === "LLM" ? "AI 综合分析" : "规则分析",
);
const pendingAnalysis = computed(() =>
  ["PENDING", "GENERATING"].includes(briefing.value?.status),
);
const facts = computed(() => [
  {
    title: "登记耕地",
    value: workspace.value?.farm?.areaMu,
    unit: "亩",
    note: workspace.value
      ? `${workspace.value.plots.length} 个地块`
      : "等待读取农场资料",
    icon: "plots",
  },
  {
    title: "正常接收设备",
    value: devices.value.fresh,
    unit:
      devices.value.monitored === null
        ? "台"
        : `/ ${devices.value.monitored} 台`,
    note: devices.value.late
      ? `${devices.value.late} 台待核查接收状态`
      : "启用的自动上报设备",
    icon: "devices",
  },
  {
    title: "待办农事",
    value:
      operations.value?.summary?.openTasks ??
      workspace.value?.farm?.pendingTasks,
    unit: "项",
    note: operations.value
      ? `${operations.value.summary.overdueTasks} 项逾期`
      : "待执行及执行中的任务",
    icon: "tasks",
  },
  {
    title: "近 30 天登记产量",
    value: productionTotal(workspace.value?.analytics),
    unit: "kg",
    note: !workspace.value
      ? "生产数据暂不可用"
      : workspace.value.analytics?.productionTrend?.length
        ? "已登记收获记录合计"
        : "暂无收获记录",
    icon: "production",
  },
]);
const nowText = computed(() =>
  new Date(clock.value).toLocaleString("zh-CN", {
    timeZone: "Asia/Shanghai",
    hour12: false,
    month: "long",
    day: "numeric",
    weekday: "long",
    hour: "2-digit",
    minute: "2-digit",
  }),
);

function clearData() {
  workspace.value =
    operations.value =
    analysis.value =
    briefing.value =
    geo.value =
    spatial.value =
      null;
  selectedPlot.value = "";
  readAt.value = null;
  failures.value = [];
}

async function refresh({ reset = false } = {}) {
  controller?.abort();
  const ticket = ++sequence,
    farmId = props.farmId;
  if (reset) clearData();
  if (!accessible.value) {
    clearData();
    loading.value = false;
    return;
  }
  controller = new AbortController();
  const signal = controller.signal,
    id = encodeURIComponent(farmId);
  loading.value = true;
  const requests = [
    ["农场资料", workspace, `/farms/${id}/workspace?days=30`],
    ["运行趋势", operations, `/farms/${id}/operations?hours=168`],
    ["智能农情", analysis, `/ai/analysis?farmId=${id}`],
    ["每日简报", briefing, `/ai/briefings/today?farmId=${id}`],
    ["地图定位", geo, `/farms/${id}/map-config`],
    ["田块边界", spatial, `/farms/${id}/field-map`],
  ];
  const results = await Promise.allSettled(
    requests.map(([, , path]) => api(path, "GET", undefined, { signal })),
  );
  if (
    !alive ||
    ticket !== sequence ||
    farmId !== props.farmId ||
    !accessible.value
  )
    return;
  failures.value = [];
  results.forEach((result, index) => {
    const [label, target] = requests[index];
    if (result.status === "fulfilled") target.value = result.value;
    else {
      target.value = null;
      if (!result.reason?.cancelled)
        failures.value.push(
          `${label}：${result.reason?.message || "读取失败"}`,
        );
    }
  });
  readAt.value = results.some((result) => result.status === "fulfilled")
    ? new Date().toISOString()
    : null;
  clock.value = Date.now();
  loading.value = false;
}

function visible() {
  clock.value = Date.now();
  if (!document.hidden && !loading.value) refresh();
}
function navigate(page, deviceId) {
  emit("navigate", {
    page,
    farmId: props.farmId,
    ...(deviceId ? { deviceId } : {}),
  });
}
function ask(item) {
  if (!accessible.value) return;
  const context = item
    ? `${item.title || item.name}：${item.recommendation || item.advice}。依据：${(item.evidence || []).join("；")}`
    : `请分析当前农场的监测、待办事项和设备异常，按优先级给出今天的生产建议。`;
  emit("ask", {
    farmId: props.farmId,
    question:
      `${context} 请使用当前农场的最新数据核实，说明缺失信息、具体处理步骤和复核方法。${briefing.value ? `大屏日报为 ${briefing.value.reportDate} 的快照，生成于 ${briefing.value.generatedAt}，请与实时监测区分。` : ""}`.slice(
        0,
        2000,
      ),
  });
}
function act(item) {
  if (item.target === "alerts") emit("alerts", { farmId: props.farmId });
  else if (item.target === "agronomy") ask(item);
  else
    navigate(
      { tasks: "daily", plantings: "plantings", production: "production" }[
        item.target
      ] || "daily",
    );
}
const actionName = (item) =>
  ({
    alerts: "查看预警",
    agronomy: "带入 AI 追问",
    tasks: "查看农事",
    plantings: "查看种植计划",
    production: "查看生产记录",
  })[item.target] || "查看今日农场";
const priorityName = (priority) =>
  ({ HIGH: "优先处理", MEDIUM: "今日跟进", LOW: "持续关注" })[priority] ||
  "今日跟进";
async function toggleFullscreen() {
  fullscreenError.value = "";
  try {
    if (document.fullscreenElement === screen.value)
      await document.exitFullscreen();
    else if (screen.value?.requestFullscreen)
      await screen.value.requestFullscreen();
    else
      fullscreenError.value =
        "当前浏览器不支持全屏，可使用浏览器的全屏快捷键。";
  } catch {
    fullscreenError.value = "浏览器未允许进入全屏，当前仍可正常查看大屏。";
  }
}
function fullscreenChanged() {
  fullscreen.value = document.fullscreenElement === screen.value;
}
async function close() {
  if (document.fullscreenElement === screen.value)
    await document.exitFullscreen().catch(() => {});
  emit("close");
}
watch(
  () => [props.farmId, props.role, accessible.value],
  () => refresh({ reset: true }),
  { immediate: true, flush: "sync" },
);
function motionChanged() {
  reducedMotion.value = motionPreference.matches;
}
onMounted(() => {
  motionPreference = window.matchMedia("(prefers-reduced-motion: reduce)");
  motionChanged();
  motionPreference.addEventListener("change", motionChanged);
  opener = document.activeElement;
  dialog.value?.showModal();
  document.addEventListener("visibilitychange", visible);
  document.addEventListener("fullscreenchange", fullscreenChanged);
  poll = setInterval(() => {
    if (!document.hidden && !loading.value) refresh();
  }, 60000);
  clockTimer = setInterval(() => {
    if (!document.hidden) clock.value = Date.now();
  }, 10000);
});
onBeforeUnmount(() => {
  alive = false;
  sequence++;
  controller?.abort();
  clearInterval(poll);
  clearInterval(clockTimer);
  motionPreference?.removeEventListener("change", motionChanged);
  document.removeEventListener("visibilitychange", visible);
  document.removeEventListener("fullscreenchange", fullscreenChanged);
  if (document.fullscreenElement === screen.value)
    document.exitFullscreen().catch(() => {});
  if (dialog.value?.open) dialog.value.close();
  if (opener?.isConnected) opener.focus();
});
</script>

<template>
  <dialog
    ref="dialog"
    class="farm-display"
    aria-label="农场展示大屏"
    @cancel.prevent="close"
  >
    <div
      ref="screen"
      class="display-stage"
      :class="{
        'display-overview-stage': view === 'overview',
        'display-agronomy-stage': view === 'agronomy',
      }"
    >
      <header class="display-header">
        <div class="display-brand">
          <AppIcon name="plantings" />
          <div>
            <span>智禾农场</span>
            <h1>农场展示大屏</h1>
          </div>
        </div>
        <label class="display-farm-picker"
          ><span>当前农场</span
          ><select
            :value="farmId"
            aria-label="大屏当前农场"
            @change="emit('update:farmId', $event.target.value)"
          >
            <option
              v-for="item in availableFarms"
              :key="item.id"
              :value="item.id"
            >
              {{ item.name }}
            </option>
          </select></label
        >
        <div class="display-header-tools">
          <time>{{ nowText }}</time
          ><button
            type="button"
            :disabled="loading || !accessible"
            @click="refresh()"
          >
            <AppIcon name="refresh" />{{ loading ? "读取中" : "刷新" }}</button
          ><button
            type="button"
            :aria-pressed="fullscreen"
            @click="toggleFullscreen"
          >
            <AppIcon name="expand" />{{
              fullscreen ? "退出全屏" : "全屏"
            }}</button
          ><button type="button" class="display-return" @click="close">
            <AppIcon name="logout" />返回工作台
          </button>
        </div>
      </header>
      <main class="display-content" :aria-busy="loading">
        <div class="display-view-bar">
          <nav aria-label="大屏视图">
            <button
              :aria-pressed="view === 'overview'"
              @click="view = 'overview'"
            >
              <AppIcon name="overview" />农场总览</button
            ><button
              :aria-pressed="view === 'agronomy'"
              @click="view = 'agronomy'"
            >
              <AppIcon name="plantings" />智能农情
            </button>
          </nav>
          <p>
            <span
              class="display-status-dot"
              :class="{ warning: failures.length }"
            ></span
            >{{
              readAt
                ? `最近读取 ${timeText(readAt, { short: true })}`
                : loading
                  ? "正在读取农场数据"
                  : "尚未读取数据"
            }}<span class="display-refresh-note">每 60 秒更新</span>
          </p>
        </div>
        <p v-if="fullscreenError" class="display-notice" role="status">
          {{ fullscreenError }}
        </p>
        <div
          v-if="failures.length"
          class="display-notice display-failure"
          role="alert"
        >
          <strong>部分数据暂不可用</strong>
          <p v-for="failure in failures" :key="failure">{{ failure }}</p>
          <button :disabled="loading" @click="refresh()">重新读取</button>
        </div>
        <div v-if="!accessible" class="display-empty display-access">
          请选择当前账号有权访问的农场。
        </div>
        <template v-else>
          <template v-if="view === 'overview'">
            <section class="display-facts" aria-label="农场关键数据">
              <article v-for="fact in facts" :key="fact.title">
                <div>
                  <span>{{ fact.title }}</span
                  ><AppIcon :name="fact.icon" />
                </div>
                <p>
                  <strong>{{ numberText(fact.value) }}</strong
                  ><span>{{ fact.unit }}</span>
                </p>
                <small>{{ fact.note }}</small>
              </article>
            </section>
            <div class="display-overview-grid">
              <section class="display-map-panel">
                <div class="display-section-heading">
                  <div>
                    <h2>{{ farm?.name || "农场" }} · 田块分布</h2>
                    <p>
                      {{
                        geo?.locationLabel || farm?.region || "已保存的农场位置"
                      }}
                    </p>
                  </div>
                  <span class="display-tag display-tag-leaf">卫星实景</span>
                </div>
                <SatelliteFarmMap
                  :key="farmId"
                  :geometry="map"
                  :selected-plot="selectedPlot"
                  :loading="loading"
                  @plot="selectedPlot = $event"
                  @device="navigate('devices', $event)"
                  @configure="navigate('dashboard')"
                >
                  <div v-if="plot" class="display-plot-detail">
                    <strong>{{ plot.name }}</strong>
                    <span
                      >{{ plot.crop || "未填写作物" }} /
                      {{ numberText(plot.areaMu) }} 亩</span
                    >
                    <button @click="navigate('plots')">查看地块资料</button>
                    <button
                      class="display-plot-close"
                      aria-label="收起地块详情"
                      @click="selectedPlot = ''"
                    >
                      ×
                    </button>
                  </div>
                </SatelliteFarmMap>
                <footer class="display-map-caption">
                  <span><i></i>设备位置 <i class="warning"></i>存在预警</span
                  ><span>缩放或点选查看田块 · WGS84 估绘，未实测</span>
                  <span v-if="geo && !geo.configured">定位参考待校准</span>
                  <span v-if="map.missingPlots || map.missingDevices"
                    >{{ map.missingPlots }} 个地块缺少有效边界，{{
                      map.missingDevices
                    }}
                    台设备位置未展示</span
                  >
                </footer>
              </section>
              <aside class="display-briefing">
                <div class="display-section-heading">
                  <div>
                    <h2>今日经营研判</h2>
                    <p>每天一份经营快照</p>
                  </div>
                  <span v-if="briefing" class="display-tag display-tag-leaf">{{
                    sourceName
                  }}</span>
                </div>
                <template v-if="briefing">
                  <div
                    class="display-briefing-body"
                    tabindex="0"
                    role="region"
                    aria-label="今日经营研判详细内容"
                  >
                    <p class="display-briefing-date">
                      {{ briefing.reportDate }} · 生成于
                      {{ timeText(briefing.generatedAt, { short: true }) }}
                    </p>
                    <div class="display-briefing-summary">
                      <AssistantText
                        v-if="briefing.aiSummary"
                        :text="briefing.aiSummary"
                      />
                      <p v-else>{{ briefing.summary }}</p>
                    </div>
                    <p
                      class="display-source-note"
                      v-if="briefing.source !== 'LLM'"
                    >
                      {{
                        pendingAnalysis
                          ? "AI 正在补充解读，先展示规则分析。"
                          : diagnosticLabels[briefing.diagnostic] ||
                            "依据已登记数据进行规则分析。"
                      }}
                    </p>
                    <div
                      v-if="briefing.priorities?.length"
                      class="display-first-priority"
                    >
                      <span>{{
                        priorityName(briefing.priorities[0].priority)
                      }}</span>
                      <h3>{{ briefing.priorities[0].title }}</h3>
                      <p>{{ briefing.priorities[0].recommendation }}</p>
                      <button @click="act(briefing.priorities[0])">
                        {{ actionName(briefing.priorities[0]) }}
                      </button>
                    </div>
                    <details
                      v-if="briefing.dataGaps?.length"
                      class="display-gaps"
                    >
                      <summary>
                        还需补齐 {{ briefing.dataGaps.length }} 项信息
                      </summary>
                      <ul>
                        <li v-for="gap in briefing.dataGaps" :key="gap">
                          {{ gap }}
                        </li>
                      </ul>
                    </details>
                  </div>
                  <button class="display-primary" @click="ask()">
                    <AppIcon name="send" />带入 AI 深入分析
                  </button></template
                >
                <div v-else class="display-empty">
                  {{
                    loading
                      ? "正在读取今日简报"
                      : "今日简报暂不可用，请刷新重试。"
                  }}
                </div>
              </aside>
              <section class="display-trend-panel">
                <div class="display-section-heading">
                  <div>
                    <h2>近 7 天土壤水分</h2>
                    <p>时间片采样均值 · 空档保持断线</p>
                  </div>
                  <span class="display-tag">历史观测</span>
                </div>
                <DataChart
                  v-if="hasSoilHistory"
                  :key="farmId"
                  :option="soilOption"
                  scope="farm-display"
                  label="近7天土壤水分时间片均值，缺测处断线"
                />
                <div v-else class="display-empty display-chart-empty">
                  {{
                    operations
                      ? "近 7 天暂无土壤水分采样。"
                      : "土壤水分趋势暂不可用。"
                  }}
                </div>
              </section>
              <section class="display-followup-panel">
                <div class="display-section-heading">
                  <div>
                    <h2>待跟进事项</h2>
                    <p>实时读取，与每日简报分别更新</p>
                  </div>
                  <button @click="emit('alerts', { farmId })">
                    <AppIcon name="bell" />{{ alertCount }}
                    项预警
                  </button>
                </div>
                <div class="display-followup-columns">
                  <div>
                    <h3>农事任务</h3>
                    <ul v-if="work.length" class="display-task-list">
                      <li v-for="task in work.slice(0, 3)" :key="task.id">
                        <button @click="navigate('daily')">
                          <span>{{ task.title }}</span
                          ><small
                            >{{ task.plotName }} ·
                            {{ task.dueDate || "未设截止日期" }} ·
                            {{ stateNames[task.status] }}</small
                          >
                        </button>
                      </li>
                    </ul>
                    <p v-else class="display-muted">
                      {{
                        !workspace
                          ? "农事数据暂不可用"
                          : pendingCount > 0
                            ? "近期记录未包含待办，请查看完整农事。"
                            : "暂无待办农事"
                      }}
                    </p>
                    <button
                      v-if="workspace && !work.length && pendingCount > 0"
                      @click="navigate('daily')"
                    >
                      查看完整农事
                    </button>
                  </div>
                  <div>
                    <h3>设备提醒</h3>
                    <ul v-if="alerts.length" class="display-alert-list">
                      <li v-for="alert in alerts.slice(0, 2)" :key="alert.id">
                        <button @click="emit('alerts', { farmId })">
                          <span
                            >{{ alert.deviceName }} ·
                            {{
                              alert.title ||
                              alert.metricName ||
                              (alert.kind === "OFFLINE"
                                ? "设备离线"
                                : "数据异常")
                            }}</span
                          ><small>{{
                            alert.recommendation || alert.message
                          }}</small>
                        </button>
                      </li>
                    </ul>
                    <p v-else class="display-muted">
                      {{
                        workspace
                          ? "暂无未关闭预警，继续关注设备上报。"
                          : "设备预警暂不可用"
                      }}
                    </p>
                  </div>
                </div>
              </section>
            </div>
          </template>
          <template v-else>
            <section class="display-agronomy-intro">
              <div>
                <h2>看清农情，再安排今天的行动</h2>
                <p>最新有效观测、历史趋势与当天建议，同屏核对。</p>
              </div>
              <button class="display-primary" @click="ask()">
                <AppIcon name="send" />询问 AI 农场助手
              </button>
            </section>
            <section class="display-metrics" aria-label="最新农情监测">
              <article v-for="item in metricCards" :key="item.metric">
                <div>
                  <h3><AppIcon :name="item.icon" />{{ item.title }}</h3>
                  <span
                    class="display-tag"
                    :class="{
                      'display-tag-leaf': item.state === 'FRESH',
                      'display-tag-gold': item.state === 'STALE',
                    }"
                    >{{
                      !analysis
                        ? "暂不可用"
                        : item.state === "FRESH"
                          ? `${item.fresh} 个有效测点`
                          : item.state === "STALE"
                            ? "读数已过期"
                            : "尚无测点数据"
                    }}</span
                  >
                </div>
                <p>
                  <strong>{{ numberText(item.value) }}</strong
                  ><span>{{ item.unit }}</span>
                </p>
                <small v-if="item.fresh"
                  >有效测点均值 · 范围 {{ numberText(item.min) }}–{{
                    numberText(item.max)
                  }}{{ item.unit
                  }}<template v-if="item.stale">
                    · {{ item.stale }} 个旧读数未计入</template
                  ></small
                ><small v-else>{{
                  item.latest
                    ? `最近采样 ${timeText(item.latest, { short: true })}，不用于当前判断`
                    : "请核查监测设备与数据接入。"
                }}</small>
              </article>
            </section>
            <div class="display-agronomy-grid">
              <section class="display-condition-panel">
                <div class="display-section-heading">
                  <div>
                    <h2>智能农情诊断</h2>
                    <p>根据监测、种植档案和巡田记录汇总</p>
                  </div>
                  <span class="display-tag">规则分析</span>
                </div>
                <div v-if="analysis" class="display-conditions">
                  <article
                    v-for="condition in analysis.conditions"
                    :key="condition.code"
                  >
                    <div>
                      <h3>{{ condition.name }}</h3>
                      <span>{{ condition.state }}</span>
                    </div>
                    <p>{{ condition.advice }}</p>
                    <button @click="ask(condition)">追问依据与处理步骤</button>
                  </article>
                </div>
                <div v-else class="display-empty">
                  {{ loading ? "正在读取农情诊断" : "农情诊断暂不可用。" }}
                </div>
                <div v-if="analysis?.cautions?.length" class="display-cautions">
                  <strong>需要现场复核的线索</strong>
                  <p v-for="caution in analysis.cautions" :key="caution">
                    {{ caution }}
                  </p>
                </div>
                <p v-if="analysis" class="display-source-note">
                  {{ analysis.thresholdNotice }}
                </p>
              </section>
              <section class="display-trend-panel display-trend-large">
                <div class="display-section-heading">
                  <div>
                    <h2>近 7 天土壤水分走势</h2>
                    <p>只展示已采集记录，不补齐缺测值</p>
                  </div>
                  <button @click="navigate('devices')">查看原始读数</button>
                </div>
                <DataChart
                  v-if="hasSoilHistory"
                  :key="farmId"
                  :option="soilOption"
                  scope="farm-display"
                  label="近7天土壤水分时间片均值，缺测处断线"
                />
                <div v-else class="display-empty display-chart-empty">
                  {{
                    operations
                      ? "近 7 天暂无土壤水分采样。"
                      : "土壤水分趋势暂不可用。"
                  }}
                </div>
                <details class="display-chart-note">
                  <summary>如何解读这条曲线</summary>
                  <p>
                    各测点合并均值用于观察走势，灌溉前需结合地块、作物与现场根层水分核实。
                  </p>
                </details>
              </section>
              <section class="display-recommendations">
                <div class="display-section-heading">
                  <div>
                    <h2>今天的生产建议</h2>
                    <p>
                      {{
                        briefing
                          ? `${briefing.reportDate} 日报快照，执行前复核最新情况`
                          : "等待读取日报建议"
                      }}
                    </p>
                  </div>
                  <span v-if="briefing" class="display-tag">{{
                    sourceName
                  }}</span>
                </div>
                <div
                  v-if="briefing?.priorities?.length"
                  class="display-recommendation-list"
                >
                  <article
                    v-for="item in briefing.priorities.slice(0, 4)"
                    :key="item.id"
                  >
                    <span
                      class="display-priority"
                      :class="{ urgent: item.priority === 'HIGH' }"
                      >{{ priorityName(item.priority) }}</span
                    >
                    <h3>{{ item.title }}</h3>
                    <p>{{ item.recommendation }}</p>
                    <ul>
                      <li v-for="evidence in item.evidence" :key="evidence">
                        {{ evidence }}
                      </li>
                    </ul>
                    <button @click="act(item)">{{ actionName(item) }}</button>
                  </article>
                </div>
                <p v-else class="display-muted">
                  {{
                    briefing
                      ? "暂无明确优先事项，请继续补充巡田与监测记录。"
                      : "今日建议暂不可用，请刷新重试。"
                  }}
                </p>
                <div v-if="briefing?.dataGaps?.length" class="display-gap-line">
                  <strong>数据缺口</strong
                  ><span v-for="gap in briefing.dataGaps" :key="gap">{{
                    gap
                  }}</span>
                </div>
              </section>
            </div>
          </template>
        </template>
        <footer class="display-footer">
          <span>虚构演示数据 · 农情分析用于辅助判断</span
          ><span>日报为当日快照，监测按采样时效显示 · 时间 UTC+8</span>
        </footer>
      </main>
    </div>
  </dialog>
</template>
