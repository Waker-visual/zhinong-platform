// Fetch each independent panel without allowing a map failure to discard readings.
export async function loadLandscapeData(request, farmId, signal) {
  const base = `/farms/${encodeURIComponent(farmId)}`;
  const keys = ["workspace", "geo", "spatial"];
  const paths = ["/workspace?days=30", "/map-config", "/field-map"];
  const results = await Promise.allSettled(
    paths.map((path) => request(base + path, "GET", undefined, { signal })),
  );
  return Object.fromEntries(keys.map((key, index) => [key, results[index]]));
}
