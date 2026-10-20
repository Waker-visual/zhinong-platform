import test from "node:test";
import assert from "node:assert/strict";
import {
  numeric,
  readingState,
  metricSnapshot,
  dailyWeatherSeries,
  completedTaskProgress,
  historySeries,
  historySvg,
  deviceSnapshot,
  productionAmount,
} from "./measurement-model.js";
import { num, lineOption } from "../workspace/presentation.js";

test("缺失观测与真实零值分别显示，历史曲线不把null填成0", () => {
  for (const value of [null, undefined, "", false, "invalid"])
    assert.equal(numeric(value), null);
  assert.equal(numeric(0), 0);
  assert.equal(num(null), "—");
  assert.equal(num(0), "0");
  assert.deepEqual(
    lineOption([
      { time: "2026-10-16T00:00:00Z", value: null },
      { time: "2026-10-16T00:01:00Z", value: 0 },
    ]).series[0].data.map((row) => row[1]),
    [null, 0],
  );
});
const now = Date.parse("2026-10-16T00:10:00Z");
const device = {
  id: "d",
  lifecycle: "ACTIVE",
  protocol: "HTTP_PUSH",
  intervalSeconds: 600,
};
const channel = {
  metric: "TEMPERATURE",
  freshness: "FRESH",
  freshnessSeconds: 1800,
  latest: { value: 0, time: "2026-10-16T00:00:00Z", source: "HTTP_PUSH" },
};
test("指标状态按本通道评估，使用服务端窗口而不私自限制15分钟", () => {
  assert.equal(readingState(device, channel, now).state, "FRESH");
  assert.equal(
    readingState(
      device,
      { ...channel, freshness: "STALE", reason: "物理范围异常" },
      now,
    ).reason,
    "物理范围异常",
  );
  assert.equal(
    readingState(
      device,
      {
        ...channel,
        latest: { ...channel.latest, time: "2026-10-16T00:11:00Z" },
      },
      now,
    ).state,
    "STALE",
  );
  assert.equal(
    readingState({ ...device, lifecycle: "MAINTENANCE" }, channel, now).state,
    "MAINTENANCE",
  );
});
test("手机快照优先有效的同指标测点，而不是首台设备或整机状态", () => {
  const stale = {
    ...device,
    id: "old",
    freshness: "FRESH",
    channels: [{ ...channel, freshness: "STALE" }],
  };
  const good = { ...device, id: "good", channels: [channel] };
  assert.equal(
    metricSnapshot([stale, good], "TEMPERATURE", now).device.id,
    "good",
  );
  assert.equal(metricSnapshot([good], "RAINFALL", now).state, "NO_DATA");
});
test("气象趋势按真实日期定位、缺失日断开并保留零值", () => {
  const series = dailyWeatherSeries(
    [
      { metric: "TEMPERATURE", date: "2026-10-16", average: 4 },
      { metric: "TEMPERATURE", date: "2026-10-13", average: 0 },
      { metric: "TEMPERATURE", date: "2026-10-14", average: null },
      { metric: "TEMPERATURE", date: "2026-10-15", average: 2 },
    ],
    "TEMPERATURE",
  );
  assert.equal(series.rows.length, 3);
  assert.equal(series.segments.length, 2);
  assert.equal(series.latestDate, "2026-10-16");
  assert.equal(series.delta, "4.0");
  assert.equal(series.points[1].x, 10 + (260 * 2) / 3);
});
test("完成率排除取消任务，空任务集合保持无分母状态", () => {
  assert.deepEqual(
    completedTaskProgress({ tasks: 4, completedTasks: 2 }, [
      { code: "CANCELLED", value: 2 },
    ]),
    { eligible: 2, completed: 2, cancelled: 2, percent: 100 },
  );
  assert.equal(
    completedTaskProgress({ tasks: 0, completedTasks: 0 }).percent,
    null,
  );
});
test("设备历史长时间未采样会断线，单点与真实零值仍可呈现", () => {
  const points = [
    { time: "2026-10-16T00:00:00Z", value: 0 },
    { time: "2026-10-16T01:00:00Z", value: 2 },
  ];
  assert.equal(historySeries(points, { intervalSeconds: 60 }).length, 3);
  assert.equal(historySvg(points, { intervalSeconds: 60 }).segments.length, 2);
  assert.equal(historySvg(points.slice(0, 1)).points.length, 1);
  assert.equal(historySvg([{ ...points[0], value: null }]).points.length, 0);
});
test("轮询失败后设备指标随时间变旧，其他通道不能掩盖缺测", () => {
  const snapshot = deviceSnapshot(
    {
      ...device,
      channels: [
        channel,
        { metric: "HUMIDITY", freshness: "NO_DATA", latest: null },
      ],
    },
    now + 1800000,
  );
  assert.equal(snapshot.freshness, "STALE");
  assert.equal(snapshot.freshChannelCount, 0);
  assert.equal(snapshot.channels[1].freshness, "NO_DATA");
  assert.equal(snapshot.dataQuality, "STALE");
});
test("生产有效条数区分无收获和已登记零产量，完成率采用服务端非取消分母", () => {
  assert.equal(
    productionAmount({
      summary: { productionRecords: 0, yieldKg: 0 },
      productionTrend: [],
    }),
    null,
  );
  assert.equal(
    productionAmount({
      summary: { productionRecords: 1, yieldKg: 0 },
      productionTrend: [{ value: 0 }],
    }),
    0,
  );
  assert.equal(
    completedTaskProgress({
      tasks: 10,
      completedTasks: 1,
      cancelledTasks: 8,
      completionEligibleTasks: 2,
    }).percent,
    50,
  );
});
