import { computed, onScopeDispose, ref, watch } from "vue";

export function useAlertState(props, changed) {
  const selectedId = ref("");
  const note = ref("");
  const busy = ref(false);
  const error = ref("");
  const canHandle = computed(() => ["ADMIN", "OPERATOR"].includes(props.role));
  const selected = computed(() => props.alerts.find((alert) => alert.id === selectedId.value) || null);
  let selectionVersion = 0;

  function selectId(id) {
    if (selectedId.value === id) return;
    selectionVersion++;
    selectedId.value = id;
    note.value = "";
    error.value = "";
  }

  watch(
    [() => props.alerts, () => props.initialId],
    ([alerts, initialId], [, previousInitialId]) => {
      if (initialId !== previousInitialId && alerts.some((alert) => alert.id === initialId)) {
        selectId(initialId);
      } else if (!alerts.some((alert) => alert.id === selectedId.value)) {
        selectId(alerts[0]?.id || "");
      }
    },
    { immediate: true },
  );

  function select(alert) {
    if (!busy.value) selectId(alert.id);
  }

  async function handle(status) {
    if (!selected.value || busy.value || props.locked || !canHandle.value) return;
    const text = note.value.trim();
    if (!text) {
      error.value = status === "RESOLVED" ? "请先写明处理结果，再关闭告警。" : "请补充确认说明，方便电脑端继续跟进。";
      return;
    }
    const version = selectionVersion;
    const id = selected.value.id;
    busy.value = true;
    error.value = "";
    try {
      await props.client.request(`/alerts/${id}`, "PATCH", { status, note: text });
      if (version !== selectionVersion) return;
      note.value = "";
      changed();
    } catch (e) {
      if (version === selectionVersion && !e.stale) error.value = e.message;
    } finally {
      busy.value = false;
    }
  }

  onScopeDispose(() => { selectionVersion++; });
  return { selectedId, selected, note, busy, error, canHandle, select, handle };
}
