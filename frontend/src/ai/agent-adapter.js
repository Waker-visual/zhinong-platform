// 同步适配器：把现有的同步 POST /api/ai/conversations/{id}/messages 接口包装成
// AgentRunEvent 流，驱动阶段 A 的 Agent UI，待阶段 B 替换为真实 SSE 适配器。
// 真实的 `api` 实现延迟到调用时才导入（而非在模块顶层导入），
// 因为它在加载时读取浏览器的 sessionStorage，在 Node 测试环境中会立即抛错；
// 测试总是注入自己的 `send`，不会触发这次动态导入。
async function defaultSend({ conversationId, question, requestId, signal }) {
  const { api } = await import("../api.js");
  return api(`/ai/conversations/${conversationId}/messages`, "POST", { question, requestId }, { timeoutMs: 70000, signal });
}

// 流式适配器的默认发送：真正的 SSE 实现延迟到调用时才导入 api.js（原因同上，避免在 Node
// 测试环境加载时触发 sessionStorage 访问）。
async function defaultStreamSend({ conversationId, question, requestId, signal }) {
  const { streamJson } = await import("../api.js");
  return streamJson(`/ai/conversations/${conversationId}/stream`, { question, requestId }, { timeoutMs: 70000, signal });
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

// 同步适配器：farmId 当前未被同步接口使用，但保留在参数中以便流式适配器复用同一调用方。
export async function* syncConversationAdapter({ farmId, conversationId, question, requestId, signal, send = defaultSend }) {
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
    const response = await send({ farmId, conversationId, question, requestId, signal });
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
    if (error?.cancelled) return; // 用户主动停止：调用方（cancelAgentRun）已经处理状态，这里不再报错
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

// 流式适配器：真实走 SSE 的 POST /api/ai/conversations/{id}/stream，逐个转发后端已经按
// AgentRunEvent 协议发出的事件。只有在还没有产出过任何事件时失败，才向上抛出异常——
// 这样 defaultConversationAdapter 才知道可以安全回退到同步接口（不会造成问题重复提交）。
// 一旦已经转发过事件再出错，只能就地发出 run.error，不能再回退（会变成同一个问题发送两次）。
export async function* streamConversationAdapter({ conversationId, question, requestId, signal, send = defaultStreamSend }) {
  let yielded = false;
  try {
    for await (const event of send({ conversationId, question, requestId, signal })) {
      yielded = true;
      yield event;
    }
  } catch (error) {
    if (error?.cancelled) return; // 用户主动停止
    if (!yielded) throw error; // 还没收到任何事件：交给调用方判断是否回退到同步接口
    const diagnostic = error?.diagnostic || null;
    yield { type: "run.error", error: diagnostics[diagnostic] || errorMessage(error), diagnostic };
  }
}

// 默认适配器：优先走真实流式连接；仅当流式接口在产出任何事件之前就失败
// （404/405，或网络层面完全连不上）才回退到同步适配器。一旦流式已经产出过事件，
// 即使中途失败，也只能原样转发 run.error 并允许用 retry() 携带同一个 requestId 重试，
// 不能静默切换到同步接口重新发一遍——那会让同一个问题被提交两次。
// streamSend/syncSend 让测试分别注入两条路径各自的假实现（两者的返回形状完全不同：
// 一个是事件的异步可迭代对象，一个是单次 JSON 响应），默认分别是真实的 SSE 和同步调用。
export async function* defaultConversationAdapter({ streamSend, syncSend, ...params }) {
  try {
    yield* streamConversationAdapter({ ...params, send: streamSend });
  } catch (error) {
    yield* syncConversationAdapter({ ...params, send: syncSend });
  }
}
