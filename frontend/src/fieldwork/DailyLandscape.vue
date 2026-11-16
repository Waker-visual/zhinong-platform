<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { api, loadError } from "../api";
import FarmMap from "../workspace/FarmMap.vue";
import DataChart from "../workspace/DataChart.vue";
import SelectMenu from "../ui/SelectMenu.vue";
import { loadLandscapeData } from "./daily-landscape-data.js";
import {
  deviceSnapshot,
  metricSnapshot,
} from "../metrics/measurement-model.js";
import {
  lineOption,
  num,
  sourceNames,
  timeText,
} from "../workspace/presentation.js";
const props = defineProps({ farmId: String, revision: Number });
const emit = defineEmits(["farm"]);
const workspace = ref(null),
  geo = ref(null),
  spatial = ref(null),
  error = ref(""),
  loading = ref(false);
const parcelId = ref("");
const selected = ref(""),
  selectedDevice = ref(""),
  hours = ref(24),
  history = ref(null),
  historyError = ref(""),
  historyBusy = ref(false),
  clock = ref(Date.now());
let sequence = 0,
  historySequence = 0,
  alive = true,
  timer,
  loadingController,
  historyController;
const plots = computed(() => workspace.value?.plots || []);
const devices = computed(() =>
  (workspace.value?.devices || []).map((d) => deviceSnapshot(d, clock.value)),
);
const plot = computed(() => plots.value.find((p) => p.id === selected.value));
const plotDevices = computed(() =>
  devices.value.filter((d) => d.plotId === selected.value),
);
const selectedParcel = computed(
  () =>
    spatial.value?.parcels?.find(
      (p) => p.id === parcelId.value && p.plotId === selected.value,
    )?.id ||
    spatial.value?.parcels?.find((p) => p.plotId === selected.value)?.id ||
    "",
);
const moisture = computed(() =>
  metricSnapshot(plotDevices.value, "SOIL_MOISTURE", clock.value),
);
const temperature = computed(() =>
  metricSnapshot(plotDevices.value, "TEMPERATURE", clock.value),
);
const metrics = computed(() => [
  { label: "土壤含水率", snapshot: moisture.value },
  { label: "温度", snapshot: temperature.value },
]);
const chart = computed(() =>
  lineOption(
    history.value?.points || [],
    history.value?.metric?.unit || "%",
    false,
    {
      intervalSeconds: moisture.value.device?.intervalSeconds,
      aggregation: history.value?.aggregation,
    },
  ),
);
const plotOptions = computed(() =>
  plots.value.map((p) => ({ value: p.id, label: p.name })),
);
const rangeOptions = [
  { value: 24, label: "24 小时" },
  { value: 168, label: "7 天" },
];
function choosePlot(id) {
  selected.value = id;
  selectedDevice.value = "";
  parcelId.value = "";
}
function chooseParcel(id) {
  const parcel = spatial.value?.parcels?.find((p) => p.id === id);
  if (parcel) {
    choosePlot(parcel.plotId);
    parcelId.value = id;
  }
}
function chooseDevice(id) {
  selectedDevice.value = id;
  const device = devices.value.find((d) => d.id === id);
  if (device?.plotId) selected.value = device.plotId;
}
async function load() {
  const run = ++sequence;
  loadingController?.abort();
  loadingController = new AbortController();
  loading.value = true;
  try {
    const result = await loadLandscapeData(
      api,
      props.farmId,
      loadingController.signal,
    );
    if (!alive || run !== sequence) return;
    if (result.workspace.status === "fulfilled")
      workspace.value = result.workspace.value;
    if (result.geo.status === "fulfilled") geo.value = result.geo.value;
    if (result.spatial.status === "fulfilled")
      spatial.value = result.spatial.value;
    clock.value = Date.now();
    if (!plots.value.some((p) => p.id === selected.value))
      choosePlot(plots.value[0]?.id || "");
    error.value = Object.values(result)
      .filter(
        (r) =>
          r.status === "rejected" &&
          !r.reason?.cancelled &&
          r.reason?.name !== "AbortError",
      )
      .map((r) => loadError(r.reason) || "部分地图或监测数据暂不可用，请重试。")
      .join("；");
  } catch (e) {
    if (alive && run === sequence && !e.cancelled && e.name !== "AbortError")
      error.value = loadError(e);
  } finally {
    if (alive && run === sequence) loading.value = false;
  }
}
async function loadHistory() {
  const run = ++historySequence;
  historyController?.abort();
  historyController = new AbortController();
  history.value = null;
  historyError.value = "";
  historyBusy.value = false;
  const device = moisture.value.device;
  if (!device) return;
  historyBusy.value = true;
  try {
    const result = await api(
      `/assets/${encodeURIComponent(device.id)}/history?metric=SOIL_MOISTURE&hours=${hours.value}`,
      "GET",
      undefined,
      { signal: historyController.signal },
    );
    if (alive && run === historySequence) history.value = result;
  } catch (e) {
    if (
      alive &&
      run === historySequence &&
      !e.cancelled &&
      e.name !== "AbortError"
    )
      historyError.value = loadError(e) || "观测记录暂不可用，请稍后重试。";
  } finally {
    if (alive && run === historySequence) historyBusy.value = false;
  }
}
watch(
  () => [props.farmId, props.revision],
  ([id], previous) => {
    if (id !== previous?.[0]) {
      sequence++;
      loadingController?.abort();
      loading.value = false;
      workspace.value = geo.value = spatial.value = history.value = null;
      selected.value = selectedDevice.value = parcelId.value = error.value = "";
      historySequence++;
      historyController?.abort();
    }
    if (id) load();
  },
  { immediate: true },
);
watch(
  () => [moisture.value.device?.id, hours.value, workspace.value],
  loadHistory,
);
onMounted(() => {
  timer = setInterval(() => {
    clock.value = Date.now();
    if (!document.hidden && !loading.value && props.farmId) load();
  }, 60000);
});
onBeforeUnmount(() => {
  alive = false;
  sequence++;
  historySequence++;
  loadingController?.abort();
  historyController?.abort();
  clearInterval(timer);
});
</script>
<template>
  <section class="daily-landscape" :aria-busy="loading">
    <div v-if="error" class="error" role="alert">
      {{ error }} <button @click="load">重试地图与监测</button>
    </div>
    <div v-if="workspace" class="daily-landscape-grid">
      <FarmMap
        v-if="geo"
        :key="farmId"
        :geo="geo"
        :plots="plots"
        :devices="devices"
        :parcels="spatial?.parcels || []"
        :zones="spatial?.zones || []"
        :jobs="spatial?.jobs || []"
        :selected-plot="selected"
        :selected-parcel="selectedParcel"
        :selected-device="selectedDevice"
        @plot="choosePlot"
        @parcel="chooseParcel"
        @device="chooseDevice"
      />
      <div v-else class="panel daily-landscape-loading" role="status">
        地图位置暂不可用，请重试地图与监测。已有测点与趋势仍可查看。
      </div>
      <aside class="daily-plot-card">
        <div class="daily-plot-heading">
          <small>正在关注</small><span>{{ plots.length }} 块地</span>
        </div>
        <SelectMenu
          v-if="plots.length"
          :model-value="selected"
          @update:model-value="choosePlot"
          aria-label="当前地块"
          :options="plotOptions"
        />
        <h3>{{ plot?.crop || "等待添加地块" }}</h3>
        <p class="daily-plot-area">
          {{ plot ? num(plot.areaMu) + " 亩" : "建立地块后查看田间数据"
          }}<span>{{ plotDevices.length }} 台关联设备</span>
        </p>
        <div v-for="item in metrics" :key="item.label" class="daily-reading">
          <div>
            <small>{{ item.label }}</small
            ><span :class="{ stale: item.snapshot.state !== 'FRESH' }">{{
              item.snapshot.state === "FRESH"
                ? "有效观测"
                : item.snapshot.reason
            }}</span>
          </div>
          <strong
            >{{ num(item.snapshot.channel?.latest?.value)
            }}<small>{{ item.snapshot.channel?.unit || "" }}</small></strong
          >
          <p>
            {{ item.snapshot.device?.name || "暂无关联测点"
            }}<span v-if="item.snapshot.channel?.latest">
              ·
              {{
                sourceNames[item.snapshot.channel.latest.source] ||
                item.snapshot.channel.latest.source
              }}</span
            >
          </p>
          <time v-if="item.snapshot.channel?.latest">{{
            timeText(item.snapshot.channel.latest.time)
          }}</time>
        </div>
        <button class="outline" @click="emit('farm', farmId)">
          进入农场工作台
        </button>
        <small class="daily-plot-note"
          >读数来自标明的单个测点，不代表整块田的平均值。</small
        >
      </aside>
    </div>
    <div
      v-else-if="loading"
      class="panel daily-landscape-loading"
      role="status"
    >
      正在展开农场地图…
    </div>
    <div class="daily-insight-grid" :class="{ 'briefing-only': !workspace }">
      <section v-if="workspace" class="panel daily-trend">
        <header>
          <div>
            <h3>土壤水分趋势</h3>
            <p>
              {{ moisture.device?.name || "当前地块暂无土壤测点"
              }}<span v-if="history">
                ·
                {{
                  sourceNames[moisture.channel?.latest?.source] || "历史观测"
                }}</span
              >
            </p>
          </div>
          <SelectMenu
            v-model="hours"
            aria-label="趋势时间范围"
            :options="rangeOptions"
          />
        </header>
        <p v-if="historyBusy" class="daily-trend-empty" role="status">
          正在读取观测记录…
        </p>
        <p v-else-if="historyError" class="error" role="alert">
          {{ historyError }} <button @click="loadHistory">重试趋势</button>
        </p>
        <DataChart
          v-else-if="history?.points?.length"
          :option="chart"
          label="当前地块单个测点的土壤含水率历史趋势"
        />
        <p v-else class="daily-trend-empty">
          {{
            moisture.device
              ? "这个时间范围内没有观测记录。"
              : "为地块关联土壤监测设备后，即可查看趋势。"
          }}
        </p>
      </section>
      <div class="daily-briefing-panel"><slot /></div>
    </div>
  </section>
</template>
