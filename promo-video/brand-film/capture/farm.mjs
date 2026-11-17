// 三维农场实录：逐帧推进虚拟时钟，镜头用产品自带的鼠标操作驱动（左键=环绕/俯仰，右键=平移，Zoom 指令=档位），
// 每帧保存画面与“地面→屏幕”单应矩阵，供成片把动效标注贴在三维画面上。
// 用法：node capture/farm.mjs [shot ...]   PREVIEW=1 只拍每个镜头的首/中/尾三帧
import fs from 'node:fs'; import path from 'node:path';
import { open, OUT } from './lib.mjs';
import { enterStage, setDemo, send, points, keepBuffer, grab } from './stage.mjs';
import { fitH, applyH } from './homography.mjs';
const K = 5, W = 2560, H = 1440, FPS = 60, STEP = 1000 / FPS;
const PREVIEW = !!process.env.PREVIEW, only = process.argv.slice(2);
const ease = (u) => (u <= 0 ? 0 : u >= 1 ? 1 : u * u * (3 - 2 * u));
const lerp = (a, b, u) => a + (b - a) * u;

const { browser, page } = await open({ width: W * K, height: H * K, dsf: 1 / K });
await keepBuffer(page);
await enterStage(page);
const scene = JSON.parse(await page.evaluate(() => window.__farm.scene));
const cen = (pts) => [pts.reduce((a, p) => a + p.x, 0) / pts.length, pts.reduce((a, p) => a + p.z, 0) / pts.length];
const parcelWorld = Object.fromEntries(scene.plots.map((p) => [p.parcelId, cen(p.points)]));
const dev = (kind) => scene.devices.find((d) => d.kind === kind);
const pump = dev('PUMP'), gate = dev('GATE');
const C01 = scene.plots[6], zone = scene.zones.find((z) => z.parcelId === C01.parcelId);
const node = (kind) => { const n = zone.nodes.find((x) => x.kind === kind); return [n.x, n.z]; };
const G = { center: [0, 0], c01: cen(C01.points), pump: [pump.x, pump.z], gate: [gate.x, gate.z], outlet: node('OUTLET'), valve: node('VALVE') };
G.mid = [(G.c01[0] + G.pump[0]) / 2, (G.c01[1] + G.pump[1]) / 2];
G.pipeA = [lerp(G.pump[0], zone.points[1].x, 0.35), lerp(G.pump[1], zone.points[1].z, 0.35)];
G.pipeB = [lerp(zone.points[1].x, zone.points[2].x, 0.75), zone.points[1].z];
const RUN = { devices: { [pump.id]: { running: true }, [gate.id]: { running: true, opening: 100 } }, zones: { [zone.id]: true } };
const IDLE = { devices: { [pump.id]: { running: false }, [gate.id]: { running: false, opening: 0 } }, zones: { [zone.id]: false } };

await page.clock.install({ time: new Date() });
await page.clock.pauseAt((await page.evaluate(() => Date.now())) + 50);
const step = async (n = 1) => { for (let i = 0; i < n; i++) await page.clock.runFor(STEP); };
const fit = async () => {
  const all = await points(page);
  const cand = all.filter((p) => p.kind === 'parcel' && parcelWorld[p.id] && Number.isFinite(p.x) && Number.isFinite(p.y));
  // 先用全部田块点；若有点落在镜头背后导致拟合不自洽，再退回只用画面附近的点
  for (const lim of [400, 30, 6]) {
    const pts = cand.filter((p) => Math.abs(p.x) < lim && Math.abs(p.y) < lim); if (pts.length < 4) continue;
    const src = pts.map((p) => parcelWorld[p.id]), dst = pts.map((p) => [p.x, p.y]); const Hm = fitH(src, dst);
    const err = Math.max(...src.map((g, i) => { const q = applyH(Hm, g); return Math.hypot(q[0] - dst[i][0], q[1] - dst[i][1]) / Math.max(1, Math.hypot(dst[i][0], dst[i][1])); }));
    if (err < 2e-3 || (lim === 6 && pts.length >= 4)) return { Hm, all };
  }
  return { Hm: null, all };
};
const cx = W * K / 2, cy = H * K / 2;
let cam = { yaw: -32, pitch: 30 };

// 镜头定位：先复位，再调档位、转到指定角度，最后把目标点平移到画面中心
async function rotateTo(yaw, pitch) {
  const dx = (yaw - cam.yaw) / 0.25 * K, dy = (pitch - cam.pitch) / 0.18 * K;
  if (Math.abs(dx) < 1 && Math.abs(dy) < 1) return;
  const sx = cx - dx / 2, sy = cy - dy / 2;
  await page.mouse.move(sx, sy); await page.mouse.down(); await step(2);
  for (let i = 1; i <= 8; i++) { await page.mouse.move(sx + dx * i / 8, sy + dy * i / 8); await step(); }
  await page.mouse.up(); await step(2); cam = { yaw, pitch };
}
async function panTo(at, rounds) {
  let d = 0;
  for (let k = 0; k < rounds; k++) {
    const { Hm } = await fit(); if (!Hm) break;
    const [u, v, w] = applyH(Hm, at);
    let px = u * W * K, py = v * H * K;
    if (w < 0) { px = cx - (px - cx); py = cy - (py - cy); }
    const m = 200; const qx = Math.min(W * K - m, Math.max(m, px)), qy = Math.min(H * K - m, Math.max(m, py));
    d = Math.hypot(px - cx, py - cy) / K;
    if (d < 1) break;
    await page.mouse.move(qx, qy); await page.mouse.down({ button: 'right' }); await step(2);
    for (let i = 1; i <= 6; i++) { await page.mouse.move(lerp(qx, cx, i / 6), lerp(qy, cy, i / 6)); await step(); }
    await page.mouse.up({ button: 'right' }); await step(2);
  }
  return d;
}
async function place({ at, zoom = 0, yaw, pitch, device, out = 0 }) {
  await send(page, 'SetDeviceDemo', 'off'); await send(page, 'SelectPlot', ''); await send(page, 'ResetView'); await step(4);
  cam = { yaw: -32, pitch: 30 };
  await rotateTo(yaw, pitch);
  if (device) { // 设备近景：用产品的“定位设备”对准（距离 16、俯仰 28），再退档、转到指定俯仰
    await send(page, 'SelectDevice', device); await step(4); cam.pitch = 28;
    for (let i = 0; i < out; i++) { await send(page, 'Zoom', 'out'); await step(2); }
    await rotateTo(yaw, pitch); console.log('  placed on device'); return;
  }
  await panTo(at, 4); // 远景下先对准
  for (let i = 0; i < zoom; i++) { await send(page, 'Zoom', 'in'); await step(2); if (i % 3 === 2) await panTo(at, 2); }
  const d = await panTo(at, 4);
  console.log('  placed, off-centre px', d.toFixed(1));
}

// 设备模型在场景里有避让偏移：用复位视角下的投影点反算水泵的实际落点
await send(page, 'ResetView'); await step(6);
{ const all = await points(page); const pts = all.filter((p) => p.kind === 'parcel' && parcelWorld[p.id]);
  const inv = fitH(pts.map((p) => [p.x, p.y]), pts.map((p) => parcelWorld[p.id])); const dp = all.find((p) => p.id === pump.id);
  if (dp) { const [x, z] = applyH(inv, [dp.x, dp.y]); console.log('pump registered', G.pump.map(Math.round), 'seen', [Math.round(x), Math.round(z)]); G.pump = [x, z]; }
  G.mid = [(G.c01[0] + G.pump[0]) / 2, (G.c01[1] + G.pump[1]) / 2];
  G.pipeA = [lerp(G.pump[0], zone.points[1].x, 0.3), lerp(G.pump[1], zone.points[1].z, 0.3)]; }

// 镜头表（秒数含首尾各约 0.5 s 余量）
const SHOTS = {
  arrive: { sec: 7.6, at: G.c01, zoom: 4, yaw: [-36, -22], pitch: [82, 50] },
  wide: { sec: 7.2, at: G.center, zoom: 1, yaw: [-58, -36], pitch: [24, 28] },
  low: { sec: 7.2, at: G.center, zoom: 3, yaw: [18, -6], pitch: [16, 19] },
  command: { sec: 9.4, at: G.mid, zoom: 4, yaw: [-64, -40], pitch: [40, 34] },
  pump: { sec: 6.2, device: pump.id, out: 1, yaw: [-70, -34], pitch: [20, 24], demo: [[0, IDLE], [0.6, RUN]] },
  pipe: { sec: 6.0, at: G.pipeA, zoom: 7, yaw: [-48, -48], pitch: [30, 30], truck: G.pipeB, demo: [[0, RUN]] },
  outlet: { sec: 5.6, at: G.outlet, zoom: 8, yaw: [-24, 2], pitch: [18, 22], demo: [[0, RUN]] },
  high: { sec: 6.0, at: G.center, zoom: 0, yaw: [-20, -2], pitch: [52, 42], demo: [[0, RUN]] },
};
const round = (x) => Math.round(x * 1e6) / 1e6;
const tag = (id) => (id === pump.id ? 'pump' : id === gate.id ? 'gate' : id === zone.id ? 'zone' : null);

for (const [name, s] of Object.entries(SHOTS)) {
  if (only.length && !only.includes(name)) continue;
  const dir = path.join(OUT, PREVIEW ? 'farm-preview' : 'farm', name); fs.mkdirSync(dir, { recursive: true });
  await setDemo(page, s.demo?.[0]?.[1] ?? IDLE);
  await place({ at: s.at, zoom: s.zoom, yaw: s.yaw[0], pitch: s.pitch[0], device: s.device, out: s.out });
  const total = Math.round(s.sec * FPS); const frames = []; const t0 = Date.now();
  let truck = null;
  if (s.truck) {
    const { Hm } = await fit(); const [u, v] = applyH(Hm, s.truck);
    truck = { x: u * W * K, y: v * H * K };
    await page.mouse.move(truck.x, truck.y); await page.mouse.down({ button: 'right' }); await step(2);
  } else {
    await page.mouse.move(cx, cy); await page.mouse.down(); await step();
    await page.mouse.move(cx + 20, cy); await step(); await page.mouse.move(cx, cy); await step();
  }
  await step(30); // 让设备动画进入稳定状态
  const picks = new Set([0, Math.floor(total / 2), total - 1]);
  for (let i = 0; i < total; i++) {
    const u = ease(i / (total - 1));
    for (const [at, st] of s.demo || []) if (at > 0 && i === Math.round(at * FPS)) await setDemo(page, st);
    if (truck) await page.mouse.move(lerp(truck.x, cx, u), lerp(truck.y, cy, u));
    else await page.mouse.move(cx + (lerp(s.yaw[0], s.yaw[1], u) - s.yaw[0]) / 0.25 * K, cy + (lerp(s.pitch[0], s.pitch[1], u) - s.pitch[0]) / 0.18 * K);
    if (PREVIEW && !picks.has(i)) { if (i % 6 === 0) await step(); continue; }
    await step();
    const { Hm, all } = await fit();
    frames.push({ H: Hm ? Hm.map(round) : null, p: Object.fromEntries(all.filter((p) => tag(p.id)).map((p) => [tag(p.id), [round(p.x), round(p.y)]])) });
    fs.writeFileSync(path.join(dir, 'f' + String(i).padStart(4, '0') + '.jpg'), await grab(page));
  }
  await page.mouse.up({ button: truck ? 'right' : 'left' }); await step(2);
  fs.writeFileSync(path.join(dir, 'track.json'), JSON.stringify({ fps: FPS, width: W, height: H, frames }));
  console.log(name, frames.length, 'frames', ((Date.now() - t0) / 1000).toFixed(0), 's');
}
fs.writeFileSync(path.join(OUT, 'farm-world.json'), JSON.stringify({
  G, plots: scene.plots.map((p) => ({ parcelId: p.parcelId, plotId: p.id, crop: p.crop, points: p.points.map((q) => [round(q.x), round(q.z)]) })),
  zone: { points: zone.points.map((q) => [round(q.x), round(q.z)]), nodes: zone.nodes.map((n) => ({ kind: n.kind, x: round(n.x), z: round(n.z) })) },
  devices: scene.devices.map((d) => ({ kind: d.kind, x: round(d.x), z: round(d.z) })), width: scene.width, depth: scene.depth,
}));
await browser.close();
