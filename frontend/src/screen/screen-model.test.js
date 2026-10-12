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
