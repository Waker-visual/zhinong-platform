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
import { colorMode } from "../account/appearance";
import { resolveTokens } from "../ui/tokens";
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
// scope 在所在区域的明暗变化时（例如进入大屏模式）改变，用于触发按新区域重新取色
const props = defineProps({ option: Object, label: String, scope: String });
const emit = defineEmits(["select"]);
const host = ref(null);
let chart, observer;
// 坐标轴与提示框的默认配色取自令牌，各图表只需声明系列颜色
function withAxisDefaults(axis) {
  if (Array.isArray(axis)) return axis.map(withAxisDefaults);
  if (!axis) return axis;
  return {
    ...axis,
    axisLine: {
      ...axis.axisLine,
      lineStyle: { color: "var(--border-strong)", ...axis.axisLine?.lineStyle },
    },
    splitLine: {
      ...axis.splitLine,
      lineStyle: { color: "var(--border)", ...axis.splitLine?.lineStyle },
    },
  };
}
function render() {
  if (!chart || !props.option) return;
  const option = props.option;
  const style = getComputedStyle(host.value);
  chart.setOption(
    resolveTokens(
      {
        ...option,
        xAxis: withAxisDefaults(option.xAxis),
        yAxis: withAxisDefaults(option.yAxis),
        tooltip: option.tooltip && {
          backgroundColor: "var(--surface)",
          borderColor: "var(--border)",
          ...option.tooltip,
        },
        // 图表文字沿用界面字体（字母数字 Iosevka Aile，汉字思源黑体）
        textStyle: { fontFamily: style.fontFamily, ...option.textStyle },
        animationDuration: 250,
        animationDurationUpdate: 150,
        aria: { enabled: true },
      },
      style,
    ),
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
// flush: "post" 让大屏模式等外层样式先生效，再按新的明暗区域解析颜色
watch(() => props.option, render, { deep: true, flush: "post" });
watch([colorMode, () => props.scope], render, { flush: "post" });
onBeforeUnmount(() => {
  observer?.disconnect();
  chart?.dispose();
  chart = null;
});
</script>
<template>
  <div ref="host" class="data-chart" role="img" :aria-label="label" />
</template>
