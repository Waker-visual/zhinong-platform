import test from "node:test";
import assert from "node:assert/strict";
import { agronomyWork } from "./agronomy-work.js";

test("unavailable business resources remain unknown instead of becoming zero", () => {
  const data = agronomyWork();
  assert.deepEqual(data.tasks, {
    total: null,
    pending: null,
    running: null,
    completed: null,
    cancelled: null,
    open: null,
    overdue: null,
    issues: null,
  });
  assert.deepEqual(data.harvest, {
    yieldKg: null,
    records: null,
    fromDate: null,
    toDate: null,
    latest: null,
    open: null,
  });
  assert.equal(data.plantings.available, false);
  assert.equal(data.plantings.total, null);
  assert.equal(data.plantings.areaMu, null);
  assert.deepEqual(data.plantings.items, []);
  assert.ok(
    data.activities.every(
      (item) => item.open === null && item.blocked === null,
    ),
  );
});

test("complete grouped counts do not infer totals from the truncated task sample", () => {
  const data = agronomyWork(
    {
      tasks: [{ status: "COMPLETED" }],
      tasksTruncated: true,
      analytics: {
        taskStatus: [
          { code: "PENDING", value: 110 },
          { code: "RUNNING", value: "7" },
          { code: "COMPLETED", value: 250 },
          { code: "CANCELLED", value: 2 },
        ],
        summary: { openTasks: 117 },
      },
    },
    { summary: { openTasks: 118, overdueTasks: 10, openIssues: 35 } },
  );
  assert.deepEqual(data.tasks, {
    total: 369,
    pending: 110,
    running: 7,
    completed: 250,
    cancelled: 2,
    open: 118,
    overdue: 10,
    issues: 35,
  });
  assert.equal(
    agronomyWork({ tasks: [{ status: "PENDING" }] }).tasks.pending,
    null,
  );
});

test("empty complete aggregates and real zero values are preserved", () => {
  const data = agronomyWork(
    {
      analytics: { taskStatus: [], summary: { openTasks: 4 } },
    },
    {
      summary: { openTasks: 0, overdueTasks: 0, openIssues: 0 },
      pipeline: {
        harvest: { yieldKg: 0, records: 1, open: 0, latest: "2026-10-20" },
        irrigation: { open: 0, blocked: 0, lastCompleted: "" },
      },
    },
    { crops: [] },
  );
  assert.equal(data.tasks.total, 0);
  assert.equal(data.tasks.pending, 0);
  assert.equal(data.tasks.open, 0);
  assert.equal(data.tasks.overdue, 0);
  assert.equal(data.tasks.issues, 0);
  assert.equal(data.harvest.yieldKg, 0);
  assert.equal(data.harvest.records, 1);
  assert.equal(data.harvest.open, 0);
  assert.equal(data.activities[0].open, 0);
  assert.equal(data.activities[0].lastCompleted, null);
  assert.deepEqual(data.plantings, {
    available: true,
    total: 0,
    areaMu: 0,
    items: [],
  });
});

test("missing status groups are zero only with a valid complete grouped array", () => {
  const workspace = {
    analytics: {
      taskStatus: [
        { code: "PENDING", value: 2 },
        { code: "PENDING", value: 3 },
        { code: "COMPLETED", value: 5 },
      ],
      summary: { openTasks: 5 },
    },
  };
  const data = agronomyWork(workspace);
  assert.equal(data.tasks.total, 10);
  assert.equal(data.tasks.pending, 5);
  assert.equal(data.tasks.running, 0);
  assert.equal(data.tasks.cancelled, 0);
  assert.equal(data.tasks.open, 5);
  assert.equal(data.tasks.overdue, null);
  assert.equal(data.tasks.issues, null);
  const invalid = agronomyWork({
    analytics: {
      taskStatus: [{ code: "PENDING", value: null }],
    },
  });
  assert.equal(invalid.tasks.pending, null);
  assert.equal(invalid.tasks.total, null);
  assert.equal(invalid.tasks.running, null);
});

test("harvest keeps its supplied inclusive dates and blocked work remains a subset", () => {
  const data = agronomyWork(null, {
    summary: { blockedTasks: 99 },
    pipeline: {
      harvest: {
        yieldKg: "1234.5",
        records: 6,
        fromDate: "2026-09-21",
        toDate: "2026-10-20",
        latest: "2026-10-19",
        open: 2,
      },
      irrigation: {
        open: 5,
        blocked: 2,
        lastCompleted: "2026-10-19T12:00:00+08:00",
      },
      protection: {
        open: 3,
        blocked: 1,
        lastCompleted: "2026-10-18T12:00:00+08:00",
      },
    },
  });
  assert.deepEqual(data.harvest, {
    yieldKg: 1234.5,
    records: 6,
    fromDate: "2026-09-21",
    toDate: "2026-10-20",
    latest: "2026-10-19",
    open: 2,
  });
  assert.deepEqual(
    data.activities.map((item) => [item.key, item.open, item.blocked]),
    [
      ["irrigation", 5, 2],
      ["protection", 3, 1],
    ],
  );
  assert.equal(data.activities[0].lastCompleted, "2026-10-19T12:00:00+08:00");
});

test("all server-filtered active plans are retained without equating area to farm land", () => {
  const crops = [
    {
      plotId: "a",
      crop: "水稻",
      areaMu: 3,
      startDate: "2026-09-01",
      endDate: "2026-10-20",
      daysAfterPlanting: 49,
    },
    {
      plotId: "b",
      crop: "小麦",
      areaMu: "4.5",
      startDate: "2026-10-20",
      endDate: "2027-04-01",
      daysAfterPlanting: 0,
    },
    { plotId: "c", crop: "青菜", areaMu: 0 },
    { plotId: "d", crop: "玉米", areaMu: 2 },
  ];
  const workspace = { farm: { areaMu: 90 } };
  const analysis = { generatedAt: "2026-10-20T15:00:00+08:00", crops };
  const before = structuredClone({ workspace, analysis });
  const data = agronomyWork(workspace, null, analysis);
  assert.equal(data.plantings.available, true);
  assert.equal(data.plantings.total, 4);
  assert.equal(data.plantings.areaMu, 9.5);
  assert.equal(data.plantings.items.length, 4);
  assert.deepEqual(data.plantings.items, crops);
  assert.deepEqual({ workspace, analysis }, before);
  assert.notEqual(data.plantings.items, crops);
});

test("partial invalid business numbers are not coerced to a reassuring zero", () => {
  const data = agronomyWork(
    { analytics: { summary: { openTasks: 7 } } },
    {
      summary: { openTasks: " ", overdueTasks: -1, openIssues: false },
      pipeline: { harvest: { yieldKg: -5, records: "", open: 0.5 } },
    },
    {
      crops: [
        { crop: "水稻", areaMu: 3 },
        { crop: "小麦", areaMu: null },
      ],
    },
  );
  assert.equal(data.tasks.open, 7);
  assert.equal(data.tasks.overdue, null);
  assert.equal(data.tasks.issues, null);
  assert.equal(data.harvest.yieldKg, null);
  assert.equal(data.harvest.records, null);
  assert.equal(data.harvest.open, null);
  assert.equal(data.plantings.total, 2);
  assert.equal(data.plantings.areaMu, null);
});
