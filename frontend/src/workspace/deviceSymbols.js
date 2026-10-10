// Original line symbols shared by Leaflet markers and device lists.
export const devicePaths = {
  TRACTOR:
    '<circle cx="7" cy="17" r="4"/><circle cx="19" cy="18" r="3"/><path d="M3 13V9h7V4h6l2 9h3v2M10 9h7M11 17h5M5 9V5"/>',
  SPRAYER:
    '<circle cx="7" cy="19" r="2"/><circle cx="17" cy="19" r="2"/><path d="M5 17V7h9v10M14 10h5v7H5M3 12H1m20 0h2M2 14v2m20-2v2M8 4h4v3"/>',
  HARVESTER:
    '<circle cx="9" cy="18" r="3"/><circle cx="19" cy="19" r="2"/><path d="M5 15V6h8v9h6v2M13 9h5v6M5 11H2v9m-1-2h4M8 3h7v3"/>',
  DRONE:
    '<rect x="9" y="9" width="6" height="6" rx="2"/><path d="m9 9-4-4m10 4 4-4M9 15l-4 4m10-4 4 4M10 15v5m4-5v5"/><ellipse cx="5" cy="5" rx="4" ry="2"/><ellipse cx="19" cy="5" rx="4" ry="2"/><ellipse cx="5" cy="19" rx="4" ry="2"/><ellipse cx="19" cy="19" rx="4" ry="2"/>',
  WEATHER:
    '<circle cx="12" cy="10" r="4"/><path d="M12 2v2m0 12v2M4 10H2m20 0h-2M5 3l2 2m10 10 2 2M19 3l-2 2M5 17l2-2M7 22h10"/>',
  SOIL: '<path d="M12 3v13m0-8c-5 0-6-3-6-3 4-1 6 1 6 3Zm0 4c5 0 6-3 6-3-4-1-6 1-6 3ZM3 17h18M4 21h3m3 0h4m3 0h3"/>',
  WATER:
    '<path d="M12 2s-6 7-6 11a6 6 0 0 0 12 0c0-4-6-11-6-11ZM9 14c0 2 1 3 3 3M3 22h18"/>',
  GATEWAY:
    '<path d="M5 12a10 10 0 0 1 14 0M8 15a6 6 0 0 1 8 0m-4 2v4M4 4h16v5H4z"/><circle cx="12" cy="17" r="1"/>',
  GATE: '<path d="M5 4v17m14-17v17M3 4h18M8 9h8v8H8zM12 4v5M3 21h18"/>',
  PUMP: '<path d="M3 11h4m10 0h4v7m-14-3v5h10v-5M9 6h6v3M8 3h8"/><circle cx="12" cy="12" r="5"/><path d="m10 10 4 2-4 2"/>',
  PEST: '<ellipse cx="12" cy="13" rx="4" ry="6"/><path d="m8 8-3-3m11 3 3-3M8 11H3m13 0h5M8 15H3m13 0h5M8 18l-3 3m11-3 3 3M10 6V3m4 3V3M12 8v11"/>',
  CAMERA:
    '<path d="M3 5h13v11H3zM16 9l5-3v10l-5-3M7 16v5m-3 0h10"/><circle cx="9" cy="10" r="2"/>',
  MACHINERY:
    '<circle cx="7" cy="17" r="4"/><circle cx="19" cy="18" r="3"/><path d="M3 13V9h7V4h6l2 9h3v2M10 9h7M11 17h5M5 9V5"/>',
  OTHER: '<path d="M5 4h14v16H5zM8 8h8m-8 4h4m-4 4h8"/>',
};
export function deviceSvg(type) {
  return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${devicePaths[type] || devicePaths.OTHER}</svg>`;
}
export function deviceState(device, jobs = []) {
  if (device.lifecycle !== "ACTIVE")
    return {
      code: "idle",
      label: device.lifecycle === "MAINTENANCE" ? "维护中" : "已停用",
      badge: "—",
    };
  const fresh = device.freshness === "FRESH";
  const reading = (metric) =>
    device.channels?.find((c) => c.metric === metric && c.freshness === "FRESH")
      ?.latest?.value;
  if (device.alertCount > 0 || Number(reading("FAULT")) > 0)
    return { code: "alert", label: "异常 / 待处理", badge: "!" };
  if (!fresh)
    return {
      code: "stale",
      label: device.freshness === "NO_DATA" ? "暂无上报" : "离线 / 超时未报",
      badge: "×",
    };
  if (jobs.some((j) => j.deviceId === device.id && j.status === "RUNNING"))
    return { code: "running", label: "模拟任务运行中", badge: "▶" };
  if (jobs.some((j) => j.deviceId === device.id && j.status === "PAUSED"))
    return { code: "idle", label: "模拟任务已暂停", badge: "Ⅱ" };
  if (Number(reading("PUMP_RUNNING")) === 1 || Number(reading("SPEED")) > 0)
    return {
      code: "running",
      label: device.protocol === "SIMULATED" ? "模拟运行中" : "运行中",
      badge: "▶",
    };
  return {
    code: "fresh",
    label: device.protocol === "SIMULATED" ? "模拟在线" : "正常上报",
    badge: "✓",
  };
}
