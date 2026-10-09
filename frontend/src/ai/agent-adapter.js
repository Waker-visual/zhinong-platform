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
// 必须是 async function*（而不是普通 async function 再 return 一个异步生成器）：调用方
// 用 `for await (const event of send(...))` 直接迭代返回值。普通 async function 的返回值
// 会被包成 Promise<AsyncGenerator>，Promise 本身不是 async-iterable，`for await...of` 会在
// 取迭代器时立即抛 TypeError——还没拿到任何事件就报错，于是在 streamConversationAdapter 里
// 被当成“流式彻底不可用”，每次都静默回退到同步接口，SSE 永远用不上（该问题已在手工浏览器
// 验收中复现：实际发送请求只命中 /messages，从未命中 /stream）。用 yield* 委托，
// defaultStreamSend(...) 调用后立即同步返回一个真正的异步生成器，才能被正确迭代。
async function* defaultStreamSend({ conversationId, question, requestId, signal }) {
  const { streamJson } = await import("../api.js");
  yield* streamJson(`/ai/conversations/${conversationId}/stream`, { question, requestId }, { timeoutMs: 70000, signal });
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
// 每个事件都带上 adapter 字段（"stream" | "sync"），标记它实际走的是哪条路径——调用方
// （agent-events.js 的 reducer）据此记下 state.usingSyncFallback，决定“已停止”文案要不要提
// “可能仍在后台继续”：只有真正回退到旧同步接口时，停止按钮才只是中止了前端等待，请求本身
// 可能已经在服务端跑完并写库；真正走流式连接时，客户端 abort() 会让服务端立刻检测到断开，
// 不会写入任何内容，“本次回答未保存”才是准确的。
export async function* defaultConversationAdapter({ streamSend, syncSend, ...params }) {
  try {
    for await (const event of streamConversationAdapter({ ...params, send: streamSend })) {
      yield { ...event, adapter: "stream" };
    }
  } catch (error) {
    for await (const event of syncConversationAdapter({ ...params, send: syncSend })) {
      yield { ...event, adapter: "sync" };
    }
  }
}
