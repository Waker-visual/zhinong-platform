import React from "react";
import { AbsoluteFill } from "remotion";
import { C, FONT, Shot, GoldBox, Stat, Cursor, ramp, env, keys, mix, clamp01, type Key } from "../kit";

// 页面坐标 = 真实截图像素（irr_approve/04-proposed-full.png，3200×3954，2× 密度）
export const PAGE = { w: 3200, h: 3954 };
export const R = {
  map: { x: 652, y: 2156, w: 1315, h: 657 },
  cRow: { x: 930, y: 2528, w: 900, h: 190 },
  c01: [[974, 2544], [1281, 2552], [1281, 2698], [945, 2690]] as [number, number][],
  card1: { x: 603, y: 1257, w: 802, h: 725 },
  card2: { x: 1439, y: 1257, w: 802, h: 725 },
  card3: { x: 2275, y: 1257, w: 802, h: 725 },
  moist: { x: 2306, y: 1508, w: 262, h: 48 },
  thresh: { x: 2482, y: 1634, w: 142, h: 46 },
  pumpRef: { x: 2008, y: 2290, w: 330, h: 42 },
  proposal: { x: 603, y: 3089, w: 2474, h: 396 },
  pScope: { x: 636, y: 3254, w: 268, h: 44 },
  pPump: { x: 911, y: 3254, w: 290, h: 44 },
  pDur: { x: 1209, y: 3254, w: 106, h: 44 },
  btn: { x: 644, y: 3372, w: 305, h: 71 },
};
export const c01Center: [number, number] = [1120, 2621];
const btnCenter: [number, number] = [R.btn.x + R.btn.w / 2, R.btn.y + R.btn.h / 2];

type K = Key<number[]>;
const PCAM: K[] = [
  { t: 4.7, v: [1113, 2621, 1.5] },
  { t: 5.6, v: [1113, 2621, 1.32], e: "out" },
  { t: 8.2, v: [1300, 2480, 0.9], e: "inOut" },
  { t: 9.8, v: [2676, 1640, 0.95], e: "inOut" },
  { t: 11.2, v: [2540, 1600, 1.3], e: "inOut" },
  { t: 13.7, v: [2540, 1590, 1.36], e: "lin" },
  { t: 14.7, v: [1380, 3285, 1.12], e: "inOut" },
  { t: 20.9, v: [1380, 3285, 1.15], e: "lin" },
  { t: 22.2, v: [1330, 2860, 0.62], e: "inOut" },
  { t: 23.4, v: [1330, 2860, 0.64], e: "lin" },
  { t: 24.3, v: [1020, 3330, 0.98], e: "inOut" },
  { t: 28.7, v: [1020, 3320, 1.02], e: "lin" },
  { t: 29.5, v: [1100, 2990, 0.74], e: "inOut" },
  { t: 30.1, v: [1113, 2621, 1.5], e: "in" },
  { t: 30.6, v: [1113, 2621, 1.9], e: "out" },
];
export const pageCam = (t: number) => keys(t, PCAM);
export const pageToScreen = (t: number, p: [number, number]): [number, number] => {
  const [fx, fy, s] = pageCam(t);
  return [(p[0] - fx) * s + 960, (p[1] - fy) * s + 540];
};
export const pageOpacity = (t: number) => env(t, 4.85, 30.45, 0.5, 0.45, "soft");

type Rect = { x: number; y: number; w: number; h: number };
// 弱化遮罩：除强调区域外整体压暗
const Dim: React.FC<{ holes: Rect[]; p: number; color?: string }> = ({ holes, p, color = "rgba(246,243,234," }) => {
  if (p <= 0) return null;
  const d = `M0,0H${PAGE.w}V${PAGE.h}H0Z ` + holes.map((r) => `M${r.x},${r.y}h${r.w}v${r.h}h${-r.w}Z`).join(" ");
  return <svg width={PAGE.w} height={PAGE.h} style={{ position: "absolute", left: 0, top: 0 }}><path d={d} fill={`${color}${0.72 * p})`} fillRule="evenodd" /></svg>;
};
const Quad: React.FC<{ pts: [number, number][]; p: number; stroke?: number; fill?: string }> = ({ pts, p, stroke = 6, fill = "rgba(239,217,166,.35)" }) => {
  if (p <= 0) return null;
  const d = pts.map((q, i) => `${i ? "L" : "M"}${q[0]},${q[1]}`).join(" ") + "Z";
  return <svg width={PAGE.w} height={PAGE.h} style={{ position: "absolute", left: 0, top: 0, overflow: "visible" }}><path d={d} fill={fill} fillOpacity={p} stroke={C.gold} strokeWidth={stroke} strokeOpacity={p} strokeLinejoin="round" /></svg>;
};

// 指令线：从“确认”位置延伸到地图中的 C-01
const CommandLine: React.FC<{ t: number }> = ({ t }) => {
  const p = ramp(t, 28.95, 0.75, "inOut");
  if (p <= 0) return null;
  const [x0, y0] = btnCenter, [x1, y1] = c01Center;
  const cx = x0 - 520, cy = (y0 + y1) / 2;
  const d = `M${x0},${y0} Q${cx},${cy} ${x1},${y1}`;
  const L = 1200;
  const bx = (1 - p) ** 2 * x0 + 2 * (1 - p) * p * cx + p * p * x1;
  const by = (1 - p) ** 2 * y0 + 2 * (1 - p) * p * cy + p * p * y1;
  return (
    <svg width={PAGE.w} height={PAGE.h} style={{ position: "absolute", left: 0, top: 0, overflow: "visible" }}>
      <path d={d} fill="none" stroke="rgba(223,188,112,.35)" strokeWidth={26} strokeLinecap="round" strokeDasharray={L} strokeDashoffset={L * (1 - p)} pathLength={L} />
      <path d={d} fill="none" stroke={C.gold} strokeWidth={9} strokeLinecap="round" strokeDasharray={L} strokeDashoffset={L * (1 - p)} pathLength={L} />
      <circle cx={bx} cy={by} r={22} fill="#fff6dc" stroke={C.gold} strokeWidth={8} />
    </svg>
  );
};

// 建议卡与地图的关联连线
const Link: React.FC<{ from: [number, number]; to: [number, number]; p: number; bend?: number }> = ({ from, to, p, bend = -360 }) => {
  if (p <= 0) return null;
  const cx = (from[0] + to[0]) / 2 + bend, cy = (from[1] + to[1]) / 2;
  const L = 1000;
  return (
    <svg width={PAGE.w} height={PAGE.h} style={{ position: "absolute", left: 0, top: 0, overflow: "visible" }}>
      <path d={`M${from[0]},${from[1]} Q${cx},${cy} ${to[0]},${to[1]}`} fill="none" stroke={C.gold} strokeWidth={7} strokeLinecap="round" strokeDasharray={`${L}`} strokeDashoffset={L * (1 - p)} pathLength={L} />
      <circle cx={from[0]} cy={from[1]} r={12} fill={C.gold} opacity={clamp01(p * 4)} />
      <circle cx={to[0]} cy={to[1]} r={14} fill={C.gold} opacity={clamp01((p - 0.85) * 7)} />
    </svg>
  );
};

const PageLayer: React.FC<{ t: number }> = ({ t }) => {
  const [fx, fy, s] = pageCam(t);
  // 地图：其余行弱化，突出 C 行（演示分区 3）与 C-01
  const mapFocus = env(t, 5.6, 9.2, 0.7, 0.6);
  const cardFocus = env(t, 9.2, 14.2, 0.6, 0.5);
  const moist = ramp(t, 10.6, 0.6, "inOut") * (1 - ramp(t, 13.9, 0.4));
  const thresh = ramp(t, 11.6, 0.6, "inOut") * (1 - ramp(t, 13.9, 0.4));
  const propFocus = env(t, 14.3, 23.0, 0.5, 0.5);
  const step = (a: number) => env(t, a, a + 2.0, 0.35, 0.35);
  const links = ramp(t, 21.9, 1.0, "inOut") * (1 - ramp(t, 23.6, 0.5));
  const c01Glow = Math.max(env(t, 5.4, 9.0, 0.3, 0.6), links, ramp(t, 29.5, 0.4) * (1 - ramp(t, 30.2, 0.3)));
  const btnHi = env(t, 23.6, 24.6, 0.3, 0.3);
  const blur = env(t, 24.35, 28.95, 0.35, 0.45);
  return (
    <div style={{ position: "absolute", left: 0, top: 0, width: PAGE.w, height: PAGE.h, transformOrigin: "0 0", transform: `translate(${960 - fx * s}px, ${540 - fy * s}px) scale(${s})`, filter: blur > 0.01 ? `blur(${blur * 7}px)` : undefined }}>
      <Shot src="shots/page-proposed.png" style={{ width: PAGE.w, height: PAGE.h }} />
      <Dim holes={[R.cRow]} p={mapFocus} />
      <Dim holes={[R.card3]} p={cardFocus} />
      <Dim holes={[R.proposal, R.map]} p={propFocus * 0.6} />
      <Quad pts={R.c01} p={c01Glow} />
      <GoldBox {...R.cRow} p={mapFocus} r={10} fill={false} width={6} />
      <GoldBox {...R.card3} p={cardFocus} r={24} fill={false} width={7} />
      <GoldBox {...R.moist} p={moist} r={10} />
      <GoldBox {...R.thresh} p={thresh} r={10} />
      <GoldBox {...R.pScope} p={step(15.4)} r={10} />
      <GoldBox {...R.pPump} p={step(17.4)} r={10} />
      <GoldBox {...R.pDur} p={step(19.4)} r={10} />
      <GoldBox {...R.btn} p={btnHi} r={14} fill={false} width={7} />
      <Link from={[R.pScope.x + 150, R.pScope.y]} to={c01Center} p={links} bend={-420} />
      <Link from={[R.pPump.x + 150, R.pPump.y]} to={[R.pumpRef.x + 30, R.pumpRef.y + 20]} p={ramp(t, 22.3, 1.0, "inOut") * (1 - ramp(t, 23.6, 0.5))} bend={260} />
      <CommandLine t={t} />
    </div>
  );
};

// 地图标签 / 参数卡等屏幕空间元素
const ParamList: React.FC<{ t: number }> = ({ t }) => {
  const show = env(t, 14.9, 21.9, 0.6, 0.5);
  if (show <= 0) return null;
  const items = [
    { at: 15.4, label: "灌溉范围", value: "C-01" },
    { at: 17.4, label: "控制设备", value: "演示泵房控制器" },
    { at: 19.4, label: "运行时长", value: "120 秒" },
  ];
  return (
    <div style={{ position: "absolute", left: 1190, top: 236, width: 660, opacity: show, transform: `translateX(${(1 - show) * 30}px)` }}>
      <div style={{ fontFamily: FONT.sans, fontSize: 24, letterSpacing: "0.12em", color: C.sub, marginBottom: 18 }}>AI 灌溉建议 · 执行依据</div>
      {items.map((it, i) => {
        const on = env(t, it.at, it.at + 2.0, 0.35, 0.35);
        const seen = ramp(t, it.at, 0.4);
        return (
          <div key={i} style={{
            display: "flex", alignItems: "center", gap: 24, padding: "22px 30px", marginBottom: 16, borderRadius: 18,
            background: on > 0.02 ? `rgba(251,248,240,${0.9 + 0.1 * on})` : "rgba(251,248,240,.78)",
            border: `2px solid rgba(223,188,112,${0.15 + 0.85 * on})`, boxShadow: `0 24px 60px -28px rgba(10,25,18,${0.2 + 0.35 * on})`,
            opacity: 0.35 + 0.65 * Math.max(on, seen * 0.55), transform: `scale(${1 + 0.035 * on})`, transformOrigin: "left center",
          }}>
            <span style={{ fontFamily: FONT.mono, fontSize: 22, color: C.goldDeep, width: 36 }}>0{i + 1}</span>
            <span style={{ fontFamily: FONT.sans, fontSize: 30, color: C.sub, width: 150 }}>{it.label}</span>
            <span style={{ fontFamily: FONT.sans, fontWeight: 700, fontSize: 46, color: C.text }}>{it.value}</span>
          </div>
        );
      })}
    </div>
  );
};

const MoistureStats: React.FC<{ t: number }> = ({ t }) => {
  const a = env(t, 10.7, 14.0, 0.6, 0.45), b = env(t, 11.7, 14.0, 0.6, 0.45);
  if (a <= 0) return null;
  const gauge = ramp(t, 12.2, 1.0, "inOut");
  return (
    <div style={{ position: "absolute", left: 120, top: 300, display: "flex", flexDirection: "column", gap: 22 }}>
      <div style={{ opacity: a, transform: `translateY(${(1 - a) * 20}px)` }}><Stat label="土壤水分" value="37.7" unit="%" tone="gold" size={84} /></div>
      <div style={{ opacity: b, transform: `translateY(${(1 - b) * 20}px)` }}><Stat label="配置阈值" value="40" unit="%" size={84} /></div>
      <div style={{ opacity: b * gauge, width: 380, padding: "18px 24px", borderRadius: 16, background: "rgba(251,248,240,.94)", border: "1px solid rgba(20,35,28,.1)" }}>
        <div style={{ position: "relative", height: 18, borderRadius: 9, background: "#e6e1d2" }}>
          <div style={{ position: "absolute", left: 0, top: 0, bottom: 0, width: `${(37.7 / 60) * 100 * gauge}%`, borderRadius: 9, background: `linear-gradient(90deg, ${C.moss}, #6f9a6b)` }} />
          <div style={{ position: "absolute", left: `${(40 / 60) * 100}%`, top: -8, width: 4, height: 34, borderRadius: 2, background: C.goldDeep }} />
        </div>
        <div style={{ marginTop: 10, fontFamily: FONT.sans, fontSize: 24, color: C.sub }}>低于阈值 · 需要补灌</div>
      </div>
    </div>
  );
};

const MapTag: React.FC<{ t: number }> = ({ t }) => {
  const p = env(t, 6.0, 9.0, 0.5, 0.5);
  if (p <= 0) return null;
  const [x, y] = pageToScreen(t, [R.cRow.x + R.cRow.w, R.cRow.y + R.cRow.h / 2]);
  return (
    <div style={{ position: "absolute", left: x + 40, top: y - 52, opacity: p, padding: "16px 26px", borderRadius: 16, background: "rgba(9,24,18,.88)", border: "1px solid rgba(223,188,112,.5)", fontFamily: FONT.sans }}>
      <div style={{ fontSize: 22, letterSpacing: "0.1em", color: C.goldSoft }}>演示分区 3 · 蔬菜</div>
      <div style={{ fontSize: 40, fontWeight: 700, color: C.cream, marginTop: 4 }}>C-01 灌溉覆盖区</div>
    </div>
  );
};

// 确认弹窗：真实截图裁切（confirm-dialog.png 中弹窗区域）
const DLG = { x: 1179, y: 773, w: 842, h: 454 };
const DS = 1.55;
const DPOS = { x: 960 - (DLG.w * DS) / 2, y: 470 - (DLG.h * DS) / 2 };
export const dialogBtn: [number, number] = [DPOS.x + 697 * DS, DPOS.y + 358 * DS];
const Dialog: React.FC<{ t: number }> = ({ t }) => {
  const p = env(t, 24.45, 27.35, 0.35, 0.3);
  if (p <= 0) return null;
  const hi = (a: number) => env(t, a, a + 0.9, 0.25, 0.25);
  const press = env(t, 26.95, 27.2, 0.06, 0.12, "lin");
  return (
    <AbsoluteFill style={{ background: `rgba(14,24,19,${0.35 * p})` }}>
      <div style={{ position: "absolute", left: DPOS.x, top: DPOS.y, width: DLG.w * DS, height: DLG.h * DS, opacity: p, transform: `scale(${0.96 + 0.04 * p})`, borderRadius: 22, overflow: "hidden", boxShadow: "0 50px 120px -30px rgba(0,0,0,.55)" }}>
        <div style={{ position: "absolute", left: -DLG.x * DS, top: -DLG.y * DS, width: 3200 * DS, height: 2000 * DS }}>
          <Shot src="shots/confirm-dialog.png" style={{ width: 3200 * DS, height: 2000 * DS }} />
        </div>
        <div style={{ position: "absolute", left: 0, top: 0 }}>
          <GoldBox x={52 * DS} y={128 * DS} w={318 * DS} h={42 * DS} p={hi(25.0)} r={10} />
          <GoldBox x={370 * DS} y={128 * DS} w={370 * DS} h={42 * DS} p={hi(25.6)} r={10} />
          <GoldBox x={52 * DS} y={174 * DS} w={70 * DS} h={42 * DS} p={hi(26.2)} r={10} />
          <GoldBox x={608 * DS} y={318 * DS} w={178 * DS} h={80 * DS} p={env(t, 26.5, 27.3, 0.2, 0.2)} r={14} fill={false} width={6} />
        </div>
        {press > 0 && <div style={{ position: "absolute", left: 608 * DS, top: 318 * DS, width: 178 * DS, height: 80 * DS, borderRadius: 14, background: `rgba(223,188,112,${0.35 * press})` }} />}
      </div>
    </AbsoluteFill>
  );
};

// 启动前校验：重新读取墒情与设备状态
const Verify: React.FC<{ t: number }> = ({ t }) => {
  const p = env(t, 27.3, 29.25, 0.35, 0.35);
  if (p <= 0) return null;
  const rows = [
    { at: 27.65, done: 28.1, text: "墒情读数有效", detail: "37.7% ＜ 阈值 40%" },
    { at: 27.95, done: 28.45, text: "演示泵房控制器", detail: "在线 · 已停机 · 可接收" },
  ];
  const pass = ramp(t, 28.65, 0.3);
  return (
    <div style={{ position: "absolute", left: 960 - 430, top: 300, width: 860, opacity: p, transform: `translateY(${(1 - p) * 16}px)`, padding: "34px 44px 30px", borderRadius: 24, background: "rgba(9,24,18,.92)", border: "1px solid rgba(223,188,112,.45)", boxShadow: "0 50px 120px -30px rgba(0,0,0,.6)", fontFamily: FONT.sans, color: C.cream }}>
      <div style={{ fontSize: 34, fontWeight: 600, letterSpacing: "0.06em" }}>重新校验墒情与设备状态</div>
      <div style={{ fontSize: 22, color: "rgba(245,242,230,.6)", marginTop: 8 }}>启动前系统重新读取，条件不符会拒绝</div>
      {rows.map((r, i) => {
        const vis = ramp(t, r.at, 0.3), ok = ramp(t, r.done, 0.25);
        const spin = (t * 540) % 360;
        return (
          <div key={i} style={{ display: "flex", alignItems: "center", gap: 20, marginTop: 24, opacity: vis }}>
            <div style={{ width: 44, height: 44, borderRadius: 22, display: "flex", alignItems: "center", justifyContent: "center", background: ok > 0.5 ? "rgba(61,220,132,.16)" : "transparent", border: ok > 0.5 ? "2px solid #5fd08f" : "3px solid rgba(245,242,230,.25)", borderTopColor: ok > 0.5 ? "#5fd08f" : C.gold, transform: ok > 0.5 ? undefined : `rotate(${spin}deg)` }}>
              {ok > 0.5 && <svg width="24" height="24" viewBox="0 0 24 24"><path d="M5 12.5l4.5 4.5L19 7.5" fill="none" stroke="#7ee2a8" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" strokeDasharray="24" strokeDashoffset={24 * (1 - ok)} /></svg>}
            </div>
            <span style={{ fontSize: 32, fontWeight: 500, width: 290 }}>{r.text}</span>
            <span style={{ fontSize: 30, fontFamily: FONT.mono, color: C.goldSoft }}>{r.detail}</span>
          </div>
        );
      })}
      <div style={{ marginTop: 26, height: 2, background: "rgba(245,242,230,.12)" }} />
      <div style={{ marginTop: 18, fontSize: 30, fontWeight: 600, color: "#7ee2a8", opacity: pass }}>校验通过 · 下发模拟指令</div>
    </div>
  );
};

export const PageScenes: React.FC<{ t: number }> = ({ t }) => {
  const op = pageOpacity(t);
  if (op <= 0) return null;
  // 光标：点“确认并启动模拟灌溉”，再点弹窗“确认启动”
  const [bx, by] = pageToScreen(t, btnCenter);
  const cur = keys(t, [
    { t: 23.3, v: [1500, 980] },
    { t: 24.15, v: [bx + 6, by + 4], e: "inOut" },
    { t: 24.6, v: [bx + 6, by + 4] },
    { t: 26.3, v: [1380, 900], e: "inOut" },
    { t: 26.95, v: [dialogBtn[0], dialogBtn[1]], e: "inOut" },
  ]);
  const curOp = env(t, 23.3, 27.6, 0.3, 0.35);
  return (
    <AbsoluteFill style={{ opacity: op, background: "#fcfcfb" }}>
      <PageLayer t={t} />
      <MapTag t={t} />
      <MoistureStats t={t} />
      <ParamList t={t} />
      <Dialog t={t} />
      <Verify t={t} />
      {curOp > 0 && <Cursor x={cur[0]} y={cur[1]} t={t} clickAt={t < 25.5 ? 24.2 : 27.0} opacity={curOp} />}
    </AbsoluteFill>
  );
};

export const pageQuadScreen = (t: number) => R.c01.map((p) => pageToScreen(t, p));
export { mix };
