<script setup>
import { computed, reactive, ref, watch } from "vue";
import { api } from "../api";
import SelectMenu from "../ui/SelectMenu.vue";
const props = defineProps({
  asset: Object,
  farms: Array,
  plots: Array,
  catalog: Object,
  farmId: String,
  defaultType: String,
});
const emit = defineEmits(["close", "saved"]);
const error = ref(""),
  busy = ref(false);
const model = reactive({
  farmId: props.asset?.farmId || props.farmId || props.farms[0]?.id || "",
  name: props.asset?.name || "",
  code: props.asset?.code || "",
  deviceType: props.asset?.deviceType || props.defaultType || "SOIL",
  protocol: props.asset?.protocol || "SIMULATED",
  lifecycle: props.asset?.lifecycle || "ACTIVE",
  plotId: props.asset?.plotId || "",
  planX: props.asset?.planX ?? "",
  planY: props.asset?.planY ?? "",
  locationMode: props.asset?.locationMode || "LOCAL_PLAN",
  latitude: props.asset?.latitude ?? "",
  longitude: props.asset?.longitude ?? "",
  controlEnabled: props.asset?.controlEnabled || false,
  model: props.asset?.model || "",
  notes: props.asset?.notes || "",
  intervalSeconds: props.asset?.intervalSeconds || 900,
  revision: props.asset?.revision || 0,
  camera: {
    mode: props.asset?.camera?.mode || (props.asset ? "NONE" : "DEMO_IMAGE"),
    demoScene: props.asset?.camera?.demoScene || "qinghe",
    sourceUrl: props.asset?.camera?.sourceUrl || "",
    viewLabel: props.asset?.camera?.viewLabel || "",
  },
  channels: props.asset?.channels.map((c) => ({
    metric: c.metric,
    lowerLimit: c.lowerLimit ?? "",
    upperLimit: c.upperLimit ?? "",
  })) || [{ metric: "SOIL_MOISTURE", lowerLimit: 20, upperLimit: 60 }],
});
const availablePlots = computed(() =>
  props.plots.filter((p) => p.farmId === model.farmId),
);
const farmOptions = computed(() =>
  props.farms.map((farm) => ({ value: farm.id, label: farm.name })),
);
const plotOptions = computed(() => [
  { value: "", label: "公共区域 / 暂不关联" },
  ...availablePlots.value.map((plot) => ({ value: plot.id, label: plot.name })),
]);
const deviceTypeOptions = computed(() =>
  props.catalog.types.map((type) => ({ value: type.code, label: type.name })),
);
const protocolOptions = computed(() =>
  props.catalog.protocols.map((protocol) => ({
    value: protocol.code,
    label: protocol.name,
  })),
);
const lifecycleOptions = [
  { value: "ACTIVE", label: "启用" },
  { value: "MAINTENANCE", label: "维护中" },
  { value: "DISABLED", label: "停用" },
];
const locationOptions = [
  { value: "LOCAL_PLAN", label: "平面示意点位" },
  { value: "WGS84", label: "WGS84 安装位置" },
];
const canControl = computed(
  () =>
    ["GATE", "PUMP"].includes(model.deviceType) && model.protocol !== "MANUAL",
);
watch(canControl, (allowed) => {
  if (!allowed) model.controlEnabled = false;
});
function applyPreset() {
  const metrics =
    props.catalog.presets?.find((p) => p.deviceType === model.deviceType)
      ?.metrics || [];
  if (!props.asset) model.channels = [];
  for (const metric of metrics) {
    if (!model.channels.some((c) => c.metric === metric))
      model.channels.push({ metric, lowerLimit: "", upperLimit: "" });
  }
}
watch(
  () => model.deviceType,
  (type) => {
    if (type === "CAMERA") applyPreset();
  },
  { immediate: true },
);
const cameraModes = [
  { value: "NONE", label: "暂不配置画面" },
  { value: "DEMO_IMAGE", label: "预置图片" },
  { value: "IMAGE", label: "HTTPS 图片源" },
  { value: "VIDEO", label: "HTTPS 视频源（浏览器可播放）" },
];
const metricOptions = computed(() => [
  { value: "", label: "请选择指标", disabled: true },
  ...props.catalog.metrics.map((metric) => ({
    value: metric.code,
    label: `${metric.name} / ${metric.unit}`,
  })),
]);
watch(
  () => model.farmId,
  () => {
    model.plotId = "";
    model.planX = "";
    model.planY = "";
    model.latitude = "";
    model.longitude = "";
  },
);
const numberOrNull = (v) => (v === "" || v == null ? null : Number(v));
async function save() {
  busy.value = true;
  error.value = "";
  try {
    const body = {
      ...model,
      camera: model.deviceType === "CAMERA" ? model.camera : null,
      plotId: model.plotId || null,
      planX:
        model.locationMode === "LOCAL_PLAN" ? numberOrNull(model.planX) : null,
      planY:
        model.locationMode === "LOCAL_PLAN" ? numberOrNull(model.planY) : null,
      latitude:
        model.locationMode === "WGS84" ? numberOrNull(model.latitude) : null,
      longitude:
        model.locationMode === "WGS84" ? numberOrNull(model.longitude) : null,
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
          class="close-button"
          aria-label="关闭设备编辑"
          @click="emit('close')"
          :disabled="busy"
        >
          ×
        </button>
      </div>
      <form v-validate @submit.prevent="save">
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
            >设备所属农场<SelectMenu
              v-model="model.farmId"
              :options="farmOptions"
              aria-label="设备所属农场"
              required
              :disabled="!!asset" /></label
          ><label
            >关联地块<SelectMenu
              v-model="model.plotId"
              :options="plotOptions"
              aria-label="关联地块"
          /></label>
          <label
            >设备类型<SelectMenu
              v-model="model.deviceType"
              :options="deviceTypeOptions"
              aria-label="设备类型" /></label
          ><label
            >接入方式<SelectMenu
              v-model="model.protocol"
              :options="protocolOptions"
              aria-label="接入方式"
          /></label>
          <label
            >使用状态<SelectMenu
              v-model="model.lifecycle"
              :options="lifecycleOptions"
              aria-label="使用状态" /></label
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
            >点位坐标系<SelectMenu
              v-model="model.locationMode"
              :options="locationOptions"
              aria-label="点位坐标系"
          /></label>
          <label v-if="canControl"
            ><span>控制授权</span
            ><span
              ><input
                type="checkbox"
                v-model="model.controlEnabled"
              />允许本租户管理员和操作员提交控制指令</span
            ></label
          >
          <template v-if="model.locationMode === 'WGS84'">
            <label
              >安装纬度<input
                v-model.number="model.latitude"
                type="number"
                min="-80"
                max="80"
                step="any"
                required
            /></label>
            <label
              >安装经度<input
                v-model.number="model.longitude"
                type="number"
                min="-180"
                max="180"
                step="any"
                required
            /></label>
          </template>
          <label v-if="model.locationMode === 'LOCAL_PLAN'"
            >平面 X 坐标<input
              type="number"
              min="0"
              max="1000"
              step="0.01"
              v-model="model.planX"
              placeholder="也可保存后在地图定位" /></label
          ><label v-if="model.locationMode === 'LOCAL_PLAN'"
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
        <p class="muted">
          WGS84 安装点位独立保存，调整农场中心不会移动它。高德 /
          百度坐标需先转换；未定位的设备仍保留数据和历史。
        </p>
        <section v-if="model.deviceType === 'CAMERA'" class="channel-settings">
          <h3>田间画面配置</h3>
          <div class="form-grid">
            <label
              >机位说明<input
                v-model.trim="model.camera.viewLabel"
                maxlength="100"
                placeholder="例如：东侧稻田固定机位"
            /></label>
            <label
              >画面来源<SelectMenu
                v-model="model.camera.mode"
                :options="cameraModes"
                aria-label="画面来源"
            /></label>
            <label v-if="model.camera.mode === 'DEMO_IMAGE'"
              >预置画面<SelectMenu
                v-model="model.camera.demoScene"
                :options="catalog.cameraScenes || []"
                aria-label="预置画面"
            /></label>
            <label v-if="['IMAGE', 'VIDEO'].includes(model.camera.mode)"
              >画面地址<input
                v-model.trim="model.camera.sourceUrl"
                type="url"
                maxlength="2048"
                required
                placeholder="https://…"
                aria-label="画面地址"
            /></label>
          </div>
          <p class="muted">
            支持预置图片、HTTPS 图片及浏览器可播放的 MP4 / WebM 视频。
            设备的启用、维护、停用状态同步作用于画面展示。
          </p>
          <p
            v-if="['IMAGE', 'VIDEO'].includes(model.camera.mode)"
            class="muted"
          >
            请使用 HTTPS 地址，不填写摄像头账号口令。RTSP / GB28181
            设备需经媒体网关转换为浏览器可播放地址。
            设备上报与画面来源分别配置。
          </p>
        </section>
        <section class="channel-settings">
          <div class="section-title">
            <h3>监测指标与告警阈值</h3>
            <button type="button" class="outline" @click="applyPreset">
              应用设备指标模板
            </button>
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
              :disabled="model.channels.length >= 32"
            >
              ＋ 添加指标
            </button>
          </div>
          <div v-for="(c, i) in model.channels" :key="i" class="channel-row">
            <label
              >指标 {{ i + 1
              }}<SelectMenu
                v-model="c.metric"
                :options="metricOptions"
                aria-label="监测指标"
                required
                :disabled="!!asset && i < asset.channels.length" /></label
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
