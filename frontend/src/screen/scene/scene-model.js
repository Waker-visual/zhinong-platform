import {
  deviceCounts,
  hasDeviceAlert,
  metricSummary,
  numeric,
} from "../screen-model.js";

const MAX_PLOTS = 12;
const ALERT_LIMIT = 200;
const METRICS = [
  { metric: "TEMPERATURE", label: "空气温度", unit: "℃" },
  { metric: "HUMIDITY", label: "空气湿度", unit: "%" },
  { metric: "SOIL_MOISTURE", label: "土壤水分", unit: "%" },
];
const STATIC_HOTSPOTS = [
  {
    id: "weather",
    label: "环境监测",
    kind: "weather",
    position: [30, 3, -18],
  },
  {
    id: "water",
    label: "水源与灌溉",
    kind: "water",
    position: [34, 2, 9],
  },
  {
    id: "yard",
    label: "农场作业区",
    kind: "yard",
    position: [-6, 4, -29],
  },
];

const rows = (value) => (Array.isArray(value) ? value : []);
const validId = (value) => typeof value === "string" && !!value.trim();
const label = (value, fallback) =>
  typeof value === "string" && value.trim() ? value.trim() : fallback;
const unassigned = (value) =>
  value === null || value === undefined || value === "";

// This layout represents registered plots schematically; it is never a GPS map.
export function buildSceneModel(workspace, analysis, now = Date.now()) {
  const farmId = workspace?.farm?.id ?? workspace?.farmId ?? null;
  const seenPlots = new Set();
  const sourcePlots = rows(workspace?.plots).filter((plot) => {
    if (
      !plot ||
      !validId(plot.id) ||
      seenPlots.has(plot.id) ||
      (farmId && plot.farmId && plot.farmId !== farmId)
    )
      return false;
    seenPlots.add(plot.id);
    return true;
  });
  const sourceDevices = rows(workspace?.devices).filter(
    (device) =>
      device &&
      validId(device.id) &&
      (!farmId || !device.farmId || device.farmId === farmId),
  );
  const devicesById = new Map(
    sourceDevices.map((device) => [device.id, device]),
  );
  const sensors = rows(analysis?.sensors).filter((sensor) => {
    if (!sensor) return false;
    const device = devicesById.get(sensor.deviceId);
    if (!device) return false;
    // Farm-level weather stations are valid, but cannot supply a plot reading.
    if (unassigned(sensor.plotId)) return unassigned(device.plotId);
    return seenPlots.has(sensor.plotId) && sensor.plotId === device.plotId;
  });
  const sourceAlerts = rows(workspace?.alerts);
  // Filtering a capped response cannot make it a complete alert inventory.
  const alertLimitReached = sourceAlerts.length >= ALERT_LIMIT;
  const alerts = sourceAlerts.filter(
    (alert) =>
      alert &&
      alert.status !== "RESOLVED" &&
      devicesById.has(alert.deviceId) &&
      (!farmId || !alert.farmId || alert.farmId === farmId),
  );
  const deviceHasAlerts = (device) =>
    hasDeviceAlert(device, alerts) ||
    (alertLimitReached && (numeric(device.alertCount) ?? 0) > 0);
  const visiblePlots = sourcePlots.slice(0, MAX_PLOTS);
  const columns =
    visiblePlots.length <= 4 ? 2 : visiblePlots.length <= 6 ? 3 : 4;
  const rowCount = Math.ceil(visiblePlots.length / columns);
  const cellWidth = 54 / columns;
  const cellDepth = rowCount ? 36 / rowCount : 0;
  const plots = visiblePlots.map((plot, index) => {
    const plotDevices = sourceDevices.filter(
      (device) => device.plotId === plot.id,
    );
    const alertedDevices = new Set(
      plotDevices.filter(deviceHasAlerts).map((device) => device.id),
    );
    return {
      id: `plot:${plot.id}`,
      plotId: plot.id,
      label: label(plot.name, "未命名地块"),
      crop: label(plot.crop, "未登记作物"),
      areaMu: numeric(plot.areaMu),
      position: [
        -27 + ((index % columns) + 0.5) * cellWidth,
        0,
        -18 + (Math.floor(index / columns) + 0.5) * cellDepth,
      ],
      size: [cellWidth - 1.6, cellDepth - 1.6],
      moisture: metricSummary(
        sensors.filter((sensor) => sensor.plotId === plot.id),
        "SOIL_MOISTURE",
        now,
      ),
      deviceCount: Array.isArray(workspace?.devices)
        ? plotDevices.length
        : null,
      // Count known unresolved records, not a fabricated count from a badge.
      alertCount: alerts.filter((alert) => alertedDevices.has(alert.deviceId))
        .length,
      alertLimitReached,
      hasAlerts: alertedDevices.size > 0,
    };
  });

  return {
    status: !Array.isArray(workspace?.plots)
      ? "loading"
      : plots.length
        ? "ready"
        : "empty",
    plots,
    hotspots: [
      ...plots.map((plot) => ({
        id: plot.id,
        label: plot.label,
        kind: "plot",
        position: [plot.position[0], 1, plot.position[2]],
        plotId: plot.plotId,
      })),
      ...STATIC_HOTSPOTS.map((hotspot) => ({
        ...hotspot,
        position: [...hotspot.position],
      })),
    ],
    layoutKey: JSON.stringify([
      farmId,
      plots.map((plot) => [plot.plotId, plot.crop, plot.position, plot.size]),
    ]),
    omittedPlots: sourcePlots.length - plots.length,
    totalPlots: Array.isArray(workspace?.plots) ? sourcePlots.length : null,
    metrics: METRICS.map((metric) => ({
      ...metric,
      ...metricSummary(sensors, metric.metric, now),
    })),
    devices: deviceCounts(
      Array.isArray(workspace?.devices) ? sourceDevices : undefined,
      now,
    ),
    alertCount: workspace ? alerts.length : null,
    alertLimitReached,
    hasAlerts: sourceDevices.some(deviceHasAlerts),
  };
}
