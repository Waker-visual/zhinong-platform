import React from "react";
import { Composition } from "remotion";
import { TaskFilm, DURATION } from "./TaskFilm";
import { FPS } from "./kit";

export const RemotionRoot: React.FC = () => (
  <>
    <Composition id="TaskFilm" component={TaskFilm} width={1920} height={1080} fps={FPS} durationInFrames={DURATION} defaultProps={{ withAudio: true }} />
    <Composition id="TaskFilmSilent" component={TaskFilm} width={1920} height={1080} fps={FPS} durationInFrames={DURATION} defaultProps={{ withAudio: false }} />
  </>
);