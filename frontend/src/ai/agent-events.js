// Agent 运行状态 reducer：纯函数，不依赖任何框架。
// 事件模型见 docs/superpowers/specs/2026-10-09-ai-agent-interface-design.md 的“消息与活动模型”。

export function createAgentRun(question, requestId) {
  return {
    requestId,
    question,
    status: "submitted",
    activities: [],
    text: "",
    messageId: null,
    diagnostic: null,
    error: null,
    lastSequence: 0,
  };
}

function upsertActivity(activities, activity) {
  const index = activities.findIndex((a) => a.id === activity.id);
  if (index === -1) return [...activities, activity];
  const next = activities.slice();
  next[index] = { ...activities[index], ...activity };
  return next;
}

function finalizeRunningActivities(activities, status) {
  return activities.map((a) => (a.status === "running" || a.status === "pending" ? { ...a, status } : a));
}

export function reduceAgentEvent(state, event) {
  if (event.sequence != null && event.sequence <= state.lastSequence) return state;
  const lastSequence = event.sequence != null ? event.sequence : state.lastSequence;
  switch (event.type) {
    case "run.started":
      return { ...state, status: "running", lastSequence };
    case "activity.started":
    case "activity.updated":
      return { ...state, activities: upsertActivity(state.activities, event.activity), lastSequence };
    case "activity.completed":
      return {
        ...state,
        activities: upsertActivity(state.activities, { ...event.activity, status: event.activity?.status || "completed" }),
        lastSequence,
      };
    case "message.delta":
      return { ...state, text: state.text + (event.delta || ""), lastSequence };
    case "message.completed":
      return { ...state, messageId: event.messageId ?? state.messageId, lastSequence };
    case "run.completed":
      return {
        ...state,
        status: "completed",
        activities: finalizeRunningActivities(state.activities, "completed"),
        lastSequence,
      };
    case "run.error":
      return {
        ...state,
        status: "error",
        error: event.error ?? state.error,
        diagnostic: event.diagnostic ?? state.diagnostic,
        activities: finalizeRunningActivities(state.activities, "error"),
        lastSequence,
      };
    default:
      return { ...state, lastSequence };
  }
}

export function cancelAgentRun(state) {
  // 停止/未完成的活动标记为 error（附“已停止”说明），不能标成 completed——那会谎称已完成。
  const stopped = state.activities.map((a) =>
    a.status === "running" || a.status === "pending" ? { ...a, status: "error", detail: a.detail || "已停止" } : a,
  );
  return { ...state, status: "cancelled", activities: stopped };
}

// 组合当前展示用的消息列表：在不改动已持久化历史的前提下，
// 追加这次运行的临时用户消息和流式中的助手占位消息。
// run 为空时直接返回原始历史（不新建数组也可以，但为了调用方一致性这里仍返回新数组的浅拷贝）。
export function visibleMessages(history, run) {
  if (!run) return history;
  return [
    ...history,
    { role: "user", content: run.question, temporary: true },
    { role: "assistant", content: run.text, temporary: true, pending: true, run },
  ];
}
