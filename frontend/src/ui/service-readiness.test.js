import assert from "node:assert/strict";
import test from "node:test";
import { readHealth, watchReadiness } from "./service-readiness.js";

const response = (status, body) => ({ ok: status >= 200 && status < 300, json: async () => body });

test("readHealth distinguishes ready, starting and unreachable services and reads the demo flag", async () => {
  assert.deepEqual(await readHealth(async () => response(200, { ready: true, demo: true })), { state: "ready", demo: true });
  assert.deepEqual(await readHealth(async () => response(503, { ready: false, demo: true })), { state: "starting", demo: true });
  assert.deepEqual(await readHealth(async () => response(200, { ready: true })), { state: "ready", demo: false });
  assert.deepEqual(await readHealth(async () => { throw new TypeError("network"); }), { state: "unreachable", demo: false });
  assert.deepEqual(await readHealth(async () => ({ ok: false, json: async () => { throw new SyntaxError("html"); } })), { state: "starting", demo: false });
});

test("watchReadiness retries until the service is ready and then stops polling", async () => {
  const replies = [{ state: "unreachable", demo: false }, { state: "starting", demo: true }, { state: "ready", demo: true }];
  const seen = [], timers = [];
  watchReadiness(status => seen.push(status.state), {
    read: async () => replies.shift(),
    setTimer: (fn, ms) => { timers.push({ fn, ms }); return timers.length; },
    clearTimer: () => {},
  });
  await new Promise(resolve => setImmediate(resolve));
  while (timers.length) { await timers.shift().fn(); }
  assert.deepEqual(seen, ["unreachable", "starting", "ready"]);
  assert.equal(replies.length, 0);
});

test("stopping the watcher cancels the pending retry and ignores a late reply", async () => {
  let release, cleared = 0;
  const seen = [];
  const stop = watchReadiness(status => seen.push(status.state), {
    read: () => new Promise(resolve => { release = resolve; }),
    setTimer: () => 1,
    clearTimer: () => { cleared++; },
  });
  stop();
  release({ state: "starting", demo: false });
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(seen, []);
  assert.equal(cleared, 0);
});
