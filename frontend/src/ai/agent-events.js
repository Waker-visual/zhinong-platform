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
  return activities.map((a) => {
    // 审批活动（例如灌溉建议待确认）的 pending 是一个真实的、需要用户去别处确认的终态，
    // 不是“模型还没处理完”——运行结束/出错都不能把它悄悄改写成 completed 或 error，
    // 否则会出现“还没确认却显示已完成”的错误状态（阶段 E 验收截图发现的问题）。
    if (a.kind === "approval" && a.status === "pending") return a;
    return a.status === "running" || a.status === "pending" ? { ...a, status } : a;
  });
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

// 以下为纯 UI 文案/判定辅助函数：供 AiActivityDisclosure / AiActivityRow / AiStreamingStatus 复用，
// 并在 agent-events.test.js 中直接测试，避免可访问性文案散落在模板里无法单测。

const ACTIVITY_STATUS_LABEL = { pending: "等待中", running: "进行中", completed: "已完成", error: "出错" };

export function activityStatusLabel(status) {
  return ACTIVITY_STATUS_LABEL[status] || status;
}

// 折叠区运行中/出错时自动展开，完成/取消后收起；供 AiActivityDisclosure 使用。
export function shouldAutoOpenActivities(status) {
  return status === "submitted" || status === "running" || status === "error";
}

// 折叠区摘要短句：避免把活动数组直接暴露为技术字段。
// 完成态改为语义短句拼接（例如“读取 1 项资料 · 查询 2 项 · 1 项待你确认 · 用时 1.8s”），
// 不再用没有意义的“已完成 N 项操作”笼统带过；运行中展示当前活动的真实标题。
const KIND_SUMMARY_LABEL = { context: "资料", tool: "查询", source: "来源", task: "任务" };

export function activitySummaryText(activities, runStatus) {
  const total = activities.length;
  if (runStatus === "error") return "出错";
  if (runStatus === "cancelled") return `已停止 · ${total} 项`;
  if (runStatus === "submitted" || runStatus === "running") {
    const running = activities.find((a) => a.status === "running");
    if (running) return `${running.label} · ${total} 项`;
    return total ? `正在处理 · ${total} 项` : "正在准备…";
  }
  // completed
  const pendingApproval = activities.filter((a) => a.kind === "approval" && a.status === "pending").length;
  const errorCount = activities.filter((a) => a.status === "error").length;
  const parts = [];
  for (const kind of ["context", "tool", "source", "task"]) {
    const count = activities.filter((a) => a.kind === kind && a.status === "completed").length;
    if (count) parts.push(kind === "context" ? `读取 ${count} 项资料` : `${KIND_SUMMARY_LABEL[kind]} ${count} 项`);
  }
  if (errorCount) parts.push(`${errorCount} 项出错`);
  if (pendingApproval) parts.push(`${pendingApproval} 项待你确认`);
  const elapsed = totalElapsedMs(activities);
  if (elapsed != null) parts.push(`用时 ${formatActivityDuration(elapsed)}`);
  if (!parts.length) return total ? `已完成 ${total} 项操作` : "已完成";
  return parts.join(" · ");
}

// 从活动的开始/结束时间里取最早的 startedAt 到最晚的 finishedAt 作为整轮运行的总耗时；
// 没有任何可用时间戳时返回 null（摘要里不显示“用时”这一段，而不是显示 0s）。
function totalElapsedMs(activities) {
  let start = null, end = null;
  for (const a of activities) {
    const s = a.startedAt ? Date.parse(a.startedAt) : NaN;
    const f = a.finishedAt ? Date.parse(a.finishedAt) : NaN;
    if (!Number.isNaN(s) && (start === null || s < start)) start = s;
    if (!Number.isNaN(f) && (end === null || f > end)) end = f;
  }
  return start != null && end != null && end >= start ? end - start : null;
}

// 单行/摘要都用的紧凑耗时文案：100ms 以内不值得报告精确小数（“0.0s”看起来很滑稽），统一显示
// “<0.1s”；10秒内保留1位小数（例如“1.8s”），否则取整秒（例如“12s”）。
// 等宽数字在 CSS 里用 font-variant-numeric: tabular-nums 实现，这里只管文案本身。
// 注意：是否完全隐藏这段文案（而不是显示“<0.1s”）由调用方决定——活动行在 <100ms 时直接不渲染
// 耗时列（见 AiActivityRow.vue），折叠区摘要则始终显示这句话，哪怕总耗时也低于 100ms。
export function formatActivityDuration(ms) {
  if (ms == null || !Number.isFinite(ms) || ms < 0) return "";
  if (ms < 100) return "<0.1s";
  const seconds = ms / 1000;
  if (seconds < 10) return `${seconds.toFixed(1)}s`;
  return `${Math.round(seconds)}s`;
}

// 运行中状态条的实时计时文案：整数秒，例如“已用 12s”。
export function formatElapsedStatus(seconds) {
  return `已用 ${Math.max(0, Math.floor(seconds))}s`;
}

// 提交/读取/生成/完成/出错状态条文案；供 AiStreamingStatus 使用，也便于屏幕阅读器播报文案单测。
export function streamingStatusText(status, diagnostic) {
  switch (status) {
    case "submitted":
      return "正在读取当前农场资料…";
    case "running":
      return "正在生成回答…";
    case "completed":
      return "已完成";
    case "cancelled":
      return "已停止；若已回退到同步请求，仍可能在后台继续，刷新对话后可看到结果";
    case "error":
      return diagnostic || "回答失败，请重试";
    default:
      return "";
  }
}

// 光标仅在运行中且已经有正文时追加，避免空文本时出现孤立的闪烁光标。
export function shouldShowCaret(status, text) {
  return status === "running" && !!text;
}

// 灌溉审批活动的 target 解析：后端把目标编码进活动的标识字段（"irrigation-run:<runId>"），不需要
// 额外的事件字段或数据库列。只有 kind==="approval" 且确实带这个前缀时才返回目标，否则返回 null——
// 调用方（AiApprovalCard，渲染在时间线下方的独立操作卡片）据此决定是否渲染“去确认”按钮。
// 注意：这个标识字段在两条数据路径上的字段名不同——SSE 实时事件（AgentEvent.Activity）序列化为
// `id`；而 GET /conversations/{id}/messages 返回的已持久化活动摘要，字段名是文档约定的
// `activityId`（经 api.js 的 normalize() 把数据库列 ACTIVITY_ID 转成 activityId，而不是 id）。
// 两种形状都要兼容，否则重新打开对话后“去确认”按钮会消失（活动摘要来自 REST 接口而不是实时事件）。
const IRRIGATION_APPROVAL_PREFIX = "irrigation-run:";

export function irrigationApprovalTarget(activity) {
  if (!activity || activity.kind !== "approval") return null;
  const id = activity.id || activity.activityId || "";
  if (!id.startsWith(IRRIGATION_APPROVAL_PREFIX)) return null;
  const runId = id.slice(IRRIGATION_APPROVAL_PREFIX.length);
  return runId ? { type: "irrigation-run", id: runId } : null;
}

// 消息时间展示：纯函数，便于单测覆盖“刚刚/当天/昨天/n天前/更早”的边界，不依赖 Vue 或浏览器时区 API
// 之外的任何东西。now 作为参数传入（而不是内部 new Date()），这样测试可以钉死“现在”的时刻。
function pad2(n) { return String(n).padStart(2, "0"); }
function startOfDay(d) { return new Date(d.getFullYear(), d.getMonth(), d.getDate()); }
function hm(d) { return `${pad2(d.getHours())}:${pad2(d.getMinutes())}`; }

export function formatMessageTime(date, now = new Date()) {
  const d = date instanceof Date ? date : new Date(date);
  const n = now instanceof Date ? now : new Date(now);
  const iso = d.toISOString();
  const full = `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日 ${hm(d)}`;
  const diffMs = n.getTime() - d.getTime();
  if (diffMs >= 0 && diffMs < 60000) return { display: "刚刚", full, iso };
  const dayDiff = Math.round((startOfDay(n).getTime() - startOfDay(d).getTime()) / 86400000);
  if (dayDiff <= 0) return { display: hm(d), full, iso };
  if (dayDiff === 1) return { display: `昨天 ${hm(d)}`, full, iso };
  if (dayDiff <= 30) return { display: `${dayDiff} 天前`, full, iso };
  const display = d.getFullYear() === n.getFullYear() ? `${d.getMonth() + 1}月${d.getDate()}日` : `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日`;
  return { display, full, iso };
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
