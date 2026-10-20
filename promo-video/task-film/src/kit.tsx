import React from "react";
import { Easing, Img, staticFile } from "remotion";

export const FPS = 60;
export const W = 1920, H = 1080;

// 品牌色：深绿、米白、暖金（与原片及产品 theme.css 的 --field-* 一致）
export const C = {
  ink: "#0b1a14",
  deep: "#0f2a21",
  brand: "#234d3c",
  moss: "#3d6b55",
  cream: "#f5f2e6",
  paper: "#fbf8f0",
  gold: "#dfbc70",
  goldDeep: "#b8893a",
  goldSoft: "#efd9a6",
  text: "#14231c",
  sub: "#54645a",
  water: "#5fc3dc",
  run: "#3ddc84",
};
export const FONT = {
  sans: '"Noto Sans SC", "Microsoft YaHei UI", sans-serif',
  serif: '"Noto Serif SC", "Source Han Serif SC", serif',
  mono: '"Iosevka Aile", "Noto Sans SC", monospace',
};

export const clamp01 = (x: number) => Math.max(0, Math.min(1, x));
const ez = {
  out: Easing.bezier(0.16, 1, 0.3, 1),
  inOut: Easing.bezier(0.65, 0, 0.35, 1),
  soft: Easing.bezier(0.45, 0, 0.25, 1),
  in: Easing.bezier(0.7, 0, 0.84, 0),
  lin: (x: number) => x,
};
export type EaseName = keyof typeof ez;
// 0→1 进度：t 从 a 走到 a+d
export const ramp = (t: number, a: number, d: number, e: EaseName = "out") => ez[e](clamp01((t - a) / d));
// 进入后再退出的包络
export const env = (t: number, a: number, b: number, fin = 0.35, fout = 0.35, e: EaseName = "soft") =>
  Math.min(ramp(t, a, fin, e), 1 - ramp(t, b - fout, fout, e));
export const mix = (a: number, b: number, p: number) => a + (b - a) * p;

// 关键帧插值：每段使用目标关键帧上的缓动
export type Key<T> = { t: number; v: T; e?: EaseName };
export function keys(t: number, ks: Key<number[]>[]): number[] {
  if (t <= ks[0].t) return ks[0].v;
  for (let i = 1; i < ks.length; i++) {
    if (t <= ks[i].t) {
      const p = ez[ks[i].e ?? "inOut"]((t - ks[i - 1].t) / (ks[i].t - ks[i - 1].t));
      return ks[i - 1].v.map((x, j) => mix(x, ks[i].v[j], p));
    }
  }
  return ks[ks.length - 1].v;
}

export const Shot: React.FC<{ src: string; style?: React.CSSProperties }> = ({ src, style }) => (
  <Img src={staticFile(src)} style={{ display: "block", ...style }} />
);

// 字幕：投影可读的大字（静音也能读懂流程）
export const Caption: React.FC<{ t: number; from: number; to: number; text: string; dark?: boolean; y?: number }> = ({ t, from, to, text, dark = true, y = 958 }) => {
  const p = env(t, from, to, 0.45, 0.4);
  if (p <= 0) return null;
  return (
    <div style={{ position: "absolute", left: 0, right: 0, top: y, display: "flex", justifyContent: "center", opacity: p, transform: `translateY(${(1 - p) * 14}px)` }}>
      <div style={{
        display: "flex", alignItems: "center", gap: 22, padding: "16px 40px 18px 30px", borderRadius: 14,
        background: dark ? "rgba(9,24,18,.82)" : "rgba(251,248,240,.94)",
        boxShadow: dark ? "0 18px 50px -18px rgba(0,0,0,.6)" : "0 16px 44px -20px rgba(30,40,30,.45)",
        border: dark ? "1px solid rgba(223,188,112,.28)" : "1px solid rgba(184,137,58,.35)",
      }}>
        <span style={{ width: 6, height: 40, borderRadius: 3, background: C.gold }} />
        <span style={{ fontFamily: FONT.sans, fontWeight: 600, fontSize: 52, letterSpacing: "0.04em", color: dark ? C.cream : C.text }}>{text}</span>
      </div>
    </div>
  );
};

// 角标：常驻“演示数据 · 模拟设备”与品牌
export const Corner: React.FC<{ dark?: boolean; opacity?: number }> = ({ dark = true, opacity = 1 }) => (
  <>
    <div style={{ position: "absolute", left: 56, top: 44, display: "flex", alignItems: "center", gap: 12, opacity, padding: "9px 18px 9px 10px", borderRadius: 30, background: dark ? "rgba(9,24,18,.55)" : "rgba(255,255,255,.82)", border: dark ? "1px solid rgba(245,242,230,.12)" : "1px solid rgba(20,35,28,.08)" }}>
      <Img src={staticFile("brand/zhihe-logo.svg")} style={{ width: 34, height: 34, borderRadius: 8 }} />
      <span style={{ fontFamily: FONT.sans, fontWeight: 600, fontSize: 21, color: dark ? C.cream : C.text, letterSpacing: "0.06em" }}>智禾农场</span>
    </div>
    <div style={{ position: "absolute", right: 56, bottom: 40, fontFamily: FONT.sans, fontSize: 18, letterSpacing: "0.12em", color: dark ? "rgba(245,242,230,.62)" : "rgba(20,35,28,.55)", opacity }}>演示数据 · 模拟设备</div>
  </>
);

// 段落标签：左上角品牌下方的小节名
export const Chapter: React.FC<{ t: number; from: number; to: number; no: string; text: string; dark?: boolean }> = ({ t, from, to, no, text, dark = true }) => {
  const p = env(t, from, to, 0.5, 0.4);
  if (p <= 0) return null;
  return (
    <div style={{ position: "absolute", left: 58, top: 112, opacity: p, display: "flex", alignItems: "baseline", gap: 14, fontFamily: FONT.sans, transform: `translateX(${(1 - p) * -12}px)` }}>
      <span style={{ fontFamily: FONT.mono, fontSize: 18, letterSpacing: "0.14em", color: C.gold }}>{no}</span>
      <span style={{ fontSize: 22, fontWeight: 500, letterSpacing: "0.08em", color: dark ? "rgba(245,242,230,.86)" : "rgba(20,35,28,.78)" }}>{text}</span>
    </div>
  );
};

// 数据/参数卡：标签 + 大号数值
export const Stat: React.FC<{ label: string; value: string; unit?: string; tone?: "gold" | "cream" | "dark"; style?: React.CSSProperties; size?: number }> = ({ label, value, unit, tone = "cream", style, size = 64 }) => {
  const dark = tone === "dark";
  return (
    <div style={{
      padding: "20px 30px 22px", borderRadius: 18, minWidth: 300,
      background: dark ? "rgba(9,24,18,.86)" : C.paper,
      border: tone === "gold" ? `2px solid ${C.gold}` : dark ? "1px solid rgba(223,188,112,.35)" : "1px solid rgba(20,35,28,.1)",
      boxShadow: "0 24px 60px -24px rgba(10,25,18,.45)", ...style,
    }}>
      <div style={{ fontFamily: FONT.sans, fontSize: 24, fontWeight: 500, letterSpacing: "0.08em", color: dark ? "rgba(245,242,230,.7)" : C.sub }}>{label}</div>
      <div style={{ marginTop: 8, display: "flex", alignItems: "baseline", gap: 8, fontFamily: FONT.mono, fontWeight: 700, fontSize: size, color: dark ? C.cream : C.text, fontVariantNumeric: "tabular-nums" }}>
        {value}{unit ? <span style={{ fontSize: size * 0.48, fontWeight: 500, color: dark ? C.goldSoft : C.sub }}>{unit}</span> : null}
      </div>
    </div>
  );
};

// 光标：柔和圆点 + 点击涟漪，只在“人做决定”的时刻出现
export const Cursor: React.FC<{ x: number; y: number; t: number; clickAt?: number; opacity?: number }> = ({ x, y, t, clickAt, opacity = 1 }) => {
  const press = clickAt != null ? env(t, clickAt - 0.08, clickAt + 0.18, 0.08, 0.14, "lin") : 0;
  const ring = clickAt != null ? clamp01((t - clickAt) / 0.6) : 0;
  return (
    <div style={{ position: "absolute", left: x, top: y, opacity, pointerEvents: "none" }}>
      {ring > 0 && ring < 1 && <div style={{ position: "absolute", left: -40 * ring - 10, top: -40 * ring - 10, width: 20 + 80 * ring, height: 20 + 80 * ring, borderRadius: "50%", border: `3px solid ${C.gold}`, opacity: 1 - ring }} />}
      <svg width="44" height="54" viewBox="0 0 22 27" style={{ position: "absolute", left: -3, top: -2, transform: `scale(${1 - 0.12 * press})`, transformOrigin: "3px 2px", filter: "drop-shadow(0 6px 10px rgba(0,0,0,.35))" }}>
        <path d="M2 1.5 L2 21 L7.2 16.4 L10.6 24.6 L14 23.2 L10.7 15.2 L17.6 15.2 Z" fill="#fff" stroke="#111" strokeWidth="1.4" strokeLinejoin="round" />
      </svg>
    </div>
  );
};

// 暖金高亮框（用于真实截图上的局部强调）
export const GoldBox: React.FC<{ x: number; y: number; w: number; h: number; p: number; r?: number; fill?: boolean; width?: number }> = ({ x, y, w, h, p, r = 14, fill = true, width = 5 }) => {
  if (p <= 0) return null;
  const per = 2 * (w + h);
  return (
    <svg style={{ position: "absolute", left: x - 10, top: y - 10, overflow: "visible" }} width={w + 20} height={h + 20}>
      {fill && <rect x={10} y={10} width={w} height={h} rx={r} fill={`rgba(239,217,166,${0.32 * p})`} />}
      <rect x={10} y={10} width={w} height={h} rx={r} fill="none" stroke={C.gold} strokeWidth={width} strokeDasharray={per} strokeDashoffset={per * (1 - p)} strokeLinecap="round" />
    </svg>
  );
};

export const SimBadge: React.FC<{ opacity: number; dark?: boolean }> = ({ opacity, dark = true }) => opacity > 0 ? (
  <div style={{ position: "absolute", right: 56, top: 46, opacity, display: "flex", alignItems: "center", gap: 12, padding: "10px 20px", borderRadius: 30, background: dark ? "rgba(9,24,18,.66)" : "rgba(255,255,255,.86)", border: `1px solid ${dark ? "rgba(223,188,112,.4)" : "rgba(184,137,58,.4)"}`, fontFamily: FONT.sans, fontSize: 22, letterSpacing: "0.08em", color: dark ? C.cream : C.text }}>
    <span style={{ width: 10, height: 10, borderRadius: 5, background: C.gold }} />设备联动示意｜模拟运行
  </div>
) : null;
