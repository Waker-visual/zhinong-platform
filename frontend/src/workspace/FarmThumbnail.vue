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
    <rect width="1000" height="700" fill="#dae8dc" />
    <path
      d="M0 340H1000M340 0V700M625 0V700"
      stroke="#eee9d9"
      stroke-width="20"
    />
    <path
      d="M952 0Q900 220 952 440T940 700"
      stroke="#aacfd0"
      stroke-width="34"
      fill="none"
    />
    <template v-if="plots.some((p) => p.boundary?.length)">
      <polygon
        v-for="p in plots.filter((p) => p.boundary?.length)"
        :key="p.id"
        :points="p.boundary.map((v) => v.join(',')).join(' ')"
        :fill="cropColor(p.crop)"
        stroke="#fbfcf0"
        stroke-width="7"
      />
    </template>
    <template v-else>
      <path
        d="M75 105L302 120L296 294L80 280Z M370 110L585 110L580 293L376 290Z M80 397L300 397L296 590L85 578Z"
        fill="#82b28e"
        stroke="#f2f5e7"
        stroke-width="7"
      />
      <text x="640" y="500" fill="#597967" font-size="40">待绘制地块</text>
    </template>
    <path
      d="M48 45V115M26 70L48 45L70 70"
      stroke="#467158"
      stroke-width="6"
      fill="none"
    />
    <text x="40" y="150" font-size="25" fill="#467158">N*</text>
  </svg>
</template>
