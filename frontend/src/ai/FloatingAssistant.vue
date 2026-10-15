<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import { api } from "../api.js";
import { aiSession } from "./ai-session.js";
import { visibleMessages } from "./agent-events.js";
import AssistantText from "./AssistantText.vue";
import AiActivityDisclosure from "./AiActivityDisclosure.vue";
import AiStreamingStatus from "./AiStreamingStatus.vue";
import AiMessageActions from "./AiMessageActions.vue";
import AiApprovalCard from "./AiApprovalCard.vue";
import AppIcon from "../ui/AppIcon.vue";
import SproutCompanion from "./SproutCompanion.vue";
import AiSourceNote from "./AiSourceNote.vue";
import { assistantPageContext, floatingTopics, prepareFloatingQuestion, sourceScopeLabel } from "./floating-assistant-model.js";
import "./floating-assistant.css";

const props = defineProps({
  farmId: { type: String, required: true },
  farmName: { type: String, default: "当前农场" },
  pageId: { type: String, default: "daily" },
  focus: { type: Object, default: () => ({}) },
  role: { type: String, default: "VIEWER" },
});
const emit = defineEmits(["open-full", "irrigation"]);
const open = ref(false), loading = ref(false), creating = ref(false), error = ref(""), notice = ref("");
const conversation = ref(null), messages = ref([]), question = ref(""), status = ref(null);
// 运行状态来自共享会话：小窗只展示属于自己快捷会话的那一次运行；完整页在生成同一会话时也会出现在这里。
const run = computed(() => aiSession.runFor(conversation.value?.id));
const sending = computed(() => creating.value || aiSession.state.sending);
const elsewhereBusy = computed(() => aiSession.state.sending && !run.value);
const unseenReply = ref(false);
const scroller = ref(null), input = ref(null), trigger = ref(null), panel = ref(null), topicIndex = ref(0);
const followOutput = ref(true), showJump = ref(false), contextOpen = ref(false);
const focusBeforeOpen = ref(null);
const writer = computed(() => ["ADMIN", "OPERATOR"].includes(props.role));
let alive = true, loadVersion = 0;
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
const contextLabel = computed(() => sourceScopeLabel(pageLabel.value, context.value));
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
  open.value = true; unseenReply.value = false;
  lockBodyScroll();
  await nextTick();
  addViewportListener();
  input.value?.focus();
  if (!conversation.value && !loading.value) await loadShortcut();
}
// 关闭小窗不会中止回答：生成会在后台继续，入口上的状态点提示完成或失败。
function closeAssistant() {
  open.value = false;
  removeViewportListener();
  unlockBodyScroll();
  // 触屏点按时焦点可能落在 body 或入口里禾苗图形的 tabindex=-1 元素上，这两种情况都归还给入口按钮本身。
  const opener = focusBeforeOpen.value;
  const restorable = opener?.isConnected && opener !== document.body && !trigger.value?.contains(opener);
  nextTick(() => (restorable ? opener : trigger.value)?.focus());
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
  const farm = props.farmId;
  const created = await api("/ai/conversations", "POST", { farmId: farm, surface: "floating" });
  if (!alive || farm !== props.farmId) return null; // 创建期间切换了农场：丢弃旧农场的会话
  conversation.value = created;
  return created;
}
// 发送结果只在仍是同一农场、同一会话时回写输入框；失败与停止都把原问题还给用户。
async function dispatch(work, text) {
  const farm = props.farmId;
  const result = await work();
  if (!alive || farm !== props.farmId) return;
  if (["error", "cancelled"].includes(result.status) && text && !question.value) question.value = text;
}
async function send() {
  const text = question.value.trim();
  if (!text || disabled.value || text.length > 2000) return;
  error.value = ""; notice.value = ""; question.value = "";
  creating.value = true;
  let chat = null;
  try { chat = await ensureConversation(); }
  catch (e) { if (alive) { error.value = e.message; question.value = text; } }
  finally { creating.value = false; }
  if (!chat) return;
  await scrollLatest();
  await dispatch(() => aiSession.send({ farmId: props.farmId, conversationId: chat.id, question: text, context: context.value, origin: "floating" }), text);
}
function stop() {
  const text = aiSession.stop();
  if (text && !question.value) question.value = text;
}
async function retry() {
  if (!run.value || sending.value) return;
  error.value = ""; question.value = "";
  await dispatch(() => aiSession.retry(), run.value?.question);
}
// 生成中也可以打开完整 AI：运行属于共享会话，完整页选中同一会话后会直接接着显示增量。
function openFull() {
  const payload = { conversationId: conversation.value?.id, question: question.value };
  question.value = "";
  closeAssistant();
  emit("open-full", payload);
}
function openIrrigation(target = null) {
  closeAssistant();
  emit("irrigation", target);
}
function applyCompleted(event) {
  if (event.conversationId !== conversation.value?.id) return;
  if (!open.value) unseenReply.value = true;
  if (!event.messages) return;
  messages.value = event.messages;
  if (followOutput.value) scrollLatest();
}
const unsubscribe = aiSession.subscribe(event => {
  if (!alive) return;
  if (event.type === "completed") applyCompleted(event);
  else if (event.type === "event" && event.event.type === "message.delta" && event.conversationId === conversation.value?.id && open.value && followOutput.value) scrollLatest();
});
function time(value) { return value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : ""; }
watch(() => [question.value, open.value], () => nextTick(resizeInput));
// 切换农场：共享会话由 App 统一 reset（中止回答、清空待重试请求），这里清空小窗自身的草稿和会话，
// 再按新农场重新读取快捷会话。
watch(() => props.farmId, () => {
  removeViewportListener();
  unlockBodyScroll();
  conversation.value = null; messages.value = []; question.value = ""; error.value = ""; notice.value = ""; open.value = false; loadVersion++;
  loadShortcut();
});
onMounted(() => loadShortcut());
onUnmounted(() => { alive = false; unsubscribe(); removeViewportListener(); unlockBodyScroll(); });
</script>

<template>
  <div class="floating-assistant" :class="{ 'is-open': open }">
    <button ref="trigger" type="button" class="floating-assistant-trigger" aria-label="打开小禾农场助手" :aria-expanded="open" title="打开小禾农场助手" @click="open ? closeAssistant() : openAssistant()">
      <SproutCompanion compact :interactive="false" :state="run ? (run.status === 'error' ? 'error' : run.status === 'completed' ? 'done' : 'thinking') : 'idle'" :message="run ? '' : ''" :disabled="disabled" />
      <span v-if="!open && (unseenReply || run?.status === 'error' || error)" class="floating-assistant-dot" :class="{ 'is-error': run?.status === 'error' || error }" aria-hidden="true"></span>
    </button>
    <div v-if="open && mobileSheet()" class="floating-assistant-backdrop" aria-hidden="true" @click="handleBackdropClick"></div>
    <div v-if="open" ref="panel" class="floating-assistant-panel" role="dialog" aria-modal="false" aria-labelledby="floating-assistant-title" @keydown="handlePanelKeydown">
      <header class="floating-assistant-header">
        <div><p id="floating-assistant-title">小禾 <span>· 农场小助手</span></p><small>{{ farmName }} · {{ pageLabel }}</small></div>
        <div class="floating-assistant-header-actions"><span v-if="status" class="floating-assistant-status">{{ status.llm ? `云端模型 · ${status.model}` : "规则分析模式" }}</span><button type="button" class="floating-assistant-icon" aria-label="关闭小禾助手" @click="closeAssistant"><AppIcon name="close" /></button></div>
      </header>
      <button class="floating-assistant-context" type="button" :aria-expanded="contextOpen" aria-controls="floating-assistant-context-detail" @click="contextOpen = !contextOpen"><span>本次参考：{{ contextLabel }}</span><AppIcon name="chevronDown" /></button>
      <p v-if="contextOpen" id="floating-assistant-context-detail" class="floating-assistant-context-detail">发送问题时，服务端会按当前农场和页面范围重新读取有限资料；不会使用浏览器页面原文。每条回答下方会记录实际参考的页面资料。</p>
      <p v-if="elsewhereBusy" class="floating-assistant-notice" role="status">完整 AI 页正在生成另一段回答，完成或停止后可以继续提问。</p>
      <p v-if="error" class="error floating-assistant-error" role="alert">{{ error }}</p>
      <div ref="scroller" class="floating-assistant-messages" role="log" aria-label="小禾对话记录" :aria-busy="!!run || loading" @scroll="handleScroll">
        <div v-if="loading" class="floating-assistant-loading" aria-hidden="true"><span class="skeleton"></span><span class="skeleton"></span><span class="skeleton" style="width:58%"></span></div>
        <template v-else-if="!displayed.length">
          <div class="floating-assistant-welcome"><p>把当前农事问题交给我，我会先看农场资料和这页可参考的信息。</p><button v-for="(topic,index) in topics" :key="topic.label" type="button" :disabled="disabled" @click="chooseTopic(topic)"><AppIcon :name="index === 1 ? 'devices' : index === 2 ? 'irrigation' : 'today'" />{{ topic.label }}</button></div>
        </template>
        <article v-for="(message,index) in displayed" v-else :key="message.id || 'tmp-'+index" class="floating-assistant-message" :class="message.role">
          <small>{{ message.role === 'user' ? '你' : '小禾' }}<span v-if="message.mode === 'rule'" class="ai-mode-badge">规则回退</span></small>
          <template v-if="message.pending"><AiActivityDisclosure :activities="message.run.activities" :run-status="message.run.status" /><AiApprovalCard v-for="activity in message.run.activities.filter(a => a.kind === 'approval')" :key="activity.id" :activity="activity" :writer="writer" @view-approval="openIrrigation" /><div class="floating-assistant-text"><AssistantText v-if="message.run.text" :text="message.run.text" :streaming="message.run.status === 'running'" /><div v-else class="ai-skeleton-lines" aria-hidden="true"><span class="skeleton"></span><span class="skeleton"></span></div></div><AiStreamingStatus :status="message.run.status" :diagnostic="message.run.error" :can-retry="message.run.status === 'error'" :using-sync-fallback="message.run.adapter === 'sync'" @retry="retry" /></template>
          <template v-else><AiApprovalCard v-for="activity in (message.activities || []).filter(a => a.kind === 'approval')" :key="activity.activityId || activity.id" :activity="activity" :writer="writer" @view-approval="openIrrigation" /><div class="floating-assistant-text"><AssistantText v-if="message.role === 'assistant'" :text="message.content" /><template v-else>{{ message.content }}</template></div><AiSourceNote v-if="message.role === 'assistant'" :activities="message.activities" compact /><AiMessageActions :id="message.id" :text="message.content" :role="message.role" :time="message.createdAt ? { display: time(message.createdAt), full: time(message.createdAt), iso: message.createdAt } : null" :conversation-id="conversation?.id" /></template>
        </article>
      </div>
      <button v-if="showJump" class="floating-assistant-jump" type="button" @click="scrollLatest()"><AppIcon name="arrowDown" />回到最新</button>
      <p v-if="notice" class="floating-assistant-notice" role="status">{{ notice }}</p>
      <form class="floating-assistant-composer" @submit.prevent="send"><label class="sr-only" for="floating-question">询问小禾</label><textarea id="floating-question" ref="input" v-model="question" rows="1" maxlength="2000" :disabled="sending" placeholder="询问当前农场…" @keydown.enter.exact="e => { if (!e.isComposing) { e.preventDefault(); send(); } }"></textarea><button v-if="sending" type="button" class="floating-assistant-send" aria-label="停止生成" @click="stop"><AppIcon name="stop" /></button><button v-else type="submit" class="floating-assistant-send" :disabled="disabled || !question.trim()" aria-label="发送"><AppIcon name="arrowUp" /></button></form>
      <footer class="floating-assistant-footer"><div class="floating-assistant-footer-actions"><button type="button" @click="openFull">打开完整 AI</button><button type="button" @click="openIrrigation()">去灌溉管理</button></div><span>只读咨询 · 不会直接控制设备</span></footer>
    </div>
  </div>
</template>
