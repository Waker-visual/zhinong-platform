<script setup>
// 今日农场首屏的地块示意：按台账地块的边界绘制，地块上挂着未结束的农事与现场问题。
// 画的是登记地块（/farm-workspaces 的 plots），不是农场地图里的小田块。
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { cropColor } from "../workspace/presentation";
import { centroidOf, plotSignals, viewBoxFor } from "./plot-overview-model.js";

const props = defineProps({
  plots: { type: Array, default: () => [] },
  tasks: { type: Array, default: () => [] },
  issues: { type: Array, default: () => [] },
  today: { type: String, default: "" },
  farmName: { type: String, default: "" },
  loading: Boolean,
  // 农事与问题尚未读到（读取中或读取失败）时为 false：只画地块，不对待办下结论。
  ready: { type: Boolean, default: true },
});
const emit = defineEmits(["map"]);

const rows = computed(() => plotSignals(props.plots, props.tasks, props.issues, props.today));
const drawn = computed(() =>
  rows.value
    .map((row, index) => ({ ...row, index, center: centroidOf(row.boundary) }))
    .filter((row) => row.boundary.length >= 3),
);
const box = computed(() => viewBoxFor(drawn.value));
// 地块整体偏横向时示意图在上、清单在下；偏高时左右并排，画布不留大片空白。
const wide = computed(() => !!box.value && box.value.width / box.value.height >= 1.25);
// 标记按画布实际尺寸换算，无论取景范围多大都保持 12px 半径。
const canvas = ref(null), canvasSize = ref({ width: 0, height: 0 });
let observer;
watch(canvas, (element) => {
  observer?.disconnect();
  if (!element || typeof ResizeObserver === "undefined") return;
  observer = new ResizeObserver(([entry]) => {
    canvasSize.value = { width: entry.contentRect.width, height: entry.contentRect.height };
  });
  observer.observe(element);
});
onBeforeUnmount(() => observer?.disconnect());
const markerRadius = computed(() => {
  if (!box.value) return 0;
  const { width, height } = canvasSize.value;
  if (!width || !height) return Math.max(box.value.width, box.value.height) * 0.03;
  return 12 / Math.min(width / box.value.width, height / box.value.height);
});
const active = ref("");
const summary = computed(() =>
  rows.value
    .map((row) => `${row.name}${row.crop ? "，" + row.crop : ""}，${row.areaMu} 亩${props.ready ? "，待办 " + row.open + " 项" : ""}`)
    .join("；"),
);
</script>

<template>
  <section class="panel plot-overview" aria-labelledby="plot-overview-title">
    <header class="plot-overview-head">
      <div>
        <h3 id="plot-overview-title">地块示意</h3>
        <small>按台账地块绘制的示意图，非测绘成果</small>
      </div>
      <button type="button" class="text-button" @click="emit('map')">打开农场地图 →</button>
    </header>
    <div v-if="loading && !rows.length" class="plot-overview-body" aria-hidden="true">
      <span class="skeleton skeleton-block plot-overview-canvas"></span>
      <div class="plot-overview-list">
        <span v-for="n in 3" :key="n" class="skeleton" :style="{ width: 70 + n * 8 + '%' }"></span>
      </div>
    </div>
    <div v-else-if="rows.length" :class="['plot-overview-body', { wide }]">
      <svg
        v-if="box"
        ref="canvas"
        class="plot-overview-canvas"
        :viewBox="`${box.x} ${box.y} ${box.width} ${box.height}`"
        preserveAspectRatio="xMidYMid meet"
        role="img"
        :aria-label="`${farmName}地块示意：${summary}`"
      >
        <g
          v-for="row in drawn"
          :key="row.id"
          :class="['plot-shape', ready ? row.tone : '', { active: active === row.id }]"
          @mouseenter="active = row.id"
          @mouseleave="active = ''"
        >
          <polygon
            :points="row.boundary.map((point) => point.join(',')).join(' ')"
            :style="{ fill: cropColor(row.crop) }"
          />
          <g :transform="`translate(${row.center[0]} ${row.center[1]})`">
            <circle :r="markerRadius" />
            <text :font-size="markerRadius * 1.15" dy="0.36em">{{ row.index + 1 }}</text>
          </g>
        </g>
      </svg>
      <div v-else class="plot-overview-canvas plot-overview-undrawn">
        <p>地块还没有绘制边界</p>
        <button type="button" class="outline" @click="emit('map')">去农场地图绘制</button>
      </div>
      <ol class="plot-overview-list">
        <li
          v-for="(row, index) in rows"
          :key="row.id"
          :class="{ active: active === row.id }"
          @mouseenter="active = row.id"
          @mouseleave="active = ''"
        >
          <span class="plot-index" :style="{ background: cropColor(row.crop) }" aria-hidden="true">{{ index + 1 }}</span>
          <div class="plot-name">
            <b>{{ row.name }}</b>
            <small>{{ row.crop || "作物待填写" }} · {{ row.areaMu }} 亩</small>
          </div>
          <div v-if="ready" class="plot-flags">
            <span v-if="row.overdue" class="daily-status danger">逾期 {{ row.overdue }}</span>
            <span v-if="row.blocked" class="daily-status caution">受阻 {{ row.blocked }}</span>
            <span v-if="row.issues" class="daily-status warning">问题 {{ row.issues }}</span>
            <span v-if="!row.overdue && !row.blocked && !row.issues" class="plot-quiet">
              {{ row.open ? `待办 ${row.open}` : "无待办" }}
            </span>
          </div>
        </li>
      </ol>
    </div>
    <div v-else class="plot-overview-empty empty-state">
      <p>这座农场还没有地块。</p>
      <slot name="empty-action" />
    </div>
  </section>
</template>

<style scoped>
.plot-overview {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  min-width: 0;
}
.plot-overview-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
}
.plot-overview-head h3 {
  font: var(--text-title);
}
.plot-overview-head small {
  display: block;
  font: var(--text-caption);
  color: var(--label-fg);
}
.plot-overview-head .text-button {
  flex: none;
  min-height: 24px;
  font: var(--text-small);
  font-weight: 500;
}
.plot-overview-body {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(200px, 1fr);
  gap: var(--space-5);
  align-items: stretch;
  flex: 1;
  min-height: 0;
}
.plot-overview-body.wide {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
/* 画布以 216px 为基准占用卡片的剩余高度，不按取景比例撑高卡片；
   右侧简报展开、卡片被拉长时最多长到 380px，多出的留白落在卡片底部。 */
.plot-overview-body.wide .plot-overview-canvas {
  flex: 1 1 216px;
  height: auto;
  min-height: 216px;
  max-height: 380px;
}
.plot-overview-canvas {
  width: 100%;
  height: 100%;
  min-height: 200px;
  border-radius: var(--radius);
  background: var(--subtle-bg);
}
.plot-overview-undrawn {
  display: grid;
  place-content: center;
  justify-items: center;
  gap: var(--space-3);
  color: var(--label-fg);
  font: var(--text-small);
}
.plot-shape polygon {
  stroke: var(--surface);
  stroke-width: 2px;
  stroke-linejoin: round;
  vector-effect: non-scaling-stroke;
  fill-opacity: 0.88;
  transition: fill-opacity 0.15s ease;
}
.plot-shape.active polygon {
  fill-opacity: 1;
  stroke: var(--text);
}
.plot-shape circle {
  fill: var(--surface);
  stroke: var(--border-strong);
  stroke-width: 1.5px;
  vector-effect: non-scaling-stroke;
}
.plot-shape.danger circle {
  stroke: var(--danger);
  stroke-width: 3px;
}
.plot-shape.caution circle {
  stroke: var(--caution-line);
  stroke-width: 3px;
}
.plot-shape.warning circle {
  stroke: var(--amber-line);
  stroke-width: 3px;
}
.plot-shape text {
  fill: var(--text);
  font-weight: 600;
  text-anchor: middle;
  font-variant-numeric: tabular-nums;
}
.plot-overview-list {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: var(--space-1);
  margin: 0;
  padding: 0;
  list-style: none;
}
.plot-overview-body.wide .plot-overview-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(184px, 1fr));
  align-items: start;
}
.plot-overview-body.wide .plot-overview-list li {
  padding: var(--space-2);
}
.plot-overview-body.wide .plot-flags {
  grid-column: 1 / -1;
}
.plot-overview-list li {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: var(--space-1) var(--space-3);
  align-items: center;
  padding: var(--space-3);
  border-radius: var(--radius-sm);
  transition: background-color 0.15s ease;
}
.plot-overview-list li.active {
  background: var(--subtle-bg);
}
.plot-index {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border-radius: 6px;
  color: var(--on-module);
  font: var(--text-caption);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.plot-name {
  min-width: 0;
}
.plot-name b {
  display: block;
  font: var(--text-body);
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.plot-name small {
  display: block;
  font: var(--text-caption);
  color: var(--label-fg);
}
.plot-flags {
  grid-column: 2;
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1);
}
.plot-quiet {
  font: var(--text-caption);
  color: var(--label-fg);
}
.plot-overview-empty {
  display: grid;
  flex: 1;
  place-content: center;
  justify-items: center;
  gap: var(--space-3);
  min-height: 200px;
  color: var(--label-fg);
  font: var(--text-small);
  text-align: center;
}
@media (max-width: 620px) {
  .plot-overview-body {
    grid-template-columns: 1fr;
    gap: var(--space-3);
  }
  .plot-overview-body .plot-overview-canvas,
  .plot-overview-body.wide .plot-overview-canvas {
    flex: none;
    height: 200px;
    min-height: 0;
  }
  .plot-overview-body.wide .plot-overview-list {
    grid-template-columns: 1fr;
  }
}
@media (prefers-reduced-motion: reduce) {
  .plot-shape polygon,
  .plot-overview-list li {
    transition: none;
  }
}
</style>
