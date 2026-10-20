import { keys, ramp, env, clamp01, mix, type Key } from "../kit";
import type { Cam, Fx, V3 } from "../Farm3D";
import { ZONES } from "../three/model";
import { ROUTE } from "../three/irrigation.js";

// 全片时间（秒）。三维层在 0–5.6、29.6–62.8、71.6–80 可见。
export const T = {
  openEnd: 5.6,
  match1: [4.7, 5.6] as const, // 三维 C-01 → 二维地图 C-01
  match2: [29.7, 30.6] as const, // 二维地图 C-01 → 三维 C-01
  cmdLand: 31.0, // 指令落入 C-01
  cmdToPump: [31.9, 33.3] as const,
  receive: 33.4, // 设备模拟接收
  lampOn: 35.6,
  fanOn: 35.9,
  xray: 36.6,
  flow: [37.2, 46.2] as const, // 水头：泵 → C-01 支管口
  valve: [45.9, 46.7] as const,
  branch: [46.5, 47.1] as const,
  spray: 47.0,
  splitIn: [50.0, 51.2] as const,
  progress: [54.0, 60.0] as const, // 120 秒任务压缩为 6 秒呈现
  stop: 60.1,
  drain: [60.3, 61.7] as const,
  ended: 61.0,
  splitOut: [62.0, 62.8] as const,
  closeIn: 71.6,
  brand: 76.4,
};

export const C01 = ZONES[0];
export const C01_CORNERS: V3[] = (() => {
  const [x, , z] = C01.center, [w, d] = C01.size;
  return [[x - w / 2, 0.4, z - d / 2], [x + w / 2, 0.4, z - d / 2], [x + w / 2, 0.4, z + d / 2], [x - w / 2, 0.4, z + d / 2]];
})();
export const PUMP_TAG: V3 = [33, 2.6, 9];
export const OUTLET_TAG: V3 = [C01.center[0], 1.9, 4.0];

const c01x = C01.center[0];
type K = Key<number[]>;
const k = (t: number, p: V3, l: V3, fov = 40, e?: K["e"]): K => ({ t, v: [...p, ...l, fov], e });

// 水头在主管上的位置（0→1）
export const flowU = (t: number) => ramp(t, T.flow[0], T.flow[1] - T.flow[0], "inOut");

// 主管输水路径上按弧长取点（与 irrigation.js 的 feedPath 相同的折线）
const SEG = ROUTE.slice(1).map((p: number[], i: number) => ({ a: ROUTE[i], b: p, len: Math.hypot(p[0] - ROUTE[i][0], p[2] - ROUTE[i][2]) }));
const FEED_LEN = SEG.reduce((s: number, x: { len: number }) => s + x.len, 0);
export function feedPoint(u: number): V3 {
  let d = clamp01(u) * FEED_LEN;
  for (const s of SEG) {
    if (d <= s.len) { const p = s.len ? d / s.len : 0; return [mix(s.a[0], s.b[0], p), s.a[1], mix(s.a[2], s.b[2], p)]; }
    d -= s.len;
  }
  const last = ROUTE[ROUTE.length - 1];
  return [last[0], last[1], last[2]];
}
const OPEN: K[] = [
  k(0, [10, 60, 122], [-4, 0, 2], 38),
  k(2.5, [3, 27, 60], [-12, 0, 8], 40, "soft"),
  k(4.3, [c01x + 2, 30, 18], [c01x, 0, 9.2], 40, "inOut"),
  k(5.6, [c01x, 31, 9.7], [c01x, 0, 9.0], 40, "inOut"),
];

const LINK: K[] = [
  k(29.6, [c01x, 31, 9.7], [c01x, 0, 9.0], 40),
  k(30.6, [c01x, 31, 9.7], [c01x, 0, 9.0], 40),
  k(31.7, [c01x + 10, 30, 40], [c01x + 14, 0, 6], 44, "soft"), // 指令落入 C-01 后拉开
  k(33.0, [18, 34, 46], [10, 0, 6], 46, "inOut"), // C-01 与泵房同框，指令弧线抵达设备
  k(34.4, [42, 11, 27], [31, 0.6, 9], 40, "inOut"), // 定位演示泵房控制器
  k(35.4, [39.5, 4.6, 17.5], [32.6, 1.0, 9.0], 36, "inOut"), // 水泵近景
  k(37.6, [37.4, 3.3, 14.6], [32.4, 0.9, 9.0], 34, "soft"),
  k(39.6, [36.0, 4.2, 15.6], [30.0, 0.7, 8.2], 36, "inOut"), // 管路剖面：看清出水方向
];

function linkCam(t: number): number[] {
  if (t <= 39.6) return keys(t, LINK);
  // 39.6–46.4：跟随水头；46.4 之后拉开到泵房与 C-01 同框
  const u = flowU(Math.max(t, 39.6));
  const head = feedPoint(u);
  const follow = [head[0] + 7.5, 6.2, head[2] + 11.5, head[0] - 3.5, 0.4, head[2] + 0.5, 38];
  const startFollow = keys(39.6, LINK);
  const toFollow = ramp(t, 39.6, 1.6, "inOut");
  let v = startFollow.map((x, i) => mix(x, follow[i], toFollow));
  const wide = [8, 30, 50, 4, 0, 4, 42];
  const outlet = [c01x + 9, 7.5, 17, c01x, 0.8, 4.5, 40];
  v = v.map((x, i) => mix(x, outlet[i], ramp(t, 45.6, 1.4, "inOut")));
  v = v.map((x, i) => mix(x, wide[i], ramp(t, 48.0, 2.4, "inOut")));
  return v;
}

// 分屏：画面左半部分展示 C-01 与泵房（渲染整幅，再平移裁切到左栏）
const SPLIT = [52, 17, 30, 10, 0, 5, 48];
const CLOSE: K[] = [
  k(71.6, [40, 7.5, 24], [33, 1, 9], 40),
  k(76.4, [74, 58, 92], [2, 0, -4], 40, "soft"),
  k(80, [80, 64, 100], [2, 0, -4], 40, "lin"),
];

export function cam3d(t: number): Cam {
  let v: number[];
  if (t < 8) v = keys(t, OPEN);
  else if (t < 71) {
    v = linkCam(Math.min(t, 50));
    if (t > 50) v = v.map((x, i) => mix(x, SPLIT[i], ramp(t, 50, 1.4, "inOut")));
  } else v = keys(t, CLOSE);
  return { position: [v[0], v[1], v[2]], lookAt: [v[3], v[4], v[5]], fov: v[6] };
}

export function fx3d(t: number): Fx {
  const running = t >= T.lampOn && t < T.stop;
  const lamp = ramp(t, T.lampOn, 0.25) * (1 - ramp(t, T.stop, 0.5));
  // 叶轮角度：启动加速、停止减速（对速度积分得到角度）
  const spin = (x: number) => {
    const a = T.fanOn, b = T.stop;
    const acc = 1.2, w = 22; // rad/s
    if (x <= a) return 0;
    const up = Math.min(x, a + acc) - a;
    let ang = (w / (2 * acc)) * up * up;
    if (x > a + acc) ang += w * (Math.min(x, b) - (a + acc));
    if (x > b) { const d = Math.min(x - b, 1.4); ang += w * d - (w / (2 * 1.4)) * d * d; }
    return ang;
  };
  const drain = ramp(t, T.drain[0], T.drain[1] - T.drain[0], "soft");
  const inClose = t >= 71;
  const open = t < 8;
  return {
    pump: inClose ? 0 : lamp,
    fanAngle: inClose ? 0 : spin(t),
    xray: inClose || open ? 0 : env(t, T.xray, 50.8, 0.8, 1.2),
    flow: inClose || open ? 0 : flowU(t),
    branch: inClose || open ? 0 : ramp(t, T.branch[0], T.branch[1] - T.branch[0], "lin"),
    valve: inClose || open ? 0 : ramp(t, T.valve[0], T.valve[1] - T.valve[0], "inOut"),
    spray: inClose || open ? 0 : ramp(t, T.spray, 0.6) * (1 - drain),
    wet: inClose || open ? 0 : clamp01((t - T.spray) / 9) * 1,
    waterAlpha: 1 - drain,
    flowPhase: running || t < T.drain[1] ? t * 1.6 : T.drain[1] * 1.6,
    zones: open ? ramp(t, 2.4, 1.0) * (1 - ramp(t, 4.72, 0.3)) : inClose ? 0 : env(t, 30.5, 62.8, 0.4, 0.6),
    c01: open ? ramp(t, 3.2, 0.9) * (1 - ramp(t, 4.72, 0.12)) : inClose ? 0 : env(t, 30.52, 62.8, 0.15, 0.6),
    plot3: open ? env(t, 1.4, 4.6, 0.9, 0.8) : 0,
    pulse: undefined,
  };
}

export function layer3d(t: number) {
  // 是否渲染三维层，以及分屏时的裁切
  const visible = t < T.openEnd + 0.05 || (t >= T.match2[0] && t < T.splitOut[1] + 0.05) || t >= T.closeIn;
  const split = t < 70 ? ramp(t, T.splitIn[0], T.splitIn[1] - T.splitIn[0], "inOut") * (1 - ramp(t, T.splitOut[0], T.splitOut[1] - T.splitOut[0], "inOut")) : 0;
  return { visible, split };
}
