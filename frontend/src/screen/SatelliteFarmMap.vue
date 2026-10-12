<script setup>
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from "vue";
import L from "leaflet";
import "leaflet/dist/leaflet.css";
import { AuthenticatedTileLayer } from "../workspace/AuthenticatedTileLayer.js";
import { tokenColor } from "../ui/tokens.js";
import { numberText } from "./screen-model.js";
import "./satellite-map.css";

const props = defineProps({
  geometry: { type: Object, required: true },
  selectedPlot: String,
  loading: Boolean,
});
const emit = defineEmits(["plot", "device", "configure"]);
const host = ref(null),
  status = ref("loading"),
  slow = ref(false);
let map,
  tiles,
  features,
  observer,
  timer,
  mounted = false,
  alive = true;
let autoFit = true;
let parcelLayers = [],
  activeShapeId = null;
let generation = 0,
  loaded = 0,
  failed = 0,
  previousLocation;
const reducedMotion = () =>
  window.matchMedia("(prefers-reduced-motion: reduce)").matches;
const paint = (token) => tokenColor(`var(${token})`, host.value);
const warning = computed(() => ["partial", "failed"].includes(status.value));
const statusText = computed(() => {
  if (status.value === "ready") return "卫星影像已加载 · 非实时画面";
  if (status.value === "partial") return "部分卫星影像未加载，请重试";
  if (status.value === "failed") return "卫星影像连接失败，请检查网络后重试";
  return slow.value ? "影像连接较慢，正在等待响应…" : "正在加载卫星影像…";
});
function textLabel(text) {
  const label = document.createElement("span");
  label.textContent = text;
  return label;
}
function activate(element, label, action) {
  if (!element) return;
  element.setAttribute("role", "button");
  element.setAttribute("tabindex", "0");
  element.setAttribute("aria-label", label);
  L.DomEvent.on(element, "keydown", (event) => {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      event.stopPropagation();
      action();
    }
  });
}
function resetView(animate = true) {
  if (!map || !props.geometry.bounds.length) return;
  if (!host.value.clientWidth || !host.value.clientHeight) return;
  if (animate) autoFit = true;
  map.invalidateSize({ pan: false });
  map.fitBounds(props.geometry.bounds, {
    padding: [28, 28],
    maxZoom: 18,
    animate: animate && !reducedMotion(),
    duration: 0.35,
  });
}
function updateLabels() {
  if (!map || !parcelLayers.length) return;
  const selected =
    parcelLayers.find(
      ({ shape }) =>
        shape.id === activeShapeId && shape.plotId === props.selectedPlot,
    ) || parcelLayers.find(({ shape }) => shape.plotId === props.selectedPlot);
  const occupied = [];
  for (const entry of [...parcelLayers].sort(
    (a, b) => Number(b === selected) - Number(a === selected),
  )) {
    const { shape, layer } = entry;
    const label = textLabel(shape.name);
    const measure = document.createElement("div");
    measure.className =
      "leaflet-tooltip satellite-parcel-label satellite-label-measure";
    measure.appendChild(label.cloneNode(true));
    host.value.appendChild(measure);
    const { width, height } = measure.getBoundingClientRect();
    measure.remove();
    const points = shape.boundary.map((point) =>
      map.latLngToContainerPoint(point),
    );
    const xs = points.map((point) => point.x),
      ys = points.map((point) => point.y);
    const center = map.latLngToContainerPoint(layer.getCenter());
    const box = {
      left: center.x - width / 2 - 3,
      right: center.x + width / 2 + 3,
      top: center.y - height / 2 - 3,
      bottom: center.y + height / 2 + 3,
    };
    const fitsParcel =
      Math.max(...xs) - Math.min(...xs) >= width + 8 &&
      Math.max(...ys) - Math.min(...ys) >= height + 6;
    const fitsViewport =
      box.left >= 0 &&
      box.top >= 0 &&
      box.right <= host.value.clientWidth &&
      box.bottom <= host.value.clientHeight;
    const overlaps = occupied.some(
      (other) =>
        box.left < other.right &&
        box.right > other.left &&
        box.top < other.bottom &&
        box.bottom > other.top,
    );
    const permanent =
      entry === selected || (fitsParcel && fitsViewport && !overlaps);
    if (permanent) occupied.push(box);
    layer.unbindTooltip().bindTooltip(label, {
      permanent,
      direction: "center",
      className: "satellite-parcel-label",
    });
    if (layer.getElement() === document.activeElement) layer.openTooltip();
  }
}
function renderFeatures() {
  if (!map || !features) return;
  const focused = host.value.contains(document.activeElement)
    ? document.activeElement
    : null;
  const focusedParcel = focused?.getAttribute("data-parcel-id"),
    focusedDevice = focused?.getAttribute("data-device-id");
  features.clearLayers();
  parcelLayers = [];
  for (const [index, shape] of props.geometry.shapes.entries()) {
    const selected = shape.plotId === props.selectedPlot;
    const area = L.polygon(shape.boundary, {
      color: paint(selected ? "--display-gold" : "--display-leaf"),
      fillColor: paint(`--display-crop-${index % 4}`),
      fillOpacity: selected ? 0.23 : 0.06,
      weight: selected ? 3 : 1.5,
      className: `satellite-parcel${selected ? " selected" : ""}`,
    }).addTo(features);
    parcelLayers.push({ shape, layer: area });
    const select = () => {
      activeShapeId = shape.id;
      emit("plot", shape.plotId);
      updateLabels();
    };
    area.on("click", select);
    const element = area.getElement();
    element?.setAttribute("data-plot-id", shape.plotId);
    element?.setAttribute("data-parcel-id", shape.id);
    element?.setAttribute("aria-pressed", String(selected));
    if (element) {
      L.DomEvent.on(element, "focus", () => area.openTooltip());
      L.DomEvent.on(element, "blur", () => {
        if (!area.getTooltip()?.options.permanent) area.closeTooltip();
      });
    }
    activate(
      element,
      `${shape.name}，${shape.crop || "未填写作物"}${shape.estimated ? "，估绘田块" : `，${numberText(shape.areaMu)}亩`}`,
      select,
    );
  }
  for (const device of props.geometry.devices) {
    const dot = L.circleMarker(device.position, {
      radius: 6,
      color: paint("--display-map"),
      weight: 2,
      fillColor: paint(device.alertCount ? "--display-gold" : "--display-leaf"),
      fillOpacity: 1,
      className: `satellite-device${device.alertCount ? " warning" : ""}`,
    }).addTo(features);
    dot.bindTooltip(
      textLabel(
        `${device.name}${device.alertCount ? " · 存在未关闭预警" : ""}`,
      ),
      {
        direction: "top",
        className: "satellite-device-label",
      },
    );
    const select = () => emit("device", device.id);
    dot.on("click", select);
    dot.getElement()?.setAttribute("data-device-id", device.id);
    activate(
      dot.getElement(),
      `查看设备 ${device.name}${device.alertCount ? "，存在未关闭预警" : ""}`,
      select,
    );
  }
  const selector = focusedDevice
    ? `[data-device-id="${CSS.escape(focusedDevice)}"]`
    : focusedParcel
      ? `[data-parcel-id="${CSS.escape(focusedParcel)}"]`
      : null;
  if (selector)
    host.value.querySelector(selector)?.focus({ preventScroll: true });
  updateLabels();
}
function removeMap() {
  generation++;
  clearTimeout(timer);
  map?.remove();
  map = tiles = features = null;
  parcelLayers = [];
}
function initialize() {
  if (
    !alive ||
    !mounted ||
    !host.value ||
    !props.geometry.bounds.length ||
    !host.value.clientWidth ||
    !host.value.clientHeight
  )
    return;
  removeMap();
  autoFit = true;
  const ticket = generation;
  status.value = "loading";
  slow.value = false;
  map = L.map(host.value, {
    minZoom: 3,
    maxZoom: 20,
    zoomControl: false,
    scrollWheelZoom: false,
    zoomAnimation: !reducedMotion(),
    fadeAnimation: !reducedMotion(),
  });
  L.control
    .zoom({
      position: "topright",
      zoomInTitle: "放大卫星地图",
      zoomOutTitle: "缩小卫星地图",
    })
    .addTo(map);
  L.control.scale({ imperial: false }).addTo(map);
  map.attributionControl.setPrefix(false);
  tiles = new AuthenticatedTileLayer("SATELLITE", {
    minZoom: 3,
    maxZoom: 20,
    maxNativeZoom: 19,
    keepBuffer: 0,
    updateWhenIdle: true,
    attribution:
      '<a href="https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer" target="_blank" rel="noopener">Esri World Imagery</a> © Esri, Vantor, Earthstar Geographics, GIS User Community',
  });
  tiles.on("loading", () => {
    if (!alive || ticket !== generation) return;
    status.value = "loading";
    slow.value = false;
    loaded = failed = 0;
    clearTimeout(timer);
    timer = setTimeout(() => {
      if (alive && ticket === generation && status.value === "loading")
        slow.value = true;
    }, 6000);
  });
  tiles.on("tileload", () => {
    if (alive && ticket === generation) loaded++;
  });
  tiles.on("tileerror", () => {
    if (alive && ticket === generation) failed++;
  });
  tiles.on("load", () => {
    if (!alive || ticket !== generation) return;
    clearTimeout(timer);
    slow.value = false;
    status.value = failed ? (loaded ? "partial" : "failed") : "ready";
  });
  features = L.layerGroup().addTo(map);
  map.on("zoomend moveend", updateLabels);
  resetView(false);
  tiles.addTo(map);
  renderFeatures();
  previousLocation = props.geometry.locationKey;
}
function refreshGeometry() {
  if (!props.geometry.bounds.length) {
    removeMap();
    return;
  }
  if (!map) initialize();
  else {
    renderFeatures();
    if (previousLocation !== props.geometry.locationKey) {
      previousLocation = props.geometry.locationKey;
      if (autoFit) resetView(false);
    }
  }
}
function retry() {
  if (tiles) tiles.redraw();
  else initialize();
}
function userMovesMap() {
  autoFit = false;
}
function resizeMap() {
  if (!map) initialize();
  else if (autoFit) resetView(false);
  else map.invalidateSize({ pan: false });
  updateLabels();
}
watch(() => props.geometry, refreshGeometry, { deep: true, flush: "post" });
watch(
  () => props.selectedPlot,
  () => {
    renderFeatures();
    const shape = props.geometry.shapes.find(
      (shape) => shape.plotId === props.selectedPlot,
    );
    if (
      map &&
      shape &&
      !map.getBounds().contains(L.latLngBounds(shape.boundary))
    ) {
      map.fitBounds(shape.boundary, {
        padding: [45, 45],
        maxZoom: 18,
        animate: !reducedMotion(),
        duration: 0.35,
      });
    }
  },
);
onMounted(async () => {
  mounted = true;
  await nextTick();
  if (!alive) return;
  initialize();
  // Dialog opening and grid layout can expose a zero-size host first. Fit again
  // when it acquires its real size, until the user chooses their own view.
  observer = new ResizeObserver(resizeMap);
  observer.observe(host.value);
  host.value.addEventListener("pointerdown", userMovesMap);
  host.value.addEventListener("keydown", userMovesMap);
});
onBeforeUnmount(() => {
  alive = false;
  observer?.disconnect();
  host.value?.removeEventListener("pointerdown", userMovesMap);
  host.value?.removeEventListener("keydown", userMovesMap);
  removeMap();
});
</script>

<template>
  <div
    class="display-map display-satellite-map"
    :data-map-state="geometry.bounds.length ? status : 'unlocated'"
  >
    <div
      ref="host"
      class="display-satellite-canvas"
      aria-label="农场卫星地图，可使用方向键移动和加减号缩放"
    />
    <template v-if="geometry.bounds.length">
      <div
        class="satellite-status"
        :class="{ warning }"
        role="status"
        aria-live="polite"
      >
        <span class="satellite-status-mark" aria-hidden="true" />
        <span>{{ statusText }}</span>
        <button v-if="warning || slow" @click="retry">重试影像</button>
      </div>
      <button class="satellite-reset" @click="resetView()">复位视野</button>
      <slot />
    </template>
    <div v-else class="satellite-unlocated">
      <strong>{{ loading ? "正在读取农场位置" : "农场位置暂不可用" }}</strong>
      <p>需要有效的农场定位或已保存的 WGS84 田块、设备坐标。</p>
      <button @click="emit('configure')">进入农场工作台</button>
    </div>
  </div>
</template>
