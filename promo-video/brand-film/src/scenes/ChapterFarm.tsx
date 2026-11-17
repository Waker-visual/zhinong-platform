import React from "react";
import { AbsoluteFill, OffthreadVideo, Sequence, staticFile } from "remotion";
import { C, FONT, FPS, S, P, ramp, env, mix, clamp01, bar, Lines, Label, Spark, SparkArc, Paper, lerp2, card } from "../kit";
import { MorphQuad } from "@task/film/Scenes3D";
import { MAP_C01 } from "./ChapterXiaohe";
import world from "../content/farm/world.json";
import arrive from "../content/farm/arrive.json";
import wide from "../content/farm/wide.json";
import low from "../content/farm/low.json";
import command from "../content/farm/command.json";
import pump from "../content/farm/pump.json";
import pipe from "../content/farm/pipe.json";
import outlet from "../content/farm/outlet.json";
import high from "../content/farm/high.json";

// 第二章：三维农场。画面是产品里 Unity 三维场景的逐帧实录（capture/farm.mjs），
// 标注靠每帧的“地面→屏幕”单应矩阵贴在画面上；镜头之间在小节线上硬切。
type Pt = [number, number];
type Track = { fps: number; frames: { H: number[] | null; p: Record<string, number[]> }[] };
type Take = { id: string; from: number; to: number; track: Track; lead?: number; zoom: [number, number]; origin?: Pt };

const f = (s: number) => Math.round(s * FPS);
const CUT = { low: bar(85.3), pipe: bar(104.6), outlet: bar(109.4) };
const START = S.turn + 1.0;
const TAKES: Take[] = [
  { id: "arrive", from: START, to: S.farm, track: arrive as Track, zoom: [1.16, 1.0] },
  { id: "wide", from: S.farm, to: CUT.low, track: wide as Track, zoom: [1.0, 1.06] },
  { id: "low", from: CUT.low, to: S.command, track: low as Track, zoom: [1.06, 1.0] },
  { id: "command", from: S.command, to: S.pump, track: command as Track, zoom: [1.0, 1.1] },
  { id: "pump", from: S.pump, to: CUT.pipe, track: pump as Track, lead: 30, zoom: [1.12, 1.0] },
  { id: "pipe", from: CUT.pipe, to: CUT.outlet, track: pipe as Track, zoom: [1.0, 1.08] },
  { id: "outlet", from: CUT.outlet, to: S.wide, track: outlet as Track, zoom: [1.08, 1.0] },
  { id: "high", from: S.wide, to: S.real, track: high as Track, zoom: [1.0, 1.08] },
];
const leadOf = (k: Take) => k.lead ?? Math.max(0, Math.round((k.track.frames.length - f(k.to - k.from)) / 2));
// 取镜头与跟踪帧都按整数帧算，与素材 Sequence 的起点一致，避免切点上错位一帧
const takeAt = (t: number) => { const n = Math.round(t * FPS); return TAKES.find((k) => n >= f(k.from) && n < f(k.to)) ?? null; };

// 产品回传的投影点已是“左上为原点”的屏幕比例坐标，无需翻转
const FLIP_Y = false;
function useShot(t: number) {
  const k = takeAt(t);
  if (!k) return null;
  const i = Math.min(k.track.frames.length - 1, Math.max(0, Math.round(t * FPS) - f(k.from) + leadOf(k)));
  const fr = k.track.frames[i];
  const u = clamp01((t - k.from) / (k.to - k.from));
  const settle = 1 + 0.03 * (1 - ramp(t, k.from, 0.55, "out")); // 切入时轻微回落
  const s = mix(k.zoom[0], k.zoom[1], u * u * (3 - 2 * u)) * settle;
  const o: Pt = k.origin ?? [960, 540];
  const xf = (p: Pt): Pt => [o[0] + (p[0] - o[0]) * s, o[1] + (p[1] - o[1]) * s];
  const view = (v: number[]): Pt => xf([v[0] * 1920, (FLIP_Y ? 1 - v[1] : v[1]) * 1080]);
  // 单应矩阵只确定到一个比例因子：用最靠近画面中心的田块定出“镜头前方”的符号
  const H = fr.H;
  const raw = (g: number[]) => { const w = H![6] * g[0] + H![7] * g[1] + H![8]; return { w, u: (H![0] * g[0] + H![1] * g[1] + H![2]) / w, v: (H![3] * g[0] + H![4] * g[1] + H![5]) / w }; };
  let front = 1;
  if (H) { let best = Infinity; for (const c of CENTRES) { const r = raw(c); const d = Math.hypot(r.u - 0.5, r.v - 0.5); if (d < best) { best = d; front = Math.sign(r.w) || 1; } } }
  const ground = (g: number[]): Pt => {
    if (!H) return [-9999, -9999];
    const r = raw(g);
    if (r.w * front <= 1e-9) return [-9999, -9999];
    return view([r.u, r.v]);
  };
  return { k, s, o, ground, pump: fr.p.pump ? view(fr.p.pump) : ground(world.G.pump), gate: fr.p.gate ? view(fr.p.gate) : ground(world.G.gate) };
}

const PLOTS = world.plots as { parcelId: string; plotId: string; crop: string; points: number[][] }[];
const C01 = PLOTS[6];
const CENTRES = PLOTS.map((p) => [p.points.reduce((a, q) => a + q[0], 0) / p.points.length, p.points.reduce((a, q) => a + q[1], 0) / p.points.length]);
const centroid = (pts: number[][]): number[] => [pts.reduce((a, p) => a + p[0], 0) / pts.length, pts.reduce((a, p) => a + p[1], 0) / pts.length];
const CROP: Record<string, string> = { rice: "水稻", paddy: "水稻", vegetable: "蔬菜", wheat: "小麦", corn: "玉米", maize: "玉米", soybean: "大豆" };
const ZONES = [0, 1, 2].map((z) => {
  const ps = PLOTS.slice(z * 3, z * 3 + 3);
  return { name: `演示分区 ${z + 1}`, crop: CROP[ps[0].crop] ?? "", at: centroid(ps.map((p) => centroid(p.points))) };
});
const PIPE: number[][] = [world.G.pump, ...(world.zone.points as number[][]).slice(1)];
const PIPE_LEN = PIPE.slice(1).map((p, i) => Math.hypot(p[0] - PIPE[i][0], p[1] - PIPE[i][1]));
const PIPE_TOTAL = PIPE_LEN.reduce((a, b) => a + b, 0);
const pipeAt = (u: number): number[] => {
  let d = clamp01(u) * PIPE_TOTAL;
  for (let i = 0; i < PIPE_LEN.length; i++) { if (d <= PIPE_LEN[i] || i === PIPE_LEN.length - 1) { const q = PIPE_LEN[i] ? d / PIPE_LEN[i] : 0; return [mix(PIPE[i][0], PIPE[i + 1][0], q), mix(PIPE[i][1], PIPE[i + 1][1], q)]; } d -= PIPE_LEN[i]; }
  return PIPE[PIPE.length - 1];
};

// 关键时刻
const F = {
  cmd: [S.command + 1.0, S.command + 2.5] as const, // 指令弧线：C-01 → 泵房
  receive: S.command + 2.6,
  run: S.pump + 0.1, // 实录里水泵在此刻启动
  flow: [S.pump + 1.4, CUT.outlet + 0.9] as const, // 水沿管路到出水口（标注为示意）
  spray: CUT.outlet + 0.9,
};
const flowU = (t: number) => ramp(t, F.flow[0], F.flow[1] - F.flow[0], "inOut");

const Tag: React.FC<{ at: Pt; title: string; status?: string; tone?: "run" | "recv" | "idle"; p: number; small?: boolean }> = ({ at, title, status, tone = "idle", p, small }) => {
  if (p <= 0 || at[0] < -500) return null;
  const dot = tone === "run" ? C.run : tone === "recv" ? C.gold : "#9aa59f";
  return (
    <div style={{ position: "absolute", left: at[0], top: at[1], transform: `translate(-50%, -100%) translateY(${-22 - (1 - p) * 14}px)`, opacity: p }}>
      <div style={{ padding: small ? "10px 18px" : "14px 24px", borderRadius: 14, background: "rgba(9,24,18,.88)", border: "1px solid rgba(223,188,112,.5)", boxShadow: "0 20px 50px -18px rgba(0,0,0,.55)", whiteSpace: "nowrap" }}>
        <div style={{ fontFamily: FONT.sans, fontWeight: 600, fontSize: small ? 24 : 30, color: C.cream, letterSpacing: "0.04em" }}>{title}</div>
        {status && <div style={{ display: "flex", alignItems: "center", gap: 10, marginTop: 6, fontFamily: FONT.sans, fontSize: small ? 20 : 23, color: "rgba(245,242,230,.8)" }}>
          <span style={{ width: 11, height: 11, borderRadius: 6, background: dot, boxShadow: tone === "run" ? `0 0 12px ${C.run}` : undefined }} />{status}
        </div>}
      </div>
      <div style={{ position: "absolute", left: "50%", bottom: -20, width: 2, height: 20, background: "rgba(223,188,112,.85)" }} />
      <div style={{ position: "absolute", left: "50%", bottom: -26, width: 8, height: 8, marginLeft: -3, borderRadius: 4, background: C.gold }} />
    </div>
  );
};

const STEPS = ["指令下发", "水泵启动", "管路输水", "田间出水"];
const STEP_AT = [F.cmd[0], F.run, F.flow[0] + 0.4, F.spray];

/** 实录层（置于纸面之下）：章节转场时由纸面让出 */
export const FarmLayer: React.FC<{ t: number }> = ({ t }) => {
  if (t < START - 0.05 || Math.round(t * FPS) >= f(S.real)) return null;
  const sh = useShot(t);
  return (
    <AbsoluteFill style={{ background: "#0a1811" }}>
      <AbsoluteFill style={sh ? { transform: `scale(${sh.s})`, transformOrigin: `${sh.o[0]}px ${sh.o[1]}px` } : undefined}>
        {TAKES.map((k) => (
          <Sequence key={k.id} from={f(k.from)} durationInFrames={Math.max(1, f(k.to) - f(k.from))} layout="none">
            <OffthreadVideo src={staticFile(`rec/f-${k.id}.mp4`)} muted trimBefore={leadOf(k)} style={{ width: 1920, height: 1080 }} />
          </Sequence>
        ))}
      </AbsoluteFill>
      {/* 统一影调：略压高光、加一层暖色，贴近全片的纸面与墨绿 */}
      <AbsoluteFill style={{ background: "linear-gradient(180deg, rgba(8,24,17,.16) 0%, rgba(8,24,17,0) 30%, rgba(8,24,17,.22) 100%)" }} />
      <AbsoluteFill style={{ background: "rgba(223,188,112,.06)", mixBlendMode: "multiply" }} />
    </AbsoluteFill>
  );
};

/** 实录之上的标注、指令光点、标题，以及片尾前的展示大屏实录 */
export const FarmOverlay: React.FC<{ t: number }> = ({ t }) => {
  if (t < START || t > S.montage + 0.9) return null;
  const sh = useShot(t);
  const id = sh?.k.id;
  const poly = (pts: number[][]) => pts.map((p) => sh!.ground(p).join(",")).join(" ");

  // 章节转场：地图上的 C-01 → 实录里的 C-01
  const morph = ramp(t, S.turn + 1.2, 1.25, "inOut");
  const quad3d = sh ? C01.points.map((p) => sh.ground(p)) : MAP_C01;
  const quad = MAP_C01.map((q, i) => lerp2(q, quad3d[i] as Pt, morph));
  const quadP = ramp(t, S.turn + 1.15, 0.1) * (1 - ramp(t, S.farm - 0.5, 0.45));

  const c01c = sh ? sh.ground(centroid(C01.points)) : ([0, 0] as Pt);
  const arc = ramp(t, F.cmd[0], F.cmd[1] - F.cmd[0], "inOut");
  const recv = clamp01((t - F.receive) / 0.9);
  const state = t < F.receive ? { s: "指令下发中", tone: "recv" as const } : t < F.run ? { s: "模拟接收 · 待启动", tone: "recv" as const } : { s: "运行中 · 模拟", tone: "run" as const };

  const titleScrim = env(t, S.farm - 0.2, CUT.low - 0.2, 0.8, 0.5);
  const wideScrim = env(t, S.wide + 0.05, S.real - 0.1, 0.7, 0.25);
  const scrim = Math.max(titleScrim, wideScrim);
  const cur = STEP_AT.filter((a) => t >= a).length - 1;
  const rail = env(t, F.cmd[0] - 0.2, S.wide - 0.2, 0.5, 0.4);
  const fu = flowU(t);
  const showPipe = id === "pump" || id === "pipe" || id === "outlet" || id === "high" || (id === "command" && t > F.receive);

  return (
    <AbsoluteFill>
      {sh && (
        <>
          <AbsoluteFill style={{ background: `linear-gradient(90deg, rgba(6,18,13,${0.78 * scrim}) 0%, rgba(6,18,13,${0.34 * scrim}) 46%, rgba(6,18,13,0) 72%)` }} />
          <svg width={1920} height={1080} style={{ position: "absolute", inset: 0 }}>
            {/* 九块小田的轮廓：低机位全景里逐块点亮 */}
            {id === "low" && PLOTS.map((pl, i) => {
              const p = env(t, CUT.low + 0.5 + i * 0.12, S.command - 0.5, 0.5, 0.4);
              return p > 0 ? <polygon key={pl.parcelId} points={poly(pl.points)} fill={`rgba(223,188,112,${0.1 * p})`} stroke={C.gold} strokeOpacity={0.85 * p} strokeWidth={2.5} strokeLinejoin="round" /> : null;
            })}
            {/* 本次任务的田块 */}
            {(id === "arrive" || id === "command" || id === "outlet" || id === "high") && (() => {
              const p = id === "arrive" ? ramp(t, S.farm - 0.5, 0.4) * 0 + ramp(t, S.turn + 2.6, 0.6) * (1 - ramp(t, S.farm - 0.4, 0.3)) : id === "command" ? env(t, S.command + 0.3, S.pump, 0.6, 0.3) : id === "outlet" ? 1 : ramp(t, S.wide + 0.2, 0.6);
              const wet = id === "outlet" ? ramp(t, F.spray, 2.4) : id === "high" ? 1 : 0;
              return <polygon points={poly(C01.points)} fill={wet > 0 ? `rgba(120,200,230,${0.22 * wet * p})` : `rgba(223,188,112,${0.12 * p})`} stroke={C.gold} strokeOpacity={p} strokeWidth={4} strokeLinejoin="round" />;
            })()}
            {/* 管路与水流（示意）：沿登记的管线走向 */}
            {showPipe && (() => {
              const o = id === "command" ? ramp(t, F.receive, 0.8) * 0.6 : 1;
              const N = 48; const pts = Array.from({ length: N + 1 }, (_, i) => sh.ground(pipeAt(i / N)));
              const done = pts.slice(0, Math.max(1, Math.round(fu * N) + 1));
              return (
                <g opacity={o}>
                  <polyline points={pts.map((p) => p.join(",")).join(" ")} fill="none" stroke="rgba(245,242,230,.55)" strokeWidth={3} strokeDasharray="3 12" strokeLinecap="round" />
                  {fu > 0 && <polyline points={done.map((p) => p.join(",")).join(" ")} fill="none" stroke="#8fdcf2" strokeWidth={7} strokeLinecap="round" strokeLinejoin="round" style={{ filter: "drop-shadow(0 0 10px rgba(143,220,242,.9))" }} />}
                </g>
              );
            })()}
          </svg>
          <MorphQuad pts={quad} p={quadP} />

          {/* 指令光点：停在 C-01，随后沿弧线到泵房 */}
          {id === "arrive" && <Spark x={c01c[0]} y={c01c[1]} r={11} opacity={ramp(t, S.turn + 2.3, 0.4) * (1 - ramp(t, S.farm - 0.35, 0.3))} />}
          {id === "command" && (() => {
            const a = c01c, b = sh.pump; const ctrl: Pt = [(a[0] + b[0]) / 2, Math.min(a[1], b[1]) - 300];
            return (
              <>
                <Spark x={a[0]} y={a[1]} r={11} opacity={ramp(t, S.command + 0.25, 0.4) * (arc <= 0 ? 1 : 0)} />
                {arc > 0 && arc < 1 && <SparkArc a={a} c={ctrl} b={b} u={arc} r={11} trail={0.3} />}
                {recv > 0 && recv < 1 && <div style={{ position: "absolute", left: b[0] - 24 - 130 * recv, top: b[1] - 24 - 130 * recv, width: 48 + 260 * recv, height: 48 + 260 * recv, borderRadius: "50%", border: `5px solid ${C.gold}`, opacity: 1 - recv }} />}
              </>
            );
          })()}
          {showPipe && fu > 0 && fu < 1 && (() => { const h = sh.ground(pipeAt(fu)); return <Spark x={h[0]} y={h[1]} r={8} opacity={id === "command" ? 0 : 1} core="#e6f9ff" />; })()}

          {/* 标注 */}
          {id === "arrive" && <Tag at={c01c} title="C-01" status="演示分区 3" p={ramp(t, S.turn + 3.0, 0.5) * (1 - ramp(t, S.farm - 0.4, 0.3))} small />}
          {id === "low" && ZONES.map((z, i) => <Tag key={z.name} at={sh.ground(z.at)} title={z.name} status={z.crop || undefined} p={env(t, CUT.low + 1.4 + i * 0.35, S.command - 0.45, 0.5, 0.35)} small />)}
          {id === "command" && <Tag at={c01c} title="C-01" status="本次灌溉任务" tone="recv" p={env(t, S.command + 0.5, S.pump - 0.3, 0.5, 0.3)} small />}
          {(id === "command" || id === "pump") && <Tag at={sh.pump} title="演示泵房控制器" status={state.s} tone={state.tone} p={id === "command" ? env(t, F.cmd[1] - 0.3, S.pump, 0.5, 0.2) : env(t, S.pump + 0.5, CUT.pipe - 0.3, 0.5, 0.3)} />}
          {id === "pipe" && <Tag at={sh.ground(world.G.valve)} title="支管阀门（示意）" p={env(t, CUT.pipe + 1.6, CUT.outlet - 0.3, 0.5, 0.3)} small />}
          {id === "outlet" && <Tag at={sh.ground(world.G.outlet)} title="C-01 田间出水" status="仅本次任务覆盖区" tone="run" p={env(t, F.spray + 0.2, S.wide - 0.3, 0.5, 0.3)} small />}

          <AbsoluteFill style={{ background: "linear-gradient(180deg, rgba(6,18,13,.5) 0%, rgba(6,18,13,0) 22%)", opacity: env(t, S.farm + 0.1, S.real - 0.1, 0.6, 0.3) }} />
          {/* 第二章标题 */}
          <div style={{ position: "absolute", left: 140, top: 132, display: "flex", gap: 22, opacity: env(t, S.farm + 0.2, S.real - 0.2, 0.6, 0.3) }}>
            <Label color={C.gold}>02</Label><Label color="rgba(245,242,230,.86)">3D 农场 · 应用实录</Label>
          </div>
          <Lines t={t} from={S.farm + 0.1} out={CUT.low - 0.5} lines={["整座农场，", "一眼看全。"]} gap={0.18}
            style={{ position: "absolute", left: 140, top: 360 }}
            lineStyle={{ fontFamily: FONT.serif, fontWeight: 700, fontSize: 132, lineHeight: 1.22, letterSpacing: "0.06em", color: C.cream, textShadow: "0 10px 50px rgba(0,0,0,.45)" }} />
          <div style={{ position: "absolute", left: 146, top: 716, fontFamily: FONT.sans, fontSize: 30, letterSpacing: "0.12em", color: "rgba(245,242,230,.86)", opacity: env(t, S.farm + 1.4, CUT.low - 0.5, 0.7, 0.4) }}>3 个分区 · 9 块小田 · 16 台设备</div>

          {/* 联动步骤 */}
          <div style={{ position: "absolute", left: 140, bottom: 96, display: "flex", alignItems: "center", gap: 14, opacity: rail, fontFamily: FONT.sans }}>
            {STEPS.map((s, i) => (
              <React.Fragment key={s}>
                {i > 0 && <span style={{ width: 44, height: 2, background: i <= cur ? C.gold : "rgba(245,242,230,.3)" }} />}
                <span style={{ padding: "10px 20px", borderRadius: 26, fontSize: 24, letterSpacing: "0.08em", fontWeight: i === cur ? 700 : 500, background: i === cur ? C.gold : "rgba(9,24,18,.72)", color: i === cur ? C.ink : i < cur ? "rgba(245,242,230,.92)" : "rgba(245,242,230,.55)", border: `1px solid ${i <= cur ? "rgba(223,188,112,.7)" : "rgba(245,242,230,.18)"}` }}>{s}</span>
              </React.Fragment>
            ))}
          </div>
          <div style={{ position: "absolute", right: 64, top: 124, opacity: env(t, F.cmd[0], S.real - 0.2, 0.5, 0.3), display: "flex", alignItems: "center", gap: 12, padding: "10px 20px", borderRadius: 30, background: "rgba(9,24,18,.72)", border: "1px solid rgba(223,188,112,.4)", fontFamily: FONT.sans, fontSize: 22, letterSpacing: "0.08em", color: C.cream }}>
            <span style={{ width: 10, height: 10, borderRadius: 5, background: C.gold }} />设备联动演示｜模拟运行
          </div>

          <Lines t={t} from={S.wide + 0.15} out={S.real - 0.45} lines={["设备怎么动，", "都看得见。"]} gap={0.16}
            style={{ position: "absolute", left: 140, top: 372 }}
            lineStyle={{ fontFamily: FONT.serif, fontWeight: 700, fontSize: 124, lineHeight: 1.22, letterSpacing: "0.06em", color: C.cream, textShadow: "0 10px 50px rgba(0,0,0,.5)" }} />
        </>
      )}

      {/* 展示大屏实录：刚才的画面就在产品的这一页里 */}
      {Math.round(t * FPS) >= f(S.real) && (() => {
        const p = ramp(t, S.real, 0.9, "out");
        return (
          <Paper>
            <div style={{ position: "absolute", left: 140, top: 96, display: "flex", gap: 22, opacity: ramp(t, S.real + 0.3, 0.5) }}>
              <Label>02</Label><Label color={P.sub}>展示大屏 · 三维农场页实录</Label>
            </div>
            <div style={{ position: "absolute", left: 140, top: 156, width: 1640, height: 922.5, overflow: "hidden", transform: `scale(${mix(1.2, 1, p)})`, transformOrigin: "50% 40%", ...card({ borderRadius: 26 }) }}>
              <Sequence from={f(S.real)} durationInFrames={f(S.montage - S.real) + 60} layout="none">
                <OffthreadVideo src={staticFile("rec/d-farm3d.mp4")} muted style={{ width: 1640, height: 922.5, display: "block" }} />
              </Sequence>
            </div>
          </Paper>
        );
      })()}
    </AbsoluteFill>
  );
};
