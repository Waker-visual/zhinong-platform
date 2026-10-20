<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import { api } from "../api";
import ModalDialog from "../ui/ModalDialog.vue";
import AppIcon from "../ui/AppIcon.vue";
import SelectMenu from "../ui/SelectMenu.vue";
import AssistantText from "./AssistantText.vue";
import { diagnosticLabels } from "./diagnostics";
import { num } from "../workspace/presentation.js";
import "./briefing.css";

const props = defineProps({ farmId: String, revision: Number, compact: { type: Boolean, default: true } });
const emit = defineEmits(["ask", "navigate", "alerts"]);
const report = ref(null),
  loading = ref(false),
  error = ref(""),
  expanded = ref(false);
const overviewExpanded = ref(false);
const history = ref([]),
  historyDate = ref(""),
  historyLoading = ref(false),
  historyError = ref("");
let generation = 0,
  historyGeneration = 0,
  alive = true,
  pollTimer,
  dayTimer,
  pollCount = 0;
const pending = computed(() =>
  ["PENDING", "GENERATING"].includes(report.value?.status),
);
const sourceName = (r) =>
  r?.source === "LLM"
    ? `AI 综合分析${r.model ? " · " + r.model : ""}`
    : "规则分析";
const sourceLabel = computed(() => sourceName(report.value));
const viewedReport = computed(() =>
  historyDate.value && historyDate.value !== report.value?.reportDate
    ? history.value.find((r) => r.reportDate === historyDate.value) ||
      report.value
    : report.value,
);
const historyOptions = computed(() => [
  ...(report.value
    ? [
        {
          value: report.value.reportDate,
          label: report.value.reportDate + "（今日）",
        },
      ]
    : []),
  ...history.value
    .filter((r) => r.reportDate !== report.value?.reportDate)
    .map((r) => ({ value: r.reportDate, label: r.reportDate })),
]);
const facts = computed(() => {
  const m = report.value?.metrics || {};
  return [
    {
      label: "待办农事",
      value: m.pendingTasks,
      unit: "项",
      detail: `${m.overdueTasks ?? '—'} 项逾期`,
    },
    {
      label: "未关闭预警",
      value: m.activeAlerts,
      unit: "项",
      detail: `${m.offlineDevices ?? '—'} 台离线`,
    },
    {
      label: "近 7 天登记产量",
      value: m.productionRecords7d ? m.yieldKg7d : null,
      unit: "kg",
      detail: m.productionRecords7d ? `${m.productionRecords7d} 条生产记录` : "暂无收获记录",
    },
    {
      label: "当前种植计划",
      value: m.activePlantings,
      unit: "项",
      detail: `${m.freshMeasurements ?? '—'} 条新鲜监测读数`,
    },
  ];
});
const number = (value) => num(value,2);
const time = (value) =>
  value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "待生成";
const priorityName = (value) =>
  ({ HIGH: "优先处理", MEDIUM: "今日跟进", LOW: "持续关注" })[value] ||
  "今日跟进";
function localDay() {
  return new Intl.DateTimeFormat("sv-SE", {
    timeZone: report.value?.timeZone || "Asia/Shanghai",
  }).format(new Date());
}
async function load({ reset = false } = {}) {
  clearTimeout(pollTimer);
  const g = ++generation,
    farm = props.farmId;
  if (reset) {
    report.value = null;
    expanded.value = false;
    pollCount = 0;
    history.value = [];
    historyDate.value = "";
    historyError.value = "";
    historyGeneration++;
  }
  error.value = "";
  if (!farm) {
    loading.value = false;
    return;
  }
  loading.value = true;
  try {
    const result = await api(
      "/ai/briefings/today?farmId=" + encodeURIComponent(farm),
    );
    if (!alive || g !== generation || farm !== props.farmId) return;
    if (historyDate.value === report.value?.reportDate)
      historyDate.value = result.reportDate;
    report.value = result;
    if (pending.value && pollCount < 20 && !document.hidden) {
      pollCount++;
      pollTimer = setTimeout(() => load(), 3000);
    }
  } catch (e) {
    if (alive && g === generation) error.value = e.message;
  } finally {
    if (alive && g === generation) loading.value = false;
  }
}
function refresh() {
  pollCount = 0;
  return load();
}
async function showReport() {
  expanded.value = true;
  historyDate.value = report.value?.reportDate || "";
  await loadHistory();
}
async function loadHistory() {
  const farm = props.farmId,
    g = ++historyGeneration;
  historyLoading.value = true;
  historyError.value = "";
  try {
    const rows = await api(
      "/ai/briefings/history?farmId=" + encodeURIComponent(farm),
    );
    if (alive && farm === props.farmId && g === historyGeneration)
      history.value = rows;
  } catch (e) {
    if (alive && g === historyGeneration) historyError.value = e.message;
  } finally {
    if (alive && g === historyGeneration) historyLoading.value = false;
  }
}
function onVisible() {
  if (document.hidden) clearTimeout(pollTimer);
  else if (
    props.farmId &&
    (!report.value || pending.value || report.value.reportDate !== localDay())
  )
    refresh();
}
function ask(item) {
  const r = expanded.value ? viewedReport.value : report.value;
  if (!r) return;
  expanded.value = false;
  const subject = item
    ? `关于“${item.title}”，简报建议：${item.recommendation}。已记录的依据：${(item.evidence || []).join("；")}`
    : `请解读 ${r.reportDate} 的每日经营简报：${r.summary}`;
  emit("ask", {
    farmId: props.farmId,
    question:
      `${subject}。请结合当前农场的最新数据，给出今天的行动顺序、可核实依据、缺失信息和复核办法。日报日期为 ${r.reportDate}，生成时间为 ${r.generatedAt}，请区分快照与最新情况。`.slice(
        0,
        2000,
      ),
  });
}
function act(item) {
  if (item.target === "agronomy") {
    ask(item);
    return;
  }
  expanded.value = false;
  if (item.target === "alerts") emit("alerts");
  else
    emit("navigate", {
      page:
        { tasks: "daily", plantings: "plantings", production: "production" }[
          item.target
        ] || "daily",
      farmId: props.farmId,
    });
}
const actionName = (item) =>
  ({
    alerts: "查看预警",
    tasks: "查看农事",
    plantings: "查看种植计划",
    agronomy: "询问 AI",
    production: "查看生产记录",
  })[item.target] || "查看今日农场";
watch(
  () => props.farmId,
  () => load({ reset: true }),
  { immediate: true },
);
watch(() => props.revision, refresh);
onMounted(() => {
  document.addEventListener("visibilitychange", onVisible);
  dayTimer = setInterval(() => {
    if (
      !document.hidden &&
      !loading.value &&
      props.farmId &&
      report.value?.reportDate !== localDay()
    )
      refresh();
  }, 60000);
});
onUnmounted(() => {
  alive = false;
  generation++;
  historyGeneration++;
  clearTimeout(pollTimer);
  clearInterval(dayTimer);
  document.removeEventListener("visibilitychange", onVisible);
});
</script>

<template>
  <section
    v-if="farmId"
    :class="['farm-briefing', { 'briefing-compact': compact }]"
    aria-labelledby="briefing-title"
    :aria-busy="loading && !report"
  >
    <div class="briefing-heading">
      <div class="briefing-heading-title">
        <AppIcon name="today" />
        <h2 id="briefing-title">今日经营简报</h2>
        <span v-if="report" class="briefing-source">{{ sourceLabel }}</span>
      </div>
      <button v-if="compact && report" class="text-button briefing-toggle" :aria-expanded="overviewExpanded" aria-controls="briefing-overview" @click="overviewExpanded = !overviewExpanded">{{ overviewExpanded ? "收起简报" : "展开简报" }}</button>
      <span v-else class="briefing-date">{{ report?.reportDate || "每日汇总" }}</span>
    </div>
    <div
      v-if="loading && !report"
      class="briefing-skeleton"
      aria-label="正在读取今日经营简报"
      role="status"
    >
      <span class="skeleton"></span><span class="skeleton"></span
      ><span class="skeleton"></span>
    </div>
    <p v-if="error" class="briefing-error" role="alert">
      {{
        report
          ? "状态更新失败，当前显示已保存的简报。"
          : "今日简报暂时无法读取。"
      }}{{ error }}
      <button class="text-button" :disabled="loading" @click="refresh">
        重试
      </button>
    </p>
    <template v-if="report">
      <div v-if="compact && !overviewExpanded" class="briefing-compact-summary">
        <p v-if="report.priorities?.length"><strong>{{ report.priorities[0].title }}</strong><br />{{ report.priorities[0].recommendation }}</p>
        <p v-else>今日快照没有明确优先建议，可查看完整日报核对分析依据。</p>
        <div class="briefing-actions"><button class="text-button" @click="showReport">查看完整日报</button><span class="briefing-date">快照 {{ time(report.generatedAt) }}</span></div>
        <p v-if="report.snapshotOutdated" class="briefing-progress">生成后已有更新，请查看实时任务台账和设备提醒。</p>
      </div>
      <div id="briefing-overview" v-show="!compact || overviewExpanded">
      <div class="briefing-overview">
        <div class="briefing-lead">
          <p>{{ report.summary }}</p>
          <p class="briefing-timestamp">
            {{ report.farmName }} · 生成于 {{ time(report.generatedAt) }}，以下为当时快照
          </p>
          <p v-if="pending" class="briefing-progress" role="status">
            AI 正在补充综合解读，当前可先查看规则分析与生产建议。
          </p>
          <div class="briefing-actions">
            <button class="primary" @click="showReport">查看完整日报</button
            ><button class="outline with-icon" @click="ask()">
              <AppIcon name="send" />带入 AI 追问
            </button>
          </div>
        </div>
        <div class="briefing-next">
          <h3>今天先做什么</h3>
          <template v-if="report.priorities?.length"
            ><p
              class="briefing-priority-label"
              :class="{ urgent: report.priorities[0].priority === 'HIGH' }"
            >
              {{ priorityName(report.priorities[0].priority) }} ·
              {{ report.priorities[0].title }}
            </p>
            <p>{{ report.priorities[0].recommendation }}</p>
            <button class="text-button" @click="act(report.priorities[0])">
              {{ actionName(report.priorities[0]) }}
            </button></template
          >
          <p v-else>暂无明确优先事项，继续记录巡田、生产与设备数据。</p>
        </div>
      </div>
      <dl class="briefing-facts">
        <div v-for="fact in facts" :key="fact.label">
          <dt>{{ fact.label }}</dt>
          <dd>
            {{ number(fact.value) }} <small>{{ fact.unit }}</small>
            <small class="briefing-fact-detail">{{ fact.detail }}</small>
          </dd>
        </div>
      </dl>
      <div class="briefing-caption">
        <span>每日保存一份经营快照 · 虚构演示数据</span
        ><button
          v-if="pending"
          class="text-button"
          :disabled="loading"
          @click="refresh"
        >
          更新分析状态</button
        ><span v-else-if="report.source !== 'LLM'">{{
          diagnosticLabels[report.diagnostic] || "规则结果，未使用模型生成"
        }}</span>
      </div>
      </div>
    </template>
  </section>
  <ModalDialog
    v-if="expanded && report"
    class="briefing-dialog"
    label="今日经营简报详情"
    @close="expanded = false"
  >
    <div class="briefing-dialog-heading">
      <div>
        <h2>{{ viewedReport.farmName }} · 每日经营简报</h2>
        <p>{{ viewedReport.reportDate }} · {{ sourceName(viewedReport) }}</p>
      </div>
      <button
        class="close-button"
        aria-label="关闭日报"
        @click="expanded = false"
      >
        ×
      </button>
    </div>
    <div class="briefing-history">
      <label
        >查看日期<SelectMenu
          v-model="historyDate"
          :options="historyOptions"
          aria-label="日报日期"
          :disabled="historyLoading" /></label
      ><span v-if="historyLoading" role="status">正在读取历史日报…</span
      ><span v-else>显示最近 14 份日报</span
      ><button
        v-if="historyDate !== report.reportDate"
        class="text-button"
        @click="historyDate = report.reportDate"
      >
        回到今日
      </button>
    </div>
    <p v-if="historyError" class="briefing-error" role="alert">
      历史日报未能读取：{{ historyError }}
      <button
        class="text-button"
        :disabled="historyLoading"
        @click="loadHistory"
      >
        重试
      </button>
    </p>
    <p v-if="historyDate !== report.reportDate" class="briefing-progress">
      正在查看历史快照；以下建议与证据反映当日记录，执行前请核对最新情况。
    </p>
    <p v-else-if="report.snapshotOutdated" class="briefing-progress">该日报为生成时快照，之后已有业务更新；请以实时任务台账和设备提醒核对当前状态。</p>
    <div class="briefing-report-summary">
      <h3>经营状况</h3>
      <p>{{ viewedReport.summary }}</p>
      <div v-if="viewedReport.aiSummary" class="briefing-model-summary">
        <h3>AI 综合解读</h3>
        <AssistantText :text="viewedReport.aiSummary" />
      </div>
      <p
        v-else-if="['PENDING', 'GENERATING'].includes(viewedReport.status)"
        class="muted"
        role="status"
      >
        AI 综合解读正在生成，可先按下方有依据的建议安排工作。
      </p>
    </div>
    <section class="briefing-priorities">
      <h3>当日生产建议</h3>
      <article v-for="item in viewedReport.priorities" :key="item.id">
        <div class="briefing-priority-heading">
          <h4>{{ item.title }}</h4>
          <span
            :class="[
              'briefing-priority-label',
              { urgent: item.priority === 'HIGH' },
            ]"
            >{{ priorityName(item.priority) }}</span
          >
        </div>
        <p>{{ item.recommendation }}</p>
        <ul v-if="item.evidence?.length">
          <li v-for="evidence in item.evidence" :key="evidence">
            {{ evidence }}
          </li>
        </ul>
        <div class="briefing-actions">
          <button class="outline" @click="act(item)">
            {{ actionName(item) }}</button
          ><button
            v-if="item.target !== 'agronomy'"
            class="text-button"
            @click="ask(item)"
          >
            追问依据与执行步骤
          </button>
        </div>
      </article>
      <p v-if="!viewedReport.priorities?.length" class="muted">
        暂无明确优先事项。
      </p>
    </section>
    <section v-if="viewedReport.dataGaps?.length" class="briefing-gaps">
      <h3>还需要补齐的信息</h3>
      <ul>
        <li v-for="gap in viewedReport.dataGaps" :key="gap">{{ gap }}</li>
      </ul>
    </section>
    <details class="briefing-evidence">
      <summary>查看分析依据与数据来源</summary>
      <dl>
        <div v-for="(item, index) in viewedReport.evidence" :key="index">
          <dt>{{ item.label }}</dt>
          <dd>
            {{ item.value }}<small>{{ item.source }}</small>
          </dd>
        </div>
      </dl>
    </details>
    <footer class="briefing-report-footer">
      <p>{{ viewedReport.notice }}</p>
      <p>
        生成时间 {{ time(viewedReport.generatedAt) }} · 更新于
        {{ time(viewedReport.updatedAt) }} · 日界线 {{ viewedReport.timeZone }}
      </p>
      <p>
        日报是生成时的快照，新增预警请查看顶部提醒。带入 AI
        追问后可编辑问题，发送才会开始分析。
      </p>
      <div class="briefing-actions">
        <button class="primary" @click="ask()">带入 AI 追问</button
        ><button class="outline" @click="expanded = false">关闭日报</button>
      </div>
    </footer>
  </ModalDialog>
</template>
