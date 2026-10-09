import { reactive } from "vue";
import { createSseParser } from "./ai/sse-parser.js";

let token = sessionStorage.getItem("zhinong-session") || "";

// 与本地服务的真实连接状态，供顶栏状态胶囊显示：收到任何 HTTP 响应即视为在线
export const connection = reactive({
  state: "unknown",
  lastSync: null,
  inflight: 0,
});

export function setToken(value) {
  token = value;
  if (value) sessionStorage.setItem("zhinong-session", value);
  else sessionStorage.removeItem("zhinong-session");
}

function normalize(value) {
  if (Array.isArray(value)) return value.map(normalize);
  if (!value || typeof value !== "object") return value;
  return Object.fromEntries(
    Object.entries(value).map(([key, item]) => [
      (/^[A-Z_]+$/.test(key) || /^[a-z]+(?:_[a-z]+)+$/.test(key))
        ? key.toLowerCase().replace(/_([a-z])/g, (_, c) => c.toUpperCase())
        : key,
      normalize(item),
    ]),
  );
}

// 连接类故障由顶栏胶囊和页面顶部的连接提示统一说明，各页面模块不再重复显示
export function isConnectionError(error) {
  return error?.status === 0 || [502, 503, 504].includes(error?.status);
}
export function loadError(error) {
  return isConnectionError(error) ? "" : error.message;
}

function failure(message, status) {
  const error = new Error(message);
  error.status = status;
  return error;
}

// keepalive 用于页面关闭时仍需送达的请求（例如撤销期结束的删除）。
// signal 可选：传入外部 AbortSignal 时，调用方中止它会真正中断这次请求（例如 AI 助手的停止按钮），
// 不只是让调用方忽略稍后到达的结果。
export async function api(path, method = "GET", body, { keepalive = false, timeoutMs = 15000, signal } = {}) {
  const controller = new AbortController();
  const onAbort = () => controller.abort();
  if (signal) {
    if (signal.aborted) controller.abort();
    else signal.addEventListener("abort", onAbort);
  }
  let timedOut = false;
  const timeout = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);
  connection.inflight++;
  try {
    let response;
    try {
      response = await fetch("/api" + path, {
        method,
        headers: {
          "Content-Type": "application/json",
          ...(token ? { Authorization: "Bearer " + token } : {}),
        },
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: controller.signal,
        keepalive,
      });
    } catch (error) {
      connection.state = "offline";
      if (error.name === "AbortError" && signal?.aborted && !timedOut) {
        const cancelled = new Error("已取消");
        cancelled.cancelled = true;
        throw cancelled;
      }
      throw error.name === "AbortError"
        ? failure("请求超时，请检查本地服务是否运行", 0)
        : failure("无法连接本地服务，请确认服务已启动后重试", 0);
    }
    // 开发代理在后端停止时返回 502/503/504
    if ([502, 503, 504].includes(response.status)) {
      connection.state = "offline";
      const details = await response.json().catch(() => ({}));
      throw failure(details.message || "服务暂时不可用，请稍后重试", response.status);
    }
    connection.state = "online";
    connection.lastSync = new Date();
    let data = {};
    try {
      data = await response.json();
    } catch {
      if (response.ok) throw failure("服务返回了无法识别的内容，请刷新后重试", response.status);
    }
    if (!response.ok) {
      if (response.status === 401 && path !== "/auth/login") {
        setToken("");
        window.dispatchEvent(new Event("session-expired"));
      }
      throw failure(data.message || "操作失败，请重试", response.status);
    }
    return normalize(data);
  } finally {
    clearTimeout(timeout);
    if (signal) signal.removeEventListener("abort", onAbort);
    connection.inflight--;
  }
}

// 流式接口：POST 一个 JSON body，响应以 text/event-stream 返回一串 AgentEvent JSON；
// 以异步生成器逐个 yield 解析后的事件（已经过 normalize()，字段命名风格与 api() 一致）。
// 与 api() 共用鉴权、超时和错误归一化逻辑，但读取方式不同（流式而非一次性 JSON）。
// signal 用于真正的取消：调用方中止它会立即中断底层 fetch，不是仅在客户端停止消费事件。
export async function* streamJson(path, body, { signal, timeoutMs = 70000 } = {}) {
  const controller = new AbortController();
  let timedOut = false;
  const onAbort = () => controller.abort();
  if (signal) {
    if (signal.aborted) controller.abort();
    else signal.addEventListener("abort", onAbort);
  }
  const timeout = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);
  connection.inflight++;
  try {
    let response;
    try {
      response = await fetch("/api" + path, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          // 同时接受 JSON：鉴权失败、幂等冲突等在建立 SSE 连接前就返回的普通 JSON 错误体，
          // 只接受 text/event-stream 会让服务端内容协商失败，变成无关的 500。
          Accept: "text/event-stream, application/json;q=0.9, */*;q=0.1",
          ...(token ? { Authorization: "Bearer " + token } : {}),
        },
        body: JSON.stringify(body),
        signal: controller.signal,
      });
    } catch (error) {
      connection.state = "offline";
      if (error.name === "AbortError" && signal?.aborted && !timedOut) return; // 调用方主动取消，不是失败
      throw error.name === "AbortError"
        ? failure("请求超时，请检查本地服务是否运行", 0)
        : failure("无法连接本地服务，请确认服务已启动后重试", 0);
    }
    if ([502, 503, 504].includes(response.status)) {
      connection.state = "offline";
      const details = await response.json().catch(() => ({}));
      throw failure(details.message || "服务暂时不可用，请稍后重试", response.status);
    }
    if (response.status === 404 || response.status === 405) {
      throw failure("流式接口不可用", response.status);
    }
    if (!response.ok) {
      const details = await response.json().catch(() => ({}));
      if (response.status === 401) {
        setToken("");
        window.dispatchEvent(new Event("session-expired"));
      }
      throw failure(details.message || "操作失败，请重试", response.status);
    }
    connection.state = "online";
    connection.lastSync = new Date();
    if (!response.body) throw failure("服务返回了无法识别的内容，请刷新后重试", response.status);
    const reader = response.body.getReader();
    const decoder = new TextDecoder("utf-8");
    const parser = createSseParser();
    try {
      while (true) {
        let value, done;
        try {
          ({ value, done } = await reader.read());
        } catch (error) {
          if (signal?.aborted && !timedOut) return; // 调用方主动取消
          throw timedOut
            ? failure("请求超时，请检查本地服务是否运行", 0)
            : failure("无法连接本地服务，请确认服务已启动后重试", 0);
        }
        if (done) break;
        for (const evt of parser.feed(decoder.decode(value, { stream: true }))) {
          if (!evt.data) continue;
          let parsed;
          try {
            parsed = JSON.parse(evt.data);
          } catch {
            continue;
          }
          yield normalize(parsed);
        }
      }
    } finally {
      reader.cancel().catch(() => {});
    }
  } finally {
    clearTimeout(timeout);
    if (signal) signal.removeEventListener("abort", onAbort);
    connection.inflight--;
  }
}
