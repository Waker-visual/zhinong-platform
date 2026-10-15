<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import { api } from "../api.js";
import { defaultConversationAdapter } from "./agent-adapter.js";
import { createAgentRun, reduceAgentEvent, cancelAgentRun, visibleMessages } from "./agent-events.js";
import AssistantText from "./AssistantText.vue";
import AiActivityDisclosure from "./AiActivityDisclosure.vue";
import AiStreamingStatus from "./AiStreamingStatus.vue";
import AiMessageActions from "./AiMessageActions.vue";
import AiApprovalCard from "./AiApprovalCard.vue";
import AppIcon from "../ui/AppIcon.vue";
import SproutCompanion from "./SproutCompanion.vue";
import { assistantPageContext, floatingTopics, prepareFloatingQuestion } from "./floating-assistant-model.js";
import "./floating-assistant.css";

const props = defineProps({
  farmId: { type: String, required: true },
  farmName: { type: String, default: "当前农场" },
  pageId: { type: String, default: "daily" },
  focus: { type: Object, default: () => ({}) },
  role: { type: String, default: "VIEWER" },
});
const emit = defineEmits(["open-full", "irrigation"]);
const open = ref(false), loading = ref(false), sending = ref(false), error = ref(""), notice = ref("");
const conversation = ref(null), messages = ref([]), question = ref(""), run = ref(null), status = ref(null);
const scroller = ref(null), input = ref(null), trigger = ref(null), panel = ref(null), topicIndex = ref(0);
const followOutput = ref(true), showJump = ref(false), contextOpen = ref(false);
const focusBeforeOpen = ref(null);
const writer = computed(() => ["ADMIN", "OPERATOR"].includes(props.role));
let alive = true, requestController = null, loadVersion = 0, requestGeneration = 0, activeQuestion = "";
let lockedBodyOverflow = "";
const mobileSheet = () => typeof matchMedia === "function" && matchMedia("(max-width: 760px)").matches;
function lockBodyScroll() {
  if (!mobileSheet() || typeof document === "undefined") return;
  lockedBodyOverflow = document.body.style.overflow;
  document.body.style.overflow = "hidden";
}
function unlockBodyScroll() {
  if (typeof document === "undefined") return;
  document.body.style.overflow = lockedBodyOverflow;
  lockedBodyOverflow = "";
}
function focusableElements() {
  return panel.value?.querySelectorAll("button:not([disabled]), textarea:not([disabled]), [href], input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex=\"-1\"])") || [];
}
function trapFocus(event) {
  if (event.key !== "Tab" || !mobileSheet()) return;
  const items = [...focusableElements()];
  if (!items.length) return;
  const first = items[0], last = items.at(-1);
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
}
function handleViewport() {
  if (!panel.value || !mobileSheet() || !window.visualViewport) return;
  panel.value.style.setProperty("--floating-viewport-height", `${window.visualViewport.height}px`);
}
function addViewportListener() { window.visualViewport?.addEventListener("resize", handleViewport); handleViewport(); }
function removeViewportListener() { window.visualViewport?.removeEventListener("resize", handleViewport); panel.value?.style.removeProperty("--floating-viewport-height"); }
const topics = floatingTopics;
const context = computed(() => assistantPageContext(props.pageId, props.focus));
const pageLabel = computed(() => ({ daily: "今日农场", operations: "运行概览", dashboard: "农场概览", cameras: "田间实景", devices: "设备台账", plots: "地块管理", plantings: "种植计划", tasks: "农事任务", production: "生产记录", ai: "农场 AI 助手" }[props.pageId] || "当前页面"));
const displayed = computed(() => visibleMessages(messages.value, run.value));
const disabled = computed(() => loading.value || sending.value);
const hasConversation = computed(() => !!conversation.value?.id);
const reducedMotion = () => typeof matchMedia === "function" && matchMedia("(prefers-reduced-motion: reduce)").matches;

async function loadShortcut() {
  if (!props.farmId) return;
  const version = ++loadVersion;
  loading.value = true; error.value = "";
  try {
    const [rows, aiStatus] = await Promise.all([
      api(`/ai/conversations?farmId=${encodeURIComponent(props.farmId)}`),
      api("/ai/status"),
    ]);
    if (!alive || version !== loadVersion) return;
    conversation.value = rows.find(row => row.surface === "floating") || null;
    status.value = aiStatus;
    messages.value = conversation.value ? await api(`/ai/conversations/${conversation.value.id}/messages`) : [];
  } catch (e) {
    if (alive && version === loadVersion) error.value = e.message;
  } finally {
    if (alive && version === loadVersion) loading.value = false;
  }
}
async function openAssistant() {
  focusBeforeOpen.value = document.activeElement;
  open.value = true;
  lockBodyScroll();
  await nextTick();
  addViewportListener();
  input.value?.focus();
  if (!conversation.value && !loading.value) await loadShortcut();
}
function closeAssistant() {
  if (sending.value) return;
  open.value = false;
  removeViewportListener();
  unlockBodyScroll();
  nextTick(() => (focusBeforeOpen.value?.isConnected ? focusBeforeOpen.value : trigger.value)?.focus());
}
function handlePanelKeydown(event) {
  if (event.key === "Escape") { event.preventDefault(); closeAssistant(); return; }
  trapFocus(event);
}
function handleBackdropClick(event) {
  if (event.target === event.currentTarget) closeAssistant();
}
function resizeInput() {
  if (!input.value) return;
  input.value.style.height = "auto";
  input.value.style.height = `${Math.min(input.value.scrollHeight, 150)}px`;
}
function handleScroll() {
  const el = scroller.value; if (!el) return;
  const distance = el.scrollHeight - el.scrollTop - el.clientHeight;
  followOutput.value = distance <= 72; showJump.value = !followOutput.value;
}
async function scrollLatest(instant = false) {
  await nextTick(); const el = scroller.value; if (!el) return;
  el.scrollTo({ top: el.scrollHeight, behavior: instant || reducedMotion() ? "auto" : "smooth" });
  followOutput.value = true; showJump.value = false;
}
async function chooseTopic(topic) {
  const result = prepareFloatingQuestion(question.value, topic);
  if (!result.changed) { notice.value = "输入框里已有问题，我先帮你保留。"; return; }
  question.value = result.question; topicIndex.value++;
  await nextTick(); resizeInput(); input.value?.focus();
}
async function ensureConversation() {
  if (conversation.value?.id) return conversation.value;
  conversation.value = await api("/ai/conversations", "POST", { farmId: props.farmId, surface: "floating" });
  return conversation.value;
}
async function send() {
  const text = question.value.trim();
  if (!text || disabled.value || text.length > 2000) return;
  error.value = ""; notice.value = ""; question.value = ""; activeQuestion = text; sending.value = true;
  const generation = ++requestGeneration;
  const controller = new AbortController();
  requestController = controller;
  try {
    const chat = await ensureConversation();
    const requestId = crypto.randomUUID();
    run.value = createAgentRun(text, requestId);
    await scrollLatest();
    for await (const event of defaultConversationAdapter({
      farmId: props.farmId, conversationId: chat.id, question: text, requestId,
      pageId: context.value.pageId, focusType: context.value.focusType, focusId: context.value.focusId,
      signal: controller.signal,
    })) {
      if (!alive || generation !== requestGeneration) break;
      run.value = reduceAgentEvent(run.value, event);
      if (event.type === "message.delta" && followOutput.value) await scrollLatest();
    }
    if (generation !== requestGeneration) return;
    if (run.value?.status === "completed") {
      messages.value = await api(`/ai/conversations/${chat.id}/messages`);
      run.value = null;
    } else if (run.value?.status === "error") {
      question.value = text;
    }
  } catch (e) {
    if (generation === requestGeneration && !e?.cancelled && alive) {
      error.value = e.message; question.value = text;
      if (run.value) run.value = { ...run.value, status: "error", error: e.message };
    }
  } finally {
    if (generation === requestGeneration) { sending.value = false; requestController = null; }
  }
}
function stop() {
  if (run.value?.status === "completed") return;
  requestGeneration++;
  activeQuestion = activeQuestion || run.value?.question || "";
  requestController?.abort();
  if (run.value) {
    question.value = activeQuestion || run.value.question;
    run.value = cancelAgentRun(run.value);
  }
  sending.value = false;
  requestController = null;
}
function retry() { if (run.value?.question) { question.value = run.value.question; send(); } }
function openFull() {
  if (sending.value) return;
  const payload = { conversationId: conversation.value?.id, question: question.value };
  closeAssistant();
  emit("open-full", payload);
}
function openIrrigation(target = null) {
  if (sending.value) return;
  closeAssistant();
  emit("irrigation", target);
}
function time(value) { return value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : ""; }
watch(() => [question.value, open.value], () => nextTick(resizeInput));
watch(() => props.farmId, () => {
  requestGeneration++;
  requestController?.abort();
  removeViewportListener();
  unlockBodyScroll();
  run.value = null; conversation.value = null; messages.value = []; question.value = ""; activeQuestion = ""; open.value = false; loadVersion++;
});
onMounted(() => loadShortcut());
onUnmounted(() => { alive = false; requestGeneration++; requestController?.abort(); removeViewportListener(); unlockBodyScroll(); });
</script>

<template>
  <div class="floating-assistant" :class="{ 'is-open': open }">
    <button ref="trigger" type="button" class="floating-assistant-trigger" aria-label="打开小禾农场助手" :aria-expanded="open" title="打开小禾农场助手" @click="open ? closeAssistant() : openAssistant()">
      <SproutCompanion compact :interactive="false" :state="run ? (run.status === 'error' ? 'error' : run.status === 'completed' ? 'done' : 'thinking') : 'idle'" :message="run ? '' : ''" :disabled="disabled" />
      <span v-if="!open && (run?.status === 'completed' || error)" class="floating-assistant-dot" aria-hidden="true"></span>
    </button>
    <div v-if="open && mobileSheet()" class="floating-assistant-backdrop" aria-hidden="true" @click="handleBackdropClick"></div>
    <div v-if="open" ref="panel" class="floating-assistant-panel" role="dialog" aria-modal="false" aria-labelledby="floating-assistant-title" @keydown="handlePanelKeydown">
      <header class="floating-assistant-header">
        <div><p id="floating-assistant-title">小禾 <span>· 农场小助手</span></p><small>{{ farmName }} · {{ pageLabel }}</small></div>
        <div class="floating-assistant-header-actions"><span v-if="status" class="floating-assistant-status">{{ status.llm ? `云端模型 · ${status.model}` : "规则分析模式" }}</span><button type="button" class="floating-assistant-icon" aria-label="关闭小禾助手" @click="closeAssistant"><AppIcon name="close" /></button></div>
      </header>
      <button class="floating-assistant-context" type="button" :aria-expanded="contextOpen" @click="contextOpen = !contextOpen"><span>本次参考：{{ pageLabel }}</span><AppIcon name="chevronDown" /></button>
      <p v-if="contextOpen" class="floating-assistant-context-detail">发送问题时，服务端会按当前农场和页面范围重新读取有限资料；不会使用浏览器页面原文。</p>
      <p v-if="error" class="error floating-assistant-error" role="alert">{{ error }}</p>
      <div ref="scroller" class="floating-assistant-messages" role="log" aria-label="小禾对话记录" :aria-busy="!!run || loading" @scroll="handleScroll">
        <div v-if="loading" class="floating-assistant-loading" aria-hidden="true"><span class="skeleton"></span><span class="skeleton"></span><span class="skeleton" style="width:58%"></span></div>
        <template v-else-if="!displayed.length">
          <div class="floating-assistant-welcome"><p>把当前农事问题交给我，我会先看农场资料和这页可参考的信息。</p><button v-for="(topic,index) in topics" :key="topic.label" type="button" :disabled="disabled" @click="chooseTopic(topic)"><AppIcon :name="index === 1 ? 'devices' : index === 2 ? 'irrigation' : 'today'" />{{ topic.label }}</button></div>
        </template>
        <article v-for="(message,index) in displayed" v-else :key="message.id || 'tmp-'+index" class="floating-assistant-message" :class="message.role">
          <small>{{ message.role === 'user' ? '你' : '小禾' }}<span v-if="message.mode === 'rule'" class="ai-mode-badge">规则回退</span></small>
          <template v-if="message.pending"><AiActivityDisclosure :activities="message.run.activities" :run-status="message.run.status" /><AiApprovalCard v-for="activity in message.run.activities.filter(a => a.kind === 'approval')" :key="activity.id" :activity="activity" :writer="writer" @view-approval="openIrrigation" /><div class="floating-assistant-text"><AssistantText v-if="message.run.text" :text="message.run.text" :streaming="message.run.status === 'running'" /><div v-else class="ai-skeleton-lines" aria-hidden="true"><span class="skeleton"></span><span class="skeleton"></span></div></div><AiStreamingStatus :status="message.run.status" :diagnostic="message.run.error" :can-retry="message.run.status === 'error'" :using-sync-fallback="message.run.adapter === 'sync'" @retry="retry" /></template>
          <template v-else><AiApprovalCard v-for="activity in (message.activities || []).filter(a => a.kind === 'approval')" :key="activity.activityId || activity.id" :activity="activity" :writer="writer" @view-approval="openIrrigation" /><div class="floating-assistant-text"><AssistantText v-if="message.role === 'assistant'" :text="message.content" /><template v-else>{{ message.content }}</template></div><AiMessageActions :id="message.id" :text="message.content" :role="message.role" :time="message.createdAt ? { display: time(message.createdAt), full: time(message.createdAt), iso: message.createdAt } : null" :conversation-id="conversation?.id" /></template>
        </article>
      </div>
      <button v-if="showJump" class="floating-assistant-jump" type="button" @click="scrollLatest()"><AppIcon name="arrowDown" />回到最新</button>
      <p v-if="notice" class="floating-assistant-notice" role="status">{{ notice }}</p>
      <form class="floating-assistant-composer" @submit.prevent="send"><label class="sr-only" for="floating-question">询问小禾</label><textarea id="floating-question" ref="input" v-model="question" rows="1" maxlength="2000" :disabled="sending" placeholder="询问当前农场…" @keydown.enter.exact="e => { if (!e.isComposing) { e.preventDefault(); send(); } }"></textarea><button v-if="sending" type="button" class="floating-assistant-send" aria-label="停止生成" @click="stop"><AppIcon name="stop" /></button><button v-else type="submit" class="floating-assistant-send" :disabled="disabled || !question.trim()" aria-label="发送"><AppIcon name="arrowUp" /></button></form>
      <footer class="floating-assistant-footer"><div class="floating-assistant-footer-actions"><button type="button" @click="openFull">打开完整 AI</button><button type="button" @click="openIrrigation()">去灌溉管理</button></div><span>只读咨询 · 不会直接控制设备</span></footer>
    </div>
  </div>
</template>
