import React from "react";
import { Composition } from "remotion";
import { BrandFilm, DURATION } from "./BrandFilm";
import { FPS } from "./kit";

export const RemotionRoot: React.FC = () => (
  <>
    <Composition id="BrandFilm" component={BrandFilm} width={1920} height={1080} fps={FPS} durationInFrames={DURATION} defaultProps={{ withAudio: true }} />
    <Composition id="BrandFilmSilent" component={BrandFilm} width={1920} height={1080} fps={FPS} durationInFrames={DURATION} defaultProps={{ withAudio: false }} />
  </>
);
