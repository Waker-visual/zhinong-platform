<script setup>
import {
  computed,
  onMounted,
  onBeforeUnmount,
  ref,
  watch,
  nextTick,
} from "vue";
import { api, loadError } from "../api";
import FarmMap from "./FarmMap.vue";
import MapSettings from "./MapSettings.vue";
import SelectMenu from "../ui/SelectMenu.vue";
const geo = ref(null),
  mapSettings = ref(false);
import DataChart from "./DataChart.vue";
import DeviceEditor from "./DeviceEditor.vue";
import DeviceDetail from "./DeviceDetail.vue";
import { important, reportFailure, toast } from "../ui/feedback";
import {
  typeNames,
  typeIcons,
  sourceNames,
  stateNames,
  num,
  timeText,
  pieOption,
  statusColors,
  lineOption,
} from "./presentation";
const props = defineProps({ farmId: String, role: String, revision: Number });
const emit = defineEmits(["back"]);
const workspace = ref(null),
  catalog = ref(null),
  error = ref(""),
  busy = ref(false),
  days = ref(30),
  selectedDeviceId = ref(""),
  selectedPlotId = ref("");
const deviceType = ref(""),
  crop = ref(""),
  taskStatus = ref(""),
  metric = ref(""),
  hours = ref(24),
  history = ref(null),
  autoRefresh = ref(true),
  editingMap = ref(false);
const detailId = ref(""),
  detailMorphSource = ref(""),
  detailMorphActive = ref(false),
  editor = ref(false),
  editAsset = ref(null),
  mapRef = ref(null),
  screenRoot = ref(null),
  screenMode = ref(false),
  plans = ref([]),
  updatedAt = ref("");
let alive = true,
  loadId = 0,
  historyId = 0,
  poll;
const admin = computed(() => props.role === "ADMIN"),
  writer = computed(() => ["ADMIN", "OPERATOR"].includes(props.role));
const selectedDevice = computed(() =>
  workspace.value?.devices.find((d) => d.id === selectedDeviceId.value),
);
const selectedPlot = computed(() =>
  workspace.value?.plots.find((p) => p.id === selectedPlotId.value),
);
const selectedPlan = computed(() =>
  plans.value.find(
    (p) => p.plotId === selectedPlotId.value && p.status === "ACTIVE",
  ),
);
const filteredDevices = computed(
  () =>
    workspace.value?.devices.filter(
      (d) =>
        (!deviceType.value || d.deviceType === deviceType.value) &&
        (!selectedPlotId.value || d.plotId === selectedPlotId.value),
    ) || [],
);
const deviceTypeOptions = computed(() => [
  { value: "", label: "全部设备类型" },
  ...Object.entries(typeNames).map(([value, label]) => ({ value, label })),
]);
const cropOptions = computed(() => [
  { value: "", label: "全部作物地块" },
  ...(workspace.value?.analytics.cropArea || []).map((item) => ({
    value: item.name,
    label: item.name,
  })),
]);
const productionRangeOptions = [
  { value: 30, label: "近 30 天" },
  { value: 90, label: "近 90 天" },
  { value: 180, label: "近 180 天" },
  { value: 365, label: "近 365 天" },
];
const monitorRangeOptions = [
  { value: 24, label: "24 小时" },
  { value: 168, label: "7 天" },
  { value: 720, label: "30 天" },
];
const taskStatusOptions = computed(() => [
  { value: "", label: "全部状态" },
  ...["PENDING", "RUNNING", "COMPLETED", "CANCELLED"].map((value) => ({
    value,
    label: stateNames[value],
  })),
]);
const metricOptions = computed(
  () =>
    selectedDevice.value?.channels.map((c) => ({
      value: c.metric,
      label: `${selectedDevice.value.name} · ${c.name}`,
    })) || [],
);
const filteredTasks = computed(
  () =>
    workspace.value?.tasks.filter(
      (t) =>
        (!taskStatus.value || t.status === taskStatus.value) &&
        (!selectedPlotId.value || t.plotId === selectedPlotId.value),
    ) || [],
);
const cropsChart = computed(() =>
  pieOption(
    workspace.value?.analytics.cropArea || [],
    "作物面积 / 亩",
  ),
);
const deviceChart = computed(() =>
  pieOption(
    workspace.value?.analytics.deviceTypes || [],
    "设备分类",
  ),
);
const taskChart = computed(() =>
  pieOption(
    workspace.value?.analytics.taskStatus.map((s) => ({
      ...s,
      name: stateNames[s.code],
      itemStyle: { color: statusColors[s.code] },
    })) || [],
    "农事状态",
  ),
);
const monitorChart = computed(() =>
  lineOption(
    history.value?.points || [],
    history.value?.metric?.unit || "",
    screenMode.value,
  ),
);
const productionChart = computed(() => ({
  backgroundColor: "transparent",
  color: ["var(--amber-line)"],
  tooltip: { trigger: "axis", renderMode: "richText" },
  grid: { left: 58, right: 20, top: 28, bottom: 38 },
  xAxis: {
    type: "category",
    data: workspace.value?.analytics.productionTrend.map((p) => p.date) || [],
  },
  yAxis: {
    type: "value",
    name: "kg",
  },
  series: [
    {
      name: "登记产量",
      type: "bar",
      barMaxWidth: 32,
      itemStyle: { borderRadius: [5, 5, 0, 0] },
      data:
        workspace.value?.analytics.productionTrend.map((p) =>
          Number(p.value),
        ) || [],
    },
  ],
}));
async function load(silent = false) {
  const current = ++loadId;
  if (!silent) busy.value = true;
  try {
    const [data, config] = await Promise.all([
      api(`/farms/${props.farmId}/workspace?days=${days.value}`),
      api(`/farms/${props.farmId}/map-config`),
    ]);
    if (!alive || current !== loadId) return;
    workspace.value = data;
    if (
      !editingMap.value &&
      JSON.stringify(geo.value) !== JSON.stringify(config)
    )
      geo.value = config;
    updatedAt.value = new Date().toISOString();
    if (!selectedDeviceId.value)
      selectedDeviceId.value =
        data.devices.find((d) => d.alertCount > 0)?.id ||
        data.devices[0]?.id ||
        "";
    if (!data.devices.some((d) => d.id === selectedDeviceId.value))
      selectedDeviceId.value = "";
    error.value = "";
    if (selectedDeviceId.value) loadHistory();
  } catch (e) {
    if (alive) error.value = loadError(e);
  } finally {
    if (alive && current === loadId) busy.value = false;
  }
}
async function loadHistory() {
  const current = ++historyId;
  const d = selectedDevice.value;
  if (!d) return;
  if (!d.channels.some((c) => c.metric === metric.value))
    metric.value = d.channels[0]?.metric || "";
  if (!metric.value) return;
  try {
    const data = await api(
      `/assets/${d.id}/history?metric=${metric.value}&hours=${hours.value}`,
    );
    if (alive && current === historyId) history.value = data;
  } catch (e) {
    if (alive) error.value = loadError(e);
  }
}
function selectDevice(id) {
  selectedDeviceId.value = id;
  const d = workspace.value.devices.find((d) => d.id === id);
  selectedPlotId.value = d?.plotId || "";
  history.value = null;
  loadHistory();
}
function selectPlot(id) {
  selectedPlotId.value = selectedPlotId.value === id ? "" : id;
  const devices = filteredDevices.value;
  if (devices.length && !devices.some((d) => d.id === selectedDeviceId.value))
    selectedDeviceId.value = devices[0].id;
}
async function saveLayout(input) {
  busy.value = true;
  error.value = "";
  try {
    await api(`/farms/${props.farmId}/layout`, "PUT", input);
    mapRef.value.finishEditing();
    await load();
    important("平面图已保存");
  } catch (e) {
    reportFailure(e, () => saveLayout(input));
  } finally {
    busy.value = false;
  }
}
function newDevice() {
  editAsset.value = null;
  editor.value = true;
}
function editDevice(asset) {
  detailId.value = "";
  detailMorphSource.value = "";
  detailMorphActive.value = false;
  editAsset.value = asset;
  editor.value = true;
}
function canMorph() {
  return (
    document.startViewTransition &&
    !matchMedia("(prefers-reduced-motion: reduce)").matches
  );
}
async function openDeviceDetail(id, source) {
  if (!canMorph()) {
    detailId.value = id;
    detailMorphSource.value = "";
    detailMorphActive.value = false;
    return;
  }
  detailMorphSource.value = source;
  detailMorphActive.value = true;
  document.documentElement.classList.add("device-morphing");
  const transition = document.startViewTransition(async () => {
    detailId.value = id;
    await nextTick();
  });
  await transition.finished.catch(() => {});
  detailMorphActive.value = false;
  document.documentElement.classList.remove("device-morphing");
}
async function closeDeviceDetail() {
  if (!detailId.value || !canMorph() || !detailMorphSource.value) {
    detailId.value = "";
    detailMorphSource.value = "";
    detailMorphActive.value = false;
    return;
  }
  detailMorphActive.value = true;
  document.documentElement.classList.add("device-morphing");
  const transition = document.startViewTransition(() => {
    detailId.value = "";
  });
  await transition.finished.catch(() => {});
  detailMorphActive.value = false;
  detailMorphSource.value = "";
  document.documentElement.classList.remove("device-morphing");
}
async function savedDevice(result) {
  const created = !editAsset.value;
  editor.value = false;
  await load();
  selectDevice(result.id);
  toast(created ? "设备已创建" : "设备资料已保存");
}
async function collect() {
  if (!selectedDevice.value) return;
  busy.value = true;
  try {
    await api("/assets/" + selectedDeviceId.value + "/collect", "POST");
    await load();
    toast("模拟采集已完成");
  } catch (e) {
    reportFailure(e, collect);
  } finally {
    busy.value = false;
  }
}
async function fullScreen() {
  screenMode.value = !screenMode.value;
  try {
    if (screenMode.value && !document.fullscreenElement)
      await screenRoot.value.requestFullscreen();
    else if (document.fullscreenElement === screenRoot.value)
      await document.exitFullscreen();
  } catch {
    /* A denied browser fullscreen request still leaves a usable screen layout. */
  }
  await nextTick();
  mapRef.value?.resetView();
}
function fullscreenChanged() {
  if (!document.fullscreenElement) screenMode.value = false;
}
watch([days, () => props.revision], () => load());
watch([metric, hours], () => loadHistory());
onMounted(async () => {
  document.addEventListener("fullscreenchange", fullscreenChanged);
  try {
    const [c, p] = await Promise.all([
      api("/assets/catalog"),
      api("/plantings"),
    ]);
    if (!alive) return;
    catalog.value = c;
    plans.value = p;
    await load();
  } catch (e) {
    error.value = e.message;
  }
  poll = setInterval(() => {
    if (
      autoRefresh.value &&
      !editingMap.value &&
      !editor.value &&
      !document.hidden
    )
      load(true);
  }, 15000);
});
onBeforeUnmount(() => {
  alive = false;
  ++loadId;
  ++historyId;
  clearInterval(poll);
  document.removeEventListener("fullscreenchange", fullscreenChanged);
});
</script>
<template>
  <section
    ref="screenRoot"
    class="farm-workspace"
    :class="{ 'screen-mode': screenMode }"
    data-testid="farm-workspace"
  >
    <div class="workspace-toolbar">
      <button class="outline" @click="emit('back')">← 返回农场</button>
      <div>
        <h2>{{ workspace?.farm.name || "加载农场…" }}</h2>
        <small
          >{{ workspace?.farm.region || "农场经营工作台" }}
          <span v-if="workspace?.farm.demo" class="demo-tag"
            >虚构演示场景</span
          ></small
        >
      </div>
      <div class="inline-controls">
        <button
          v-if="screenMode && selectedDevice"
          class="outline"
          @click="detailId = selectedDevice.id"
        >
          设备详情</button
        ><label class="refresh-check"
          ><input type="checkbox" v-model="autoRefresh" />15 秒刷新</label
        ><button class="outline" @click="load()" :disabled="busy">
          刷新工作台</button
        ><button class="primary" @click="fullScreen">
          {{ screenMode ? "退出大屏" : "大屏模式" }}
        </button>
      </div>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <!-- 首次读取：指标卡、地图与侧栏的占位；地图占位同时作为封面转场的落点 -->
    <template v-if="!workspace && !error">
      <p class="sr-only" role="status">正在加载农场工作台…</p>
      <div class="workspace-stats numbers-loading" aria-hidden="true">
        <article v-for="n in 5" :key="n">
          <span class="skeleton" style="width: 50%"></span>
          <strong>0</strong>
        </article>
      </div>
      <div class="workspace-main-grid" aria-hidden="true">
        <span class="skeleton skeleton-block workspace-map-skeleton"></span>
        <div class="panel">
          <span class="skeleton" style="width: 45%; height: 16px"></span>
          <span class="skeleton" style="width: 85%"></span>
          <span class="skeleton" style="width: 70%"></span>
          <span class="skeleton skeleton-button"></span>
        </div>
      </div>
    </template>
    <template v-if="workspace">
      <div class="workspace-stats">
        <article>
          <span>经营面积</span
          ><strong
            >{{ num(workspace.analytics.summary.areaMu)
            }}<small>亩</small></strong
          ><small>{{ workspace.plots.length }} 个地块</small>
        </article>
        <article>
          <span>设备与监测</span
          ><strong>{{ workspace.devices.length }}<small>台</small></strong
          ><small
            >{{
              workspace.devices.filter((d) => d.freshness === "FRESH").length
            }}
            台正常上报</small
          >
        </article>
        <article :class="{ attention: workspace.alerts.length }">
          <span>待处理告警</span
          ><strong>{{ workspace.alerts.length }}<small>项</small></strong
          ><small>依据已配置的监测阈值</small>
        </article>
        <article>
          <span>近 {{ days }} 天登记产量</span
          ><strong
            >{{ num(workspace.analytics.summary.yieldKg)
            }}<small>kg</small></strong
          ><small>来自实际保存的生产记录</small>
        </article>
        <article>
          <span>农事完成进度</span
          ><strong
            >{{
              workspace.analytics.summary.tasks
                ? Math.round(
                    (workspace.analytics.summary.completedTasks /
                      workspace.analytics.summary.tasks) *
                      100,
                  )
                : 0
            }}<small>%</small></strong
          ><small
            >{{ workspace.analytics.summary.completedTasks }} /
            {{ workspace.analytics.summary.tasks }} 项</small
          >
        </article>
      </div>
      <details class="stat-notes">
        <summary>统计口径</summary>
        <ul>
          <li>经营面积：本农场全部地块面积之和。</li>
          <li>
            设备与监测：已登记的设备台数；“正常上报”指最近一次读数在设定上报间隔的
            3 倍以内（至少 3 分钟）。
          </li>
          <li>待处理告警：超出已配置监测阈值、尚未恢复的告警，含已确认的告警。</li>
          <li>
            近 {{ days }} 天登记产量：所选范围内实际保存的生产记录合计，不含经营模拟结果。
          </li>
          <li>农事完成进度：本农场全部农事中已完成的比例，已取消的任务计入总数。</li>
        </ul>
      </details>
      <div class="workspace-main-grid">
        <div>
          <div class="map-filters">
            <SelectMenu
              v-model="deviceType"
              aria-label="地图设备类型"
              :options="deviceTypeOptions"
            /><SelectMenu
              v-model="crop"
              aria-label="地图作物筛选"
              :options="cropOptions"
            />
            <button
              @click="
                crop = '';
                deviceType = '';
                selectedPlotId = '';
              "
            >
              清除筛选</button
            ><button
              v-if="admin"
              class="primary"
              @click="newDevice"
              :disabled="!catalog"
            >
              ＋ 新增设备
            </button>
          </div>
          <FarmMap
            :geo="geo"
            @configure="mapSettings = true"
            ref="mapRef"
            :plots="workspace.plots"
            :devices="workspace.devices"
            :selected-device="selectedDeviceId"
            :selected-plot="selectedPlotId"
            :editable="admin"
            :revision="workspace.farm.layoutRevision"
            :busy="busy"
            :type-filter="deviceType"
            :crop-filter="crop"
            @device="selectDevice"
            @plot="selectPlot"
            @save="saveLayout"
            @editing="editingMap = $event"
          />
          <section v-if="selectedPlot" class="plot-detail-strip">
            <div>
              <small>当前地块</small>
              <h3>{{ selectedPlot.name }}</h3>
              <p>
                {{ selectedPlot.crop || "未填写作物" }} ·
                {{ selectedPlot.areaMu }} 亩
              </p>
            </div>
            <div>
              <small>当前种植计划</small>
              <p>
                {{
                  selectedPlan
                    ? selectedPlan.crop +
                      " / " +
                      (selectedPlan.variety || "未填写品种")
                    : "暂无进行中的计划"
                }}
              </p>
              <small v-if="selectedPlan"
                >{{ selectedPlan.startDate }} —
                {{ selectedPlan.endDate }}</small
              >
            </div>
            <button class="outline" @click="selectedPlotId = ''">
              取消地块选择
            </button>
          </section>
        </div>
        <aside class="workspace-side">
          <section
            class="panel device-inspector"
            :style="
              detailMorphActive && detailMorphSource === 'inspector'
                ? { viewTransitionName: 'device-morph' }
                : null
            "
          >
            <div class="section-title">
              <h3>点位详情</h3>
              <span class="muted">点击地图设备切换</span>
            </div>
            <template v-if="selectedDevice"
              ><div class="device-title">
                <i>{{ typeIcons[selectedDevice.deviceType] }}</i>
                <div>
                  <h3>{{ selectedDevice.name }}</h3>
                  <small>{{ selectedDevice.code }}</small>
                </div>
              </div>
              <div class="detail-meta">
                <span
                  class="status-chip"
                  :class="selectedDevice.freshness.toLowerCase()"
                  >{{ stateNames[selectedDevice.freshness] }}</span
                ><span class="source-tag">{{
                  sourceNames[selectedDevice.protocol]
                }}</span>
              </div>
              <div class="inspector-values">
                <button
                  v-for="c in selectedDevice.channels"
                  :key="c.metric"
                  @click="metric = c.metric"
                  :class="{ selected: metric === c.metric }"
                >
                  <span>{{ c.name }}</span
                  ><strong
                    >{{ c.latest ? num(c.latest.value, 2) : "—" }}
                    <small>{{ c.unit }}</small></strong
                  >
                </button>
              </div>
              <p class="muted">
                最近上报：{{ timeText(selectedDevice.lastReceivedAt) }}
              </p>
              <div class="inline-controls">
                <button
                  class="outline"
                  @click="openDeviceDetail(selectedDevice.id, 'inspector')"
                >
                  完整详情与历史</button
                ><button
                  v-if="
                    writer &&
                    selectedDevice.protocol === 'SIMULATED' &&
                    selectedDevice.lifecycle === 'ACTIVE'
                  "
                  class="primary"
                  @click="collect"
                  :disabled="busy"
                >
                  模拟采集
                </button>
              </div></template
            >
            <p v-else class="empty">在地图或设备列表中选择设备。</p>
          </section>
          <section class="panel alert-preview">
            <div class="section-title">
              <h3>告警关注</h3>
              <span class="count">{{ workspace.alerts.length }}</span>
            </div>
            <button
              v-for="alert in workspace.alerts.slice(0, 5)"
              :key="alert.id"
              class="alert-preview-item"
              :style="
                detailMorphActive && detailMorphSource === `alert:${alert.id}`
                  ? { viewTransitionName: 'device-morph' }
                  : null
              "
              @click="openDeviceDetail(alert.deviceId, `alert:${alert.id}`)"
            >
              <strong>{{ alert.message }}</strong
              ><span
                >{{ alert.deviceName }} · {{ stateNames[alert.status] }}</span
              ><small>{{ timeText(alert.openedAt) }}</small>
            </button>
            <p v-if="!workspace.alerts.length" class="empty">
              当前没有未处理的阈值告警
            </p>
          </section>
        </aside>
      </div>
      <div class="analytics-toolbar">
        <div>
          <h3>经营与环境数据</h3>
          <small
            >点击饼图分类可筛选地图或任务列表；趋势图支持悬停与时间缩放。</small
          >
        </div>
        <label
          >生产统计范围<SelectMenu
            v-model="days"
            aria-label="生产统计范围"
            :options="productionRangeOptions"
          /></label
        >
      </div>
      <div class="analytics-grid">
        <section class="panel">
          <h3>作物面积分布</h3>
          <DataChart
            v-if="workspace.analytics.cropArea.length"
            :option="cropsChart"
            :scope="screenMode ? 'screen' : 'page'"
            label="作物面积饼图，点击筛选地块"
            @select="crop = crop === $event.name ? '' : $event.name"
          />
          <p v-else class="empty">尚未建立地块</p>
          <div class="chart-filter-chips">
            <button
              v-for="item in workspace.analytics.cropArea"
              :key="item.name"
              @click="crop = crop === item.name ? '' : item.name"
              :class="{ selected: crop === item.name }"
            >
              {{ item.name }} {{ num(item.value) }} 亩
            </button>
          </div>
        </section>
        <section class="panel">
          <h3>设备类型分布</h3>
          <DataChart
            v-if="workspace.devices.length"
            :option="deviceChart"
            :scope="screenMode ? 'screen' : 'page'"
            label="设备分类环形图"
            @select="deviceType = deviceType === $event.code ? '' : $event.code"
          />
          <p v-else class="empty">尚未添加设备</p>
        </section>
        <section class="panel">
          <h3>农事状态分布</h3>
          <DataChart
            v-if="workspace.analytics.taskStatus.length"
            :option="taskChart"
            :scope="screenMode ? 'screen' : 'page'"
            label="农事状态环形图"
            @select="taskStatus = taskStatus === $event.code ? '' : $event.code"
          />
          <p v-else class="empty">尚未安排任务</p>
        </section>
      </div>
      <div class="analytics-bottom">
        <section class="panel">
          <div class="section-title">
            <h3>收获产量趋势</h3>
            <small>按登记日期汇总 / kg</small>
          </div>
          <DataChart
            v-if="workspace.analytics.productionTrend.length"
            :option="productionChart"
            :scope="screenMode ? 'screen' : 'page'"
            label="农场产量柱状图"
          />
          <p v-else class="empty">所选范围没有生产记录</p>
          <div class="yield-ranking">
            <span
              v-for="p in workspace.analytics.productionByPlot.slice(0, 4)"
              :key="p.name"
              >{{ p.name }} <b>{{ num(p.value) }} kg</b></span
            >
          </div>
        </section>
        <section class="panel">
          <div class="section-title">
            <h3>环境监测趋势</h3>
            <SelectMenu
              v-model="hours"
              aria-label="大屏监测时间"
              :options="monitorRangeOptions"
            />
          </div>
          <SelectMenu
            v-if="selectedDevice"
            v-model="metric"
            aria-label="大屏监测指标"
            :options="metricOptions"
          />
          <DataChart
            v-if="history?.points.length"
            :option="monitorChart"
            :scope="screenMode ? 'screen' : 'page'"
            label="环境监测时间序列"
          />
          <p v-else class="empty">当前设备和时间范围内没有监测记录</p>
          <small v-if="history">{{
            history.sources
              .map((s) => sourceNames[s.source] + " " + s.count + " 条")
              .join(" · ")
          }}</small>
        </section>
      </div>
      <div class="workspace-lists">
        <section class="panel">
          <div class="section-title">
            <h3>设备点位清单</h3>
            <small>{{ filteredDevices.length }} 台</small>
          </div>
          <button
            v-for="d in filteredDevices"
            :key="d.id"
            class="device-list-row"
            :class="{ selected: selectedDeviceId === d.id }"
            :style="
              detailMorphActive && detailMorphSource === `list:${d.id}`
                ? { viewTransitionName: 'device-morph' }
                : null
            "
            @click="selectDevice(d.id)"
          >
            <span
              >{{ typeIcons[d.deviceType] }} {{ d.name
              }}<small
                >{{ d.plotName || "公共区域" }} ·
                {{ d.planX == null ? "未定位" : "已定位" }}</small
              ></span
            ><span class="status-chip" :class="d.freshness.toLowerCase()">{{
              stateNames[d.freshness]
            }}</span>
          </button>
          <p v-if="!filteredDevices.length" class="empty">当前筛选下没有设备</p>
        </section>
        <section class="panel">
          <div class="section-title">
            <h3>农事进度</h3>
            <SelectMenu
              v-model="taskStatus"
              aria-label="大屏任务状态"
              :options="taskStatusOptions"
            />
          </div>
          <div
            v-for="task in filteredTasks.slice(0, 10)"
            :key="task.id"
            class="device-list-row"
          >
            <span
              >{{ task.title
              }}<small>{{ task.plotName }} · {{ task.dueDate }}</small></span
            ><span class="status-chip">{{ stateNames[task.status] }}</span>
          </div>
          <p v-if="!filteredTasks.length" class="empty">
            当前筛选下没有农事任务
          </p>
        </section>
      </div>
      <p class="workspace-footnote">
        更新于 {{ timeText(updatedAt) }} · 上报状态按预期周期的 3 倍（最少 180
        秒）判断。{{
          workspace.farm.demo
            ? "本农场地图、设备、生产和历史监测为虚构演示数据。"
            : ""
        }}
      </p>
    </template>
    <DeviceDetail
      v-if="detailId"
      :id="detailId"
      :role="role"
      :transition-name="detailMorphActive ? 'device-morph' : ''"
      @close="closeDeviceDetail"
      @edit="editDevice"
      @changed="load(true)"
    />
    <MapSettings
      v-if="mapSettings && geo"
      :farm-id="farmId"
      :config="geo"
      @close="mapSettings = false"
      @saved="
        geo = $event;
        mapSettings = false;
        important('地图位置已保存');
      "
    />
    <DeviceEditor
      v-if="editor && workspace && catalog"
      :asset="editAsset"
      :farms="[workspace.farm]"
      :plots="workspace.plots"
      :catalog="catalog"
      :farm-id="farmId"
      @close="editor = false"
      @saved="savedDevice"
    />
  </section>
</template>
