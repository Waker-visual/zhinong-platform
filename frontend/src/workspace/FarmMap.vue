<script setup>
import {
  ref,
  computed,
  watch,
  onMounted,
  onBeforeUnmount,
  nextTick,
} from "vue";
import L from "leaflet";
import "leaflet/dist/leaflet.css";
import { AuthenticatedTileLayer } from "./AuthenticatedTileLayer";
import { cropColor, typeIcons, typeNames, stateNames } from "./presentation";
import { tokenColor } from "../ui/tokens";
import SelectMenu from "../ui/SelectMenu.vue";
const props = defineProps({
  geo: Object,
  plots: Array,
  devices: Array,
  selectedDevice: String,
  selectedPlot: String,
  editable: Boolean,
  revision: Number,
  busy: Boolean,
  typeFilter: String,
  cropFilter: String,
});
const emit = defineEmits(["device", "plot", "save", "editing", "configure"]);
const host = ref(null),
  // Leaflet 需要实际色值：从地图所在区域（深色地图面板）解析令牌
  paint = (value) => tokenColor(value, host.value || document.documentElement),
  editing = ref(false),
  tool = ref("select"),
  targetPlot = ref(""),
  targetDevice = ref(""),
  drawPoints = ref([]);
const layers = ref({ plots: true, devices: true, labels: true }),
  shapes = ref([]),
  positions = ref([]);
const baseRevision = ref(0),
  hint = ref("");
let map, plotLayer, deviceLayer, sketchLayer, observer, tileLayer, loadingTimer;
let generation = 0;
const viewMode = ref(props.geo?.mode || "SATELLITE"),
  mapStatus = ref("loading"),
  mapFailure = ref(""),
  loadedTiles = ref(0),
  failedTiles = ref(0),
  slowLoad = ref(false);
const geographic = computed(() => viewMode.value !== "PLAN");
function extent() {
  const g = props.geo || {
    latitude: 47.26,
    longitude: 132.73,
    widthMeters: 1600,
    heightMeters: 1120,
  };
  const dy = g.heightMeters / 111320,
    dx = g.widthMeters / (111320 * Math.cos((g.latitude * Math.PI) / 180));
  return { west: g.longitude - dx / 2, north: g.latitude + dy / 2, dx, dy };
}
const xy = (p) => {
  if (!geographic.value) return L.latLng(700 - p[1], p[0]);
  const b = extent();
  return L.latLng(b.north - (p[1] / 700) * b.dy, b.west + (p[0] / 1000) * b.dx);
};
const fromLatLng = (p) => {
  const b = extent();
  return (
    geographic.value
      ? [((p.lng - b.west) / b.dx) * 1000, ((b.north - p.lat) / b.dy) * 700]
      : [p.lng, 700 - p.lat]
  ).map((n) => Math.round(n * 100) / 100);
};
function bounds() {
  return L.latLngBounds([xy([0, 700]), xy([1000, 0])]);
}
const shownPlots = computed(() =>
  props.plots.filter((p) => !props.cropFilter || p.crop === props.cropFilter),
);
const shownDevices = computed(() =>
  props.devices.filter(
    (d) => !props.typeFilter || d.deviceType === props.typeFilter,
  ),
);
const mapModeOptions = [
  { value: "SATELLITE", label: "卫星影像" },
  { value: "STREET", label: "街道地图" },
  { value: "PLAN", label: "离线平面图" },
];
const plotOptions = computed(() => [
  { value: "", label: "选择地块" },
  ...props.plots.map((plot) => ({ value: plot.id, label: plot.name })),
]);
const deviceOptions = computed(() => [
  { value: "", label: "选择设备" },
  ...props.devices.map((device) => ({ value: device.id, label: device.name })),
]);
function label(text) {
  const node = document.createElement("span");
  node.textContent = text;
  return node;
}
function begin() {
  shapes.value = props.plots.map((p) => ({
    plotId: p.id,
    boundary: (p.boundary || []).map((point) => [...point]),
  }));
  positions.value = props.devices.map((d) => ({
    deviceId: d.id,
    x: d.planX,
    y: d.planY,
  }));
  baseRevision.value = props.revision;
  editing.value = true;
  hint.value = "选择地块绘制边界，或拖动设备标记调整位置。";
  emit("editing", true);
  render();
}
function finishEditing() {
  editing.value = false;
  tool.value = "select";
  drawPoints.value = [];
  hint.value = "";
  emit("editing", false);
  render();
}
defineExpose({ finishEditing, resetView });
function plotPoints(p) {
  return editing.value
    ? shapes.value.find((s) => s.plotId === p.id)?.boundary || []
    : p.boundary || [];
}
function devicePoint(d) {
  const p = editing.value
    ? positions.value.find((p) => p.deviceId === d.id)
    : { x: d.planX, y: d.planY };
  return p?.x == null || p?.y == null ? null : [Number(p.x), Number(p.y)];
}
function render() {
  if (!map) return;
  plotLayer.clearLayers();
  deviceLayer.clearLayers();
  sketchLayer.clearLayers();
  if (layers.value.plots)
    for (const p of shownPlots.value) {
      const points = plotPoints(p);
      if (points.length < 3) continue;
      const polygon = L.polygon(points.map(xy), {
        color: paint(
          props.selectedPlot === p.id ? "var(--module-harvest)" : "var(--on-image)",
        ),
        weight: props.selectedPlot === p.id ? 4 : 2,
        fillColor: paint(cropColor(p.crop)),
        fillOpacity: geographic.value ? 0.2 : 0.74,
      }).addTo(plotLayer);
      if (layers.value.labels)
        polygon.bindTooltip(label(p.name), {
          permanent: true,
          direction: "center",
          offset: [0, -32],
          className: "plan-plot-label",
        });
      polygon.on("click", () => {
        if (!editing.value) emit("plot", p.id);
      });
      const el = polygon.getElement();
      el?.setAttribute("data-plot-id", p.id);
      el?.setAttribute("aria-label", "地块 " + p.name);
      el?.setAttribute("role", "button");
      el?.setAttribute("tabindex", "0");
      if (el)
        L.DomEvent.on(el, "keydown", (e) => {
          if (e.key === "Enter") emit("plot", p.id);
        });
    }
  if (layers.value.devices)
    for (const d of shownDevices.value) {
      const point = devicePoint(d);
      if (!point) continue;
      const mood =
        d.alertCount > 0
          ? "alert"
          : d.freshness === "FRESH"
            ? "fresh"
            : d.freshness === "STALE"
              ? "stale"
              : "idle";
      const marker = L.marker(xy(point), {
        draggable: editing.value,
        // 状态同时写进标题和角标形状，不只靠底色区分
        title:
          d.name +
          "，" +
          stateNames[d.freshness] +
          (d.alertCount > 0 ? "，" + d.alertCount + " 条告警" : ""),
        icon: L.divIcon({
          className:
            "asset-marker " +
            mood +
            (props.selectedDevice === d.id ? " chosen" : ""),
          html:
            `<span>${typeIcons[d.deviceType] || "◉"}</span>` +
            (mood === "alert"
              ? '<i class="marker-badge alert" aria-hidden="true">!</i>'
              : mood === "stale"
                ? '<i class="marker-badge stale" aria-hidden="true">…</i>'
                : ""),
          iconSize: [36, 36],
          iconAnchor: [18, 18],
        }),
      }).addTo(deviceLayer);
      marker.bindTooltip(label(`${d.name} · ${stateNames[d.freshness]}`), {
        direction: "top",
        offset: [0, -16],
      });
      marker.on("click", () => emit("device", d.id));
      marker.on("dragend", () => {
        const p = fromLatLng(marker.getLatLng());
        if (p[0] < 0 || p[0] > 1000 || p[1] < 0 || p[1] > 700) {
          hint.value = "点位不能超出平面图范围";
          render();
          return;
        }
        Object.assign(
          positions.value.find((v) => v.deviceId === d.id),
          { x: p[0], y: p[1] },
        );
        hint.value = "点位已调整，点击保存平面图后生效。";
      });
      marker.getElement()?.setAttribute("data-device-id", d.id);
      marker.getElement()?.setAttribute("aria-label", "设备 " + d.name);
    }
  if (drawPoints.value.length) {
    L.polyline(drawPoints.value.map(xy), {
      color: paint("var(--module-harvest)"),
      dashArray: "5 5",
      weight: 3,
    }).addTo(sketchLayer);
    drawPoints.value.forEach((p) =>
      L.circleMarker(xy(p), {
        radius: 5,
        color: paint("var(--module-harvest)"),
        fillOpacity: 1,
      }).addTo(sketchLayer),
    );
  }
}
function startDraw() {
  if (!targetPlot.value) {
    hint.value = "先选择要绘制的地块";
    return;
  }
  tool.value = "plot";
  drawPoints.value = [];
  hint.value = "在地图依次点击顶点，至少三个点后点击完成边界。";
  render();
}
function completeDraw() {
  if (drawPoints.value.length < 3) {
    hint.value = "至少需要三个顶点";
    return;
  }
  shapes.value.find((s) => s.plotId === targetPlot.value).boundary =
    drawPoints.value.map((point) => [...point]);
  tool.value = "select";
  drawPoints.value = [];
  hint.value = "边界已更新，点击保存平面图后生效。";
  render();
}
function clearShape() {
  if (targetPlot.value) {
    shapes.value.find((s) => s.plotId === targetPlot.value).boundary = [];
    drawPoints.value = [];
    render();
  }
}
function clickMap(event) {
  if (!editing.value) return;
  const p = fromLatLng(event.latlng);
  if (p[0] < 0 || p[0] > 1000 || p[1] < 0 || p[1] > 700) return;
  if (tool.value === "plot") {
    if (drawPoints.value.length >= 80) {
      hint.value = "最多 80 个顶点";
      return;
    }
    drawPoints.value.push(p);
    render();
  }
  if (tool.value === "place" && targetDevice.value) {
    Object.assign(
      positions.value.find((v) => v.deviceId === targetDevice.value),
      { x: p[0], y: p[1] },
    );
    tool.value = "select";
    hint.value = "设备点位已设置，保存后生效。";
    render();
  }
}
function resetView() {
  map?.invalidateSize({ pan: false });
  map?.fitBounds(bounds(), { padding: [20, 20] });
}
function initializeMap() {
  if (!host.value) return;
  const activeGeneration = ++generation;
  clearTimeout(loadingTimer);
  map?.remove();
  tileLayer = null;
  mapStatus.value = geographic.value ? "loading" : "offline";
  mapFailure.value = "";
  loadedTiles.value = 0;
  failedTiles.value = 0;
  slowLoad.value = false;
  map = L.map(host.value, {
    crs: geographic.value ? L.CRS.EPSG3857 : L.CRS.Simple,
    minZoom: geographic.value ? 3 : -2,
    maxZoom: geographic.value ? 20 : 3,
    zoomSnap: geographic.value ? 1 : 0.25,
    attributionControl: geographic.value,
  });
  if (geographic.value) {
    const satellite = viewMode.value === "SATELLITE";
    const tiles = new AuthenticatedTileLayer(viewMode.value, {
      maxZoom: 20,
      maxNativeZoom: 19,
      keepBuffer: 0,
      updateWhenIdle: true,
      attribution: satellite
        ? "Tiles © Esri — Esri, Vantor, Earthstar Geographics, GIS User Community"
        : '© <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a> contributors',
    });
    tileLayer = tiles;
    tiles.on("loading", () => {
      if (activeGeneration !== generation) return;
      mapStatus.value = "loading";
      loadedTiles.value = 0;
      failedTiles.value = 0;
      slowLoad.value = false;
      clearTimeout(loadingTimer);
      loadingTimer = setTimeout(() => {
        if (activeGeneration === generation && mapStatus.value === "loading")
          slowLoad.value = true;
      }, 6000);
    });
    tiles.on("tileload", () => {
      if (activeGeneration === generation) loadedTiles.value++;
    });
    tiles.on("tileerror", (event) => {
      if (activeGeneration !== generation) return;
      failedTiles.value++;
      mapFailure.value = event.error?.message || "底图连接失败";
    });
    tiles.on("load", () => {
      if (activeGeneration !== generation) return;
      clearTimeout(loadingTimer);
      mapStatus.value = failedTiles.value
        ? loadedTiles.value
          ? "partial"
          : "failed"
        : "ready";
    });
    tiles.addTo(map);
    L.rectangle(bounds(), {
      color: paint("var(--module-harvest)"),
      weight: 1,
      dashArray: "6 6",
      fill: false,
      interactive: false,
    }).addTo(map);
    L.control.scale({ imperial: false }).addTo(map);
  } else {
    const svg =
      `<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="700"><defs><pattern id="grid" width="50" height="50" patternUnits="userSpaceOnUse"><path d="M50 0H0V50" fill="none" stroke="${paint("var(--border)")}"/></pattern></defs><rect width="1000" height="700" fill="${paint("var(--surface)")}"/><rect width="1000" height="700" fill="url(#grid)"/></svg>`;
    L.imageOverlay(
      "data:image/svg+xml;charset=utf-8," + encodeURIComponent(svg),
      bounds(),
    ).addTo(map);
  }
  plotLayer = L.layerGroup().addTo(map);
  deviceLayer = L.layerGroup().addTo(map);
  sketchLayer = L.layerGroup().addTo(map);
  map.on("click", clickMap);
  resetView();
  render();
}
function retryTiles() {
  if (tileLayer) {
    mapFailure.value = "";
    tileLayer.redraw();
  }
}
onMounted(async () => {
  await nextTick();
  initializeMap();
  observer = new ResizeObserver(() => map?.invalidateSize({ pan: false }));
  observer.observe(host.value);
});
watch(viewMode, initializeMap);
watch(
  () => props.geo,
  () => {
    if (!editing.value) {
      viewMode.value = props.geo?.mode || "SATELLITE";
      initializeMap();
    }
  },
  { deep: true },
);
watch(
  () => [
    props.plots,
    props.devices,
    props.selectedDevice,
    props.selectedPlot,
    props.typeFilter,
    props.cropFilter,
    layers.value,
  ],
  render,
  { deep: true },
);
watch(
  () => props.selectedDevice,
  (id) => {
    const d = props.devices.find((v) => v.id === id);
    if (d && devicePoint(d) && map) {
      const point = xy(devicePoint(d));
      if (!map.getBounds().contains(point)) map.panTo(point);
    }
  },
);
onBeforeUnmount(() => {
  generation++;
  clearTimeout(loadingTimer);
  observer?.disconnect();
  map?.remove();
  map = null;
});
</script>
<template>
  <section class="farm-map-panel" :data-load-state="mapStatus">
    <div class="map-toolbar">
      <div>
        <strong>农场实景地图</strong
        ><small>{{ geo?.locationLabel || "示例位置待校准" }}</small>
      </div>
      <div class="map-layer-controls">
        <SelectMenu
          v-model="viewMode"
          aria-label="地图底图"
          :options="mapModeOptions"
          :disabled="editing"
        />
        <button
          v-if="editable"
          class="light-button"
          :disabled="editing"
          @click="emit('configure')"
        >
          位置校准
        </button>
        <label
          v-for="(name, key) in {
            plots: '地块',
            devices: '设备',
            labels: '标签',
          }"
          :key="key"
          ><input type="checkbox" v-model="layers[key]" />{{ name }}</label
        ><button class="light-button" @click="resetView">全图</button
        ><button
          v-if="editable && !editing"
          class="light-button"
          @click="begin"
        >
          编辑平面图
        </button>
      </div>
    </div>
    <div
      class="map-network-status"
      role="status"
      :class="{
        'map-network-warning': ['failed', 'partial'].includes(mapStatus),
      }"
    >
      <span v-if="mapStatus === 'loading'"
        >{{
          slowLoad ? "底图连接较慢，正在等待服务响应…" : "正在加载底图…"
        }}
        已加载 {{ loadedTiles }} 张</span
      >
      <span v-else-if="mapStatus === 'ready'">{{
        viewMode === "SATELLITE"
          ? "卫星影像已加载 · 非实时视频"
          : "街道底图已加载 · 农田区域道路资料较少，查看实景请切换卫星影像"
      }}</span>
      <span v-else-if="mapStatus === 'offline'"
        >当前为离线布局，仅展示已保存的地块和设备，不是卫星影像。</span
      >
      <span v-else
        >{{ mapStatus === "partial" ? "部分底图未加载" : "在线底图未加载" }}：{{
          mapFailure
        }}。地块与设备数据仍可查看。</span
      >
      <div
        v-if="
          geographic && (slowLoad || ['failed', 'partial'].includes(mapStatus))
        "
        class="map-network-actions"
      >
        <button @click="retryTiles">重试底图</button>
        <button @click="viewMode = 'PLAN'" :disabled="editing">
          查看离线布局
        </button>
      </div>
      <button
        v-if="viewMode === 'STREET'"
        :disabled="editing"
        @click="viewMode = 'SATELLITE'"
      >
        查看卫星实景
      </button>
    </div>
    <div v-if="editing" class="map-editor">
      <div class="map-editor-row">
        <SelectMenu
          v-model="targetPlot"
          aria-label="绘制地块"
          :options="plotOptions"
        /><button @click="startDraw">绘制边界</button
        ><button @click="completeDraw" :disabled="tool !== 'plot'">
          完成边界</button
        ><button
          @click="
            drawPoints.pop();
            render();
          "
          :disabled="!drawPoints.length"
        >
          撤销顶点</button
        ><button @click="clearShape" :disabled="!targetPlot">清除边界</button>
      </div>
      <div class="map-editor-row">
        <SelectMenu
          v-model="targetDevice"
          aria-label="定位设备"
          :options="deviceOptions"
        /><button
          @click="
            tool = 'place';
            hint = '在地图点击设备安装位置';
          "
          :disabled="!targetDevice"
        >
          点击定位</button
        ><button
          class="primary"
          @click="emit('save', { revision: baseRevision, shapes, positions })"
          :disabled="busy || tool === 'plot'"
        >
          保存平面图</button
        ><button @click="finishEditing" :disabled="busy">取消编辑</button>
      </div>
      <p role="status">{{ hint }}</p>
    </div>
    <div
      ref="host"
      class="farm-map"
      data-testid="farm-map"
      aria-label="可缩放农场平面图"
    />
    <div class="map-legend">
      <span><i class="fresh-dot" />正常上报</span
      ><span><i class="alert-dot" />待处理告警</span
      ><span><i class="stale-dot" />超时未报</span
      ><span>{{
        geographic
          ? "WGS84 位置映射 · 卫星影像非实时视频"
          : "本地平面坐标 1000 × 700"
      }}</span>
    </div>
    <p v-if="!plots.some((p) => p.boundary?.length)" class="map-empty-tip">
      <span class="toast-info-mark" aria-hidden="true">i</span>
      <span>尚未绘制地块。管理员可选择“编辑平面图”，为已建档地块绘制边界并放置设备。</span>
    </p>
  </section>
</template>

<style scoped>
.map-network-status {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 10px 16px;
  background: var(--subtle-bg);
  color: var(--text-2);
  font-size: 12px;
  line-height: 1.65;
}
.map-network-warning {
  background: var(--amber-bg);
  color: var(--amber);
}
.map-network-actions {
  display: flex;
  gap: 12px;
}
.map-network-status button {
  border: 1px solid currentColor;
  border-radius: var(--radius-xs);
  padding: 4px 8px;
  white-space: nowrap;
}
</style>
