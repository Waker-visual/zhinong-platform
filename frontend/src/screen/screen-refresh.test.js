import test from "node:test";
import assert from "node:assert/strict";
import { createScreenRefresh, screenFreshness } from "./screen-refresh.js";

function fixture() {
  const calls = [],
    updates = [];
  let now = Date.parse("2026-10-12T00:00:00Z");
  const refresh = createScreenRefresh({
    request: (path, { signal }) =>
      new Promise((resolve, reject) =>
        calls.push({ path, signal, resolve, reject }),
      ),
    onUpdate: (update) => updates.push(update),
    now: () => now,
  });
  return {
    refresh,
    calls,
    updates,
    state: () => updates.at(-1),
    later: () => {
      now += 60_000;
    },
    finish: (start = 0) =>
      calls.slice(start).forEach((call) => call.resolve({ value: call.path })),
  };
}
const tick = () => new Promise((resolve) => setImmediate(resolve));

test("fast cards publish while a slow briefing is still pending", async () => {
  const f = fixture(),
    done = f.refresh.refresh("farm-a");
  f.calls[0].resolve({ farm: { name: "演示农场" } });
  await tick();
  assert.equal(f.state().resources.workspace.data.farm.name, "演示农场");
  assert.equal(f.state().resources.workspace.loading, false);
  assert.equal(f.state().resources.briefing.loading, true);
  assert.equal(f.state().loading, true);
  f.finish();
  await done;
  assert.equal(f.state().loading, false);
});

test("automatic and manual refresh share an in-flight batch without duplicate reads", async () => {
  const f = fixture(),
    first = f.refresh.refresh("farm-a");
  const second = f.refresh.refresh("farm-a"),
    third = f.refresh.refresh("farm-a", { onlyFailed: true });
  assert.equal(first, second);
  assert.equal(first, third);
  assert.equal(f.calls.length, 6);
  f.finish();
  await first;
});

test("a switched farm aborts old work and ignores even late successful responses", async () => {
  const f = fixture(),
    old = f.refresh.refresh("farm-a");
  const next = f.refresh.refresh("farm-b");
  assert.ok(f.calls.slice(0, 6).every((call) => call.signal.aborted));
  f.calls[0].resolve({ farm: { name: "旧农场" } });
  await tick();
  assert.equal(f.state().scope, "farm-b");
  assert.equal(f.state().resources.workspace.data, null);
  f.finish();
  await Promise.all([old, next]);
  assert.match(f.state().resources.workspace.data.value, /farm-b/);
});

test("a failed refresh retains the original snapshot time instead of advertising freshness", async () => {
  const f = fixture(),
    initial = f.refresh.refresh("farm-a");
  f.finish();
  await initial;
  const previous = f.state().resources.workspace;
  f.later();
  const next = f.refresh.refresh("farm-a");
  f.calls[6].reject(new Error("服务暂时不可用"));
  f.finish(7);
  await next;
  const state = f.state();
  assert.equal(state.resources.workspace.data, previous.data);
  assert.equal(state.resources.workspace.readAt, previous.readAt);
  assert.equal(state.resources.workspace.error, "服务暂时不可用");
  assert.notEqual(state.resources.operations.readAt, previous.readAt);
  assert.deepEqual(screenFreshness(state.resources), {
    readAt: previous.readAt,
    loaded: 6,
    total: 6,
    failed: 1,
  });
});

test("retry only requests failed resources and clears the error on success", async () => {
  const f = fixture(),
    initial = f.refresh.refresh("farm-a");
  f.calls[3].reject(new Error("简报超时"));
  f.finish();
  await initial;
  const retry = f.refresh.refresh("farm-a", { onlyFailed: true });
  assert.equal(f.calls.length, 7);
  assert.match(f.calls[6].path, /briefings/);
  assert.equal(
    f.state().resources.briefing.error,
    "简报超时",
    "old warning stays visible until recovery",
  );
  f.calls[6].resolve({ reportDate: "2026-10-12" });
  await retry;
  assert.equal(f.state().resources.briefing.error, null);
  await f.refresh.refresh("farm-a", { onlyFailed: true });
  assert.equal(f.calls.length, 7);
});

test("hiding the page cancels pending work, retains rendered cards and allows a fresh batch on resume", async () => {
  const f = fixture(),
    initial = f.refresh.refresh("farm-a");
  f.calls[0].resolve({ farm: { name: "演示农场" } });
  await tick();
  f.refresh.cancel();
  assert.ok(f.calls.every((call) => call.signal.aborted));
  assert.equal(f.state().loading, false);
  assert.equal(f.state().resources.workspace.data.farm.name, "演示农场");
  const next = f.refresh.refresh("farm-a");
  assert.equal(f.calls.length, 12);
  f.finish();
  await Promise.all([initial, next]);
});

test("access loss clears snapshots and cancelled results cannot restore them", async () => {
  const f = fixture(),
    initial = f.refresh.refresh("farm-a");
  f.calls[0].resolve({ farm: { name: "演示农场" } });
  await tick();
  await f.refresh.refresh(null);
  assert.equal(f.state().scope, null);
  assert.ok(
    Object.values(f.state().resources).every(
      (state) => state.data === null && state.readAt === null,
    ),
  );
  f.finish();
  await initial;
  assert.equal(f.state().resources.workspace.data, null);
});

test("permission errors do not retain an unauthorized snapshot", async () => {
  const f = fixture(),
    initial = f.refresh.refresh("farm-a");
  f.finish();
  await initial;
  const next = f.refresh.refresh("farm-a");
  f.calls[6].reject(Object.assign(new Error("无权读取"), { status: 403 }));
  f.finish(7);
  await next;
  assert.equal(f.state().resources.workspace.data, null);
  assert.equal(f.state().resources.workspace.readAt, null);
});

test("unmount cancels network work and prevents any future publish or request", async () => {
  const f = fixture(),
    initial = f.refresh.refresh("farm-a"),
    before = f.updates.length;
  f.refresh.dispose();
  f.finish();
  await initial;
  await f.refresh.refresh("farm-b");
  assert.equal(f.updates.length, before);
  assert.equal(f.calls.length, 6);
  assert.ok(f.calls.every((call) => call.signal.aborted));
});

test("initial freshness never invents a last-read time", () => {
  assert.deepEqual(screenFreshness({}), {
    readAt: null,
    loaded: 0,
    total: 6,
    failed: 0,
  });
});

test("live refresh reads device feedback and map jobs without repeating report requests", async () => {
  const f = fixture(),
    first = f.refresh.refresh("farm-a");
  f.finish();
  await first;
  const report = f.state().resources.briefing;
  const live = f.refresh.refresh("farm-a", { liveOnly: true });
  assert.equal(f.calls.length, 8);
  assert.ok(f.calls[6].path.includes("workspace"));
  assert.ok(f.calls[7].path.includes("field-map"));
  f.finish(6);
  await live;
  assert.deepEqual(f.state().resources.briefing, report);
});
