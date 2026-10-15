import test from "node:test";
import assert from "node:assert/strict";
import { assistantPageContext, floatingSurfaceLabel, pageSources, prepareFloatingQuestion, sourceScopeLabel } from "./floating-assistant-model.js";

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
