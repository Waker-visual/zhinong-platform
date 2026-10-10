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
    // 这次运行实际走的是哪条请求路径（"stream"|"sync"|null，null 表示还不知道）。
    // defaultConversationAdapter 在每个事件上标记 adapter 字段，reduceAgentEvent 据此更新；
    // 只有停留在 "sync"（已经回退到旧同步接口）时，停止才可能意味着请求仍在后台继续——
    // 真正走流式连接时客户端 abort() 会让服务端立刻检测到断开、不写入任何内容。
    adapter: null,
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
  const adapter = event.adapter || state.adapter;
  if (adapter !== state.adapter) state = { ...state, adapter };
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
    case "message.reset":
      // 一轮工具调用（finish_reason=tool_calls）结束：这一轮模型在调用工具之前吐出的“旁白”文本
      // 已经通过 message.delta 流给了客户端并显示出来，但它不是最终答案的一部分（阶段验收发现
      // 模型先说“我先查一下任务和问题列表。”再调用工具，这句英文/中文旁白被原样拼进了最终回答开头）。
      // 后端在判断某一轮以 tool_calls 收尾时会广播这个事件，text 是“目前真正确认”的正文——在最终
      // 一轮完成之前始终是空字符串——客户端据此丢弃刚才那一轮已经显示的文本，不能继续累加。
      return { ...state, text: event.text || "", lastSequence };
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
// usingSyncFallback：这次运行是否用的是旧同步接口（而不是真正的流式连接）——只有这种情况下，
// “停止”才只是中止了前端等待，请求本身可能已经在服务端跑完并写库，需要提示“可能仍在后台继续”；
// 真正走流式连接时，客户端 abort() 会让服务端立刻检测到断开、不写入任何内容，文案应该直接、
// 准确地说“本次回答未保存”，不该用一句含糊的“若已回退……”去吓唬从未发生过的情况。
export function streamingStatusText(status, diagnostic, usingSyncFallback = false) {
  switch (status) {
    case "submitted":
      return "正在读取当前农场资料…";
    case "running":
      return "正在生成回答…";
    case "completed":
      return "已完成";
    case "cancelled":
      return usingSyncFallback ? "已停止；请求可能仍在后台继续，刷新对话后可能已有结果" : "已停止，本次回答未保存";
    case "error":
      return diagnostic || "回答失败，请重试";
    default:
      return "";
  }
}

// 时间线的展示顺序：仍在进行的活动沉到末尾，其余保持到达顺序。
// “生成回答”贯穿整轮运行、最先开始，之后才陆续插入各个工具查询——按到达顺序渲染的话，
// 转圈的进行中图标会夹在一串已完成的勾中间；落库后的活动摘要里它本来就排在最后，
// 这里让运行中的展示与之一致。只调整展示，不改 run.activities 本身的顺序。
export function orderActivitiesForDisplay(activities) {
  const list = activities || [];
  return [...list.filter((a) => a.status !== "running"), ...list.filter((a) => a.status === "running")];
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

// 灌溉管理页签里“确认并启动模拟灌溉”的二次确认文案：写明地块、水泵与时长，让人在点击前
// 明确知道会启动什么（聊天卡片“去确认”只导航到这里，真正的启动只发生在此确认之后）。
export function irrigationApproveConfirm(run) {
  const plot = (run && run.plotName) || "该地块";
  const pump = run && run.pumpName ? `水泵“${run.pumpName}”，` : "";
  const seconds = run && run.durationSeconds ? `运行 ${run.durationSeconds} 秒` : "按建议时长运行";
  const scope = run?.zoneName ? `作用范围：${run.zoneName}。` : "";
  return {
    title: `确认启动 ${plot} 的模拟灌溉？`,
    message: `${scope}${pump}${seconds}。当前仅模拟设备，不会驱动实体水泵；启动前系统会重新读取墒情与设备状态，条件不符会拒绝。`,
    confirmLabel: "确认启动",
  };
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

// 阶段F：把会话列表按“已置顶 / 今天 / 昨天 / 近7天 / 更早”分组，供历史栏渲染组标题。
// 纯函数：now 作为参数传入（而不是内部 new Date()），测试才能钉死“现在”的时刻来覆盖边界
// （例如“23:59 创建的对话在次日 00:01 算不算今天”）。分组内部顺序原样保留调用方已经排好的
// 顺序（后端已经按 pinned_at desc, updated_at desc 排序过，这里不重新排序）。
// 已置顶的对话（pinnedAt 非空）总是单独成组排在最前，不再按时间归入今天/昨天等分组，
// 即使它恰好是今天创建或更新的。
const CONVERSATION_GROUP_LABELS = { pinned: "已置顶", today: "今天", yesterday: "昨天", last7: "近 7 天", older: "更早" };

export function conversationGroupLabel(key) {
  return CONVERSATION_GROUP_LABELS[key] || key;
}

export function groupConversationsByDate(conversations, now = new Date()) {
  const n = now instanceof Date ? now : new Date(now);
  const todayStart = startOfDay(n).getTime();
  const groups = { pinned: [], today: [], yesterday: [], last7: [], older: [] };
  for (const c of conversations || []) {
    if (c.pinnedAt) {
      groups.pinned.push(c);
      continue;
    }
    const basis = c.updatedAt || c.createdAt;
    const d = basis ? new Date(basis) : null;
    if (!d || Number.isNaN(d.getTime())) {
      groups.older.push(c);
      continue;
    }
    const dayDiff = Math.round((todayStart - startOfDay(d).getTime()) / 86400000);
    if (dayDiff <= 0) groups.today.push(c);
    else if (dayDiff === 1) groups.yesterday.push(c);
    else if (dayDiff <= 7) groups.last7.push(c);
    else groups.older.push(c);
  }
  return Object.entries(groups)
    .filter(([, items]) => items.length)
    .map(([key, items]) => ({ key, label: conversationGroupLabel(key), items }));
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
