const PAGE_LABELS = {
  daily: "今日农场", operations: "运行概览", dashboard: "农场概览", cameras: "田间实景",
  devices: "设备台账", plots: "地块管理", plantings: "种植计划", tasks: "农事任务",
  production: "生产记录", ai: "农场 AI 助手", display: "展示大屏", simulation: "经营模拟",
};

export const floatingTopics = [
  { label: "今天优先做什么？", question: "请结合当前农场资料和当前页面可参考的信息，梳理今天最优先的三件农事，并说明依据和缺失信息。" },
  { label: "核查设备异常", question: "请核查当前农场的设备异常、掉线和过期读数，按优先级给出检查步骤，不要把缺测当正常。" },
  { label: "看看灌溉依据", question: "当前地块是否需要灌溉？请结合最新有效土壤水分与种植资料，说明依据、缺失信息和现场复核步骤。" },
];

export function assistantPageContext(pageId, focus = {}) {
  const context = { pageId: PAGE_LABELS[pageId] ? pageId : "daily", focusType: null, focusId: null };
  const allowed = { devices: "device", cameras: "device", plots: "plot", plantings: "plot", tasks: "plot", production: "plot" };
  if (allowed[pageId] && focus.id && (!focus.type || focus.type === allowed[pageId])) {
    context.focusType = allowed[pageId];
    context.focusId = focus.id;
  }
  return context;
}

export function prepareFloatingQuestion(draft, topic) {
  const current = typeof draft === "string" ? draft : "";
  if (current.trim() || !topic?.question) return { question: current, changed: false };
  return { question: topic.question, changed: true };
}

// 发送前的“本次参考”文案：只描述意图范围；对象名称由服务端解析后写进来源记录，不在浏览器猜测。
export function sourceScopeLabel(pageLabel, context = {}) {
  const page = pageLabel || "当前页面";
  return context.focusId ? `${page} · 当前所选对象` : page;
}

// 已保存回答里的页面资料来源记录（kind=source）。只取服务端写入的安全标签与摘要，
// 老消息没有来源记录时返回空数组，界面不显示来源行。
export function pageSources(activities) {
  return (Array.isArray(activities) ? activities : [])
    .filter(activity => (activity?.kind ?? activity?.KIND) === "source")
    .map(activity => ({
      id: String(activity.activityId ?? activity.ACTIVITY_ID ?? activity.id ?? "source"),
      label: String(activity.label ?? activity.LABEL ?? ""),
      summary: String(activity.resultSummary ?? activity.RESULT_SUMMARY ?? ""),
    }))
    .filter(source => source.label);
}

export function floatingSurfaceLabel(surface) {
  return surface === "floating" ? "小窗" : "";
}

export function fallbackQuestion(message, messages) {
  if (message?.role !== "assistant" || message.mode !== "rule" || !message.requestId) return "";
  return (messages.find(row => row.role === "user" && row.requestId === message.requestId)?.content || "").trim();
}

export function clampAssistantPosition(position, size, viewport, bottomInset = 0) {
  const margin = 12;
  return {
    x: Math.max(margin, Math.min(position.x, viewport.width - size.width - margin)),
    y: Math.max(margin, Math.min(position.y, viewport.height - bottomInset - size.height - margin)),
  };
}

// Keep the dialog near its movable opener, choosing above, below or beside it before clamping.
export function assistantPanelPosition(anchor, size, viewport) {
  const margin = 12, gap = 12;
  let x = anchor.right - size.width, y = anchor.top - gap - size.height;
  if (y < margin) {
    if (anchor.bottom + gap + size.height <= viewport.height - margin) y = anchor.bottom + gap;
    else {
      y = anchor.top;
      if (anchor.left - gap - size.width >= margin) x = anchor.left - gap - size.width;
      else if (anchor.right + gap + size.width <= viewport.width - margin) x = anchor.right + gap;
    }
  }
  return clampAssistantPosition({ x, y }, size, viewport);
}
