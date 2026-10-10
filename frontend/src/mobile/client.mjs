export function normalize(value) {
  if (Array.isArray(value)) return value.map(normalize);
  if (!value || typeof value !== "object") return value;
  return Object.fromEntries(
    Object.entries(value).map(([key, item]) => [
      /^[A-Z_]+$/.test(key) || /^[a-z]+(?:_[a-z]+)+$/.test(key)
        ? key.toLowerCase().replace(/_([a-z])/g, (_, c) => c.toUpperCase())
        : key,
      normalize(item),
    ]),
  );
}

// LAN HTTP is supported; randomUUID is not available on insecure origins.
export function requestId() {
  const bytes = new Uint8Array(16);
  globalThis.crypto.getRandomValues(bytes);
  return (
    "mobile-" +
    Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("")
  );
}

export function createClient(
  fetcher = (...args) => fetch(...args),
  expired = () => {},
) {
  let token = "",
    generation = 0;
  return {
    setToken(value) {
      token = value || "";
      generation++;
    },
    async request(path, method = "GET", body) {
      const ownGeneration = generation;
      const abort = new AbortController();
      const timer = setTimeout(() => abort.abort(), 15000);
      try {
        let response;
        try {
          response = await fetcher("/api" + path, {
            method,
            signal: abort.signal,
            cache: "no-store",
            credentials: "omit",
            headers: {
              "Content-Type": "application/json",
              ...(token ? { Authorization: "Bearer " + token } : {}),
            },
            body: body === undefined ? undefined : JSON.stringify(body),
          });
        } catch {
          throw Object.assign(
            new Error(
              method === "GET"
                ? "连接失败，请检查网络后刷新。"
                : "未收到响应，操作结果待核对，请勿重复创建任务。",
            ),
            { status: 0, uncertain: method !== "GET" },
          );
        }
        if (ownGeneration !== generation)
          throw Object.assign(new Error("会话已切换"), { stale: true });
        const data = await response.json().catch(() => null);
        if (ownGeneration !== generation)
          throw Object.assign(new Error("会话已切换"), { stale: true });
        if (!response.ok) {
          if (response.status === 401 && path !== "/auth/login") {
            token = "";
            generation++;
            expired();
          }
          throw Object.assign(
            new Error(data?.message || "服务暂不可用，请稍后核对。"),
            {
              status: response.status,
              uncertain: method !== "GET" && response.status >= 500,
            },
          );
        }
        if (data === null)
          throw Object.assign(new Error("服务响应格式有误，请核对操作记录。"), {
            uncertain: method !== "GET",
          });
        return normalize(data);
      } finally {
        clearTimeout(timer);
      }
    },
  };
}

export const typeNames = {
  SOIL: "土壤",
  WEATHER: "气象",
  PEST: "虫情",
  SPORE: "孢子",
  CAMERA: "摄像头",
  MACHINERY: "农机",
  PUMP: "水泵",
  GATE: "闸门",
  SENSOR: "传感器",
};
export const taskNames = {
  INSPECTION: "巡检",
  SOWING: "播种",
  FERTILIZING: "施肥",
  HARVEST: "收获",
  PROTECTION: "植保",
};
export const statuses = {
  PENDING: "等待设备领取",
  DISPATCHED: "已领取 · 等待回执",
  SUCCEEDED: "回执成功",
  FAILED: "失败",
  EXPIRED: "已超时",
  CANCELLED: "已取消",
  RUNNING: "模拟执行中",
  PAUSED: "模拟已暂停",
  COMPLETED: "模拟已完成",
  STOPPED: "已停止",
};
export function freshness(device) {
  return (
    {
      FRESH: "上报正常",
      STALE: "上报过期",
      NO_DATA: "尚无上报",
      MAINTENANCE: "维护中",
      DISABLED: "已停用",
    }[device?.freshness] || "状态未知"
  );
}
export function number(value, digits = 1) {
  return value === null ||
    value === undefined ||
    value === "" ||
    !Number.isFinite(Number(value))
    ? "—"
    : Number(value).toLocaleString("zh-CN", { maximumFractionDigits: digits });
}
export function time(value) {
  return value
    ? new Date(value).toLocaleString("zh-CN", { hour12: false })
    : "暂无记录";
}
export function canControl(device, action, role) {
  if (!["ADMIN", "OPERATOR"].includes(role)) return false;
  if (action.role === "ADMIN" && role !== "ADMIN") return false;
  if (!device?.controlEnabled || device.lifecycle !== "ACTIVE") return false;
  if (!["SIMULATED", "HTTP_PUSH"].includes(device.protocol)) return false;
  if (device.protocol === "HTTP_PUSH" && !device.credentialConfigured)
    return false;
  return (
    ["PUMP_STOP", "EMERGENCY_STOP"].includes(action.code) ||
    device.freshness === "FRESH"
  );
}
