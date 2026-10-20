// 三块经营地块按产品 buildSceneModel 的示意布局排布（3 块 → 2 列 × 2 行）。
// 名称与作物取自 capture/out/api-v2/desk_overview 的 workspace 接口（青禾设备联动演示场）。
const PLOTS = [
  { id: "p1", name: "演示分区 1", crop: "水稻" },
  { id: "p2", name: "演示分区 2", crop: "水稻" },
  { id: "p3", name: "演示分区 3", crop: "蔬菜" },
];
const columns = 2, rows = 2, cellWidth = 54 / columns, cellDepth = 36 / rows;

export const SCENE_PLOTS = PLOTS.map((p, index) => ({
  id: `plot:${p.id}`,
  label: p.name,
  crop: p.crop,
  position: [-27 + ((index % columns) + 0.5) * cellWidth, 0, -18 + (Math.floor(index / columns) + 0.5) * cellDepth] as [number, number, number],
  size: [cellWidth - 1.6, cellDepth - 1.6] as [number, number],
}));

export const SCENE_MODEL = {
  status: "ready",
  plots: SCENE_PLOTS,
  hotspots: [],
  layoutKey: "task-film",
};

// 演示分区 3 = 前排左侧地块；C-01 / C-02 / C-03 沿 x 方向三等分（与系统地图中 C 行从左到右一致）。
export const PLOT3 = SCENE_PLOTS[2];
const [p3x, , p3z] = PLOT3.position;
const [p3w, p3d] = PLOT3.size;
export const ZONE_W = p3w / 3;
export const ZONES = ["C-01", "C-02", "C-03"].map((name, i) => ({
  name,
  center: [p3x - p3w / 2 + ZONE_W * (i + 0.5), 0, p3z] as [number, number, number],
  size: [ZONE_W, p3d] as [number, number],
}));
export const PLOT3_EDGE = { west: p3x - p3w / 2, east: p3x + p3w / 2, north: p3z - p3d / 2, south: p3z + p3d / 2 };
// 泵房在水塘西岸（产品场景坐标），出水管口朝东。
export const PUMP = { body: [33, 1, 9] as [number, number, number], panel: [32.8, 1.8, 9] as [number, number, number] };
