<script setup>
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from "vue";
import { api } from "../api";
import { labels } from "../catalog";
import "./daily-farm.css";
const props = defineProps({
  identity: Object,
  farms: Array,
  plots: Array,
  farmId: String,
  revision: Number,
});
const emit = defineEmits(["update:farmId", "navigate", "farm"]);
const admin = computed(() => props.identity.role === "ADMIN");
const writer = computed(() =>
  ["ADMIN", "OPERATOR"].includes(props.identity.role),
);
const farm = computed(() => props.farms.find((f) => f.id === props.farmId));
const farmPlots = computed(() =>
  props.plots.filter((p) => p.farmId === props.farmId),
);
const data = ref({ tasks: [], issues: [], crew: [], today: "" }),
  error = ref(""),
  notice = ref(""),
  loading = ref(false),
  saving = ref(false);
const filter = ref("open"),
  mine = ref(props.identity.role === "OPERATOR"),
  search = ref("");
const modal = ref(""),
  dialogElement = ref(null),
  target = ref(null),
  issueId = ref(""),
  logs = ref([]);
const form = reactive({});
const methods = {
  UNCONFIRMED: "资源待确认",
  DRONE: "无人机作业",
  MANUAL: "人工作业",
  SERVICE: "外包服务",
};
const categories = {
  PEST: "病虫害",
  WATER: "水分异常",
  EQUIPMENT: "设备故障",
  OTHER: "其他问题",
};
let seq = 0,
  alive = true;
const pending = computed(() =>
  data.value.tasks.filter((t) => ["PENDING", "RUNNING"].includes(t.status)),
);
const myPending = computed(() =>
  pending.value.filter(
    (t) =>
      !mine.value || t.assigneeId === props.identity.memberId || !t.assigneeId,
  ),
);
const openIssues = computed(() =>
  data.value.issues.filter((i) => i.status !== "RESOLVED"),
);
const overdue = (t) =>
  ["PENDING", "RUNNING"].includes(t.status) && t.dueDate < data.value.today;
// 任务状态的语义色，与徽章文字一一对应：逾期红色，受阻橙色，执行中蓝色，其余中性。
// 既逾期又受阻时按逾期处理，农场主先看到已经超期的事项。
function statusTone(t) {
  if (overdue(t)) return "danger";
  if (t.blockedReason) return "caution";
  if (t.status === "RUNNING") return "info";
  return "";
}
function statusText(t) {
  if (overdue(t)) return "已逾期";
  if (t.blockedReason) return "受阻";
  return word(t.status);
}
const taskList = computed(() =>
  data.value.tasks
    .filter((t) => {
      if (
        mine.value &&
        t.assigneeId &&
        t.assigneeId !== props.identity.memberId
      )
        return false;
      if (filter.value === "open" && !["PENDING", "RUNNING"].includes(t.status))
        return false;
      if (filter.value === "blocked" && !t.blockedReason) return false;
      if (
        filter.value === "done" &&
        !["COMPLETED", "CANCELLED"].includes(t.status)
      )
        return false;
      return (t.title + t.plotName + (t.assigneeName || "")).includes(
        search.value,
      );
    })
    .sort(
      (a, b) =>
        Number(Boolean(b.blockedReason)) - Number(Boolean(a.blockedReason)) ||
        Number(overdue(b)) - Number(overdue(a)) ||
        a.dueDate.localeCompare(b.dueDate),
    ),
);
const canWork = (t) =>
  writer.value &&
  (admin.value || !t.assigneeId || t.assigneeId === props.identity.memberId);
const selectedPlot = computed(() =>
  farmPlots.value.find((p) => p.id === form.plotId),
);
const area = computed(() =>
  farmPlots.value.reduce((s, p) => s + Number(p.areaMu), 0),
);
const word = (value) =>
  labels[value] ||
  {
    BLOCKED: "资源受阻",
    PLANNED: "安排任务",
    OPEN: "待安排",
    ASSIGNED: "处理中",
    RESOLVED: "已复核关闭",
  }[value] ||
  value;
function jump(page, create = false) {
  emit("navigate", { page, farmId: props.farmId, create });
}
async function load() {
  const run = ++seq;
  error.value = "";
  loading.value = true;
  if (!props.farmId) {
    data.value = { tasks: [], issues: [], crew: [], today: "" };
    loading.value = false;
    return;
  }
  try {
    const result = await api(
      "/field-work?farmId=" + encodeURIComponent(props.farmId),
    );
    if (alive && run === seq) data.value = result;
  } catch (e) {
    if (alive && run === seq) error.value = e.message;
  } finally {
    if (alive && run === seq) loading.value = false;
  }
}
watch(
  () => [props.farmId, props.revision],
  () => {
    data.value = { tasks: [], issues: [], crew: [], today: "" };
    load();
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  alive = false;
  ++seq;
});
async function open(kind, row = null, status = "") {
  modal.value = kind;
  target.value = row;
  issueId.value = "";
  error.value = "";
  Object.keys(form).forEach((k) => delete form[k]);
  if (kind === "issue")
    Object.assign(form, {
      plotId: farmPlots.value.length === 1 ? farmPlots.value[0].id : "",
      category: "PEST",
      severity: "HIGH",
      description: "",
    });
  if (kind === "plan") {
    const issue = row?.category ? row : null;
    issueId.value = issue?.id || "";
    Object.assign(form, {
      plotId:
        row?.plotId ||
        (farmPlots.value.length === 1 ? farmPlots.value[0].id : ""),
      title: issue
        ? `${issue.plotName} · ${categories[issue.category]}处理`
        : row?.title || "",
      taskType:
        issue?.category === "PEST"
          ? "PROTECTION"
          : row?.taskType || "INSPECTION",
      dueDate: row?.dueDate || data.value.today,
      assigneeId: row?.assigneeId || "",
      method: row?.method || "UNCONFIRMED",
      note: issue ? issue.description : row?.note || "",
    });
  }
  if (kind === "progress")
    Object.assign(form, {
      status,
      note: "",
      method: row.method || "UNCONFIRMED",
      actualAreaMu: status === "COMPLETED" ? Number(row.plotAreaMu) : 0,
    });
  if (kind === "review") Object.assign(form, { note: "" });
  await nextTick();
  dialogElement.value?.showModal();
}
function close() {
  if (saving.value) return;
  dialogElement.value?.close();
  modal.value = "";
}
async function submit() {
  if (saving.value) return;
  saving.value = true;
  error.value = "";
  try {
    if (modal.value === "issue") await api("/field-work/issues", "POST", form);
    if (modal.value === "plan")
      await api(
        issueId.value
          ? `/field-work/issues/${issueId.value}/task`
          : target.value
            ? `/field-work/tasks/${target.value.id}/plan`
            : "/field-work/tasks",
        target.value && !issueId.value ? "PUT" : "POST",
        form,
      );
    if (modal.value === "progress")
      await api(`/field-work/tasks/${target.value.id}/progress`, "PATCH", form);
    if (modal.value === "review")
      await api(`/field-work/issues/${target.value.id}/resolve`, "POST", form);
    dialogElement.value.close();
    modal.value = "";
    notice.value = "记录已保存，农场待办已更新。";
    await load();
  } catch (e) {
    error.value = e.message;
  } finally {
    saving.value = false;
  }
}
async function history(row) {
  error.value = "";
  logs.value = [];
  target.value = row;
  modal.value = "history";
  await nextTick();
  dialogElement.value.showModal();
  try {
    logs.value = await api(`/field-work/tasks/${row.id}/logs`);
  } catch (e) {
    error.value = e.message;
  }
}
</script>

<template>
  <div class="daily-farm" :aria-busy="loading">
    <section class="daily-hero">
      <div>
        <p class="eyebrow">
          {{
            identity.role === "OPERATOR"
              ? "FIELD WORK · 田间作业"
              : identity.role === "VIEWER"
                ? "FARM REVIEW · 经营查看"
                : "MY FARM · 日常经营"
          }}
        </p>
        <h2>{{ farm?.name || "从你的第一座农场开始" }}</h2>
        <p>
          {{
            admin
              ? "先处理风险，再安排今天的农事。"
              : identity.role === "OPERATOR"
                ? "查看我的任务，记录现场发现与作业结果。"
                : "查看农场进展、现场问题与作业记录。"
          }}
        </p>
        <small v-if="farm"
          >{{ farmPlots.length }} 块田 · {{ area }} 亩 ·
          {{ identity.displayName }}，欢迎回来</small
        >
      </div>
      <label class="daily-farm-picker"
        >当前农场<select
          aria-label="当前农场"
          :value="farmId"
          @change="emit('update:farmId', $event.target.value)"
          :disabled="loading || !farms.length"
        >
          <option v-if="!farms.length" value="">尚无农场</option>
          <option v-for="f in farms" :key="f.id" :value="f.id">
            {{ f.name }}
          </option>
        </select></label
      >
    </section>
    <p v-if="error && !modal" class="error" role="alert">
      {{ error }} <button @click="load">重新加载</button>
    </p>
    <p v-if="notice && !modal" class="success" role="status">{{ notice }}</p>
    <section v-if="!farmId && !loading" class="panel daily-empty">
      <h3>还没有可使用的农场</h3>
      <p>
        {{
          admin
            ? "先建立农场，再添加地块和种植计划。"
            : "请联系农场主建立农场与地块，之后即可查看任务。"
        }}
      </p>
      <button v-if="admin" class="primary" @click="jump('farms')">
        建立农场档案
      </button>
    </section>
    <template v-if="farmId">
      <div class="daily-quick">
        <button
          v-if="writer"
          class="primary"
          @click="open('issue')"
          :disabled="loading || !farmPlots.length"
        >
          ＋ 巡田上报</button
        ><button
          v-if="admin"
          @click="open('plan')"
          :disabled="loading || !farmPlots.length"
        >
          安排农事</button
        ><button
          v-if="writer"
          @click="jump('production', true)"
          :disabled="!farmPlots.length"
        >
          记录收获</button
        ><button @click="emit('farm', farmId)">农场地图 →</button
        ><button @click="jump('simulation')">季度方案对照 →</button>
      </div>
      <p v-if="!farmPlots.length && !loading" class="panel daily-empty">
        农场尚无地块。<button v-if="admin" @click="jump('plots', true)">
          添加第一块田</button
        ><span v-else>请联系农场主添加地块。</span>
      </p>
      <div class="daily-kpis">
        <article>
          <small>{{ mine ? "我的待办（含未分配）" : "农场待办" }}</small
          ><strong>{{ myPending.length }}<span>项</span></strong>
          <p>待执行与执行中</p>
        </article>
        <article :class="{ 'attention danger': pending.some(overdue) }">
          <small>农场逾期任务</small
          ><strong>{{ pending.filter(overdue).length }}<span>项</span></strong>
          <p>按服务器日期 {{ data.today || "…" }}</p>
        </article>
        <article
          :class="{ 'attention caution': pending.some((t) => t.blockedReason) }"
        >
          <small>资源或现场受阻</small
          ><strong
            >{{ pending.filter((t) => t.blockedReason).length
            }}<span>项</span></strong
          >
          <p>优先协调设备与人员</p>
        </article>
        <article>
          <small>待跟进现场问题</small
          ><strong>{{ openIssues.length }}<span>项</span></strong>
          <p>处理后仍需农场主复核</p>
        </article>
      </div>
      <div class="daily-columns">
        <section class="panel daily-worklist">
          <div class="daily-section-title">
            <div>
              <p class="eyebrow">WORK QUEUE</p>
              <h3>{{ mine ? "我的农事" : "农场农事" }}</h3>
            </div>
            <label class="daily-check"
              ><input type="checkbox" v-model="mine" />只看我负责 /
              未分配</label
            >
          </div>
          <div class="daily-tabs" role="group" aria-label="任务状态筛选">
            <button
              v-for="t in [
                ['open', '待处理'],
                ['blocked', '受阻'],
                ['done', '已结束'],
                ['all', '全部'],
              ]"
              :key="t[0]"
              :aria-pressed="filter === t[0]"
              @click="filter = t[0]"
            >
              {{ t[1] }}
            </button>
          </div>
          <input
            class="daily-search"
            v-model="search"
            aria-label="搜索农事"
            placeholder="搜索地块、任务或负责人"
          />
          <p v-if="loading" class="daily-empty" role="status">
            正在读取农场任务…
          </p>
          <p v-else-if="!taskList.length" class="daily-empty">
            {{
              search
                ? "没有匹配的任务，请调整搜索条件。"
                : "当前范围没有任务。可切换“全部”查看历史。"
            }}
          </p>
          <article
            v-for="t in taskList"
            :key="t.id"
            class="daily-task"
            :data-task-id="t.id"
          >
            <div class="daily-task-line">
              <span :class="['daily-status', statusTone(t)]">{{
                statusText(t)
              }}</span
              ><time>{{ t.dueDate }}</time>
            </div>
            <h4>{{ t.title }}</h4>
            <p class="daily-meta">
              {{ t.plotName }} · {{ word(t.taskType) }} ·
              {{ t.assigneeName || "未分配" }}
            </p>
            <p v-if="t.note" class="daily-note">{{ t.note }}</p>
            <p v-if="t.blockedReason" class="daily-blocker">
              <b>待协调：</b>{{ t.blockedReason }}
            </p>
            <p v-if="t.completionNote" class="daily-receipt">
              <b>作业回执：</b>{{ t.completionNote }} · {{ t.actualAreaMu }} 亩
            </p>
            <div class="daily-task-footer">
              <small>{{ methods[t.method] || "尚未安排资源" }}</small>
              <div class="daily-task-actions">
                <button @click="history(t)">记录</button>
                <button
                  v-if="admin && ['PENDING', 'RUNNING'].includes(t.status)"
                  @click="open('plan', t)"
                >
                  调整安排
                </button>
                <template v-if="canWork(t)"
                  ><button
                    v-if="t.status === 'PENDING'"
                    class="primary"
                    @click="open('progress', t, 'RUNNING')"
                  >
                    开始任务</button
                  ><button
                    v-if="['PENDING', 'RUNNING'].includes(t.status)"
                    @click="open('progress', t, 'BLOCKED')"
                  >
                    报告受阻</button
                  ><button
                    v-if="t.status === 'RUNNING'"
                    class="primary"
                    @click="open('progress', t, 'COMPLETED')"
                  >
                    完成回执
                  </button></template
                >
                <button
                  v-if="admin && ['PENDING', 'RUNNING'].includes(t.status)"
                  @click="open('progress', t, 'CANCELLED')"
                >
                  取消任务
                </button>
              </div>
            </div>
          </article>
        </section>
        <aside class="daily-right">
          <section class="panel daily-risk">
            <div class="daily-section-title">
              <div>
                <p class="eyebrow">FIELD NOTES</p>
                <h3>现场问题</h3>
              </div>
              <span class="count">{{ openIssues.length }}</span>
            </div>
            <p class="muted">上报 → 安排 → 作业回执 → 复核关闭</p>
            <p v-if="!openIssues.length" class="daily-empty">
              暂无待跟进问题。巡田发现异常时可随时上报。
            </p>
            <article
              v-for="i in openIssues"
              :key="i.id"
              class="daily-issue"
              :data-issue-id="i.id"
            >
              <div class="daily-task-line">
                <b>{{ i.plotName }}</b
                ><span
                  :class="['daily-status', { warning: i.severity === 'HIGH' }]"
                  >{{ i.severity === "HIGH" ? "优先处理" : "常规跟进" }}</span
                >
              </div>
              <p>{{ i.description }}</p>
              <small
                >{{ categories[i.category] }} · {{ i.reporterName }}上报 ·
                {{ word(i.status) }}</small
              >
              <button
                v-if="admin && i.status === 'OPEN'"
                class="outline"
                @click="open('plan', i)"
              >
                安排处理
              </button>
              <button
                v-if="admin && i.taskStatus === 'COMPLETED'"
                class="primary"
                @click="open('review', i)"
              >
                复核关闭
              </button>
              <small v-if="i.status === 'ASSIGNED'">{{
                i.taskStatus === "COMPLETED"
                  ? "作业已完成，等待农场主确认效果"
                  : "处理任务已派发，请跟踪农事队列"
              }}</small>
            </article>
            <details v-if="data.issues.some((i) => i.status === 'RESOLVED')">
              <summary>
                已关闭问题
                {{
                  data.issues.filter((i) => i.status === "RESOLVED").length
                }}
                项
              </summary>
              <article
                v-for="i in data.issues.filter((i) => i.status === 'RESOLVED')"
                :key="i.id"
                class="daily-issue"
              >
                <b>{{ i.plotName }} · {{ categories[i.category] }}</b>
                <p>{{ i.description }}</p>
                <small>复核：{{ i.reviewNote }}</small>
              </article>
            </details>
          </section>
          <section class="panel daily-season">
            <p class="eyebrow">THIS SEASON</p>
            <h3>我的地块</h3>
            <div v-for="p in farmPlots" :key="p.id" class="daily-plot">
              <span
                >{{ p.name }}<small>{{ p.crop || "作物待填写" }}</small></span
              ><b>{{ p.areaMu }} 亩</b>
            </div>
            <button class="outline" @click="jump('plantings')">
              查看种植计划 →
            </button>
          </section>
          <section class="daily-tip">
            <h3>无人机缺位，如何补位？</h3>
            <p>
              先记录缺口，调整负责人和作业方式，再用季度对照检查人工能力是否足够。
            </p>
            <button @click="jump('simulation')">打开方案演练 →</button>
          </section>
        </aside>
      </div>
    </template>
    <dialog
      ref="dialogElement"
      class="daily-dialog"
      @cancel="saving ? $event.preventDefault() : close()"
    >
      <template v-if="modal"
        ><div class="daily-section-title">
          <h3>
            {{
              {
                issue: "巡田上报",
                plan: issueId ? "安排问题处理" : "安排农事",
                progress: word(form.status) + " · 作业记录",
                review: "复核关闭现场问题",
                history: "作业记录",
              }[modal]
            }}
          </h3>
          <button
            type="button"
            aria-label="关闭表单"
            @click="close"
            :disabled="saving"
          >
            ×
          </button>
        </div>
        <form v-if="modal !== 'history'" @submit.prevent="submit">
          <template v-if="modal === 'issue' || modal === 'plan'"
            ><label
              >所属地块<select
                v-model="form.plotId"
                required
                :disabled="modal === 'plan' && !!target"
              >
                <option value="" disabled>选择地块</option>
                <option v-for="p in farmPlots" :key="p.id" :value="p.id">
                  {{ p.name }} · {{ p.crop }} · {{ p.areaMu }} 亩
                </option>
              </select></label
            ></template
          >
          <template v-if="modal === 'issue'"
            ><div class="daily-form-grid">
              <label
                >问题类型<select v-model="form.category">
                  <option v-for="(v, k) in categories" :key="k" :value="k">
                    {{ v }}
                  </option>
                </select></label
              ><label
                >跟进优先级<select v-model="form.severity">
                  <option value="HIGH">优先处理</option>
                  <option value="NORMAL">常规跟进</option>
                </select></label
              >
            </div>
            <label
              >现场情况<textarea
                v-model.trim="form.description"
                rows="4"
                maxlength="500"
                required
                placeholder="记录受影响区域、看到的现象以及目前缺少的资源"
              /></label
          ></template>
          <template v-if="modal === 'plan'"
            ><label
              >任务名称<input
                v-model.trim="form.title"
                required
                maxlength="120"
            /></label>
            <div class="daily-form-grid">
              <label
                >农事类型<select v-model="form.taskType">
                  <option
                    v-for="k in [
                      'INSPECTION',
                      'PROTECTION',
                      'IRRIGATION',
                      'FERTILIZING',
                      'SOWING',
                      'HARVEST',
                    ]"
                    :key="k"
                    :value="k"
                  >
                    {{ word(k) }}
                  </option>
                </select></label
              ><label
                >计划日期<input type="date" v-model="form.dueDate" required
              /></label>
            </div>
            <label
              >负责人<select v-model="form.assigneeId" required>
                <option value="" disabled>选择执行人员</option>
                <option v-for="m in data.crew" :key="m.id" :value="m.id">
                  {{ m.displayName }} · {{ word(m.role) }}
                </option>
              </select></label
            ><label
              >计划作业方式<select v-model="form.method">
                <option v-for="(v, k) in methods" :key="k" :value="k">
                  {{ v }}
                </option>
              </select></label
            ><label
              >安排说明<textarea
                v-model="form.note"
                rows="3"
                maxlength="500"
                placeholder="注明设备/服务队、能力与作业范围；未落实时保留资源待确认"
              /></label
          ></template>
          <template v-if="modal === 'progress'"
            ><p class="daily-context">
              {{ target.plotName }} · {{ target.title }}
            </p>
            <label
              >实际作业方式<select v-model="form.method">
                <option v-for="(v, k) in methods" :key="k" :value="k">
                  {{ v }}
                </option>
              </select></label
            ><label v-if="form.status === 'COMPLETED'"
              >实际完成面积（亩）<input
                type="number"
                v-model.number="form.actualAreaMu"
                min="0.01"
                :max="target.plotAreaMu"
                step="0.01"
                required /></label
            ><label
              >{{
                form.status === "BLOCKED"
                  ? "受阻原因与所需支持"
                  : form.status === "COMPLETED"
                    ? "实际作业结果"
                    : "操作说明"
              }}<textarea
                v-model.trim="form.note"
                rows="4"
                maxlength="500"
                required
                :placeholder="
                  form.status === 'BLOCKED'
                    ? '例如：无人机检修中，需要人工队伍补位。'
                    : '填写现场操作、覆盖范围和需要后续跟进的情况。'
                "
              />
            </label>
            <p v-if="form.status === 'COMPLETED'" class="muted">
              完成回执记录本次作业；关联的现场问题仍需农场主复核，避免把完成作业直接等同于问题解决。
            </p></template
          >
          <label v-if="modal === 'review'"
            >复核结果<textarea
              v-model.trim="form.note"
              rows="4"
              maxlength="500"
              required
              placeholder="填写复查时间、现场变化和关闭依据"
            />
          </label>
          <p v-if="error" class="error" role="alert">{{ error }}</p>
          <div class="modal-actions">
            <button
              type="button"
              class="outline"
              @click="close"
              :disabled="saving"
            >
              取消</button
            ><button class="primary" :disabled="saving">
              {{ saving ? "保存中…" : "保存记录" }}
            </button>
          </div>
        </form>
        <template v-else
          ><p>{{ target.title }} · {{ target.plotName }}</p>
          <p v-if="error" class="error" role="alert">{{ error }}</p>
          <p v-if="!logs.length" class="muted">
            暂无作业回执。历史任务的状态变更可在操作日志查看。
          </p>
          <article v-for="l in logs" :key="l.id" class="daily-log">
            <b>{{ word(l.action) }} · {{ l.actorName }}</b>
            <p>{{ l.note || "已安排" }}</p>
            <small
              >{{ methods[l.method] }} · {{ l.actualAreaMu }} 亩 ·
              {{ String(l.occurredAt).replace("T", " ").slice(0, 19) }}</small
            >
          </article></template
        >
      </template>
    </dialog>
  </div>
</template>
