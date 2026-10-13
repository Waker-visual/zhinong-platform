import test from "node:test";
import assert from "node:assert/strict";
import {
  sceneGeometry,
  sceneDeviceState,
  sceneZoneStates,
} from "./scene-model.js";

test("world dimensions retain physical metres and do not normalize different farms", () => {
  const farm = (size) =>
    sceneGeometry({
      shapes: [
        {
          plotId: "a",
          boundary: [
            [0, 0],
            [0, size],
            [size, size],
            [size, 0],
          ],
        },
      ],
      devices: [],
    });
  const a = farm(0.001),
    b = farm(0.002);
  assert.ok(Math.abs(a.width - 111.31949) < 0.01);
  assert.ok(Math.abs(a.depth - 111.31949) < 0.01);
  assert.ok(Math.abs(b.width / a.width - 2) < 0.00001);
});

test("device activity requires fresh feedback, preserves zero and expires locally", () => {
  const now = Date.parse("2026-10-13T10:00:00Z");
  const stamp = new Date(now).toISOString();
  const device = {
    id: "pump",
    deviceType: "PUMP",
    lifecycle: "ACTIVE",
    freshness: "FRESH",
    lastReceivedAt: stamp,
    channels: [
      {
        metric: "PUMP_RUNNING",
        freshness: "FRESH",
        latest: { value: 0, time: stamp, receivedAt: stamp },
      },
    ],
  };
  assert.equal(sceneDeviceState(device, now).label, "已停止");
  device.channels[0].latest.value = 1;
  assert.equal(sceneDeviceState(device, now).running, true);
  assert.equal(sceneDeviceState(device, now + 181000).known, false);
  device.channels[0].latest.value = null;
  assert.equal(sceneDeviceState(device, now).known, false);
  device.channels[0].latest.value = 1;
  device.channels[0].freshness = "STALE";
  assert.equal(sceneDeviceState(device, now).running, false);
  device.channels[0].freshness = "FRESH";
  device.lifecycle = "DISABLED";
  assert.equal(sceneDeviceState(device, now).running, false);
});

test("crop appearance follows current authorized plot metadata and never guesses missing crops", () => {
  const geometry = {
    shapes: ["rice", "veg", "corn", "empty"].map((id) => ({
      plotId: id,
      boundary: [
        [32, 119],
        [32, 119.01],
        [32.01, 119.01],
      ],
    })),
    devices: [],
  };
  const records = [
    { id: "rice", crop: "水稻", privateNote: "not exported" },
    { id: "veg", crop: "蔬菜" },
    { id: "corn", crop: "玉米" },
    { id: "outside", crop: "小麦" },
  ];
  const result = sceneGeometry(geometry, records);
  assert.deepEqual(
    result.plots.map((p) => p.crop),
    ["rice", "vegetable", "corn", "unknown"],
  );
  assert.equal(JSON.stringify(result).includes("not exported"), false);
  assert.equal(
    result.plots.some((p) => p.id === "outside"),
    false,
  );
});

test("an empty farm clears both parcels and devices", () => {
  assert.deepEqual(
    sceneGeometry({ shapes: [], devices: [{ position: [30, 120] }] }),
    { plots: [], devices: [] },
  );
});
test("scene shares a WGS84 origin for imagery without forwarding private business fields", () => {
  const geometry = {
    shapes: [
      {
        plotId: "a",
        name: "private label",
        boundary: [
          [30, 120],
          [30, 120.01],
          [30.01, 120.01],
          [30.01, 120],
        ],
      },
    ],
    devices: [
      { position: [30, 120], deviceKey: "must-not-forward", alertCount: 1 },
    ],
  };
  const scene = sceneGeometry(geometry);
  assert.ok(Math.abs(scene.originLatitude - 30.005) < 1e-10);
  assert.ok(Math.abs(scene.originLongitude - 120.005) < 1e-10);
  assert.equal(scene.plots[0].id, "a");
  assert.equal(scene.plots[0].points.length, 4);
  assert.equal(scene.devices[0].x, scene.plots[0].points[0].x);
  assert.equal(scene.devices[0].z, scene.plots[0].points[0].z);
  assert.equal(scene.devices[0].alert, true);
  assert.ok(scene.plots[0].points[1].x > scene.plots[0].points[0].x);
  assert.ok(scene.plots[0].points[2].z > scene.plots[0].points[1].z);
  assert.ok(!JSON.stringify(scene).includes("private label"));
  assert.ok(!JSON.stringify(scene).includes("must-not-forward"));
});

test("satellite and parcel positions round-trip through the same metre origin", () => {
  const ring = [
    [47.251, 132.737],
    [47.251, 132.739],
    [47.254, 132.739],
    [47.254, 132.737],
  ];
  const scene = sceneGeometry({
    shapes: [{ plotId: "field", boundary: ring }],
    devices: [],
  });
  const degree = (Math.PI * 6378137) / 180;
  for (const [index, point] of scene.plots[0].points.entries()) {
    const lat = scene.originLatitude + point.z / degree;
    const lon =
      scene.originLongitude +
      point.x / (degree * Math.cos((scene.originLatitude * Math.PI) / 180));
    assert.ok(Math.abs(lat - ring[index][0]) < 1e-10);
    assert.ok(Math.abs(lon - ring[index][1]) < 1e-10);
  }
});

test("map pipe vertices, outlets and parcels share one coordinate transform; unrelated zones are excluded", () => {
  const geometry = {
    shapes: [
      {
        id: "parcel",
        plotId: "a",
        boundary: [
          [32, 119],
          [32, 119.001],
          [32.001, 119.001],
        ],
      },
    ],
    devices: [{ id: "pump", position: [32, 119], deviceType: "PUMP" }],
  };
  const zone = {
    id: "zone",
    parcelId: "parcel",
    plotId: "a",
    pumpId: "pump",
    pipeline: [
      [32, 119],
      [32, 119.001],
    ],
    nodes: [{ kind: "OUTLET", point: [32, 119.001] }],
  };
  const result = sceneGeometry(
    geometry,
    [],
    [zone, { ...zone, id: "foreign", parcelId: "other" }],
  );
  assert.equal(result.plots[0].parcelId, "parcel");
  assert.equal(result.zones.length, 1);
  assert.deepEqual(result.zones[0].points[0], {
    x: result.devices[0].x,
    z: result.devices[0].z,
  });
  assert.deepEqual(result.zones[0].points[1], result.plots[0].points[1]);
  assert.equal(result.zones[0].nodes[0].x, result.plots[0].points[1].x);
  assert.equal(
    sceneGeometry(
      geometry,
      [],
      [
        {
          ...zone,
          pipeline: [
            [32, 119],
            [null, 119],
          ],
        },
      ],
    ).zones.length,
    0,
  );
});

test("a running pump alone never animates every irrigation zone", () => {
  const stamp = "2026-10-13T10:00:00Z",
    now = Date.parse(stamp);
  const zones = [
    { id: "z1", pumpId: "p", parcelId: "a" },
    { id: "z2", pumpId: "p", parcelId: "b" },
  ];
  const devices = [{ id: "p", known: true, running: true }];
  const jobs = [
    { deviceId: "p", parcelId: "a", kind: "IRRIGATION", status: "RUNNING" },
  ];
  assert.deepEqual(
    sceneZoneStates(zones, jobs, devices, stamp, now).map((z) => z.running),
    [true, false],
  );
  assert.equal(
    sceneZoneStates(zones, [], devices, stamp, now)[0].running,
    false,
  );
  assert.equal(
    sceneZoneStates(zones, jobs, devices, stamp, now + 76000)[0].running,
    false,
  );
  assert.equal(
    sceneZoneStates(
      zones,
      jobs,
      [{ id: "p", known: false, running: false }],
      stamp,
      now,
    )[0].running,
    false,
  );
});
