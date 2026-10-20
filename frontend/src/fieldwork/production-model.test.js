import test from "node:test";
import assert from "node:assert/strict";
import {
  productionSubmission,
  eligibleHarvestTasks,
  eligiblePlantings,
} from "./production-model.js";

test("生产登记的超时重试复用请求标识，用户改变草稿后使用新标识", () => {
  let next = 0;
  const id = () => `request-${++next}`;
  const body = { plotId: "p1", yieldKg: 120, recordDate: "2026-10-16" };
  const first = productionSubmission(body, null, id);
  assert.equal(productionSubmission({ ...body }, first, id), first);
  const changed = productionSubmission({ ...body, yieldKg: 125 }, first, id);
  assert.notEqual(changed.requestId, first.requestId);
  assert.equal(next, 2);
});

test("批次选项仅包含同地块且收获日期位于周期内的记录", () => {
  const plantings = [
    {
      id: "current",
      plotId: "p1",
      startDate: "2026-10-01",
      endDate: "2026-10-16",
    },
    { id: "old", plotId: "p1", startDate: "2026-01-01", endDate: "2026-05-31" },
    {
      id: "other",
      plotId: "p2",
      startDate: "2026-10-01",
      endDate: "2026-10-31",
    },
  ];
  assert.deepEqual(
    eligiblePlantings(plantings, "p1", "2026-10-16").map((row) => row.id),
    ["current"],
  );
  assert.deepEqual(eligiblePlantings(plantings, "p1", ""), []);
});

test("任务选项要求同地块、已完成收获任务且日期位于所选批次周期", () => {
  const planting = {
    plotId: "p1",
    startDate: "2026-10-01",
    endDate: "2026-10-31",
  };
  const task = {
    id: "harvest",
    plotId: "p1",
    taskType: "HARVEST",
    status: "COMPLETED",
    dueDate: "2026-10-16",
  };
  const tasks = [
    task,
    { ...task, id: "pending", status: "PENDING" },
    { ...task, id: "other", plotId: "p2" },
    { ...task, id: "inspection", taskType: "INSPECTION" },
    { ...task, id: "outside", dueDate: "2026-09-30" },
  ];
  assert.deepEqual(
    eligibleHarvestTasks(tasks, "p1", planting).map((row) => row.id),
    ["harvest"],
  );
  assert.deepEqual(eligibleHarvestTasks(tasks, "p1", null), []);
});
