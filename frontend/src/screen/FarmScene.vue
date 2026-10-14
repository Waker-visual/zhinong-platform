<script setup>
import {
  computed,
  onBeforeUnmount,
  onMounted,
  onUpdated,
  ref,
  shallowRef,
  watch,
} from "vue";
import DeviceControl from "../workspace/DeviceControl.vue";
import AppIcon from "../ui/AppIcon.vue";
import { typeNames } from "../workspace/presentation.js";
import { layoutSceneMarkers, sceneMarkerIndex } from "./scene-markers.js";
import {
  sceneDeviceState,
  sceneGeometry,
  sceneZoneStates,
} from "./scene-model.js";
const props = defineProps({
  geometry: { type: Object, required: true },
  farmName: String,
  role: String,
  zones: { type: Array, default: () => [] },
  jobs: { type: Array, default: () => [] },
  spatialReadAt: String,
  selectedPlot: String,
  plots: { type: Array, default: () => [] },
});
const emit = defineEmits(["plot", "satellite", "refresh"]);
const frame = ref(null),
  ready = ref(false),
  progress = ref(0),
  error = ref(""),
  season = ref("summer"),
  projections = shallowRef([]),
  satelliteMapped = ref(false),
  selectedDevice = ref(""),
  selectedParcel = ref(""),
  clock = ref(Date.now());
const viewport = shallowRef({ width: 1000, height: 700 });
const deviceDemo = ref(
  !window.matchMedia("(prefers-reduced-motion: reduce)").matches,
);
const markerOffsets = new Map();
const device = computed(() =>
  props.geometry.devices.find((d) => String(d.id) === selectedDevice.value),
);
const states = computed(() =>
  props.geometry.devices.map((d) => {
    const point = scene.value.devices.find((p) => p.id === String(d.id));
    return {
      ...sceneDeviceState(d, clock.value),
      x: point?.x || 0,
      z: point?.z || 0,
    };
  }),
);
const deviceStatus = computed(() =>
  states.value.find((d) => d.id === selectedDevice.value),
);
const zoneStates = computed(() =>
  sceneZoneStates(
    scene.value.zones || [],
    props.jobs,
    states.value,
    props.spatialReadAt,
    clock.value,
  ),
);
function locateParcel(id) {
  const parcel = props.geometry.shapes.find(
    (p) => String(p.id || p.plotId) === id,
  );
  if (!parcel) return;
  selectedDevice.value = "";
  selectedParcel.value = id;
  emit("plot", parcel.plotId);
  send("SelectParcel", id);
}
function choosePlot(id) {
  selectedDevice.value = "";
  selectedParcel.value = "";
  emit("plot", id);
  send("SelectPlot", id);
}
function locateDevice(id) {
  selectedDevice.value = id;
  send("SelectDevice", id);
}
function resetView() {
  selectedDevice.value = "";
  send("ResetView");
}
const overlappingDevices = computed(() => {
  const selected = scene.value.devices.find(
    (d) => d.id === selectedDevice.value,
  );
  return (
    selected &&
    scene.value.devices.some(
      (d) =>
        d.id !== selected.id &&
        (d.x - selected.x) ** 2 + (d.z - selected.z) ** 2 < 4,
    )
  );
});
const markerIndex = computed(() =>
  sceneMarkerIndex(props.geometry, props.zones, states.value, zoneStates.value),
);
const markers = computed(() =>
  layoutSceneMarkers(
    projections.value,
    markerIndex.value,
    viewport.value,
    selectedDevice.value,
    markerOffsets,
  ),
);
const scene = computed(() =>
  sceneGeometry(props.geometry, props.plots, props.zones),
);
const visiblePlots = computed(() =>
  props.plots.filter((plot) =>
    scene.value.plots.some((shape) => shape.id === plot.id),
  ),
);
let timeout,
  ticker,
  resizeObserver,
  projectionFrame = 0,
  pendingProjection;
const observedPanels = new Set();
function measureViewport() {
  if (!frame.value) return;
  const bounds = frame.value.getBoundingClientRect();
  const obstacles = [
    ...frame.value.parentElement.querySelectorAll(
      ".farm-landscape-heading, .farm-landscape-plots, .farm-landscape-device, .farm-landscape-controls, .farm-landscape-summary, .farm-landscape-caption",
    ),
  ]
    .filter((element) => element.getClientRects().length)
    .map((element) => {
      const rect = element.getBoundingClientRect();
      return {
        x: rect.left - bounds.left + rect.width / 2,
        y: rect.top - bounds.top + rect.height / 2,
        width: rect.width,
        height: rect.height,
      };
    });
  const next = { width: bounds.width, height: bounds.height, obstacles };
  if (JSON.stringify(next) !== JSON.stringify(viewport.value))
    viewport.value = next;
}
onUpdated(() => {
  if (!resizeObserver || !frame.value) return;
  const panels = new Set(
    frame.value.parentElement.querySelectorAll(
      ".farm-landscape-heading, .farm-landscape-plots, .farm-landscape-device, .farm-landscape-controls, .farm-landscape-summary, .farm-landscape-caption",
    ),
  );
  let changed = false;
  for (const panel of observedPanels)
    if (!panels.has(panel)) {
      resizeObserver.unobserve(panel);
      observedPanels.delete(panel);
      changed = true;
    }
  for (const panel of panels)
    if (!observedPanels.has(panel)) {
      resizeObserver.observe(panel);
      observedPanels.add(panel);
      changed = true;
    }
  if (changed) measureViewport();
});
function send(method, value = "") {
  if (ready.value)
    frame.value?.contentWindow?.postMessage(
      { type: "farm-command", method, value },
      location.origin,
    );
}
function sync() {
  send("LoadFarm", JSON.stringify(scene.value));
  send("SelectPlot", props.selectedPlot || "");
  updateDevices();
}
function updateDevices() {
  send(
    "UpdateDevices",
    JSON.stringify({ devices: states.value, zones: zoneStates.value }),
  );
}
function receive(event) {
  if (
    event.origin !== location.origin ||
    event.source !== frame.value?.contentWindow
  )
    return;
  const message = event.data;
  if (message?.type === "farm-ready") {
    ready.value = true;
    error.value = "";
    clearTimeout(timeout);
    sync();
    send("SetSeason", season.value);
    send("SetDeviceDemo", deviceDemo.value ? "on" : "off");
  }
  if (message?.type === "farm-points" && Array.isArray(message.points)) {
    pendingProjection = message.points;
    if (!projectionFrame)
      projectionFrame = requestAnimationFrame(() => {
        projectionFrame = 0;
        projections.value = pendingProjection;
      });
    satelliteMapped.value = message.satelliteMapped === true;
  }
  if (message?.type === "farm-progress")
    progress.value = Math.round(message.value * 100);
  if (message?.type === "farm-error") {
    error.value =
      message.reason === "graphics-context"
        ? "浏览器释放了三维画面，请点击重新加载恢复。"
        : "三维场景暂时无法加载，请重试或查看卫星图。";
    ready.value = false;
    projections.value = [];
    clearTimeout(timeout);
  }
  if (
    message?.type === "farm-plot" &&
    scene.value.plots.some((plot) => plot.id === message.id)
  )
    emit("plot", message.id);
}
function retry() {
  frame.value?.contentWindow?.disposeFarmScene?.();
  error.value = "";
  ready.value = false;
  progress.value = 0;
  projections.value = [];
  markerOffsets.clear();
  cancelAnimationFrame(projectionFrame);
  projectionFrame = 0;
  frame.value.src = "/farm-scene/index.html?v=daylight-20261014-2";
  startTimeout();
}
function startTimeout() {
  clearTimeout(timeout);
  timeout = setTimeout(() => {
    if (!ready.value)
      error.value = "三维场景加载时间较长，请重试或查看卫星图。";
  }, 120000);
}
watch(
  () =>
    JSON.stringify({
      ...scene.value,
      devices: scene.value.devices.map(({ alert, x, z, ...d }) => d),
    }),
  sync,
);
watch(() => JSON.stringify([states.value, zoneStates.value]), updateDevices);
watch(
  () => props.selectedPlot,
  (id) => {
    if (
      props.geometry.shapes.find(
        (p) => String(p.id || p.plotId) === selectedParcel.value,
      )?.plotId !== id
    ) {
      selectedParcel.value = "";
      send("SelectPlot", id || "");
    }
  },
);
watch(season, (value) => send("SetSeason", value));
watch(deviceDemo, (value) => send("SetDeviceDemo", value ? "on" : "off"));
onMounted(() => {
  window.addEventListener("message", receive);
  resizeObserver = new ResizeObserver(measureViewport);
  resizeObserver.observe(frame.value);
  startTimeout();
  ticker = setInterval(() => {
    clock.value = Date.now();
  }, 1000);
});
onBeforeUnmount(() => {
  frame.value?.contentWindow?.disposeFarmScene?.();
  clearTimeout(timeout);
  clearInterval(ticker);
  cancelAnimationFrame(projectionFrame);
  resizeObserver?.disconnect();
  window.removeEventListener("message", receive);
});
</script>

<template>
  <section class="farm-landscape" aria-label="交互式三维农场">
    <iframe
      ref="frame"
      src="/farm-scene/index.html?v=daylight-20261014-2"
      title="三维农田：左键旋转，右键平移，滚轮缩放，点击田块"
    />
    <div
      v-if="ready && !error"
      class="farm-landscape-markers"
      aria-label="田块位置标记"
    >
      <svg class="farm-landscape-marker-lines" aria-hidden="true">
        <line
          v-for="marker in markers.filter((item) => item.displaced)"
          :key="marker.key"
          :x1="marker.anchorX"
          :y1="marker.anchorY"
          :x2="marker.x"
          :y2="marker.y"
        />
      </svg>
      <button
        v-for="marker in markers"
        :key="`${marker.kind}:${marker.id}`"
        :class="{
          'device-poi': marker.kind === 'device',
          'parcel-poi': marker.kind === 'parcel',
          running: marker.running,
        }"
        :aria-label="`定位${marker.name}`"
        :title="marker.name"
        :aria-pressed="
          marker.kind === 'device'
            ? selectedDevice === marker.id
            : selectedParcel === marker.id
        "
        :style="{
          width: `${marker.width}px`,
          height: `${marker.height}px`,
          transform: `translate3d(${marker.x}px, ${marker.y}px, 0) translate(-50%, -50%)`,
        }"
        @click="
          marker.kind === 'device'
            ? locateDevice(marker.id)
            : marker.kind === 'zone'
              ? locateDevice(
                  props.zones.find((z) => z.id === marker.id)?.pumpId || '',
                )
              : locateParcel(marker.id)
        "
      >
        <span>{{ marker.name }}</span
        ><small v-if="marker.label">{{ marker.label }}</small>
      </button>
    </div>
    <div class="farm-landscape-heading">
      <span class="display-tag">{{
        satelliteMapped ? "卫星影像对齐" : "三维示意"
      }}</span>
      <h2>{{ farmName || "农场" }}</h2>
      <p>
        边界跨度 {{ Math.round(scene.width || 0) }} ×
        {{ Math.round(scene.depth || 0) }} 米
      </p>
      <p v-if="geometry.missingPlots">
        {{ geometry.missingPlots }} 个地块缺少有效边界
      </p>
    </div>
    <div v-if="!ready || error" class="farm-landscape-loading" role="status">
      <strong>{{ error || `正在准备三维农田 · ${progress}%` }}</strong>
      <p v-if="!error">首次加载需要下载场景，随后即可拖动浏览。</p>
      <button v-if="error" @click="retry">重新加载</button
      ><button @click="emit('satellite')">查看卫星图</button>
    </div>
    <aside
      v-if="ready && !error"
      class="farm-landscape-plots"
      aria-label="场景地块"
    >
      <strong>田块导览</strong>
      <p v-if="!geometry.shapes.length">当前农场暂无有效田块边界。</p>
      <button
        v-for="plot in visiblePlots"
        :key="plot.id"
        :aria-pressed="selectedPlot === plot.id"
        @click="choosePlot(plot.id)"
      >
        {{ plot.name }}
      </button>
      <slot />
      <label
        >平面图小田块<select
          :value="selectedParcel"
          aria-label="场景小田块"
          @change="locateParcel($event.target.value)"
        >
          <option value="">选择小田块</option>
          <option
            v-for="parcel in geometry.shapes"
            :key="parcel.id || parcel.plotId"
            :value="String(parcel.id || parcel.plotId)"
          >
            {{ parcel.name }}
          </option>
        </select></label
      >
      <label
        >设备导览<select
          :value="selectedDevice"
          aria-label="场景设备"
          @change="locateDevice($event.target.value)"
        >
          <option value="">选择设备</option>
          <option
            v-for="item in geometry.devices"
            :key="item.id"
            :value="item.id"
          >
            {{ item.name }}
          </option>
        </select></label
      >
    </aside>
    <aside
      v-if="device && ready"
      class="farm-landscape-device"
      aria-label="设备详情"
    >
      <button
        class="device-close"
        @click="locateDevice('')"
        aria-label="关闭设备详情"
      >
        ×
      </button>
      <span class="display-tag">{{
        typeNames[device.deviceType] || "设备"
      }}</span>
      <h3>{{ device.name }}</h3>
      <p>
        {{ device.protocol === "SIMULATED" ? "模拟反馈" : "采样反馈" }} ·
        {{ deviceStatus?.label }}{{ device.alertCount ? " · 存在告警" : "" }}
      </p>
      <DeviceControl
        v-if="
          device.controlEnabled && ['PUMP', 'GATE'].includes(device.deviceType)
        "
        :key="device.id"
        :device="device"
        :role="role"
        @changed="emit('refresh')"
      />
      <p>动画为演示，设备状态以采样为准。</p>
      <p v-if="overlappingDevices">
        同点设备模型已错位展示，细线指向登记位置；登记坐标未改变。
      </p>
      <p v-if="zones.some((z) => z.pumpId === device.id)">
        地图供水节点 · 关联
        {{ zones.filter((z) => z.pumpId === device.id).length }}
        个灌溉分区。分区出水效果还需对应地图灌溉任务处于运行中。
      </p>
    </aside>
    <div class="farm-landscape-dock">
      <div
        class="farm-landscape-controls"
        role="group"
        aria-label="三维场景操作"
      >
        <label
          ><AppIcon name="plantings" /><span>作物</span>
          <select v-model="season" :disabled="!ready">
            <option value="spring">苗期示意</option>
            <option value="summer">生长期示意</option>
            <option value="autumn">成熟期示意</option>
          </select></label
        >
        <div class="farm-landscape-zoom" role="group" aria-label="场景缩放">
          <button
            :disabled="!ready"
            aria-label="放大场景"
            @click="send('Zoom', 'in')"
          >
            ＋
          </button>
          <button
            :disabled="!ready"
            aria-label="缩小场景"
            @click="send('Zoom', 'out')"
          >
            −
          </button>
        </div>
        <button :disabled="!ready" @click="resetView">
          <AppIcon name="refresh" />复位视角
        </button>
        <button
          :disabled="!ready"
          :aria-pressed="deviceDemo"
          @click="deviceDemo = !deviceDemo"
        >
          <span class="scene-motion-dot" aria-hidden="true"></span>
          {{ deviceDemo ? "动画已开" : "动画已关" }}
        </button>
        <button @click="emit('satellite')">
          <AppIcon name="overview" />卫星实景
        </button>
      </div>
      <div
        v-if="ready && !error && !device"
        class="farm-landscape-summary"
        aria-label="农场数据摘要"
      >
        <slot name="summary" />
      </div>
    </div>
    <p class="farm-landscape-caption">
      <span>左键旋转</span><span>右键平移</span><span>滚轮缩放</span
      ><span>点选田块</span>
    </p>
  </section>
</template>
