<script setup>
import { ref, reactive, computed, onMounted } from "vue";
import { api } from "../api";
import { reportFailure, toast } from "../ui/feedback";
import SelectMenu from "../ui/SelectMenu.vue";
import { timeText } from "./presentation";
const props = defineProps({ device: Object, role: String });
const loaded = ref(null),
  busy = ref(false),
  editing = ref(false);
const names = {
  PUMP_MQTT: "smart-farm 泵房",
  GATE_MQTT: "smart-farm 闸门",
  LAN_DTU: "局域网四情采集",
};
const form = reactive({
  adapterType: "",
  externalId: "",
  upstreamTopic: "",
  downstreamTopic: "",
  revision: 0,
  bindings: [],
});
const options = computed(
  () =>
    loaded.value?.adapters.map((a) => ({
      value: a.code,
      label: names[a.code],
    })) || [],
);
const metrics = computed(() =>
  props.device.channels.map((c) => ({ value: c.metric, label: c.name })),
);
const metricName = (code) =>
  props.device.channels.find((c) => c.metric === code)?.name || code;
async function load() {
  try {
    loaded.value = await api(`/assets/${props.device.id}/integration`);
    if (loaded.value.config) Object.assign(form, loaded.value.config);
    else if (loaded.value.adapters.length) {
      form.adapterType = loaded.value.adapters[0].code;
      defaults();
    }
  } catch (e) {
    reportFailure(e, load);
  }
}
function defaults() {
  const preset = loaded.value?.adapters.find(
    (a) => a.code === form.adapterType,
  );
  form.bindings = (preset?.bindings || [])
    .filter((b) => props.device.channels.some((c) => c.metric === b.metric))
    .map((b) => ({ ...b }));
  if (form.adapterType === "LAN_DTU") {
    form.upstreamTopic = "";
    form.downstreamTopic = "";
  }
}
function add() {
  const channel = props.device.channels.find(
    (c) => !form.bindings.some((b) => b.metric === c.metric),
  );
  if (channel)
    form.bindings.push({
      metric: channel.metric,
      field: "",
      unit: channel.unit,
    });
}
async function save() {
  busy.value = true;
  try {
    loaded.value = await api(`/assets/${props.device.id}/integration`, "PUT", {
      ...form,
    });
    Object.assign(form, loaded.value.config);
    editing.value = false;
    toast("设备协议映射已保存，等待新的现场上报");
  } catch (e) {
    reportFailure(e);
  } finally {
    busy.value = false;
  }
}
onMounted(load);
</script>
<template>
  <section class="integration-panel" v-if="loaded && options.length">
    <div class="section-title">
      <h3>现场设备接入</h3>
      <div class="inline-controls">
        <button class="outline" @click="load" :disabled="busy || editing">
          刷新接收记录
        </button>
        <button
          v-if="role === 'ADMIN'"
          class="outline"
          @click="editing = !editing"
        >
          {{ editing ? "收起配置" : "配置协议映射" }}
        </button>
      </div>
    </div>
    <p v-if="loaded.configured">
      {{ names[loaded.config.adapterType] }} · {{ loaded.config.externalId }} ·
      映射版本 {{ loaded.config.revision }}
    </p>
    <p v-else class="muted">
      绑定现场设备编号和指标后，可接收原项目的泵房、闸门或局域网采集报文。
    </p>
    <form v-if="editing && role === 'ADMIN'" v-validate @submit.prevent="save">
      <div class="form-grid">
        <label
          >设备协议<SelectMenu
            v-model="form.adapterType"
            :options="options"
            @update:model-value="defaults"
        /></label>
        <label
          >外部设备编号<input
            v-model="form.externalId"
            required
            maxlength="100"
        /></label>
        <template v-if="form.adapterType !== 'LAN_DTU'">
          <label
            >设备上行主题<input
              v-model="form.upstreamTopic"
              required
              maxlength="240"
          /></label>
          <label
            >控制下行主题（可留空）<input
              v-model="form.downstreamTopic"
              maxlength="240"
          /></label>
        </template>
      </div>
      <p class="muted">
        字段和单位以现场协议为准。闸门模板只映射已确认的实际开度；启用控制还需真实的远程模式及故障反馈。局域网字段例如
        1.temValue，表示节点 1 的温度值。
      </p>
      <div
        v-for="(binding, index) in form.bindings"
        :key="index"
        class="form-grid integration-binding"
      >
        <label
          >平台指标<SelectMenu v-model="binding.metric" :options="metrics"
        /></label>
        <label
          >设备字段<input v-model="binding.field" required maxlength="80"
        /></label>
        <label
          >原始单位<input
            v-model="binding.unit"
            maxlength="20"
            placeholder="状态量留空"
        /></label>
        <button
          type="button"
          class="outline"
          @click="form.bindings.splice(index, 1)"
        >
          移除映射
        </button>
      </div>
      <div class="inline-controls">
        <button
          type="button"
          class="outline"
          @click="add"
          :disabled="form.bindings.length >= device.channels.length"
        >
          添加指标
        </button>
        <button type="button" class="outline" @click="defaults">
          载入协议模板
        </button>
        <button class="primary" :disabled="busy || !form.bindings.length">
          保存接入配置
        </button>
      </div>
      <p class="muted">
        修改映射后，未完成指令会失效；需收到新配置下的反馈后才能再次启动或调整设备。
      </p>
    </form>
    <h4>最近接收记录</h4>
    <div
      v-for="event in loaded.events"
      :key="event.messageId"
      class="alert-item"
    >
      <div>
        <strong
          >{{ event.readingCount }} 项有效读数 ·
          {{ event.missing.length ? "部分指标缺测" : "映射指标齐全" }}</strong
        >
        <small
          >采样 {{ timeText(event.measuredAt) }} · 接收
          {{ timeText(event.receivedAt) }}</small
        >
        <small
          >消息 {{ event.messageId }} · 映射版本
          {{ event.mappingRevision }}</small
        >
        <p v-if="event.missing.length">
          缺测：{{ event.missing.map(metricName).join("、") }}
        </p>
      </div>
    </div>
    <p v-if="!loaded.events.length" class="muted">
      尚未收到协议报文。成功连接网关并不代表设备已上报数据。
    </p>
  </section>
</template>
