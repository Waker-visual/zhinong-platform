import test from "node:test";
import assert from "node:assert/strict";
import { api, connection, setToken, streamJson } from "./api.js";

function browserSession(t) {
  const values = new Map(),
    expired = [];
  const globals = {
    sessionStorage: {
      getItem: (key) => values.get(key),
      setItem: (key, value) => values.set(key, value),
      removeItem: (key) => values.delete(key),
    },
    window: { dispatchEvent: (event) => expired.push(event.type) },
  };
  const originals = Object.fromEntries(
    Object.keys(globals).map((key) => [
      key,
      Object.getOwnPropertyDescriptor(globalThis, key),
    ]),
  );
  for (const [key, value] of Object.entries(globals))
    Object.defineProperty(globalThis, key, { configurable: true, value });
  connection.state = "online";
  t.after(() => {
    setToken("");
    for (const [key, descriptor] of Object.entries(originals)) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor);
      else delete globalThis[key];
    }
  });
  return { values, expired };
}
const abortError = () =>
  new DOMException("The operation was aborted", "AbortError");

test("optional guidance timeout does not mark the whole workspace offline or busy", async (t) => {
  browserSession(t);
  t.mock.method(globalThis, "fetch", async (_url, { signal }) => ({
    status: 200, ok: true,
    json: () => new Promise((_, reject) => signal.addEventListener("abort", () => reject(abortError()), { once: true })),
  }));
  const pending = api("/ai/conversations/chat/follow-ups", "POST", { messageId: "message" }, { timeoutMs: 10, trackConnection: false });
  assert.equal(connection.inflight, 0);
  await assert.rejects(pending, (error) => error.status === 0 && /超时/.test(error.message));
  assert.equal(connection.state, "online"); assert.equal(connection.inflight, 0);
});

test("optional guidance keeps authentication expiry even without connection tracking", async (t) => {
  const browser = browserSession(t); setToken("expired-demo-session");
  t.mock.method(globalThis, "fetch", async () => new Response('{"message":"已过期"}', { status: 401 }));
  await assert.rejects(api("/ai/conversations/chat/follow-ups", "POST", {}, { trackConnection: false }), (error) => error.status === 401);
  assert.deepEqual(browser.expired, ["session-expired"]); assert.equal(connection.inflight, 0);
});

test("an optional route outage does not overwrite the main connection status", async (t) => {
  browserSession(t);
  t.mock.method(globalThis, "fetch", async () => new Response('{}', { status: 503 }));
  await assert.rejects(api("/ai/conversations/chat/follow-ups", "POST", {}, { trackConnection: false }), (error) => error.status === 503);
  assert.equal(connection.state, "online");
});

test("cancelling fetch is not reported as a service outage and balances inflight", async (t) => {
  browserSession(t);
  const controller = new AbortController();
  t.mock.method(
    globalThis,
    "fetch",
    (_url, { signal }) =>
      new Promise((_, reject) =>
        signal.addEventListener("abort", () => reject(abortError()), {
          once: true,
        }),
      ),
  );
  const pending = api("/farms/demo/workspace", "GET", undefined, {
    signal: controller.signal,
  });
  controller.abort();
  await assert.rejects(pending, (error) => error.cancelled === true);
  assert.equal(connection.state, "online");
  assert.equal(connection.inflight, 0);
});

test("caller cancellation while reading a JSON body remains cancellation", async (t) => {
  browserSession(t);
  const controller = new AbortController();
  t.mock.method(globalThis, "fetch", async (_url, { signal }) => ({
    status: 200,
    ok: true,
    json: () =>
      new Promise((_, reject) => {
        signal.addEventListener("abort", () => reject(abortError()), {
          once: true,
        });
        controller.abort();
      }),
  }));
  await assert.rejects(
    api("/farms/demo/workspace", "GET", undefined, {
      signal: controller.signal,
    }),
    (error) => error.cancelled === true,
  );
  assert.equal(connection.state, "online");
  assert.equal(connection.inflight, 0);
});

test("the request timeout also bounds a stalled response body", async (t) => {
  browserSession(t);
  t.mock.method(globalThis, "fetch", async (_url, { signal }) => ({
    status: 200,
    ok: true,
    json: () =>
      new Promise((_, reject) =>
        signal.addEventListener("abort", () => reject(abortError()), {
          once: true,
        }),
      ),
  }));
  await assert.rejects(
    api("/farms/demo/workspace", "GET", undefined, { timeoutMs: 10 }),
    (error) =>
      error.status === 0 && /超时/.test(error.message) && !error.cancelled,
  );
  // Headers arrived, so the service is up; a slow body is a timeout, not an outage.
  assert.equal(connection.state, "slow");
  assert.equal(connection.inflight, 0);
});

test("a slow request is reported as a timeout, never as offline", async (t) => {
  browserSession(t);
  t.mock.method(globalThis, "fetch", async (_url, { signal }) =>
    new Promise((_, reject) =>
      signal.addEventListener("abort", () => reject(abortError()), { once: true }),
    ),
  );
  await assert.rejects(
    api("/alerts", "GET", undefined, { timeoutMs: 10 }),
    (error) => error.status === 0 && /超时/.test(error.message) && !/无法连接/.test(error.message),
  );
  assert.equal(connection.state, "slow");
  assert.equal(connection.inflight, 0);

  t.mock.method(globalThis, "fetch", async () => ({ status: 200, ok: true, json: async () => [] }));
  assert.deepEqual(await api("/alerts"), []);
  assert.equal(connection.state, "online", "the next response clears the timeout state");
});

test("a refused connection or gateway outage is still offline", async (t) => {
  browserSession(t);
  t.mock.method(globalThis, "fetch", async () => {
    throw new TypeError("Failed to fetch");
  });
  await assert.rejects(api("/alerts"), (error) => error.status === 0 && /无法连接/.test(error.message));
  assert.equal(connection.state, "offline");

  connection.state = "online";
  t.mock.method(globalThis, "fetch", async () => ({ status: 504, ok: false, json: async () => ({}) }));
  await assert.rejects(api("/alerts"), (error) => error.status === 504);
  assert.equal(connection.state, "offline");
  assert.equal(connection.inflight, 0);
});

test("invalid successful JSON is distinguished from a network timeout", async (t) => {
  browserSession(t);
  t.mock.method(
    globalThis,
    "fetch",
    async () => new Response("broken", { status: 200 }),
  );
  await assert.rejects(
    api("/farms/demo/workspace"),
    (error) => error.status === 200 && /无法识别/.test(error.message),
  );
  assert.equal(connection.state, "online");
  assert.equal(connection.inflight, 0);
});

test("an empty JSON error body preserves its HTTP status and a useful message", async (t) => {
  browserSession(t);
  t.mock.method(globalThis, "fetch", async () => new Response("null", { status: 503 }));
  await assert.rejects(api("/farms/demo/workspace"), (error) => error.status === 503 && /暂时不可用/.test(error.message));
  assert.equal(connection.inflight, 0);
});

test("a delayed unauthorized response cannot invalidate a replacement session", async (t) => {
  const browser = browserSession(t);
  setToken("old-demo-session");
  t.mock.method(globalThis, "fetch", async () => {
    setToken("new-demo-session");
    return new Response('{"message":"已过期"}', { status: 401 });
  });
  await assert.rejects(
    api("/farms/demo/workspace"),
    (error) => error.status === 401,
  );
  assert.equal(browser.values.get("zhinong-session"), "new-demo-session");
  assert.deepEqual(browser.expired, []);
});

test("a current unauthorized response still expires its own session", async (t) => {
  const browser = browserSession(t);
  setToken("expired-demo-session");
  t.mock.method(
    globalThis,
    "fetch",
    async () => new Response('{"message":"已过期"}', { status: 401 }),
  );
  await assert.rejects(
    api("/farms/demo/workspace"),
    (error) => error.status === 401,
  );
  assert.equal(browser.values.has("zhinong-session"), false);
  assert.deepEqual(browser.expired, ["session-expired"]);
});

test("stopping an SSE request before headers preserves online state and yields no events", async (t) => {
  browserSession(t);
  const controller = new AbortController();
  t.mock.method(
    globalThis,
    "fetch",
    (_url, { signal }) =>
      new Promise((_, reject) => {
        signal.addEventListener("abort", () => reject(abortError()), {
          once: true,
        });
        controller.abort();
      }),
  );
  const events = [];
  for await (const event of streamJson(
    "/ai/conversations/demo/stream",
    {},
    { signal: controller.signal },
  ))
    events.push(event);
  assert.deepEqual(events, []);
  assert.equal(connection.state, "online");
  assert.equal(connection.inflight, 0);
});

test("stopping SSE after headers also preserves the connection and balances inflight", async (t) => {
  browserSession(t);
  const controller = new AbortController();
  t.mock.method(
    globalThis,
    "fetch",
    async (_url, { signal }) =>
      new Response(
        new ReadableStream({
          start(stream) {
            signal.addEventListener("abort", () => stream.error(abortError()), {
              once: true,
            });
            queueMicrotask(() => controller.abort());
          },
        }),
        { headers: { "Content-Type": "text/event-stream" } },
      ),
  );
  const events = [];
  for await (const event of streamJson(
    "/ai/conversations/demo/stream",
    {},
    { signal: controller.signal },
  ))
    events.push(event);
  assert.deepEqual(events, []);
  assert.equal(connection.state, "online");
  assert.equal(connection.inflight, 0);
});
