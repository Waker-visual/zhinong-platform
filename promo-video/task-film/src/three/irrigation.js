import { ZONES, PLOT3, PLOT3_EDGE } from "./model";

// 设备联动示意：泵房 → 主管 → C-01 支管阀门 → 田间出水。
// 全部为模拟示意（与产品“水塘、泵房与水流为场景演示”的说明一致），状态由片子时间轴驱动。
export const PIPE_Y = 0.42;
export const LANE_Z = 0; // 前后两排地块之间的田间道
export const PUMP_OUT = [31.9, PIPE_Y, 9];
export const ROUTE = [
  [31.9, PIPE_Y, 9],
  [28.6, PIPE_Y, 9],
  [28.6, PIPE_Y, LANE_Z],
  [ZONES[0].center[0], PIPE_Y, LANE_Z],
];
export const BRANCH_TOP = ZONES.map((z) => [z.center[0], PIPE_Y, LANE_Z]);
export const OUTLET = [ZONES[0].center[0], PIPE_Y, PLOT3_EDGE.north + 3.2];

export function buildIrrigation(world) {
  const { THREE, scene, palette } = world;
  const group = new THREE.Group();
  scene.add(group);
  const std = (color, o = {}) => new THREE.MeshStandardMaterial({ color, roughness: 0.55, ...o });

  // 主管与支管：实心管（远景）+ 半透明管壳（近景剖面），内部水芯按进度填充。
  const polyline = (pts) => {
    const path = new THREE.CurvePath();
    for (let i = 0; i < pts.length - 1; i++)
      path.add(new THREE.LineCurve3(new THREE.Vector3(...pts[i]), new THREE.Vector3(...pts[i + 1])));
    return path;
  };
  const mainPath = polyline([...ROUTE.slice(0, 3), [BRANCH_TOP[2][0] + 0.01, PIPE_Y, LANE_Z], [BRANCH_TOP[0][0], PIPE_Y, LANE_Z]]);
  const feedPath = polyline([...ROUTE]); // 泵 → C-01 支管口的实际输水路径
  const branchPaths = BRANCH_TOP.map((p) => polyline([p, [p[0], PIPE_Y, PLOT3_EDGE.north + 3.2]]));

  const shellMat = std(palette.pumpPipe, { transparent: true, opacity: 1, metalness: 0.3, roughness: 0.35, depthWrite: true });
  const shell = (path, r, segs) => {
    const m = new THREE.Mesh(new THREE.TubeGeometry(path, segs, r, 14, false), shellMat);
    m.castShadow = true;
    group.add(m);
    return m;
  };
  shell(mainPath, 0.26, 400);
  branchPaths.forEach((p) => shell(p, 0.18, 40));

  // 水芯：条纹贴图沿管长滚动表示流向；drawRange 控制水头位置。
  const stripes = document.createElement("canvas");
  stripes.width = 256; stripes.height = 8;
  const g = stripes.getContext("2d");
  const grad = g.createLinearGradient(0, 0, 256, 0);
  grad.addColorStop(0, "#2f8fb0"); grad.addColorStop(0.45, "#6fd0e6"); grad.addColorStop(0.55, "#d9f6ff"); grad.addColorStop(1, "#2f8fb0");
  g.fillStyle = grad; g.fillRect(0, 0, 256, 8);
  const flowTex = new THREE.CanvasTexture(stripes);
  flowTex.wrapS = flowTex.wrapT = THREE.RepeatWrapping;
  flowTex.colorSpace = THREE.SRGBColorSpace;
  const waterMat = new THREE.MeshBasicMaterial({ map: flowTex, transparent: true, opacity: 0.95, toneMapped: false });
  const core = (path, r, segs, repeat) => {
    const tex = flowTex.clone(); tex.repeat.set(repeat, 1); tex.needsUpdate = true;
    const m = new THREE.Mesh(new THREE.TubeGeometry(path, segs, r, 10, false), waterMat.clone());
    m.material.map = tex;
    m.renderOrder = 2;
    group.add(m);
    return m;
  };
  const feedLen = feedPath.getLength();
  const feedCore = core(feedPath, 0.17, 600, Math.round(feedLen / 2.2));
  const branchCore = core(branchPaths[0], 0.12, 60, 2);
  const radial = 10 * 6; // 每段索引数：radialSegments × 6

  // 节点与阀门（三个支管口；仅 C-01 动作）
  const valves = BRANCH_TOP.map((p, i) => {
    const v = new THREE.Group();
    v.position.set(p[0], PIPE_Y, LANE_Z);
    group.add(v);
    const body = new THREE.Mesh(new THREE.CylinderGeometry(0.36, 0.36, 0.7, 16), std("#5d7a72", { metalness: 0.4 }));
    body.castShadow = true; v.add(body);
    const stem = new THREE.Mesh(new THREE.CylinderGeometry(0.06, 0.06, 0.7, 8), std("#9aa9a2"));
    stem.position.y = 0.6; v.add(stem);
    const wheel = new THREE.Mesh(new THREE.TorusGeometry(0.42, 0.07, 8, 24), std(i === 0 ? palette.selection : "#b9c3bd", { metalness: 0.2 }));
    wheel.rotation.x = Math.PI / 2; wheel.position.y = 0.98; v.add(wheel);
    const spoke = new THREE.Mesh(new THREE.BoxGeometry(0.84, 0.05, 0.06), wheel.material);
    spoke.position.y = 0.98; v.add(spoke);
    return { group: v, wheel, spoke };
  });

  // 田间出水：立管 + 喷头，水滴粒子按确定性轨迹喷洒。
  const riser = new THREE.Mesh(new THREE.CylinderGeometry(0.1, 0.1, 1.5, 10), std(palette.irrigationPipe));
  riser.position.set(OUTLET[0], 0.75, OUTLET[2]); group.add(riser);
  const head = new THREE.Mesh(new THREE.CylinderGeometry(0.22, 0.14, 0.22, 12), std("#3d5650", { metalness: 0.4 }));
  head.position.set(OUTLET[0], 1.55, OUTLET[2]); group.add(head);
  const DROPS = 2600;
  const dropGeo = new THREE.BufferGeometry();
  const dropPos = new Float32Array(DROPS * 3);
  dropGeo.setAttribute("position", new THREE.BufferAttribute(dropPos, 3));
  const dropMat = new THREE.PointsMaterial({ color: "#dff6ff", size: 0.17, transparent: true, opacity: 0, depthWrite: false, toneMapped: false });
  const drops = new THREE.Points(dropGeo, dropMat);
  drops.frustumCulled = false; drops.renderOrder = 3;
  group.add(drops);
  const seeds = Array.from({ length: DROPS }, (_, i) => {
    const r = (n) => { const x = Math.sin(i * 12.9898 + n * 78.233) * 43758.5453; return x - Math.floor(x); };
    return { a: r(1) * Math.PI * 2, v: 2.4 + r(2) * 2.2, up: 3.2 + r(3) * 1.6, ph: r(4), life: 0.9 + r(5) * 0.5 };
  });

  // 湿润范围：C-01 内部的深色土壤扩散（只在本次任务涉及的覆盖区）
  const z0 = ZONES[0];
  const wet = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), new THREE.MeshStandardMaterial({ color: "#4a3a26", transparent: true, opacity: 0, roughness: 0.4, depthWrite: false }));
  wet.rotation.x = -Math.PI / 2;
  wet.position.set(z0.center[0], 0.24, OUTLET[2] + 3.4);
  wet.renderOrder = 1;
  group.add(wet);
  // 作物冠层上的浅水色覆盖：远景也能看出水只进入 C-01
  const glow = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), new THREE.MeshBasicMaterial({ color: "#7fd3e6", transparent: true, opacity: 0, depthWrite: false, toneMapped: false }));
  glow.rotation.x = -Math.PI / 2;
  glow.position.set(z0.center[0], 1.05, z0.center[2]);
  glow.renderOrder = 2;
  group.add(glow);

  // C-01 覆盖区边界（暖金色描边）与三分区分隔线
  const outline = (cx, cz, w, d, color, y = 0.36) => {
    const pts = [[-w / 2, -d / 2], [w / 2, -d / 2], [w / 2, d / 2], [-w / 2, d / 2], [-w / 2, -d / 2]].map(([x, z]) => new THREE.Vector3(cx + x, y, cz + z));
    const geo = new THREE.TubeGeometry(new THREE.CatmullRomCurve3(pts, false, "catmullrom", 0), 120, 0.11, 6, false);
    const m = new THREE.Mesh(geo, new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0, toneMapped: false, depthWrite: false }));
    m.renderOrder = 4;
    group.add(m);
    return m;
  };
  const zoneLines = ZONES.map((z) => outline(z.center[0], z.center[2], z.size[0] - 0.3, z.size[1] - 0.3, "#efe6c8", 0.34));
  const c01Line = outline(z0.center[0], z0.center[2], z0.size[0] - 0.1, z0.size[1] - 0.1, "#e2b65c", 0.4);
  const plotLines = world.plots ? null : null;
  const plotOutline = (p) => outline(p.position[0], p.position[2], p.size[0] + 0.5, p.size[1] + 0.5, "#e8c77a", 0.38);
  const plot3Line = plotOutline(PLOT3);

  // 泵：运行指示灯 + 侧面叶轮护罩（近景可辨认转动）
  const lamp = new THREE.Mesh(new THREE.SphereGeometry(0.16, 16, 12), new THREE.MeshStandardMaterial({ color: "#2b3a33", emissive: "#3ddc84", emissiveIntensity: 0 }));
  lamp.position.set(33.55, 1.38, 9.7); group.add(lamp);
  const halo = new THREE.Mesh(new THREE.SphereGeometry(0.27, 16, 12), new THREE.MeshBasicMaterial({ color: "#7cf0b0", transparent: true, opacity: 0, depthWrite: false, toneMapped: false }));
  halo.position.copy(lamp.position); group.add(halo);
  const fan = new THREE.Group();
  fan.position.set(32.85, 0.92, 9.68); group.add(fan);
  const ring = new THREE.Mesh(new THREE.TorusGeometry(0.42, 0.05, 8, 28), std("#c9d3cd", { metalness: 0.5 }));
  fan.add(ring);
  for (let i = 0; i < 5; i++) {
    const blade = new THREE.Mesh(new THREE.BoxGeometry(0.1, 0.36, 0.03), std("#e2e8e3", { metalness: 0.3 }));
    blade.position.set(Math.cos((i / 5) * Math.PI * 2) * 0.2, Math.sin((i / 5) * Math.PI * 2) * 0.2, 0.02);
    blade.rotation.z = (i / 5) * Math.PI * 2;
    fan.add(blade);
  }

  // 指令光点：沿管线移动的暖金色脉冲
  const pulse = new THREE.Mesh(new THREE.SphereGeometry(0.42, 18, 14), new THREE.MeshBasicMaterial({ color: "#ffd98a", transparent: true, opacity: 0, toneMapped: false, depthWrite: false }));
  pulse.renderOrder = 5;
  group.add(pulse);

  const clamp01 = (x) => Math.max(0, Math.min(1, x));
  const tmp = new THREE.Vector3();
  return {
    feedLen,
    pointOnFeed(u) { return feedPath.getPointAt(clamp01(u)).toArray(); },
    update(fx, t) {
      // fx: { pump, flow, branch, valve, spray, wet, zones, c01, plot3, pulse, pipeAlpha, xray }
      const pump = fx.pump || 0;
      lamp.material.emissiveIntensity = 2.6 * pump;
      halo.material.opacity = 0.35 * pump * (0.85 + 0.15 * Math.sin(t * 6));
      fan.rotation.z = -(fx.fanAngle || 0);
      shellMat.opacity = 1 - 0.72 * (fx.xray || 0);
      shellMat.depthWrite = (fx.xray || 0) < 0.5;
      const feedSegs = 600, idx = Math.floor(clamp01(fx.flow || 0) * feedSegs) * radial;
      feedCore.geometry.setDrawRange(0, idx);
      feedCore.visible = idx > 0;
      const bIdx = Math.floor(clamp01(fx.branch || 0) * 60) * radial;
      branchCore.geometry.setDrawRange(0, bIdx);
      branchCore.visible = bIdx > 0;
      const fade = fx.waterAlpha ?? 1;
      feedCore.material.opacity = 0.95 * fade;
      branchCore.material.opacity = 0.95 * fade;
      const speed = fx.flowSpeed ?? 1;
      feedCore.material.map.offset.x = -(fx.flowPhase ?? t * 0.9 * speed);
      branchCore.material.map.offset.x = -(fx.flowPhase ?? t * 0.9 * speed) * 0.6;
      valves.forEach((v, i) => {
        const open = i === 0 ? fx.valve || 0 : 0;
        v.wheel.rotation.z = open * Math.PI * 1.5;
        v.spoke.rotation.y = open * Math.PI * 1.5;
      });
      const spray = fx.spray || 0;
      dropMat.opacity = 0.85 * spray;
      if (spray > 0) {
        for (let i = 0; i < DROPS; i++) {
          const s = seeds[i];
          const life = s.life, k = ((t / life + s.ph) % 1) * life;
          const dir = s.a, hv = s.v * (0.55 + 0.45 * spray);
          dropPos[i * 3] = OUTLET[0] + Math.cos(dir) * hv * k;
          dropPos[i * 3 + 1] = 1.62 + s.up * k - 4.9 * k * k;
          dropPos[i * 3 + 2] = OUTLET[2] + 1.6 + Math.sin(dir) * hv * k * 0.9;
          if (dropPos[i * 3 + 1] < 0.25) dropPos[i * 3 + 1] = -5;
        }
        dropGeo.attributes.position.needsUpdate = true;
      }
      const w = fx.wet || 0;
      wet.material.opacity = 0.42 * Math.min(1, w * 1.6);
      wet.scale.set(Math.max(0.01, z0.size[0] * 0.92 * w), Math.max(0.01, (z0.size[1] - 3.6) * Math.min(1, w * 1.15)), 1);
      glow.material.opacity = 0.3 * Math.min(1, w * 1.4);
      glow.scale.set(z0.size[0] - 0.4, Math.max(0.01, (z0.size[1] - 0.4) * Math.min(1, 0.25 + w)), 1);
      glow.position.z = PLOT3_EDGE.north + 0.2 + glow.scale.y / 2;
      zoneLines.forEach((m) => (m.material.opacity = 0.7 * (fx.zones || 0)));
      c01Line.material.opacity = fx.c01 || 0;
      plot3Line.material.opacity = fx.plot3 || 0;
      const p = fx.pulse;
      pulse.material.opacity = p == null ? 0 : (fx.pulseAlpha ?? 1);
      if (p != null) {
        tmp.copy(feedPath.getPointAt(clamp01(p)));
        pulse.position.set(tmp.x, tmp.y + 0.15, tmp.z);
        pulse.scale.setScalar(1 + 0.15 * Math.sin(t * 10));
      }
    },
  };
}
