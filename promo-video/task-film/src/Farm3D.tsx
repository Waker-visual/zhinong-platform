import React, { useLayoutEffect, useMemo, useRef } from "react";
import * as THREE from "three";
import { createFarmWorld } from "./three/farm-world.js";
import { buildIrrigation } from "./three/irrigation.js";
import { SCENE_MODEL } from "./three/model";

export type V3 = [number, number, number];
export type Cam = { position: V3; lookAt: V3; fov?: number };
export type Fx = Record<string, number | undefined>;
type World = ReturnType<typeof createFarmWorld> & { rig: ReturnType<typeof buildIrrigation> };

// 每个 Remotion 渲染标签页只建一次三维世界；各帧同步设置相机与设备状态后渲染。
let shared: World | null = null;
function getWorld(canvas: HTMLCanvasElement, w: number, h: number): World {
  if (shared && shared.renderer.domElement === canvas) return shared;
  shared?.dispose();
  const world = createFarmWorld({ canvas, model: SCENE_MODEL, width: w, height: h } as any) as World;
  world.rig = buildIrrigation(world);
  world.renderer.shadowMap.needsUpdate = true;
  shared = world;
  return world;
}

export type Projector = (p: V3) => { x: number; y: number; visible: boolean };

// 与渲染相机同参的纯计算投影，供 HTML 标注在同一帧内对齐三维物体。
export function makeProjector(cam: Cam, width: number, height: number): Projector {
  const c = new THREE.PerspectiveCamera(cam.fov ?? 43, width / height, 0.5, 420);
  c.position.set(...cam.position);
  c.lookAt(new THREE.Vector3(...cam.lookAt));
  c.updateMatrixWorld();
  const v = new THREE.Vector3();
  return (p) => {
    v.set(...p).project(c);
    return { x: ((v.x + 1) * width) / 2, y: ((1 - v.y) * height) / 2, visible: v.z > -1 && v.z < 1 };
  };
}

export const Farm3D: React.FC<{
  cam: Cam; fx?: Fx; t: number; width?: number; height?: number; active?: boolean;
  overlay?: (project: Projector) => React.ReactNode; style?: React.CSSProperties;
}> = ({ cam, fx = {}, t, width = 1920, height = 1080, active = true, overlay, style }) => {
  const ref = useRef<HTMLCanvasElement>(null);
  const key = JSON.stringify([cam, fx, t, active]);
  useLayoutEffect(() => {
    if (!ref.current || !active) return;
    const world = getWorld(ref.current, width, height);
    world.rig.update(fx, t);
    world.render({ ...cam, time: t });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, width, height]);
  const project = useMemo(() => makeProjector(cam, width, height), [JSON.stringify(cam), width, height]);
  return (
    <div style={{ position: "absolute", left: 0, top: 0, width, height, overflow: "hidden", ...style }}>
      <canvas ref={ref} width={width} height={height} style={{ width, height, display: "block" }} />
      {overlay ? overlay(project) : null}
    </div>
  );
};
