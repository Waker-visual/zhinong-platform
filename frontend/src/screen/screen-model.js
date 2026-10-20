import { assetPlanPoint } from "../workspace/coordinates.js";
import { numeric } from "../metrics/measurement-model.js";
export { numeric, deviceCounts } from "../metrics/measurement-model.js";

export function numberText(value, digits = 1) {
  const number = numeric(value);
  return number === null
    ? "—"
    : number.toLocaleString("zh-CN", { maximumFractionDigits: digits });
}

const forecastStates = new Set(["READY", "STALE", "UNAVAILABLE", "UNCONFIGURED", "DISABLED"]);
export function forecastPresentation(data, resource = {}, now = Date.now()) {
  let status = data?.status;
  if (!forecastStates.has(status)) status = resource.loading ? "LOADING" : "UNAVAILABLE";
  if (data && data.dataKind !== "PUBLIC_FORECAST") status = "UNAVAILABLE";
  const dates = new Set();
  const days = ["READY", "STALE"].includes(status)
    ? (data?.days || []).filter(day => {
      if (!/^\d{4}-\d{2}-\d{2}$/.test(day.date || "")) return false;
      const at = Date.parse(day.date + "T00:00:00Z");
      if (!Number.isFinite(at) || new Date(at).toISOString().slice(0, 10) !== day.date || dates.has(day.date)) return false;
      dates.add(day.date);
      return true;
    }).sort((a,b) => a.date.localeCompare(b.date)).slice(0, 7)
    : [];
  const expiry = Date.parse(data?.expiresAt);
  let today;
  try { today = new Intl.DateTimeFormat("sv-SE", {timeZone:data?.timeZone || "Asia/Shanghai"}).format(new Date(now)); }
  catch { today = new Date(now).toISOString().slice(0,10); }
  const expired = status === "STALE" || (Number.isFinite(expiry) && now >= expiry) || (!!days[0] && days[0].date < today);
  if (status === "READY" && (resource.error || expired)) status = "STALE";
  if (["READY", "STALE"].includes(status) && !days.length) status = "UNAVAILABLE";
  return {
    status, days, expired,
    label: status === "STALE" && resource.error && !expired ? "更新失败，保留旧预报" : { READY: "预报已更新", STALE: "预报已过期", UNAVAILABLE: "天气服务暂不可用", UNCONFIGURED: "参考位置待配置", DISABLED: "天气服务未启用", LOADING: "正在读取预报" }[status],
  };
}

export function forecastWeatherType(code) {
  const value = numeric(code);
  if (value === null) return "unknown";
  if (value === 0) return "sun";
  if ([1, 2, 3].includes(value)) return "cloud";
  if ([45, 48].includes(value)) return "fog";
  if ([71, 73, 75, 77, 85, 86].includes(value)) return "snow";
  if ([95, 96, 99].includes(value)) return "storm";
  if ([51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82].includes(value)) return "rain";
  return "unknown";
}

export const riskSources = { WEATHER: "天气风险", DEVICE: "设备预警", FIELD: "现场问题", DATA_QUALITY: "数据缺口" };
export const riskSeverities = { HIGH: "优先处理", WARNING: "需要跟进", INFO: "参考信息" };
const riskRank = { HIGH: 0, WARNING: 1, INFO: 2 };
export function riskCoversDate(item, date) {
  if (!date) return true;
  if (Array.isArray(item.affectedDates)) return item.affectedDates.includes(date);
  return !!item.affectedDate && item.affectedDate <= date && (item.endDate || item.affectedDate) >= date;
}
export function riskForecastFreshness(data, now = Date.now()) {
  const expiry = Date.parse(data?.forecastExpiresAt);
  return data?.forecastStatus === "READY" && Number.isFinite(expiry) && now >= expiry ? "STALE" : data?.forecastStatus || "UNAVAILABLE";
}
export function prioritizedRisks(data, { source = "ALL", highOnly = false, date = "" } = {}) {
  const seen = new Set();
  return (data?.items || []).filter(item => {
    const key = `${item.source}:${item.id}`;
    if (!item.id || seen.has(key)) return false;
    seen.add(key);
    return (source === "ALL" || item.source === source) && (!highOnly || item.severity === "HIGH") && (!date || (item.source === "WEATHER" && riskCoversDate(item, date)));
  }).sort((a,b) => (riskRank[a.severity] ?? 2) - (riskRank[b.severity] ?? 2) || String(a.affectedDate || "9999").localeCompare(String(b.affectedDate || "9999")));
}

export function weatherRisksOnDate(data, date) {
  return prioritizedRisks(data, { source: "WEATHER", date });
}

export function publicWebUrl(value) {
  try { const url = new URL(value); return ["https:", "http:"].includes(url.protocol) ? url.href : null; }
  catch { return null; }
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
    time <= now &&
    time > now - seconds * 1000
  );
}

// Treat the server freshness verdict as necessary, then age it locally between polls.
export function metricSummary(sensors, metric, now = Date.now()) {
  const rows = (sensors || []).filter((sensor) => sensor.metric === metric);
  const fresh = rows.filter((sensor) => {
    const seconds = numeric(sensor.freshnessSeconds) ?? 180;
    return (
      sensor.fresh === true &&
      numeric(sensor.value) !== null &&
      recent(sensor.time, seconds, now)
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
    observed: rows.filter(sensor => numeric(sensor.value) !== null).length,
    stale: rows.filter(sensor => numeric(sensor.value) !== null).length - fresh.length,
    latest: timestamps.sort((a, b) => new Date(b) - new Date(a))[0] || null,
    state: values.length ? "FRESH" : rows.some(sensor => numeric(sensor.value) !== null) ? "STALE" : "NO_DATA",
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

export function productionDistribution(analytics) {
  if (
    !Array.isArray(analytics?.productionTrend) ||
    !Array.isArray(analytics?.productionByPlot)
  )
    return {
      state: "UNAVAILABLE",
      rows: [],
      recordedDays: null,
      missingPlots: 0,
      groupedPlots: 0,
    };
  // The API groups records by date; this count must never be called a record count.
  const recordedDays = new Set(
    analytics.productionTrend.map((row) => row.date).filter(Boolean),
  ).size;
  const rows = analytics.productionByPlot
    .map((row, index) => ({
      name: row.name || "未命名地块",
      value: numeric(row.value),
      key: `plot-${index}`,
      plots: 1,
    }))
    .filter((row) => row.value !== null)
    .sort((a, b) => b.value - a.value);
  const missingPlots = analytics.productionByPlot.length - rows.length;
  // The LEFT JOIN supplies zero for every plot even when there are no records.
  // Only the dated ledger can distinguish that case from a recorded zero yield.
  if (!analytics.productionTrend.length)
    return {
      state: "EMPTY",
      rows: [],
      recordedDays,
      missingPlots,
      groupedPlots: 0,
    };
  if (!rows.length)
    return {
      state: "UNAVAILABLE",
      rows: [],
      recordedDays,
      missingPlots,
      groupedPlots: 0,
    };
  const grouped = rows.length > 4 ? rows.slice(3) : [];
  return {
    state: "AVAILABLE",
    recordedDays,
    missingPlots,
    groupedPlots: grouped.length,
    rows: grouped.length
      ? [
          ...rows.slice(0, 3),
          {
            key: "other-plots",
            name: `其他 ${grouped.length} 块`,
            value: grouped.reduce((sum, row) => sum + row.value, 0),
            plots: grouped.length,
          },
        ]
      : rows,
  };
}

export function productionOption(distribution) {
  return {
    backgroundColor: "transparent",
    color: ["var(--display-leaf)"],
    textStyle: { color: "var(--text-2)" },
    grid: { top: 4, bottom: 4, left: 94, right: 72 },
    tooltip: {
      trigger: "item",
      renderMode: "richText",
      confine: true,
      formatter: ({ data }) =>
        `${data.name}\n登记产量 ${numberText(data.value)} kg${data.plots > 1 ? `\n其余 ${data.plots} 个地块合计` : ""}`,
    },
    xAxis: { type: "value", show: false },
    yAxis: {
      type: "category",
      inverse: true,
      data: distribution.rows.map((row) => row.name),
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: {
        color: "var(--text-2)",
        fontSize: 11,
        width: 84,
        overflow: "truncate",
        interval: 0,
      },
    },
    series: [
      {
        name: "登记采收产量",
        type: "bar",
        barMaxWidth: 8,
        showBackground: true,
        backgroundStyle: { color: "var(--border)" },
        itemStyle: { borderRadius: 4 },
        label: {
          show: true,
          position: "right",
          color: "var(--text)",
          fontSize: 11,
          formatter: ({ value }) => `${numberText(value)} kg`,
        },
        data: distribution.rows,
      },
    ],
  };
}
