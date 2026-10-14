// Marker metadata comes only from the current, authorized farm geometry.
export function sceneMarkerIndex(geometry, zones, devices, zoneStates) {
  const result = new Map();
  const deviceStates = new Map(devices.map((item) => [String(item.id), item]));
  const waterStates = new Map(
    zoneStates.map((item) => [String(item.id), item]),
  );
  for (const item of geometry.shapes) {
    const id = String(item.id || item.plotId);
    result.set(`parcel:${id}`, {
      id,
      kind: "parcel",
      name: item.name,
      label: "",
    });
  }
  for (const item of geometry.devices) {
    const id = String(item.id),
      state = deviceStates.get(id);
    result.set(`device:${id}`, {
      id,
      kind: "device",
      name: item.name,
      label: state?.label || "",
      running: state?.running === true,
    });
  }
  for (const item of zones) {
    const id = String(item.id),
      state = waterStates.get(id);
    result.set(`zone:${id}`, {
      id,
      kind: "zone",
      name: item.name,
      label: state?.running ? "分区供水中" : "模拟管网",
      running: state?.running === true,
    });
  }
  return result;
}

const clamp = (value, min, max) =>
  Math.min(Math.max(value, min), Math.max(min, max));
const overlaps = (a, b) =>
  Math.abs(a.x - b.x) < (a.width + b.width) / 2 + 4 &&
  Math.abs(a.y - b.y) < (a.height + b.height) / 2 + 4;

// Preserve every visible parcel. Crowded secondary POIs may yield to parcel numbers,
// but never consume a shared label limit or displace the selected device.
export function layoutSceneMarkers(
  points,
  index,
  viewport,
  selectedDevice = "",
  previous = new Map(),
) {
  const width = Math.max(1, viewport.width),
    height = Math.max(1, viewport.height);
  const candidates = [],
    seen = new Set();
  for (const point of points) {
    const key = `${point?.kind}:${point?.id}`,
      item = index.get(key);
    if (
      !item ||
      seen.has(key) ||
      point.visible !== true ||
      !Number.isFinite(point.x) ||
      !Number.isFinite(point.y) ||
      point.x < 0 ||
      point.x > 1 ||
      point.y < 0 ||
      point.y > 1
    )
      continue;
    seen.add(key);
    candidates.push({
      ...item,
      key,
      anchorX: point.x * width,
      anchorY: point.y * height,
    });
  }
  const priority = (item) =>
    item.kind === "parcel"
      ? 3
      : item.kind === "device" && item.id === selectedDevice
        ? 2
        : item.running
          ? 1
          : 0;
  candidates.sort(
    (a, b) => priority(b) - priority(a) || a.key.localeCompare(b.key),
  );
  const placed = [];
  let secondaryCount = 0;
  const secondaryLimit = width < 700 ? 3 : 6;
  for (const item of candidates) {
    if (
      item.kind !== "parcel" &&
      item.id !== selectedDevice &&
      secondaryCount >= secondaryLimit
    )
      continue;
    const textWidth = Array.from(item.name || "").reduce(
      (sum, char) => sum + (char.charCodeAt(0) > 255 ? 12 : 7),
      0,
    );
    const boxWidth = Math.min(
      width - 16,
      Math.max(
        item.kind === "parcel" ? 58 : 110,
        Math.min(176, textWidth + 22),
      ),
    );
    const boxHeight = item.label ? 46 : 30;
    const position = (dx, dy) => ({
      ...item,
      width: Math.max(1, boxWidth),
      height: boxHeight,
      x: clamp(item.anchorX + dx, boxWidth / 2 + 8, width - boxWidth / 2 - 8),
      y: clamp(
        item.anchorY + dy,
        boxHeight / 2 + 8,
        height - boxHeight / 2 - 8,
      ),
      offsetX: dx,
      offsetY: dy,
    });
    const offsets = [[0, 0]];
    const prior = previous.get(item.key);
    if (prior) offsets.unshift([prior.offsetX, prior.offsetY]);
    const rings = item.kind === "parcel" ? 6 : 2;
    for (let ring = 1; ring <= rings; ring += 1) {
      const y = ring * (boxHeight + 5),
        x = ring * (boxWidth + 5);
      offsets.push(
        [0, -y],
        [0, y],
        [-x, 0],
        [x, 0],
        [-x, -y],
        [x, -y],
        [-x, y],
        [x, y],
      );
    }
    let label = offsets
      .map(([x, y]) => position(x, y))
      .find(
        (next) =>
          ![...placed, ...(viewport.obstacles || [])].some((other) =>
            overlaps(next, other),
          ),
      );
    if (!label && item.kind !== "parcel" && item.id !== selectedDevice)
      continue;
    // Very crowded/narrow views retain the parcel and its full name in the guide.
    label ||= position(0, 0);
    label.displaced =
      Math.abs(label.x - item.anchorX) > 3 ||
      Math.abs(label.y - item.anchorY) > 3;
    placed.push(label);
    if (item.kind !== "parcel") secondaryCount += 1;
  }
  previous.clear();
  for (const item of placed)
    previous.set(item.key, { offsetX: item.offsetX, offsetY: item.offsetY });
  return placed;
}
