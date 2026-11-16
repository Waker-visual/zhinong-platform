import { test } from "node:test";
import assert from "node:assert/strict";
import { loadLandscapeData } from "./daily-landscape-data.js";

test("map configuration failure preserves successful workspace and parcel data", async () => {
  const workspace = { plots: [{ id: "plot-a" }], devices: [] };
  const spatial = { parcels: [{ id: "parcel-a", plotId: "plot-a" }] };
  const failure = Error("map temporarily unavailable");
  const result = await loadLandscapeData(async (path) => {
    if (path.endsWith("/map-config")) throw failure;
    return path.endsWith("/field-map") ? spatial : workspace;
  }, "demo-farm");
  assert.equal(result.workspace.value, workspace);
  assert.equal(result.spatial.value, spatial);
  assert.equal(result.geo.status, "rejected");
  assert.equal(result.geo.reason, failure);
});

test("each request carries its farm scope and shared cancellation signal", async () => {
  const controller = new AbortController(),
    pending = [];
  const result = loadLandscapeData(
    (path, method, body, options) => {
      assert.ok(path.startsWith("/farms/demo%2Fone/"));
      assert.equal(method, "GET");
      assert.equal(body, undefined);
      assert.equal(options.signal, controller.signal);
      return new Promise((resolve, reject) => {
        pending.push(path);
        options.signal.addEventListener(
          "abort",
          () => reject(new DOMException("cancelled", "AbortError")),
          { once: true },
        );
      });
    },
    "demo/one",
    controller.signal,
  );
  assert.equal(pending.length, 3);
  controller.abort();
  for (const value of Object.values(await result)) {
    assert.equal(value.status, "rejected");
    assert.equal(value.reason.name, "AbortError");
  }
});

test("workspace failure is reported rather than replaced with empty or zero data", async () => {
  const result = await loadLandscapeData(async (path) => {
    if (path.includes("workspace")) throw Error("unavailable");
    return {};
  }, "demo");
  assert.equal(result.workspace.status, "rejected");
  assert.equal(result.workspace.value, undefined);
  assert.equal(result.geo.status, "fulfilled");
});
