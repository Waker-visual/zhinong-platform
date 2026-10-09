import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  createAgentRun,
  reduceAgentEvent,
  cancelAgentRun,
  visibleMessages,
  activityStatusLabel,
  shouldAutoOpenActivities,
  activitySummaryText,
  streamingStatusText,
  shouldShowCaret,
  irrigationApprovalTarget,
  formatActivityDuration,
  formatElapsedStatus,
  formatMessageTime,
} from "./agent-events.js";
import { syncConversationAdapter, streamConversationAdapter, defaultConversationAdapter } from "./agent-adapter.js";
import { isNearBottom } from "./scroll.js";
import { createSseParser } from "./sse-parser.js";

const dir = path.dirname(fileURLToPath(import.meta.url));
const read = (name) => fs.readFileSync(path.join(dir, name), "utf8");

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

test("cancelAgentRun marks pending/running activities as error, not completed", () => {
  let state = createAgentRun("问题", "req-1");
  state = reduceAgentEvent(state, { sequence: 1, type: "run.started" });
  state = reduceAgentEvent(state, {
    sequence: 2,
    type: "activity.started",
    activity: { id: "a", kind: "context", label: "读取资料", status: "running" },
  });
  state = reduceAgentEvent(state, {
    sequence: 3,
    type: "activity.started",
    activity: { id: "b", kind: "tool", label: "等待确认", status: "pending" },
  });
  const cancelled = cancelAgentRun(state);
  assert.equal(cancelled.activities[0].status, "error");
  assert.equal(cancelled.activities[0].detail, "已停止");
  assert.equal(cancelled.activities[1].status, "error");
  assert.notEqual(cancelled.activities[0].status, "completed");
});

test("visibleMessages appends temporary user message and streaming placeholder without mutating history", () => {
  const history = [{ id: "m1", role: "user", content: "之前的问题" }, { id: "m2", role: "assistant", content: "之前的回答" }];
  const frozenHistory = JSON.parse(JSON.stringify(history));
  let run = createAgentRun("新的问题", "req-9");
  run = reduceAgentEvent(run, { sequence: 1, type: "run.started" });
  run = reduceAgentEvent(run, { sequence: 2, type: "message.delta", delta: "正在生成" });
  const visible = visibleMessages(history, run);
  assert.deepEqual(history, frozenHistory);
  assert.notEqual(visible, history);
  assert.equal(visible.length, 4);
  assert.equal(visible[0], history[0]);
  assert.equal(visible[1], history[1]);
  assert.equal(visible[2].role, "user");
  assert.equal(visible[2].content, "新的问题");
  assert.equal(visible[2].temporary, true);
  assert.equal(visible[3].role, "assistant");
  assert.equal(visible[3].pending, true);
  assert.equal(visible[3].content, "正在生成");
  assert.equal(visible[3].run, run);
});

test("visibleMessages returns the original history when there is no active run", () => {
  const history = [{ id: "m1", role: "user", content: "问题" }];
  assert.equal(visibleMessages(history, null), history);
});

test("isNearBottom treats distances within the 72px threshold as near", () => {
  // scrollHeight 1000, clientHeight 600 -> distance = 1000 - scrollTop - 600
  assert.equal(isNearBottom(400, 1000, 600), true); // distance 0
  assert.equal(isNearBottom(328, 1000, 600), true); // distance 72, boundary inclusive
  assert.equal(isNearBottom(327, 1000, 600), false); // distance 73
  assert.equal(isNearBottom(0, 1000, 600), false); // distance 400, scrolled far up
  assert.equal(isNearBottom(0, 400, 600), true); // content shorter than viewport
  assert.equal(isNearBottom(200, 1000, 600, 300), true); // custom wider threshold
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

// --- 纯 SSE 解析器：覆盖跨 chunk 断行、\r\n 行尾和多行 data 字段拼接 ---

test("createSseParser parses a single complete event delivered in one chunk", () => {
  const parser = createSseParser();
  const events = parser.feed('data:{"type":"run.started","sequence":1}\n\n');
  assert.equal(events.length, 1);
  assert.deepEqual(JSON.parse(events[0].data), { type: "run.started", sequence: 1 });
});

test("createSseParser reassembles an event split mid-line across multiple feed() calls", () => {
  const parser = createSseParser();
  let events = parser.feed('data:{"type":"message.delta","del');
  assert.equal(events.length, 0, "no complete line yet");
  events = parser.feed('ta":"土壤水分偏低"}\n\n');
  assert.equal(events.length, 1);
  assert.deepEqual(JSON.parse(events[0].data), { type: "message.delta", delta: "土壤水分偏低" });
});

test("createSseParser handles \\r\\n line endings the same as \\n", () => {
  const parser = createSseParser();
  const events = parser.feed('data:{"type":"run.completed"}\r\n\r\n');
  assert.equal(events.length, 1);
  assert.deepEqual(JSON.parse(events[0].data), { type: "run.completed" });
});

test("createSseParser joins multiple data: lines of the same event with \\n", () => {
  const parser = createSseParser();
  const events = parser.feed("data:line one\ndata:line two\n\n");
  assert.equal(events.length, 1);
  assert.equal(events[0].data, "line one\nline two");
});

test("createSseParser parses two events delivered back to back in one chunk, in order", () => {
  const parser = createSseParser();
  const events = parser.feed('data:{"sequence":1}\n\ndata:{"sequence":2}\n\n');
  assert.equal(events.length, 2);
  assert.equal(JSON.parse(events[0].data).sequence, 1);
  assert.equal(JSON.parse(events[1].data).sequence, 2);
});

// --- streamConversationAdapter / defaultConversationAdapter ---

async function* fakeEventStream(events) {
  for (const e of events) yield e;
}

test("streamConversationAdapter forwards every event the backend sends, unchanged", async () => {
  const backendEvents = [
    { sequence: 1, type: "run.started" },
    { sequence: 2, type: "message.delta", delta: "结论：" },
    { sequence: 3, type: "run.completed" },
  ];
  const send = () => fakeEventStream(backendEvents);
  const events = [];
  for await (const e of streamConversationAdapter({ conversationId: "c1", question: "q", requestId: "r1", send })) events.push(e);
  assert.deepEqual(events, backendEvents);
});

test("streamConversationAdapter rethrows when the backend fails before any event is yielded", async () => {
  const send = async function* () {
    throw Object.assign(new Error("流式接口不可用"), { status: 404 });
  };
  await assert.rejects(
    (async () => {
      for await (const _ of streamConversationAdapter({ conversationId: "c1", question: "q", requestId: "r1", send })) {
        // no-op
      }
    })(),
    /流式接口不可用/,
  );
});

test("streamConversationAdapter yields run.error instead of throwing once it already produced events", async () => {
  const send = async function* () {
    yield { sequence: 1, type: "run.started" };
    yield { sequence: 2, type: "message.delta", delta: "部分" };
    throw Object.assign(new Error("连接中断"), { diagnostic: "UNAVAILABLE" });
  };
  const events = [];
  for await (const e of streamConversationAdapter({ conversationId: "c1", question: "q", requestId: "r1", send })) events.push(e);
  assert.equal(events.length, 3);
  assert.equal(events[2].type, "run.error");
  assert.equal(events[2].diagnostic, "UNAVAILABLE");
});

test("streamConversationAdapter ends silently on a user-initiated cancellation (no run.error)", async () => {
  const send = async function* () {
    yield { sequence: 1, type: "run.started" };
    const cancelled = new Error("已取消");
    cancelled.cancelled = true;
    throw cancelled;
  };
  const events = [];
  for await (const e of streamConversationAdapter({ conversationId: "c1", question: "q", requestId: "r1", send })) events.push(e);
  assert.deepEqual(events, [{ sequence: 1, type: "run.started" }]);
});

test("defaultConversationAdapter falls back to the sync adapter when streaming is unavailable before any event", async () => {
  const streamSend = async function* () {
    throw Object.assign(new Error("Not Found"), { status: 404 });
  };
  const syncSend = async () => ({ answer: "同步回退的回答", mode: "llm", diagnostic: "OK" });
  const events = [];
  for await (const e of defaultConversationAdapter({ conversationId: "c1", question: "q", requestId: "r1", streamSend, syncSend })) events.push(e);
  const types = events.map((e) => e.type);
  assert.deepEqual(types, ["run.started", "activity.started", "activity.completed", "message.delta", "message.completed", "run.completed"]);
  assert.equal(events.find((e) => e.type === "message.delta").delta, "同步回退的回答");
});

test("defaultConversationAdapter never falls back once the stream already produced events (surfaces run.error instead)", async () => {
  let syncCalled = false;
  const streamSend = async function* () {
    yield { sequence: 1, type: "run.started" };
    throw Object.assign(new Error("连接中断"), { diagnostic: "UNAVAILABLE" });
  };
  const syncSend = async () => {
    syncCalled = true;
    return { answer: "不应该走到这里", mode: "llm", diagnostic: "OK" };
  };
  const events = [];
  for await (const e of defaultConversationAdapter({ conversationId: "c1", question: "q", requestId: "r1", streamSend, syncSend })) events.push(e);
  assert.equal(syncCalled, false, "must not double-submit the same question through the sync endpoint");
  assert.equal(events.at(-1).type, "run.error");
});

// --- 纯文案/判定辅助函数：供折叠区、活动行、流式状态条复用的可访问性文案 ---

test("activityStatusLabel maps known statuses to Chinese labels and falls back to the raw value", () => {
  assert.equal(activityStatusLabel("pending"), "等待中");
  assert.equal(activityStatusLabel("running"), "进行中");
  assert.equal(activityStatusLabel("completed"), "已完成");
  assert.equal(activityStatusLabel("error"), "出错");
  assert.equal(activityStatusLabel("unknown-status"), "unknown-status");
});

test("shouldAutoOpenActivities is true while submitted/running/error and false once completed/cancelled", () => {
  assert.equal(shouldAutoOpenActivities("submitted"), true);
  assert.equal(shouldAutoOpenActivities("running"), true);
  assert.equal(shouldAutoOpenActivities("error"), true);
  assert.equal(shouldAutoOpenActivities("completed"), false);
  assert.equal(shouldAutoOpenActivities("cancelled"), false);
});

test("activitySummaryText reports short human labels without exposing raw technical fields", () => {
  assert.equal(activitySummaryText([], "error"), "出错");
  // 没有任何可识别 kind 的活动时，退回笼统的“已完成 N 项操作”而不是空字符串。
  assert.equal(activitySummaryText([{ id: "a" }, { id: "b" }], "completed"), "已完成 2 项操作");
  assert.equal(activitySummaryText([{ id: "a" }], "cancelled"), "已停止 · 1 项");
  assert.equal(
    activitySummaryText([{ id: "a", status: "running", label: "读取当前农场资料" }], "running"),
    "读取当前农场资料 · 1 项",
  );
  assert.equal(activitySummaryText([], "running"), "正在准备…");
  assert.equal(activitySummaryText([{ id: "a", status: "completed" }], "running"), "正在处理 · 1 项");
});

test("activitySummaryText builds a semantic sentence for a completed run: kind counts, pending approval and total duration", () => {
  const activities = [
    { id: "ctx", kind: "context", status: "completed", startedAt: "2026-10-09T00:00:00.000Z", finishedAt: "2026-10-09T00:00:01.000Z" },
    { id: "t1", kind: "tool", status: "completed", startedAt: "2026-10-09T00:00:01.000Z", finishedAt: "2026-10-09T00:00:01.300Z" },
    { id: "t2", kind: "tool", status: "completed", startedAt: "2026-10-09T00:00:01.300Z", finishedAt: "2026-10-09T00:00:01.800Z" },
    { id: "run-1", kind: "approval", status: "pending", startedAt: "2026-10-09T00:00:01.800Z" },
  ];
  assert.equal(activitySummaryText(activities, "completed"), "读取 1 项资料 · 查询 2 项 · 1 项待你确认 · 用时 1.8s");
});

test("activitySummaryText counts errored activities in a completed run without hiding them", () => {
  const activities = [
    { id: "ctx", kind: "context", status: "completed" },
    { id: "t1", kind: "tool", status: "error" },
  ];
  assert.equal(activitySummaryText(activities, "completed"), "读取 1 项资料 · 1 项出错");
});

test("formatActivityDuration keeps one decimal under 10s and rounds to whole seconds at or above 10s", () => {
  assert.equal(formatActivityDuration(1800), "1.8s");
  assert.equal(formatActivityDuration(300), "0.3s");
  assert.equal(formatActivityDuration(12000), "12s");
  assert.equal(formatActivityDuration(12400), "12s");
  assert.equal(formatActivityDuration(null), "");
  assert.equal(formatActivityDuration(-5), "");
});

test("formatElapsedStatus renders a whole-second running timer", () => {
  assert.equal(formatElapsedStatus(12.4), "已用 12s");
  assert.equal(formatElapsedStatus(0), "已用 0s");
  assert.equal(formatElapsedStatus(-1), "已用 0s");
});

test("finalizeRunningActivities (via run.completed) never silently completes a still-pending approval activity", () => {
  let state = createAgentRun("问题", "req-1");
  state = reduceAgentEvent(state, { sequence: 1, type: "run.started" });
  state = reduceAgentEvent(state, {
    sequence: 2,
    type: "activity.completed",
    activity: { id: "irrigation-run:r1", kind: "approval", label: "灌溉建议待确认：地块A", status: "pending" },
  });
  state = reduceAgentEvent(state, { sequence: 3, type: "run.completed" });
  assert.equal(state.status, "completed");
  assert.equal(state.activities[0].status, "pending", "an awaiting-approval activity must stay pending after run.completed, not flip to completed");
});

test("streamingStatusText returns the expected Chinese text per status, including the error diagnostic fallback", () => {
  assert.equal(streamingStatusText("submitted"), "正在读取当前农场资料…");
  assert.equal(streamingStatusText("running"), "正在生成回答…");
  assert.equal(streamingStatusText("completed"), "已完成");
  assert.equal(streamingStatusText("cancelled"), "已停止；若已回退到同步请求，仍可能在后台继续，刷新对话后可看到结果");
  assert.equal(streamingStatusText("error", "模型服务响应超时"), "模型服务响应超时");
  assert.equal(streamingStatusText("error", ""), "回答失败，请重试");
  assert.equal(streamingStatusText("error"), "回答失败，请重试");
});

test("shouldShowCaret only appears while running and text has already started streaming", () => {
  assert.equal(shouldShowCaret("running", "部分正文"), true);
  assert.equal(shouldShowCaret("running", ""), false);
  assert.equal(shouldShowCaret("submitted", "部分正文"), false);
  assert.equal(shouldShowCaret("completed", "全部正文"), false);
  assert.equal(shouldShowCaret("error", "部分正文"), false);
});

test("irrigationApprovalTarget parses the irrigation-run id encoded in an approval activity", () => {
  assert.deepEqual(
    irrigationApprovalTarget({ id: "irrigation-run:run-123", kind: "approval" }),
    { type: "irrigation-run", id: "run-123" },
  );
});

test("irrigationApprovalTarget returns null for non-approval activities, even with a matching id", () => {
  assert.equal(irrigationApprovalTarget({ id: "irrigation-run:run-123", kind: "tool" }), null);
});

test("irrigationApprovalTarget returns null when the approval activity has no encoded target", () => {
  assert.equal(irrigationApprovalTarget({ id: "context", kind: "approval" }), null);
  assert.equal(irrigationApprovalTarget({ id: "irrigation-run:", kind: "approval" }), null);
  assert.equal(irrigationApprovalTarget(null), null);
});

// 持久化消息走 GET /conversations/{id}/messages 时，活动摘要的字段名是文档约定的 activityId
// （数据库列 ACTIVITY_ID 经 api.js 的 normalize() 转换而来），不是实时 SSE 事件用的 id。
// 两条路径的活动最终都会交给 AiApprovalCard 解析出审批目标，必须都认得，否则重新打开对话后
// “去确认”按钮会消失。
test("irrigationApprovalTarget also recognizes the persisted activityId field (reload path), not just the live SSE id field", () => {
  assert.deepEqual(
    irrigationApprovalTarget({ activityId: "irrigation-run:run-456", kind: "approval" }),
    { type: "irrigation-run", id: "run-456" },
  );
});

// --- formatMessageTime：当天/昨天(跨午夜)/刚刚/n天前的边界，不依赖浏览器时区之外的任何东西 ---

test("formatMessageTime reports '刚刚' for a message less than 60s old", () => {
  const now = new Date("2026-10-09T12:00:30.000+08:00");
  const r = formatMessageTime(new Date("2026-10-09T12:00:00.000+08:00"), now);
  assert.equal(r.display, "刚刚");
  assert.equal(r.full, "2026年10月9日 12:00");
});

test("formatMessageTime shows HH:mm for an earlier message the same calendar day", () => {
  const now = new Date("2026-10-09T20:30:00.000+08:00");
  const r = formatMessageTime(new Date("2026-10-09T08:05:00.000+08:00"), now);
  assert.equal(r.display, "08:05");
});

test("formatMessageTime shows '昨天 HH:mm' across a midnight boundary", () => {
  const now = new Date("2026-10-09T00:10:00.000+08:00");
  const r = formatMessageTime(new Date("2026-10-08T23:50:00.000+08:00"), now);
  assert.equal(r.display, "昨天 23:50");
});

test("formatMessageTime shows 'n 天前' for 2-30 days ago", () => {
  const now = new Date("2026-10-09T12:00:00.000+08:00");
  assert.equal(formatMessageTime(new Date("2026-10-07T12:00:00.000+08:00"), now).display, "2 天前");
  assert.equal(formatMessageTime(new Date("2026-09-15T12:00:00.000+08:00"), now).display, "24 天前");
});

test("formatMessageTime falls back to a plain date beyond 30 days", () => {
  const now = new Date("2026-10-09T12:00:00.000+08:00");
  assert.equal(formatMessageTime(new Date("2026-08-01T12:00:00.000+08:00"), now).display, "8月1日");
  assert.equal(formatMessageTime(new Date("2024-01-05T12:00:00.000+08:00"), now).display, "2024年1月5日");
});

test("formatMessageTime always exposes an ISO datetime attribute for <time datetime>", () => {
  const r = formatMessageTime(new Date("2026-10-09T08:05:00.000Z"), new Date("2026-10-09T09:00:00.000Z"));
  assert.equal(r.iso, "2026-10-09T08:05:00.000Z");
});

// --- 静态源码检查：role/aria-busy/aria-live、装饰性动画元素的 aria-hidden、reduced-motion 覆盖均需存在 ---
// 没有 DOM 测试运行器，这里直接读取 .vue/.css 源文本做字符串级断言，覆盖本任务要求的可访问性标记。

test("the message list exposes role=log and a reactive aria-busy binding", () => {
  const src = read("FarmAssistant.vue");
  assert.match(src, /class="ai-messages"[^>]*role="log"/);
  assert.match(src, /class="ai-messages"[^>]*:aria-busy="[^"]+"/);
});

test("AiStreamingStatus exposes role=status and aria-live=polite for polite status announcements", () => {
  const src = read("AiStreamingStatus.vue");
  assert.match(src, /class="ai-streaming-status"[^>]*role="status"/);
  assert.match(src, /class="ai-streaming-status"[^>]*aria-live="polite"/);
});

test("the streamed assistant text itself is not nested inside the aria-live status region (no per-delta spam)", () => {
  const src = read("FarmAssistant.vue");
  // AiStreamingStatus（role=status/aria-live）与承载逐字增量文本的 ai-message-text 是兄弟节点，不互相嵌套。
  const statusIndex = src.indexOf("<AiStreamingStatus");
  const textIndex = src.indexOf('class="ai-message-text"');
  assert.ok(statusIndex > -1 && textIndex > -1);
  assert.ok(textIndex < statusIndex, "ai-message-text must come before the sibling AiStreamingStatus, not wrap it");
});

test("decorative animated caret and status/activity dots carry aria-hidden=true", () => {
  const farmAssistant = read("FarmAssistant.vue");
  assert.match(farmAssistant, /class="ai-caret"[^>]*aria-hidden="true"/);
  assert.match(farmAssistant, /class="ai-skeleton-lines"[^>]*aria-hidden="true"/);
  const streamingStatus = read("AiStreamingStatus.vue");
  assert.match(streamingStatus, /class="ai-status-dot"[^>]*aria-hidden="true"/);
  const activityRow = read("AiActivityRow.vue");
  assert.match(activityRow, /class="ai-activity-dot"[^>]*aria-hidden="true"/);
});

test("assistant.css disables the caret, shimmer text and disclosure transition under prefers-reduced-motion: reduce", () => {
  const css = read("assistant.css");
  const match = css.match(/@media \(prefers-reduced-motion: reduce\) \{([\s\S]*?)\n\}/);
  assert.ok(match, "assistant.css must contain a prefers-reduced-motion: reduce block");
  const block = match[1];
  assert.match(block, /\.ai-caret\s*\{[^}]*animation:\s*none/);
  assert.match(block, /\.ai-streaming-status\.submitted \.ai-status-text,\s*\.ai-streaming-status\.running \.ai-status-text\s*\{[^}]*animation:\s*none/);
  assert.match(block, /\.ai-activity-collapse[^{]*\{[^}]*transition:\s*none/);
});
