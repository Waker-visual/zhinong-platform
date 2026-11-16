<script setup>
import { computed, ref, useId } from "vue";
import { cropPatternMarkup, readDiagramStyle, saveDiagramStyle } from "../workspace/pastoral-map.js";
import "../workspace/pastoral-map.css";
const pastoral = ref(readDiagramStyle());
const textureId = 'route-' + useId().replaceAll(':', '');
function styleMap(value) { pastoral.value = value; saveDiagramStyle(value); }
const props = defineProps({ boundary: Array, points: Array });
const shape = computed(() => {
  const all = [...(props.boundary || []), ...(props.points || [])];
  if (!all.length) return null;
  const lat = all.reduce((s, p) => s + p[0], 0) / all.length;
  const x = (p) => p[1] * Math.cos((lat * Math.PI) / 180),
    y = (p) => -p[0];
  const minX = Math.min(...all.map(x)),
    minY = Math.min(...all.map(y));
  const scale = Math.min(
    288 / (Math.max(...all.map(x)) - minX || 1),
    188 / (Math.max(...all.map(y)) - minY || 1),
  );
  const width = (Math.max(...all.map(x)) - minX) * scale;
  const height = (Math.max(...all.map(y)) - minY) * scale;
  const point = (p) => [16 + (288 - width) / 2 + (x(p) - minX) * scale, 16 + (188 - height) / 2 + (y(p) - minY) * scale];
  const route = (props.points || []).map(point);
  return {
    boundary: (props.boundary || []).map((p) => point(p).join(",")).join(" "),
    route: route.map((p) => p.join(",")).join(" "),
    start: route[0],
    end: route.at(-1),
  };
});
</script>
<template>
  <figure class="route-figure" :class="{ 'pastoral-map': pastoral }" v-if="shape">
    <div class="map-style-switch" role="group" aria-label="路线图风格"><button :aria-pressed="pastoral" @click="styleMap(true)">田园插画</button><button :aria-pressed="!pastoral" @click="styleMap(false)">普通示意</button></div>
    <svg
      viewBox="0 0 320 220"
      role="img"
      aria-label="估绘田块与计划路线，非实际轨迹"
    >
      <defs v-html="cropPatternMarkup(textureId, '')" /><polygon :points="shape.boundary" class="route-boundary" :class="{ 'pastoral-parcel': pastoral }" :style="pastoral ? { fill: `url(#${textureId})` } : null" />
      <polyline :points="shape.route" class="route-path" />
      <g v-if="shape.start">
        <circle
          :cx="shape.start[0]"
          :cy="shape.start[1]"
          r="5"
          class="route-start"
        />
        <text :x="Math.min(shape.start[0] + 7, 298)" :y="shape.start[1] - 6">
          起
        </text>
      </g>
      <g v-if="shape.end">
        <circle :cx="shape.end[0]" :cy="shape.end[1]" r="5" class="route-end" />
        <text :x="Math.min(shape.end[0] + 7, 298)" :y="shape.end[1] - 6">
          终
        </text>
      </g>
    </svg>
    <figcaption>估绘田块 · 计划路线示意 · 非实机轨迹</figcaption>
  </figure>
</template>
