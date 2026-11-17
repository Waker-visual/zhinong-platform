// 三维农场的镜头与设备状态轨道。时间为全片秒；关键时刻都挂在配乐小节线上（见 kit.S）。
import { keys, ramp, env, clamp01, mix, S, type Key } from "../kit";
import type { Cam, Fx, V3 } from "@task/Farm3D";
import { C01, feedPoint } from "@task/film/track3d";

const c01x = C01.center[0];
type K = Key<number[]>;
const k = (t: number, p: V3, l: V3, fov = 40, e?: K["e"]): K => ({ t, v: [...p, ...l, fov], e });

export const F = {
  show: S.turn + 1.1, // 三维层开始渲染
  lift: S.turn + 2.5, // 俯视 → 抬起
  cmd: [S.command + 0.6, S.command + 1.9] as const, // 指令弧线：C-01 → 泵房
  receive: S.command + 2.0,
  lamp: S.pump,
  fan: S.pump + 0.25,
  xray: S.pump + 1.0,
  flow: [S.pump + 1.6, S.pump + 9.0] as const,
  valve: [S.pump + 8.7, S.pump + 9.5] as const,
  branch: [S.pump + 9.3, S.pump + 9.9] as const,
  spray: S.pump + 9.8,
  hide: S.real + 0.05,
};

export const flowU = (t: number) => ramp(t, F.flow[0], F.flow[1] - F.flow[0], "inOut");

const TOP: number[] = [c01x, 31, 9.7, c01x, 0, 9.0, 40];
const TRACK: K[] = [
  k(F.show, [c01x, 31, 9.7], [c01x, 0, 9.0], 40),
  k(F.lift, [c01x, 31, 9.7], [c01x, 0, 9.0], 40),
  k(S.farm + 0.6, [10, 60, 122], [-4, 0, 2], 38, "inOut"), // 抬起：整座农场入画
  k(S.farm + 6.4, [36, 46, 100], [0, 0, 2], 38, "soft"), // 缓慢环绕
  k(S.command, [3, 27, 60], [-12, 0, 8], 40, "inOut"), // 推向演示分区 3
  k(S.command + 1.9, [18, 34, 46], [10, 0, 6], 46, "inOut"), // C-01 与泵房同框
  k(S.command + 4.8, [42, 11, 27], [31, 0.6, 9], 40, "inOut"), // 定位泵房
  k(S.pump - 0.4, [39.5, 4.6, 17.5], [32.6, 1.0, 9.0], 36, "inOut"), // 水泵近景，卡在高潮前
  k(S.pump + 2.0, [37.4, 3.3, 14.6], [32.4, 0.9, 9.0], 34, "soft"),
  k(S.pump + 3.6, [36.0, 4.2, 15.6], [30.0, 0.7, 8.2], 36, "inOut"), // 管路剖面
];
const FOLLOW0 = S.pump + 3.6;

export function cam3d(t: number): Cam {
  let v: number[];
  if (t <= FOLLOW0) v = keys(Math.max(t, F.show), TRACK);
  else {
    const head = feedPoint(flowU(t));
    const follow = [head[0] + 7.5, 6.2, head[2] + 11.5, head[0] - 3.5, 0.4, head[2] + 0.5, 38];
    v = keys(FOLLOW0, TRACK).map((x, i) => mix(x, follow[i], ramp(t, FOLLOW0, 1.6, "inOut")));
    const outlet = [c01x + 9, 7.5, 17, c01x, 0.8, 4.5, 40];
    const wide = [8, 30, 50, 4, 0, 4, 42];
    const high = [70, 56, 90, 2, 0, -4, 40];
    v = v.map((x, i) => mix(x, outlet[i], ramp(t, F.flow[1] - 0.8, 1.4, "inOut")));
    v = v.map((x, i) => mix(x, wide[i], ramp(t, F.spray + 1.2, S.wide - F.spray - 1.2, "inOut")));
    v = v.map((x, i) => mix(x, high[i], ramp(t, S.wide, S.real - S.wide + 1.2, "soft")));
  }
  return { position: [v[0], v[1], v[2]], lookAt: [v[3], v[4], v[5]], fov: v[6] };
}
export const TOP_CAM: Cam = { position: [TOP[0], TOP[1], TOP[2]], lookAt: [TOP[3], TOP[4], TOP[5]], fov: TOP[6] };

export function fx3d(t: number): Fx {
  const lamp = ramp(t, F.lamp, 0.25);
  // 叶轮角度：对转速积分（1.2 s 加速到 22 rad/s）
  const up = clamp01((t - F.fan) / 1.2);
  const ang = t <= F.fan ? 0 : (22 / 2.4) * Math.min(t - F.fan, 1.2) ** 2 + 22 * Math.max(0, t - F.fan - 1.2) * (up >= 1 ? 1 : 0);
  return {
    pump: lamp,
    fanAngle: ang,
    xray: env(t, F.xray, S.wide + 1.5, 0.8, 1.6),
    flow: flowU(t),
    branch: ramp(t, F.branch[0], F.branch[1] - F.branch[0], "lin"),
    valve: ramp(t, F.valve[0], F.valve[1] - F.valve[0], "inOut"),
    spray: ramp(t, F.spray, 0.6),
    wet: clamp01((t - F.spray) / 8),
    waterAlpha: 1,
    flowPhase: t * 1.6,
    zones: env(t, F.lift + 0.6, S.real, 0.8, 0.4) * 0.9,
    c01: env(t, F.show, S.real, 0.2, 0.4),
    plot3: env(t, S.farm + 1.0, S.command + 1.5, 0.9, 0.8),
  };
}
