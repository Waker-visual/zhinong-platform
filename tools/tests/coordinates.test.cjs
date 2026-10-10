const { test } = require("node:test");
const assert = require("node:assert/strict");

test("image-traced WGS84 parcels and legacy device points retain their map positions", async () => {
  const { readFileSync } = require("node:fs");
  const { planToWgs84, wgs84ToPlan } = await import("../../frontend/src/workspace/coordinates.js");
  const { containsPoint, deviceCoordinates, routePrefix } = await import("../../frontend/src/workspace/fieldSpatial.js");
  const fixture = JSON.parse(readFileSync(require("node:path").join(__dirname, "../../backend/src/main/resources/farm-map-estimates.json"), "utf8"));
  for (const latitude of [47.26, 47.265]) {
    const geo = { latitude, longitude: 132.73, widthMeters: 1600, heightMeters: 1120 };
    for (const parcel of fixture.parcels) for (const point of parcel.boundary) {
      const restored = planToWgs84(wgs84ToPlan(...point, geo), geo);
      assert.ok(Math.abs(restored[0] - point[0]) < 1e-10);
      assert.ok(Math.abs(restored[1] - point[1]) < 1e-10);
    }
    const ring = fixture.parcels[0].boundary;
    const center = [0, 1].map(i => ring.reduce((sum, p) => sum + p[i], 0) / ring.length);
    const plan = wgs84ToPlan(...center, geo);
    assert.ok(containsPoint(ring, deviceCoordinates({ locationMode: "LOCAL_PLAN", planX: plan[0], planY: plan[1] }, geo)));
    assert.equal(containsPoint(ring, null), false);
  }
  const points = [[0, 0], [0, 2], [1, 2]];
  assert.deepEqual(routePrefix(points, 50), [[0, 0], [0, 1.5]]);
  assert.deepEqual(routePrefix(points, 100), points);
});

test("map projection round-trips all layout corners and center", async () => {
  const { planToWgs84, wgs84ToPlan } = await import(
    "../../frontend/src/workspace/coordinates.js"
  );
  for (const latitude of [0, 47.26, -55, 79]) {
    const geo = {
      latitude,
      longitude: 25,
      widthMeters: 2000,
      heightMeters: 1300,
    };
    for (const point of [
      [0, 0],
      [1000, 700],
      [1000, 0],
      [0, 700],
      [500, 350],
    ]) {
      const [lat, lon] = planToWgs84(point, geo);
      const roundTrip = wgs84ToPlan(lat, lon, geo);
      assert.ok(Math.abs(roundTrip[0] - point[0]) < 1e-6);
      assert.ok(Math.abs(roundTrip[1] - point[1]) < 1e-6);
    }
  }
});

test("WGS84 installation does not drift when the plan reference changes; zero is a valid point", async () => {
  const { assetPlanPoint, planToWgs84 } = await import(
    "../../frontend/src/workspace/coordinates.js"
  );
  const asset = {
    locationMode: "WGS84",
    latitude: 47.2604,
    longitude: 132.7303,
  };
  for (const geo of [
    {
      latitude: 47.26,
      longitude: 132.73,
      widthMeters: 1600,
      heightMeters: 1120,
    },
    {
      latitude: 47.27,
      longitude: 132.74,
      widthMeters: 3200,
      heightMeters: 2200,
    },
  ]) {
    const actual = planToWgs84(assetPlanPoint(asset, geo), geo);
    assert.ok(Math.abs(actual[0] - asset.latitude) < 1e-9);
    assert.ok(Math.abs(actual[1] - asset.longitude) < 1e-9);
  }
  assert.deepEqual(assetPlanPoint({ planX: 0, planY: 0 }, {}), [0, 0]);
  assert.equal(assetPlanPoint({ planX: null, planY: null }, {}), null);
});
