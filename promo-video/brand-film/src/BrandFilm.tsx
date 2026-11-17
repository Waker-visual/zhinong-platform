import React from "react";
import { AbsoluteFill, staticFile, useCurrentFrame } from "remotion";
import { Audio } from "@remotion/media";
import "./fonts.css";
import { FPS, S, TOTAL, DemoBadge, env } from "./kit";
import { Prologue } from "./scenes/Prologue";
import { ChapterXiaohe } from "./scenes/ChapterXiaohe";
import { FarmLayer, FarmOverlay } from "./scenes/ChapterFarm";
import { Closing } from "./scenes/Closing";

export const DURATION = Math.round(TOTAL * FPS);

// 全片一条时间轴（秒）。各段自己负责入场与退场，相邻两段共享一个元素或一个镜头来完成转场。
export const BrandFilm: React.FC<{ withAudio?: boolean }> = ({ withAudio = true }) => {
  const t = useCurrentFrame() / FPS;
  const dark = t < S.question || (t > S.turn + 1.8 && t < S.montage) || t > S.fall + 1.2;
  return (
    <AbsoluteFill style={{ background: "#050b08" }}>
      <Prologue t={t} />
      <FarmLayer t={t} />
      <ChapterXiaohe t={t} />
      <FarmOverlay t={t} />
      <Closing t={t} />
      <DemoBadge dark={dark} opacity={env(t, S.question + 0.8, S.end + 5.0, 0.6, 0.6)} />
      {withAudio ? <Audio src={staticFile("audio/music-cut.wav")} volume={0.6} /> : null}
    </AbsoluteFill>
  );
};
