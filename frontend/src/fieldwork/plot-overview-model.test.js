import test from "node:test";
import assert from "node:assert/strict";
import { centroidOf, plotSignals, viewBoxFor } from "./plot-overview-model.js";

const square = (x, y, size) => [[x, y], [x + size, y], [x + size, y + size], [x, y + size]];
const plots = [
  { id: "p1", name: "东区", crop: "水稻", areaMu: 20, boundary: square(0, 0, 100) },
  { id: "p2", name: "西区", crop: "蔬菜", areaMu: 30, boundary: square(200, 0, 100) },
  { id: "p3", name: "北区", crop: "", areaMu: 5 },
];

test("open work and unresolved issues are counted on their own plot only", () => {
  const tasks = [
    { plotId: "p1", status: "PENDING", dueDate: "2026-11-10" },
    { plotId: "p1", status: "RUNNING", dueDate: "2026-11-20", blockedReason: "缺人" },
    { plotId: "p1", status: "COMPLETED", dueDate: "2026-11-01" },
    { plotId: "p2", status: "CANCELLED", dueDate: "2026-11-01", blockedReason: "旧记录" },
  ];
  const issues = [
    { plotId: "p2", status: "OPEN" },
    { plotId: "p2", status: "RESOLVED" },
  ];
  const [east, west, north] = plotSignals(plots, tasks, issues, "2026-11-16");
  assert.deepEqual([east.open, east.overdue, east.blocked, east.issues], [2, 1, 1, 0]);
  assert.deepEqual([west.open, west.overdue, west.blocked, west.issues], [0, 0, 0, 1]);
  assert.deepEqual([north.open, north.issues, north.boundary], [0, 0, []]);
});

test("tone follows overdue, then blocked, then open issues", () => {
  const tone = (tasks, issues) => plotSignals(plots.slice(0, 1), tasks, issues, "2026-11-16")[0].tone;
  assert.equal(tone([{ plotId: "p1", status: "PENDING", dueDate: "2026-11-01", blockedReason: "缺人" }], [{ plotId: "p1", status: "OPEN" }]), "danger");
  assert.equal(tone([{ plotId: "p1", status: "PENDING", dueDate: "2026-11-30", blockedReason: "缺人" }], [{ plotId: "p1", status: "OPEN" }]), "caution");
  assert.equal(tone([], [{ plotId: "p1", status: "ASSIGNED" }]), "warning");
  assert.equal(tone([{ plotId: "p1", status: "PENDING", dueDate: "2026-11-30" }], []), "");
});

test("nothing is overdue before the farm date is known", () => {
  const [east] = plotSignals(plots, [{ plotId: "p1", status: "PENDING", dueDate: "2020-01-01" }], [], "");
  assert.equal(east.overdue, 0);
  assert.equal(east.open, 1);
});

test("rows without a plot id fall back to the plot name", () => {
  const [east, west] = plotSignals(plots, [{ plotName: "西区", status: "PENDING", dueDate: "2026-11-30" }], [], "2026-11-16");
  assert.equal(east.open, 0);
  assert.equal(west.open, 1);
});

test("the view box frames every drawable boundary with a margin", () => {
  const box = viewBoxFor(plotSignals(plots), 0.1);
  assert.deepEqual(box, { x: -30, y: -30, width: 360, height: 160 });
  assert.equal(viewBoxFor(plotSignals([plots[2]])), null);
  assert.equal(viewBoxFor([]), null);
});

test("centroid is the centre of a rectangle and survives degenerate shapes", () => {
  assert.deepEqual(centroidOf(square(10, 20, 40)), [30, 40]);
  assert.deepEqual(centroidOf([[0, 0], [10, 0]]), [5, 0]);
  assert.deepEqual(centroidOf([]), [0, 0]);
});
