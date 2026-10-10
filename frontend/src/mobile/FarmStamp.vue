<script setup>
import { computed } from "vue";
import { cropColor } from "../workspace/presentation";
const props = defineProps({ plots: { type: Array, default: () => [] }, name: String });
const shapes = computed(() => props.plots.filter(p => p.boundary?.length >= 3 && p.boundary.every(v => v.length >= 2 && v.every(Number.isFinite))));
const extent = computed(() => {
  const points = shapes.value.flatMap(p => p.boundary);
  if (!points.length) return { box: "0 0 100 80", stroke: 2 };
  const xs = points.map(p => p[0]), ys = points.map(p => p[1]);
  const x = Math.min(...xs), y = Math.min(...ys), width = Math.max(...xs) - x, height = Math.max(...ys) - y;
  const pad = Math.max(width, height, 0.000001) * .14;
  return { box: `${x - pad} ${y - pad} ${width + pad * 2} ${height + pad * 2}`, stroke: pad * .18 };
});
</script>
<template>
  <svg class="farm-stamp" :viewBox="extent.box" role="img" :aria-label="`${name}地块轮廓`">
    <polygon v-for="plot in shapes" :key="plot.id" :points="plot.boundary.map(v => v.join(',')).join(' ')" :fill="cropColor(plot.crop)" :stroke-width="extent.stroke" />
    <g v-if="!shapes.length" fill="none" stroke-width="3" stroke-linecap="round"><path d="M50 58V33M50 42C35 42 28 32 28 22C43 22 50 31 50 42M51 34C51 22 60 16 72 16C72 28 63 34 51 34M25 64Q50 49 75 64" /></g>
  </svg>
</template>
<style scoped>
.farm-stamp { background: var(--field-soft); }
.farm-stamp polygon { stroke: var(--surface); stroke-linejoin: round; }
.farm-stamp g { stroke: var(--field-brand); }
</style>
