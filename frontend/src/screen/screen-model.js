import { assetPlanPoint } from "../workspace/coordinates.js";

export function numeric(value) {
  if (
    value === null ||
    value === undefined ||
    value === "" ||
    typeof value === "boolean"
  )
    return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

export function numberText(value, digits = 1) {
  const number = numeric(value);
  return number === null
    ? "—"
    : number.toLocaleString("zh-CN", { maximumFractionDigits: digits });
}

export function canReadFarm(role, farmId, farms) {
  return (
    ["ADMIN", "OPERATOR", "VIEWER"].includes(role) &&
    !!farmId &&
    (farms || []).some((farm) => farm.id === farmId && !farm.archived)
  );
}

export function timeText(value, { short = false } = {}) {
  if (!value || !Number.isFinite(new Date(value).getTime())) return "—";
  return new Date(value).toLocaleString("zh-CN", {
    timeZone: "Asia/Shanghai",
    hour12: false,
    ...(short
      ? { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" }
      : {}),
  });
}

function recent(value, seconds, now) {
  const time = new Date(value).getTime();
  return (
    !!value &&
    Number.isFinite(time) &&
    time <= now + 60000 &&
    time > now - seconds * 1000
  );
}

// Treat the server freshness verdict as necessary, then age it locally between polls.
export function metricSummary(sensors, metric, now = Date.now()) {
  const rows = (sensors || []).filter((sensor) => sensor.metric === metric);
  const fresh = rows.filter((sensor) => {
    const seconds = Math.min(numeric(sensor.freshnessSeconds) ?? 900, 900);
    return (
      sensor.fresh === true &&
      numeric(sensor.value) !== null &&
      recent(sensor.time, seconds, now) &&
      recent(sensor.lastReceivedAt || sensor.readingReceivedAt, seconds, now)
    );
  });
  const values = fresh.map((sensor) => numeric(sensor.value));
  const timestamps = rows
    .map((sensor) => sensor.time)
    .filter((value) => value && Number.isFinite(new Date(value).getTime()));
  return {
    value: values.length
      ? values.reduce((sum, value) => sum + value, 0) / values.length
      : null,
    min: values.length ? Math.min(...values) : null,
    max: values.length ? Math.max(...values) : null,
    fresh: values.length,
    observed: rows.length,
    stale: rows.length - fresh.length,
    latest: timestamps.sort((a, b) => new Date(b) - new Date(a))[0] || null,
    state: values.length ? "FRESH" : rows.length ? "STALE" : "NO_DATA",
  };
}

export function deviceCounts(devices, now = Date.now()) {
  if (!Array.isArray(devices))
    return { total: null, monitored: null, fresh: null, late: null };
  const monitored = devices.filter(
    (device) => device.lifecycle === "ACTIVE" && device.protocol !== "MANUAL",
  );
  const fresh = monitored.filter(
    (device) =>
      device.connectionState === "FRESH" &&
      recent(
        device.lastReceivedAt,
        Math.max(180, (numeric(device.intervalSeconds) ?? 60) * 3),
        now,
      ),
  ).length;
  return {
    total: devices.length,
    monitored: monitored.length,
    fresh,
    late: monitored.length - fresh,
  };
}

export function pendingTasks(tasks) {
  return (tasks || [])
    .filter((task) => ["PENDING", "RUNNING"].includes(task.status))
    .sort((a, b) =>
      String(a.dueDate || "9999").localeCompare(String(b.dueDate || "9999")),
    );
}

export function hasDeviceAlert(device, alerts) {
  return (
    alerts.some((alert) => alert.deviceId === device.id) ||
    (alerts.length >= 200 && Number(device.alertCount) > 0)
  );
}

export function moisturePoints(operations) {
  return (operations?.slices || []).map((slice) => [
    slice.start,
    numeric(slice.moisture),
  ]);
}

export function moistureOption(operations, compact = false) {
  return {
    backgroundColor: "transparent",
    color: ["var(--display-leaf)"],
    tooltip: {
      trigger: "axis",
      renderMode: "richText",
      valueFormatter: (value) =>
        value === null ? "无采样" : numberText(value) + "%",
    },
    grid: {
      top: compact ? 12 : 25,
      left: 45,
      right: 16,
      bottom: compact ? 22 : 28,
    },
    textStyle: { color: "var(--text-2)" },
    xAxis: {
      type: "time",
      splitNumber: compact ? 3 : 4,
      axisLabel: { color: "var(--text-2)", hideOverlap: true },
    },
    yAxis: {
      type: "value",
      name: "%",
      nameGap: 8,
      scale: true,
      splitNumber: compact ? 2 : 4,
      axisLabel: { color: "var(--text-2)", hideOverlap: true },
    },
    series: [
      {
        name: "土壤水分均值",
        type: "line",
        showSymbol: false,
        connectNulls: false,
        smooth: false,
        lineStyle: { width: 2 },
        areaStyle: { opacity: 0.08 },
        data: moisturePoints(operations),
      },
    ],
  };
}

function point(value) {
  return (
    Array.isArray(value) &&
    value.length >= 2 &&
    numeric(value[0]) !== null &&
    numeric(value[1]) !== null
  );
}

export function mapGeometry(workspace, geo) {
  const plots = (workspace?.plots || []).flatMap((plot) => {
    const boundary = (plot.boundary || [])
      .filter(point)
      .map((pair) => pair.slice(0, 2).map(Number));
    if (boundary.length < 3) return [];
    return [
      {
        ...plot,
        points: boundary.map((pair) => pair.join(",")).join(" "),
        x: boundary.reduce((sum, pair) => sum + pair[0], 0) / boundary.length,
        y: boundary.reduce((sum, pair) => sum + pair[1], 0) / boundary.length,
      },
    ];
  });
  const geoValid =
    [geo?.latitude, geo?.longitude, geo?.widthMeters, geo?.heightMeters].every(
      (value) => numeric(value) !== null,
    ) &&
    Number(geo.widthMeters) > 0 &&
    Number(geo.heightMeters) > 0;
  const devices = (workspace?.devices || []).flatMap((device) => {
    if (device.locationMode === "WGS84" && !geoValid) return [];
    const location = assetPlanPoint(device, geo);
    if (!point(location)) return [];
    const [x, y] = location;
    if (x < 0 || x > 1000 || y < 0 || y > 700) return [];
    return [{ ...device, x, y }];
  });
  return {
    plots,
    devices,
    bounds: mapBounds(plots, devices),
    missingPlots: (workspace?.plots?.length || 0) - plots.length,
    missingDevices: (workspace?.devices?.length || 0) - devices.length,
  };
}

function mapBounds(plots, devices) {
  const points = plots
    .flatMap((plot) => plot.boundary || [])
    .filter(point)
    .concat(devices.map((device) => [device.x, device.y]));
  if (!points.length) return { x: 0, y: 0, width: 1000, height: 700 };
  const xs = points.map((position) => Number(position[0])),
    ys = points.map((position) => Number(position[1]));
  const x = Math.min(...xs) - 55,
    y = Math.min(...ys) - 55;
  return {
    x,
    y,
    width: Math.max(120, Math.max(...xs) - x + 55),
    height: Math.max(120, Math.max(...ys) - y + 55),
  };
}

export function productionTotal(analytics) {
  const values = (analytics?.productionTrend || [])
    .map((point) => numeric(point.value))
    .filter((value) => value !== null);
  return values.length ? values.reduce((sum, value) => sum + value, 0) : null;
}
