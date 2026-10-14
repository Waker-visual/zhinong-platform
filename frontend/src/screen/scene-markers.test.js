import test from "node:test";
import assert from "node:assert/strict";
import { layoutSceneMarkers, sceneMarkerIndex } from "./scene-markers.js";

const parcels = Array.from({ length: 9 }, (_, i) => ({
  id: `p${i}`,
  name: `A-0${i + 1}`,
}));
const geometry = { shapes: parcels, devices: [{ id: "d1", name: "虫情监测" }] };
const index = sceneMarkerIndex(
  geometry,
  [],
  [{ id: "d1", label: "正常上报" }],
  [],
);
const points = parcels.map((item) => ({
  id: item.id,
  kind: "parcel",
  x: 0.5,
  y: 0.5,
  visible: true,
}));
const viewport = { width: 1000, height: 700 };

test("parcel labels move clear of the guide panel while preserving their anchors", () => {
  const panel = { x: 860, y: 210, width: 240, height: 380 };
  const [label] = layoutSceneMarkers(
    [{ ...points[0], x: 0.84, y: 0.35 }],
    index,
    { ...viewport, obstacles: [panel] },
  );
  assert.equal(label.anchorX, 840);
  assert.ok(Math.abs(label.anchorY - 245) < 1e-9);
  assert.ok(
    Math.abs(label.x - panel.x) >= (label.width + panel.width) / 2 + 4 ||
      Math.abs(label.y - panel.y) >= (label.height + panel.height) / 2 + 4,
  );
  assert.equal(label.displaced, true);
});

test("all nine parcel numbers remain visible even when their anchors coincide", () => {
  const labels = layoutSceneMarkers(points, index, viewport);
  assert.equal(labels.length, 9);
  assert.equal(new Set(labels.map((item) => `${item.x}:${item.y}`)).size, 9);
  assert.ok(labels.some((item) => item.displaced));
  assert.deepEqual(
    new Set(labels.map((item) => item.id)),
    new Set(parcels.map((item) => item.id)),
  );
});

test("edge labels remain inside the viewport and keep their true anchors for leader lines", () => {
  const labels = layoutSceneMarkers(
    [
      { ...points[0], x: 0.001, y: 0.001 },
      { ...points[1], x: 0.999, y: 0.999 },
    ],
    index,
    viewport,
  );
  for (const label of labels) {
    assert.ok(label.x - label.width / 2 >= 8);
    assert.ok(label.x + label.width / 2 <= viewport.width - 8);
    assert.ok(label.y - label.height / 2 >= 8);
    assert.ok(label.y + label.height / 2 <= viewport.height - 8);
    assert.equal(label.displaced, true);
  }
  assert.equal(labels[0].anchorX, 1);
});

test("projection messages cannot introduce a different farm or invalid marker coordinates", () => {
  assert.deepEqual(
    layoutSceneMarkers(
      [
        { ...points[0], id: "outside-farm" },
        { ...points[0], x: NaN },
        { ...points[0], x: -0.1 },
        { ...points[0], visible: false },
        { ...points[0], kind: "other" },
      ],
      index,
      viewport,
    ),
    [],
  );
  assert.equal(
    layoutSceneMarkers([points[0], points[0]], index, viewport).length,
    1,
  );
});

test("selected device remains available without consuming parcel label slots", () => {
  const labels = layoutSceneMarkers(
    [
      ...points,
      {
        id: "d1",
        kind: "device",
        x: 0.5,
        y: 0.5,
        visible: true,
      },
    ],
    index,
    viewport,
    "d1",
  );
  assert.equal(labels.filter((item) => item.kind === "parcel").length, 9);
  assert.equal(labels.find((item) => item.id === "d1")?.label, "正常上报");
});

test("small camera movements retain collision offsets instead of making numbers swap slots", () => {
  const previous = new Map();
  const before = layoutSceneMarkers(points, index, viewport, "", previous);
  const after = layoutSceneMarkers(
    points.map((point) => ({ ...point, x: point.x + 0.002 })),
    index,
    viewport,
    "",
    previous,
  );
  for (let i = 0; i < before.length; i += 1) {
    assert.equal(after[i].x - before[i].x, 2);
    assert.equal(after[i].y, before[i].y);
  }
});

test("secondary device labels do not crowd out parcels and selected devices remain reachable", () => {
  const devices = Array.from({ length: 12 }, (_, i) => ({
    id: `d${i}`,
    name: `设备${i}`,
  }));
  const crowdedIndex = sceneMarkerIndex(
    { shapes: parcels, devices },
    [],
    [],
    [],
  );
  const labels = layoutSceneMarkers(
    [
      ...points,
      ...devices.map((device, i) => ({
        id: device.id,
        kind: "device",
        visible: true,
        x: ((i % 6) + 0.5) / 6,
        y: i < 6 ? 0.15 : 0.85,
      })),
    ],
    crowdedIndex,
    viewport,
    "d11",
  );
  assert.equal(labels.filter((item) => item.kind === "parcel").length, 9);
  assert.ok(labels.filter((item) => item.kind === "device").length <= 6);
  assert.ok(labels.some((item) => item.id === "d11"));
});
