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

export function floatingSurfaceLabel(surface) {
  return surface === "floating" ? "小窗" : "";
}
