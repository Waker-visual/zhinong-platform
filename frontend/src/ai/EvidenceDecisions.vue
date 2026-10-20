<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from "vue";
import { api } from "../api";
import AssistantText from "./AssistantText.vue";
import ModalDialog from "../ui/ModalDialog.vue";
import SelectMenu from "../ui/SelectMenu.vue";
import DatePicker from "../ui/DatePicker.vue";
import { diagnosticLabels } from "./diagnostics";
import { requestId as id } from "../ui/request-id.js";
import "./evidence-decisions.css";

const props = defineProps({ farmId: String, role: String });
const emit = defineEmits(["navigate"]);
const rows = ref([]),
  plots = ref([]),
  crew = ref([]),
  today = ref(""),
  selected = ref(""),
  error = ref(""),
  busy = ref(false),
  loading = ref(false),
  modal = ref("");
const draft = reactive({
  plotId: "",
  question: "请核对这块地的墒情依据、资料缺口与下一步巡查安排。",
});
const plan = reactive({
  title: "",
  assigneeId: "",
  dueDate: "",
  note: "请现场核对测点位置、作物阶段和土壤条件，记录复测数值、时间与测量方法。",
});
const review = reactive({ note: "", manualValue: "", observedAt: "" });
let generation = 0,
  controller,
  createKey = "",
  reviewKey = "",
  alive = true;
const admin = computed(() => props.role === "ADMIN");
const writer = computed(() => ["ADMIN", "OPERATOR"].includes(props.role));
const card = computed(() => rows.value.find((r) => r.id === selected.value));
const plotOptions = computed(() =>
  plots.value.map((plot) => ({ value: plot.id, label: plot.name })),
);
const crewOptions = computed(() =>
  crew.value.map((person) => ({ value: person.id, label: person.displayName })),
);
const stateNames = {
  DRAFT: "待人工确认",
  TASK_CREATED: "待执行",
  REVIEW_PENDING: "待复测复核",
  REVIEWED: "已留存复核",
};
const qualityNames = {
  FRESH: "有有效读数",
  PARTIAL: "部分读数无效",
  MISSING: "缺少测点",
  STALE: "缺少有效新读数",
};
const sourceNames = {
  SIMULATED: "模拟采集",
  HTTP_PUSH: "设备上报",
  MANUAL: "人工采集",
};
function time(value) {
  return value
    ? new Date(value).toLocaleString("zh-CN", { hour12: false })
    : "未记录";
}
watch(
  () => [draft.plotId, draft.question],
  () => {
    createKey = "";
  },
);
watch(
  () => [review.note, review.manualValue, review.observedAt],
  () => {
    reviewKey = "";
  },
);
async function load() {
  if (busy.value || !props.farmId) return false;
  const own = ++generation;
  controller?.abort();
  controller = new AbortController();
  loading.value = true;
  error.value = "";
  try {
    const options = { signal: controller.signal };
    const [decisions, fields, work] = await Promise.all([
      api(
        `/ai/decisions?farmId=${encodeURIComponent(props.farmId)}`,
        "GET",
        undefined,
        options,
      ),
      api(
        `/plots?farmId=${encodeURIComponent(props.farmId)}`,
        "GET",
        undefined,
        options,
      ),
      api(
        `/field-work?farmId=${encodeURIComponent(props.farmId)}`,
        "GET",
        undefined,
        options,
      ),
    ]);
    if (own !== generation || !alive) return;
    rows.value = decisions;
    plots.value = fields;
    crew.value = work.crew;
    today.value = work.today;
    if (!decisions.some((r) => r.id === selected.value))
      selected.value = decisions[0]?.id || "";
    if (!fields.some((p) => p.id === draft.plotId))
      draft.plotId = fields[0]?.id || "";
    return true;
  } catch (e) {
    if (own === generation && !e.cancelled) error.value = e.message;
    return false;
  } finally {
    if (own === generation) loading.value = false;
  }
}
watch(
  () => props.farmId,
  () => {
    rows.value = [];
    selected.value = "";
    modal.value = "";
    createKey = "";
    load();
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  alive = false;
  generation++;
  controller?.abort();
});
defineExpose({ refresh: load, busy, loading });
async function mutate(action) {
  if (busy.value) return;
  const own = generation;
  busy.value = true;
  error.value = "";
  try {
    const result = await action();
    if (own !== generation || !alive) return;
    const at = rows.value.findIndex((r) => r.id === result.id);
    if (at < 0) rows.value.unshift(result);
    else rows.value[at] = result;
    selected.value = result.id;
    modal.value = "";
    return result;
  } catch (e) {
    if (own === generation)
      error.value =
        e.message +
        (e.status === 0 ? "；可保留原内容重试，系统会核对原请求编号。" : "");
  } finally {
    if (own === generation) busy.value = false;
  }
}
async function create() {
  createKey ||= id();
  const result = await mutate(() =>
    api("/ai/decisions", "POST", {
      farmId: props.farmId,
      plotId: draft.plotId,
      question: draft.question,
      requestId: createKey,
    }),
  );
  if (result) createKey = "";
}
function approval() {
  plan.title = card.value.evidence.suggestedTitle;
  plan.assigneeId = crew.value[0]?.id || "";
  plan.dueDate = today.value;
  modal.value = "approve";
}
async function approve() {
  await mutate(() =>
    api(`/ai/decisions/${card.value.id}/approve`, "POST", {
      ...plan,
      expectedRevision: card.value.revision,
    }),
  );
}
function openReview() {
  review.note = "";
  review.manualValue = "";
  const now = new Date();
  review.observedAt = new Date(now.getTime() - now.getTimezoneOffset() * 60000)
    .toISOString()
    .slice(0, 19);
  reviewKey = "";
  modal.value = "review";
}
async function saveReview() {
  reviewKey ||= id();
  const result = await mutate(() =>
    api(`/ai/decisions/${card.value.id}/reviews`, "POST", {
      note: review.note,
      manualValue:
        review.manualValue === "" ? null : Number(review.manualValue),
      observedAt:
        review.manualValue !== "" && review.observedAt
          ? new Date(review.observedAt).toISOString()
          : null,
      requestId: reviewKey,
      expectedRevision: card.value.revision,
    }),
  );
  if (result) reviewKey = "";
}
async function explain() {
  await mutate(() =>
    api(
      `/ai/decisions/${card.value.id}/explain`,
      "POST",
      {},
      { timeoutMs: 55000 },
    ),
  );
}
</script>

<template>
  <div class="evidence-decisions" :aria-busy="loading">
    <div class="decision-intro">
      <div>
        <h2>让每次巡查都有依据</h2>
        <p>
          保存地块证据，人工确认任务，完成后用新观测复核。本流程先支持墒情核查。
        </p>
      </div>
      <button class="outline" @click="load" :disabled="busy || loading">
        刷新记录
      </button>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <form v-if="writer" class="decision-create" @submit.prevent="create">
      <label
        >核查地块<SelectMenu
          v-model="draft.plotId"
          :options="plotOptions"
          aria-label="核查地块"
          required
          :disabled="busy || loading"
      /></label>
      <label
        >本次问题<input
          v-model="draft.question"
          required
          maxlength="500"
          :disabled="busy"
      /></label>
      <button
        class="primary"
        :disabled="busy || loading || !draft.plotId"
        :aria-busy="busy"
      >
        保存证据与建议
      </button>
    </form>
    <div v-if="rows.length" class="decision-layout">
      <nav class="decision-list" aria-label="决策记录">
        <button
          v-for="row in rows"
          :key="row.id"
          :aria-pressed="selected === row.id"
          @click="selected = row.id"
          :disabled="busy"
        >
          <strong>{{ row.evidence.plotName }}</strong
          ><span>{{ stateNames[row.state] }}</span
          ><small>{{ time(row.createdAt) }}</small>
        </button>
      </nav>
      <article v-if="card" class="decision-detail">
        <div class="decision-title">
          <h3>{{ card.evidence.plotName }} · 墒情核查</h3>
          <span>{{ stateNames[card.state] }}</span>
        </div>
        <p>{{ card.question }}</p>
        <p class="decision-meta">
          证据保存于 {{ time(card.evidence.capturedAt) }} ·
          {{ qualityNames[card.evidence.quality] }}
        </p>
        <h4>当时的证据</h4>
        <p v-if="card.evidence.plantings?.length" class="decision-meta">
          在种批次：<span
            v-for="(planting, index) in card.evidence.plantings"
            :key="planting.id"
            >{{ index ? "；" : "" }}{{ planting.crop
            }}{{ planting.variety ? `（${planting.variety}）` : "" }} ·
            {{ planting.startDate }} 至 {{ planting.endDate }}</span
          >
        </p>
        <ul class="decision-readings" v-if="card.evidence.readings.length">
          <li v-for="r in card.evidence.readings" :key="r.deviceId">
            <strong
              >{{ r.deviceName }}：{{
                r.value == null ? "缺测" : `${r.value} %`
              }}</strong
            ><span
              >{{
                r.reason || (r.fresh ? "保存时有效" : "缺测、过期或状态不符")
              }}
              · {{ sourceNames[r.source] || "未取得来源" }}</span
            ><small
              >观测：{{ time(r.observedAt)
              }}<template v-if="r.lowerLimit != null">
                · 配置下限 {{ r.lowerLimit }} %</template
              ></small
            >
            <small v-if="r.freshnessThresholdSeconds != null"
              >有效期 {{ r.freshnessThresholdSeconds }} 秒<template
                v-if="r.receivedAt"
              >
                · 接收：{{ time(r.receivedAt) }}</template
              ><template v-else-if="r.receivedAtKnown === false"> · 旧记录未保留接收时刻</template
              ></small
            >
          </li>
        </ul>
        <p v-else>当前地块没有登记土壤水分测点，先安排现场补测。</p>
        <details class="decision-limits">
          <summary>资料缺口与适用边界</summary>
          <ul>
            <li v-for="line in card.evidence.limitations" :key="line">
              {{ line }}
            </li>
          </ul>
        </details>
        <div class="decision-explanation">
          <div class="decision-title">
            <h4>{{ card.mode === "llm" ? "模型辅助解读" : "规则核验说明" }}</h4>
            <button
              v-if="writer"
              class="outline"
              @click="explain"
              :disabled="busy || loading"
              :aria-busy="busy"
            >
              {{ card.mode === "llm" ? "重新解读" : "请求模型解读" }}
            </button>
          </div>
          <p
            v-if="
              card.mode === 'llm' && card.explanationRevision !== card.revision
            "
            class="decision-stale-explanation"
            role="status"
          >
            解读依据较早，复核后可重新请求模型解读。当前解读不代表最新复核结论。
          </p>
          <AssistantText :text="card.explanation" /><small
            >模型文字仅供参考；审批只会创建巡查任务。{{
              card.mode === "llm"
                ? `配置模型：${card.model || "兼容服务"}`
                : `模型状态：${card.diagnostic === "NOT_REQUESTED" ? "尚未请求" : diagnosticLabels[card.diagnostic] || "暂不可用"}`
            }}</small
          >
        </div>
        <section v-if="card.taskId" class="decision-task">
          <h4>已确认的执行任务</h4>
          <p>
            <strong>{{ card.task.title }}</strong> ·
            {{ card.task.displayName }} · {{ card.task.dueDate }}
          </p>
          <p>
            状态：{{
              {
                PENDING: "待开始",
                RUNNING: "执行中",
                COMPLETED: "已完成",
                CANCELLED: "已取消",
              }[card.task.status] || card.task.status
            }}
          </p>
          <p v-if="card.task.completionNote">
            完成回执：{{ card.task.completionNote }}
          </p>
          <small>在“今日农场”处理该任务并填写回执，再回到此处刷新复核。</small
          ><button
            class="outline"
            @click="emit('navigate', { page: 'daily', farmId })"
          >
            前往今日农场
          </button>
        </section>
        <button
          v-if="admin && !card.taskId"
          class="primary"
          @click="approval"
          :disabled="busy || loading"
        >
          确认并安排巡查
        </button>
        <p v-else-if="!card.taskId">等待农场管理员确认负责人和日期后派工。</p>
        <button
          v-if="admin && card.task?.status === 'COMPLETED'"
          class="primary"
          @click="openReview"
          :disabled="busy || loading"
        >
          记录复测与复核
        </button>
        <section v-for="r in card.reviews" :key="r.id" class="decision-review">
          <h4>
            {{
              r.outcome === "INSUFFICIENT"
                ? "复核：证据仍不足"
                : "复核：已取得新观测"
            }}
          </h4>
          <p>{{ r.note }}</p>
          <p>{{ r.evidence.assessment }}</p>
          <p v-if="r.evidence.manual">
            人工复测：{{ r.evidence.manual.value }} % ·
            {{ time(r.evidence.manual.observedAt) }} ·
            {{ r.evidence.manual.source }}
          </p>
          <ul v-if="r.evidence.comparisons?.length">
            <li v-for="c in r.evidence.comparisons" :key="c.deviceId">
              {{ c.deviceName }}：{{ c.beforeValue }} % →
              {{ c.afterValue }} %（变化 {{ c.delta }} 个百分点）
            </li>
          </ul>
          <small>{{ r.reviewer }} · {{ time(r.createdAt) }}</small>
        </section>
        <small
          >证据规则版本 {{ card.ruleVersion }} ·
          最近展示50张卡、每卡最近10次复核；历史记录保留。</small
        >
      </article>
    </div>
    <p v-else-if="!loading" class="decision-empty">
      还没有决策卡。选择一块地，保存本次问题和证据后开始核查。
    </p>
    <ModalDialog
      v-if="modal"
      :label="modal === 'approve' ? '确认巡查任务' : '记录复测与复核'"
      :locked="busy"
      @close="modal = ''"
    >
      <form
        @submit.prevent="modal === 'approve' ? approve() : saveReview()"
        class="decision-form"
      >
        <h3>{{ modal === "approve" ? "确认巡查任务" : "记录复测与复核" }}</h3>
        <template v-if="modal === 'approve'"
          ><label
            >任务标题<input
              v-model="plan.title"
              required
              maxlength="120"
              :disabled="busy" /></label
          ><label
            >负责人<SelectMenu
              v-model="plan.assigneeId"
              :options="crewOptions"
              aria-label="巡查负责人"
              required
              :disabled="busy" /></label
          ><label
            >计划日期<DatePicker
              v-model="plan.dueDate"
              aria-label="巡查计划日期"
              required
              :disabled="busy" /></label
          ><label
            >核验要求<textarea
              v-model="plan.note"
              required
              maxlength="400"
              :disabled="busy"
            />
          </label>
          <p>确认后创建一条人工巡查任务，不会下发设备控制指令。</p></template
        >
        <template v-else
          ><p>
            系统会重新读取测点。旧观测与完成回执不能替代任务完成后的复测；没有新证据时记录为“证据仍不足”。
          </p>
          <label
            >现场复核说明<textarea
              v-model="review.note"
              required
              maxlength="500"
              :disabled="busy"
            /></label
          ><label
            >人工复测土壤水分 / %（可选）<input
              type="number"
              v-model="review.manualValue"
              min="0"
              max="100"
              step="0.001"
              :disabled="busy" /></label
          ><label
            >人工观测时间<input
              type="datetime-local"
              step="1"
              v-model="review.observedAt"
              :required="review.manualValue !== ''"
              :disabled="busy" /></label
          ><small
            >时间预填为打开表单的本机时间，请核对真实观测时间。人工复测须在任务完成之后、最近30分钟内；请在说明中记录测量方法与位置。</small
          ></template
        >
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <div class="decision-actions">
          <button
            type="button"
            class="outline"
            @click="modal = ''"
            :disabled="busy"
          >
            取消</button
          ><button class="primary" :disabled="busy" :aria-busy="busy">
            {{ modal === "approve" ? "确认生成任务" : "保存复核" }}
          </button>
        </div>
      </form>
    </ModalDialog>
  </div>
</template>
