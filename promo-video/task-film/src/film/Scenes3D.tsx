import React from "react";
import { AbsoluteFill } from "remotion";
import { C, FONT, ramp, env, clamp01, mix, Shot } from "../kit";
import { makeProjector, type Projector, type V3 } from "../Farm3D";
import { T, cam3d, C01, C01_CORNERS, PUMP_TAG, OUTLET_TAG, flowU, feedPoint } from "./track3d";
import { Logo } from "./Logo";

export const project3d = (t: number): Projector => makeProjector(cam3d(t), 1920, 1080);

// ---------- 片头 ----------
export const OpeningOverlay: React.FC<{ t: number }> = ({ t }) => {
  const title = env(t, 0.35, 4.4, 0.9, 0.6);
  if (t > 5.6) return null;
  return (
    <AbsoluteFill>
      <div style={{ position: "absolute", inset: 0, background: `linear-gradient(90deg, rgba(9,24,18,${0.62 * title}) 0%, rgba(9,24,18,${0.25 * title}) 45%, rgba(9,24,18,0) 70%)` }} />
      <div style={{ position: "absolute", left: 140, top: 330, opacity: title, transform: `translateY(${(1 - title) * 18}px)` }}>
        <div style={{ display: "flex", alignItems: "center", gap: 34 }}>
          <Logo size={128} grow={ramp(t, 0.4, 1.4, "out")} />
          <div>
            <div style={{ fontFamily: FONT.serif, fontWeight: 700, fontSize: 112, color: C.cream, letterSpacing: "0.08em", textShadow: "0 10px 40px rgba(0,0,0,.35)" }}>智禾农场</div>
            <div style={{ fontFamily: FONT.mono, fontSize: 24, letterSpacing: "0.32em", color: C.goldSoft, marginTop: 4 }}>ZHIHE FARM · 灌溉任务演示</div>
          </div>
        </div>
        <div style={{ marginTop: 56, width: 92 * ramp(t, 1.1, 0.8), height: 4, background: C.gold }} />
        <div style={{ marginTop: 34, fontFamily: FONT.serif, fontWeight: 500, fontSize: 60, color: C.cream, letterSpacing: "0.06em", opacity: ramp(t, 1.2, 0.9), textShadow: "0 8px 30px rgba(0,0,0,.4)" }}>让每一次决策，落实到田间。</div>
      </div>
    </AbsoluteFill>
  );
};

// ---------- 二维 ↔ 三维 C-01 轮廓匹配 ----------
export const MorphQuad: React.FC<{ pts: [number, number][]; p: number; glow?: number }> = ({ pts, p, glow = 1 }) => {
  if (p <= 0) return null;
  const d = pts.map((q, i) => `${i ? "L" : "M"}${q[0].toFixed(1)},${q[1].toFixed(1)}`).join(" ") + "Z";
  return (
    <svg width={1920} height={1080} style={{ position: "absolute", left: 0, top: 0 }}>
      <path d={d} fill={`rgba(239,217,166,${0.28 * p})`} stroke="rgba(223,188,112,.45)" strokeWidth={22 * glow} strokeOpacity={p} strokeLinejoin="round" />
      <path d={d} fill="none" stroke={C.gold} strokeWidth={6} strokeOpacity={p} strokeLinejoin="round" />
    </svg>
  );
};
export const c01Screen = (t: number) => C01_CORNERS.map((c) => { const s = project3d(t)(c); return [s.x, s.y] as [number, number]; });

// ---------- 设备标签：与系统界面中的设备名称一致 ----------
const Tag: React.FC<{ x: number; y: number; title: string; status?: string; tone?: "run" | "idle" | "recv" | "end"; p: number; small?: boolean }> = ({ x, y, title, status, tone = "idle", p, small }) => {
  if (p <= 0) return null;
  const dot = tone === "run" ? C.run : tone === "recv" ? C.gold : tone === "end" ? "#9aa59f" : "#9aa59f";
  return (
    <div style={{ position: "absolute", left: x, top: y, transform: `translate(-50%, -100%) translateY(${-18 - (1 - p) * 12}px)`, opacity: p }}>
      <div style={{ padding: small ? "10px 18px" : "14px 24px", borderRadius: 14, background: "rgba(9,24,18,.9)", border: "1px solid rgba(223,188,112,.55)", boxShadow: "0 20px 50px -18px rgba(0,0,0,.55)", whiteSpace: "nowrap" }}>
        <div style={{ fontFamily: FONT.sans, fontWeight: 600, fontSize: small ? 24 : 30, color: C.cream, letterSpacing: "0.04em" }}>{title}</div>
        {status && <div style={{ display: "flex", alignItems: "center", gap: 10, marginTop: 6, fontFamily: FONT.sans, fontSize: small ? 20 : 23, color: "rgba(245,242,230,.82)" }}>
          <span style={{ width: 11, height: 11, borderRadius: 6, background: dot, boxShadow: tone === "run" ? `0 0 12px ${C.run}` : undefined }} />{status}
        </div>}
      </div>
      <div style={{ position: "absolute", left: "50%", bottom: -16, width: 2, height: 16, background: "rgba(223,188,112,.8)" }} />
    </div>
  );
};

const Callout: React.FC<{ from: [number, number]; to: [number, number]; text: string; p: number }> = ({ from, to, text, p }) => {
  if (p <= 0) return null;
  return (
    <>
      <svg width={1920} height={1080} style={{ position: "absolute", left: 0, top: 0, opacity: p }}>
        <line x1={from[0]} y1={from[1]} x2={mix(from[0], to[0], p)} y2={mix(from[1], to[1], p)} stroke={C.gold} strokeWidth={3} />
        <circle cx={from[0]} cy={from[1]} r={9} fill="none" stroke={C.gold} strokeWidth={3} />
      </svg>
      <div style={{ position: "absolute", left: to[0], top: to[1], transform: "translate(0,-50%)", opacity: p, padding: "10px 20px", borderRadius: 12, background: "rgba(251,248,240,.95)", border: `1px solid ${C.gold}`, fontFamily: FONT.sans, fontSize: 28, fontWeight: 600, color: C.text, whiteSpace: "nowrap" }}>{text}</div>
    </>
  );
};

// 指令：暖金光束落入 C-01，再沿弧线抵达泵房控制器
const Command: React.FC<{ t: number; P: Projector }> = ({ t, P }) => {
  const c = P([C01.center[0], 0.4, C01.center[2]]);
  const drop = ramp(t, 30.35, 0.65, "in");
  const land = clamp01((t - T.cmdLand) / 0.9);
  const arc = ramp(t, T.cmdToPump[0], T.cmdToPump[1] - T.cmdToPump[0], "inOut");
  const fade = 1 - ramp(t, 34.6, 0.6);
  if (t < 30.3 || fade <= 0) return null;
  const pump = P(PUMP_TAG);
  const midW: V3 = [(C01.center[0] + 33) / 2, 16, (C01.center[2] + 9) / 2 - 6];
  const m = P(midW);
  const cx = 2 * m.x - (c.x + pump.x) / 2, cy = 2 * m.y - (c.y + pump.y) / 2;
  const L = 1000;
  const bx = (1 - arc) ** 2 * c.x + 2 * (1 - arc) * arc * cx + arc * arc * pump.x;
  const by = (1 - arc) ** 2 * c.y + 2 * (1 - arc) * arc * cy + arc * arc * pump.y;
  return (
    <svg width={1920} height={1080} style={{ position: "absolute", left: 0, top: 0, opacity: fade }}>
      <defs><linearGradient id="beam" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stopColor="#ffe6a8" stopOpacity="0" /><stop offset="1" stopColor="#ffd98a" stopOpacity=".95" /></linearGradient></defs>
      {drop > 0 && t < 31.6 && <rect x={c.x - 7} y={-20} width={14} height={(c.y + 20) * drop} fill="url(#beam)" opacity={1 - ramp(t, 31.0, 0.6)} />}
      {land > 0 && land < 1 && [0, 0.25].map((o, i) => { const q = clamp01(land - o); return <ellipse key={i} cx={c.x} cy={c.y} rx={40 + 260 * q} ry={(40 + 260 * q) * 0.55} fill="none" stroke={C.gold} strokeWidth={5} opacity={1 - q} />; })}
      {land > 0 && <circle cx={c.x} cy={c.y} r={16} fill="#fff1c8" stroke={C.gold} strokeWidth={6} opacity={1 - ramp(t, 33.6, 0.6)} />}
      {arc > 0 && <>
        <path d={`M${c.x},${c.y} Q${cx},${cy} ${pump.x},${pump.y}`} fill="none" stroke="rgba(223,188,112,.35)" strokeWidth={18} strokeLinecap="round" pathLength={L} strokeDasharray={L} strokeDashoffset={L * (1 - arc)} />
        <path d={`M${c.x},${c.y} Q${cx},${cy} ${pump.x},${pump.y}`} fill="none" stroke={C.gold} strokeWidth={6} strokeLinecap="round" pathLength={L} strokeDasharray={L} strokeDashoffset={L * (1 - arc)} />
        {arc < 1 && <circle cx={bx} cy={by} r={14} fill="#fff6dc" stroke={C.gold} strokeWidth={5} />}
      </>}
      {t > T.receive && t < T.receive + 0.9 && (() => { const q = (t - T.receive) / 0.9; return <circle cx={pump.x} cy={pump.y} r={20 + 120 * q} fill="none" stroke={C.gold} strokeWidth={5} opacity={1 - q} />; })()}
    </svg>
  );
};

// 管路剖面：水流方向箭头
const FlowArrows: React.FC<{ t: number; P: Projector; p: number }> = ({ t, P, p }) => {
  if (p <= 0) return null;
  const u = flowU(t);
  const items = Array.from({ length: 6 }, (_, i) => {
    const a = ((i / 6 + t * 0.18) % 1) * Math.min(u, 0.16);
    const s0 = P(feedPoint(a)), s1 = P(feedPoint(a + 0.004));
    const ang = Math.atan2(s1.y - s0.y, s1.x - s0.x) * 180 / Math.PI;
    return { x: s0.x, y: s0.y - 34, ang, show: a < u - 0.004 };
  });
  return (
    <svg width={1920} height={1080} style={{ position: "absolute", left: 0, top: 0, opacity: p }}>
      {items.filter((i) => i.show).map((i, k) => (
        <g key={k} transform={`translate(${i.x},${i.y}) rotate(${i.ang})`}><path d="M-12,-11 L4,0 L-12,11" fill="none" stroke="#dff6ff" strokeWidth={5} strokeLinecap="round" strokeLinejoin="round" /></g>
      ))}
    </svg>
  );
};

const STEPS = ["设备定位", "水泵启动", "管路输水", "田间出水"];
const StepRail: React.FC<{ t: number; p: number }> = ({ t, p }) => {
  if (p <= 0) return null;
  const at = [33.0, 35.6, 39.4, 46.8];
  const cur = at.filter((a) => t >= a).length - 1;
  return (
    <div style={{ position: "absolute", left: 58, top: 112, display: "flex", gap: 10, opacity: p, fontFamily: FONT.sans }}>
      {STEPS.map((s, i) => (
        <div key={s} style={{ display: "flex", alignItems: "center", gap: 10, padding: "8px 16px", borderRadius: 22, fontSize: 22, letterSpacing: "0.06em",
          background: i === cur ? "rgba(223,188,112,.95)" : "rgba(9,24,18,.6)", color: i === cur ? C.ink : i < cur ? "rgba(245,242,230,.9)" : "rgba(245,242,230,.5)",
          border: `1px solid ${i <= cur ? "rgba(223,188,112,.7)" : "rgba(245,242,230,.15)"}` }}>
          <span style={{ fontFamily: FONT.mono, fontSize: 18 }}>{i + 1}</span>{s}
        </div>
      ))}
    </div>
  );
};

export const LinkageOverlay: React.FC<{ t: number }> = ({ t }) => {
  if (t < 30.3 || t > 50.2) return null;
  const P = project3d(t);
  const pump = P(PUMP_TAG);
  const state = t < T.receive ? { s: "指令下发中", tone: "recv" as const } : t < T.lampOn ? { s: "模拟接收 · 待启动", tone: "recv" as const } : { s: "运行中 · 模拟", tone: "run" as const };
  const tagP = env(t, 32.6, 50.1, 0.5, 0.4) * (t > 34.9 && t < 39.6 ? 0.0 + ramp(t, 38.0, 0.6) : 1);
  const lamp = P([33.45, 1.36, 9.7]);
  const lampP = env(t, 35.9, 38.4, 0.4, 0.4);
  const pipeP = env(t, 37.6, 41.6, 0.5, 0.4);
  const pipePt = P(feedPoint(0.035));
  const valve = P([C01.center[0], 1.4, 0]);
  const valveP = env(t, 45.7, 48.0, 0.4, 0.4);
  const out = P(OUTLET_TAG);
  const outP = env(t, 47.2, 50.1, 0.5, 0.4);
  const c01 = P([C01.center[0], 0.5, C01.center[2] + 4]);
  const c01P = env(t, 31.0, 33.0, 0.4, 0.5);
  return (
    <AbsoluteFill>
      <Command t={t} P={P} />
      <FlowArrows t={t} P={P} p={env(t, 37.4, 42.6, 0.5, 0.5)} />
      <Tag x={c01.x} y={c01.y} title="C-01 灌溉覆盖区" status="演示分区 3" p={c01P} small />
      <Tag x={pump.x} y={pump.y} title="演示泵房控制器" status={state.s} tone={state.tone} p={tagP} />
      <Callout from={[lamp.x, lamp.y]} to={[lamp.x + 150, lamp.y - 170]} text="运行指示灯 · 点亮" p={lampP} />
      <Callout from={[pipePt.x, pipePt.y]} to={[pipePt.x - 420, pipePt.y + 150]} text="管路剖面 · 水流方向" p={pipeP} />
      <Callout from={[valve.x, valve.y]} to={[valve.x + 120, valve.y - 150]} text="支管阀门开启（示意）" p={valveP} />
      <Tag x={out.x} y={out.y} title="田间出水 · C-01" status="仅本次任务覆盖区" tone="run" p={outP} small />
      <StepRail t={t} p={env(t, 32.8, 50.1, 0.5, 0.4)} />
    </AbsoluteFill>
  );
};

// ---------- 分屏：现场设备 + 系统界面与模拟状态浮层 ----------
export const splitRect = (s: number) => ({ x: mix(0, 56, s), y: mix(0, 150, s), w: mix(1920, 884, s), h: mix(1080, 780, s), r: 22 * s });
const fmt = (sec: number) => `${String(Math.floor(sec / 60)).padStart(2, "0")}:${String(Math.floor(sec % 60)).padStart(2, "0")}`;

export const SplitOverlay: React.FC<{ t: number; split: number }> = ({ t, split }) => {
  if (split <= 0) return null;
  const right = ramp(t, 50.5, 0.9) * (1 - ramp(t, T.splitOut[0], 0.6));
  const prog = ramp(t, T.progress[0], T.progress[1] - T.progress[0], "lin");
  const ended = t >= T.ended;
  const stopped = t >= T.stop;
  const compress = env(t, 53.3, 61.6, 0.4, 0.4);
  const rect = splitRect(split);
  const P = makeProjector(cam3d(t), 1920, 1080);
  const offX = rect.x + rect.w / 2 - 960, offY = rect.y + rect.h / 2 - 540;
  const pump = P(PUMP_TAG), c01 = P([C01.center[0], 0.5, C01.center[2] + 3]);
  const status = ended ? "运行结束" : stopped ? "停止中" : "模拟运行中";
  const tagStatus = ended ? "运行结束 · 已停止" : stopped ? "停泵中" : "运行中 · 模拟";
  return (
    <AbsoluteFill>
      {/* 左栏标注（与三维画面同一坐标） */}
      <div style={{ position: "absolute", left: rect.x, top: rect.y, width: rect.w, height: rect.h, overflow: "hidden", borderRadius: rect.r, opacity: right }}>
        <div style={{ position: "absolute", left: -rect.x + offX, top: -rect.y + offY, width: 1920, height: 1080 }}>
          <Tag x={pump.x} y={pump.y} title="演示泵房控制器" status={tagStatus} tone={ended || stopped ? "end" : "run"} p={1} small />
          <Tag x={c01.x} y={c01.y} title="C-01" status={ended ? "出水停止" : "田间出水"} tone={ended ? "end" : "run"} p={1} small />
        </div>
        <div style={{ position: "absolute", left: 24, top: 22, padding: "8px 16px", borderRadius: 20, background: "rgba(9,24,18,.7)", fontFamily: FONT.sans, fontSize: 21, letterSpacing: "0.08em", color: C.cream }}>现场 · 设备运行动画</div>
      </div>
      {/* 右栏：真实界面 + 模拟状态浮层 */}
      <div style={{ position: "absolute", left: 980, top: 150, width: 884, opacity: right, transform: `translateX(${(1 - right) * 60}px)` }}>
        <div style={{ fontFamily: FONT.sans, fontSize: 21, letterSpacing: "0.08em", color: "rgba(245,242,230,.75)", marginBottom: 12 }}>系统界面 · AI 建议与审批记录</div>
        <div style={{ width: 884, height: 372, borderRadius: 18, overflow: "hidden", position: "relative", boxShadow: "0 30px 70px -30px rgba(0,0,0,.6)", background: "#fff" }}>
          <div style={{ position: "absolute", left: -603 * 0.9 + 10, top: -1093 * 0.9 + 4, width: 3200 * 0.9, height: 2000 * 0.9 }}>
            <Shot src="shots/running.png" style={{ width: 3200 * 0.9, height: 2000 * 0.9 }} />
          </div>
        </div>
        <div style={{ marginTop: 22, padding: "26px 32px", borderRadius: 18, background: "rgba(9,24,18,.92)", border: "1px solid rgba(223,188,112,.5)", fontFamily: FONT.sans, color: C.cream, position: "relative" }}>
          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
            <span style={{ fontSize: 22, letterSpacing: "0.1em", color: C.goldSoft }}>模拟状态浮层</span>
            <span style={{ opacity: compress, padding: "6px 14px", borderRadius: 16, background: C.gold, color: C.ink, fontSize: 21, fontWeight: 600 }}>时间压缩演示 · 120 秒 → 6 秒</span>
          </div>
          {[["区域", "C-01"], ["设备", "演示泵房控制器"], ["状态", status]].map(([k, v]) => (
            <div key={k} style={{ display: "flex", alignItems: "baseline", gap: 24, marginTop: 14 }}>
              <span style={{ width: 70, fontSize: 26, color: "rgba(245,242,230,.6)" }}>{k}</span>
              <span style={{ fontSize: 34, fontWeight: 600, color: k === "状态" ? (ended ? C.goldSoft : "#8fe8b5") : C.cream }}>{v}</span>
            </div>
          ))}
          <div style={{ display: "flex", alignItems: "baseline", gap: 24, marginTop: 14 }}>
            <span style={{ width: 70, fontSize: 26, color: "rgba(245,242,230,.6)" }}>进度</span>
            <span style={{ fontFamily: FONT.mono, fontSize: 40, fontWeight: 700, fontVariantNumeric: "tabular-nums" }}>{fmt(prog * 120)} / 02:00</span>
          </div>
          <div style={{ marginTop: 14, height: 12, borderRadius: 6, background: "rgba(245,242,230,.12)", overflow: "hidden" }}>
            <div style={{ width: `${prog * 100}%`, height: "100%", background: ended ? C.gold : `linear-gradient(90deg, #3aa774, ${C.run})` }} />
          </div>
        </div>
      </div>
    </AbsoluteFill>
  );
};

// ---------- 收束：回到农场全景 ----------
export const CloseOverlay: React.FC<{ t: number }> = ({ t }) => {
  if (t < T.closeIn) return null;
  const P = project3d(t);
  const nodes = env(t, 72.3, 74.7, 0.6, 0.5);
  const pump = P(PUMP_TAG), c01 = P([C01.center[0], 0.5, C01.center[2]]);
  const ui = { x: 1500, y: 300 };
  const L = 1000;
  const lines = ramp(t, 72.8, 1.2, "inOut");
  const phrases = ["田间情况可见", "设备执行可视", "管理过程可追溯"];
  const brand = ramp(t, T.brand, 0.8, "soft");
  return (
    <AbsoluteFill>
      <div style={{ position: "absolute", inset: 0, background: `linear-gradient(90deg, rgba(9,24,18,${0.6 * nodes}) 0%, rgba(9,24,18,${0.15 * nodes}) 50%, rgba(9,24,18,0) 75%)` }} />
      {nodes > 0 && (
        <svg width={1920} height={1080} style={{ position: "absolute", left: 0, top: 0, opacity: nodes }}>
          {[[c01, pump], [pump, ui], [ui, c01]].map(([a, b], i) => (
            <line key={i} x1={a.x} y1={a.y} x2={b.x} y2={b.y} stroke={C.gold} strokeWidth={3} strokeDasharray={L} strokeDashoffset={L * (1 - clamp01(lines * 3 - i))} pathLength={L} opacity={0.85} />
          ))}
          {[c01, pump, ui].map((n, i) => <circle key={i} cx={n.x} cy={n.y} r={11} fill={C.gold} />)}
        </svg>
      )}
      {nodes > 0 && <>
        <div style={{ position: "absolute", left: c01.x, top: c01.y + 22, transform: "translateX(-50%)", opacity: nodes, fontFamily: FONT.sans, fontSize: 24, color: C.cream, padding: "6px 14px", borderRadius: 14, background: "rgba(9,24,18,.75)", whiteSpace: "nowrap" }}>地图 · C-01</div>
        <div style={{ position: "absolute", left: pump.x, top: pump.y + 22, transform: "translateX(-50%)", opacity: nodes, fontFamily: FONT.sans, fontSize: 24, color: C.cream, padding: "6px 14px", borderRadius: 14, background: "rgba(9,24,18,.75)", whiteSpace: "nowrap" }}>设备 · 演示泵房控制器</div>
        <div style={{ position: "absolute", left: ui.x - 190, top: ui.y - 150, width: 380, height: 130, opacity: nodes, borderRadius: 14, overflow: "hidden", boxShadow: "0 24px 60px -24px rgba(0,0,0,.6)", border: "2px solid rgba(223,188,112,.8)", background: "#fff" }}>
          <div style={{ position: "absolute", left: -603 * 0.4 + 6, top: -1093 * 0.4 + 2, width: 3200 * 0.4, height: 2000 * 0.4 }}><Shot src="shots/running.png" style={{ width: 3200 * 0.4, height: 2000 * 0.4 }} /></div>
        </div>
        <div style={{ position: "absolute", left: ui.x, top: ui.y + 22, transform: "translateX(-50%)", opacity: nodes, fontFamily: FONT.sans, fontSize: 24, color: C.cream, padding: "6px 14px", borderRadius: 14, background: "rgba(9,24,18,.75)", whiteSpace: "nowrap" }}>系统 · 任务记录</div>
      </>}
      <div style={{ position: "absolute", left: 140, top: 360 }}>
        {phrases.map((s, i) => {
          const p = env(t, 74.0 + i * 0.6, 76.6, 0.55, 0.5);
          return <div key={s} style={{ opacity: p, transform: `translateY(${(1 - p) * 16}px)`, fontFamily: FONT.serif, fontWeight: 700, fontSize: 76, color: C.cream, letterSpacing: "0.08em", marginBottom: 26, textShadow: "0 8px 30px rgba(0,0,0,.45)" }}>
            <span style={{ display: "inline-block", width: 12, height: 52, background: C.gold, marginRight: 30, verticalAlign: "-6px", borderRadius: 3 }} />{s}
          </div>;
        })}
      </div>
      {brand > 0 && (
        <AbsoluteFill style={{ opacity: brand, background: `radial-gradient(120% 90% at 50% 45%, ${C.brand} 0%, ${C.deep} 55%, #08150f 100%)`, alignItems: "center", justifyContent: "center" }}>
          <div style={{ display: "flex", flexDirection: "column", alignItems: "center", transform: `translateY(${(1 - brand) * 14}px)` }}>
            <Logo size={150} grow={ramp(t, T.brand + 0.2, 1.4)} />
            <div style={{ marginTop: 40, fontFamily: FONT.serif, fontWeight: 700, fontSize: 120, color: C.cream, letterSpacing: "0.14em" }}>智禾农场</div>
            <div style={{ marginTop: 26, fontFamily: FONT.serif, fontWeight: 500, fontSize: 54, color: C.goldSoft, letterSpacing: "0.1em", opacity: ramp(t, T.brand + 0.7, 0.9) }}>从看见田间，到推动行动。</div>
            <div style={{ marginTop: 44, fontFamily: FONT.mono, fontSize: 22, letterSpacing: "0.36em", color: "rgba(245,242,230,.5)", opacity: ramp(t, T.brand + 1.1, 0.9) }}>ZHIHE FARM · 多租户农场管理</div>
          </div>
        </AbsoluteFill>
      )}
    </AbsoluteFill>
  );
};
