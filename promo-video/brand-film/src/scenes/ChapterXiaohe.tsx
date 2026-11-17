import React from "react";
import { AbsoluteFill } from "remotion";
import { C, FONT, S, P, ramp, env, mix, clamp01, beat, bar, Paper, Lines, Chars, Label, Spark, SparkArc, Cursor, card, stag } from "../kit";
import { Sprout } from "../parts/Sprout";
import run from "../content/xiaohe.json";

// ───────── 数据：全部来自真实运行记录 ─────────
// 今日农场数字取自 2026-10-19 采集的 workspace 接口（与主片台词 v4 同源）
const TILES = [
  { n: 8, label: "待办任务" }, { n: 1, label: "逾期任务", warn: true }, { n: 4, label: "现场问题" },
  { n: 16, label: "设备在线" }, { n: 0, label: "未处理告警" },
];
const ACT_ORDER = ["context", "page-source", "tool-3", "tool-4", "tool-5", "tool-6"];
const ACTS = ACT_ORDER.map((id) => run.activities.find((a) => a.id === id)!);
const EVID = [
  { title: "农场资料", note: "青禾设备联动演示场" },
  { title: "今日农场", note: "未完成任务 8 项 · 待处理问题 4 项" },
  { title: "今日经营简报", note: "已查询保存状态" },
  { title: "设备告警", note: "未解决", big: "0" },
  { title: "现场问题", note: "未处理", big: "4" },
  { title: "待办任务", note: "其中逾期 1 项", big: "8" },
];
const CONCLUSION = run.answer.split("\n")[0].replace(/\*\*/g, "").split("。")[0] + "。";
const MARK = "演示分区 3 的灌溉支管渗漏";

// ───────── 版式 ─────────
const L = 140; // 左安全边距
const COL_R = 900; // 对话栏右沿
const EV = { x: 1090, y: 250, w: 690, h: 96, gap: 14 };
const evY = (i: number) => EV.y + i * (EV.h + EV.gap);
const SPROUT_CHAT = { x: 140, y: 352, size: 96 };
const SPROUT_HERO = { x: 196, y: 640, size: 260 };
const BUBBLE = { right: COL_R, top: 250, h: 78 };
// 灌溉建议与地图卡
const PROP = { x: 140, y: 196, w: 880, h: 690 };
const MAP = { x: 1080, y: 196, w: 700, h: 690 };
const CELL = { w: 190, h: 150, gap: 12, ox: MAP.x + 53, oy: MAP.y + 118 };
const cell = (r: number, c: number) => ({ x: CELL.ox + c * (CELL.w + CELL.gap), y: CELL.oy + r * (CELL.h + CELL.gap) });
const c01 = cell(2, 0);
/** 地图卡上 C-01 的四角（屏幕坐标），供章节转场与三维地块对位 */
export const MAP_C01: [number, number][] = [[c01.x, c01.y], [c01.x + CELL.w, c01.y], [c01.x + CELL.w, c01.y + CELL.h], [c01.x, c01.y + CELL.h]];
export const MAP_C01_CENTER: [number, number] = [c01.x + CELL.w / 2, c01.y + CELL.h / 2];
const BTN = { x: PROP.x + 48, y: PROP.y + PROP.h - 118, w: 372, h: 70 };
const DLG = { x: 330, y: 330, w: 740, h: 400 };
const OK = { x: DLG.x + DLG.w - 48 - 190, y: DLG.y + DLG.h - 48 - 66, w: 190, h: 66 };
export const CONFIRM_POINT: [number, number] = [OK.x + OK.w / 2, OK.y + OK.h / 2];

// ───────── 时间 ─────────
const Q2 = bar(16.9); // 第二句
const X = S.xiaohe;
const ASK = beat(X + 3.3); // 问题落进对话
const DOCK = beat(X + 4.9); // 小禾归位
const actAt = (i: number) => beat(S.xRead + 0.5 + i * 1.9);
const STREAM = [S.xAnswer + 0.8, S.xAnswer + 4.2] as const;
const MARK_AT = beat(S.xAnswer + 4.6);
const D = S.xDecide;
const CLICK1 = bar(D + 7.2);
export const CONFIRM_AT = S.turn;

const Tile: React.FC<{ t: number; i: number }> = ({ t, i }) => {
  const d = TILES[i];
  // 五项并排在标题下方，一行读完
  const w = 308, x = L + i * 333, y = 640;
  const p = stag(t, beat(S.question + 1.3), i, 0.3, 0.8);
  const gather = ramp(t, X - 0.35, 0.9, "inOut"); // 汇入“今日农场”依据卡
  if (p <= 0 || gather >= 1) return null;
  const tx = mix(0, EV.x + 80 - x, gather), ty = mix(0, evY(1) + 10 - y, gather);
  const n = Math.round(d.n * ramp(t, beat(S.question + 1.3) + i * 0.3 + 0.1, 0.9, "out"));
  // 第二句出现后，选框在五项之间游移：还没决定先做哪一件
  const hop = t > Q2 + 0.6 && t < X - 0.4 ? Math.floor((t - Q2 - 0.6) / 0.604) % 5 : -1;
  const sel = hop === [1, 3, 0, 4, 2][i] ? 1 : 0;
  return (
    <div style={{ position: "absolute", left: x, top: y, width: w, height: 190, transform: `translate(${tx}px, ${ty + (1 - p) * 40}px) scale(${mix(1, 0.32, gather)})`, transformOrigin: "0 0", opacity: p * (1 - gather), ...card({ borderRadius: 22, border: sel ? `2px solid ${C.gold}` : `1px solid ${P.hair}`, background: sel ? "#fffaf0" : P.card }) }}>
      <div style={{ position: "absolute", left: 28, top: 22, fontFamily: FONT.mono, fontWeight: 700, fontSize: 96, lineHeight: 1, color: d.warn ? "#c2410c" : P.ink, fontVariantNumeric: "tabular-nums" }}>{n}</div>
      <div style={{ position: "absolute", left: 30, bottom: 24, fontFamily: FONT.sans, fontSize: 26, fontWeight: 500, color: P.sub, letterSpacing: "0.06em" }}>{d.label}</div>
      {d.warn && <div style={{ position: "absolute", right: 22, top: 24, width: 12, height: 12, borderRadius: 6, background: "#c2410c" }} />}
    </div>
  );
};

const Evidence: React.FC<{ t: number; i: number; cited: number }> = ({ t, i, cited }) => {
  const at = i === 1 ? X - 0.1 : actAt(i) - 0.1;
  const p = ramp(t, at, 0.9, "out");
  if (p <= 0) return null;
  const e = EVID[i];
  return (
    <div style={{ position: "absolute", left: EV.x, top: evY(i), width: EV.w, height: EV.h, opacity: p, transform: `perspective(1600px) translateX(${(1 - p) * 220}px) rotateY(${(1 - p) * -32}deg)`, transformOrigin: "100% 50%", ...card({ borderRadius: 18, border: cited > 0 ? `2px solid rgba(184,137,58,${0.3 + 0.7 * cited})` : `1px solid ${P.hair}`, background: cited > 0 ? "#fffaf0" : P.card }) }}>
      <div style={{ position: "absolute", left: 26, top: 0, bottom: 0, display: "flex", alignItems: "center", gap: 22 }}>
        <span style={{ fontFamily: FONT.mono, fontSize: 18, letterSpacing: "0.12em", color: C.goldDeep }}>{String(i + 1).padStart(2, "0")}</span>
        <span style={{ fontFamily: FONT.sans, fontSize: 30, fontWeight: 600, color: P.ink, letterSpacing: "0.04em" }}>{e.title}</span>
      </div>
      <div style={{ position: "absolute", right: 28, top: 0, bottom: 0, display: "flex", alignItems: "center", gap: 14, fontFamily: FONT.sans, fontSize: 22, color: P.sub }}>
        {e.note}{e.big && <span style={{ fontFamily: FONT.mono, fontWeight: 700, fontSize: 50, color: P.ink, minWidth: 34, textAlign: "right" }}>{e.big}</span>}
      </div>
    </div>
  );
};

const Check: React.FC<{ done: number; t: number }> = ({ done, t }) => (
  <svg width={30} height={30} viewBox="0 0 30 30" style={{ flex: "none" }}>
    <circle cx={15} cy={15} r={12.5} fill={done > 0.5 ? P.sprout.leaf : "none"} stroke={done > 0.5 ? P.sprout.leaf : "rgba(18,36,28,.2)"} strokeWidth={2.4} />
    {done <= 0.5 && <path d="M15 2.5a12.5 12.5 0 0 1 12.5 12.5" fill="none" stroke={P.ink} strokeWidth={2.4} strokeLinecap="round" transform={`rotate(${(t * 420) % 360} 15 15)`} />}
    {done > 0.5 && <path d="M9 15.5l4.2 4.2 8-8.6" fill="none" stroke="#fff" strokeWidth={2.8} strokeLinecap="round" strokeLinejoin="round" pathLength={1} strokeDasharray={1} strokeDashoffset={1 - clamp01((done - 0.5) * 2)} />}
  </svg>
);

const Row: React.FC<{ k: string; v: React.ReactNode }> = ({ k, v }) => (
  <div style={{ display: "flex", alignItems: "baseline", padding: "15px 0", borderTop: `1px solid ${P.hair}` }}>
    <span style={{ width: 170, fontFamily: FONT.sans, fontSize: 24, color: P.sub, letterSpacing: "0.06em" }}>{k}</span>
    <span style={{ fontFamily: FONT.sans, fontSize: 30, fontWeight: 600, color: P.ink }}>{v}</span>
  </div>
);

export const ChapterXiaohe: React.FC<{ t: number }> = ({ t }) => {
  if (t < S.question - 0.4 || t > S.turn + 2.8) return null;

  // 入场：纸面自下而上盖住四季
  const wipe = ramp(t, S.question - 0.35, 0.8, "inOut");
  // 出场：纸面在章节转场时让位给三维农场
  const paperOut = ramp(t, S.turn + 1.25, 1.2, "inOut");

  // ── 提问段 ──
  const qOut = X - 0.7;
  const h2Move = ramp(t, ASK - 0.55, 0.75, "inOut");

  // ── 小禾 ──
  const pop = ramp(t, X + 0.15, 0.7, "out");
  const dock = ramp(t, DOCK, 0.9, "inOut");
  const sx = mix(SPROUT_HERO.x, SPROUT_CHAT.x, dock), sy = mix(SPROUT_HERO.y, SPROUT_CHAT.y, dock), ss = mix(SPROUT_HERO.size, SPROUT_CHAT.size, dock) * mix(0.3, 1, pop);
  const greet = env(t, X + 0.7, DOCK + 0.2, 0.5, 0.45);
  const doneAt = (i: number) => actAt(i) + 0.95;
  const lastDone = doneAt(5);
  const blinkAt = [X + 1.6, ...ACTS.map((_, i) => doneAt(i)), S.xAnswer + 5.2, S.xClaim + 2.0];
  const blink = Math.max(...blinkAt.map((b) => (t >= b && t < b + 0.28 ? (t - b) / 0.28 : 0)));
  const hop = t < X + 1.2 ? clamp01((t - X - 0.25) / 0.8) : Math.max(...ACTS.map((_, i) => (t >= doneAt(i) && t < doneAt(i) + 0.45 ? (t - doneAt(i)) / 0.45 : 0))) * 0.5;
  const busy = t > S.xRead && t < STREAM[1];
  const status = t < S.xRead ? "" : t < lastDone ? "正在读取农场资料…" : t < STREAM[1] ? "正在整理回答，内容会陆续显示。" : "回答已整理好。";

  // ── 回答 ──
  const collapse = ramp(t, S.xAnswer, 0.7, "inOut");
  const tlH = mix(428, 76, collapse);
  const shown = Math.round(CONCLUSION.length * ramp(t, STREAM[0], STREAM[1] - STREAM[0], "lin"));
  const mark = ramp(t, MARK_AT, 0.7, "inOut");
  const mi = CONCLUSION.indexOf(MARK);
  const cited = ramp(t, MARK_AT + 0.3, 0.6);

  // ── 主张：对话退到背景 ──
  const claim = env(t, S.xClaim, D - 0.15, 0.7, 0.5);
  const chatGone = ramp(t, D - 0.6, 0.6, "in");
  const stageOp = (1 - 0.88 * claim) * (1 - chatGone);

  // ── 决定 ──
  const propIn = ramp(t, D + 0.1, 0.9, "out"), mapIn = ramp(t, D + 0.3, 0.9, "out");
  const gauge = ramp(t, D + 1.0, 1.3, "inOut");
  const dlg = ramp(t, CLICK1 + 0.12, 0.55, "out");
  const after = ramp(t, CONFIRM_AT + 0.05, 0.5, "in"); // 确认后界面退场
  const cur = (() => {
    const a: [number, number] = [1480, 1010], b: [number, number] = [BTN.x + BTN.w - 70, BTN.y + 40], c: [number, number] = [CONFIRM_POINT[0] + 22, CONFIRM_POINT[1] + 10];
    const p1 = ramp(t, CLICK1 - 1.9, 1.5, "inOut"), p2 = ramp(t, CONFIRM_AT - 2.1, 1.6, "inOut");
    const m = [mix(a[0], b[0], p1), mix(a[1], b[1], p1)];
    return { x: mix(m[0], c[0], p2), y: mix(m[1], c[1], p2), o: ramp(t, CLICK1 - 2.0, 0.4) * (1 - ramp(t, CONFIRM_AT + 0.25, 0.3)) };
  })();
  const press1 = env(t, CLICK1 - 0.05, CLICK1 + 0.3, 0.06, 0.2), press2 = env(t, CONFIRM_AT - 0.05, CONFIRM_AT + 0.3, 0.06, 0.2);

  // 光点：从确认按钮飞到地图上的 C-01
  const fly = ramp(t, CONFIRM_AT + 0.08, 0.95, "inOut");
  const landed = clamp01((t - CONFIRM_AT - 1.0) / 0.8);
  const quadGlow = ramp(t, CONFIRM_AT + 0.9, 0.4);

  return (
    <AbsoluteFill style={{ clipPath: wipe < 1 ? `inset(${(1 - wipe) * 100}% 0 0 0)` : undefined }}>
      <Paper warm={ramp(t, D, 4)} style={{ opacity: 1 - paperOut }} />

      {/* 章节标签 */}
      <Label style={{ position: "absolute", left: L, top: 132, opacity: env(t, S.question + 0.5, X - 0.5, 0.5, 0.4) }}>今日农场 · 06:00</Label>
      <div style={{ position: "absolute", left: L, top: 132, display: "flex", gap: 22, alignItems: "baseline", opacity: env(t, X + 0.3, CONFIRM_AT + 0.2, 0.6, 0.4) * (1 - claim) }}>
        <Label>01</Label><Label color={P.sub}>小禾 · 农场 AI 助手</Label>
      </div>

      {/* ── 提问 ── */}
      <Lines t={t} from={S.question + 0.55} out={qOut} lines={["田里的事很多。"]}
        style={{ position: "absolute", left: L, top: 238 }}
        lineStyle={{ fontFamily: FONT.serif, fontWeight: 700, fontSize: 124, lineHeight: 1.2, letterSpacing: "0.05em", color: P.ink }} />
      {t >= Q2 - 0.05 && h2Move < 1 && (
        <div style={{ position: "absolute", left: mix(L, 380, h2Move), top: mix(t < X ? 420 : mix(420, 238, ramp(t, X - 0.6, 0.9, "inOut")), BUBBLE.top - 20, h2Move), transform: `scale(${mix(1, 0.3, h2Move)})`, transformOrigin: "100% 0", opacity: 1 - ramp(t, ASK - 0.3, 0.4) }}>
          <Lines t={t} from={Q2} lines={["今天，先做哪一件？"]} lineStyle={{ fontFamily: FONT.serif, fontWeight: 700, fontSize: mix(124, 94, ramp(t, X - 0.6, 0.9, "inOut")), lineHeight: 1.2, letterSpacing: "0.05em", color: P.ink, whiteSpace: "nowrap" }} />
          <div style={{ marginTop: 14, height: 8, width: 520 * ramp(t, Q2 + 0.7, 0.8, "inOut"), background: C.gold, borderRadius: 4, opacity: 1 - ramp(t, X - 0.6, 0.4) }} />
        </div>
      )}
      {TILES.map((_, i) => <Tile key={i} t={t} i={i} />)}

      {/* ── 对话舞台 ── */}
      <AbsoluteFill style={{ opacity: stageOp, transform: `scale(${mix(1, 0.94, claim)})`, filter: claim > 0.02 ? `blur(${claim * 10}px)` : undefined, display: t < X - 0.2 || stageOp <= 0 ? "none" : undefined }}>
        {EVID.map((_, i) => <Evidence key={i} t={t} i={i} cited={[3, 4, 5].includes(i) ? cited : 0} />)}

        {/* 用户问题 */}
        {t >= ASK - 0.35 && (() => {
          const p = ramp(t, ASK - 0.3, 0.6, "out");
          return (
            <div style={{ position: "absolute", right: 1920 - BUBBLE.right, top: BUBBLE.top, height: BUBBLE.h, padding: "0 34px", display: "flex", alignItems: "center", borderRadius: "26px 26px 6px 26px", background: P.ink, color: C.cream, fontFamily: FONT.sans, fontSize: 32, fontWeight: 500, letterSpacing: "0.03em", whiteSpace: "nowrap", opacity: p, transform: `scale(${mix(1.14, 1, p)})`, transformOrigin: "100% 50%", boxShadow: "0 24px 50px -24px rgba(18,36,28,.6)" }}>{run.question}</div>
          );
        })()}

        {/* 小禾与问候（文案取自产品） */}
        {pop > 0 && <div style={{ position: "absolute", left: sx, top: sy }}><Sprout size={ss} hop={hop} wave={clamp01((t - X - 0.5) / 1.3)} blink={blink} busy={busy} sway={Math.sin(t * 1.3) * 0.4} /></div>}
        {greet > 0 && (
          <div style={{ position: "absolute", left: 500, top: 706, width: 600, padding: "26px 34px 30px", opacity: greet, transform: `translateY(${(1 - greet) * 16}px)`, ...card({ borderRadius: "30px 30px 30px 8px" }) }}>
            <div style={{ fontFamily: FONT.sans, fontSize: 24, fontWeight: 700, color: P.ink }}>小禾 <span style={{ fontWeight: 400, color: P.sub }}>· 农场小助手</span></div>
            <div style={{ marginTop: 10, fontFamily: FONT.sans, fontSize: 32, lineHeight: 1.6, color: P.ink }}>
              <Chars t={t} from={X + 0.95} span={1.2} text="我是小禾，陪你看农情、理农事。" />
            </div>
          </div>
        )}
        {dock > 0.6 && (
          <div style={{ position: "absolute", left: 258, top: 372, opacity: ramp(t, DOCK + 0.5, 0.5) }}>
            <div style={{ fontFamily: FONT.sans, fontSize: 30, fontWeight: 700, color: P.ink }}>小禾</div>
            <div style={{ marginTop: 4, fontFamily: FONT.sans, fontSize: 22, color: P.sub, letterSpacing: "0.04em" }}>{status}</div>
          </div>
        )}

        {/* 活动时间线：标签与结果摘要来自真实 SSE 事件 */}
        {t >= S.xRead - 0.2 && (
          <div style={{ position: "absolute", left: L, top: 476, width: COL_R - L, height: tlH, overflow: "hidden", opacity: ramp(t, S.xRead - 0.15, 0.6), ...card({ borderRadius: 22 }) }}>
            <div style={{ position: "absolute", left: 30, right: 30, top: 0, height: 76, display: "flex", alignItems: "center", gap: 16, opacity: collapse, fontFamily: FONT.sans, fontSize: 26, color: P.ink }}>
              <Check done={1} t={t} /><span style={{ fontWeight: 600 }}>读取 2 项资料 · 查询 4 项</span>
            </div>
            <div style={{ position: "absolute", left: 30, right: 30, top: 28, opacity: 1 - collapse }}>
              {ACTS.map((a, i) => {
                const p = ramp(t, actAt(i), 0.45, "out");
                const done = clamp01((t - actAt(i) - 0.55) / 0.5);
                return (
                  <div key={a.id} style={{ height: 62, display: "flex", alignItems: "center", gap: 18, opacity: p, transform: `translateX(${(1 - p) * -18}px)` }}>
                    <Check done={done} t={t} />
                    <span style={{ fontFamily: FONT.sans, fontSize: 28, fontWeight: 600, color: P.ink, letterSpacing: "0.03em" }}>{a.label}</span>
                    <span style={{ flex: 1 }} />
                    <span style={{ fontFamily: FONT.sans, fontSize: 20, color: P.sub, opacity: clamp01((done - 0.6) * 2.5) }}>{a.summary}</span>
                  </div>
                );
              })}
            </div>
          </div>
        )}

        {/* 回答首句（原文，逐字流出） */}
        {t >= STREAM[0] - 0.1 && (
          <div style={{ position: "absolute", left: L + 6, top: 584, width: COL_R - L - 12, fontFamily: FONT.sans, fontSize: 31, lineHeight: 1.72, color: P.ink, letterSpacing: "0.02em" }}>
            {[...CONCLUSION].map((ch, i) => {
              const inMark = i >= mi && i < mi + MARK.length;
              const on = inMark ? clamp01(mark * MARK.length - (i - mi)) : 0;
              return <span key={i} style={{ opacity: i < shown ? 1 : 0, fontWeight: i < 3 || inMark ? 700 : 400, background: on > 0 ? `rgba(223,188,112,${0.5 * on})` : undefined, boxShadow: on > 0 ? `0 0 0 4px rgba(223,188,112,${0.5 * on})` : undefined }}>{ch}</span>;
            })}
          </div>
        )}
        {/* 飞向小禾的引用光点 */}
        {ACTS.map((_, i) => {
          const u = ramp(t, actAt(i) + 0.3, 0.75, "inOut");
          if (u <= 0 || u >= 1) return null;
          const a: [number, number] = [EV.x, evY(i) + EV.h / 2], b: [number, number] = [SPROUT_CHAT.x + 48, SPROUT_CHAT.y + 40];
          return <SparkArc key={i} a={a} c={[(a[0] + b[0]) / 2, Math.min(a[1], b[1]) - 150]} b={b} u={u} r={7} />;
        })}
      </AbsoluteFill>

      {/* ── 主张 ── */}
      {claim > 0 && (
        <AbsoluteFill style={{ alignItems: "center", justifyContent: "center", flexDirection: "column" }}>
          <Lines t={t} from={S.xClaim + 0.15} out={D - 0.5} lines={["它读过数据，", "才开口。"]} gap={0.18} style={{ textAlign: "center" }}
            lineStyle={{ fontFamily: FONT.serif, fontWeight: 700, fontSize: 156, lineHeight: 1.24, letterSpacing: "0.06em", color: P.ink }} />
          <div style={{ marginTop: 26, height: 8, width: 300 * ramp(t, S.xClaim + 1.0, 0.8, "inOut"), background: C.gold, borderRadius: 4, opacity: 1 - ramp(t, D - 0.6, 0.3) }} />
          <div style={{ marginTop: 52, fontFamily: FONT.sans, fontSize: 28, letterSpacing: "0.14em", color: P.sub, opacity: env(t, S.xClaim + 1.3, D - 0.5, 0.7, 0.3) }}>读取 2 项资料 · 查询 4 项 · 每条建议附数据依据</div>
        </AbsoluteFill>
      )}

      {/* ── 决定 ── */}
      {t >= D - 0.05 && (
        <AbsoluteFill>
          {/* 地图卡 */}
          <div style={{ position: "absolute", left: MAP.x, top: MAP.y, width: MAP.w, height: MAP.h, opacity: mapIn * (1 - ramp(t, CONFIRM_AT + 0.85, 0.5)), transform: `translateY(${(1 - mapIn) * 50}px)`, ...card() }}>
            <div style={{ position: "absolute", left: 53, top: 40, fontFamily: FONT.sans, fontSize: 28, fontWeight: 600, color: P.ink, letterSpacing: "0.05em" }}>农场地图 · 灌溉覆盖区</div>
            <div style={{ position: "absolute", right: 53, top: 46, fontFamily: FONT.mono, fontSize: 20, color: P.sub, letterSpacing: "0.1em" }}>演示分区 3 → C 行</div>
          </div>
          <div style={{ opacity: mapIn * (1 - ramp(t, CONFIRM_AT + 0.85, 0.5)), transform: `translateY(${(1 - mapIn) * 50}px)` }}>
            {["A", "B", "C"].flatMap((r, ri) => [0, 1, 2].map((ci) => {
              const q = cell(ri, ci); const hot = ri === 2 && ci === 0;
              if (hot) return null;
              return (
                <div key={r + ci} style={{ position: "absolute", left: q.x, top: q.y, width: CELL.w, height: CELL.h, borderRadius: 10, border: "1.5px solid rgba(40,88,70,.28)", background: ri === 2 ? "rgba(121,185,117,.16)" : "rgba(40,88,70,.06)", fontFamily: FONT.mono, fontSize: 24, color: "rgba(18,36,28,.55)", display: "grid", placeItems: "center" }}>{r}-0{ci + 1}</div>
              );
            }))}
          </div>
          {/* C-01：转场时保留在画面上，随后与三维地块对位 */}
          {t < CONFIRM_AT + 1.2 && (
            <div style={{ position: "absolute", left: c01.x, top: c01.y + (1 - mapIn) * 50, width: CELL.w, height: CELL.h, borderRadius: 10, border: `4px solid ${C.gold}`, background: `rgba(239,217,166,${0.42 + 0.2 * Math.sin(t * 3.2) * (1 - quadGlow)})`, opacity: mapIn, fontFamily: FONT.mono, fontSize: 30, fontWeight: 700, color: P.ink, display: "grid", placeItems: "center", boxShadow: `0 0 ${40 * quadGlow}px rgba(223,188,112,${0.8 * quadGlow})` }}><span style={{ opacity: 1 - quadGlow }}>C-01</span></div>
          )}

          {/* 灌溉建议卡：数值来自 2026-10-19 的真实建议（37.7% < 40%，120 秒） */}
          <div style={{ position: "absolute", left: PROP.x, top: PROP.y, width: PROP.w, height: PROP.h, opacity: propIn * (1 - after), transform: `translateY(${(1 - propIn) * 50}px) translateX(${-after * 60}px)`, ...card() }}>
            <div style={{ position: "absolute", left: 48, top: 34, right: 48, display: "flex", alignItems: "center", gap: 16 }}>
              <Sprout size={64} blink={blink} />
              <span style={{ fontFamily: FONT.sans, fontSize: 32, fontWeight: 700, color: P.ink, letterSpacing: "0.04em" }}>小禾的灌溉建议</span>
              <span style={{ flex: 1 }} />
              <span style={{ padding: "8px 18px", borderRadius: 30, border: `1.5px solid ${C.goldDeep}`, color: C.goldDeep, fontFamily: FONT.sans, fontSize: 22, fontWeight: 600, letterSpacing: "0.06em" }}>等待人工确认</span>
            </div>
            <div style={{ position: "absolute", left: 48, right: 48, top: 142 }}>
              <div style={{ display: "flex", alignItems: "flex-end", gap: 20 }}>
                <div>
                  <div style={{ fontFamily: FONT.sans, fontSize: 24, color: P.sub, letterSpacing: "0.08em" }}>土壤水分</div>
                  <div style={{ fontFamily: FONT.mono, fontWeight: 700, fontSize: 118, lineHeight: 1.05, color: P.ink, fontVariantNumeric: "tabular-nums" }}>{(37.7 * mix(0.8, 1, gauge)).toFixed(1)}<span style={{ fontSize: 52, fontWeight: 500, color: P.sub }}>%</span></div>
                </div>
                <span style={{ flex: 1 }} />
                <div style={{ textAlign: "right", paddingBottom: 16, opacity: ramp(t, D + 1.8, 0.6) }}>
                  <div style={{ fontFamily: FONT.sans, fontSize: 24, color: "#b45309", fontWeight: 600 }}>低于配置阈值</div>
                  <div style={{ fontFamily: FONT.mono, fontSize: 44, fontWeight: 700, color: P.ink }}>40.0<span style={{ fontSize: 26, color: P.sub, fontWeight: 500 }}>%</span></div>
                </div>
              </div>
              <div style={{ position: "relative", marginTop: 14, height: 14, borderRadius: 7, background: "rgba(18,36,28,.08)" }}>
                <div style={{ width: `${((37.7 - 30) / 15) * 100 * gauge}%`, height: "100%", borderRadius: 7, background: `linear-gradient(90deg, ${P.sprout.young}, ${C.gold})` }} />
                <div style={{ position: "absolute", left: `${((40 - 30) / 15) * 100}%`, top: -9, width: 3, height: 32, background: P.ink, borderRadius: 2 }} />
              </div>
              <div style={{ marginTop: 30 }}>
                <Row k="作用范围" v="C-01 灌溉分区 · 演示分区 3" />
                <Row k="水泵" v="演示泵房控制器" />
                <Row k="运行时长" v="120 秒" />
              </div>
            </div>
            <div style={{ position: "absolute", left: 48, top: PROP.h - 118, width: BTN.w, height: BTN.h, borderRadius: 16, background: P.ink, color: C.cream, display: "grid", placeItems: "center", fontFamily: FONT.sans, fontSize: 27, fontWeight: 600, letterSpacing: "0.04em", transform: `scale(${1 - 0.04 * press1})` }}>确认并启动模拟灌溉</div>
            <div style={{ position: "absolute", left: 48 + BTN.w + 22, top: PROP.h - 118, height: BTN.h, padding: "0 30px", borderRadius: 16, border: `1.5px solid ${P.hair}`, display: "grid", placeItems: "center", fontFamily: FONT.sans, fontSize: 27, color: P.sub }}>取消建议</div>
          </div>

          {/* 确认弹窗：文案与产品 irrigationApproveConfirm 一致 */}
          {dlg > 0 && (
            <>
              <AbsoluteFill style={{ background: `rgba(18,30,24,${0.34 * dlg * (1 - after)})` }} />
              <div style={{ position: "absolute", left: DLG.x, top: DLG.y, width: DLG.w, height: DLG.h, opacity: dlg * (1 - after), transform: `translateY(${(1 - dlg) * 30}px) scale(${mix(0.96, 1, dlg) - 0.03 * after})`, ...card({ borderRadius: 28, boxShadow: "0 60px 120px -40px rgba(10,25,18,.6), 0 20px 40px -24px rgba(10,25,18,.4)" }) }}>
                <div style={{ position: "absolute", left: 48, top: 44, right: 48, fontFamily: FONT.sans, fontSize: 36, fontWeight: 700, color: P.ink }}>确认启动 演示分区 3 的模拟灌溉？</div>
                <div style={{ position: "absolute", left: 48, top: 116, right: 48, fontFamily: FONT.sans, fontSize: 25, lineHeight: 1.75, color: P.sub }}>作用范围：C-01 灌溉分区。水泵“演示泵房控制器”，运行 120 秒。当前仅模拟设备，不会驱动实体水泵；启动前系统会重新读取墒情与设备状态，条件不符会拒绝。</div>
                <div style={{ position: "absolute", left: OK.x - DLG.x - 150, top: OK.y - DLG.y, width: 130, height: OK.h, borderRadius: 16, border: `1.5px solid ${P.hair}`, display: "grid", placeItems: "center", fontFamily: FONT.sans, fontSize: 27, color: P.sub }}>取消</div>
                <div style={{ position: "absolute", left: OK.x - DLG.x, top: OK.y - DLG.y, width: OK.w, height: OK.h, borderRadius: 16, background: press2 > 0.5 || t >= CONFIRM_AT ? C.gold : P.ink, color: press2 > 0.5 || t >= CONFIRM_AT ? P.ink : C.cream, display: "grid", placeItems: "center", fontFamily: FONT.sans, fontSize: 27, fontWeight: 700, transform: `scale(${1 - 0.05 * press2})` }}>确认启动</div>
              </div>
            </>
          )}

          {/* 这一段的主张 */}
          <div style={{ position: "absolute", left: L, top: 916, opacity: env(t, D + 2.2, CONFIRM_AT - 0.1, 0.7, 0.3), fontFamily: FONT.serif, fontWeight: 700, fontSize: 54, letterSpacing: "0.06em", color: P.ink }}>
            建议由小禾给，<span style={{ color: C.goldDeep }}>决定由人做。</span>
          </div>

          <Cursor x={cur.x} y={cur.y} t={t} clickAt={t < CLICK1 + 1 ? CLICK1 : CONFIRM_AT} opacity={cur.o} />
          {fly > 0 && fly < 1 && <SparkArc a={CONFIRM_POINT} c={[(CONFIRM_POINT[0] + MAP_C01_CENTER[0]) / 2, 330]} b={MAP_C01_CENTER} u={fly} r={10} />}
          {landed > 0 && landed < 1 && [0, 0.3].map((o, i) => { const q = clamp01(landed - o); return <div key={i} style={{ position: "absolute", left: MAP_C01_CENTER[0] - 60 - 200 * q, top: MAP_C01_CENTER[1] - 60 - 200 * q, width: 120 + 400 * q, height: 120 + 400 * q, borderRadius: "50%", border: `4px solid ${C.gold}`, opacity: (1 - q) * (1 - paperOut) }} />; })}
        </AbsoluteFill>
      )}
    </AbsoluteFill>
  );
};
