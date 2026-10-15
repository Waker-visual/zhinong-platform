// 登录页的服务就绪状态。直接读 /api/health，不经过 api()：服务初始化中返回 503 是预期状态，
// 不应把顶栏连接胶囊标成离线，也不应触发会话过期处理。
export const READINESS_RETRY_MS = 3000;

// state: ready（可登录）、starting（服务在初始化或停止中）、unreachable（连不上本地服务）
export async function readHealth(fetchImpl = globalThis.fetch) {
  try {
    const response = await fetchImpl("/api/health", { cache: "no-store", credentials: "omit" });
    const body = await response.json().catch(() => ({}));
    const demo = body?.demo === true;
    return { state: response.ok && body?.ready === true ? "ready" : "starting", demo };
  } catch {
    return { state: "unreachable", demo: false };
  }
}

// 未就绪时按固定间隔重查，就绪后停止；返回的函数用于离开登录页时停止轮询。
export function watchReadiness(onChange, {
  read = () => readHealth(),
  retryMs = READINESS_RETRY_MS,
  setTimer = globalThis.setTimeout,
  clearTimer = globalThis.clearTimeout,
} = {}) {
  let timer = null, stopped = false;
  async function tick() {
    timer = null;
    const status = await read();
    if (stopped) return;
    onChange(status);
    if (status.state !== "ready") timer = setTimer(tick, retryMs);
  }
  tick();
  return () => {
    stopped = true;
    if (timer !== null) clearTimer(timer);
  };
}
