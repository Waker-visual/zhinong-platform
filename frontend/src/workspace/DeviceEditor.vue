<script setup>
import { computed, reactive, ref, watch } from "vue";
import { api } from "../api";
const props = defineProps({
  asset: Object,
  farms: Array,
  plots: Array,
  catalog: Object,
  farmId: String,
});
const emit = defineEmits(["close", "saved"]);
const error = ref(""),
  busy = ref(false);
const model = reactive({
  farmId: props.asset?.farmId || props.farmId || props.farms[0]?.id || "",
  name: props.asset?.name || "",
  code: props.asset?.code || "",
  deviceType: props.asset?.deviceType || "SOIL",
  protocol: props.asset?.protocol || "SIMULATED",
  lifecycle: props.asset?.lifecycle || "ACTIVE",
  plotId: props.asset?.plotId || "",
  planX: props.asset?.planX ?? "",
  planY: props.asset?.planY ?? "",
  model: props.asset?.model || "",
  notes: props.asset?.notes || "",
  intervalSeconds: props.asset?.intervalSeconds || 900,
  revision: props.asset?.revision || 0,
  channels: props.asset?.channels.map((c) => ({
    metric: c.metric,
    lowerLimit: c.lowerLimit ?? "",
    upperLimit: c.upperLimit ?? "",
  })) || [{ metric: "SOIL_MOISTURE", lowerLimit: 20, upperLimit: 60 }],
});
const availablePlots = computed(() =>
  props.plots.filter((p) => p.farmId === model.farmId),
);
watch(
  () => model.farmId,
  () => {
    model.plotId = "";
    model.planX = "";
    model.planY = "";
  },
);
const numberOrNull = (v) => (v === "" || v == null ? null : Number(v));
async function save() {
  busy.value = true;
  error.value = "";
  try {
    const body = {
      ...model,
      plotId: model.plotId || null,
      planX: numberOrNull(model.planX),
      planY: numberOrNull(model.planY),
      intervalSeconds: Number(model.intervalSeconds),
      channels: model.channels.map((c) => ({
        metric: c.metric,
        lowerLimit: numberOrNull(c.lowerLimit),
        upperLimit: numberOrNull(c.upperLimit),
      })),
    };
    const result = await api(
      "/assets" + (props.asset ? "/" + props.asset.id : ""),
      props.asset ? "PUT" : "POST",
      body,
    );
    emit("saved", result);
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div
    class="modal-backdrop workspace-overlay"
    @click.self="!busy && emit('close')"
  >
    <section
      class="modal asset-editor"
      role="dialog"
      aria-modal="true"
      :aria-label="asset ? '编辑设备' : '新增设备'"
    >
      <div class="section-title">
        <div>
          <h2>{{ asset ? "编辑设备" : "新增设备" }}</h2>
          <p class="muted">建档、指标与接入配置保存到当前租户。</p>
        </div>
        <button
          aria-label="关闭设备编辑"
          @click="emit('close')"
          :disabled="busy"
        >
          ×
        </button>
      </div>
      <form @submit.prevent="save">
        <div class="form-grid">
          <label
            >设备名称<input
              v-model.trim="model.name"
              maxlength="100"
              required
              placeholder="例如：A 区多指标土壤站" /></label
          ><label
            >设备编码<input
              v-model.trim="model.code"
              maxlength="60"
              pattern="[A-Za-z0-9_-]{2,60}"
              required
              placeholder="例如 SOIL-A01"
          /></label>
          <label
            >设备所属农场<select
              v-model="model.farmId"
              :disabled="!!asset"
              required
            >
              <option v-for="f in farms" :key="f.id" :value="f.id">
                {{ f.name }}
              </option>
            </select></label
          ><label
            >关联地块<select v-model="model.plotId">
              <option value="">公共区域 / 暂不关联</option>
              <option v-for="p in availablePlots" :key="p.id" :value="p.id">
                {{ p.name }}
              </option>
            </select></label
          >
          <label
            >设备类型<select v-model="model.deviceType">
              <option v-for="t in catalog.types" :key="t.code" :value="t.code">
                {{ t.name }}
              </option>
            </select></label
          ><label
            >接入方式<select v-model="model.protocol">
              <option
                v-for="p in catalog.protocols"
                :key="p.code"
                :value="p.code"
              >
                {{ p.name }}
              </option>
            </select></label
          >
          <label
            >使用状态<select v-model="model.lifecycle">
              <option value="ACTIVE">启用</option>
              <option value="MAINTENANCE">维护中</option>
              <option value="DISABLED">停用</option>
            </select></label
          ><label
            >预期上报周期（秒）<input
              type="number"
              min="30"
              max="86400"
              step="1"
              v-model="model.intervalSeconds"
              required
          /></label>
          <label
            >平面 X 坐标<input
              type="number"
              min="0"
              max="1000"
              step="0.01"
              v-model="model.planX"
              placeholder="也可保存后在地图定位" /></label
          ><label
            >平面 Y 坐标<input
              type="number"
              min="0"
              max="700"
              step="0.01"
              v-model="model.planY"
          /></label>
          <label
            >型号 / 规格<input v-model="model.model" maxlength="100" /></label
          ><label
            >安装与维护说明<input v-model="model.notes" maxlength="500"
          /></label>
        </div>
        <section class="channel-settings">
          <div class="section-title">
            <h3>监测指标与告警阈值</h3>
            <button
              type="button"
              class="outline"
              @click="
                model.channels.push({
                  metric: '',
                  lowerLimit: '',
                  upperLimit: '',
                })
              "
              :disabled="model.channels.length >= 12"
            >
              ＋ 添加指标
            </button>
          </div>
          <div v-for="(c, i) in model.channels" :key="i" class="channel-row">
            <label
              >指标 {{ i + 1
              }}<select
                v-model="c.metric"
                required
                :disabled="!!asset && i < asset.channels.length"
              >
                <option value="" disabled>请选择指标</option>
                <option
                  v-for="m in catalog.metrics"
                  :key="m.code"
                  :value="m.code"
                >
                  {{ m.name }} / {{ m.unit }}
                </option>
              </select></label
            ><label
              >告警下限<input
                type="number"
                step="0.001"
                v-model="c.lowerLimit"
                placeholder="不设置" /></label
            ><label
              >告警上限<input
                type="number"
                step="0.001"
                v-model="c.upperLimit"
                placeholder="不设置" /></label
            ><button
              type="button"
              @click="model.channels.splice(i, 1)"
              :disabled="
                model.channels.length <= 1 ||
                (!!asset && i < asset.channels.length)
              "
              aria-label="移除指标"
            >
              ×
            </button>
          </div>
        </section>
        <p class="muted">
          HTTP
          上报设备保存后，可在设备详情中生成独立接入凭据。已有指标保留用于历史查询；停用设备会拒绝后续采集和上报。
        </p>
        <p v-if="error" role="alert" class="error">{{ error }}</p>
        <div class="modal-actions">
          <button
            type="button"
            class="outline"
            @click="emit('close')"
            :disabled="busy"
          >
            取消</button
          ><button class="primary" :disabled="busy">
            {{ busy ? "正在保存…" : "保存设备" }}
          </button>
        </div>
      </form>
    </section>
  </div>
</template>
