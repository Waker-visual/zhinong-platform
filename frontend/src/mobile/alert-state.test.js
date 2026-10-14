import test from "node:test";
import assert from "node:assert/strict";
import { effectScope, nextTick, reactive } from "vue";
import { useAlertState } from "./alert-state.mjs";

function setup(t, request = async () => ({})) {
  const props = reactive({
    alerts: [{ id: "A" }, { id: "B" }],
    initialId: "A",
    role: "OPERATOR",
    locked: false,
    client: { request },
  });
  let changes = 0;
  const scope = effectScope();
  const state = scope.run(() => useAlertState(props, () => { changes++; }));
  t.after(() => scope.stop());
  return { props, state, scope, changes: () => changes };
}

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

test("refresh preserves the manually selected alert and its draft", async (t) => {
  const { props, state } = setup(t);
  assert.equal(state.selectedId.value, "A");
  state.select({ id: "B" });
  state.note.value = "已核对 B 设备";
  props.alerts = [{ id: "A", value: 1 }, { id: "B", value: 2 }];
  await nextTick();
  assert.equal(state.selectedId.value, "B");
  assert.equal(state.selected.value.value, 2);
  assert.equal(state.note.value, "已核对 B 设备");
});

test("a disappeared alert cannot transfer its draft or error to another alert", async (t) => {
  const { props, state } = setup(t);
  state.select({ id: "B" });
  await state.handle("RESOLVED");
  assert.ok(state.error.value);
  state.note.value = "B 的处理结果";
  props.alerts = [{ id: "A" }];
  await nextTick();
  assert.equal(state.selectedId.value, "A");
  assert.equal(state.note.value, "");
  assert.equal(state.error.value, "");
  state.note.value = "A 的处理结果";
  props.alerts = [];
  await nextTick();
  assert.equal(state.selected.value, null);
  assert.equal(state.note.value, "");
});

test("changing the requested initial alert selects it and clears the previous draft", async (t) => {
  const { props, state } = setup(t);
  state.note.value = "A 的说明";
  props.initialId = "B";
  await nextTick();
  assert.equal(state.selectedId.value, "B");
  assert.equal(state.note.value, "");
});

test("saving keeps its target and blocks manual selection until the response", async (t) => {
  const pending = deferred(), calls = [];
  const { state, changes } = setup(t, (...args) => { calls.push(args); return pending.promise; });
  state.note.value = "  A 的处理结果  ";
  const work = state.handle("RESOLVED");
  state.select({ id: "B" });
  assert.equal(state.selectedId.value, "A");
  assert.equal(state.busy.value, true);
  assert.deepEqual(calls, [["/alerts/A", "PATCH", { status: "RESOLVED", note: "A 的处理结果" }]]);
  pending.resolve({});
  await work;
  assert.equal(state.busy.value, false);
  assert.equal(state.note.value, "");
  assert.equal(changes(), 1);
});

for (const result of ["success", "failure"]) {
  test(`a late ${result} cannot affect an alert selected by a newer refresh`, async (t) => {
    const pending = deferred();
    const { props, state, changes } = setup(t, () => pending.promise);
    state.note.value = "A 的处理结果";
    const work = state.handle("RESOLVED");
    props.alerts = [{ id: "B" }];
    await nextTick();
    assert.equal(state.selectedId.value, "B");
    assert.equal(state.note.value, "");
    if (result === "success") pending.resolve({});
    else pending.reject(new Error("A 保存失败"));
    await work;
    assert.equal(state.selectedId.value, "B");
    assert.equal(state.error.value, "");
    assert.equal(changes(), 0);
    assert.equal(state.busy.value, false);
  });
}

test("closing the alert panel prevents its late save from closing a newer panel", async (t) => {
  const pending = deferred();
  const { state, scope, changes } = setup(t, () => pending.promise);
  state.note.value = "A 的处理结果";
  const work = state.handle("RESOLVED");
  scope.stop();
  pending.resolve({});
  await work;
  assert.equal(changes(), 0);
});
