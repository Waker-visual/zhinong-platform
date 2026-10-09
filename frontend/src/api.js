import { reactive } from "vue";

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

// keepalive 用于页面关闭时仍需送达的请求（例如撤销期结束的删除）
export async function api(path, method = "GET", body, { keepalive = false, timeoutMs = 15000 } = {}) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
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
    connection.inflight--;
  }
}
