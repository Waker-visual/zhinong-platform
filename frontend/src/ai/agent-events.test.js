import test from "node:test";
import assert from "node:assert/strict";
import { createAgentRun, reduceAgentEvent, cancelAgentRun } from "./agent-events.js";
import { syncConversationAdapter } from "./agent-adapter.js";

test("createAgentRun returns the initial submitted state", () => {
  const state = createAgentRun("今天地块需要灌溉吗？", "req-1");
  assert.equal(state.requestId, "req-1");
  assert.equal(state.question, "今天地块需要灌溉吗？");
  assert.equal(state.status, "submitted");
  assert.deepEqual(state.activities, []);
  assert.equal(state.text, "");
  assert.equal(state.messageId, null);
  assert.equal(state.diagnostic, null);
  assert.equal(state.error, null);
  assert.equal(state.lastSequence, 0);
});

test("run.started moves status to running without mutating input", () => {
  const state = createAgentRun("问题", "req-1");
  const frozen = JSON.parse(JSON.stringify(state));
  const next = reduceAgentEvent(state, { sequence: 1, type: "run.started" });
  assert.deepEqual(state, frozen);
  assert.notEqual(next, state);
  assert.equal(next.status, "running");
  assert.equal(next.lastSequence, 1);
});

test("message.delta events concatenate in order", () => {
  let state = createAgentRun("问题", "req-1");
  state = reduceAgentEvent(state, { sequence: 1, type: "run.started" });
  state = reduceAgentEvent(state, { sequence: 2, type: "message.delta", delta: "土壤" });
  state = reduceAgentEvent(state, { sequence: 3, type: "message.delta", delta: "水分偏低。" });
  assert.equal(state.text, "土壤水分偏低。");
  assert.equal(state.lastSequence, 3);
});

test("duplicate or stale sequence numbers are ignored", () => {
  let state = createAgentRun("问题", "req-1");
  state = reduceAgentEvent(state, { sequence: 1, type: "run.started" });
  state = reduceAgentEvent(state, { sequence: 2, type: "message.delta", delta: "A" });
  const beforeReplay = state;
  state = reduceAgentEvent(state, { sequence: 2, type: "message.delta", delta: "B" });
  assert.equal(state, beforeReplay);
  assert.equal(state.text, "A");
  state = reduceAgentEvent(state, { sequence: 1, type: "message.delta", delta: "C" });
  assert.equal(state.text, "A");
  assert.equal(state.lastSequence, 2);
});

test("activity status transitions pending -> running -> completed and upserts by id", () => {
  let state = createAgentRun("问题", "req-1");
  state = reduceAgentEvent(state, {
    sequence: 1,
    type: "activity.started",
    activity: { id: "ctx-1", kind: "context", label: "读取当前农场资料", status: "running" },
  });
  assert.equal(state.activities.length, 1);
  assert.equal(state.activities[0].status, "running");
  state = reduceAgentEvent(state, {
    sequence: 2,
    type: "activity.updated",
    activity: { id: "ctx-1", kind: "context", label: "读取当前农场资料", status: "running", detail: "已读取天气与四情" },
  });
  assert.equal(state.activities.length, 1);
  assert.equal(state.activities[0].detail, "已读取天气与四情");
  state = reduceAgentEvent(state, {
    sequence: 3,
    type: "activity.completed",
    activity: { id: "ctx-1", kind: "context", label: "读取当前农场资料", status: "completed", resultSummary: "已读取3类资料" },
  });
  assert.equal(state.activities.length, 1);
  assert.equal(state.activities[0].status, "completed");
  assert.equal(state.activities[0].resultSummary, "已读取3类资料");
});

test("activity insertion order is preserved across multiple activities", () => {
  let state = createAgentRun("问题", "req-1");
  state = reduceAgentEvent(state, {
    sequence: 1,
    type: "activity.started",
    activity: { id: "a", kind: "context", label: "第一步", status: "running" },
  });
  state = reduceAgentEvent(state, {
    sequence: 2,
    type: "activity.started",
    activity: { id: "b", kind: "tool", label: "第二步", status: "running" },
  });
  state = reduceAgentEvent(state, {
    sequence: 3,
    type: "activity.completed",
    activity: { id: "a", kind: "context", label: "第一步", status: "completed" },
  });
  assert.deepEqual(state.activities.map((a) => a.id), ["a", "b"]);
});

test("run.completed finalizes still-running activities and sets messageId", () => {
  let state = createAgentRun("问题", "req-1");
  state = reduceAgentEvent(state, { sequence: 1, type: "run.started" });
  state = reduceAgentEvent(state, {
    sequence: 2,
    type: "activity.started",
    activity: { id: "a", kind: "context", label: "读取资料", status: "running" },
  });
  state = reduceAgentEvent(state, { sequence: 3, type: "message.delta", delta: "结论：无需灌溉。" });
  state = reduceAgentEvent(state, { sequence: 4, type: "message.completed", messageId: "msg-1" });
  state = reduceAgentEvent(state, { sequence: 5, type: "run.completed" });
  assert.equal(state.status, "completed");
  assert.equal(state.messageId, "msg-1");
  assert.equal(state.text, "结论：无需灌溉。");
  assert.equal(state.activities[0].status, "completed");
});

test("run.error preserves partial text and activities and marks running activities as error", () => {
  let state = createAgentRun("问题", "req-1");
  state = reduceAgentEvent(state, { sequence: 1, type: "run.started" });
  state = reduceAgentEvent(state, {
    sequence: 2,
    type: "activity.started",
    activity: { id: "a", kind: "context", label: "读取资料", status: "running" },
  });
  state = reduceAgentEvent(state, { sequence: 3, type: "message.delta", delta: "部分内容" });
  state = reduceAgentEvent(state, {
    sequence: 4,
    type: "run.error",
    error: "模型服务响应超时",
    diagnostic: "TIMEOUT",
  });
  assert.equal(state.status, "error");
  assert.equal(state.text, "部分内容");
  assert.equal(state.error, "模型服务响应超时");
  assert.equal(state.diagnostic, "TIMEOUT");
  assert.equal(state.activities[0].status, "error");
});

test("reduceAgentEvent never mutates the input state object", () => {
  let state = createAgentRun("问题", "req-1");
  state = reduceAgentEvent(state, { sequence: 1, type: "run.started" });
  state = reduceAgentEvent(state, {
    sequence: 2,
    type: "activity.started",
    activity: { id: "a", kind: "context", label: "读取资料", status: "running" },
  });
  const before = JSON.parse(JSON.stringify(state));
  reduceAgentEvent(state, { sequence: 3, type: "message.delta", delta: "新增内容" });
  assert.deepEqual(state, before);
});

test("cancelAgentRun marks the run cancelled without losing partial text", () => {
  let state = createAgentRun("问题", "req-1");
  state = reduceAgentEvent(state, { sequence: 1, type: "run.started" });
  state = reduceAgentEvent(state, { sequence: 2, type: "message.delta", delta: "部分" });
  const cancelled = cancelAgentRun(state);
  assert.equal(cancelled.status, "cancelled");
  assert.equal(cancelled.text, "部分");
  assert.notEqual(cancelled, state);
});

test("syncConversationAdapter emits the expected event sequence on success", async () => {
  const send = async () => ({ answer: "建议：保持当前灌溉策略。", mode: "llm", diagnostic: "OK" });
  const events = [];
  for await (const event of syncConversationAdapter({ farmId: "f1", conversationId: "c1", question: "需要灌溉吗？", requestId: "req-1", send })) {
    events.push(event);
  }
  const types = events.map((e) => e.type);
  assert.deepEqual(types, [
    "run.started",
    "activity.started",
    "activity.completed",
    "message.delta",
    "message.completed",
    "run.completed",
  ]);
  const sequences = events.map((e) => e.sequence);
  assert.deepEqual(sequences, [1, 2, 3, 4, 5, 6]);
  const delta = events.find((e) => e.type === "message.delta");
  assert.equal(delta.delta, "建议：保持当前灌溉策略。");
  const completed = events.find((e) => e.type === "message.completed");
  assert.equal(typeof completed.messageId, "string");
});

test("syncConversationAdapter yields run.error on failure without throwing", async () => {
  const send = async () => {
    const error = new Error("模型服务响应超时");
    error.status = 504;
    throw error;
  };
  const events = [];
  for await (const event of syncConversationAdapter({ farmId: "f1", conversationId: "c1", question: "需要灌溉吗？", requestId: "req-2", send })) {
    events.push(event);
  }
  const last = events.at(-1);
  assert.equal(last.type, "run.error");
  assert.equal(typeof last.error, "string");
  assert.ok(last.error.length > 0);
});

test("syncConversationAdapter reports rule fallback diagnostic when model unavailable", async () => {
  const send = async () => ({ answer: "规则回退建议", mode: "rule", diagnostic: "TIMEOUT" });
  const events = [];
  for await (const event of syncConversationAdapter({ farmId: "f1", conversationId: "c1", question: "问题", requestId: "req-3", send })) {
    events.push(event);
  }
  const completed = events.find((e) => e.type === "run.completed");
  assert.ok(completed);
  const delta = events.find((e) => e.type === "message.delta");
  assert.equal(delta.delta, "规则回退建议");
});
