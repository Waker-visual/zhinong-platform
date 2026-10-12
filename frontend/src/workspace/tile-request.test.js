import test from "node:test";
import assert from "node:assert/strict";
import { createTileRequestQueue } from "./tile-request.js";

const tick = () => new Promise((resolve) => setImmediate(resolve));
function deferred() {
  let resolve;
  const promise = new Promise((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

test("tile work is bounded until image body finishes, then pending tiles progress", async () => {
  const queue = createTileRequestQueue({ concurrency: 1, capacity: 2 });
  const first = deferred();
  const controller = new AbortController();
  let started = 0;
  const a = queue(() => {
    started++;
    return first.promise;
  }, controller.signal);
  const b = queue(() => {
    started++;
    return "second-image";
  }, controller.signal);
  await tick();
  assert.equal(started, 1);
  first.resolve("first-image");
  assert.equal(await a, "first-image");
  assert.equal(await b, "second-image");
  assert.equal(started, 2);
});

test("removed maps cancel queued tiles without making a network request", async () => {
  const queue = createTileRequestQueue({ concurrency: 1 });
  const first = deferred();
  const controller = new AbortController();
  const a = queue(() => first.promise, new AbortController().signal);
  let requested = false;
  const b = queue(() => {
    requested = true;
  }, controller.signal);
  const cancelled = assert.rejects(b, { name: "AbortError" });
  controller.abort();
  await cancelled;
  first.resolve();
  await a;
  await tick();
  assert.equal(requested, false);
});

test("active cancellation rejects promptly but holds its slot until network cleanup completes", async () => {
  const queue = createTileRequestQueue({ concurrency: 1 });
  const first = deferred();
  const controller = new AbortController();
  const a = queue(() => first.promise, controller.signal);
  const cancelled = assert.rejects(a, { name: "AbortError" });
  await tick();
  controller.abort();
  await cancelled;
  let secondStarted = false;
  const b = queue(() => {
    secondStarted = true;
  }, new AbortController().signal);
  await tick();
  assert.equal(secondStarted, false);
  first.resolve();
  await b;
  assert.equal(secondStarted, true);
});

test("queue overload fails clearly and a failed request releases capacity", async () => {
  const queue = createTileRequestQueue({ concurrency: 1, capacity: 1 });
  const first = deferred();
  const signal = new AbortController().signal;
  const a = queue(async () => {
    await first.promise;
    throw new Error("offline");
  }, signal);
  const offline = assert.rejects(a, /offline/);
  const b = queue(() => "recovered", signal);
  await assert.rejects(
    queue(() => assert.fail("overflow must not run"), signal),
    /请求较多/,
  );
  first.resolve();
  await offline;
  assert.equal(await b, "recovered");
});

test("already cancelled work never starts", async () => {
  const controller = new AbortController();
  controller.abort();
  await assert.rejects(
    createTileRequestQueue()(
      () => assert.fail("aborted work started"),
      controller.signal,
    ),
    { name: "AbortError" },
  );
});

test("default queue reserves connection capacity by running only four tile requests", async () => {
  const queue = createTileRequestQueue();
  const gate = deferred();
  const signal = new AbortController().signal;
  let started = 0;
  const tiles = Array.from({ length: 6 }, () =>
    queue(async () => {
      started++;
      await gate.promise;
    }, signal),
  );
  await tick();
  assert.equal(started, 4);
  gate.resolve();
  await Promise.all(tiles);
  assert.equal(started, 6);
});
