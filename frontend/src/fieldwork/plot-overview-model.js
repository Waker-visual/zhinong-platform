// 今日农场“地块示意”的数据整理：把未结束的农事与未关闭的现场问题按地块归并，
// 并计算示意图的取景范围。只做归并与几何计算，不产生新的业务指标。

const OPEN_STATUSES = ["PENDING", "RUNNING"];

// 任务与问题带 plotId；旧数据缺失时退回按地块名对应。
function belongsTo(plot, row) {
  return row.plotId ? row.plotId === plot.id : row.plotName === plot.name;
}

export function plotSignals(plots = [], tasks = [], issues = [], today = "") {
  return plots.map((plot) => {
    const open = tasks.filter((task) => OPEN_STATUSES.includes(task.status) && belongsTo(plot, task));
    const overdue = today ? open.filter((task) => task.dueDate < today).length : 0;
    const blocked = open.filter((task) => task.blockedReason).length;
    const unresolved = issues.filter((issue) => issue.status !== "RESOLVED" && belongsTo(plot, issue)).length;
    return {
      id: plot.id,
      name: plot.name,
      crop: plot.crop || "",
      areaMu: plot.areaMu,
      boundary: Array.isArray(plot.boundary) ? plot.boundary : [],
      open: open.length,
      overdue,
      blocked,
      issues: unresolved,
      // 与任务徽标同一优先级：逾期先于受阻，其次是待跟进的现场问题。
      tone: overdue ? "danger" : blocked ? "caution" : unresolved ? "warning" : "",
    };
  });
}

// 取景范围：全部边界的外接矩形，四周留白，避免地块贴边。没有可画的边界时返回 null。
export function viewBoxFor(rows = [], padding = 0.08) {
  const points = rows.flatMap((row) => (row.boundary?.length >= 3 ? row.boundary : []));
  if (!points.length) return null;
  const xs = points.map((point) => point[0]), ys = points.map((point) => point[1]);
  const left = Math.min(...xs), top = Math.min(...ys);
  const width = Math.max(...xs) - left, height = Math.max(...ys) - top;
  const margin = Math.max(width, height, 1) * padding;
  return { x: left - margin, y: top - margin, width: width + margin * 2, height: height + margin * 2 };
}

// 多边形面积加权质心；退化为线或点时取顶点平均值，标记仍落在图形附近。
export function centroidOf(boundary = []) {
  let area = 0, cx = 0, cy = 0;
  for (let i = 0; i < boundary.length; i++) {
    const [x0, y0] = boundary[i], [x1, y1] = boundary[(i + 1) % boundary.length];
    const cross = x0 * y1 - x1 * y0;
    area += cross;
    cx += (x0 + x1) * cross;
    cy += (y0 + y1) * cross;
  }
  if (Math.abs(area) < 1e-9) {
    const count = boundary.length || 1;
    return [boundary.reduce((sum, point) => sum + point[0], 0) / count, boundary.reduce((sum, point) => sum + point[1], 0) / count];
  }
  return [cx / (3 * area), cy / (3 * area)];
}
