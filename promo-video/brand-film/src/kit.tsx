// 综合宣传片基础件：时间轴（卡在配乐小节线上）、文字显影、光点、纸面背景
import React from "react";
import { AbsoluteFill } from "remotion";
import { C, FONT, ramp, env, mix, clamp01, type EaseName } from "@task/kit";
import beats from "./beats.json";

export { C, FONT, ramp, env, mix, clamp01, keys, Cursor, Shot, FPS, W, H } from "@task/kit";
export type { EaseName, Key } from "@task/kit";

// ───────── 节拍：全部来自 audio/music.py 对剪辑后配乐的分析 ─────────
export const DOWN: number[] = beats.downbeats;
export const BEATS: number[] = beats.beats;
export const TOTAL = beats.total;
/** 最接近 t 的小节线 */
export const bar = (t: number) => DOWN.reduce((a, b) => (Math.abs(b - t) < Math.abs(a - t) ? b : a));
/** 最接近 t 的拍点 */
export const beat = (t: number) => BEATS.reduce((a, b) => (Math.abs(b - t) < Math.abs(a - t) ? b : a));
/** 从 t 起第 n 个拍点（n 可为 0） */
export const beatFrom = (t: number, n: number) => { const i = BEATS.indexOf(beat(t)); return BEATS[Math.min(BEATS.length - 1, i + n)]; };

// ───────── 段落切点（秒）。每个切点都落在小节线上 ─────────
export const S = {
  prologue: 0,
  question: bar(9.66),
  xiaohe: bar(24.1),
  xRead: bar(31.4),
  xAnswer: bar(45.7),
  xClaim: bar(52.92),
  xDecide: bar(60.1),
  turn: bar(72.05),
  farm: bar(79.25),
  command: bar(91.3),
  pump: bar(99.64),
  wide: bar(113.9),
  real: bar(118.8),
  montage: bar(123.6),
  fall: bar(129.64),
  end: bar(134.42),
};

// ───────── 颜色补充 ─────────
export const P = {
  paper: "#f6f3e8",
  paperHi: "#fcfaf3",
  ink: "#12241c",
  sub: "#5b6a60",
  hair: "rgba(18,36,28,.12)",
  card: "#fffdf7",
  sprout: { leaf: "#285846", young: "#79b975", rice: "#edf3d5", line: "#24473b", gold: "#d6b85f", cheek: "#e9b9a0", hi: "#fafced", vein: "#aed291", limb: "#527757" },
};

// ───────── 背景 ─────────
export const Paper: React.FC<{ warm?: number; children?: React.ReactNode; style?: React.CSSProperties }> = ({ warm = 0, children, style }) => (
  <AbsoluteFill style={{ background: `radial-gradient(120% 90% at 30% 18%, ${P.paperHi} 0%, ${P.paper} 55%, #ece6d4 100%)`, ...style }}>
    {warm > 0 && <AbsoluteFill style={{ background: `radial-gradient(70% 60% at 82% 88%, rgba(223,188,112,${0.28 * warm}) 0%, rgba(223,188,112,0) 70%)` }} />}
    {children}
  </AbsoluteFill>
);
export const Night: React.FC<{ children?: React.ReactNode; style?: React.CSSProperties }> = ({ children, style }) => (
  <AbsoluteFill style={{ background: `radial-gradient(120% 90% at 50% 42%, ${C.brand} 0%, ${C.deep} 55%, #08150f 100%)`, ...style }}>{children}</AbsoluteFill>
);

// ───────── 文字显影 ─────────
/** 逐行遮罩上浮。p 为每行进度的函数入口：from 起每行错开 gap 秒 */
export const Lines: React.FC<{ t: number; from: number; out?: number; lines: React.ReactNode[]; gap?: number; dur?: number; style?: React.CSSProperties; lineStyle?: React.CSSProperties }> = ({ t, from, out, lines, gap = 0.09, dur = 0.75, style, lineStyle }) => {
  const o = out == null ? 1 : 1 - ramp(t, out, 0.4, "in");
  if (t < from - 0.02 || o <= 0) return null;
  return (
    <div style={{ ...style }}>
      {lines.map((l, i) => {
        const p = ramp(t, from + i * gap, dur, "out");
        return (
          <div key={i} style={{ overflow: "hidden", padding: "0.06em 0 0.14em", margin: "-0.06em 0 -0.14em" }}>
            <div style={{ transform: `translateY(${(1 - p) * 108 - (1 - o) * 60}%)`, opacity: o, ...lineStyle }}>{l}</div>
          </div>
        );
      })}
    </div>
  );
};
/** 逐字显影：6px 模糊收拢 */
export const Chars: React.FC<{ t: number; from: number; text: string; span?: number; out?: number; style?: React.CSSProperties }> = ({ t, from, text, span = 0.5, out, style }) => {
  const cs = [...text];
  const o = out == null ? 1 : 1 - ramp(t, out, 0.35, "in");
  if (o <= 0) return null;
  return (
    <span style={{ ...style, opacity: o }}>
      {cs.map((c, i) => {
        const p = ramp(t, from + (cs.length > 1 ? (i / (cs.length - 1)) * span : 0), 0.45, "out");
        return <span key={i} style={{ display: "inline-block", opacity: p, filter: p < 1 ? `blur(${(1 - p) * 6}px)` : undefined, transform: `translateY(${(1 - p) * 0.2}em)`, whiteSpace: "pre" }}>{c}</span>;
      })}
    </span>
  );
};
/** 小标签：等宽、疏字距 */
export const Label: React.FC<{ children: React.ReactNode; color?: string; size?: number; style?: React.CSSProperties }> = ({ children, color = C.goldDeep, size = 22, style }) => (
  <div style={{ fontFamily: FONT.mono, fontSize: size, letterSpacing: "0.28em", color, fontWeight: 500, ...style }}>{children}</div>
);

// ───────── 光点：全片的线索（太阳、光标、引用、指令、水流、标志的新叶） ─────────
export const Spark: React.FC<{ x: number; y: number; r?: number; glow?: number; opacity?: number; core?: string }> = ({ x, y, r = 9, glow = 1, opacity = 1, core = "#fff3cf" }) => opacity <= 0 ? null : (
  <div style={{ position: "absolute", left: x, top: y, width: 0, height: 0, opacity, pointerEvents: "none" }}>
    <div style={{ position: "absolute", left: -r * 7, top: -r * 7, width: r * 14, height: r * 14, borderRadius: "50%", background: `radial-gradient(circle, rgba(223,188,112,${0.5 * glow}) 0%, rgba(223,188,112,${0.16 * glow}) 32%, rgba(223,188,112,0) 68%)` }} />
    <div style={{ position: "absolute", left: -r, top: -r, width: r * 2, height: r * 2, borderRadius: "50%", background: core, boxShadow: `0 0 ${r * 1.6}px ${C.gold}, 0 0 0 ${Math.max(1.5, r * 0.22)}px ${C.gold}` }} />
  </div>
);
/** 二次贝塞尔上的点 */
export const bez = (a: [number, number], c: [number, number], b: [number, number], u: number): [number, number] => [
  (1 - u) ** 2 * a[0] + 2 * (1 - u) * u * c[0] + u * u * b[0],
  (1 - u) ** 2 * a[1] + 2 * (1 - u) * u * c[1] + u * u * b[1],
];
/** 光点沿弧线飞行并拖尾 */
export const SparkArc: React.FC<{ a: [number, number]; c: [number, number]; b: [number, number]; u: number; r?: number; trail?: number; opacity?: number }> = ({ a, c, b, u, r = 9, trail = 0.22, opacity = 1 }) => {
  if (opacity <= 0) return null;
  const head = bez(a, c, b, u);
  const L = 1000, d = `M${a[0]},${a[1]} Q${c[0]},${c[1]} ${b[0]},${b[1]}`;
  const tail = Math.max(0, u - trail);
  return (
    <AbsoluteFill style={{ opacity, pointerEvents: "none" }}>
      <svg width={1920} height={1080} style={{ position: "absolute", inset: 0 }}>
        <path d={d} fill="none" stroke={C.gold} strokeOpacity={0.55} strokeWidth={r * 0.5} strokeLinecap="round" pathLength={L} strokeDasharray={`${(u - tail) * L} ${L}`} strokeDashoffset={-tail * L} />
      </svg>
      <Spark x={head[0]} y={head[1]} r={r} />
    </AbsoluteFill>
  );
};

// ───────── 常驻角标 ─────────
export const DemoBadge: React.FC<{ dark: boolean; opacity?: number; text?: string }> = ({ dark, opacity = 1, text = "演示数据 · 模拟设备" }) => (
  <div style={{ position: "absolute", right: 64, bottom: 44, fontFamily: FONT.sans, fontSize: 18, letterSpacing: "0.14em", color: dark ? "rgba(245,242,230,.6)" : "rgba(18,36,28,.5)", opacity }}>{text}</div>
);

// ───────── 卡片 ─────────
export const card = (extra?: React.CSSProperties): React.CSSProperties => ({
  background: P.card, borderRadius: 24, border: `1px solid ${P.hair}`,
  boxShadow: "0 1px 0 rgba(255,255,255,.9) inset, 0 40px 80px -40px rgba(30,45,30,.35), 0 12px 28px -18px rgba(30,45,30,.25)", ...extra,
});

export const stag = (t: number, from: number, i: number, step = 0.09, dur = 0.7, e: EaseName = "out") => ramp(t, from + i * step, dur, e);
export const lerp2 = (a: [number, number], b: [number, number], p: number): [number, number] => [mix(a[0], b[0], p), mix(a[1], b[1], p)];
export { env as envelope };
