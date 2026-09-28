<script setup>
import { onMounted, onBeforeUnmount, ref, watch } from "vue";
import * as echarts from "echarts/core";
import { PieChart, BarChart, LineChart } from "echarts/charts";
import {
  TooltipComponent,
  LegendComponent,
  GridComponent,
  DataZoomComponent,
  AriaComponent,
} from "echarts/components";
import { SVGRenderer } from "echarts/renderers";
echarts.use([
  PieChart,
  BarChart,
  LineChart,
  TooltipComponent,
  LegendComponent,
  GridComponent,
  DataZoomComponent,
  AriaComponent,
  SVGRenderer,
]);
const props = defineProps({ option: Object, label: String });
const emit = defineEmits(["select"]);
const host = ref(null);
let chart, observer;
function render() {
  if (chart && props.option)
    chart.setOption(
      {
        ...props.option,
        animationDuration: 250,
        animationDurationUpdate: 150,
        aria: { enabled: true },
      },
      true,
    );
}
onMounted(() => {
  chart = echarts.init(host.value, null, { renderer: "svg" });
  render();
  chart.on("click", (p) => emit("select", p.data));
  observer = new ResizeObserver(() => chart?.resize());
  observer.observe(host.value);
});
watch(() => props.option, render, { deep: true });
onBeforeUnmount(() => {
  observer?.disconnect();
  chart?.dispose();
  chart = null;
});
</script>
<template>
  <div ref="host" class="data-chart" role="img" :aria-label="label" />
</template>
