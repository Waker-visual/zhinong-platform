export function numeric(value) {
  if (
    value === null ||
    value === undefined ||
    value === "" ||
    typeof value === "boolean"
  )
    return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

export function readingState(device, channel, now = Date.now()) {
  if (device?.lifecycle && device.lifecycle !== "ACTIVE")
    return {
      state: device.lifecycle,
      reason: device.lifecycle === "MAINTENANCE" ? "设备维护中" : "设备已停用",
    };
  const reading = channel?.latest;
  if (!reading || numeric(reading.value) === null)
    return { state: "NO_DATA", reason: "没有有效观测值" };
  const seconds =
    numeric(channel.freshnessSeconds) ??
    Math.max(180, (numeric(device?.intervalSeconds) ?? 60) * 3);
  const at = new Date(reading.time).getTime();
  if (!Number.isFinite(at) || at > now)
    return { state: "STALE", reason: "观测时间无效或晚于当前时间" };
  if (now - at >= seconds * 1000)
    return { state: "STALE", reason: "观测已过期" };
  if (device?.protocol && reading.source !== device.protocol)
    return { state: "STALE", reason: "采集来源与配置不一致" };
  if (channel.freshness !== "FRESH")
    return {
      state: channel.freshness || "STALE",
      reason: channel.reason || "当前读数未通过有效性核验",
    };
  return { state: "FRESH", reason: "当前指标有效" };
}

// A snapshot represents one explicitly named measuring point, never a farm average.
export function metricSnapshot(devices, metric, now = Date.now()) {
  const candidates = (devices || []).flatMap((device) =>
    (device.channels || [])
      .filter((channel) => channel.metric === metric)
      .map((channel) => ({
        device,
        channel,
        metric,
        ...readingState(device, channel, now),
      })),
  );
  const rank = (item) =>
    item.state === "FRESH"
      ? 0
      : item.device.lifecycle === "ACTIVE" &&
          numeric(item.channel.latest?.value) !== null
        ? 1
        : 2;
  candidates.sort(
    (a, b) =>
      rank(a) - rank(b) ||
      (Date.parse(b.channel.latest?.time) || 0) -
        (Date.parse(a.channel.latest?.time) || 0),
  );
  return candidates[0] || { metric, state: "NO_DATA", reason: "尚无对应设备" };
}

export function deviceSnapshot(device, now = Date.now()) {
  const channels = (device.channels || []).map((channel) => {
    const quality = readingState(device, channel, now);
    return { ...channel, freshness: quality.state, reason: quality.reason };
  });
  const freshChannelCount = channels.filter(
    (channel) => channel.freshness === "FRESH",
  ).length;
  const observed = channels.filter(
    (channel) => channel.latest && numeric(channel.latest.value) !== null,
  ).length;
  return {
    ...device,
    channels,
    freshChannelCount,
    dataQuality: !observed
      ? "NO_DATA"
      : freshChannelCount === channels.length
        ? "COMPLETE"
        : freshChannelCount
          ? "PARTIAL"
          : "STALE",
    freshness:
      device.lifecycle !== "ACTIVE"
        ? device.lifecycle
        : freshChannelCount
          ? "FRESH"
          : observed
            ? "STALE"
            : "NO_DATA",
  };
}

export function dailyWeatherSeries(weather, metric) {
  const rows = (weather || [])
    .filter((row) => row.metric === metric && numeric(row.average) !== null)
    .map((row) => ({
      ...row,
      date: String(row.date).slice(0, 10),
      average: numeric(row.average),
    }))
    .filter((row) => Number.isFinite(Date.parse(row.date + "T00:00:00Z")))
    .sort((a, b) => a.date.localeCompare(b.date));
  if (!rows.length)
    return {
      rows,
      latest: null,
      latestDate: null,
      delta: null,
      segments: [],
      points: [],
    };
  const values = rows.map((row) => row.average),
    low = Math.min(...values),
    range = Math.max(...values) - low || 1;
  const first = Date.parse(rows[0].date + "T00:00:00Z"),
    last = Date.parse(rows.at(-1).date + "T00:00:00Z");
  const segments = [],
    points = [];
  let segment = [],
    previous = null;
  rows.forEach((row) => {
    const at = Date.parse(row.date + "T00:00:00Z");
    if (previous !== null && at - previous > 86400000) {
      segments.push(segment.join(" "));
      segment = [];
    }
    const point = {
      x: 10 + (260 * (at - first)) / (last - first || 1),
      y: 72 - (50 * (row.average - low)) / range,
    };
    segment.push(`${point.x},${point.y}`);
    points.push(point);
    previous = at;
  });
  segments.push(segment.join(" "));
  return {
    rows,
    latest: rows.at(-1).average,
    latestDate: rows.at(-1).date,
    delta: rows.length > 1 ? (values.at(-1) - values[0]).toFixed(1) : null,
    segments,
    points,
  };
}

export function completedTaskProgress(summary, statuses = []) {
  const total = numeric(summary?.tasks),
    completed = numeric(summary?.completedTasks);
  const cancelled =
    numeric(summary?.cancelledTasks) ??
    numeric(statuses.find((row) => row.code === "CANCELLED")?.value) ??
    0;
  const eligible =
    numeric(summary?.completionEligibleTasks) ??
    numeric(summary?.eligibleTasks) ??
    (total === null ? null : Math.max(0, total - cancelled));
  return {
    eligible,
    completed,
    cancelled,
    percent:
      eligible > 0 && completed !== null
        ? Math.round((completed * 100) / eligible)
        : null,
  };
}

export function productionAmount(analytics) {
  if (!analytics) return null;
  const values = (analytics.productionTrend || [])
    .map((row) => numeric(row.value))
    .filter((value) => value !== null);
  const count =
    numeric(analytics.summary?.productionRecords) ??
    analytics.productionTrend?.length ??
    0;
  if (!count) return null;
  return (
    numeric(analytics.summary?.yieldKg) ??
    (values.length ? values.reduce((sum, value) => sum + value, 0) : null)
  );
}

export function deviceCounts(devices, now = Date.now()) {
  if (!Array.isArray(devices))
    return { total: null, monitored: null, fresh: null, late: null };
  const monitored = devices.filter(
    (device) => device.lifecycle === "ACTIVE" && device.protocol !== "MANUAL",
  );
  const fresh = monitored.filter((device) => {
    const at = Date.parse(device.lastReceivedAt);
    const seconds = Math.max(180, (numeric(device.intervalSeconds) ?? 60) * 3);
    return (
      device.connectionState === "FRESH" &&
      Number.isFinite(at) &&
      at <= now &&
      at > now - seconds * 1000
    );
  }).length;
  return {
    total: devices.length,
    monitored: monitored.length,
    fresh,
    late: monitored.length - fresh,
  };
}

export function historySeries(
  points,
  { intervalSeconds = 60, aggregation = "MINUTE" } = {},
) {
  const bucketSeconds = aggregation === "HOUR" ? 3600 : 60;
  const maxGap =
    Math.max(180, bucketSeconds * 2, (numeric(intervalSeconds) ?? 60) * 3) *
    1000;
  const ordered = (points || [])
    .filter((point) => Number.isFinite(Date.parse(point.time)))
    .sort((a, b) => Date.parse(a.time) - Date.parse(b.time));
  const result = [];
  let previous = null;
  for (const point of ordered) {
    const at = Date.parse(point.time);
    if (previous !== null && at - previous > maxGap)
      result.push([
        new Date(previous + bucketSeconds * 1000).toISOString(),
        null,
      ]);
    result.push([point.time, numeric(point.value)]);
    previous = at;
  }
  return result;
}

export function historySvg(points, options) {
  const series = historySeries(points, options),
    present = series.filter((row) => row[1] !== null);
  if (!present.length) return { segments: [], points: [] };
  const values = present.map((row) => row[1]),
    low = Math.min(...values),
    range = Math.max(...values) - low || 1;
  const start = Date.parse(present[0][0]),
    end = Date.parse(present.at(-1)[0]);
  const segments = [],
    coords = [];
  let segment = [];
  for (const [time, value] of series) {
    if (value === null) {
      if (segment.length) segments.push(segment.join(" "));
      segment = [];
      continue;
    }
    const point = {
      x: 10 + (280 * (Date.parse(time) - start)) / (end - start || 1),
      y: 85 - (65 * (value - low)) / range,
    };
    coords.push(point);
    segment.push(`${point.x},${point.y}`);
  }
  if (segment.length) segments.push(segment.join(" "));
  return { segments, points: coords };
}
