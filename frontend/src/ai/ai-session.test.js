import test from "node:test";
import assert from "node:assert/strict";
import { createAiSession } from "./ai-session.js";

function gate() { let open; const promise = new Promise(resolve => { open = resolve; }); return { promise, open }; }
let ids = 0;
const newRequestId = () => `req-${++ids}`;

test("both views read the same in-flight run and receive persisted messages together", async () => {
  const release = gate();
  const adapter = async function* ({ signal }) {
    yield { type: "run.started" };
    yield { type: "message.delta", delta: "先看墒情" };
    await release.promise;
    if (signal.aborted) return;
    yield { type: "message.completed", messageId: "m2" };
    yield { type: "run.completed" };
  };
  const session = createAiSession({ adapter, newRequestId, loadMessages: async () => [{ id: "m1" }, { id: "m2" }] });
  const seen = [];
  session.subscribe(event => seen.push(event.type));
  const sending = session.send({ farmId: "f1", conversationId: "c1", question: "今天做什么", origin: "floating" });
  await new Promise(resolve => setTimeout(resolve, 0));
  // 小窗发起，完整页按同一个 conversationId 读取到同一份增量
  assert.equal(session.runFor("c1").text, "先看墒情");
  assert.equal(session.runFor("other"), null);
  assert.equal(session.state.sending, true);
  release.open();
  const result = await sending;
  assert.equal(result.status, "completed");
  assert.deepEqual(result.messages.map(m => m.id), ["m1", "m2"]);
  assert.equal(session.runFor("c1"), null);
  assert.equal(session.state.sending, false);
  assert.ok(seen.includes("completed"));
});

test("retry after failure reuses requestId and the original page context", async () => {
  const calls = [];
  let fail = true;
  const adapter = async function* (params) {
    calls.push(params);
    yield { type: "run.started" };
    if (fail) { yield { type: "run.error", error: "模型服务繁忙", diagnostic: "BUSY" }; return; }
    yield { type: "message.delta", delta: "好" };
    yield { type: "run.completed" };
  };
  const session = createAiSession({ adapter, newRequestId, loadMessages: async () => [] });
  const first = await session.send({ farmId: "f1", conversationId: "c1", question: "设备异常", context: { pageId: "devices", focusType: "device", focusId: "d1" } });
  assert.equal(first.status, "error");
  assert.equal(session.runFor("c1").status, "error");
  fail = false;
  const second = await session.retry();
  assert.equal(second.status, "completed");
  assert.equal(calls[1].requestId, calls[0].requestId);
  assert.equal(calls[1].pageId, "devices");
  assert.equal(calls[1].focusId, "d1");
  // 成功后不再保留待重试请求：同一问题再次发送是新的一轮
  await session.send({ farmId: "f1", conversationId: "c1", question: "设备异常" });
  assert.notEqual(calls[2].requestId, calls[0].requestId);
});

test("a changed question or conversation gets a new requestId", async () => {
  const calls = [];
  const adapter = async function* (params) { calls.push(params); yield { type: "run.error", error: "x" }; };
  const session = createAiSession({ adapter, newRequestId, loadMessages: async () => [] });
  await session.send({ farmId: "f1", conversationId: "c1", question: "甲" });
  await session.send({ farmId: "f1", conversationId: "c1", question: "乙" });
  await session.send({ farmId: "f1", conversationId: "c2", question: "乙" });
  assert.equal(new Set(calls.map(c => c.requestId)).size, 3);
});

test("stop aborts, returns the question and keeps a stopped run for the conversation", async () => {
  let aborted = false;
  const adapter = async function* ({ signal }) {
    yield { type: "run.started" };
    await new Promise(resolve => signal.addEventListener("abort", () => { aborted = true; resolve(); }));
  };
  const session = createAiSession({ adapter, newRequestId });
  const sending = session.send({ farmId: "f1", conversationId: "c1", question: "停下来" });
  await new Promise(resolve => setTimeout(resolve, 0));
  assert.equal(session.stop(), "停下来");
  assert.equal((await sending).status, "cancelled");
  assert.ok(aborted);
  assert.equal(session.state.sending, false);
  assert.equal(session.runFor("c1").status, "cancelled");
});

test("farm reset aborts the run and drops late events and retry state", async () => {
  const release = gate();
  const adapter = async function* () {
    yield { type: "run.started" };
    await release.promise;
    yield { type: "message.delta", delta: "旧农场的回答" };
    yield { type: "run.completed" };
  };
  const session = createAiSession({ adapter, newRequestId, loadMessages: async () => assert.fail("must not load after reset") });
  const sending = session.send({ farmId: "f1", conversationId: "c1", question: "问题" });
  await new Promise(resolve => setTimeout(resolve, 0));
  session.reset();
  release.open();
  assert.equal((await sending).status, "cancelled");
  assert.equal(session.state.run, null);
  assert.equal(session.state.conversationId, "");
  assert.equal((await session.retry()).status, "busy");
});

test("a second send while generating is rejected instead of interleaving runs", async () => {
  const release = gate();
  const adapter = async function* () { yield { type: "run.started" }; await release.promise; yield { type: "run.completed" }; };
  const session = createAiSession({ adapter, newRequestId, loadMessages: async () => [] });
  const first = session.send({ farmId: "f1", conversationId: "c1", question: "一" });
  await new Promise(resolve => setTimeout(resolve, 0));
  assert.equal((await session.send({ farmId: "f1", conversationId: "c2", question: "二" })).status, "busy");
  release.open();
  assert.equal((await first).status, "completed");
});
