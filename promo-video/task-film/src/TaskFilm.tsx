import React from "react";
import { AbsoluteFill, staticFile, useCurrentFrame } from "remotion";
import { Audio } from "@remotion/media";
import "./fonts.css";
import { FPS, C, Caption, Corner, Chapter, SimBadge, ramp, env, mix } from "./kit";
import { Farm3D } from "./Farm3D";
import { T, cam3d, fx3d, layer3d } from "./film/track3d";
import { PageScenes, pageQuadScreen } from "./film/PageScenes";
import { OpeningOverlay, LinkageOverlay, SplitOverlay, CloseOverlay, MorphQuad, c01Screen, splitRect } from "./film/Scenes3D";
import { Collab } from "./film/Collab";

export const DURATION = 80 * FPS;

const lerpQuad = (a: [number, number][], b: [number, number][], p: number) => a.map((q, i) => [mix(q[0], b[i][0], p), mix(q[1], b[i][1], p)] as [number, number]);

export const TaskFilm: React.FC<{ withAudio?: boolean }> = ({ withAudio = true }) => {
  const f = useCurrentFrame();
  const t = f / FPS;
  const { visible, split } = layer3d(t);
  const rect = splitRect(split);
  const offX = rect.x + rect.w / 2 - 960, offY = rect.y + rect.h / 2 - 540;
  // 三维层淡入淡出：片头后由页面覆盖；收束段从协作画面中浮现
  const closeFade = t >= 70 ? ramp(t, T.closeIn, 0.7) : 1;
  const splitBg = split > 0 && t < 70;

  const m1 = t >= 4.72 && t < 5.6 ? ramp(t, 4.75, 0.75, "inOut") : -1;
  const m2 = t >= 29.7 && t < 30.75 ? ramp(t, 29.75, 0.8, "inOut") : -1;
  return (
    <AbsoluteFill style={{ background: C.deep }}>
      {splitBg && <AbsoluteFill style={{ background: `radial-gradient(110% 90% at 70% 30%, ${C.brand} 0%, ${C.deep} 60%, #08150f 100%)` }} />}
      <div style={{ position: "absolute", left: rect.x, top: rect.y, width: rect.w, height: rect.h, overflow: "hidden", borderRadius: rect.r, opacity: closeFade, boxShadow: split > 0 ? `0 40px 90px -30px rgba(0,0,0,${0.6 * split})` : undefined }}>
        <Farm3D t={t} cam={cam3d(t)} fx={fx3d(t)} active={visible} style={{ left: offX - rect.x, top: offY - rect.y }} />
      </div>

      <OpeningOverlay t={t} />
      <LinkageOverlay t={t} />
      <SplitOverlay t={t} split={split} />
      <PageScenes t={t} />
      <Collab t={t} />
      <CloseOverlay t={t} />

      {m1 >= 0 && <MorphQuad pts={lerpQuad(c01Screen(t), pageQuadScreen(t), m1)} p={1 - ramp(t, 5.42, 0.16)} />}
      {m2 >= 0 && <MorphQuad pts={lerpQuad(pageQuadScreen(t), c01Screen(t), m2)} p={ramp(t, 29.7, 0.1) * (1 - ramp(t, 30.55, 0.18))} />}

      {/* 段落标签与常驻角标 */}
      <Chapter t={t} from={5.6} to={14.3} no="01" text="发现需要补灌的田块" dark={false} />
      <Chapter t={t} from={14.5} to={23.1} no="02" text="灌溉建议与操作依据" dark={false} />
      <Chapter t={t} from={23.3} to={29.8} no="03" text="人工确认 · 启动前校验" dark={false} />
      <Chapter t={t} from={50.9} to={62.0} no="05" text="设备反馈与运行结束" />
      <SimBadge opacity={env(t, 30.6, 62.2, 0.5, 0.4)} />
      <Corner dark={!(t > 5.3 && t < 30.2)} opacity={(t > 4.6 && t < 62.0) || (t > 72.2 && t < 76.2) ? 1 : 0} />

      {/* 字幕（静音可读） */}
      <Caption t={t} from={6.4} to={13.7} text="定位田块，核对墒情。" dark={false} />
      <Caption t={t} from={15.0} to={22.9} text="建议有依据，执行有范围。" dark={false} />
      <Caption t={t} from={24.7} to={29.5} text="建议由系统辅助，启动由人确认。" />
      <Caption t={t} from={31.6} to={34.9} text="指令下发 → 模拟接收" />
      <Caption t={t} from={35.9} to={41.9} text="水泵启动 · 管路输水" />
      <Caption t={t} from={42.2} to={49.7} text="水流进入 C-01，其他田块保持原状" />
      <Caption t={t} from={54.3} to={61.9} text="设备动作看得见，运行状态有反馈。" />

      {withAudio ? <Audio src={staticFile("audio/mix.wav")} /> : null}
    </AbsoluteFill>
  );
};
