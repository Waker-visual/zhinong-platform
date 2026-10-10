import { assetPlanPoint, planToWgs84 } from "./coordinates.js";
// Coordinates are [latitude, longitude] in WGS84, including persisted parcel rings.
export function deviceCoordinates(device, geo) {
  if (!device) return null;
  if (device.locationMode === "WGS84")
    return device.latitude == null || device.longitude == null
      ? null
      : [Number(device.latitude), Number(device.longitude)];
  if (!geo) return null;
  const point = assetPlanPoint(device, geo);
  return point ? planToWgs84(point, geo) : null;
}
export function containsPoint(ring, point) {
  if (!point || !ring?.length) return false;
  let inside = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const [y, x] = ring[i],
      [py, px] = ring[j];
    if (
      y > point[0] !== py > point[0] &&
      point[1] < ((px - x) * (point[0] - y)) / (py - y) + x
    )
      inside = !inside;
  }
  return inside;
}

// A progress illustration along the plan, deliberately not a telemetry track.
export function routePrefix(points, percentage) {
  if (!points?.length) return [];
  const cos = Math.cos((points[0][0] * Math.PI) / 180);
  const lengths = points
    .slice(1)
    .map((p, i) =>
      Math.hypot(p[0] - points[i][0], (p[1] - points[i][1]) * cos),
    );
  let remaining =
    (lengths.reduce((a, b) => a + b, 0) *
      Math.max(0, Math.min(100, percentage))) /
    100;
  const result = [points[0]];
  for (let i = 0; i < lengths.length; i++) {
    if (remaining >= lengths[i]) {
      result.push(points[i + 1]);
      remaining -= lengths[i];
    } else {
      const f = lengths[i] ? remaining / lengths[i] : 0;
      result.push(points[i].map((v, j) => v + (points[i + 1][j] - v) * f));
      break;
    }
  }
  return result;
}
