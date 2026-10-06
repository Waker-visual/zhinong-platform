export const typeNames = {
  WEATHER: "气象站",
  SOIL: "土壤监测",
  WATER: "水情监测",
  GATEWAY: "采集网关",
  OTHER: "通用设备",
  GATE: "灌溉闸门",
  PUMP: "泵房控制器",
  PEST: "虫情监测",
  CAMERA: "视频监测",
  MACHINERY: "农机终端",
};
export const typeIcons = {
  WEATHER: "☀",
  SOIL: "♧",
  WATER: "≈",
  GATEWAY: "⌘",
  OTHER: "◉",
  GATE: "⊞",
  PUMP: "↥",
  PEST: "♧",
  CAMERA: "▣",
  MACHINERY: "⚙",
};
export const sourceNames = {
  SIMULATED: "模拟数据",
  MANUAL: "人工录入",
  HTTP_PUSH: "接口上报",
};
export const stateNames = {
  FRESH: "正常上报",
  STALE: "超时未报",
  NO_DATA: "尚无上报",
  ACTIVE: "启用",
  MAINTENANCE: "维护中",
  DISABLED: "已停用",
  OPEN: "待处理",
  ACKNOWLEDGED: "已确认",
  RESOLVED: "已恢复/关闭",
  PENDING: "待执行",
  RUNNING: "执行中",
  COMPLETED: "已完成",
  CANCELLED: "已取消",
};
// 作物分类色取模块色：两种主题下不翻转，相邻分类的色相相差明显。
export const cropColors = [
  "var(--module-planting)",
  "var(--module-harvest)",
  "var(--module-irrigation)",
  "var(--module-protection)",
  "var(--module-device)",
  "var(--module-simulation)",
];
// 任务状态与全站徽章一致：执行中蓝、已完成绿、待执行与已取消为中性灰
export const statusColors = {
  PENDING: "var(--label-fg)",
  RUNNING: "var(--info)",
  COMPLETED: "var(--ok)",
  CANCELLED: "var(--border-strong)",
};
export function cropColor(crop = "") {
  return cropColors[
    [...crop].reduce((n, c) => n + c.charCodeAt(0), 0) % cropColors.length
  ];
}
export function timeText(value) {
  return value
    ? new Date(value).toLocaleString("zh-CN", { hour12: false })
    : "—";
}
export function num(value, digits = 1) {
  return Number(value || 0).toLocaleString("zh-CN", {
    maximumFractionDigits: digits,
  });
}
export function pieOption(data, name) {
  return {
    backgroundColor: "transparent",
    color: cropColors,
    tooltip: { trigger: "item", renderMode: "richText" },
    legend: {
      bottom: 0,
      type: "scroll",
      pageIconColor: "var(--text-2)",
      pageIconInactiveColor: "var(--border-strong)",
    },
    series: [
      {
        name,
        type: "pie",
        radius: ["43%", "70%"],
        center: ["50%", "43%"],
        avoidLabelOverlap: true,
        itemStyle: {
          borderWidth: 3,
          borderColor: "var(--surface)",
          borderRadius: 5,
        },
        label: { show: false },
        emphasis: { label: { show: true, fontSize: 15, fontWeight: "bold" } },
        data,
      },
    ],
  };
}
// compact 用于大屏模式：横轴刻度更少
export function lineOption(points, unit = "", compact = false) {
  return {
    backgroundColor: "transparent",
    color: ["var(--info)"],
    tooltip: { trigger: "axis", renderMode: "richText" },
    grid: { left: 58, right: 22, top: 25, bottom: 62 },
    xAxis: {
      type: "time",
      splitNumber: compact ? 3 : 6,
      axisLabel: { hideOverlap: true },
    },
    yAxis: {
      type: "value",
      name: unit,
      scale: true,
    },
    dataZoom: [
      { type: "inside" },
      {
        type: "slider",
        height: 16,
        bottom: 12,
        showDataShadow: false,
        borderColor: "var(--border)",
        backgroundColor: "var(--subtle-bg)",
        fillerColor: "var(--nav-selected-bg)",
        handleStyle: {
          color: "var(--surface)",
          borderColor: "var(--border-strong)",
        },
        moveHandleStyle: { color: "var(--border-strong)" },
        textStyle: { color: "var(--label-fg)" },
      },
    ],
    series: [
      {
        name: "监测均值",
        type: "line",
        showSymbol: points.length < 35,
        smooth: false,
        connectNulls: false,
        areaStyle: { opacity: 0.12 },
        data: points.map((p) => [p.time, Number(p.value)]),
      },
    ],
  };
}
export function exportHistory(history) {
  const rows = [
    ["时间", "均值", "最小值", "最大值", "原始记录数", "指标", "单位"],
    ...history.points.map((p) => [
      p.time,
      p.value,
      p.min,
      p.max,
      p.samples,
      history.metric.name,
      history.metric.unit,
    ]),
  ];
  const text = rows
    .map((row) =>
      row
        .map((v) => '"' + String(v ?? "").replaceAll('"', '""') + '"')
        .join(","),
    )
    .join("\r\n");
  const url = URL.createObjectURL(
    new Blob(["\uFEFF" + text], { type: "text/csv;charset=utf-8" }),
  );
  const a = document.createElement("a");
  a.href = url;
  a.download = `监测记录-${history.metric.code}-${history.hours}h.csv`;
  a.click();
  URL.revokeObjectURL(url);
}
