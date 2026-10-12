export const screenResources = [
  {
    key: "workspace",
    label: "农场资料",
    path: (id) => `/farms/${id}/workspace?days=30`,
  },
  {
    key: "operations",
    label: "运行趋势",
    path: (id) => `/farms/${id}/operations?hours=168`,
  },
  {
    key: "analysis",
    label: "智能农情",
    path: (id) => `/ai/analysis?farmId=${id}`,
  },
  {
    key: "briefing",
    label: "每日简报",
    path: (id) => `/ai/briefings/today?farmId=${id}`,
  },
  { key: "geo", label: "地图定位", path: (id) => `/farms/${id}/map-config` },
  { key: "spatial", label: "田块边界", path: (id) => `/farms/${id}/field-map` },
];

const emptyResource = () => ({
  data: null,
  readAt: null,
  error: null,
  loading: false,
});

// Each response publishes independently. The generation guards even transports that
// finish after cancellation; an old farm must never populate the new farm's screen.
export function createScreenRefresh({ request, onUpdate, now = Date.now }) {
  let scope = null,
    generation = 0,
    active = null,
    disposed = false;
  let resources = Object.fromEntries(
    screenResources.map(({ key }) => [key, emptyResource()]),
  );
  const publish = () => {
    if (!disposed)
      onUpdate({
        scope,
        resources: Object.fromEntries(
          Object.entries(resources).map(([key, state]) => [key, { ...state }]),
        ),
        loading: Object.values(resources).some((state) => state.loading),
      });
  };
  function cancel() {
    generation++;
    active?.controller.abort();
    active = null;
    for (const state of Object.values(resources)) state.loading = false;
    publish();
  }
  function reset(farmId = null) {
    cancel();
    scope = farmId;
    resources = Object.fromEntries(
      screenResources.map(({ key }) => [key, emptyResource()]),
    );
    publish();
  }
  function refresh(farmId, { onlyFailed = false } = {}) {
    if (disposed) return Promise.resolve();
    if (!farmId) {
      reset();
      return Promise.resolve();
    }
    if (farmId !== scope) reset(farmId);
    if (active) return active.promise;
    const selected = screenResources.filter(
      ({ key }) => !onlyFailed || resources[key].error,
    );
    if (!selected.length) return Promise.resolve();
    const controller = new AbortController(),
      ticket = ++generation;
    // Assign before publishing so a reactive subscriber cannot start a second batch.
    const batch = { controller, promise: null };
    active = batch;
    for (const { key } of selected) resources[key].loading = true;
    publish();
    const current = () =>
      !disposed && ticket === generation && scope === farmId;
    batch.promise = Promise.all(
      selected.map(async ({ key, path }) => {
        try {
          const data = await request(path(encodeURIComponent(farmId)), {
            signal: controller.signal,
          });
          if (!current()) return;
          resources[key] = {
            data,
            readAt: new Date(now()).toISOString(),
            error: null,
            loading: false,
          };
        } catch (error) {
          if (!current()) return;
          const previous = resources[key];
          const forbidden = [401, 403].includes(error?.status);
          resources[key] = {
            ...previous,
            ...(forbidden ? { data: null, readAt: null } : {}),
            error: error?.cancelled
              ? previous.error
              : error?.message || "读取失败",
            loading: false,
          };
        }
        if (current()) publish();
      }),
    ).finally(() => {
      if (current()) {
        active = null;
        publish();
      }
    });
    return batch.promise;
  }
  return {
    refresh,
    reset,
    cancel,
    dispose() {
      disposed = true;
      cancel();
    },
  };
}

export function screenFreshness(resources) {
  const states = Object.values(resources);
  const times = states
    .map((state) => state.readAt)
    .filter(Boolean)
    .sort();
  return {
    readAt: times[0] || null,
    loaded: times.length,
    total: screenResources.length,
    failed: states.filter((state) => state.error).length,
  };
}
