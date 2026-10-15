import { reactive } from "vue";
import { api } from "../api.js";
import { defaultConversationAdapter } from "./agent-adapter.js";
import { createAgentRun, reduceAgentEvent, cancelAgentRun } from "./agent-events.js";

// 小窗与完整 AI 页共用的运行状态：同一时间每个登录页面只有一次回答在生成。
// 运行归属于 conversationId，而不是某个组件——生成中从小窗切到完整页、或从完整页离开回到小窗，
// 另一侧都能接着看到同一份增量、活动摘要和停止/重试状态。
//
// requestId 规则：同一会话、同一问题文字的重发（失败重试、停止后原样再发）复用上一次的 requestId，
// 服务端据此回放已落库的回答或重新完整生成，不会出现重复问题；问题或会话变化才换新编号。
// 重试同时沿用首次发送时的页面资料范围：同一个 requestId 必须对应同一份上下文意图。
export function createAiSession({ adapter = defaultConversationAdapter, loadMessages = id => api(`/ai/conversations/${id}/messages`), newRequestId = () => crypto.randomUUID() } = {}) {
  const state = reactive({ farmId: "", conversationId: "", run: null, sending: false, origin: "" });
  const listeners = new Set();
  let controller = null, pending = null, generation = 0;

  const emit = event => { for (const listener of [...listeners]) listener(event); };
  const subscribe = listener => { listeners.add(listener); return () => listeners.delete(listener); };
  const runFor = conversationId => (conversationId && state.conversationId === conversationId ? state.run : null);

  async function send({ farmId, conversationId, question, context = {}, origin = "" }) {
    const text = String(question || "").trim();
    if (!text || !conversationId || state.sending) return { status: "busy" };
    if (!pending || pending.text !== text || pending.conversationId !== conversationId)
      pending = { text, conversationId, farmId, context: { ...context }, origin, requestId: newRequestId() };
    const request = pending, ticket = ++generation, abort = new AbortController();
    controller = abort;
    Object.assign(state, { farmId, conversationId, origin: request.origin, sending: true, run: createAgentRun(text, request.requestId) });
    emit({ type: "started", conversationId, origin: request.origin });
    try {
      for await (const event of adapter({
        farmId, conversationId, question: text, requestId: request.requestId,
        pageId: request.context.pageId || undefined, focusType: request.context.focusType || undefined,
        focusId: request.context.focusId || undefined, signal: abort.signal,
      })) {
        if (ticket !== generation) return { status: "cancelled" };
        if (event.type === "conversation.titled") { emit({ type: "titled", conversationId: event.conversationId, title: event.title }); continue; }
        state.run = reduceAgentEvent(state.run, event);
        emit({ type: "event", conversationId, event });
      }
      if (ticket !== generation) return { status: "cancelled" };
      if (state.run?.status !== "completed") {
        emit({ type: "failed", conversationId, run: state.run });
        return { status: state.run?.status || "error", run: state.run };
      }
      pending = null;
      const completedRun = state.run;
      let messages = null;
      try { messages = await loadMessages(conversationId); } catch { /* 保留已完成的运行正文，下次打开会话再读取 */ }
      if (ticket !== generation) return { status: "cancelled" };
      if (messages) state.run = null;
      emit({ type: "completed", conversationId, messages, run: completedRun });
      return { status: "completed", messages, run: completedRun };
    } catch (error) {
      if (ticket !== generation || error?.cancelled) return { status: "cancelled" };
      state.run = { ...(state.run || createAgentRun(text, request.requestId)), status: "error", error: error?.message || "回答失败，请重试" };
      emit({ type: "failed", conversationId, run: state.run });
      return { status: "error", run: state.run, error };
    } finally {
      if (ticket === generation) { state.sending = false; controller = null; }
    }
  }

  // 停止：中止网络请求并把运行标记为已停止，返回原问题文字，交给发起停止的那一侧还原到输入框。
  function stop() {
    if (!state.sending || state.run?.status === "completed") return "";
    generation++;
    controller?.abort();
    controller = null;
    const text = state.run?.question || pending?.text || "";
    if (state.run) state.run = cancelAgentRun(state.run);
    state.sending = false;
    emit({ type: "stopped", conversationId: state.conversationId, question: text });
    return text;
  }

  function retry() {
    if (state.sending || !state.run || !pending || pending.conversationId !== state.conversationId) return Promise.resolve({ status: "busy" });
    return send({ farmId: pending.farmId, conversationId: pending.conversationId, question: pending.text, context: pending.context, origin: pending.origin });
  }

  // 用户在某一侧切换到别的会话：已结束（停止/失败）的运行只属于原会话，不再展示也不再可重试。
  function clearIdle() {
    if (state.sending) return;
    state.run = null; pending = null; state.conversationId = ""; state.origin = "";
  }

  // 切换农场或登出：立即中止，清空一切运行与待重试请求，避免旧农场回答写进新农场视图。
  function reset() {
    generation++;
    controller?.abort();
    controller = null; pending = null;
    Object.assign(state, { farmId: "", conversationId: "", run: null, sending: false, origin: "" });
    emit({ type: "reset" });
  }

  return { state, runFor, send, stop, retry, clearIdle, reset, subscribe };
}

export const aiSession = createAiSession();
