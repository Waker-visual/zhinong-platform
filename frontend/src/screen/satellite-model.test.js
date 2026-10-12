import test from "node:test";
import assert from "node:assert/strict";
import {
  geographicPoint,
  geographicReference,
  satelliteGeometry,
} from "./satellite-model.js";

const geo = {
  latitude: 47.26,
  longitude: 132.73,
  widthMeters: 1600,
  heightMeters: 1120,
};
const plot = {
  id: "plot-a",
  boundary: [
    [0, 0],
    [1000, 0],
    [0, 700],
  ],
};

test("satellite layout projects local coordinates using the saved WGS84 reference", () => {
  const result = satelliteGeometry(
    { plots: [plot], devices: [{ id: "local", planX: 500, planY: 350 }] },
    geo,
  );
  assert.deepEqual(result.devices[0].position, [47.26, 132.73]);
  assert.ok(result.shapes[0].boundary[0][0] > geo.latitude);
  assert.ok(result.shapes[0].boundary[0][1] < geo.longitude);
  assert.equal(result.missingPlots, 0);
});

test("saved parcel boundaries take precedence and unrelated plot associations are ignored", () => {
  const boundary = [
    [47.26, 132.73],
    [47.27, 132.73],
    [47.26, 132.74],
  ];
  const result = satelliteGeometry({ plots: [plot] }, geo, [
    { id: "parcel", plotId: plot.id, boundary },
    { id: "other", plotId: "other-farm-plot", boundary },
  ]);
  assert.equal(result.shapes.length, 1);
  assert.deepEqual(result.shapes[0].boundary, boundary);
  assert.equal(result.shapes[0].estimated, true);
});

test("invalid references never place local plots or devices at an invented location", () => {
  for (const bad of [
    null,
    { ...geo, latitude: null },
    { ...geo, widthMeters: 0 },
    { ...geo, latitude: 89 },
    { ...geo, longitude: 180 },
    { ...geo, coordinateSystem: "GCJ02" },
  ]) {
    assert.equal(geographicReference(bad), null);
    const result = satelliteGeometry(
      { plots: [plot], devices: [{ planX: 0, planY: 0 }] },
      bad,
    );
    assert.equal(result.bounds.length, 0);
    assert.equal(result.missingPlots, 1);
    assert.equal(result.missingDevices, 1);
  }
});

test("valid GPS positions remain independent of the farm layout while missing and out-of-range coordinates stay hidden", () => {
  assert.deepEqual(geographicPoint([0, 0]), [0, 0]);
  for (const invalid of [
    [null, 0],
    ["", 0],
    [false, 0],
    [NaN, 1],
    [86, 1],
    [0, 181],
  ])
    assert.equal(geographicPoint(invalid), null);
  const result = satelliteGeometry(
    {
      devices: [
        { id: "gps", locationMode: "WGS84", latitude: 31, longitude: 121 },
        {
          id: "missing",
          locationMode: "WGS84",
          latitude: null,
          longitude: 121,
        },
        { id: "outside", planX: 1001, planY: 0 },
      ],
    },
    null,
  );
  assert.deepEqual(
    result.devices.map((device) => device.position),
    [[31, 121]],
  );
  assert.equal(result.missingDevices, 2);
});

test("malformed and degenerate rings are rejected rather than connected across missing vertices", () => {
  for (const boundary of [
    [
      [0, 0],
      [500, 350],
      [1000, 700],
    ],
    [
      [0, 0],
      [0, 0],
      [0, 700],
    ],
    [
      [0, 0],
      [1000, 0],
      [null, 350],
      [0, 700],
    ],
  ]) {
    const result = satelliteGeometry({ plots: [{ ...plot, boundary }] }, geo);
    assert.equal(result.shapes.length, 0);
    assert.equal(result.missingPlots, 1);
  }
});

test("the initial view follows saved GPS geometry instead of a distant default reference", () => {
  const boundary = [
    [31, 121],
    [31.001, 121],
    [31, 121.001],
  ];
  const result = satelliteGeometry(
    {
      plots: [plot],
      devices: [
        {
          id: "gps",
          locationMode: "WGS84",
          latitude: 31.002,
          longitude: 121.002,
        },
      ],
    },
    geo,
    [{ id: "parcel", plotId: plot.id, boundary }],
  );
  assert.deepEqual(result.bounds, [...boundary, [31.002, 121.002]]);
  const empty = satelliteGeometry({ plots: [], devices: [] }, geo);
  assert.equal(empty.bounds.length, 2);
  assert.ok(
    empty.bounds.every((point) => Math.abs(point[0] - geo.latitude) < 0.01),
  );
  assert.notEqual(result.locationKey, empty.locationKey);
});
