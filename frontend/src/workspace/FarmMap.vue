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
import { cropColor } from "./presentation";
import { tokenColor } from "../ui/tokens";
import SelectMenu from "../ui/SelectMenu.vue";
import { planToWgs84, wgs84ToPlan, assetPlanPoint } from "./coordinates";
import { deviceSvg, deviceState } from "./deviceSymbols";
import { routePrefix } from "./fieldSpatial";
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
  parcels: { type: Array, default: () => [] },
  zones: { type: Array, default: () => [] },
  jobs: { type: Array, default: () => [] },
  selectedParcel: String,
  selectedZone: String,
  selectedJob: String,
  routePreview: Object,
  routeDrawing: Boolean,
  draftRoute: { type: Array, default: () => [] },
});
const emit = defineEmits([
  "device",
  "plot",
  "save",
  "editing",
  "configure",
  "parcel",
  "zone",
  "parcel-save",
  "route-point",
]);
const parcelName = ref("");
const host = ref(null),
  // Leaflet 需要实际色值：从地图所在区域（深色地图面板）解析令牌
  paint = (value) => tokenColor(value, host.value || document.documentElement),
  editing = ref(false),
  tool = ref("select"),
  targetPlot = ref(""),
  targetDevice = ref(""),
  drawPoints = ref([]);
const layers = ref({
    plots: true,
    devices: true,
    labels: true,
    irrigation: false,
    routes: true,
  }),
  shapes = ref([]),
  positions = ref([]);
const baseRevision = ref(0),
  hint = ref("");
let map,
  plotLayer,
  deviceLayer,
  sketchLayer,
  waterLayer,
  routeLayer,
  observer,
  tileLayer,
  loadingTimer;
let generation = 0;
const viewMode = ref(props.geo?.mode || "SATELLITE"),
  mapStatus = ref("loading"),
  mapFailure = ref(""),
  loadedTiles = ref(0),
  failedTiles = ref(0),
  slowLoad = ref(false);
const geographic = computed(() => viewMode.value !== "PLAN");
function reference() {
  return (
    props.geo || {
      latitude: 47.26,
      longitude: 132.73,
      widthMeters: 1600,
      heightMeters: 1120,
    }
  );
}
const xy = (p) => {
  if (!geographic.value) return L.latLng(700 - p[1], p[0]);
  return L.latLng(planToWgs84(p, reference()));
};
const world = (p) =>
  geographic.value ? L.latLng(p) : xy(wgs84ToPlan(p[0], p[1], reference()));
const fromLatLng = (p) => {
  return (
    geographic.value
      ? wgs84ToPlan(p.lat, p.lng, reference())
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
    locationMode: d.locationMode || "LOCAL_PLAN",
    latitude: d.latitude ?? null,
    longitude: d.longitude ?? null,
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
    : { ...d, x: d.planX, y: d.planY };
  return p
    ? assetPlanPoint({ ...p, planX: p.x, planY: p.y }, reference())
    : null;
}
function placeDevice(id, point) {
  const position = positions.value.find((p) => p.deviceId === id);
  if (geographic.value || position.locationMode === "WGS84") {
    const [latitude, longitude] = planToWgs84(point, reference());
    Object.assign(position, {
      x: null,
      y: null,
      locationMode: "WGS84",
      latitude,
      longitude,
    });
  } else {
    Object.assign(position, {
      x: point[0],
      y: point[1],
      locationMode: "LOCAL_PLAN",
      latitude: null,
      longitude: null,
    });
  }
}
function clearPosition() {
  const position = positions.value.find(
    (p) => p.deviceId === targetDevice.value,
  );
  if (position)
    Object.assign(position, {
      x: null,
      y: null,
      locationMode: "LOCAL_PLAN",
      latitude: null,
      longitude: null,
    });
  tool.value = "select";
  hint.value = "点位已清除，保存后生效；设备台账和历史保留。";
  render();
}
function render() {
  if (!map) return;
  plotLayer.clearLayers();
  deviceLayer.clearLayers();
  sketchLayer.clearLayers();
  waterLayer.clearLayers();
  routeLayer.clearLayers();
  renderSpatial();
  if (layers.value.plots)
    for (const p of shownPlots.value) {
      if (!editing.value && props.parcels.some((s) => s.plotId === p.id))
        continue;
      const points = plotPoints(p);
      if (points.length < 3) continue;
      const polygon = L.polygon(points.map(xy), {
        color: paint(
          props.selectedPlot === p.id
            ? "var(--module-harvest)"
            : "var(--on-image)",
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
      const state = deviceState(d, props.jobs),
        mood = state.code;
      const marker = L.marker(xy(point), {
        zIndexOffset: props.selectedDevice === d.id ? 1000 : 0,
        draggable: editing.value,
        // 状态同时写进标题和角标形状，不只靠底色区分
        title:
          d.name +
          "，" +
          state.label +
          (d.alertCount > 0 ? "，" + d.alertCount + " 条告警" : ""),
        icon: L.divIcon({
          className:
            "asset-marker " +
            mood +
            (props.selectedDevice === d.id ? " chosen" : ""),
          html: `<span class="device-symbol">${deviceSvg(d.machinery?.kind || d.deviceType)}</span><i class="marker-badge ${mood}" aria-hidden="true">${state.badge}</i>`,
          iconSize: [36, 36],
          iconAnchor: [18, 18],
        }),
      }).addTo(deviceLayer);
      marker.bindTooltip(label(`${d.name} · ${state.label}`), {
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
        placeDevice(d.id, p);
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
function renderSpatial() {
  if (layers.value.plots && !editing.value)
    for (const p of props.parcels) {
      if (props.cropFilter && p.crop !== props.cropFilter) continue;
      const selected = p.id === props.selectedParcel;
      const active = props.jobs.some(
        (j) => j.parcelId === p.id && j.status === "RUNNING",
      );
      const style = {
        color: paint(selected ? "var(--module-harvest)" : "var(--on-image)"),
        weight: selected ? 3 : 1.5,
        fillColor: paint("var(--module-planting)"),
        fillOpacity: selected ? 0.22 : 0.08,
        dashArray: active ? "7 4" : null,
        pane: "field-parcels",
      };
      const polygon = L.polygon(p.boundary.map(world), style).addTo(plotLayer);
      if (layers.value.labels)
        polygon.bindTooltip(label(`${p.name}${active ? " · 作业中" : ""}`), {
          permanent: true,
          direction: "center",
          offset: [0, -25],
          className: "plan-plot-label field-parcel-label",
        });
      polygon.on("mouseover", () =>
        polygon.setStyle({ weight: 3, fillOpacity: 0.25 }),
      );
      polygon.on("mouseout", () => polygon.setStyle(style));
      polygon.on("click", () => {
        if (!props.routeDrawing) emit("parcel", p.id);
      });
      const el = polygon.getElement();
      el?.setAttribute("data-parcel-id", p.id);
      el?.setAttribute("data-plot-id", p.plotId);
      el?.setAttribute("aria-label", `估绘田块 ${p.name}`);
      el?.setAttribute("role", "button");
      el?.setAttribute("tabindex", "0");
      if (el)
        L.DomEvent.on(el, "keydown", (e) => {
          if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            emit("parcel", p.id);
          }
        });
    }
  if (layers.value.irrigation && !editing.value)
    for (const z of props.zones) {
      if (props.cropFilter && !shownPlots.value.some((p) => p.id === z.plotId))
        continue;
      const selected = z.id === props.selectedZone,
        running = props.jobs.some(
          (j) =>
            j.parcelId === z.parcelId &&
            j.kind === "IRRIGATION" &&
            j.status === "RUNNING",
        );
      const blue = paint("var(--module-irrigation)");
      const area = L.polygon(z.coverage.map(world), {
        pane: "field-coverage",
        color: blue,
        weight: selected ? 2 : 0,
        fillColor: blue,
        fillOpacity: selected || running ? 0.24 : 0.05,
      }).addTo(waterLayer);
      area.on("click", () => emit("zone", z.id));
      area.bindTooltip(label(`${z.name} · 模拟覆盖范围`));
      area.getElement()?.setAttribute("data-zone-id", z.id);
      L.polyline(z.pipeline.map(world), {
        pane: "field-pipes",
        color: blue,
        weight: selected || running ? 4 : 2,
        opacity: selected || running ? 1 : 0.55,
        interactive: false,
      }).addTo(waterLayer);
      for (const n of z.nodes) {
        const node = L.circleMarker(world(n.point), {
          pane: "field-pipes",
          radius: n.kind === "VALVE" ? 5 : 3,
          weight: 2,
          color: blue,
          fillColor: paint("var(--on-image)"),
          fillOpacity: 1,
        }).addTo(waterLayer);
        node.bindTooltip(
          label(
            `${n.kind === "VALVE" ? "模拟分区阀" : "模拟出水口"} · ${z.name}`,
          ),
        );
        node.on("click", () => emit("zone", z.id));
      }
    }
  if (layers.value.routes && !editing.value) {
    const job = props.jobs.find(
      (j) => j.id === props.selectedJob && j.kind === "MACHINERY",
    );
    const points = props.routeDrawing
      ? props.draftRoute
      : props.routePreview?.points || job?.route;
    if (props.routeDrawing)
      for (let i = 0; i < points.length; i++)
        L.circleMarker(world(points[i]), {
          pane: "field-routes",
          radius: 5,
          color: paint("var(--brand)"),
          fillOpacity: 1,
          interactive: false,
        })
          .bindTooltip(String(i + 1), { permanent: true, direction: "top" })
          .addTo(routeLayer);
    if (points?.length > 1) {
      const color = paint("var(--module-harvest)");
      L.polyline(points.map(world), {
        pane: "field-routes",
        color,
        weight: 3,
        dashArray: "7 5",
        interactive: false,
      }).addTo(routeLayer);
      if (job && !props.routePreview && !props.routeDrawing)
        L.polyline(routePrefix(points, job.progress).map(world), {
          pane: "field-routes",
          color,
          weight: 5,
          opacity: 0.85,
          interactive: false,
        }).addTo(routeLayer);
      for (const [p, text] of [
        [points[0], "起"],
        [points.at(-1), "终"],
      ])
        L.marker(world(p), {
          pane: "field-routes",
          interactive: false,
          icon: L.divIcon({
            className: "field-route-end",
            html: text,
            iconSize: [23, 23],
            iconAnchor: [11, 11],
          }),
        }).addTo(routeLayer);
      // Arrow headings are projected through Leaflet, so PLAN and geographic modes agree.
      for (let i = 1; i < points.length; i += 2) {
        const a = world(points[i - 1]),
          b = world(points[i]),
          pa = map.latLngToLayerPoint(a),
          pb = map.latLngToLayerPoint(b);
        const angle = (Math.atan2(pb.y - pa.y, pb.x - pa.x) * 180) / Math.PI;
        L.marker([(a.lat + b.lat) / 2, (a.lng + b.lng) / 2], {
          pane: "field-routes",
          interactive: false,
          icon: L.divIcon({
            className: "field-route-arrow",
            html: `<span style="transform:rotate(${angle}deg)">➤</span>`,
            iconSize: [16, 16],
            iconAnchor: [8, 8],
          }),
        }).addTo(routeLayer);
      }
    }
  }
}
function startDraw(small = false) {
  if (!targetPlot.value) {
    hint.value = "先选择要绘制的地块";
    return;
  }
  if (small && !parcelName.value.trim()) {
    hint.value = "请填写小田块名称";
    return;
  }
  tool.value = small ? "parcel" : "plot";
  drawPoints.value = [];
  hint.value = "在地图依次点击顶点，至少三个点后点击完成边界。";
  render();
}
function completeDraw() {
  if (drawPoints.value.length < 3) {
    hint.value = "至少需要三个顶点";
    return;
  }
  if (tool.value === "parcel") {
    emit("parcel-save", {
      plotId: targetPlot.value,
      name: parcelName.value.trim(),
      boundary: drawPoints.value.map((p) => planToWgs84(p, reference())),
    });
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
  if (props.routeDrawing && !editing.value) {
    const point = geographic.value
      ? [event.latlng.lat, event.latlng.lng]
      : planToWgs84(fromLatLng(event.latlng), props.geo);
    if (props.draftRoute.length < 500) emit("route-point", point);
    return;
  }
  if (!editing.value) return;
  const p = fromLatLng(event.latlng);
  if (p[0] < 0 || p[0] > 1000 || p[1] < 0 || p[1] > 700) return;
  if (tool.value === "plot" || tool.value === "parcel") {
    if (drawPoints.value.length >= 80) {
      hint.value = "最多 80 个顶点";
      return;
    }
    drawPoints.value.push(p);
    render();
  }
  if (tool.value === "place" && targetDevice.value) {
    placeDevice(targetDevice.value, p);
    tool.value = "select";
    hint.value = "设备点位已设置，保存后生效。";
    render();
  }
}
function resetView() {
  map?.invalidateSize({ pan: false });
  const all =
    props.parcels.length && !editing.value
      ? L.latLngBounds(props.parcels.flatMap((p) => p.boundary.map(world)))
      : bounds();
  for (const d of shownDevices.value) {
    const point = devicePoint(d);
    if (point) all.extend(xy(point));
  }
  map?.fitBounds(all, { padding: [32, 32] });
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
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="700"><defs><pattern id="grid" width="50" height="50" patternUnits="userSpaceOnUse"><path d="M50 0H0V50" fill="none" stroke="${paint("var(--border)")}"/></pattern></defs><rect width="1000" height="700" fill="${paint("var(--surface)")}"/><rect width="1000" height="700" fill="url(#grid)"/></svg>`;
    L.imageOverlay(
      "data:image/svg+xml;charset=utf-8," + encodeURIComponent(svg),
      bounds(),
    ).addTo(map);
  }
  plotLayer = L.layerGroup().addTo(map);
  for (const [name, z] of [
    ["field-parcels", 400],
    ["field-coverage", 410],
    ["field-pipes", 420],
    ["field-routes", 450],
  ])
    map.createPane(name).style.zIndex = z;
  waterLayer = L.layerGroup().addTo(map);
  routeLayer = L.layerGroup().addTo(map);
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
    props.parcels,
    props.zones,
    props.jobs,
    props.selectedParcel,
    props.selectedZone,
    props.selectedJob,
    props.routePreview,
    props.routeDrawing,
    props.draftRoute,
  ],
  render,
  { deep: true },
);
watch(
  () => props.selectedZone,
  (id) => {
    if (id) layers.value.irrigation = true;
  },
);
watch(
  () => props.selectedParcel,
  (id) => {
    const p = props.parcels.find((p) => p.id === id);
    if (p && map) {
      const b = L.latLngBounds(p.boundary.map(world));
      if (!map.getBounds().contains(b)) map.panTo(b.getCenter());
    }
  },
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
            irrigation: '灌溉',
            routes: '路线',
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
        /><button @click="startDraw(false)">经营地块边界</button>
        <input
          v-model="parcelName"
          placeholder="新小田块名称"
          aria-label="新小田块名称"
          maxlength="80"
        />
        <button @click="startDraw(true)">新增估绘小田块</button
        ><button
          @click="completeDraw"
          :disabled="!['plot', 'parcel'].includes(tool) || busy"
        >
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
        ><button @click="clearPosition" :disabled="!targetDevice">
          清除点位</button
        ><button
          class="primary"
          @click="emit('save', { revision: baseRevision, shapes, positions })"
          :disabled="busy || ['plot', 'parcel'].includes(tool)"
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
    <div v-if="parcels.length" class="map-data-provenance">
      <span>田块：影像/人工估绘 · WGS84 · 未实测</span
      ><span
        >模拟设备
        {{ devices.filter((d) => d.protocol === "SIMULATED").length }} 台 ·
        灌溉管网为虚构示意</span
      >
      <span v-if="routePreview || selectedJob"
        >虚线：规划路线 · 实线：模拟进度 · 未接入实际轨迹</span
      >
    </div>
    <p v-if="!plots.some((p) => p.boundary?.length)" class="map-empty-tip">
      <span class="toast-info-mark" aria-hidden="true">i</span>
      <span
        >尚未绘制地块。管理员可选择“编辑平面图”，为已建档地块绘制边界并放置设备。</span
      >
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
