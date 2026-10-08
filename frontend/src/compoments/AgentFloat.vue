<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { api } from "../api";
import "./agent-float.css";

const props = defineProps({
  identity: Object,
  farms: Array,
  farmId: String,
});
const open = ref(false);
const busy = ref(false);
const llmOnline = ref(false);
const input = ref("");
const inputBox = ref(null);
const scrollBox = ref(null);
const presets = [
  "今天最紧急的农事？",
  "整个农场有哪些地块和种植计划？",
  "当前有什么经营风险？",
  "最近产量怎么样？",
];
const messages = ref([]);
const localFarms = ref(props.farms || []);
let alive = true;
let typeTimer = null;

const HISTORY_KEY = "zhinong-agent-history";
const MAX_HISTORY = 20;

function persistHistory() {
  // 全量保存最近条目，恢复时再清洗；不依赖 phase 状态
  const keep = messages.value.slice(-MAX_HISTORY * 2);
  try {
    localStorage.setItem(HISTORY_KEY, JSON.stringify(keep));
  } catch {
    /* 存储不可用时忽略，仅影响刷新恢复 */
  }
}

watch(messages, persistHistory, { deep: true });

watch(
  () => props.farms,
  (v) => {
    if (v?.length) localFarms.value = v;
  },
);

const farmId = computed(() => props.farmId || localFarms.value[0]?.id || "");
const farmName = computed(
  () =>
    localFarms.value.find((f) => f.id === farmId.value)?.name ||
    "当前农场",
);

onMounted(async () => {
  // 恢复上次对话历史（仅完整问答，中间态丢弃）
  try {
    const raw = localStorage.getItem(HISTORY_KEY);
    if (raw) {
      const saved = JSON.parse(raw);
      if (Array.isArray(saved)) {
        messages.value = saved
          .filter(
            (m) =>
              m.role === "user" ||
              (m.role === "assistant" && (m.content || "").length),
          )
          .map((m) => ({
            ...m,
            tools: Array.isArray(m.tools) ? m.tools : [],
            sources: Array.isArray(m.sources) ? m.sources : [],
            phase: "done",
          }));
      }
    }
  } catch {
    /* 损坏的历史忽略 */
  }
  // 看板等页面可能未预载农场列表，组件自治拉取
  if (!localFarms.value.length) {
    try {
      localFarms.value = await api("/farms");
    } catch {
      /* 保持空列表 */
    }
  }
  try {
    const s = await api("/ai/status");
    if (alive) llmOnline.value = !!s.llm;
  } catch {
    /* 探测失败按离线处理 */
  }
});
onBeforeUnmount(() => {
  alive = false;
  if (typeTimer) clearInterval(typeTimer);
});

function toggle() {
  open.value = !open.value;
  if (open.value) scrollDown();
}

function pushUser(text) {
  messages.value.push({ role: "user", content: text, phase: "done" });
}

function newAssistant() {
  const msg = { role: "assistant", content: "", tools: [], phase: "thinking", elapsed: null, sources: [], mode: "llm" };
  messages.value.push(msg);
  return msg;
}

function buildHistory() {
  // 最近 6 轮完整问答（跳过中间态与空内容），供后端多轮记忆
  return messages.value
    .filter(
      (m) =>
        (m.role === "user" || m.role === "assistant") &&
        (m.content || "").trim(),
    )
    .slice(-12)
    .map((m) => ({ role: m.role, content: m.content }));
}

async function ask(question) {
  const q = (question || input.value || "").trim();
  if (!q || busy.value) return;
  input.value = "";
  pushUser(q);
  const assistant = newAssistant();
  busy.value = true;
  scrollDown();
  try {
    // 思考态至少停留片刻，让演示可见
    await wait(650);
    const r = await api("/ai/ask", "POST", {
      farmId: farmId.value,
      question: q,
      history: buildHistory(),
    });
    if (!alive) return;
    assistant.tools = r.toolsCalled || [];
    assistant.elapsed = r.elapsedMs;
    assistant.sources = r.sources || [];
    assistant.mode = r.mode || "llm";
    // 工具轨迹逐条点亮
    if (assistant.tools.length) {
      assistant.phase = "tools";
      for (let i = 0; i < assistant.tools.length; i++) {
        await wait(260);
        assistant.tools[i] = { name: assistant.tools[i], state: "done" };
        scrollDown();
      }
    }
    // 流式打字输出
    assistant.phase = "streaming";
    await typeOut(assistant, r.answer || "");
    assistant.phase = "done";
    scrollDown();
    persistHistory(); // 回答完成后显式落盘，刷新可恢复
  } catch (e) {
    if (!alive) return;
    assistant.phase = "done";
    assistant.content = "暂时无法回答，请稍后再试。";
    persistHistory();
  } finally {
    if (alive) busy.value = false;
  }
}

function wait(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

async function typeOut(msg, text) {
  msg.content = "";
  let i = 0;
  while (i < text.length) {
    if (!alive) return;
    i += 3;
    msg.content = text.slice(0, i);
    scrollDown();
    await wait(16);
  }
  msg.content = text;
}

function scrollDown() {
  requestAnimationFrame(() => {
    if (scrollBox.value) scrollBox.value.scrollTop = scrollBox.value.scrollHeight;
  });
}

function onKeydown(e) {
  if (e.key === "Enter" && !e.shiftKey) {
    e.preventDefault();
    ask();
  }
}

function toolLabel(name) {
  const map = {
    get_farm_summary: "农场概况",
    get_farm_profile: "农场档案",
    get_plots: "地块数据",
    get_plantings: "种植计划",
    get_production: "生产记录",
    get_devices: "设备监测",
    get_pending_tasks: "农事任务",
    get_open_issues: "现场问题",
    get_audit_logs: "操作日志",
    get_crew: "成员信息",
    get_farms: "农场列表",
    get_all_farm_summaries: "全场概况",
    get_simulations: "经营模拟",
    get_simulation_result: "模拟结果",
    create_task: "安排农事",
    update_task_progress: "更新任务",
    report_issue: "上报问题",
    get_tenants: "租户列表",
  };
  return map[name] || name;
}

function fmtElapsed(ms) {
  return ((ms || 0) / 1000).toFixed(1) + "s";
}
</script>

<template>
  <div v-if="identity" class="agent-float">
    <div v-if="open" class="agent-panel" role="dialog" aria-label="经营助手">
      <header class="agent-head">
        <div class="agent-avatar"><span>禾</span></div>
        <div class="agent-title">
          <b>经营助手</b>
          <small>
            <i
              :class="['agent-dot', { on: llmOnline }]"
              :aria-label="llmOnline ? '模型在线' : '规则模式'"
            ></i>
            {{ llmOnline ? "模型在线 · " : "规则模式 · " }}{{ farmName }}
          </small>
        </div>
        <button class="agent-close" aria-label="收起助手" @click="toggle">
          —
        </button>
      </header>

      <div ref="scrollBox" class="agent-stream">
        <div v-if="!messages.length" class="agent-empty">
          <div class="agent-empty-mark">问</div>
          <p>问一句农场经营，助手会先查数据再回答。</p>
          <div class="agent-chips">
            <button v-for="c in presets" :key="c" @click="ask(c)">
              {{ c }}
            </button>
          </div>
        </div>

        <template v-for="(m, i) in messages" :key="i">
          <div v-if="m.role === 'user'" class="agent-row user">
            <div class="agent-bubble">{{ m.content }}</div>
          </div>
          <div v-else class="agent-row assistant">
            <div class="agent-bubble">
              <div v-if="m.phase === 'thinking'" class="agent-thinking">
                <span class="agent-matrix"></span>
                <span>正在分析农场数据…</span>
              </div>
              <div
                v-else-if="m.tools.length"
                class="agent-tools"
                :class="{ show: m.phase !== 'thinking' }"
              >
                <span class="agent-tools-label">已查询</span>
                <span
                  v-for="(t, ti) in m.tools"
                  :key="ti"
                  class="agent-tool-chip"
                  :class="{ lit: typeof t === 'object' && t.state === 'done' }"
                >
                  <i>✓</i>{{ typeof t === "string" ? toolLabel(t) : toolLabel(t.name) }}
                </span>
              </div>
              <p
                v-if="m.content"
                class="agent-answer"
                :class="{ streaming: m.phase === 'streaming' }"
              >
                {{ m.content }}
              </p>
              <div
                v-if="m.phase === 'done' && (m.sources.length || m.elapsed)"
                class="agent-meta"
              >
                <span v-if="m.mode === 'llm'">DeepSeek 生成</span>
                <span v-else>规则引擎</span>
                <span v-if="m.elapsed">耗时 {{ fmtElapsed(m.elapsed) }}</span>
                <span v-if="m.sources.length">{{
                  m.sources.join(" · ")
                }}</span>
              </div>
            </div>
          </div>
        </template>

        <div v-if="busy" class="agent-row assistant">
          <div class="agent-bubble agent-thinking">
            <span class="agent-matrix"></span>
            <span>正在生成回答…</span>
          </div>
        </div>
      </div>

      <footer class="agent-composer">
        <textarea
          ref="inputBox"
          v-model="input"
          rows="1"
          :disabled="busy"
          :placeholder="'问点农场经营…（Enter 发送）'"
          aria-label="经营问题"
          @keydown="onKeydown"
        ></textarea>
        <button
          class="agent-send"
          :disabled="busy || !input.trim()"
          aria-label="发送"
          @click="ask()"
        >
          ➤
        </button>
      </footer>
    </div>

    <button
      class="agent-fab"
      :aria-expanded="open"
      :aria-label="open ? '收起经营助手' : '打开经营助手'"
      @click="toggle"
    >
      <span class="agent-fab-mark"><i></i>禾</span>
      <small v-if="!open">经营助手</small>
      <i v-if="llmOnline" class="agent-fab-dot" aria-hidden="true"></i>
    </button>
  </div>
</template>
