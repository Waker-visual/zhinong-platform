import test from "node:test";
import assert from "node:assert/strict";
import { buildSceneModel } from "./scene-model.js";

const now = Date.parse("2026-10-13T08:00:00Z");
const time = "2026-10-13T07:59:00Z";
const plot = (id, extra = {}) => ({
  id,
  name: `田块 ${id}`,
  crop: "水稻",
  ...extra,
});
const device = (id, plotId, extra = {}) => ({
  id,
  plotId,
  lifecycle: "ACTIVE",
  protocol: "HTTP_PUSH",
  connectionState: "FRESH",
  lastReceivedAt: time,
  intervalSeconds: 60,
  ...extra,
});
const sensor = (deviceId, plotId, value, extra = {}) => ({
  deviceId,
  plotId,
  metric: "SOIL_MOISTURE",
  value,
  fresh: true,
  freshnessSeconds: 180,
  time,
  lastReceivedAt: time,
  ...extra,
});
const workspace = (extra = {}) => ({
  farm: { id: "farm-a" },
  plots: [plot("a")],
  devices: [device("d-a", "a")],
  alerts: [],
  ...extra,
});

test("missing workspace stays loading and cannot expose a lingering analysis snapshot", () => {
  const result = buildSceneModel(
    null,
    { sensors: [sensor("d-a", "a", 42)] },
    now,
  );
  assert.equal(result.status, "loading");
  assert.equal(result.totalPlots, null);
  assert.equal(result.alertCount, null);
  assert.deepEqual(result.plots, []);
  assert.equal(result.devices.total, null);
  assert.ok(
    result.metrics.every(
      (metric) => metric.value === null && metric.state === "NO_DATA",
    ),
  );
  assert.deepEqual(
    result.hotspots.map((item) => item.id),
    ["weather", "water", "yard"],
  );
  assert.ok(result.hotspots.every((item) => !("available" in item)));
  assert.equal(buildSceneModel({}, null, now).status, "loading");
});

test("a known empty farm has no invented plots and records known zero counts", () => {
  const result = buildSceneModel(
    workspace({ plots: [], devices: [] }),
    null,
    now,
  );
  assert.equal(result.status, "empty");
  assert.equal(result.totalPlots, 0);
  assert.equal(result.omittedPlots, 0);
  assert.equal(result.alertCount, 0);
  assert.equal(result.devices.total, 0);
  assert.deepEqual(result.plots, []);
  assert.equal(result.hotspots.length, 3);
});

test("registered zero area and zero readings remain distinct from missing values", () => {
  const result = buildSceneModel(
    workspace({ plots: [plot("a", { areaMu: 0 }), plot("b", { crop: " " })] }),
    { sensors: [sensor("d-a", "a", 0)] },
    now,
  );
  assert.equal(result.plots[0].areaMu, 0);
  assert.equal(result.plots[0].moisture.value, 0);
  assert.equal(result.plots[0].moisture.state, "FRESH");
  assert.equal(result.plots[1].areaMu, null);
  assert.equal(result.plots[1].moisture.value, null);
  assert.equal(result.plots[1].crop, "未登记作物");
  assert.equal(
    result.metrics.find((metric) => metric.metric === "SOIL_MOISTURE").value,
    0,
  );
  assert.equal(
    result.metrics.find((metric) => metric.metric === "TEMPERATURE").value,
    null,
  );
});

test("readings and connection state age locally without another poll", () => {
  const data = workspace();
  const analysis = { sensors: [sensor("d-a", "a", 36)] };
  const initial = buildSceneModel(data, analysis, now);
  const expired = buildSceneModel(data, analysis, now + 180000);
  assert.equal(initial.plots[0].moisture.value, 36);
  assert.equal(expired.plots[0].moisture.value, null);
  assert.equal(expired.plots[0].moisture.state, "STALE");
  assert.equal(expired.plots[0].moisture.stale, 1);
  assert.equal(expired.devices.fresh, 0);
  assert.equal(expired.devices.late, 1);
  assert.equal(initial.layoutKey, expired.layoutKey);
});

test("samples must match both a workspace device and its current plot assignment", () => {
  const result = buildSceneModel(
    workspace({
      plots: [plot("a"), plot("b")],
      devices: [
        device("d-a", "a"),
        device("d-b", "b"),
        device("d-gone", "gone"),
      ],
    }),
    {
      sensors: [
        sensor("d-a", "a", 10),
        sensor("d-b", "b", 30),
        sensor("d-a", "b", 900),
        sensor("outside-device", "a", 900),
        sensor("d-a", "gone", 900),
        sensor("d-gone", "gone", 900),
        sensor("d-a", null, 900),
      ],
    },
    now,
  );
  assert.equal(
    result.metrics.find((metric) => metric.metric === "SOIL_MOISTURE").value,
    20,
  );
  assert.deepEqual(
    result.plots.map((item) => item.moisture.value),
    [10, 30],
  );
  assert.deepEqual(
    result.plots.map((item) => item.deviceCount),
    [1, 1],
  );
});

test("farm-level weather stations contribute global metrics but never plot readings", () => {
  const result = buildSceneModel(
    workspace({ devices: [device("d-a", "a"), device("weather", null)] }),
    {
      sensors: [
        sensor("weather", null, 18, { metric: "TEMPERATURE" }),
        sensor("weather", null, 62, { metric: "HUMIDITY" }),
        sensor("weather", null, 70),
        sensor("weather", "a", 999),
      ],
    },
    now,
  );
  assert.deepEqual(
    result.metrics.map((metric) => metric.value),
    [18, 62, 70],
  );
  assert.equal(result.plots[0].moisture.value, null);
  assert.equal(result.plots[0].moisture.observed, 0);
  assert.equal(result.plots[0].deviceCount, 1);
});

test("resolved and unknown-device alerts are excluded; multiple open records are counted", () => {
  const result = buildSceneModel(
    workspace({
      plots: [plot("a"), plot("b")],
      devices: [
        device("d-a", "a"),
        device("d-b", "b"),
        device("weather", null),
      ],
      alerts: [
        { deviceId: "d-a", status: "OPEN" },
        { deviceId: "d-a", status: "ACKNOWLEDGED" },
        { deviceId: "d-a", status: "RESOLVED" },
        { deviceId: "d-b", status: "RESOLVED" },
        { deviceId: "unknown", status: "OPEN" },
        { deviceId: "weather", status: "OPEN" },
      ],
    }),
    null,
    now,
  );
  assert.equal(result.alertCount, 3);
  assert.deepEqual(
    result.plots.map((item) => item.alertCount),
    [2, 0],
  );
});

test("only valid distinct plots are retained, with explicit visual omission counts", () => {
  const plots = Array.from({ length: 15 }, (_, index) => plot(`p-${index}`));
  const result = buildSceneModel(
    workspace({ plots: [null, {}, plot(""), plot(" "), plots[0], ...plots] }),
    null,
    now,
  );
  assert.equal(result.totalPlots, 15);
  assert.equal(result.plots.length, 12);
  assert.equal(result.omittedPlots, 3);
  assert.deepEqual(
    result.plots.map((item) => item.plotId),
    plots.slice(0, 12).map((item) => item.id),
  );
  assert.equal(
    result.hotspots.filter((item) => item.kind === "plot").length,
    12,
  );
});

test("capped alerts preserve known counts and flag devices omitted from the detail page", () => {
  for (const count of [200, 205]) {
    const result = buildSceneModel(
      workspace({
        plots: [plot("a"), plot("b"), plot("c")],
        devices: [
          device("d-a", "a", { alertCount: count }),
          device("d-b", "b", { alertCount: 3 }),
          device("d-c", "c", {
            lifecycle: "MAINTENANCE",
            connectionState: "STALE",
            alertCount: 0,
          }),
        ],
        alerts: Array.from({ length: count }, (_, index) => ({
          id: `alert-${index}`,
          deviceId: "d-a",
          status: "OPEN",
        })),
      }),
      null,
      now,
    );
    assert.equal(result.alertCount, count);
    assert.equal(result.alertLimitReached, true);
    assert.equal(result.hasAlerts, true);
    assert.deepEqual(
      result.plots.map((item) => item.alertCount),
      [count, 0, 0],
    );
    assert.deepEqual(
      result.plots.map((item) => item.hasAlerts),
      [true, true, false],
    );
    assert.ok(result.plots.every((item) => item.alertLimitReached));
  }
});

test("filtering foreign or resolved rows does not erase the capped-response warning", () => {
  const result = buildSceneModel(
    workspace({
      plots: [plot("a"), plot("b"), plot("c")],
      devices: [
        device("d-a", "a"),
        device("d-b", "b", { alertCount: 2 }),
        device("foreign", "c", { farmId: "farm-b", alertCount: 99 }),
      ],
      alerts: [
        ...Array.from({ length: 197 }, (_, index) => ({
          id: `alert-${index}`,
          deviceId: "d-a",
          status: "OPEN",
        })),
        { deviceId: "d-a", status: "OPEN", farmId: "farm-b" },
        { deviceId: "foreign", status: "OPEN" },
        { deviceId: "d-b", status: "RESOLVED" },
      ],
    }),
    null,
    now,
  );
  assert.equal(result.alertCount, 197);
  assert.equal(result.alertLimitReached, true);
  assert.deepEqual(
    result.plots.map((item) => item.alertCount),
    [197, 0, 0],
  );
  assert.deepEqual(
    result.plots.map((item) => item.hasAlerts),
    [true, true, false],
  );
  assert.equal(result.plots[2].deviceCount, 0);
});

test("an uncapped snapshot excludes resolved alerts and ignores stale badges or lifecycle status", () => {
  const result = buildSceneModel(
    workspace({
      devices: [
        device("d-a", "a", { lifecycle: "MAINTENANCE", alertCount: 7 }),
      ],
      alerts: [{ deviceId: "d-a", status: "RESOLVED" }],
    }),
    null,
    now,
  );
  assert.equal(result.alertCount, 0);
  assert.equal(result.alertLimitReached, false);
  assert.equal(result.hasAlerts, false);
  assert.equal(result.plots[0].hasAlerts, false);
  assert.equal(result.plots[0].alertLimitReached, false);
});

test("every supported layout fits its schematic bounds without overlapping plots", () => {
  for (let count = 1; count <= 12; count++) {
    const data = workspace({
      plots: Array.from({ length: count }, (_, index) => plot(`p-${index}`)),
    });
    const result = buildSceneModel(data, null, now);
    assert.deepEqual(result, buildSceneModel(data, null, now));
    for (const item of result.plots) {
      const [x, y, z] = item.position;
      const [width, depth] = item.size;
      assert.equal(y, 0);
      assert.ok(width > 0 && depth > 0);
      assert.ok(x - width / 2 >= -27 && x + width / 2 <= 27);
      assert.ok(z - depth / 2 >= -18 && z + depth / 2 <= 18);
      for (const other of result.plots.filter(
        (candidate) => candidate !== item,
      ))
        assert.ok(
          Math.abs(x - other.position[0]) >= (width + other.size[0]) / 2 ||
            Math.abs(z - other.position[2]) >= (depth + other.size[1]) / 2,
        );
    }
  }
});

test("layout identity ignores live readings but changes with farm, crop, or plot order", () => {
  const data = workspace({ plots: [plot("a"), plot("b")] });
  const first = buildSceneModel(
    data,
    { sensors: [sensor("d-a", "a", 10)] },
    now,
  );
  const next = buildSceneModel(
    data,
    { sensors: [sensor("d-a", "a", 99)] },
    now + 1000,
  );
  assert.equal(first.layoutKey, next.layoutKey);
  assert.notEqual(
    first.layoutKey,
    buildSceneModel({ ...data, farm: { id: "farm-b" } }, null, now).layoutKey,
  );
  assert.notEqual(
    first.layoutKey,
    buildSceneModel({ ...data, plots: [...data.plots].reverse() }, null, now)
      .layoutKey,
  );
  assert.notEqual(
    first.layoutKey,
    buildSceneModel(
      { ...data, plots: [plot("a", { crop: "小麦" }), plot("b")] },
      null,
      now,
    ).layoutKey,
  );
});

test("explicitly foreign plots, devices, and alerts cannot enter scene summaries", () => {
  const result = buildSceneModel(
    workspace({
      plots: [plot("a"), plot("foreign", { farmId: "farm-b" })],
      devices: [
        device("d-a", "a"),
        device("foreign", "a", { farmId: "farm-b" }),
      ],
      alerts: [{ deviceId: "d-a", status: "OPEN", farmId: "farm-b" }],
    }),
    { sensors: [sensor("foreign", "a", 100), sensor("d-a", "a", 20)] },
    now,
  );
  assert.equal(result.totalPlots, 1);
  assert.deepEqual(result.devices, {
    total: 1,
    monitored: 1,
    fresh: 1,
    late: 0,
  });
  assert.equal(result.plots[0].deviceCount, 1);
  assert.equal(result.plots[0].moisture.value, 20);
  assert.equal(result.alertCount, 0);
});

test("building and editing a returned model never mutates input or later models", () => {
  const data = workspace();
  const analysis = { sensors: [sensor("d-a", "a", 20)] };
  const before = structuredClone({ data, analysis });
  const freeze = (value) => {
    if (value && typeof value === "object") {
      Object.values(value).forEach(freeze);
      Object.freeze(value);
    }
    return value;
  };
  freeze(data);
  freeze(analysis);
  const result = buildSceneModel(data, analysis, now);
  result.plots[0].position[0] = 999;
  result.hotspots.find((item) => item.id === "weather").position[0] = 999;
  result.metrics[0].label = "changed";
  assert.deepEqual({ data, analysis }, before);
  const next = buildSceneModel(data, analysis, now);
  assert.equal(
    next.hotspots.find((item) => item.id === "weather").position[0],
    30,
  );
  assert.equal(next.metrics[0].label, "空气温度");
  assert.notEqual(next.plots[0].position[0], 999);
});
