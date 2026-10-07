<script setup>
import { computed, ref, watch } from "vue";
import { num, sourceNames, timeText } from "./presentation";
const props = defineProps({ devices: Array, plots: Array, demo: Boolean, farmId: String });
const emit = defineEmits(["device"]);
const includeDemo = ref(props.demo);
watch(() => props.farmId, () => { includeDemo.value = props.demo; });
const visible = computed(() =>
  props.devices.filter(
    (d) =>
      d.lifecycle === "ACTIVE" &&
      (includeDemo.value || d.protocol !== "SIMULATED"),
  ),
);
const groups = computed(() =>
  [
    ...props.plots.map((p) => ({ id: p.id, name: p.name })),
    { id: null, name: "农场公共区域" },
  ]
    .map((plot) => ({
      ...plot,
      devices: visible.value.filter((d) => (d.plotId || null) === plot.id),
    }))
    .filter((plot) => plot.devices.length),
);
const selectedMetrics = [
  "SOIL_MOISTURE",
  "SOIL_MOISTURE_2",
  "SOIL_MOISTURE_3",
  "TEMPERATURE",
  "WATER_LEVEL",
  "GATE_OPENING",
  "PUMP_RUNNING",
  "PEST_COUNT",
];
function channels(device) {
  return device.channels.filter((c) => selectedMetrics.includes(c.metric));
}
const incomplete = computed(
  () => visible.value.filter((d) => d.dataQuality !== "COMPLETE").length,
);
</script>
<template>
  <section class="field-monitoring">
    <div class="section-title">
      <div>
        <h3>地块现场监测</h3>
        <p class="muted">
          {{ visible.length }} 台在用设备，{{ incomplete }}
          台有缺测或过期指标。点击设备查看历史和告警。
        </p>
      </div>
      <label class="monitor-demo-toggle"
        ><input type="checkbox" v-model="includeDemo" />包含模拟设备</label
      >
    </div>
    <p class="muted">
      分层墒情分别展示；过期读数保留采样时间。阈值采用设备中设置的范围。
    </p>
    <div class="field-monitor-grid">
      <article
        v-for="plot in groups"
        :key="plot.id || 'common'"
        class="field-monitor-plot"
      >
        <h4>{{ plot.name }}</h4>
        <button
          v-for="device in plot.devices"
          :key="device.id"
          class="field-monitor-device"
          @click="emit('device', device.id)"
        >
          <strong
            >{{ device.name }}
            <small>{{ sourceNames[device.protocol] }}</small></strong
          >
          <span
            v-for="c in channels(device)"
            :key="c.metric"
            class="field-monitor-reading"
            :class="{ muted: c.freshness !== 'FRESH' }"
          >
            <span>{{ c.name }}</span>
            <b
              >{{
                c.latest
                  ? c.metric === "PUMP_RUNNING"
                    ? Number(c.latest.value) === 1
                      ? "运行"
                      : "停止"
                    : num(c.latest.value, 1)
                  : "—"
              }}
              {{ c.unit }}</b
            >
            <small
              >{{
                c.freshness === "FRESH"
                  ? "数据新鲜"
                  : c.latest
                    ? "已过期"
                    : "等待上报"
              }}
              · {{ timeText(c.latest?.time) }}</small
            >
          </span>
          <small v-if="!channels(device).length"
            >{{ device.freshChannelCount }} /
            {{ device.channels.length }} 项指标数据新鲜</small
          >
          <span v-if="device.alertCount"
            >{{ device.alertCount }} 项告警待处理</span
          >
        </button>
      </article>
    </div>
    <p v-if="!groups.length" class="empty">
      尚无在用的现场监测设备。可新增 HTTP 上报或人工录入设备，并关联到所属地块。
    </p>
  </section>
</template>
<style scoped>
.field-monitoring {
  margin: 1.2rem 0;
}
.monitor-demo-toggle {
  display: flex;
  align-items: center;
  gap: 0.45rem;
  white-space: nowrap;
  flex-shrink: 0;
}
.monitor-demo-toggle input {
  width: 1rem;
  height: 1rem;
  margin: 0;
}
.field-monitor-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 280px), 1fr));
  gap: 1rem;
}
.field-monitor-plot {
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 1rem;
}
.field-monitor-device {
  display: flex;
  flex-direction: column;
  gap: 0.7rem;
  width: 100%;
  text-align: left;
  margin-top: 0.6rem;
}
.field-monitor-reading {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 0.3rem;
  width: 100%;
}
.field-monitor-reading small {
  grid-column: 1 / -1;
}
</style>
