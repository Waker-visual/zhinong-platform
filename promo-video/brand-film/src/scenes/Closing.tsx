import React from "react";
import { AbsoluteFill, Img, staticFile } from "remotion";
import { C, FONT, S, P, TOTAL, ramp, env, mix, clamp01, beatFrom, Paper, Night, Lines, Chars, Label, SparkArc, card } from "../kit";
import { Logo } from "@task/film/Logo";

// 全貌：四块卡片一拍一亮 → 向中心收拢成标志 → 落版
const TILE = [
  { x: 140, y: 196, w: 420, h: 690 }, // 手机随行
  { x: 592, y: 196, w: 560, h: 329 }, // 闭环
  { x: 592, y: 557, w: 560, h: 329 }, // 经营模拟
  { x: 1184, y: 196, w: 596, h: 690 }, // 一套系统
];
const tileAt = (i: number) => beatFrom(S.montage, i * 2) + 0.05;
const LOGO = 168;
const LOGO_POS = { x: 960 - LOGO / 2, y: 300 };
// 标志里金色新叶的中心（viewBox 64：约 39.5, 21）
const LEAF: [number, number] = [LOGO_POS.x + (39.5 / 64) * LOGO, LOGO_POS.y + (21 / 64) * LOGO];

const Ring: React.FC<{ t: number; at: number }> = ({ t, at }) => {
  const steps = ["上报", "安排", "回执", "复核"];
  const draw = ramp(t, at + 0.2, 2.2, "inOut");
  const R = 96, cx = 150, cy = 172, L = 2 * Math.PI * R;
  return (
    <>
      <svg width={300} height={329} style={{ position: "absolute", left: 0, top: 0 }}>
        <circle cx={cx} cy={cy} r={R} fill="none" stroke="rgba(18,36,28,.1)" strokeWidth={5} />
        <circle cx={cx} cy={cy} r={R} fill="none" stroke={C.gold} strokeWidth={5} strokeLinecap="round" strokeDasharray={L} strokeDashoffset={L * (1 - draw)} transform={`rotate(-90 ${cx} ${cy})`} />
        {steps.map((_, i) => { const a = (-90 + i * 90) * Math.PI / 180; const on = clamp01(draw * 4 - i + 0.2); return <circle key={i} cx={cx + R * Math.cos(a)} cy={cy + R * Math.sin(a)} r={11} fill={on > 0.5 ? P.sprout.leaf : P.card} stroke={P.sprout.leaf} strokeWidth={3} />; })}
      </svg>
      <div style={{ position: "absolute", left: cx - 60, top: cy - 26, width: 120, textAlign: "center", fontFamily: FONT.serif, fontWeight: 700, fontSize: 38, color: P.ink }}>闭环</div>
      <div style={{ position: "absolute", left: 296, top: 86, fontFamily: FONT.sans, fontSize: 30, lineHeight: 1.75, color: P.ink, fontWeight: 600 }}>
        {steps.map((s, i) => <div key={s} style={{ opacity: 0.3 + 0.7 * clamp01(draw * 4 - i + 0.2) }}><span style={{ fontFamily: FONT.mono, fontSize: 20, color: C.goldDeep, marginRight: 14 }}>0{i + 1}</span>{s}</div>)}
      </div>
    </>
  );
};

const Season: React.FC<{ t: number; at: number }> = ({ t, at }) => {
  const d = ramp(t, at + 0.2, 2.0, "inOut");
  const paths = [
    { d: "M0,150 L60,150 L60,40 L250,40 L250,96 L470,96", c: C.goldDeep },
    { d: "M0,150 L76,150 L76,24 L92,24 L92,150 L470,150", c: P.sprout.leaf },
    { d: "M0,150 L60,150 L60,70 L200,70 L200,120 L470,120", c: "#7b8f84" },
  ];
  return (
    <>
      <svg width={470} height={170} viewBox="0 0 470 170" style={{ position: "absolute", left: 44, top: 100, overflow: "visible" }}>
        <line x1={0} y1={150} x2={470} y2={150} stroke="rgba(18,36,28,.14)" strokeWidth={2} />
        {paths.map((p, i) => <path key={i} d={p.d} fill="none" stroke={p.c} strokeWidth={5} strokeLinejoin="round" strokeLinecap="round" pathLength={1} strokeDasharray={1} strokeDashoffset={1 - clamp01(d * 1.3 - i * 0.15)} />)}
      </svg>
      <div style={{ position: "absolute", left: 44, bottom: 22, fontFamily: FONT.sans, fontSize: 20, color: P.sub, letterSpacing: "0.04em" }}>同一组地块与天气 · 三种资源方案 · 用于比较，不是产量承诺</div>
    </>
  );
};

export const Closing: React.FC<{ t: number }> = ({ t }) => {
  if (t < S.montage - 0.5) return null;
  const inP = ramp(t, S.montage - 0.4, 0.8, "inOut");
  const gather = ramp(t, S.fall, 1.3, "inOut"); // 卡片向中心收拢
  const iris = ramp(t, S.fall + 0.5, 1.5, "inOut"); // 墨绿自中心展开
  const logoP = ramp(t, S.fall + 1.2, 1.6, "out");
  const fly = ramp(t, S.fall + 1.5, S.end - S.fall - 1.5, "inOut"); // 光点落进标志的新叶
  const fadeOut = ramp(t, TOTAL - 1.6, 1.5, "soft");
  const titles = ["智禾随行", "一件事的闭环", "从一天到一季", "一套系统"];
  const subs = ["手机巡田 · 上报 · 回执", "", "", "五个演示农场"];
  return (
    <AbsoluteFill style={{ clipPath: inP < 1 ? `inset(0 0 0 ${(1 - inP) * 100}%)` : undefined }}>
      <Paper />
      <div style={{ position: "absolute", left: 140, top: 132, display: "flex", gap: 22, opacity: env(t, S.montage + 0.2, S.fall - 0.1, 0.5, 0.3) }}>
        <Label>03</Label><Label color={P.sub}>同一套系统里，还有</Label>
      </div>

      {TILE.map((r, i) => {
        const p = ramp(t, tileAt(i), 0.8, "out");
        if (p <= 0) return null;
        const g = clamp01(gather * 1.25 - i * 0.07);
        const cx = r.x + r.w / 2, cy = r.y + r.h / 2;
        return (
          <div key={i} style={{ position: "absolute", left: r.x, top: r.y, width: r.w, height: r.h, overflow: "hidden", opacity: p * (1 - g), transform: `translate(${(960 - cx) * g}px, ${(384 - cy) * g + (1 - p) * 60}px) scale(${mix(mix(0.94, 1, p), 0.16, g)})`, ...card({ borderRadius: 28 }) }}>
            <div style={{ position: "absolute", left: 44, top: 34, fontFamily: FONT.serif, fontWeight: 700, fontSize: 40, color: P.ink, letterSpacing: "0.06em" }}>{titles[i]}</div>
            {subs[i] && <div style={{ position: "absolute", left: 46, top: 96, fontFamily: FONT.sans, fontSize: 22, color: P.sub, letterSpacing: "0.08em" }}>{subs[i]}</div>}
            {i === 0 && (
              <div style={{ position: "absolute", left: 85, top: 150, width: 250, height: 541, borderRadius: 34, overflow: "hidden", border: "7px solid #14231c", boxShadow: "0 30px 60px -30px rgba(10,25,18,.6)", transform: `translateY(${(1 - p) * 60}px)` }}>
                <Img src={staticFile("shots/m-home.png")} style={{ width: 236, display: "block" }} />
              </div>
            )}
            {i === 1 && <Ring t={t} at={tileAt(1)} />}
            {i === 2 && <Season t={t} at={tileAt(2)} />}
            {i === 3 && (
              <div style={{ position: "absolute", left: 44, right: 44, top: 150 }}>
                {[["租户隔离", "每条查询都带租户条件"], ["工具调用", "AI 只用工具读数据，过程可见"], ["人工确认", "建议要人确认后才执行"], ["设备协议", "HTTP 指令领取与回执，MQTT 可选"]].map(([a, b], k) => {
                  const q = ramp(t, tileAt(3) + 0.25 + k * 0.22, 0.6, "out");
                  return (
                    <div key={a} style={{ padding: "24px 0", borderTop: `1px solid ${P.hair}`, opacity: q, transform: `translateX(${(1 - q) * 24}px)` }}>
                      <div style={{ fontFamily: FONT.sans, fontSize: 32, fontWeight: 700, color: P.ink, letterSpacing: "0.05em" }}>{a}</div>
                      <div style={{ marginTop: 6, fontFamily: FONT.sans, fontSize: 22, color: P.sub }}>{b}</div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        );
      })}

      {/* 落版 */}
      {iris > 0 && (
        <Night style={{ clipPath: `circle(${iris * 1250}px at 960px 384px)` }}>
          <div style={{ position: "absolute", left: LOGO_POS.x, top: LOGO_POS.y, opacity: logoP, transform: `scale(${mix(0.7, 1, logoP)})` }}>
            <Logo size={LOGO} grow={ramp(t, S.fall + 1.3, 2.4, "out")} />
          </div>
          <div style={{ position: "absolute", left: 0, right: 0, top: 520, textAlign: "center" }}>
            <Lines t={t} from={S.end - 0.3} lines={["智禾农场"]} lineStyle={{ fontFamily: FONT.serif, fontWeight: 700, fontSize: 132, letterSpacing: "0.16em", paddingLeft: "0.16em", color: C.cream }} />
            <div style={{ marginTop: 30, fontFamily: FONT.serif, fontWeight: 500, fontSize: 58, letterSpacing: "0.1em", color: C.goldSoft }}>
              <Chars t={t} from={S.end + 2.6} span={1.1} text="从一块田，到一季经营。" />
            </div>
            <div style={{ marginTop: 44, fontFamily: FONT.mono, fontSize: 22, letterSpacing: "0.36em", color: "rgba(245,242,230,.5)", opacity: ramp(t, S.end + 4.4, 0.9) }}>ZHIHE FARM · 多租户农场经营管理</div>
          </div>
          <div style={{ position: "absolute", left: 0, right: 0, bottom: 70, textAlign: "center", fontFamily: FONT.sans, fontSize: 19, lineHeight: 1.9, color: "rgba(245,242,230,.46)", opacity: ramp(t, S.end + 5.6, 1.0) }}>
            配乐《Documentary Nature》— alex-morgan（Pixabay Content License） · AI 回答为 deepseek-flash 于 2026-10-19 实时生成<br />
            登录页、三维农场与展示大屏为 2026-11-17 应用实录，设备运行状态为演示设定 · 片中农场、设备与经营数据均为演示数据
          </div>
          {fly > 0 && fly < 1 && <SparkArc a={[300, 1140]} c={[520, 120]} b={LEAF} u={fly} r={10} trail={0.3} />}
          {t >= S.end && t < S.end + 1.2 && (() => { const q = (t - S.end) / 1.2; return <div style={{ position: "absolute", left: LEAF[0] - 30 - 150 * q, top: LEAF[1] - 30 - 150 * q, width: 60 + 300 * q, height: 60 + 300 * q, borderRadius: "50%", border: `3px solid ${C.gold}`, opacity: 1 - q }} />; })()}
        </Night>
      )}
      {fadeOut > 0 && <AbsoluteFill style={{ background: "#040906", opacity: fadeOut }} />}
    </AbsoluteFill>
  );
};
