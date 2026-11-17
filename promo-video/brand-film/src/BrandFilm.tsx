import React from "react";
import { AbsoluteFill, Sequence, staticFile, useCurrentFrame } from "remotion";
import { Audio } from "@remotion/media";
import "./fonts.css";
import { FPS, S, TOTAL, DemoBadge, env, ramp } from "./kit";
import { Prologue } from "./scenes/Prologue";
import { ChapterXiaohe } from "./scenes/ChapterXiaohe";
import { FarmLayer, FarmOverlay } from "./scenes/ChapterFarm";
import { Closing } from "./scenes/Closing";
import voice from "./content/voice.json";

// 配乐在旁白期间让出空间：每句前 0.35 s 开始压低，句末 0.5 s 回升
const duck = (t: number) => Math.max(0, ...voice.map((l) => env(t, l.at - 0.35, l.at + l.duration + 0.1, 0.35, 0.5)));
const musicVolume = (t: number) => (0.62 - 0.36 * duck(t)) * (1 - ramp(t, TOTAL - 2.2, 2.0, "soft"));

export const DURATION = Math.round(TOTAL * FPS);

// 全片一条时间轴（秒）。各段自己负责入场与退场，相邻两段共享一个元素或一个镜头来完成转场。
export const BrandFilm: React.FC<{ withAudio?: boolean }> = ({ withAudio = true }) => {
  const t = useCurrentFrame() / FPS;
  const dark = t < S.question || (t > S.turn + 1.8 && t < S.real) || t > S.fall + 1.2;
  return (
    <AbsoluteFill style={{ background: "#050b08" }}>
      <Prologue t={t} />
      <FarmLayer t={t} />
      <ChapterXiaohe t={t} />
      <FarmOverlay t={t} />
      <Closing t={t} />
      <DemoBadge dark={dark} opacity={env(t, S.question + 0.8, S.end + 5.0, 0.6, 0.6)} />
      {withAudio ? (
        <>
          <Audio src={staticFile("audio/music-cut.wav")} volume={(f) => musicVolume(f / FPS)} />
          {voice.map((l) => (
            <Sequence key={l.id} from={Math.round(l.at * FPS)} durationInFrames={Math.ceil((l.duration + 0.3) * FPS)} layout="none">
              <Audio src={staticFile(`audio/vo/${l.id}.mp3`)} volume={1} />
            </Sequence>
          ))}
        </>
      ) : null}
    </AbsoluteFill>
  );
};
