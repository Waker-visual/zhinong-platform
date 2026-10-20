import { numeric } from "../metrics/measurement-model.js";

function nonNegative(value) {
  if (typeof value === "string" && !value.trim()) return null;
  const number = numeric(value);
  return number !== null && number >= 0 ? number : null;
}

function count(value) {
  const number = nonNegative(value);
  return Number.isSafeInteger(number) ? number : null;
}

function text(value) {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function taskCounts(rows) {
  // The grouped status array covers every recorded task. An absent group is
  // zero only when that complete aggregate was actually returned.
  if (
    !Array.isArray(rows) ||
    rows.some((row) => !text(row?.code) || count(row?.value) === null)
  )
    return null;
  const totals = new Map();
  let total = 0;
  for (const row of rows) {
    const code = row.code.trim();
    const value = count(row.value);
    total += value;
    // Combine repeated groups instead of silently dropping one of their counts.
    totals.set(code, (totals.get(code) ?? 0) + value);
  }
  if (!Number.isSafeInteger(total)) return null;
  return {
    total,
    pending: totals.get("PENDING") ?? 0,
    running: totals.get("RUNNING") ?? 0,
    completed: totals.get("COMPLETED") ?? 0,
    cancelled: totals.get("CANCELLED") ?? 0,
  };
}

/** Farm-scoped business aggregates already loaded by the display, not task samples. */
export function agronomyWork(workspace, operations, analysis) {
  const status = taskCounts(workspace?.analytics?.taskStatus);
  const harvest = operations?.pipeline?.harvest;
  const crops = Array.isArray(analysis?.crops) ? analysis.crops : null;
  const areas = crops?.map((crop) => nonNegative(crop?.areaMu));
  return {
    tasks: {
      total: status?.total ?? null,
      pending: status?.pending ?? null,
      running: status?.running ?? null,
      completed: status?.completed ?? null,
      cancelled: status?.cancelled ?? null,
      open:
        count(operations?.summary?.openTasks) ??
        count(workspace?.analytics?.summary?.openTasks),
      overdue: count(operations?.summary?.overdueTasks),
      issues: count(operations?.summary?.openIssues),
    },
    harvest: {
      yieldKg: nonNegative(harvest?.yieldKg),
      records: count(harvest?.records),
      fromDate: text(harvest?.fromDate),
      toDate: text(harvest?.toDate),
      latest: text(harvest?.latest),
      open: count(harvest?.open),
    },
    activities: [
      ["irrigation", "灌溉作业"],
      ["protection", "植保作业"],
    ].map(([key, title]) => {
      const activity = operations?.pipeline?.[key];
      return {
        key,
        title,
        open: count(activity?.open),
        blocked: count(activity?.blocked),
        lastCompleted: text(activity?.lastCompleted),
      };
    }),
    plantings: {
      available: crops !== null,
      total: crops?.length ?? null,
      // These are active plan areas, not the farm's registered land area. The
      // analysis endpoint has already applied its farm-calendar date bounds.
      areaMu:
        areas && areas.every((area) => area !== null)
          ? areas.reduce((sum, area) => sum + area, 0)
          : null,
      items: crops ? crops.map((crop) => ({ ...crop })) : [],
    },
  };
}
