export const typeNames = {
  WEATHER: "气象站",
  SOIL: "土壤监测",
  WATER: "水情监测",
  GATEWAY: "采集网关",
  OTHER: "通用设备",
};
export const typeIcons = {
  WEATHER: "☀",
  SOIL: "♧",
  WATER: "≈",
  GATEWAY: "⌘",
  OTHER: "◉",
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
export const cropColors = [
  "#69ae84",
  "#99b66b",
  "#d3b966",
  "#60aaa1",
  "#94a6cf",
  "#ce9677",
];
export function cropColor(crop = "") {
  return cropColors[
    [...crop].reduce((n, c) => n + c.charCodeAt(0), 0) % cropColors.length
  ];
}
export function timeText(value) {
  return value
    ? new Date(value).toLocaleString("zh-CN", { hour12: false })
    : "暂无记录";
}
export function num(value, digits = 1) {
  return Number(value || 0).toLocaleString("zh-CN", {
    maximumFractionDigits: digits,
  });
}
export function pieOption(data, name, dark = false) {
  return {
    backgroundColor: "transparent",
    color: cropColors,
    textStyle: { color: dark ? "#ccdfd9" : "#52655b" },
    tooltip: { trigger: "item", renderMode: "richText" },
    legend: {
      bottom: 0,
      type: "scroll",
      textStyle: { color: dark ? "#b5cbc1" : "#52655b" },
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
          borderColor: dark ? "#15332c" : "#fff",
          borderRadius: 5,
        },
        label: { show: false },
        emphasis: { label: { show: true, fontSize: 15, fontWeight: "bold" } },
        data,
      },
    ],
  };
}
export function lineOption(points, unit = "", dark = false) {
  return {
    backgroundColor: "transparent",
    color: ["#42a781"],
    tooltip: { trigger: "axis", renderMode: "richText" },
    grid: { left: 58, right: 22, top: 25, bottom: 62 },
    xAxis: {
      type: "time",
      splitNumber: dark ? 3 : 6,
      axisLabel: { hideOverlap: true, color: dark ? "#bed4c9" : "#667c70" },
    },
    yAxis: {
      type: "value",
      name: unit,
      scale: true,
      axisLabel: { color: dark ? "#bed4c9" : "#667c70" },
      splitLine: { lineStyle: { color: dark ? "#29473e" : "#eaf0eb" } },
    },
    dataZoom: [{ type: "inside" }, { type: "slider", height: 16, bottom: 12 }],
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
