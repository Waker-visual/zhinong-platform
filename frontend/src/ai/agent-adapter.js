// 同步适配器：把现有的同步 POST /api/ai/conversations/{id}/messages 接口包装成
// AgentRunEvent 流，驱动阶段 A 的 Agent UI，待阶段 B 替换为真实 SSE 适配器。
// 真实的 `api` 实现延迟到调用时才导入（而非在模块顶层导入），
// 因为它在加载时读取浏览器的 sessionStorage，在 Node 测试环境中会立即抛错；
// 测试总是注入自己的 `send`，不会触发这次动态导入。
async function defaultSend({ conversationId, question, requestId }) {
  const { api } = await import("../api.js");
  return api(`/ai/conversations/${conversationId}/messages`, "POST", { question, requestId }, { timeoutMs: 70000 });
}

const diagnostics = {
  NOT_CONFIGURED: "模型服务尚未配置",
  AUTH_FAILED: "模型认证失败，请联系平台管理员更换凭据",
  BALANCE_REQUIRED: "模型账户余额不足",
  RATE_LIMITED: "模型请求过于频繁，请稍后重试",
  TIMEOUT: "模型服务响应超时",
  BUSY: "模型服务繁忙",
  UNAVAILABLE: "模型服务暂不可用",
  SERVICE_ERROR: "模型服务返回错误",
  EMPTY_RESPONSE: "模型未返回有效回答",
};

function errorMessage(error) {
  if (error && typeof error.message === "string" && error.message) return error.message;
  return "回答失败，请重试";
}

// 同步适配器：farmId 当前未被同步接口使用，但保留在参数中以便阶段 B 的流式适配器复用同一调用方。
export async function* syncConversationAdapter({ farmId, conversationId, question, requestId, send = defaultSend }) {
  let sequence = 0;
  const next = () => ++sequence;
  yield { sequence: next(), type: "run.started" };
  const activity = {
    id: "context",
    kind: "context",
    label: "读取当前农场资料",
    status: "running",
    startedAt: new Date().toISOString(),
  };
  yield { sequence: next(), type: "activity.started", activity };
  try {
    const response = await send({ farmId, conversationId, question, requestId });
    yield {
      sequence: next(),
      type: "activity.completed",
      activity: { ...activity, status: "completed", finishedAt: new Date().toISOString(), resultSummary: "已读取农场摘要并整理回答" },
    };
    const answer = response?.answer || "";
    yield { sequence: next(), type: "message.delta", delta: answer };
    yield { sequence: next(), type: "message.completed", messageId: response?.id || crypto.randomUUID() };
    yield { sequence: next(), type: "run.completed" };
  } catch (error) {
    const diagnostic = error?.diagnostic || null;
    const message = diagnostics[diagnostic] || errorMessage(error);
    yield {
      sequence: next(),
      type: "run.error",
      error: message,
      diagnostic,
    };
  }
}
