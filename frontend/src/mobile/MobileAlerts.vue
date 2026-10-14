<script setup>
import AppIcon from "../ui/AppIcon.vue";
import { time } from "./client.mjs";
import { useAlertState } from "./alert-state.mjs";

const props = defineProps({
  alerts: { type: Array, default: () => [] },
  client: { type: Object, required: true },
  role: { type: String, default: "VIEWER" },
  initialId: { type: String, default: "" },
  locked: Boolean,
});
const emit = defineEmits(["close", "changed", "device"]);
const { selectedId, selected, note, busy, error, canHandle, select, handle } =
  useAlertState(props, () => emit("changed"));
</script>

<template>
  <div class="sheet-backdrop" @click.self="emit('close')" @keydown.esc="emit('close')">
    <section class="alert-sheet" role="dialog" aria-modal="true" aria-labelledby="alert-sheet-title" tabindex="-1">
      <header class="sheet-header">
        <div>
          <p class="eyebrow">FIELD ALERTS</p>
          <h2 id="alert-sheet-title">待处理告警</h2>
        </div>
        <button class="icon-button" type="button" aria-label="关闭告警" @click="emit('close')">
          <AppIcon name="close" />
        </button>
      </header>
      <p class="muted sheet-summary">
        {{ alerts.length ? `当前有 ${alerts.length} 条告警，先确认再安排现场处理。` : "当前没有待处理告警。" }}
      </p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <div v-if="alerts.length" class="alert-list" aria-label="告警列表">
        <button v-for="alert in alerts" :key="alert.id" class="alert-item" type="button" :aria-pressed="selectedId === alert.id" :disabled="busy" @click="select(alert)">
          <span class="alert-severity" aria-hidden="true"></span>
          <span class="alert-copy">
            <strong>{{ alert.deviceName || "设备告警" }}</strong>
            <small>{{ alert.message || alert.metric || "需要核对设备状态" }}</small>
            <small>{{ time(alert.openedAt || alert.updatedAt) }}</small>
          </span>
          <AppIcon name="chevronDown" class="alert-chevron" />
        </button>
      </div>
      <p v-else class="empty sheet-empty">刷新后会在这里看到新的设备告警。</p>
      <article v-if="selected" class="alert-detail">
        <div class="section-heading">
          <div>
            <p class="eyebrow">{{ selected.metric || "设备状态" }}</p>
            <h3>{{ selected.message || "请核对设备状态" }}</h3>
          </div>
          <span class="tag">待处理</span>
        </div>
        <dl class="facts">
          <dt>设备</dt><dd>{{ selected.deviceName || "—" }}</dd>
          <dt>发生时间</dt><dd>{{ time(selected.openedAt || selected.updatedAt) }}</dd>
          <dt>当前读数</dt><dd>{{ selected.value ?? "—" }}</dd>
        </dl>
        <button class="full" type="button" @click="emit('device', selected.deviceId)">查看设备状态</button>
        <template v-if="canHandle">
          <label class="alert-note">
            处理说明
            <textarea v-model="note" rows="3" maxlength="300" :disabled="busy" placeholder="例如：已通知田间人员前往查看"></textarea>
          </label>
          <div class="button-row">
            <button type="button" :disabled="busy || locked" @click="handle('ACKNOWLEDGED')">{{ busy ? "保存中…" : "确认告警" }}</button>
            <button class="primary" type="button" :disabled="busy || locked" @click="handle('RESOLVED')">处理完成</button>
          </div>
        </template>
        <p v-else class="muted alert-readonly">当前账号只能查看告警，处理请联系农场管理员或操作员。</p>
      </article>
    </section>
  </div>
</template>
