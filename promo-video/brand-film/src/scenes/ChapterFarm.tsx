import React from "react";
import { AbsoluteFill, OffthreadVideo, Sequence, staticFile } from "remotion";
import { C, FONT, FPS, S, ramp, env, mix, clamp01, bar, Lines, Label, Spark, SparkArc, lerp2 } from "../kit";
import { Farm3D, makeProjector, type Projector, type V3 } from "@task/Farm3D";
import { MorphQuad } from "@task/film/Scenes3D";
import { C01, C01_CORNERS, PUMP_TAG, OUTLET_TAG, feedPoint } from "@task/film/track3d";
import { SCENE_PLOTS } from "@task/three/model";
import { F, cam3d, fx3d, flowU } from "./farmTrack";
import { MAP_C01 } from "./ChapterXiaohe";

const f = (s: number) => Math.round(s * FPS);
const REAL2 = bar(S.real + 2.4); // 实录第二镜（卫星影像）

const Tag: React.FC<{ x: number; y: number; title: string; status?: string; tone?: "run" | "recv" | "idle"; p: number; small?: boolean }> = ({ x, y, title, status, tone = "idle", p, small }) => {
  if (p <= 0) return null;
  const dot = tone === "run" ? C.run : tone === "recv" ? C.gold : "#9aa59f";
  return (
    <div style={{ position: "absolute", left: x, top: y, transform: `translate(-50%, -100%) translateY(${-20 - (1 - p) * 14}px)`, opacity: p }}>
      <div style={{ padding: small ? "10px 18px" : "14px 24px", borderRadius: 14, background: "rgba(9,24,18,.86)", border: "1px solid rgba(223,188,112,.5)", boxShadow: "0 20px 50px -18px rgba(0,0,0,.55)", whiteSpace: "nowrap", backdropFilter: "blur(6px)" }}>
        <div style={{ fontFamily: FONT.sans, fontWeight: 600, fontSize: small ? 24 : 30, color: C.cream, letterSpacing: "0.04em" }}>{title}</div>
        {status && <div style={{ display: "flex", alignItems: "center", gap: 10, marginTop: 6, fontFamily: FONT.sans, fontSize: small ? 20 : 23, color: "rgba(245,242,230,.8)" }}>
          <span style={{ width: 11, height: 11, borderRadius: 6, background: dot, boxShadow: tone === "run" ? `0 0 12px ${C.run}` : undefined }} />{status}
        </div>}
      </div>
      <div style={{ position: "absolute", left: "50%", bottom: -18, width: 2, height: 18, background: "rgba(223,188,112,.8)" }} />
    </div>
  );
};

const STEPS = ["指令下发", "水泵启动", "管路输水", "田间出水"];
const STEP_AT = [F.cmd[0], F.lamp, F.flow[0] + 0.6, F.spray];

/** 三维层（置于纸面之下）：章节转场时由纸面让出 */
export const FarmLayer: React.FC<{ t: number }> = ({ t }) => {
  const visible = t >= F.show - 0.05 && t < F.hide + 0.3;
  if (!visible) return null;
  const punch = ramp(t, S.real - 0.3, 0.3, "in"); // 冲向实录画面
  return (
    <AbsoluteFill style={{ transform: `scale(${1 + 0.22 * punch})`, filter: punch > 0.02 ? `blur(${punch * 10}px)` : undefined }}>
      <Farm3D t={t} cam={cam3d(t)} fx={fx3d(t)} active={visible} />
    </AbsoluteFill>
  );
};

/** 三维层之上的标注、指令光点与实录镜头 */
export const FarmOverlay: React.FC<{ t: number }> = ({ t }) => {
  if (t < S.turn + 1.0 || t > S.montage + 0.9) return null;
  const P: Projector = makeProjector(cam3d(t), 1920, 1080);
  const scr = (p: V3): [number, number] => { const s = P(p); return [s.x, s.y]; };
  const in3d = t < F.hide;

  // 章节转场：地图上的 C-01 → 三维里的 C-01
  const morph = ramp(t, S.turn + 1.2, 1.25, "inOut");
  const quad3d = C01_CORNERS.map(scr);
  const quad = MAP_C01.map((q, i) => lerp2(q, quad3d[i], morph));
  const quadP = ramp(t, S.turn + 1.15, 0.1) * (1 - ramp(t, F.lift + 0.9, 0.9));

  // 指令光点：停在 C-01 上，随后沿弧线到泵房
  const c01c = scr([C01.center[0], 0.4, C01.center[2]]);
  const pump = scr(PUMP_TAG);
  const mid = scr([(C01.center[0] + 33) / 2, 16, (C01.center[2] + 9) / 2 - 6]);
  const ctrl: [number, number] = [2 * mid[0] - (c01c[0] + pump[0]) / 2, 2 * mid[1] - (c01c[1] + pump[1]) / 2];
  const arc = ramp(t, F.cmd[0], F.cmd[1] - F.cmd[0], "inOut");
  const dotOn = ramp(t, S.turn + 2.3, 0.4) * (1 - ramp(t, F.receive + 0.2, 0.5));
  const recv = clamp01((t - F.receive) / 0.9);

  const head = scr(feedPoint(flowU(t)));
  const headOn = env(t, F.flow[0] + 0.2, F.flow[1] + 0.2, 0.4, 0.4);

  const state = t < F.receive ? { s: "指令下发中", tone: "recv" as const } : t < F.lamp ? { s: "模拟接收 · 待启动", tone: "recv" as const } : { s: "运行中 · 模拟", tone: "run" as const };
  const pumpTag = env(t, F.cmd[1] - 0.4, S.wide + 0.5, 0.5, 0.5) * (t > S.pump - 1.2 && t < S.pump + 3.6 ? ramp(t, S.pump + 0.4, 0.5) : 1);
  const valve = scr([C01.center[0], 1.4, 0]);
  const out = scr(OUTLET_TAG);

  const titleScrim = env(t, S.farm - 0.4, S.farm + 6.6, 1.0, 0.9);
  const wideScrim = env(t, S.wide - 0.2, S.real - 0.1, 0.9, 0.25);
  const cur = STEP_AT.filter((a) => t >= a).length - 1;
  const rail = env(t, F.cmd[0] - 0.2, S.wide - 0.2, 0.5, 0.5);

  return (
    <AbsoluteFill>
      {in3d && (
        <>
          <AbsoluteFill style={{ background: `linear-gradient(90deg, rgba(6,18,13,${0.74 * Math.max(titleScrim, wideScrim)}) 0%, rgba(6,18,13,${0.3 * Math.max(titleScrim, wideScrim)}) 46%, rgba(6,18,13,0) 72%)` }} />
          <MorphQuad pts={quad} p={quadP} />
          <Spark x={c01c[0]} y={c01c[1]} r={11} opacity={dotOn * (arc <= 0 ? 1 : 0)} />
          {arc > 0 && arc < 1 && <SparkArc a={c01c} c={ctrl} b={pump} u={arc} r={11} trail={0.3} />}
          {recv > 0 && recv < 1 && <div style={{ position: "absolute", left: pump[0] - 24 - 130 * recv, top: pump[1] - 24 - 130 * recv, width: 48 + 260 * recv, height: 48 + 260 * recv, borderRadius: "50%", border: `5px solid ${C.gold}`, opacity: 1 - recv }} />}
          <Spark x={head[0]} y={head[1] - 6} r={8} opacity={headOn} core="#e6f9ff" />

          {/* 地块名称取自真实 workspace 接口 */}
          {SCENE_PLOTS.map((pl, i) => {
            const s = scr([pl.position[0], 2.2, pl.position[2]]);
            return <Tag key={pl.id} x={s[0]} y={s[1]} title={pl.label} status={pl.crop} p={env(t, S.farm + 2.2 + i * 0.6, S.command - 0.6 + i * 0.1, 0.6, 0.4)} small />;
          })}
          <Tag x={pump[0]} y={pump[1]} title="演示泵房控制器" status={state.s} tone={state.tone} p={pumpTag} />
          <Tag x={valve[0]} y={valve[1]} title="支管阀门开启（示意）" p={env(t, F.valve[0] - 0.1, F.valve[1] + 1.3, 0.4, 0.4)} small />
          <Tag x={out[0]} y={out[1]} title="C-01 田间出水" status="仅本次任务覆盖区" tone="run" p={env(t, F.spray + 0.3, S.wide + 0.6, 0.5, 0.5)} small />

          {/* 第二章标题 */}
          <div style={{ position: "absolute", left: 140, top: 132, display: "flex", gap: 22, opacity: env(t, S.farm + 0.2, S.real - 0.2, 0.6, 0.3) }}>
            <Label color={C.gold}>02</Label><Label color="rgba(245,242,230,.78)">三维农场 · 展示大屏</Label>
          </div>
          <Lines t={t} from={S.farm + 0.1} out={S.farm + 6.0} lines={["整座农场，", "一眼看全。"]} gap={0.18}
            style={{ position: "absolute", left: 140, top: 360 }}
            lineStyle={{ fontFamily: FONT.serif, fontWeight: 700, fontSize: 132, lineHeight: 1.22, letterSpacing: "0.06em", color: C.cream, textShadow: "0 10px 50px rgba(0,0,0,.45)" }} />
          <div style={{ position: "absolute", left: 146, top: 716, fontFamily: FONT.sans, fontSize: 30, letterSpacing: "0.12em", color: "rgba(245,242,230,.82)", opacity: env(t, S.farm + 1.4, S.farm + 5.8, 0.7, 0.4) }}>3 个分区 · 16 台设备 · 同一座三维场景</div>

          {/* 联动步骤 */}
          <div style={{ position: "absolute", left: 140, bottom: 96, display: "flex", alignItems: "center", gap: 14, opacity: rail, fontFamily: FONT.sans }}>
            {STEPS.map((s, i) => (
              <React.Fragment key={s}>
                {i > 0 && <span style={{ width: 44, height: 2, background: i <= cur ? C.gold : "rgba(245,242,230,.25)" }} />}
                <span style={{ padding: "10px 20px", borderRadius: 26, fontSize: 24, letterSpacing: "0.08em", fontWeight: i === cur ? 700 : 500, background: i === cur ? C.gold : "rgba(9,24,18,.62)", color: i === cur ? C.ink : i < cur ? "rgba(245,242,230,.92)" : "rgba(245,242,230,.5)", border: `1px solid ${i <= cur ? "rgba(223,188,112,.7)" : "rgba(245,242,230,.16)"}` }}>{s}</span>
              </React.Fragment>
            ))}
          </div>
          <div style={{ position: "absolute", right: 64, top: 124, opacity: env(t, F.cmd[0], S.real - 0.2, 0.5, 0.3), display: "flex", alignItems: "center", gap: 12, padding: "10px 20px", borderRadius: 30, background: "rgba(9,24,18,.62)", border: "1px solid rgba(223,188,112,.4)", fontFamily: FONT.sans, fontSize: 22, letterSpacing: "0.08em", color: C.cream }}>
            <span style={{ width: 10, height: 10, borderRadius: 5, background: C.gold }} />设备联动示意｜模拟运行
          </div>

          <Lines t={t} from={S.wide + 0.1} out={S.real - 0.45} lines={["设备怎么动，", "都看得见。"]} gap={0.16}
            style={{ position: "absolute", left: 140, top: 372 }}
            lineStyle={{ fontFamily: FONT.serif, fontWeight: 700, fontSize: 124, lineHeight: 1.22, letterSpacing: "0.06em", color: C.cream, textShadow: "0 10px 50px rgba(0,0,0,.5)" }} />
        </>
      )}

      {/* 真实应用实录：Unity 三维场景 → 卫星影像（均为 2026-10-19 逐帧录制） */}
      {t >= S.real - 0.02 && (
        <AbsoluteFill style={{ background: "#07110c" }}>
          <AbsoluteFill style={{ transform: `scale(${t < REAL2 ? mix(1.2, 1.02, ramp(t, S.real, 1.1, "out")) : mix(1.14, 1.0, ramp(t, REAL2, 1.1, "out"))})` }}>
            <Sequence from={f(S.real)} durationInFrames={f(REAL2 - S.real)} layout="none">
              <OffthreadVideo src={staticFile("rec/u-wide.mp4")} muted style={{ width: 1920, height: 1080 }} />
            </Sequence>
            <Sequence from={f(REAL2)} durationInFrames={f(S.montage - REAL2) + 60} layout="none">
              <OffthreadVideo src={staticFile("rec/s-overview.mp4")} muted style={{ width: 1920, height: 1080 }} />
            </Sequence>
          </AbsoluteFill>
          <AbsoluteFill style={{ background: "linear-gradient(0deg, rgba(6,18,13,.7) 0%, rgba(6,18,13,0) 34%)" }} />
          <div style={{ position: "absolute", left: 140, bottom: 96, fontFamily: FONT.serif, fontWeight: 700, fontSize: 64, letterSpacing: "0.06em", color: C.cream }}>
            {t < REAL2 ? "三维场景" : "卫星影像"}
            <span style={{ marginLeft: 28, fontFamily: FONT.mono, fontWeight: 500, fontSize: 22, letterSpacing: "0.2em", color: "rgba(245,242,230,.7)" }}>{t < REAL2 ? "UNITY WEBGL · 应用实录" : "ESRI WORLD IMAGERY · 应用实录"}</span>
          </div>
        </AbsoluteFill>
      )}
    </AbsoluteFill>
  );
};
