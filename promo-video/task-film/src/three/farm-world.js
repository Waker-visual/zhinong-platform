import * as THREE from "three";
import { PALETTE } from "./palette.js";

// 片子专用分支：取自 frontend/src/screen/scene/scene-world.js（产品三维农场导览）。
// 去掉交互与 rAF 循环，改为按帧号同步渲染；场景与产品保持同一套模型、配色和布局。
// Display coordinates are illustrative. Business data and geographic truth stay in the model.
export function createFarmWorld({
  canvas,
  model,
  width: fixedWidth = 1920,
  height: fixedHeight = 1080,
  reducedMotion = false,
  onSelect,
  onProject,
  onState,
}) {
  const palette = PALETTE;
  const geometries = new Set(),
    materials = new Set(),
    textures = new Set(),
    disposables = [];
  const geometryCache = new Map(),
    materialCache = new Map(),
    picks = [],
    anchors = new Map();
  const activePointers = new Set();
  const scene = new THREE.Scene(),
    farm = new THREE.Group(),
    plots = new THREE.Group();
  const camera = new THREE.PerspectiveCamera(43, 1, 0.5, 420);
  const raycaster = new THREE.Raycaster(),
    pointer = new THREE.Vector2(),
    projected = new THREE.Vector3();
  const dummy = new THREE.Object3D(),
    center = new THREE.Vector3(3, 0, -7);
  const overview = new THREE.Vector3(61, 51, 69);
  let renderer,
    controls,
    observer,
    raf = 0,
    disposed = false,
    lost = false,
    ready = false;
  let width = 1,
    height = 1,
    lastFrame = -Infinity,
    lastClock = 0,
    clock = 0,
    motion = !reducedMotion;
  let tour = false,
    tween = null,
    selected = null,
    currentModel = null,
    pointerStart = null;
  let sun,
    ambient,
    water,
    windRotor,
    selection,
    dirty = true,
    projectionDirty = true,
    theme = "day";
  const state = () => onState?.({ tour, ready, lost });
  const geo = (key, make) => {
    if (!geometryCache.has(key)) {
      const value = make();
      geometries.add(value);
      geometryCache.set(key, value);
    }
    return geometryCache.get(key);
  };
  const mat = (color, options = {}) => {
    const key = `${color}:${JSON.stringify(options)}`;
    if (!materialCache.has(key)) {
      const value = new THREE.MeshStandardMaterial({
        color,
        roughness: 0.88,
        ...options,
      });
      materials.add(value);
      materialCache.set(key, value);
    }
    return materialCache.get(key);
  };
  const box = () => geo("box", () => new THREE.BoxGeometry(1, 1, 1));
  const cylinder = () =>
    geo("cylinder", () => new THREE.CylinderGeometry(1, 1, 1, 18));
  const cone = () => geo("cone", () => new THREE.ConeGeometry(1, 1, 14));
  const sphere = () => geo("sphere", () => new THREE.IcosahedronGeometry(1, 1));
  const mesh = (
    geometry,
    material,
    position,
    scale = [1, 1, 1],
    parent = farm,
  ) => {
    const value = new THREE.Mesh(geometry, material);
    value.position.set(...position);
    value.scale.set(...scale);
    value.castShadow = true;
    value.receiveShadow = true;
    parent.add(value);
    return value;
  };
  const block = (color, position, scale, parent = farm) =>
    mesh(box(), mat(color), position, scale, parent);
  const random = (seed) => {
    let n = seed >>> 0;
    return () => (n = (1664525 * n + 1013904223) >>> 0) / 4294967296;
  };
  const listen = (target, event, fn, options) => {
    target.addEventListener(event, fn, options);
    disposables.push(() => target.removeEventListener(event, fn, options));
  };
  function instanced(
    geometry,
    material,
    transforms,
    parent = farm,
    castShadow = true,
  ) {
    const value = new THREE.InstancedMesh(
      geometry,
      material,
      transforms.length,
    );
    transforms.forEach((item, i) => {
      dummy.position.set(...item.position);
      dummy.rotation.set(0, item.rotation || 0, 0);
      dummy.scale.set(...(item.scale || [1, 1, 1]));
      dummy.updateMatrix();
      value.setMatrixAt(i, dummy.matrix);
      if (item.color) value.setColorAt(i, new THREE.Color(item.color));
    });
    value.instanceMatrix.needsUpdate = true;
    if (value.instanceColor) value.instanceColor.needsUpdate = true;
    value.castShadow = castShadow;
    value.receiveShadow = true;
    parent.add(value);
    return value;
  }
  function groundTexture() {
    const surface = document.createElement("canvas");
    surface.width = surface.height = 128;
    const context = surface.getContext("2d");
    if (!context) return null;
    const noise = random(71);
    context.fillStyle = palette.grassBase;
    context.fillRect(0, 0, 128, 128);
    for (let i = 0; i < 3400; i++) {
      context.fillStyle = [
        palette.grassShade,
        palette.grassLight,
        palette.grassDry,
        palette.grassDeep,
      ][i % 4];
      context.fillRect(
        noise() * 128,
        noise() * 128,
        1 + noise() * 3,
        1 + noise() * 3,
      );
    }
    const value = new THREE.CanvasTexture(surface);
    value.colorSpace = THREE.SRGBColorSpace;
    value.wrapS = value.wrapT = THREE.RepeatWrapping;
    value.repeat.set(36, 36);
    value.anisotropy = Math.min(4, renderer.capabilities.getMaxAnisotropy());
    textures.add(value);
    return value;
  }
  function terrain() {
    const groundMaterial = mat(palette.materialNeutral, {
      map: groundTexture(),
    });
    mesh(box(), groundMaterial, [0, -0.55, 0], [380, 1, 380]).castShadow =
      false;
    const rng = random(310),
      hills = [],
      trunks = [],
      crowns = [],
      pines = [];
    for (let i = 0; i < 27; i++) {
      const angle = (i / 27) * Math.PI * 2,
        radius = 105 + rng() * 30;
      hills.push({
        position: [Math.cos(angle) * radius, -5, Math.sin(angle) * radius],
        scale: [22 + rng() * 24, 8 + rng() * 8, 21 + rng() * 24],
      });
    }
    instanced(
      geo("hill", () => new THREE.IcosahedronGeometry(1, 2)),
      mat(palette.hill, { flatShading: true }),
      hills,
      farm,
      false,
    );
    for (let i = 0; i < 155; i++) {
      const angle = (i / 155) * Math.PI * 2,
        radius = 62 + rng() * 23;
      const x = Math.cos(angle) * radius,
        z = Math.sin(angle) * radius - 9;
      if (z > 20 && x > -48 && x < 54) continue;
      const size = 0.8 + rng() * 1.2;
      trunks.push({
        position: [x, size * 1.9, z],
        scale: [size * 0.22, size * 3.8, size * 0.22],
      });
      const destination = i % 3 === 0 ? pines : crowns;
      destination.push({
        position: [x, size * 4.1, z],
        scale: [size * 1.9, size * (i % 3 === 0 ? 6 : 2.4), size * 1.9],
        color: [
          palette.forestDeep,
          palette.forestPine,
          palette.forestLeaf,
          palette.forestLight,
        ][i % 4],
      });
    }
    instanced(cylinder(), mat(palette.treeBark), trunks);
    instanced(
      sphere(),
      mat(palette.materialNeutral, { flatShading: true }),
      crowns,
    );
    instanced(
      cone(),
      mat(palette.materialNeutral, { flatShading: true }),
      pines,
    );
    // A closer orchard frames the service lane, with visible individual trunks and fruit crowns.
    const orchardTrunks = [],
      orchardCrowns = [];
    for (let row = 0; row < 3; row++)
      for (let col = 0; col < 6; col++) {
        const x = -37 - row * 4.5,
          z = -30 + col * 6;
        orchardTrunks.push({ position: [x, 1.7, z], scale: [0.24, 3.4, 0.24] });
        orchardCrowns.push({
          position: [x, 4.1, z],
          scale: [2.1, 2.5, 2.2],
          color: [
            palette.orchardLeaf,
            palette.orchardLight,
            palette.orchardDeep,
          ][col % 3],
        });
      }
    instanced(cylinder(), mat(palette.orchardBark), orchardTrunks);
    instanced(
      sphere(),
      mat(palette.materialNeutral, { flatShading: true }),
      orchardCrowns,
    );
    // Gravel lanes touch the fields and continue into the landscape.
    block(palette.roadRear, [0, 0.025, -21.8], [66, 0.1, 3.6]);
    block(palette.roadFront, [0, 0.02, 22.2], [67, 0.09, 3.6]);
    block(palette.roadOrchard, [-31, 0.01, 3], [3.4, 0.1, 47]);
    block(palette.roadPond, [30.2, 0.025, 8], [3.5, 0.1, 62]);
    block(palette.yardPaving, [-4, 0.04, -32], [62, 0.12, 17]);
    block(palette.roadFront, [1, 0.03, 43], [3.4, 0.1, 39]);
    const posts = [],
      rails = [];
    for (let x = -31; x <= 30; x += 4) {
      if (Math.abs(x) < 4) continue;
      posts.push({ position: [x, 0.8, 25], scale: [0.17, 1.6, 0.17] });
      rails.push({ position: [x + 1.8, 1.05, 25], scale: [3.7, 0.13, 0.12] });
      rails.push({ position: [x + 1.8, 0.5, 25], scale: [3.7, 0.13, 0.12] });
    }
    instanced(box(), mat(palette.fencePost), posts);
    instanced(box(), mat(palette.fenceRail), rails);
  }
  function barn() {
    const group = new THREE.Group();
    group.position.set(-14, 0, -32);
    farm.add(group);
    const body = block(palette.barnWall, [0, 2.5, 0], [12, 5, 9], group);
    body.userData.selectionId = "yard";
    picks.push(body);
    const gable = geo("gable", () => {
      const shape = new THREE.Shape();
      shape.moveTo(-6, 0);
      shape.lineTo(6, 0);
      shape.lineTo(0, 3);
      shape.closePath();
      const value = new THREE.ExtrudeGeometry(shape, {
        depth: 9,
        bevelEnabled: false,
      });
      value.translate(0, 0, -4.5);
      return value;
    });
    mesh(gable, mat(palette.barnGable), [0, 5, 0], [1, 1, 1], group);
    for (const side of [-1, 1]) {
      const roof = block(
        palette.barnRoof,
        [side * 3.1, 6.55, 0],
        [7.05, 0.22, 10.1],
        group,
      );
      roof.rotation.z = -side * Math.atan(0.5);
      block(
        palette.barnPillar,
        [side * 5.83, 2.6, 4.58],
        [0.24, 5.2, 0.18],
        group,
      );
    }
    block(palette.barnDoor, [0, 2, 4.57], [4.5, 4, 0.2], group);
    block(palette.barnDoorTrim, [0, 2, 4.69], [0.14, 4, 0.12], group);
    for (const x of [-4.3, 4.3]) {
      block(palette.barnWindow, [x, 3, 4.57], [1.65, 1.4, 0.16], group);
      block(palette.barnWindowFrame, [x, 3, 4.7], [0.1, 1.45, 0.09], group);
      block(palette.barnWindowFrame, [x, 3, 4.7], [1.7, 0.1, 0.09], group);
    }
    for (let z = -4; z < 5; z += 1.2) {
      for (const side of [-1, 1]) {
        const seam = block(
          palette.barnRoofSeam,
          [side * 3.1, 6.69, z],
          [6.97, 0.035, 0.045],
          group,
        );
        seam.rotation.z = -side * Math.atan(0.5);
      }
    }
    block(palette.barnSign, [0, 5.6, 4.66], [2.1, 0.55, 0.12], group);
    for (let i = 0; i < 3; i++) {
      const x = 2 + i * 5.3;
      const silo = mesh(
        cylinder(),
        mat(palette.siloBody, { metalness: 0.35, roughness: 0.48 }),
        [x, 4.35, -33],
        [2.15, 8.7, 2.15],
      );
      silo.userData.selectionId = "yard";
      picks.push(silo);
      mesh(
        cone(),
        mat(palette.siloCap, { metalness: 0.35 }),
        [x, 9.5, -33],
        [2.2, 1.8, 2.2],
      );
      const ring = geo(
        "silo-ring",
        () => new THREE.TorusGeometry(2.18, 0.035, 5, 32),
      );
      for (let y = 1; y <= 8; y += 1)
        mesh(ring, mat(palette.siloRing, { metalness: 0.4 }), [
          x,
          y,
          -33,
        ]).rotation.x = Math.PI / 2;
      block(palette.siloHatch, [x, 1.2, -30.82], [0.75, 2.1, 0.15]);
      const legs = [];
      for (let j = 0; j < 8; j++)
        legs.push({
          position: [
            x + Math.cos((j * Math.PI) / 4) * 2.17,
            4.4,
            -33 + Math.sin((j * Math.PI) / 4) * 2.17,
          ],
          scale: [0.045, 8.7, 0.045],
        });
      instanced(box(), mat(palette.siloRib), legs);
    }
  }
  function greenhouses() {
    const frame = geo("greenhouse-arch", () => {
      const path = new THREE.EllipseCurve(0, 1.2, 2.6, 2.5, 0, Math.PI, false);
      return new THREE.TubeGeometry(
        new THREE.CatmullRomCurve3(
          path.getPoints(20).map((p) => new THREE.Vector3(p.x, p.y, 0)),
        ),
        20,
        0.055,
        5,
        false,
      );
    });
    const cover = geo(
      "greenhouse-cover",
      () =>
        new THREE.CylinderGeometry(
          2.58,
          2.58,
          11.8,
          22,
          1,
          true,
          Math.PI / 2,
          Math.PI,
        ),
    );
    for (const x of [20, 27]) {
      const roof = mesh(
        cover,
        mat(palette.greenhouseGlass, {
          transparent: true,
          opacity: 0.42,
          side: THREE.DoubleSide,
          depthWrite: false,
          roughness: 0.3,
        }),
        [x, 1.2, -32],
      );
      roof.rotation.x = Math.PI / 2;
      roof.castShadow = false;
      for (let z = -37.8; z <= -26; z += 1.95)
        mesh(frame, mat(palette.greenhouseArch, { metalness: 0.25 }), [
          x,
          0,
          z,
        ]);
      for (const side of [-1, 1])
        block(
          palette.greenhouseFrame,
          [x + side * 2.6, 0.6, -32],
          [0.06, 1.2, 12],
        );
      block(palette.greenhouseBed, [x, 0.18, -32], [4.6, 0.25, 11.5]);
      for (let j = -1; j <= 1; j++)
        block(
          palette.greenhouseCrop,
          [x + j * 1.3, 0.5, -32],
          [0.55, 0.6, 10.8],
        );
      block(palette.greenhouseDoor, [x, 1.2, -25.98], [0.09, 2.4, 0.09]);
    }
  }
  function utilities() {
    const shore = mesh(
      cylinder(),
      mat(palette.pondShore),
      [40, 0.06, 7],
      [9.8, 0.22, 13.3],
    );
    shore.castShadow = false;
    water = mesh(
      cylinder(),
      mat(palette.pondWater, { metalness: 0.3, roughness: 0.25 }),
      [40, 0.21, 7],
      [8.9, 0.12, 12.4],
    );
    water.castShadow = false;
    const ripples = [];
    for (let i = 0; i < 16; i++)
      ripples.push({
        position: [34 + (i % 4) * 3.5, 0.283, -1 + Math.floor(i / 4) * 4.6],
        scale: [1.2 + (i % 3), 0.015, 0.07],
        rotation: -0.18,
      });
    instanced(
      box(),
      mat(palette.pondRipple, { transparent: true, opacity: 0.42 }),
      ripples,
      farm,
      false,
    );
    block(palette.pumpBase, [32.6, 0.34, 9], [3.6, 0.45, 3.6]);
    block(palette.pumpBody, [33, 1, 9], [1.4, 1.1, 1.25]);
    mesh(
      cylinder(),
      mat(palette.pumpPipe, { metalness: 0.4 }),
      [34.3, 0.75, 9],
      [0.21, 3, 0.21],
    ).rotation.z = Math.PI / 2;
    block(palette.pumpPanel, [32.8, 1.8, 9], [1.3, 0.12, 1.65]).rotation.z =
      -0.22;
    for (let i = 0; i < 16; i++) {
      const a = i * 2.399,
        x = 40 + Math.cos(a) * 9.6,
        z = 7 + Math.sin(a) * 13;
      mesh(
        sphere(),
        mat(
          [palette.shoreStone, palette.shoreStoneDark, palette.shoreStoneLight][
            i % 3
          ],
        ),
        [x, 0.4, z],
        [0.7, 0.5, 0.9],
      );
    }
    // Weather mast: radiation shield, solar panel and rotating wind cups.
    mesh(
      cylinder(),
      mat(palette.weatherMast, { metalness: 0.3 }),
      [30, 2.4, -18],
      [0.1, 4.8, 0.1],
    );
    block(
      palette.weatherSolar,
      [30.4, 2.8, -18],
      [0.85, 0.08, 0.65],
    ).rotation.z = -0.35;
    for (let i = 0; i < 4; i++)
      mesh(
        cylinder(),
        mat(palette.weatherShield),
        [29.55, 2.5 + i * 0.15, -18],
        [0.3, 0.08, 0.3],
      );
    windRotor = new THREE.Group();
    windRotor.position.set(30, 4.85, -18);
    farm.add(windRotor);
    for (let i = 0; i < 3; i++) {
      const a = (i * Math.PI * 2) / 3,
        arm = block(
          palette.weatherArm,
          [Math.cos(a) * 0.35, 0, Math.sin(a) * 0.35],
          [0.75, 0.06, 0.06],
          windRotor,
        );
      arm.rotation.y = -a;
      mesh(
        sphere(),
        mat(palette.weatherCup),
        [Math.cos(a) * 0.7, 0.05, Math.sin(a) * 0.7],
        [0.16, 0.13, 0.16],
        windRotor,
      );
    }
    windRotor.traverse((item) => {
      item.castShadow = false;
    });
    // Small wind turbine on the rear ridge is deliberately static; the weather cups animate.
    mesh(cylinder(), mat(palette.turbineTower), [-40, 11, -48], [0.3, 22, 0.3]);
    mesh(sphere(), mat(palette.turbineHub), [-40, 22, -47.7], [0.7, 0.6, 0.9]);
    for (let i = 0; i < 3; i++) {
      const a = (i * Math.PI * 2) / 3;
      const blade = block(
        palette.turbineBlade,
        [-40 + Math.sin(a) * 3.4, 22 + Math.cos(a) * 3.4, -46.85],
        [0.46, 6.9, 0.16],
      );
      blade.rotation.z = -a;
    }
  }
  function tractor(position, color, scale = 1, harvester = false) {
    const group = new THREE.Group();
    group.position.set(...position);
    group.scale.setScalar(scale);
    group.rotation.y = -Math.PI / 2;
    farm.add(group);
    block(palette.vehicleChassis, [0, 0.7, 0], [1.4, 0.35, 3.1], group);
    block(color, [0, 1.28, -0.7], [1.25, 0.75, 1.45], group);
    block(palette.vehicleCabin, [0, 1.9, 0.65], [1.3, 1.3, 1.5], group);
    block(
      palette.vehicleWindscreen,
      [0, 2.05, -0.12],
      [1.03, 0.8, 0.035],
      group,
    );
    for (const side of [-1, 1]) {
      block(
        palette.vehicleWindow,
        [side * 0.67, 2.05, 0.65],
        [0.035, 0.8, 1.25],
        group,
      );
      block(color, [side * 0.92, 1.53, 0.9], [0.62, 0.18, 1.9], group);
      for (const [z, radius] of [
        [1, 0.84],
        [-1, 0.53],
      ]) {
        const tire = mesh(
          cylinder(),
          mat(palette.vehicleTire),
          [side * 0.9, radius, z],
          [radius, 0.53, radius],
          group,
        );
        tire.rotation.z = Math.PI / 2;
        const hub = mesh(
          cylinder(),
          mat(palette.vehicleHub, { metalness: 0.2 }),
          [side * 1.2, radius, z],
          [radius * 0.52, 0.04, radius * 0.52],
          group,
        );
        hub.rotation.z = Math.PI / 2;
      }
      block(
        palette.vehicleHeadlamp,
        [side * 0.47, 1.32, -1.46],
        [0.19, 0.18, 0.055],
        group,
      );
    }
    block(color, [0, 2.68, 0.63], [1.6, 0.16, 1.83], group);
    mesh(
      cylinder(),
      mat(palette.vehicleExhaust),
      [0.49, 2.05, -0.76],
      [0.075, 1.12, 0.075],
      group,
    );
    block(palette.vehicleGrille, [0, 1.13, -1.45], [0.8, 0.42, 0.07], group);
    if (harvester) {
      block(palette.harvesterHopper, [0, 1.9, 1.4], [1.7, 0.65, 1.7], group);
      block(palette.harvesterHeader, [0, 0.45, -2], [4, 0.3, 0.8], group);
      mesh(
        cylinder(),
        mat(palette.harvesterReel),
        [0, 0.7, -2.2],
        [0.27, 4, 0.27],
        group,
      ).rotation.z = Math.PI / 2;
      for (let i = -1.7; i < 2; i += 0.35)
        block(palette.harvesterTine, [i, 0.35, -2.5], [0.08, 0.12, 0.7], group);
    } else {
      block(palette.trailerBed, [0, 0.7, 3], [1.7, 0.16, 2.2], group);
      for (const side of [-1, 1])
        block(
          palette.trailerSide,
          [side * 0.85, 1.1, 3],
          [0.1, 0.7, 2.2],
          group,
        );
      block(palette.trailerSide, [0, 1.1, 4.05], [1.7, 0.7, 0.1], group);
      for (const side of [-1, 1])
        mesh(
          cylinder(),
          mat(palette.trailerTire),
          [side * 0.9, 0.42, 3.3],
          [0.42, 0.3, 0.42],
          group,
        ).rotation.z = Math.PI / 2;
    }
  }
  function cropGeometry(golden) {
    return geo(golden ? "grain" : "leaves", () => {
      const vertices = [];
      const triangle = (a, b, c) => vertices.push(...a, ...b, ...c);
      for (let side = 0; side < 3; side++) {
        const a = (side * Math.PI) / 3,
          dx = Math.cos(a),
          dz = Math.sin(a);
        const w = golden ? 0.025 : 0.06,
          h = golden ? 0.86 : 0.55;
        triangle([-dx * w, 0, -dz * w], [dx * w, 0, dz * w], [0, h, 0]);
        for (const sign of [-1, 1]) {
          const extent = golden ? 0.15 : 0.32,
            base = golden ? 0.68 : 0.15;
          triangle(
            [0, base, 0],
            [sign * dx * extent, golden ? 0.81 : 0.45, sign * dz * extent],
            [0, golden ? 1.05 : 0.6, 0],
          );
        }
      }
      const value = new THREE.BufferGeometry();
      value.setAttribute(
        "position",
        new THREE.Float32BufferAttribute(vertices, 3),
      );
      value.computeVertexNormals();
      return value;
    });
  }
  function rebuildFields(next) {
    // Instance buffers belong to the individual layout; shared meshes/materials survive metric refreshes.
    plots.traverse((item) => {
      if (item.isInstancedMesh) item.dispose();
    });
    plots.clear();
    for (let i = picks.length - 1; i >= 0; i--)
      if (picks[i].userData.field || picks[i].userData.proxy)
        picks.splice(i, 1);
    anchors.clear();
    (next.plots || []).forEach((plot, index) => {
      const [x, y = 0, z] = plot.position,
        [w, d] = plot.size;
      const earth = block(
        index % 2 ? palette.soilDark : palette.soilLight,
        [x, y + 0.025, z],
        [w, 0.13, d],
        plots,
      );
      earth.userData = { selectionId: plot.id, field: true };
      picks.push(earth);
      anchors.set(plot.id, new THREE.Vector3(x, y + 1.4, z));
      const rng = random(index * 137 + 17),
        golden =
          /稻|麦|谷|wheat|rice/i.test(String(plot.crop)) || index % 4 === 2;
      // Artistic row colors distinguish plots; they do not infer actual crop maturity.
      const grainPalette = [
        [palette.cropGreen, palette.cropGreenLight, palette.cropGrainGreen],
        [palette.cropOlive, palette.cropOliveLight, palette.cropOliveDeep],
        [palette.cropGold, palette.cropGoldLight, palette.cropGoldDeep],
      ][index % 3];
      const planted = [],
        ridges = [],
        rowStep = golden ? 0.49 : 0.72,
        columnStep = golden ? 0.42 : 0.72;
      for (let row = -w / 2 + 0.48; row < w / 2 - 0.35; row += rowStep) {
        ridges.push({
          position: [x + row, y + 0.12, z],
          scale: [golden ? 0.23 : 0.42, 0.11, d - 0.6],
        });
        for (
          let column = -d / 2 + 0.5;
          column < d / 2 - 0.4;
          column += columnStep
        ) {
          const size = 0.72 + rng() * 0.5;
          planted.push({
            position: [
              x + row + rng() * 0.08,
              y + 0.17,
              z + column + rng() * 0.12,
            ],
            rotation: rng() * Math.PI,
            scale: [size, size, size],
            color: golden
              ? grainPalette[Math.floor(rng() * 3)]
              : [
                  palette.cropGreen,
                  palette.cropLeafDeep,
                  palette.cropGreenLight,
                ][Math.floor(rng() * 3)],
          });
        }
      }
      instanced(box(), mat(palette.soilRidge), ridges, plots, false);
      instanced(
        cropGeometry(golden),
        mat(palette.materialNeutral, { side: THREE.DoubleSide }),
        planted,
        plots,
        false,
      );
      // Irrigation standpipes give scale and reinforce that this is a working farm.
      const stems = [];
      for (let row = -w / 2 + 2; row < w / 2 - 1; row += 5)
        stems.push({
          position: [x + row, y + 0.6, z - d / 2 + 0.5],
          scale: [0.055, 1.1, 0.055],
        });
      instanced(cylinder(), mat(palette.irrigationPipe), stems, plots);
    });
    (next.hotspots || []).forEach((hotspot) => {
      const position = new THREE.Vector3(...hotspot.position);
      anchors.set(hotspot.id, position);
      const proxy = mesh(
        sphere(),
        mat(palette.materialNeutral, {
          transparent: true,
          opacity: 0,
          depthWrite: false,
        }),
        hotspot.position,
        [2, 2, 2],
        plots,
      );
      proxy.castShadow = proxy.receiveShadow = false;
      proxy.userData = { selectionId: hotspot.id, proxy: true };
      picks.push(proxy);
    });
    projectionDirty = true;
    renderer.shadowMap.needsUpdate = true;
    updateSelection();
  }
  function updateSelection() {
    selection.visible = false;
    const plot = currentModel?.plots?.find((item) => item.id === selected);
    if (!plot) return;
    selection.position.set(
      plot.position[0],
      (plot.position[1] || 0) + 0.32,
      plot.position[2],
    );
    selection.scale.set(plot.size[0] + 0.38, 1, plot.size[1] + 0.38);
    selection.visible = true;
  }
  function publishPositions() {
    // Ambient movement does not change anchors. Avoid 30 reactive Vue updates per second.
    if (!onProject || !projectionDirty) return;
    const result = [];
    for (const [id, point] of anchors) {
      projected.copy(point).project(camera);
      result.push({
        id,
        x: ((projected.x + 1) * width) / 2,
        y: ((1 - projected.y) * height) / 2,
        visible:
          projected.z > -1 &&
          projected.z < 1 &&
          Math.abs(projected.x) < 0.97 &&
          Math.abs(projected.y) < 0.97,
      });
    }
    projectionDirty = false;
    onProject(result);
  }
  function invalidate() {
    // Rendering is driven by render(); no animation loop in the film build.
    dirty = true;
  }
  function frame(now) {
    raf = 0;
    if (disposed || lost || document.hidden) return;
    if (now - lastFrame < 1000 / 30) {
      raf = requestAnimationFrame(frame);
      return;
    }
    const delta = Math.min(0.1, (now - (lastClock || now)) / 1000);
    lastClock = now;
    lastFrame = now;
    if (motion) {
      clock += delta;
      windRotor.rotation.y = clock * 0.75;
      water.material.roughness = 0.24 + Math.sin(clock * 0.7) * 0.025;
      if (tour) {
        const offset = camera.position
          .clone()
          .sub(controls.target)
          .applyAxisAngle(THREE.Object3D.DEFAULT_UP, delta * 0.025);
        camera.position.copy(controls.target).add(offset);
        controls.update();
      }
    }
    if (tween) {
      const progress = Math.min(1, (now - tween.started) / 720),
        eased = 1 - (1 - progress) ** 3;
      camera.position.lerpVectors(tween.from, tween.to, eased);
      controls.target.lerpVectors(tween.targetFrom, tween.targetTo, eased);
      controls.update();
      if (progress === 1) tween = null;
    }
    if (dirty || motion || tween) {
      renderer.render(scene, camera);
      publishPositions();
      dirty = false;
    }
    if ((motion || tween) && !raf) raf = requestAnimationFrame(frame);
  }
  function stopTour() {
    if (tour) {
      tour = false;
      state();
    }
  }
  function moveCamera(position, target) {
    if (motion)
      tween = {
        from: camera.position.clone(),
        to: position,
        targetFrom: controls.target.clone(),
        targetTo: target,
        started: performance.now(),
      };
    else {
      camera.position.copy(position);
      controls.target.copy(target);
      controls.update();
    }
    invalidate();
  }
  function reset() {
    if (disposed) return;
    stopTour();
    const fit = Math.max(1, 1.52 / Math.max(0.6, camera.aspect));
    const distance = Math.min(1.55, fit);
    moveCamera(overview.clone().multiplyScalar(distance), center.clone());
  }
  function select(id, { focus = true } = {}) {
    if (disposed) return;
    selected = anchors.has(id) ? id : null;
    updateSelection();
    stopTour();
    if (focus && selected) {
      const target = anchors.get(selected).clone();
      target.y = 0;
      // Each facility needs a different view to keep its subject in the foreground.
      // Fields retain the visitor's approach direction; facility tours use composed views.
      const facilityViews = {
        weather: [20, 15, 22],
        water: [28, 23, 28],
        yard: [32, 24, 29],
      };
      const offset = facilityViews[selected]
        ? new THREE.Vector3(...facilityViews[selected])
        : camera.position
            .clone()
            .sub(controls.target)
            .normalize()
            .multiplyScalar(48);
      if (!facilityViews[selected]) offset.y = Math.max(offset.y, 23);
      moveCamera(target.clone().add(offset), target);
    }
    invalidate();
  }
  function resize() {
    if (disposed || !renderer) return;
    width = fixedWidth;
    height = fixedHeight;
    renderer.setPixelRatio(1);
    renderer.setSize(width, height, false);
    camera.aspect = width / height;
    camera.updateProjectionMatrix();
    projectionDirty = true;
    invalidate();
  }
  function setTime(value) {
    if (disposed) return;
    theme = value === "dusk" ? "dusk" : "day";
    const dusk = theme === "dusk";
    scene.background.set(dusk ? palette.skyDusk : palette.skyDay);
    scene.fog.color.copy(scene.background);
    sun.color.set(dusk ? palette.sunDusk : palette.sunDay);
    sun.intensity = dusk ? 2.1 : 2.75;
    sun.position.set(dusk ? -65 : -35, dusk ? 30 : 65, 30);
    ambient.intensity = dusk ? 1.4 : 1.9;
    renderer.toneMappingExposure = dusk ? 0.93 : 1.04;
    renderer.shadowMap.needsUpdate = true;
    invalidate();
  }
  function dispose() {
    if (disposed) return;
    disposed = true;
    cancelAnimationFrame(raf);
    raf = 0;
    observer?.disconnect();
    disposables.forEach((fn) => fn());
    controls?.dispose();
    scene.traverse((item) => {
      if (item.isInstancedMesh) item.dispose();
    });
    // Shadow render targets are owned by the light, not by renderer.dispose().
    sun?.dispose();
    geometries.forEach((item) => item.dispose());
    materials.forEach((item) => item.dispose());
    textures.forEach((item) => item.dispose());
    renderer?.dispose();
    renderer?.forceContextLoss();
    scene.clear();
    picks.length = 0;
    anchors.clear();
    activePointers.clear();
  }
  try {
    renderer = new THREE.WebGLRenderer({
      canvas,
      antialias: true,
      alpha: false,
      preserveDrawingBuffer: true,
      powerPreference: "high-performance",
    });
    renderer.outputColorSpace = THREE.SRGBColorSpace;
    renderer.toneMapping = THREE.ACESFilmicToneMapping;
    renderer.shadowMap.enabled = true;
    renderer.shadowMap.type = THREE.PCFShadowMap;
    // Only the tiny weather rotor moves. Static farm shadows need no per-frame redraw.
    renderer.shadowMap.autoUpdate = false;
    renderer.shadowMap.needsUpdate = true;
    scene.background = new THREE.Color(palette.skyDay);
    scene.fog = new THREE.Fog(palette.skyDay, 105, 255);
    ambient = new THREE.HemisphereLight(
      palette.skyAmbient,
      palette.groundAmbient,
      1.9,
    );
    scene.add(ambient);
    sun = new THREE.DirectionalLight(palette.sunDay, 2.75);
    sun.position.set(-35, 65, 30);
    sun.castShadow = true;
    sun.shadow.mapSize.set(1536, 1536);
    sun.shadow.camera.left = -78;
    sun.shadow.camera.right = 78;
    sun.shadow.camera.top = 65;
    sun.shadow.camera.bottom = -65;
    sun.shadow.camera.near = 1;
    sun.shadow.camera.far = 180;
    sun.shadow.normalBias = 0.045;
    sun.shadow.bias = -0.0001;
    scene.add(sun);
    scene.add(farm, plots);
    terrain();
    barn();
    greenhouses();
    utilities();
    tractor([-19, 0.1, 21.7], palette.cropGreen, 1.25, true);
    tractor([-11, 0.1, -23.8], palette.tractorBody, 1.05);
    const outlineGeometry = geo("outline", () =>
      new THREE.BufferGeometry().setFromPoints([
        new THREE.Vector3(-0.5, 0, -0.5),
        new THREE.Vector3(0.5, 0, -0.5),
        new THREE.Vector3(0.5, 0, 0.5),
        new THREE.Vector3(-0.5, 0, 0.5),
      ]),
    );
    const outlineMaterial = new THREE.LineBasicMaterial({
      color: palette.selection,
      depthTest: false,
    });
    materials.add(outlineMaterial);
    selection = new THREE.LineLoop(outlineGeometry, outlineMaterial);
    selection.renderOrder = 10;
    selection.visible = false;
    scene.add(selection);
    currentModel = model || { plots: [], hotspots: [], layoutKey: "" };
    rebuildFields(currentModel);
    resize();
    setTime("day");
    ready = true;
    const target = new THREE.Vector3();
    return {
      THREE,
      scene,
      camera,
      renderer,
      farm,
      plots,
      palette,
      anchors,
      mat,
      setTime,
      dispose,
      // Frame-driven render: camera pose and ambient clock come from the film timeline.
      render({ position, lookAt, fov = 43, time = 0 }) {
        camera.position.set(...position);
        target.set(...lookAt);
        camera.lookAt(target);
        if (camera.fov !== fov) {
          camera.fov = fov;
          camera.updateProjectionMatrix();
        }
        windRotor.rotation.y = time * 0.75;
        water.material.roughness = 0.24 + Math.sin(time * 0.7) * 0.025;
        renderer.render(scene, camera);
      },
      project(point) {
        projected.set(...point).project(camera);
        return {
          x: ((projected.x + 1) * width) / 2,
          y: ((1 - projected.y) * height) / 2,
          visible: projected.z > -1 && projected.z < 1,
        };
      },
    };  } catch (error) {
    dispose();
    throw error;
  }
}
