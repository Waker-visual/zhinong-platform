<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { api } from "../api";
import { confirmAction } from "../ui/confirm";
import { toast } from "../ui/feedback";
import SelectMenu from "../ui/SelectMenu.vue";
import { timeText, num, stateNames } from "./presentation";

const props = defineProps({ device: Object, role: String });
const emit = defineEmits(["changed"]);
const actions = ref([]),
  commands = ref([]),
  selected = ref(""),
  value = ref(0),
  note = ref("");
const busy = ref(false),
  error = ref("");
let alive = true,
  timer,
  request = null;
const writer = computed(() => ["ADMIN", "OPERATOR"].includes(props.role));
const action = computed(() =>
  actions.value.find((item) => item.code === selected.value),
);
const options = computed(() =>
  actions.value.map((item) => ({
    value: item.code,
    label: item.name + (item.role === "ADMIN" ? "（管理员）" : ""),
    disabled: item.role === "ADMIN" && props.role !== "ADMIN",
  })),
);
const states = {
  PENDING: "等待领取",
  DISPATCHED: "已领取，等待回执",
  SUCCEEDED: "回执成功",
  FAILED: "设备执行失败",
  EXPIRED: "已超时，结果未知",
  CANCELLED: "已撤销 / 失效",
};
const feedback = computed(() =>
  props.device.channels.filter((c) =>
    [
      "GATE_OPENING",
      "PUMP_RUNNING",
      "STANDBY_RUNNING",
      "PUMP_FREQUENCY",
      "REMOTE_ENABLED",
      "FAULT",
      "EMERGENCY_STOP",
      "PARAMETER_WRITE_ENABLED",
    ].includes(c.metric),
  ),
);
function feedbackText(channel) {
  if (!channel.latest) return "—";
  const flags = {
    FAULT: ["正常", "故障"],
    REMOTE_ENABLED: ["本地模式", "远程模式"],
    PUMP_RUNNING: ["已停止", "运行中"],
    STANDBY_RUNNING: ["已停止", "运行中"],
    EMERGENCY_STOP: ["未触发", "已急停"],
    PARAMETER_WRITE_ENABLED: ["禁止设置", "允许设置"],
  };
  return (
    flags[channel.metric]?.[Number(channel.latest.value)] ??
    num(channel.latest.value, 0)
  );
}
async function load() {
  const data = await api(`/assets/${props.device.id}/commands`);
  if (!alive) return;
  const before = JSON.stringify(commands.value);
  commands.value = data.commands;
  actions.value = data.actions;
  if (!selected.value) selected.value = data.actions[0]?.code || "";
  if (before !== JSON.stringify(data.commands)) emit("changed");
}
async function submit() {
  if (busy.value || !action.value) return;
  const text = `${action.value.name}${action.value.min == null ? "" : ` ${value.value}${action.value.unit}`}，设备：${props.device.name}。`;
  if (
    !(await confirmAction({
      title:
        props.device.protocol === "SIMULATED"
          ? "确认模拟控制"
          : "确认提交设备指令",
      message:
        text +
        (props.device.protocol === "SIMULATED"
          ? "仅更新本地模拟反馈。"
          : "指令有效期 120 秒，领取后需设备回执，实际状态以采样反馈为准。"),
      confirmLabel: "确认提交",
      danger: true,
    }))
  )
    return;
  if (!alive) return;
  const body = {
    action: selected.value,
    value: action.value.min == null ? null : Number(value.value),
    note: note.value.trim(),
  };
  const signature = JSON.stringify(body);
  if (request?.signature !== signature)
    request = { signature, requestId: crypto.randomUUID() };
  busy.value = true;
  error.value = "";
  try {
    const result = await api(`/assets/${props.device.id}/commands`, "POST", {
      ...body,
      requestId: request.requestId,
    });
    if (!alive) return;
    request = null;
    toast(
      result.protocol === "SIMULATED"
        ? "模拟指令已执行"
        : "指令已提交，等待设备领取和回执",
    );
    await load();
    emit("changed");
  } catch (e) {
    if (alive) error.value = e.message;
  } finally {
    if (alive) busy.value = false;
  }
}
async function cancel(command) {
  busy.value = true;
  try {
    await api(
      `/assets/${props.device.id}/commands/${command.id}/cancel`,
      "POST",
    );
    await load();
    toast("指令已撤销");
  } catch (e) {
    error.value = e.message;
  } finally {
    if (alive) busy.value = false;
  }
}
async function refresh() {
  try {
    await load();
  } catch (e) {
    if (alive) error.value = e.message;
  }
}
onMounted(() => {
  refresh();
  timer = setInterval(() => {
    if (!busy.value && !document.hidden) refresh();
  }, 5000);
});
onBeforeUnmount(() => {
  alive = false;
  clearInterval(timer);
});
</script>

<template>
  <section class="device-control" aria-label="设备控制与回执">
    <div class="section-title">
      <h3>设备控制与回执</h3>
      <button class="outline" @click="refresh" :disabled="busy">
        刷新指令
      </button>
    </div>
    <p class="muted">
      {{
        device.protocol === "SIMULATED"
          ? "本地模拟控制，未连接实体设备。"
          : "目标参数与实际反馈分开记录。回执成功后，仍需核对设备采样；超时表示结果未知。"
      }}
    </p>
    <div class="control-feedback">
      <div v-for="channel in feedback" :key="channel.metric">
        <span>{{ channel.name }}</span>
        <strong>{{ feedbackText(channel) }} {{ channel.unit }}</strong>
        <small
          >{{ stateNames[channel.freshness] }} ·
          {{ timeText(channel.latest?.time) }}</small
        >
      </div>
    </div>
    <p v-if="!device.controlEnabled" class="muted">
      控制尚未启用，管理员可在设备资料中授权。
    </p>
    <form
      v-if="writer && device.controlEnabled && device.lifecycle === 'ACTIVE'"
      v-validate
      @submit.prevent="submit"
    >
      <div class="form-grid">
        <label
          >控制操作<SelectMenu
            v-model="selected"
            :options="options"
            aria-label="控制操作"
            :disabled="busy"
        /></label>
        <label v-if="action?.min != null"
          >目标值（{{ action.unit }}）<input
            v-model.number="value"
            type="number"
            :min="action.min"
            :max="action.max"
            step="1"
            required
            :disabled="busy"
        /></label>
        <label
          >操作说明<input
            v-model.trim="note"
            required
            maxlength="300"
            placeholder="填写作业原因，便于追溯"
            :disabled="busy"
        /></label>
      </div>
      <button class="primary" :disabled="busy || !action || !note.trim()">
        {{ device.protocol === "SIMULATED" ? "提交模拟指令" : "提交控制指令" }}
      </button>
    </form>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="!commands.length" class="muted">尚无控制记录。</p>
    <ol v-else class="command-history">
      <li v-for="command in commands" :key="command.id">
        <div>
          <strong
            >{{
              actions.find((a) => a.code === command.action)?.name ||
              command.action
            }}
            {{ command.value ?? "" }}</strong
          ><span class="status-chip"
            >{{ command.protocol === "SIMULATED" ? "模拟 · " : ""
            }}{{ states[command.status] }}</span
          >
        </div>
        <p>
          {{ command.note
          }}<span v-if="command.resultNote"> · {{ command.resultNote }}</span>
        </p>
        <small>{{ timeText(command.createdAt) }} · {{ command.actor }}</small>
        <button
          v-if="writer && command.status === 'PENDING'"
          class="outline"
          :disabled="busy"
          @click="cancel(command)"
        >
          撤销待领取指令
        </button>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.device-control {
  margin-block: 24px;
  padding: 20px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--subtle-bg);
}
.control-feedback {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px;
  margin-block: 16px;
}
.control-feedback > div {
  display: grid;
  gap: 6px;
}
.control-feedback small,
.command-history small {
  color: var(--label-fg);
}
.command-history {
  padding: 0;
  list-style: none;
  max-height: 320px;
  overflow: auto;
}
.command-history li {
  border-top: 1px solid var(--border);
  padding-block: 12px;
}
.command-history li > div {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  align-items: center;
}
.command-history p {
  margin-block: 8px;
  overflow-wrap: anywhere;
}
.command-history button {
  margin-inline-start: 12px;
}
</style>
