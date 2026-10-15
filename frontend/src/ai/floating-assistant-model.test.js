import test from "node:test";
import assert from "node:assert/strict";
import { assistantPageContext, floatingSurfaceLabel, prepareFloatingQuestion } from "./floating-assistant-model.js";

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
