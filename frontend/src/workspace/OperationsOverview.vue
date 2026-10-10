<script setup>
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { api, loadError } from "../api";
import AppIcon from "../ui/AppIcon.vue";
import SelectMenu from "../ui/SelectMenu.vue";
import "./operations.css";

// 农场运行概览：结构沿用磐隐运维概览——指标卡给结论，时间片健康条看趋势，流水线卡与地块矩阵看明细。
const props = defineProps({ farmId: String, revision: Number });
const emit = defineEmits(["navigate"]);
const hours = ref(168),
  data = ref(null),
  loading = ref(false),
  error = ref(""),
  readout = ref(""),
  focusIndex = ref(0);
const morphId = ref("");
const rangeOptions = [
  { value: 24, label: "近 24 小时" },
  { value: 168, label: "近 7 天" },
  { value: 720, label: "近 30 天" },
];
let sequence = 0;

async function load() {
  if (!props.farmId) return;
  const ticket = ++sequence;
  loading.value = true;
  error.value = "";
  try {
    const result = await api(
      `/farms/${encodeURIComponent(props.farmId)}/operations?hours=${hours.value}`,
    );
    if (ticket === sequence) {
      data.value = result;
      readout.value = "";
      focusIndex.value = result.slices.length - 1;
    }
  } catch (e) {
    if (ticket === sequence) error.value = loadError(e);
  } finally {
    if (ticket === sequence) loading.value = false;
  }
}
watch(() => [props.farmId, props.revision, hours.value], load, {
  immediate: true,
});
onBeforeUnmount(() => sequence++);

const summary = computed(() => data.value?.summary || {});
const slices = computed(() => data.value?.slices || []);
const observedSlices = computed(
  () => slices.value.filter((s) => s.health !== null).length,
);
// 健康度分档沿用磐隐：≥98 绿、75–98 黄褐、50–75 橙、<50 红，无样本为空色
function tone(health) {
  if (health === null || health === undefined) return "none";
  return health >= 98
    ? "green"
    : health >= 75
      ? "brown"
      : health >= 50
        ? "orange"
        : "red";
}
const moistureLine = computed(() => {
  const values = slices.value.map((s) =>
    s.moisture === null ? null : Number(s.moisture),
  );
  const present = values.filter((v) => v !== null);
  if (!present.length) return { path: "", points: [] };
  const pad = Math.max(1, (Math.max(...present) - Math.min(...present)) * 0.15);
  const lo = Math.min(...present) - pad,
    hi = Math.max(...present) + pad;
  // 短时间片里常常没有读数：相邻读数相隔不超过 3 小时就连线，更长的空档断开，不臆造中间值
  const maxGap = Math.max(1, Math.round(180 / data.value.window.sliceMinutes));
  let path = "",
    last = -Infinity;
  const points = [];
  values.forEach((v, i) => {
    if (v === null) return;
    const x = (i + 0.5) * (1000 / values.length),
      y = 48 - ((v - lo) / (hi - lo)) * 48;
    points.push({ x: x.toFixed(1), y: y.toFixed(1) });
    path +=
      i - last <= maxGap
        ? `L${x.toFixed(1)} ${y.toFixed(1)}`
        : `M${x.toFixed(1)} ${y.toFixed(1)}`;
    last = i;
  });
  return { path, points };
});
function when(value) {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
}
// 指标卡给“此刻”的结论：最后一个时间片截止到现在，与设备台账的上报状态一致
const currentHealth = computed(() => slices.value.at(-1)?.health ?? null);
const lateDevices = computed(() =>
  Math.max(
    0,
    (summary.value.activeDevices || 0) -
      (summary.value.freshDevices || 0) -
      (summary.value.silentDevices || 0),
  ),
);
const healthText = computed(() =>
  summary.value.health === null || summary.value.health === undefined
    ? "—"
    : summary.value.health + "%",
);
function sliceText(s) {
  const span = `${when(s.start)}–${when(s.end)}`;
  if (s.health === null) return `${span} · 设备尚未开始上报，无样本`;
  return (
    `${span} · 正常上报 ${s.onTime}/${s.observed} 台 · 健康 ${s.health}%` +
    ` · 土壤水分均值 ${s.moisture === null ? "—（无读数）" : s.moisture + "%"}`
  );
}
function tooltipWhen(value) {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  });
}
function sliceTooltip(s) {
  const span = `${tooltipWhen(s.start)}–${tooltipWhen(s.end)}（UTC+8）`;
  if (s.health === null) return `${span}\n设备尚未开始上报 · 无样本`;
  return `${span}\n正常上报 ${s.onTime}/${s.observed} 台 · 健康 ${s.health}%\n土壤水分均值 ${s.moisture === null ? "—（无读数）" : s.moisture + "%"}`;
}
function show(index) {
  focusIndex.value = index;
  readout.value = sliceTooltip(slices.value[index]);
}
function onSliceKey(event) {
  const last = slices.value.length - 1;
  let next = focusIndex.value;
  if (event.key === "ArrowRight") next = Math.min(next + 1, last);
  else if (event.key === "ArrowLeft") next = Math.max(next - 1, 0);
  else if (event.key === "Home") next = 0;
  else if (event.key === "End") next = last;
  else return;
  event.preventDefault();
  show(next);
  event.currentTarget.querySelectorAll(".ops-slice")[next]?.focus();
}
function openPipeline(card) {
  morphId.value = card.key;
  emit("navigate", { page: card.target, morph: card.key });
}

const pipeline = computed(() => {
  const p = data.value?.pipeline;
  if (!p) return [];
  const work = (w) =>
    w.blocked
      ? { tone: "caution", text: `${w.blocked} 项受阻` }
      : w.open
        ? { tone: "info", text: `${w.open} 项待完成` }
        : { tone: "", text: "暂无待办" };
  return [
    {
      key: "patrol",
      title: "巡田上报",
      module: "var(--module-patrol)",
      status: p.patrol.open
        ? { tone: "caution", text: `${p.patrol.open} 项待处理` }
        : { tone: "ok", text: "暂无待处理问题" },
      time: p.patrol.latest ? `最近上报 ${when(p.patrol.latest)}` : "尚无上报",
      target: "daily",
    },
    {
      key: "irrigation",
      title: "灌溉作业",
      module: "var(--module-irrigation)",
      status: work(p.irrigation),
      time: p.irrigation.lastCompleted
        ? `最近回执 ${when(p.irrigation.lastCompleted)}`
        : "尚无作业回执",
      target: "tasks",
    },
    {
      key: "protection",
      title: "植保防治",
      module: "var(--module-protection)",
      status: work(p.protection),
      time: p.protection.lastCompleted
        ? `最近回执 ${when(p.protection.lastCompleted)}`
        : "尚无作业回执",
      target: "tasks",
    },
    {
      key: "production",
      title: "收获记录",
      module: "var(--module-harvest)",
      status: p.harvest.records
        ? { tone: "ok", text: `近 30 天 ${p.harvest.records} 次` }
        : { tone: "", text: "近 30 天无记录" },
      time: p.harvest.latest ? `最近记录 ${p.harvest.latest}` : "尚无记录",
      target: "production",
    },
  ];
});
const matrix = computed(() => data.value?.matrix || { days: [], plots: [] });
const cellNames = {
  OK: "在上下限内",
  LOW: "低于下限",
  HIGH: "高于上限",
  UNSET: "未设上下限",
  NONE: "无读数",
};
// 读屏软件按行听汇总，不逐格朗读 28 个色块
function rowSummary(plot) {
  if (!plot.sensors) return `${plot.name}：未布设土壤水分监测设备`;
  const count = (state) => plot.cells.filter((c) => c === state).length;
  const parts = [`${count("OK")} 天在上下限内`];
  if (plot.lowDays) parts.push(`${plot.lowDays} 天低于下限`);
  if (plot.highDays) parts.push(`${plot.highDays} 天高于上限`);
  if (count("UNSET")) parts.push(`${count("UNSET")} 天有读数但未设上下限`);
  parts.push(`${count("NONE")} 天无读数`);
  return `${plot.name}，近 ${plot.cells.length} 天：${parts.join("，")}`;
}
</script>

<template>
  <div class="operations" :aria-busy="loading">
    <p v-if="error" class="error" role="alert">
      {{ error }} <button class="text-button" @click="load">重新加载</button>
    </p>
    <div class="ops-metrics">
      <article class="ops-card ops-metric">
        <div class="ops-label">
          <span>农场待办</span
          ><img class="ops-metric-icon" src="/icons/operations/tasks-summary.png" alt="" />
        </div>
        <div class="ops-value">
          <span v-if="!data" class="skeleton skeleton-number"></span
          ><template v-else>{{ summary.openTasks }}<small>项</small></template>
        </div>
        <div class="ops-bottom">
          <span
            ><b :class="{ 'tone-danger': summary.overdueTasks > 0 }"
              >逾期 {{ summary.overdueTasks ?? "—" }}</b
            >
            ·
            <b :class="{ 'tone-caution': summary.blockedTasks > 0 }"
              >受阻 {{ summary.blockedTasks ?? "—" }}</b
            ></span
          ><button class="link-button" @click="emit('navigate', 'daily')">
            查看农事 →
          </button>
        </div>
      </article>
      <article class="ops-card ops-metric">
        <div class="ops-label">
          <span>此刻设备上报健康度</span
          ><img class="ops-metric-icon" src="/icons/operations/health-summary.png" alt="" />
        </div>
        <div
          :class="[
            'ops-value',
            { danger: currentHealth !== null && currentHealth < 50 },
          ]"
        >
          <span v-if="!data" class="skeleton skeleton-number"></span
          ><template v-else
            >{{ currentHealth ?? "—" }}<small>{{
              currentHealth === null ? "暂无样本" : "%"
            }}</small></template
          >
        </div>
        <div class="ops-bottom">
          <span v-if="data"
            >正常 {{ summary.freshDevices }} 台<template v-if="lateDevices">
              · 超时 {{ lateDevices }} 台</template
            ><template v-if="summary.silentDevices">
              · 尚无上报 {{ summary.silentDevices }} 台</template
            ></span
          ><span v-else>—</span
          ><button class="link-button" @click="emit('navigate', 'devices')">
            查看设备 →
          </button>
        </div>
      </article>
      <article class="ops-card ops-metric">
        <div class="ops-label">
          <span>未关闭现场问题</span
          ><img class="ops-metric-icon" src="/icons/operations/issue-summary.png" alt="" />
        </div>
        <div class="ops-value">
          <span v-if="!data" class="skeleton skeleton-number"></span
          ><template v-else>{{ summary.openIssues }}<small>项</small></template>
        </div>
        <div class="ops-bottom">
          <span>均需农场主复核后关闭</span
          ><button class="link-button" @click="emit('navigate', 'daily')">
            查看问题 →
          </button>
        </div>
      </article>
    </div>

    <section class="ops-card ops-chart" aria-labelledby="ops-chart-title">
      <div class="ops-chart-head">
        <div class="ops-section-title">
          <img class="ops-section-icon" src="/icons/operations/monitor-chart.png" alt="" />
          <h3 id="ops-chart-title">设备上报与土壤水分</h3>
          <SelectMenu
            v-model="hours"
            aria-label="统计范围"
            class="ops-range"
            :options="rangeOptions"
          />
          <p>
            100 个等距时间片 · 每片约
            {{
              data
                ? data.window.sliceMinutes >= 60
                  ? (data.window.sliceMinutes / 60).toFixed(1) + " 小时"
                  : Math.round(data.window.sliceMinutes) + " 分钟"
                : "—"
            }}
          </p>
        </div>
        <div class="ops-summary">
          {{ healthText }}<small>已观测时段平均健康度</small>
        </div>
      </div>
      <div class="ops-legend" aria-label="健康度颜色阈值">
        <span class="hc-green"><i></i>≥98%</span
        ><span class="hc-brown"><i></i>75–&lt;98%</span
        ><span class="hc-orange"><i></i>50–&lt;75%</span
        ><span class="hc-red"><i></i>&lt;50%</span
        ><span class="hc-none"><i></i>无样本</span
        ><span class="line-key"><i></i>土壤水分均值</span>
      </div>
      <div class="ops-slice-chart">
        <div
          class="ops-slices"
          role="group"
          aria-label="各时间片的设备上报健康度与土壤水分，左右方向键切换时间片"
          @keydown="onSliceKey"
        >
          <svg
            class="ops-line"
            viewBox="0 0 1000 48"
            preserveAspectRatio="none"
            aria-hidden="true"
          >
            <path class="halo" :d="moistureLine.path" /><path
              :d="moistureLine.path"
            />
          </svg>
          <div class="ops-points" aria-hidden="true">
            <span
              v-for="(point, i) in moistureLine.points"
              :key="`${point.x}-${point.y}-${i}`"
              class="ops-point"
              :style="{ left: `${point.x / 10}%`, top: `${(point.y / 48) * 100}%` }"
            ></span>
          </div>
          <button
            v-for="(s, i) in slices"
            :key="s.start"
            type="button"
            :class="['ops-slice', 'hc-' + tone(s.health)]"
            :tabindex="i === focusIndex ? 0 : -1"
            :aria-label="`时间片 ${i + 1}：${sliceText(s)}`"
            @mouseenter="show(i)"
            @focus="show(i)"
          >
            <i></i>
          </button>
          <span
            v-if="loading && !slices.length"
            class="skeleton skeleton-block"
            aria-hidden="true"
          ></span>
          <p v-if="loading && !slices.length" class="sr-only" role="status">
            正在汇总上报记录…
          </p>
        </div>
        <p v-if="readout" class="ops-readout" aria-live="polite">
          {{ readout }}
        </p>
      </div>
      <p class="ops-readout-hint" aria-live="polite">
        {{ readout ? "悬停或用左右方向键切换时间片。" : "悬停或用左右方向键查看某个时间片的上报情况与读数。" }}
      </p>
      <div class="ops-axis">
        <span>{{ data ? when(data.window.start) : "" }}</span
        ><span>{{ observedSlices }}/100 片有上报样本</span
        ><span>{{ data ? when(data.window.end) : "" }}</span>
      </div>
    </section>

    <div class="ops-sechead">
      <h3>作业流水线</h3>
      <span>展示最近记录，请结合现场时间判断</span>
    </div>
    <div class="ops-runs">
      <template v-if="!data">
        <div v-for="n in 4" :key="n" class="ops-card ops-run" aria-hidden="true">
          <span class="skeleton ops-art"></span>
          <span class="ops-run-body" style="flex: 1">
            <span class="skeleton" style="width: 55%"></span>
            <span class="skeleton" style="width: 80%"></span>
          </span>
        </div>
      </template>
      <button
        v-for="card in pipeline"
        :key="card.key"
        type="button"
        class="ops-card ops-run"
        :style="morphId === card.key ? { viewTransitionName: 'ops-morph' } : null"
        @click="openPipeline(card)"
      >
        <span class="ops-art" :style="{ background: card.module }"
          ><img
            class="ops-icon-art"
            :src="`/icons/operations/${card.key}.png`"
            alt=""
            aria-hidden="true"
        /></span>
        <span class="ops-run-body">
          <b>{{ card.title }}</b>
          <span :class="['ops-status', card.status.tone]">{{
            card.status.text
          }}</span>
          <small>{{ card.time }}</small>
        </span>
      </button>
    </div>

    <div class="ops-sechead">
      <img class="ops-section-icon" src="/icons/operations/soil-matrix.png" alt="" />
      <h3>地块 × 日期 · 土壤水分</h3>
      <span>每格一天，按设备配置的上下限判定</span>
    </div>
    <section class="ops-card ops-matrix" aria-label="地块逐日土壤水分">
      <div class="ops-mx">
        <div class="ops-mx-dates" aria-hidden="true">
          <span>地块 / 作物</span><span>{{ matrix.days[0]?.slice(5) }}</span
          ><span>{{ matrix.days.at(-1)?.slice(5) }}</span>
        </div>
        <div v-for="plot in matrix.plots" :key="plot.id" class="ops-mx-row">
          <span class="sr-only">{{ rowSummary(plot) }}</span>
          <div class="ops-mx-name" aria-hidden="true">
            <span>{{ plot.name }}</span
            ><small
              ><template v-if="plot.crop && !plot.name.includes(plot.crop)"
                >{{ plot.crop }} · </template
              >{{ plot.areaMu }} 亩<template v-if="!plot.sensors">
                · 未布设监测</template
              ><template v-if="plot.lowDays">
                · {{ plot.lowDays }} 天偏低</template
              ><template v-if="plot.highDays">
                · {{ plot.highDays }} 天偏高</template
              ></small
            >
          </div>
          <span
            v-for="(cell, i) in plot.cells"
            :key="i"
            :class="['ops-mx-cell', 'mc-' + cell.toLowerCase()]"
            aria-hidden="true"
            :title="`${plot.name} · ${matrix.days[i]} · ${cellNames[cell]}`"
          ></span>
        </div>
        <template v-if="!data">
          <div v-for="n in 4" :key="n" class="ops-mx-row" aria-hidden="true">
            <span class="skeleton" style="width: 70%"></span>
            <span class="skeleton ops-mx-skeleton"></span>
          </div>
        </template>
        <div v-if="data && !matrix.plots.length" class="empty-state ops-placeholder">
          <p>这座农场还没有地块。建好地块并布设土壤水分设备后，这里会逐日显示墒情。</p>
          <button class="outline" @click="emit('navigate', 'plots')">去地块管理</button>
        </div>
      </div>
      <div class="ops-legend">
        <span class="mc-ok"><i></i>在上下限内</span
        ><span class="mc-low"><i></i>低于下限</span
        ><span class="mc-high"><i></i>高于上限</span
        ><span class="mc-unset"><i></i>未设上下限</span
        ><span class="mc-none"><i></i>无读数</span>
      </div>
    </section>

    <details class="stat-notes disclosure">
      <summary>统计口径</summary>
      <div class="disclosure-content">
        <ul>
          <li>
            设备上报健康度：在每个时间片结束时，最近一次上报距今不超过上报间隔 3
            倍（至少 3 分钟）的启用设备所占比例，与设备台账的“正常上报”同一口径。维护中和已停用的设备不计入；从未上报过的设备单独列出，不计入比例。
          </li>
          <li>
            指标卡里的“此刻”是最后一个时间片（截止到现在）的健康度；图表右上角是所选范围内有样本时间片的平均值，两者可能不同。
          </li>
          <li>土壤水分均值：时间片内全部土壤水分读数的平均值，没有读数的时段不画线。</li>
          <li>
            地块 × 日期：当天该地块任一设备有一次读数低于（或高于）该设备配置的下限（上限），即标为偏低（偏高）；两者都有时按偏低显示。设备未设上下限时只显示有读数，不判定适宜与否。
          </li>
          <li>
            作业流水线：灌溉和植保按任务类型统计待完成、受阻和最近的作业回执；收获按近 30 天生产记录统计。
          </li>
        </ul>
      </div>
    </details>
    <p class="ops-footer">
      <span>设备读数快照，不代表实时田间状况</span
      ><span v-if="data">更新于 {{ when(data.window.end) }}</span
      ><span>阈值来自设备台账中的配置，未经农学标定</span>
    </p>
  </div>
</template>
