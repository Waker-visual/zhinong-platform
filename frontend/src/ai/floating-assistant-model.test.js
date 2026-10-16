import test from "node:test";
import assert from "node:assert/strict";
import { assistantPageContext, assistantPanelPosition, clampAssistantPosition, fallbackQuestion, floatingSurfaceLabel, pageSources, prepareFloatingQuestion, sourceScopeLabel } from "./floating-assistant-model.js";

test("page sources keep only server-written source records", () => {
  const activities = [
    { activityId: "context", kind: "context", label: "读取当前农场资料", resultSummary: "已读取" },
    { activityId: "page-source", kind: "source", label: "参考页面：设备台账", resultSummary: "已定位「一号墒情站」" },
    { activityId: "bad", kind: "source", label: "" },
  ];
  assert.deepEqual(pageSources(activities), [{ id: "page-source", label: "参考页面：设备台账", summary: "已定位「一号墒情站」" }]);
  assert.deepEqual(pageSources(undefined), []);
});

test("scope label only states the intent, never guesses object names", () => {
  assert.equal(sourceScopeLabel("设备台账", { focusId: "d1" }), "设备台账 · 当前所选对象");
  assert.equal(sourceScopeLabel("今日农场", {}), "今日农场");
});

test("page context keeps only allowed focus types", () => {
  assert.deepEqual(assistantPageContext("devices", { type: "device", id: "d1" }), { pageId: "devices", focusType: "device", focusId: "d1" });
  assert.deepEqual(assistantPageContext("devices", { type: "plot", id: "p1" }), { pageId: "devices", focusType: null, focusId: null });
  assert.equal(assistantPageContext("unknown").pageId, "daily");
});

test("floating quick topics fill only an empty draft", () => {
  const topic = { question: "今天做什么" };
  assert.deepEqual(prepareFloatingQuestion("", topic), { question: "今天做什么", changed: true });
  assert.deepEqual(prepareFloatingQuestion("已有问题", topic), { question: "已有问题", changed: false });
});

test("surface label distinguishes shortcut history", () => {
  assert.equal(floatingSurfaceLabel("floating"), "小窗");
  assert.equal(floatingSurfaceLabel(null), "");
});

test("dragged assistant stays reachable after crossing edges or resizing above mobile navigation", () => {
  const size = { width: 104, height: 116 };
  assert.deepEqual(clampAssistantPosition({ x: -400, y: -300 }, size, { width: 1280, height: 800 }), { x: 12, y: 12 });
  assert.deepEqual(clampAssistantPosition({ x: 1500, y: 900 }, size, { width: 360, height: 800 }, 64), { x: 244, y: 608 });
});

test("model retry uses the question belonging to the failed answer, never another turn", () => {
  const messages = [{ role: "user", requestId: "older", content: "旧问题" }, { role: "user", requestId: "failed", content: "  请整理优先事项  " }];
  assert.equal(fallbackQuestion({ role: "assistant", mode: "rule", requestId: "failed" }, messages), "请整理优先事项");
  assert.equal(fallbackQuestion({ role: "assistant", mode: "rule", requestId: "missing" }, messages), "");
  assert.equal(fallbackQuestion({ role: "assistant", mode: "llm", requestId: "failed" }, messages), "");
});

test("assistant dialog stays on screen when the opener moves to each edge", () => {
  const viewport = { width: 1280, height: 800 }, size = { width: 408, height: 600 };
  assert.deepEqual(assistantPanelPosition({ left: 1152, right: 1256, top: 660, bottom: 776 }, size, viewport), { x: 848, y: 48 });
  assert.deepEqual(assistantPanelPosition({ left: 12, right: 116, top: 12, bottom: 128 }, size, viewport), { x: 12, y: 140 });
  assert.deepEqual(assistantPanelPosition({ left: 600, right: 704, top: 340, bottom: 456 }, size, viewport), { x: 180, y: 188 });
});
