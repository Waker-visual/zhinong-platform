let token = sessionStorage.getItem("zhinong-session") || "";

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
      /^[A-Z_]+$/.test(key)
        ? key.toLowerCase().replace(/_([a-z])/g, (_, c) => c.toUpperCase())
        : key,
      normalize(item),
    ]),
  );
}

export async function api(path, method = "GET", body) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 15000);
  try {
    const response = await fetch("/api" + path, {
      method,
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: "Bearer " + token } : {}),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
    });
    const data = await response.json();
    if (!response.ok) {
      if (response.status === 401 && path !== "/auth/login") {
        setToken("");
        window.dispatchEvent(new Event("session-expired"));
      }
      throw new Error(data.message || "操作失败，请重试");
    }
    return normalize(data);
  } catch (error) {
    if (error.name === "AbortError")
      throw new Error("请求超时，请检查服务是否运行");
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}
