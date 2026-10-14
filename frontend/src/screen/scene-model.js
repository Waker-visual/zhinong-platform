import { numeric } from "./screen-model.js";
import { geographicPoint } from "./satellite-model.js";

export function sceneDeviceState(device, now = Date.now()) {
  const age = Math.max(180, (numeric(device.intervalSeconds) ?? 60) * 3) * 1000;
  const recent = (time) => {
    const value = Date.parse(time);
    return Number.isFinite(value) && value <= now + 60000 && value > now - age;
  };
  const online =
    device.lifecycle === "ACTIVE" &&
    device.freshness === "FRESH" &&
    recent(device.lastReceivedAt);
  const reading = (metric) => {
    const channel = device.channels?.find((item) => item.metric === metric);
    return online &&
      channel?.freshness === "FRESH" &&
      recent(channel.latest?.time) &&
      recent(channel.latest?.receivedAt)
      ? numeric(channel.latest.value)
      : null;
  };
  const metric =
    device.deviceType === "PUMP"
      ? "PUMP_RUNNING"
      : device.deviceType === "GATE"
        ? "GATE_OPENING"
        : device.deviceType === "MACHINERY"
          ? "SPEED"
          : null;
  const value = metric ? reading(metric) : null;
  const known = metric ? value !== null : online;
  const running =
    known &&
    (metric === "PUMP_RUNNING" ? value === 1 : metric === "SPEED" && value > 0);
  const opening =
    metric === "GATE_OPENING" && known ? Math.min(100, Math.max(0, value)) : 0;
  return {
    id: String(device.id),
    known,
    running: !!running,
    opening,
    alert: device.alertCount > 0,
    label: !known
      ? "状态未知"
      : metric === "GATE_OPENING"
        ? `开度 ${opening}%`
        : running
          ? "运行中"
          : metric
            ? "已停止"
            : "正常上报",
  };
}

function deviceKind(device) {
  if (device.deviceType !== "MACHINERY") return device.deviceType || "OTHER";
  const text = `${device.name || ""} ${device.model || ""}`;
  return /无人机|大疆|T100/i.test(text)
    ? "DRONE"
    : /收割|1180/i.test(text)
      ? "HARVESTER"
      : /喷雾|植保机|3WPZ/i.test(text)
        ? "SPRAYER"
        : "TRACTOR";
}

// Only geometry already scoped by the authenticated farm workspace enters the scene.
export function sceneGeometry(geometry, plots = [], zones = []) {
  const crops = new Map(
    plots.map((plot) => [
      plot.id,
      /水稻|稻/.test(plot.crop || "")
        ? "rice"
        : /玉米/.test(plot.crop || "")
          ? "corn"
          : /蔬菜|菜/.test(plot.crop || "")
            ? "vegetable"
            : /小麦|麦/.test(plot.crop || "")
              ? "wheat"
              : "unknown",
    ]),
  );
  const points = geometry.shapes.flatMap((shape) => shape.boundary);
  if (!points.length) return { plots: [], devices: [] };
  const latitude =
    (Math.min(...points.map((p) => p[0])) +
      Math.max(...points.map((p) => p[0]))) /
    2;
  const longitude =
    (Math.min(...points.map((p) => p[1])) +
      Math.max(...points.map((p) => p[1]))) /
    2;
  const cos = Math.cos((latitude * Math.PI) / 180);
  // Local tangent plane in metres; one Unity unit is one metre.
  const metersPerDegree = (Math.PI * 6378137) / 180;
  const project = ([lat, lon]) => [
    (lon - longitude) * cos * metersPerDegree,
    (lat - latitude) * metersPerDegree,
  ];
  const local = points.map(project);
  const position = (point) => {
    const [x, z] = project(point);
    return { x, z };
  };
  return {
    originLatitude: latitude,
    originLongitude: longitude,
    width:
      Math.max(...local.map((p) => p[0])) - Math.min(...local.map((p) => p[0])),
    depth:
      Math.max(...local.map((p) => p[1])) - Math.min(...local.map((p) => p[1])),
    plots: geometry.shapes.map((shape) => ({
      id: String(shape.plotId),
      parcelId: String(shape.id || shape.plotId),
      crop: crops.get(shape.plotId) || "unknown",
      points: shape.boundary.map(position),
    })),
    devices: geometry.devices.map((device) => ({
      id: String(device.id),
      kind: deviceKind(device),
      ...position(device.position),
      alert: device.alertCount > 0,
    })),
    zones: zones.flatMap((zone) => {
      if (
        !geometry.shapes.some(
          (shape) => shape.id === zone.parcelId && shape.plotId === zone.plotId,
        ) ||
        !geometry.devices.some((device) => device.id === zone.pumpId)
      )
        return [];
      if (
        !Array.isArray(zone.pipeline) ||
        zone.pipeline.length < 2 ||
        zone.pipeline.some((point) => !geographicPoint(point))
      )
        return [];
      return [
        {
          id: String(zone.id),
          pumpId: String(zone.pumpId),
          parcelId: String(zone.parcelId),
          points: zone.pipeline.map(position),
          nodes: (zone.nodes || []).flatMap((node) =>
            geographicPoint(node.point) &&
            ["VALVE", "OUTLET"].includes(node.kind)
              ? [{ ...position(node.point), kind: node.kind }]
              : [],
          ),
        },
      ];
    }),
  };
}

export function sceneZoneStates(
  zones,
  jobs,
  devices,
  readAt,
  now = Date.now(),
) {
  const stamp = Date.parse(readAt);
  const recent =
    Number.isFinite(stamp) && stamp <= now + 60000 && now - stamp < 15000;
  return zones.map((zone) => ({
    id: zone.id,
    running:
      recent &&
      devices.some((d) => d.id === zone.pumpId && d.known && d.running) &&
      jobs.some(
        (job) =>
          job.kind === "IRRIGATION" &&
          job.status === "RUNNING" &&
          job.parcelId === zone.parcelId &&
          job.deviceId === zone.pumpId,
      ),
  }));
}
