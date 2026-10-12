import { planToWgs84 } from "../workspace/coordinates.js";
import { numeric } from "./screen-model.js";

export function geographicPoint(point) {
  if (!Array.isArray(point) || point.length < 2) return null;
  const latitude = numeric(point[0]),
    longitude = numeric(point[1]);
  return latitude !== null &&
    longitude !== null &&
    Math.abs(latitude) <= 85.05112878 &&
    Math.abs(longitude) <= 180
    ? [latitude, longitude]
    : null;
}

export function geographicReference(geo) {
  if (!geo || (geo.coordinateSystem && geo.coordinateSystem !== "WGS84"))
    return null;
  const values = Object.fromEntries(
    ["latitude", "longitude", "widthMeters", "heightMeters"].map((key) => [
      key,
      numeric(geo[key]),
    ]),
  );
  if (
    Object.values(values).some((value) => value === null) ||
    Math.abs(values.latitude) > 80 ||
    Math.abs(values.longitude) > 180 ||
    values.widthMeters < 100 ||
    values.widthMeters > 20000 ||
    values.heightMeters < 100 ||
    values.heightMeters > 20000
  )
    return null;
  const corners = [
    [0, 0],
    [1000, 700],
  ].map((point) => planToWgs84(point, values));
  return corners.every(geographicPoint) ? values : null;
}

function localPoint(point, geo) {
  if (!geo || !Array.isArray(point)) return null;
  const x = numeric(point[0]),
    y = numeric(point[1]);
  if (x === null || y === null || x < 0 || x > 1000 || y < 0 || y > 700)
    return null;
  return geographicPoint(planToWgs84([x, y], geo));
}

function ring(boundary, convert) {
  if (!Array.isArray(boundary) || boundary.length < 3) return null;
  const points = boundary.map(convert);
  // Reject the entire boundary: dropping an invalid vertex invents a different parcel.
  if (
    points.some((point) => !point) ||
    new Set(points.map((point) => point.join(","))).size < 3
  )
    return null;
  const [lat, lon] = points[0];
  const area = points.reduce((sum, point, index) => {
    const next = points[(index + 1) % points.length];
    return (
      sum +
      (point[0] - lat) * (next[1] - lon) -
      (next[0] - lat) * (point[1] - lon)
    );
  }, 0);
  return Math.abs(area) > 1e-14 ? points : null;
}

export function satelliteGeometry(workspace, config, parcels = []) {
  const geo = geographicReference(config),
    sourcePlots = workspace?.plots || [];
  const plotIds = new Set(sourcePlots.map((plot) => plot.id));
  const shapes = (Array.isArray(parcels) ? parcels : []).flatMap((parcel) => {
    if (!plotIds.has(parcel.plotId)) return [];
    const boundary = ring(parcel.boundary, geographicPoint);
    return boundary ? [{ ...parcel, boundary, estimated: true }] : [];
  });
  const represented = new Set(shapes.map((shape) => shape.plotId));
  for (const plot of sourcePlots) {
    if (represented.has(plot.id)) continue;
    const boundary = ring(plot.boundary, (point) => localPoint(point, geo));
    if (boundary) {
      shapes.push({ ...plot, plotId: plot.id, boundary, estimated: false });
      represented.add(plot.id);
    }
  }
  const devices = (workspace?.devices || []).flatMap((device) => {
    const position =
      device.locationMode === "WGS84"
        ? geographicPoint([device.latitude, device.longitude])
        : localPoint([device.planX, device.planY], geo);
    return position ? [{ ...device, position }] : [];
  });
  const locations = shapes
    .flatMap((shape) => shape.boundary)
    .concat(devices.map((device) => device.position));
  const bounds = locations.length
    ? locations
    : geo
      ? [
          [0, 0],
          [1000, 700],
        ].map((point) => planToWgs84(point, geo))
      : [];
  return {
    shapes,
    devices,
    bounds,
    locationKey: JSON.stringify(bounds),
    missingPlots: sourcePlots.length - represented.size,
    missingDevices: (workspace?.devices?.length || 0) - devices.length,
  };
}
