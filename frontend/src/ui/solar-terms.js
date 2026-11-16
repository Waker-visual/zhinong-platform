import { firstTermYear, termTimes } from "./solar-term-times.js";
export const termNames =
  "小寒 大寒 立春 雨水 惊蛰 春分 清明 谷雨 立夏 小满 芒种 夏至 小暑 大暑 立秋 处暑 白露 秋分 寒露 霜降 立冬 小雪 大雪 冬至".split(
    " ",
  );
const entries = termTimes.flatMap((row, year) =>
  row.map((seconds, index) => ({
    name: termNames[index],
    index,
    year: firstTermYear + year,
    at: seconds * 1000,
  })),
);
export const seasons = [
  {
    id: "spring",
    name: "春",
    label: "春生",
    indices: [2, 3, 4, 5, 6, 7],
    gesture: "唤一场春雨",
  },
  {
    id: "summer",
    name: "夏",
    label: "夏长",
    indices: [8, 9, 10, 11, 12, 13],
    gesture: "送一阵夏风",
  },
  {
    id: "autumn",
    name: "秋",
    label: "秋收",
    indices: [14, 15, 16, 17, 18, 19],
    gesture: "拂一片秋叶",
  },
  {
    id: "winter",
    name: "冬",
    label: "冬藏",
    indices: [20, 21, 22, 23, 0, 1],
    gesture: "落一场轻雪",
  },
];
export function solarTermInYear(year, index) {
  if (
    !Number.isInteger(year) ||
    !Number.isInteger(index) ||
    index < 0 ||
    index > 23
  )
    return null;
  const at = termTimes[year - firstTermYear]?.[index];
  return at === undefined ? null : solarTermAt(at * 1000);
}
// The winter season spans November through the following January.
export function seasonTerms(cycleYear, season) {
  return (seasons.find((item) => item.id === season)?.indices || [])
    .map((index) => solarTermInYear(cycleYear + Number(index < 2), index))
    .filter(Boolean);
}
export const seasonalDiscoveries = {
  spring: [
    {
      id: "plants",
      title: "看看新叶",
      text: "巡田时记录出苗与叶色，把生长的变化留在照片里。",
      object: "田间幼苗",
    },
    {
      id: "water",
      title: "沿水边走走",
      text: "雨后观察田间是否积水，将现场情况交给农场主判断。",
      object: "田边水渠",
    },
    {
      id: "shelter",
      title: "打开春耕笔记",
      text: "核对本季种植计划和人员安排，再结合当地农时准备作业。",
      object: "田边农舍",
    },
  ],
  summer: [
    {
      id: "plants",
      title: "躲进一片绿荫",
      text: "关注高温预警，露天巡田时合理安排休息与作业时间。",
      object: "田间作物",
    },
    {
      id: "water",
      title: "听听水声",
      text: "结合有效测点、当地预报与现场观测判断水分状况。",
      object: "田边水渠",
    },
    {
      id: "shelter",
      title: "去农舍歇一会",
      text: "检查维护记录，安排设备与人员，忙季也让工作有序进行。",
      object: "田边农舍",
    },
  ],
  autumn: [
    {
      id: "plants",
      title: "找一份秋收记忆",
      text: "将采收照片与批次、任务关联，给这一季留下一份完整记录。",
      object: "田间谷穗",
    },
    {
      id: "water",
      title: "看看秋日田水",
      text: "关注昼夜温差与当地预警，巡田时记录水分和作物状态。",
      object: "田边水渠",
    },
    {
      id: "shelter",
      title: "收好这一季",
      text: "核对产量与仓储记录，再看看还有哪些农事尚未完成。",
      object: "田边农舍",
    },
  ],
  winter: [
    {
      id: "plants",
      title: "等下一次生长",
      text: "整理本季的观测与现场问题，让下一季的计划更有依据。",
      object: "冬日田地",
    },
    {
      id: "water",
      title: "留意管线防护",
      text: "结合本地低温预警，记录需要巡检的管线与设施位置。",
      object: "田边水渠",
    },
    {
      id: "shelter",
      title: "为农舍做次巡检",
      text: "查看棚体、门窗与连接处，把需要维护的部位加入现场记录。",
      object: "田边农舍",
    },
  ],
};
export function solarTermAt(now = Date.now()) {
  if (!Number.isFinite(now) || now < entries[0].at || now >= entries.at(-1).at)
    return null;
  const nextIndex = entries.findIndex((entry) => entry.at > now);
  const current = entries[nextIndex - 1],
    next = entries[nextIndex];
  return {
    ...current,
    next,
    progress: (now - current.at) / (next.at - current.at),
    season:
      current.index >= 2 && current.index < 8
        ? "spring"
        : current.index >= 8 && current.index < 14
          ? "summer"
          : current.index >= 14 && current.index < 20
            ? "autumn"
            : "winter",
  };
}
export const termCaptions = [
  "寒意渐深，留心田间的细微变化。",
  "一年将尽，给土地与设备留一份从容。",
  "从一粒种子的计划，迎接新的生长。",
  "雨水渐至，走一遍田间排水沟。",
  "万物苏醒，也开始新的巡田记录。",
  "昼夜相近，整理春耕的节奏。",
  "春光正好，把新叶与土壤一起观察。",
  "雨生百谷，记录每一次生长。",
  "田野走向浓绿，关注水分与通风。",
  "小得盈满，留意作物生长的差异。",
  "农事渐忙，让任务与人员各就其位。",
  "白昼绵长，巡田也记得错开暑热。",
  "暑气渐盛，检查设备与田间水路。",
  "盛夏时节，把田间安全放在心上。",
  "秋意初起，收获的准备可以开始了。",
  "暑气渐退，观察昼夜温度的变化。",
  "晨露初现，留心叶面与田间湿度。",
  "秋色平分，整理这一季的生长记录。",
  "露意渐凉，关注作物与设施状态。",
  "秋深将冬，为季节转换做好准备。",
  "田野入冬，把这一季的收获妥善收藏。",
  "寒意渐浓，给农场做一次细致巡检。",
  "深冬将至，查看设施与设备维护记录。",
  "长夜之后，新的生长正在酝酿。",
];
export const seasonNotes = {
  spring: [
    ["巡田观察", "查看土壤墒情、出苗与叶色，把异常拍照记录后交给农场主复核。"],
    [
      "作业准备",
      "核对种植计划、人员安排与设备维护状态，再按本地农时安排作业。",
    ],
  ],
  summer: [
    ["水分与通风", "结合天气预报、现场观测与传感器有效读数判断田间状态。"],
    [
      "田间安全",
      "关注高温和强降雨预警，调整露天作业安排，并检查排水与电气设施。",
    ],
  ],
  autumn: [
    ["收获记录", "核对批次、采收任务与产量记录，为本季复盘留下可追溯的数据。"],
    ["天气变化", "关注昼夜温差及当地预警，巡查作物、仓储与设施状态。"],
  ],
  winter: [
    [
      "设施巡检",
      "结合本地低温预警，检查棚体、管线与设备防护，记录需要维护的部位。",
    ],
    ["一季复盘", "整理收获、投入与现场问题，核对未结束任务，为下一季做准备。"],
  ],
};
