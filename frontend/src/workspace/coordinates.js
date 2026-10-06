// Esri / OSM use WGS84 geographic positions. No GCJ-02 conversion belongs here.
export function extent(geo) {
  const dy = geo.heightMeters / 111320;
  const dx =
    geo.widthMeters / (111320 * Math.cos((geo.latitude * Math.PI) / 180));
  return { west: geo.longitude - dx / 2, north: geo.latitude + dy / 2, dx, dy };
}
export function planToWgs84(point, geo) {
  const b = extent(geo);
  return [b.north - (point[1] / 700) * b.dy, b.west + (point[0] / 1000) * b.dx];
}
export function wgs84ToPlan(latitude, longitude, geo) {
  const b = extent(geo);
  return [
    ((longitude - b.west) / b.dx) * 1000,
    ((b.north - latitude) / b.dy) * 700,
  ];
}
export function assetPlanPoint(asset, geo) {
  if (asset.locationMode === "WGS84") {
    return asset.latitude == null || asset.longitude == null
      ? null
      : wgs84ToPlan(Number(asset.latitude), Number(asset.longitude), geo);
  }
  return asset.planX == null || asset.planY == null
    ? null
    : [Number(asset.planX), Number(asset.planY)];
}
