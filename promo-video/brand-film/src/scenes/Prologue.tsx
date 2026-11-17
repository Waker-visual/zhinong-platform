import React from "react";
import { AbsoluteFill, OffthreadVideo, Sequence, staticFile } from "remotion";
import { C, FONT, FPS, S, BEATS, ramp, env, mix, Lines, Spark, bar } from "../kit";

// 序：黑场里一粒光 → 地平线 → 画幅沿地平线张开，露出登录页的像素田园（真实应用实录）→ 四季一拍一切
const OPEN = bar(2.4), SEASONS = bar(7.3);
const seasons = [
  { clip: "l-spring", ch: "春" }, { clip: "l-summer", ch: "夏" }, { clip: "l-autumn", ch: "秋" }, { clip: "l-winter", ch: "冬" },
];
const sBeats = (() => { const i = BEATS.findIndex((b) => b >= SEASONS - 0.05); return [0, 1, 2, 3, 4].map((k) => BEATS[i + k]); })();
const f = (s: number) => Math.round(s * FPS);

export const Prologue: React.FC<{ t: number }> = ({ t }) => {
  if (t > S.question + 0.9) return null;
  const open = ramp(t, OPEN, 2.6, "inOut");
  const band = mix(0, 540, open); // 画幅半高
  const sunY = mix(640, 540, ramp(t, 0.3, OPEN + 1.2, "soft"));
  const horizon = ramp(t, 0.9, 1.6, "inOut");
  const sparkFade = 1 - ramp(t, OPEN + 0.9, 1.2, "soft");
  const zoom = mix(1.14, 1.0, ramp(t, OPEN, 5.2, "soft"));
  const scrim = env(t, OPEN + 0.5, SEASONS - 0.1, 0.9, 0.5);
  return (
    <AbsoluteFill style={{ background: "#050b08" }}>
      <AbsoluteFill style={{ clipPath: `inset(${540 - band}px 0 ${540 - band}px 0)` }}>
        <AbsoluteFill style={{ transform: `scale(${zoom})` }}>
          <Sequence from={f(OPEN) - 6} durationInFrames={f(SEASONS - OPEN) + 12} layout="none">
            <OffthreadVideo src={staticFile("rec/l-daytour.mp4")} muted trimBefore={f(4.2)} style={{ width: 1920, height: 1080 }} />
          </Sequence>
          {seasons.map((s, i) => (
            <Sequence key={s.clip} from={f(sBeats[i])} durationInFrames={f(sBeats[i + 1] - sBeats[i]) + (i === 3 ? 60 : 0)} layout="none">
              <OffthreadVideo src={staticFile(`rec/${s.clip}.mp4`)} muted trimBefore={20} style={{ width: 1920, height: 1080 }} />
            </Sequence>
          ))}
        </AbsoluteFill>
        <AbsoluteFill style={{ background: `linear-gradient(90deg, rgba(6,16,12,${0.72 * scrim}) 0%, rgba(6,16,12,${0.32 * scrim}) 46%, rgba(6,16,12,0) 72%)` }} />
      </AbsoluteFill>

      {/* 地平线与光点 */}
      <div style={{ position: "absolute", left: 960 - 760 * horizon, top: 640 - 1, width: 1520 * horizon, height: 2, background: `linear-gradient(90deg, rgba(223,188,112,0), ${C.gold} 50%, rgba(223,188,112,0))`, opacity: (1 - ramp(t, OPEN, 0.9, "soft")) * 0.9 }} />
      <Spark x={960} y={sunY} r={mix(5, 11, ramp(t, 0.3, 2.0))} opacity={ramp(t, 0.3, 0.8) * sparkFade} glow={1.2} />

      <Lines t={t} from={OPEN + 0.9} out={SEASONS - 0.45} lines={["每一块田，", "都有它的一天。"]} gap={0.16}
        style={{ position: "absolute", left: 140, top: 392 }}
        lineStyle={{ fontFamily: FONT.serif, fontWeight: 700, fontSize: 124, lineHeight: 1.22, letterSpacing: "0.06em", color: C.cream, textShadow: "0 10px 50px rgba(0,0,0,.45)" }} />

      {/* 四季：一拍一字 */}
      {seasons.map((s, i) => {
        if (t < sBeats[i] || t >= sBeats[i + 1]) return null;
        const p = ramp(t, sBeats[i], 0.5, "out");
        return (
          <AbsoluteFill key={s.ch} style={{ alignItems: "center", justifyContent: "center" }}>
            <div style={{ fontFamily: FONT.serif, fontWeight: 700, fontSize: 340, color: C.cream, opacity: 0.94, transform: `scale(${mix(1.12, 1, p)})`, textShadow: "0 20px 90px rgba(0,0,0,.5)", mixBlendMode: "soft-light" }}>{s.ch}</div>
            <div style={{ position: "absolute", fontFamily: FONT.serif, fontWeight: 700, fontSize: 340, color: C.cream, opacity: 0.55, transform: `scale(${mix(1.12, 1, p)})` }}>{s.ch}</div>
          </AbsoluteFill>
        );
      })}
      <div style={{ position: "absolute", left: 140, bottom: 96, fontFamily: FONT.mono, fontSize: 20, letterSpacing: "0.26em", color: "rgba(245,242,230,.62)", opacity: env(t, OPEN + 1.6, S.question - 0.2, 0.8, 0.3) }}>智禾农场 · 登录页实录</div>
    </AbsoluteFill>
  );
};
