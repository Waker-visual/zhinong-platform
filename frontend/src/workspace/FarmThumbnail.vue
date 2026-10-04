<script setup>
import { cropColor } from "./presentation";
defineProps({ plots: { type: Array, default: () => [] }, name: String });
</script>
<template>
  <svg
    viewBox="0 0 1000 700"
    class="farm-thumbnail"
    role="img"
    :aria-label="name + '平面预览'"
  >
    <rect class="thumb-land" width="1000" height="700" />
    <path class="thumb-road" d="M0 340H1000M340 0V700M625 0V700" stroke-width="20" />
    <path
      class="thumb-river"
      d="M952 0Q900 220 952 440T940 700"
      stroke-width="34"
      fill="none"
    />
    <template v-if="plots.some((p) => p.boundary?.length)">
      <polygon
        v-for="p in plots.filter((p) => p.boundary?.length)"
        :key="p.id"
        class="thumb-plot"
        :points="p.boundary.map((v) => v.join(',')).join(' ')"
        :style="{ fill: cropColor(p.crop) }"
        stroke-width="7"
      />
    </template>
    <template v-else>
      <path
        class="thumb-plot thumb-plot-empty"
        d="M75 105L302 120L296 294L80 280Z M370 110L585 110L580 293L376 290Z M80 397L300 397L296 590L85 578Z"
        stroke-width="7"
      />
      <text class="thumb-label" x="640" y="500" font-size="40">待绘制地块</text>
    </template>
    <path
      class="thumb-north"
      d="M48 45V115M26 70L48 45L70 70"
      stroke-width="6"
      fill="none"
    />
    <text class="thumb-label" x="40" y="150" font-size="25">N*</text>
  </svg>
</template>
<style scoped>
/* 缩略图配色取自令牌：燕麦色底、湖蓝水系、作物用模块色，随深浅主题变化 */
.thumb-land {
  fill: var(--hero-bg);
}
.thumb-road,
.thumb-plot {
  stroke: var(--surface);
}
.thumb-river {
  stroke: var(--module-irrigation);
}
.thumb-plot-empty {
  fill: var(--module-planting);
}
.thumb-label {
  fill: var(--label-fg);
}
.thumb-north {
  stroke: var(--text-2);
}
</style>
