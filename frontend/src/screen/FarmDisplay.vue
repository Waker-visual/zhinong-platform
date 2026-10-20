<script setup>
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from "vue";
import { api } from "../api";
import AppIcon from "../ui/AppIcon.vue";
import DataChart from "../workspace/DataChart.vue";
import AssistantText from "../ai/AssistantText.vue";
import SatelliteFarmMap from "./SatelliteFarmMap.vue";
import FarmScene from "./FarmScene.vue";
import SceneAmbience from "./SceneAmbience.vue";
import AgronomyWork from "./AgronomyWork.vue";
import WeatherForecast from "./WeatherForecast.vue";
import RiskOverview from "./RiskOverview.vue";
import { satelliteGeometry } from "./satellite-model.js";
import {
  createScreenRefresh,
  screenFreshness,
  screenResources,
} from "./screen-refresh.js";
import { diagnosticLabels } from "../ai/diagnostics";
import { stateNames } from "../workspace/presentation";
import {
  canReadFarm,
  forecastPresentation,
  deviceCounts,
  hasDeviceAlert,
  metricSummary,
  moistureOption,
  moisturePoints,
  numberText,
  pendingTasks,
  prioritizedRisks,
  riskForecastFreshness,
  productionDistribution,
  productionOption,
  productionTotal,
  timeText,
} from "./screen-model.js";
import "./display.css";
import "./forecast.css";
import "./agronomy.css";

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
  view = ref("scene"),
  selectedPlot = ref("");
const workspace = ref(null),
  operations = ref(null),
  analysis = ref(null),
  briefing = ref(null),
  geo = ref(null),
  spatial = ref(null),
  forecast = ref(null),
  risks = ref(null);
const forecastSection = ref(null),
  riskSection = ref(null),
  riskPanel = ref(null),
  riskDate = ref("");
const loading = ref(false),
  resourceStates = ref({}),
  clock = ref(Date.now()),
  fullscreen = ref(false),
  fullscreenError = ref(""),
  reducedMotion = ref(false);
let poll, clockTimer, motionPreference, opener;
const resourceTargets = {
  workspace,
  operations,
  analysis,
  briefing,
  geo,
  spatial,
  forecast,
  risks,
};
const refreshState = createScreenRefresh({
  request: (path, options) => api(path, "GET", undefined, options),
  onUpdate: (state) => {
    resourceStates.value = state.resources;
    loading.value = state.loading;
    for (const [key, target] of Object.entries(resourceTargets))
      target.value = state.resources[key].data;
    clock.value = Date.now();
  },
});
const freshness = computed(() => screenFreshness(resourceStates.value));
const forecastStatus = computed(
  () =>
    forecastPresentation(
      forecast.value,
      resourceStates.value.forecast,
      clock.value,
    ).status,
);
const weatherRiskCurrent = computed(
  () =>
    forecastStatus.value === "READY" &&
    riskForecastFreshness(risks.value, clock.value) === "READY" &&
    !resourceStates.value.risks?.error,
);
const priorityRisk = computed(() =>
  prioritizedRisks(risks.value).find(
    (item) =>
      item.severity === "HIGH" &&
      (item.source !== "WEATHER" || weatherRiskCurrent.value),
  ),
);
const failures = computed(() =>
  screenResources.flatMap(({ key, label }) => {
    const state = resourceStates.value[key];
    return state?.error
      ? [
          `${label}：${state.error}${state.readAt ? `（保留 ${timeText(state.readAt, { short: true })} 读取的快照）` : ""}`,
        ]
      : [];
  }),
);
const readSummary = computed(() =>
  screenResources
    .map(({ key, label }) => `${label}：${resourceTime(key)}`)
    .join("；"),
);
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
    : String(
        workspace.value.alertTotal ??
          workspace.value.devices.reduce(
            (total, device) => total + Number(device.alertCount || 0),
            0,
          ),
      ),
);
const pendingCount = computed(
  () =>
    operations.value?.summary?.openTasks ?? workspace.value?.farm?.pendingTasks,
);
const soilOption = computed(() => ({
  ...moistureOption(operations.value),
  animation: !reducedMotion.value,
}));
const harvest = computed(() =>
  productionDistribution(workspace.value?.analytics),
);
const harvestOption = computed(() => ({
  ...productionOption(harvest.value),
  animation: !reducedMotion.value,
}));
const harvestDescription = computed(
  () =>
    `近30天台账登记采收产量，单位kg。${harvest.value.groupedPlots ? `按产量展示前3个地块，其余${harvest.value.groupedPlots}个地块合并。` : "展示全部地块。"}${harvest.value.rows.map((row) => `${row.name}：${numberText(row.value)} kg`).join("；")}`,
);
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

function resourceTime(key) {
  const state = resourceStates.value[key];
  if (!state?.readAt) return state?.loading ? "读取中" : "尚未读取";
  return `${state.error ? "保留快照" : "读取于"} ${timeText(state.readAt, { short: true })}`;
}
function conditionSummary(advice) {
  const text = typeof advice === "string" ? advice.trim() : "";
  const end = text.indexOf("。");
  return end < 0 ? text : text.slice(0, end + 1);
}
function refresh({
  reset = false,
  onlyFailed = false,
  liveOnly = false,
  keys = null,
} = {}) {
  if (reset || !accessible.value) {
    refreshState.reset(accessible.value ? props.farmId : null);
    selectedPlot.value = "";
    riskDate.value = "";
  }
  if (!accessible.value || document.hidden) return;
  return refreshState.refresh(props.farmId, { onlyFailed, liveOnly, keys });
}
async function showWeatherRisks(date) {
  riskDate.value = date;
  await nextTick();
  riskSection.value?.scrollIntoView({
    behavior: reducedMotion.value ? "instant" : "smooth",
    block: "start",
  });
  riskPanel.value?.focus();
}
async function showPriorityRisks() {
  riskDate.value = "";
  riskPanel.value?.resetFilters();
  await nextTick();
  riskSection.value?.scrollIntoView({
    behavior: reducedMotion.value ? "instant" : "smooth",
    block: "start",
  });
  riskPanel.value?.focus();
}
function riskAction(item) {
  const target = item.action?.target;
  if (target === "alerts") emit("alerts", { farmId: props.farmId });
  else if (target === "forecast")
    forecastSection.value?.scrollIntoView({
      behavior: reducedMotion.value ? "instant" : "smooth",
      block: "start",
    });
  else
    navigate(
      { daily: "daily", devices: "devices", map: "dashboard" }[target] ||
        "daily",
      item.deviceId,
    );
}

function visible() {
  clock.value = Date.now();
  if (document.hidden) refreshState.cancel();
  else refresh();
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
  const weatherContext =
    item?.source === "WEATHER"
      ? `区域公开天气预报参考，虚构农场参考位置：${forecast.value?.location?.label || "未标注"}；获取于 ${risks.value?.forecastFetchedAt || forecast.value?.fetchedAt || "未标注"}，有效至 ${risks.value?.forecastExpiresAt || forecast.value?.expiresAt || "未标注"}。当前天气风险依据状态 ${weatherRiskCurrent.value ? "有效参考" : "需重新更新核对"}，请勿当成田间实测或官方气象预警。`
      : "";
  const context = item
    ? `${weatherContext}${item.title || item.name}：${item.recommendation || item.advice}。依据：${(item.evidence || []).join("；")}`
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
watch(view, async () => {
  riskDate.value = "";
  await nextTick();
  screen.value?.scrollTo({ top: 0, behavior: "instant" });
});
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
  let pollTick = 0;
  poll = setInterval(() => {
    pollTick++;
    if (
      !document.hidden &&
      !loading.value &&
      (view.value === "scene" || pollTick % 12 === 0)
    )
      refresh({ liveOnly: pollTick % 12 !== 0 });
  }, 5000);
  clockTimer = setInterval(() => {
    if (!document.hidden) clock.value = Date.now();
  }, 10000);
});
onBeforeUnmount(() => {
  refreshState.dispose();
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
        'display-weather-stage': view === 'weather',
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
          <SceneAmbience />
          <details
            class="display-about"
            @keydown.esc.stop.prevent="$event.currentTarget.open = false"
          >
            <summary aria-label="大屏来源说明" title="来源说明">
              <AppIcon name="info" />
            </summary>
            <div class="display-about-content">
              <strong>来源说明</strong>
              <p>
                虚构演示数据。田块、设备和管网来自当前农场登记资料；模型高度和环境设施为示意，非实景测绘。
              </p>
              <p>
                卫星影像 © Esri, Vantor, Earthstar Geographics, GIS User
                Community。
              </p>
              <a
                href="https://virtual.cleverfarm.ag/"
                target="_blank"
                rel="noopener noreferrer"
                >模型参考：CleverFarm</a
              >
              <p>
                农情分析用于辅助判断；日报为当日快照，监测按采样时效显示。时间
                UTC+8。
              </p>
            </div>
          </details>
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
            <button :aria-pressed="view === 'scene'" @click="view = 'scene'">
              <AppIcon name="cube" />三维农场
            </button>
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
            <button
              :aria-pressed="view === 'weather'"
              @click="view = 'weather'"
            >
              <AppIcon name="bell" />天气预警
            </button>
          </nav>
          <p :title="readSummary">
            <span
              class="display-status-dot"
              :class="{ warning: failures.length }"
            ></span
            >{{
              freshness.readAt
                ? `${freshness.loaded}/${freshness.total} 项已读取 · 最早 ${timeText(freshness.readAt, { short: true })}`
                : loading
                  ? "正在读取农场数据"
                  : "尚未读取数据"
            }}<span class="display-refresh-note">{{
              loading ? "分项更新中" : "三维设备 5 秒 / 其他 60 秒更新"
            }}</span>
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
          <strong>部分数据更新失败，已有内容保留原读取时间</strong>
          <p v-for="failure in failures" :key="failure">{{ failure }}</p>
          <button :disabled="loading" @click="refresh({ onlyFailed: true })">
            重试失败项
          </button>
        </div>
        <div v-if="!accessible" class="display-empty display-access">
          请选择当前账号有权访问的农场。
        </div>
        <template v-else>
          <FarmScene
            v-if="view === 'scene'"
            :key="farmId"
            :geometry="map"
            :zones="spatial?.zones || []"
            :jobs="spatial?.jobs || []"
            :spatial-read-at="resourceStates.spatial?.readAt || ''"
            :role="role"
            @refresh="refresh()"
            :plots="workspace?.plots || []"
            :farm-name="farm?.name"
            :selected-plot="selectedPlot"
            @plot="selectedPlot = $event"
            @satellite="view = 'overview'"
          >
            <template #summary>
              <article v-for="fact in facts.slice(0, 3)" :key="fact.title">
                <span>{{ fact.title }}</span>
                <p>
                  <strong>{{ numberText(fact.value) }}</strong> {{ fact.unit }}
                </p>
                <small>{{ fact.note }}</small>
              </article>
            </template>
            <div v-if="plot" class="farm-landscape-detail">
              <strong>{{ plot.name }}</strong>
              <p>
                {{ plot.crop || "未填写作物" }} /
                {{ numberText(plot.areaMu) }} 亩
              </p>
              <button @click="navigate('plots')">查看地块资料</button>
            </div>
          </FarmScene>
          <template v-else-if="view === 'overview'">
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
                  :loading="
                    resourceStates.workspace?.loading ||
                    resourceStates.geo?.loading ||
                    resourceStates.spatial?.loading
                  "
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
                    <p>每天一份经营快照 · {{ resourceTime("briefing") }}</p>
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
                    <p
                      v-if="briefing.snapshotOutdated"
                      class="display-source-note"
                    >
                      生成后已有更新，请以实时任务台账与设备提醒核对当前状态。
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
                    resourceStates.briefing?.loading
                      ? "正在读取今日简报"
                      : "今日简报暂不可用，请刷新重试。"
                  }}
                </div>
              </aside>
              <section class="display-trend-panel display-production-panel">
                <div class="display-section-heading">
                  <div>
                    <h2>近 30 天采收分布</h2>
                    <p :title="resourceTime('workspace')">
                      台账产量 / kg<template
                        v-if="harvest.recordedDays !== null"
                      >
                        · {{ harvest.recordedDays }} 天有登记</template
                      >
                    </p>
                  </div>
                  <button @click="navigate('production')">生产台账</button>
                </div>
                <DataChart
                  v-if="harvest.state === 'AVAILABLE'"
                  :key="farmId"
                  :option="harvestOption"
                  scope="farm-display"
                  :label="harvestDescription"
                />
                <div v-else class="display-empty display-chart-empty">
                  {{
                    harvest.state === "EMPTY"
                      ? "近 30 天暂无采收登记。"
                      : resourceStates.workspace?.loading
                        ? "正在读取采收台账"
                        : "采收分布暂不可用，请刷新重试。"
                  }}
                </div>
                <p v-if="harvest.missingPlots" class="display-source-note">
                  {{ harvest.missingPlots }} 个地块产量缺失，未计入分布。
                </p>
              </section>
              <section class="display-followup-panel">
                <div class="display-section-heading">
                  <div>
                    <h2>待跟进事项</h2>
                    <p>{{ resourceTime("workspace") }} · 与日报分别更新</p>
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
          <template v-else-if="view === 'weather'">
            <section class="display-agronomy-intro display-weather-intro">
              <div>
                <h2>七日天气与重点预警</h2>
                <p v-if="priorityRisk">
                  <button class="display-risk-peek" @click="showPriorityRisks">
                    优先核对：{{ priorityRisk.title }}
                  </button>
                </p>
                <p v-else>先核对区域天气与优先线索，再结合田间观测安排行动。</p>
              </div>
              <button
                class="display-primary"
                @click="
                  ask({
                    source: 'WEATHER',
                    title: '未来七日天气与农事安排',
                    recommendation:
                      '结合七日预报、当前作物和现场观测，分析天气影响与需人工复核的行动',
                  })
                "
              >
                <AppIcon name="send" />询问 AI 天气影响
              </button>
            </section>
            <div class="display-weather-grid">
              <div ref="forecastSection" class="screen-forecast-anchor">
                <WeatherForecast
                  :key="farmId"
                  :data="forecast"
                  :resource="resourceStates.forecast"
                  :risks="risks"
                  :risk-resource="resourceStates.risks"
                  :now="clock"
                  :role="role"
                  @retry="refresh({ keys: ['forecast', 'risks'] })"
                  @map="navigate('dashboard')"
                  @risks="showWeatherRisks"
                />
              </div>
              <div ref="riskSection" class="screen-risk-anchor">
                <RiskOverview
                  ref="riskPanel"
                  :key="farmId"
                  :data="risks"
                  :resource="resourceStates.risks"
                  :forecast-status="forecastStatus"
                  :now="clock"
                  :selected-date="riskDate"
                  @retry="refresh({ keys: ['risks'] })"
                  @action="riskAction"
                  @ask="ask"
                  @clear-date="riskDate = ''"
                />
              </div>
            </div>
          </template>
          <template v-else-if="view === 'agronomy'">
            <section class="display-agronomy-intro display-agronomy-summary">
              <div>
                <h2>智能农情与农事进度</h2>
                <p>监测、作业、种植与采收，同屏核对。</p>
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
                            ? "暂无有效读数"
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
                    · {{ item.stale }} 个无效读数未计入</template
                  ></small
                ><small v-else>{{
                  item.latest
                    ? `最近采样 ${timeText(item.latest, { short: true })}，不用于当前判断`
                    : "请核查监测设备与数据接入。"
                }}</small>
              </article>
            </section>
            <div class="display-agronomy-grid">
              <AgronomyWork
                :key="`work-${farmId}`"
                :workspace="workspace"
                :operations="operations"
                :analysis="analysis"
                :workspace-time="resourceTime('workspace')"
                :operations-time="resourceTime('operations')"
                :analysis-time="resourceTime('analysis')"
                @navigate="navigate"
              />
              <section
                :key="`conditions-${farmId}`"
                class="display-condition-panel"
              >
                <div class="display-section-heading">
                  <div>
                    <h2>智能农情诊断</h2>
                    <p>
                      监测、种植档案与巡田汇总 · {{ resourceTime("analysis") }}
                    </p>
                  </div>
                  <span class="display-tag">规则分析</span>
                </div>
                <div
                  class="display-condition-body"
                  tabindex="0"
                  role="region"
                  aria-label="四情诊断与复核依据"
                >
                  <div v-if="analysis" class="display-conditions">
                    <article
                      v-for="condition in analysis.conditions"
                      :key="condition.code"
                    >
                      <div class="display-condition-heading">
                        <div>
                          <h3>{{ condition.name }}</h3>
                          <span>{{ condition.state }}</span>
                        </div>
                        <button
                          :aria-label="`${condition.name}：追问依据与处理步骤`"
                          @click="ask(condition)"
                        >
                          追问<AppIcon name="send" />
                        </button>
                      </div>
                      <details
                        v-if="condition.advice"
                        class="display-condition-detail"
                      >
                        <summary
                          :aria-label="`${conditionSummary(condition.advice)}，展开完整诊断与建议`"
                        >
                          {{ conditionSummary(condition.advice) }}
                        </summary>
                        <p>{{ condition.advice }}</p>
                      </details>
                      <p v-else>暂无诊断依据，请更新后复核。</p>
                    </article>
                  </div>
                  <div v-else class="display-empty">
                    {{
                      resourceStates.analysis?.loading
                        ? "正在读取农情诊断"
                        : "农情诊断暂不可用。"
                    }}
                  </div>
                  <details v-if="analysis" class="display-analysis-notes">
                    <summary>
                      复核线索与分析口径<template
                        v-if="analysis.cautions?.length"
                      >
                        · {{ analysis.cautions.length }} 项线索</template
                      >
                    </summary>
                    <div
                      v-if="analysis.cautions?.length"
                      class="display-cautions"
                    >
                      <strong>需要现场复核的线索</strong>
                      <p v-for="caution in analysis.cautions" :key="caution">
                        {{ caution }}
                      </p>
                    </div>
                    <p v-if="analysis" class="display-source-note">
                      {{ analysis.thresholdNotice }}
                    </p>
                  </details>
                </div>
              </section>
              <section class="display-trend-panel display-trend-large">
                <div class="display-section-heading">
                  <div>
                    <h2>近 7 天土壤水分走势</h2>
                    <p>缺测处断线 · {{ resourceTime("operations") }}</p>
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
              <section
                :key="`recommendations-${farmId}`"
                class="display-recommendations"
              >
                <div class="display-section-heading">
                  <div>
                    <h2>今天的生产建议</h2>
                    <p>
                      {{
                        briefing
                          ? `${briefing.reportDate} 日报快照 · 生成 ${timeText(briefing.generatedAt, { short: true })} · 执行前复核最新情况`
                          : "等待读取日报建议"
                      }}
                    </p>
                  </div>
                  <span v-if="briefing" class="display-tag">{{
                    sourceName
                  }}</span>
                </div>
                <p
                  v-if="briefing?.snapshotOutdated"
                  class="display-snapshot-note"
                >
                  生成后已有更新，请以实时监测与任务台账复核。
                </p>
                <div
                  class="display-recommendation-body"
                  tabindex="0"
                  role="region"
                  aria-label="生产建议、行动与快照依据"
                >
                  <div
                    v-if="briefing?.priorities?.length"
                    class="display-recommendation-list"
                  >
                    <article
                      v-for="item in briefing.priorities.slice(0, 4)"
                      :key="item.id"
                    >
                      <div class="display-recommendation-heading">
                        <span
                          class="display-priority"
                          :class="{ urgent: item.priority === 'HIGH' }"
                          >{{ priorityName(item.priority) }}</span
                        >
                        <h3>{{ item.title }}</h3>
                      </div>
                      <p>{{ item.recommendation }}</p>
                      <div class="display-recommendation-actions">
                        <button @click="act(item)">
                          {{ actionName(item) }}
                        </button>
                        <details
                          v-if="item.evidence?.length"
                          class="display-recommendation-evidence"
                        >
                          <summary>
                            依据 · {{ item.evidence.length }} 项
                          </summary>
                          <ul>
                            <li
                              v-for="evidence in item.evidence"
                              :key="evidence"
                            >
                              {{ evidence }}
                            </li>
                          </ul>
                        </details>
                        <span v-else class="display-evidence-empty"
                          >暂无依据</span
                        >
                      </div>
                    </article>
                  </div>
                  <p v-else class="display-muted">
                    {{
                      briefing
                        ? "暂无明确优先事项，请继续补充巡田与监测记录。"
                        : "今日建议暂不可用，请刷新重试。"
                    }}
                  </p>
                  <details
                    v-if="briefing?.dataGaps?.length"
                    class="display-briefing-gaps"
                  >
                    <summary>
                      数据缺口 · {{ briefing.dataGaps.length }} 项
                    </summary>
                    <div class="display-gap-line">
                      <strong>数据缺口</strong
                      ><span v-for="gap in briefing.dataGaps" :key="gap">{{
                        gap
                      }}</span>
                    </div>
                  </details>
                </div>
              </section>
            </div>
          </template>
        </template>
      </main>
    </div>
  </dialog>
</template>
