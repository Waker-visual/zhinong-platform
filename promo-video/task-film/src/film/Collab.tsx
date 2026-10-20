import React from "react";
import { AbsoluteFill } from "remotion";
import { C, FONT, Shot, ramp, env, clamp01 } from "../kit";

// 配套田间协作：上报 → 安排 → 回执 → 复核（平台配套能力，与灌溉模拟反馈分开表达）
const BEATS = [
  { key: "上报", at: 62.9, side: "phone", src: "shots/m-report.png", note: "手机端 · 巡田上报" },
  { key: "安排", at: 65.2, side: "desk", src: "shots/d-assign.png", crop: { x: 1040, y: 290, w: 1120, h: 845 }, note: "桌面端 · 安排问题处理" },
  { key: "回执", at: 67.5, side: "phone", src: "shots/m-receipt.png", note: "手机端 · 作业回执" },
  { key: "复核", at: 69.6, side: "desk", src: "shots/d-review.png", crop: { x: 1040, y: 690, w: 1120, h: 622 }, note: "桌面端 · 复核关闭" },
];
export const COLLAB = [62.2, 72.2] as const;

const PHONE = { x: 300, y: 262, w: 320, h: 692 };
const DESK = { x: 780, y: 262, w: 1040, h: 700 };

export const Collab: React.FC<{ t: number }> = ({ t }) => {
  const op = env(t, COLLAB[0], COLLAB[1], 0.7, 0.6);
  if (op <= 0) return null;
  const active = BEATS.filter((b) => t >= b.at - 0.3).length - 1;
  const phoneBeat = t < 67.2 ? BEATS[0] : BEATS[2];
  const deskBeat = t < 69.3 ? BEATS[1] : BEATS[3];
  const phoneSwap = ramp(t, 67.2, 0.5, "inOut");
  const deskIn = ramp(t, 64.9, 0.6);
  const deskSwap = ramp(t, 69.3, 0.5, "inOut");
  const phoneIn = ramp(t, 62.5, 0.7);
  // 连接线上的流向点：手机 → 桌面（上报 / 回执），桌面 → 手机（安排）
  const flowDir = active === 1 ? -1 : 1;
  const dotP = ((t * 0.7) % 1);
  const lx0 = PHONE.x + PHONE.w + 18, lx1 = DESK.x - 18, ly = 560;
  const dx = flowDir > 0 ? lx0 + (lx1 - lx0) * dotP : lx1 - (lx1 - lx0) * dotP;
  const DeskCard = ({ b, p }: { b: (typeof BEATS)[number]; p: number }) => {
    const cr = b.crop!;
    const s = Math.min((DESK.w - 140) / cr.w, (DESK.h - 130) / cr.h);
    return (
      <div style={{ position: "absolute", left: (DESK.w - cr.w * s) / 2, top: 84 + (DESK.h - 130 - cr.h * s) / 2, width: cr.w * s, height: cr.h * s, borderRadius: 18, overflow: "hidden", opacity: p, transform: `scale(${0.97 + 0.03 * p})`, boxShadow: "0 30px 70px -26px rgba(0,0,0,.45)" }}>
        <div style={{ position: "absolute", left: -cr.x * s, top: -cr.y * s }}><Shot src={b.src} style={{ width: 3200 * s, height: 2000 * s }} /></div>
      </div>
    );
  };
  return (
    <AbsoluteFill style={{ opacity: op, background: `radial-gradient(120% 100% at 30% 20%, ${C.brand} 0%, ${C.deep} 60%, #08150f 100%)` }}>
      {/* 标题与流程 */}
      <div style={{ position: "absolute", left: 0, right: 0, top: 54, display: "flex", flexDirection: "column", alignItems: "center", fontFamily: FONT.sans }}>
        <div style={{ fontFamily: FONT.serif, fontWeight: 700, fontSize: 62, color: C.cream, letterSpacing: "0.12em" }}>配套田间协作</div>
        <div style={{ display: "flex", alignItems: "center", gap: 16, marginTop: 20 }}>
          {BEATS.map((b, i) => (
            <React.Fragment key={b.key}>
              {i > 0 && <span style={{ color: i <= active ? C.gold : "rgba(245,242,230,.3)", fontSize: 28 }}>→</span>}
              <span style={{ padding: "8px 26px", borderRadius: 24, fontSize: 30, fontWeight: 600, letterSpacing: "0.1em",
                background: i === active ? C.gold : i < active ? "rgba(223,188,112,.18)" : "rgba(245,242,230,.06)",
                color: i === active ? C.ink : i < active ? C.goldSoft : "rgba(245,242,230,.45)",
                border: `1px solid ${i <= active ? "rgba(223,188,112,.7)" : "rgba(245,242,230,.15)"}` }}>{b.key}</span>
            </React.Fragment>
          ))}
        </div>
      </div>
      {/* 手机 */}
      <div style={{ position: "absolute", left: PHONE.x, top: PHONE.y + (1 - phoneIn) * 60, width: PHONE.w, height: PHONE.h, opacity: phoneIn }}>
        <div style={{ position: "absolute", inset: -14, borderRadius: 58, background: "#101614", boxShadow: "0 40px 90px -30px rgba(0,0,0,.7), inset 0 0 0 2px rgba(255,255,255,.08)" }} />
        <div style={{ position: "absolute", inset: 0, borderRadius: 46, overflow: "hidden", background: "#fff" }}>
          <Shot src={BEATS[0].src} style={{ position: "absolute", width: PHONE.w, height: PHONE.h, opacity: 1 - phoneSwap }} />
          <Shot src={BEATS[2].src} style={{ position: "absolute", width: PHONE.w, height: PHONE.h, opacity: phoneSwap }} />
        </div>
        <div style={{ position: "absolute", left: 0, right: 0, top: PHONE.h + 26, textAlign: "center", fontFamily: FONT.sans, fontSize: 24, color: "rgba(245,242,230,.8)" }}>{phoneBeat.note}</div>
      </div>
      {/* 连线 */}
      <svg width={1920} height={1080} style={{ position: "absolute", left: 0, top: 0, opacity: deskIn }}>
        <line x1={lx0} y1={ly} x2={lx1} y2={ly} stroke="rgba(223,188,112,.55)" strokeWidth={3} strokeDasharray="10 10" />
        <circle cx={dx} cy={ly} r={9} fill={C.gold} opacity={clamp01(Math.sin(dotP * Math.PI) * 2)} />
      </svg>
      {/* 桌面 */}
      <div style={{ position: "absolute", left: DESK.x, top: DESK.y, width: DESK.w, height: DESK.h, opacity: deskIn, transform: `translateX(${(1 - deskIn) * 50}px)` }}>
        <div style={{ position: "absolute", inset: 0, borderRadius: 20, background: "rgba(245,242,230,.06)", border: "1px solid rgba(245,242,230,.14)" }} />
        <div style={{ position: "absolute", left: 24, top: 22, display: "flex", gap: 9 }}>{[0, 1, 2].map((i) => <span key={i} style={{ width: 13, height: 13, borderRadius: 7, background: "rgba(245,242,230,.25)" }} />)}</div>
        <div style={{ position: "absolute", left: 0, right: 0, top: 16, textAlign: "center", fontFamily: FONT.sans, fontSize: 24, color: "rgba(245,242,230,.8)" }}>{deskBeat.note}</div>
        <DeskCard b={BEATS[1]} p={1 - deskSwap} />
        <DeskCard b={BEATS[3]} p={deskSwap} />
      </div>
      <div style={{ position: "absolute", left: 0, right: 0, bottom: 40, textAlign: "center", fontFamily: FONT.sans, fontSize: 22, letterSpacing: "0.1em", color: "rgba(245,242,230,.55)" }}>平台配套能力 · 人工回执与设备模拟反馈分别记录</div>
    </AbsoluteFill>
  );
};
