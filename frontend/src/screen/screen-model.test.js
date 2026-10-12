import test from "node:test";
import assert from "node:assert/strict";
import {
  canReadFarm,
  deviceCounts,
  hasDeviceAlert,
  mapGeometry,
  metricSummary,
  moisturePoints,
  numberText,
  pendingTasks,
  productionDistribution,
  productionTotal,
} from "./screen-model.js";

test("missing measurements stay distinct from valid zero", () => {
  assert.equal(numberText(null), "—");
  assert.equal(numberText(undefined), "—");
  assert.equal(numberText(""), "—");
  assert.equal(numberText(0), "0");
  assert.equal(productionTotal({ productionTrend: [] }), null);
  assert.equal(productionTotal({ productionTrend: [{ value: 0 }] }), 0);
});

test("harvest distribution separates unavailable data, no ledger, and a recorded zero", () => {
  assert.equal(productionDistribution(null).state, "UNAVAILABLE");
  assert.equal(
    productionDistribution({ productionTrend: [] }).state,
    "UNAVAILABLE",
  );
  const productionByPlot = [{ name: "一号田", value: 0 }];
  const empty = productionDistribution({
    productionTrend: [],
    productionByPlot,
  });
  assert.equal(empty.state, "EMPTY");
  assert.equal(empty.rows.length, 0);
  assert.equal(empty.recordedDays, 0);
  const recorded = productionDistribution({
    productionTrend: [{ date: "2026-10-12", value: 0 }],
    productionByPlot,
  });
  assert.equal(recorded.state, "AVAILABLE");
  assert.equal(recorded.rows[0].value, 0);
  assert.equal(recorded.recordedDays, 1);
});

test("harvest ranking preserves all quantities when grouping more than four plots", () => {
  const productionByPlot = [7, 80, 15, 0, 5, 50].map((value, index) => ({
    name: `田块${index + 1}`,
    value,
  }));
  const result = productionDistribution({
    productionTrend: [{ date: "2026-10-12", value: 157 }],
    productionByPlot,
  });
  assert.deepEqual(
    result.rows.map((row) => row.value),
    [80, 50, 15, 12],
  );
  assert.equal(result.rows[3].name, "其他 3 块");
  assert.equal(result.rows[3].plots, 3);
  assert.equal(result.groupedPlots, 3);
  assert.equal(
    result.rows.reduce((sum, row) => sum + row.value, 0),
    157,
  );
  assert.deepEqual(
    productionByPlot.map((row) => row.value),
    [7, 80, 15, 0, 5, 50],
  );
});

test("four plots remain individually visible and date groups are counted as dates", () => {
  const result = productionDistribution({
    productionTrend: [
      { date: "2026-10-10", value: 30 },
      { date: "2026-10-12", value: 0 },
    ],
    productionByPlot: [
      { name: "甲", value: 0 },
      { name: "乙", value: 10 },
      { name: "丙", value: "20" },
      { name: "丁", value: 0 },
    ],
  });
  assert.equal(result.recordedDays, 2);
  assert.equal(result.groupedPlots, 0);
  assert.deepEqual(
    result.rows.map((row) => row.name),
    ["丙", "乙", "甲", "丁"],
  );
});

test("missing plot quantities are disclosed rather than fabricated as zero", () => {
  const productionTrend = [{ date: "2026-10-12", value: 0 }];
  const result = productionDistribution({
    productionTrend,
    productionByPlot: [
      { name: "零产量登记", value: 0 },
      { name: "未知", value: null },
      { name: "无读数" },
    ],
  });
  assert.equal(result.state, "AVAILABLE");
  assert.equal(result.missingPlots, 2);
  assert.equal(result.rows.length, 1);
  assert.equal(result.rows[0].value, 0);
  assert.equal(
    productionDistribution({
      productionTrend,
      productionByPlot: [{ name: "未知", value: null }],
    }).state,
    "UNAVAILABLE",
  );
});

test("only active tenant farm roles may read the screen", () => {
  const farms = [{ id: "a" }, { id: "old", archived: true }];
  assert.equal(canReadFarm("VIEWER", "a", farms), true);
  for (const role of ["PLATFORM_ADMIN", "", undefined])
    assert.equal(canReadFarm(role, "a", farms), false);
  assert.equal(canReadFarm("ADMIN", "other", farms), false);
  assert.equal(canReadFarm("ADMIN", "old", farms), false);
});

test("expired and server-rejected readings never enter the fresh average", () => {
  const now = Date.parse("2026-10-12T08:00:00Z");
  const reading = {
    metric: "TEMPERATURE",
    fresh: true,
    freshnessSeconds: 180,
    time: "2026-10-12T07:59:00Z",
    lastReceivedAt: "2026-10-12T07:59:00Z",
  };
  const rows = [
    { ...reading, value: 0 },
    { ...reading, value: 20 },
    { ...reading, value: 100, fresh: false },
    { ...reading, value: 100, time: "2026-10-12T07:50:00Z" },
    { ...reading, value: 100, time: "2026-10-12T09:00:00Z" },
  ];
  assert.equal(metricSummary(rows, "TEMPERATURE", now).value, 10);
  assert.equal(metricSummary(rows, "TEMPERATURE", now).fresh, 2);
  assert.equal(metricSummary(rows, "TEMPERATURE", now + 300000).value, null);
  assert.equal(metricSummary(rows, "HUMIDITY", now).state, "NO_DATA");
});

test("a newer sample cannot hide an expired receipt between refreshes", () => {
  const now = Date.parse("2026-10-12T08:00:00Z");
  assert.equal(
    metricSummary(
      [
        {
          metric: "TEMPERATURE",
          value: 20,
          fresh: true,
          freshnessSeconds: 180,
          time: "2026-10-12T07:59:00Z",
          lastReceivedAt: "2026-10-12T07:56:00Z",
        },
      ],
      "TEMPERATURE",
      now,
    ).value,
    null,
  );
});

test("soil history preserves every empty slice and accepts zero", () => {
  assert.deepEqual(
    moisturePoints({
      slices: [
        { start: "a", moisture: 0 },
        { start: "b", moisture: null },
        { start: "c" },
        { start: "d", moisture: 20 },
      ],
    }),
    [
      ["a", 0],
      ["b", null],
      ["c", null],
      ["d", 20],
    ],
  );
});

test("device connectivity ages without treating manual or disabled devices as offline", () => {
  const now = Date.parse("2026-10-12T08:00:00Z");
  const device = {
    lifecycle: "ACTIVE",
    protocol: "HTTP_PUSH",
    connectionState: "FRESH",
    lastReceivedAt: "2026-10-12T07:59:00Z",
    intervalSeconds: 60,
  };
  assert.deepEqual(
    deviceCounts(
      [
        device,
        { ...device, protocol: "MANUAL" },
        { ...device, lifecycle: "DISABLED" },
        { ...device, lastReceivedAt: "2026-10-12T07:00:00Z" },
      ],
      now,
    ),
    { total: 4, monitored: 2, fresh: 1, late: 1 },
  );
  assert.equal(deviceCounts(null).fresh, null);
});

test("map uses stored geometry and never invents coordinates", () => {
  const data = {
    plots: [
      {
        id: "drawn",
        boundary: [
          [0, 0],
          [0, 20],
          [20, 20],
        ],
      },
      { id: "missing" },
    ],
    devices: [
      { id: "local", planX: 0, planY: 0 },
      { id: "missing" },
      { id: "gps", locationMode: "WGS84", latitude: 31, longitude: 121 },
    ],
  };
  const map = mapGeometry(data, null);
  assert.equal(map.plots.length, 1);
  assert.equal(map.devices.length, 1);
  assert.equal(map.devices[0].x, 0);
  assert.equal(map.missingPlots, 1);
  assert.equal(map.missingDevices, 2);
});

test("pending work is ordered by due date and excludes terminal statuses", () => {
  assert.deepEqual(
    pendingTasks([
      { id: "late", dueDate: "2026-10-10", status: "PENDING" },
      { id: "early", dueDate: "2026-10-09", status: "RUNNING" },
      { id: "done", status: "COMPLETED" },
    ]).map((task) => task.id),
    ["early", "late"],
  );
});

test("a capped alert list cannot clear an older device alert", () => {
  const device = { id: "old-device", alertCount: 1 };
  assert.equal(hasDeviceAlert(device, []), false);
  assert.equal(
    hasDeviceAlert({ id: "new-device", alertCount: 0 }, [
      { deviceId: "new-device" },
    ]),
    true,
  );
  assert.equal(
    hasDeviceAlert(
      device,
      Array.from({ length: 200 }, () => ({ deviceId: "other-device" })),
    ),
    true,
  );
});
