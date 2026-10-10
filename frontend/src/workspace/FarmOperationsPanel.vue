<script setup>
import { computed, ref, watch } from "vue";
import { api } from "../api";
import { deviceSvg, deviceState } from "./deviceSymbols";
import { containsPoint, deviceCoordinates } from "./fieldSpatial";
import {
  num,
  timeText,
  typeNames,
  stateNames,
  preferredMetric,
} from "./presentation";
const props = defineProps({
  farmId: String,
  role: String,
  spatial: Object,
  devices: Array,
  plots: Array,
  tasks: Array,
  selectedParcel: String,
  selectedDevice: String,
  selectedJob: String,
  selectedZone: String,
  geo: Object,
  manualPoints: { type: Array, default: () => [] },
});
const emit = defineEmits([
  "parcel",
  "device",
  "zone",
  "preview",
  "job",
  "refresh",
  "detail",
  "collect",
  "route-edit",
  "route-points",
  "machine",
]);
const mode = ref("field"),
  pending = ref(false),
  error = ref(""),
  preview = ref(null);
const statuses = {
  RUNNING: "模拟执行中",
  PAUSED: "已暂停",
  COMPLETED: "已完成",
  STOPPED: "已停止",
  FAILED: "失败",
};
const machine = ref(""),
  title = ref("田间巡检"),
  taskType = ref("INSPECTION"),
  width = ref(5),
  bearing = ref(90),
  headland = ref(4),
  speed = ref(4),
  simulationRate = ref(10),
  planningMode = ref("AUTO"),
  reverse = ref(false),
  altitude = ref(4),
  recommendBearing = ref(true),
  step = ref(1),
  waterDuration = ref(60);
let requestId = crypto.randomUUID(),
  waterRequestId = crypto.randomUUID(),
  previewVersion = 0;
const parcel = computed(() =>
  props.spatial.parcels.find((p) => p.id === props.selectedParcel),
);
const device = computed(() =>
  props.devices.find((d) => d.id === props.selectedDevice),
);
const deviceMetrics = computed(() => {
  const channels = device.value?.channels || [],
    preferred = preferredMetric(device.value);
  return [...channels]
    .sort(
      (a, b) => Number(b.metric === preferred) - Number(a.metric === preferred),
    )
    .slice(0, 4);
});
const machines = computed(() =>
  props.devices.filter((d) => d.deviceType === "MACHINERY"),
);
const machineDevice = computed(() =>
  machines.value.find((d) => d.id === machine.value),
);
const profile = computed(() => machineDevice.value?.machinery);
const drone = computed(() => profile.value?.kind === "DRONE");
const machineNames = {
  TRACTOR: "拖拉机",
  SPRAYER: "植保机",
  HARVESTER: "收割机",
  DRONE: "无人飞机",
};
const taskNames = {
  INSPECTION: "巡检",
  SOWING: "播种",
  FERTILIZING: "施肥",
  HARVEST: "收获",
  PROTECTION: "植保",
};
const taskOptions = computed(
  () => profile.value?.taskTypes || Object.keys(taskNames),
);
const dispatchBlock = computed(() => {
  if (!admin.value) return "仅农场管理员可以下发农机任务";
  if (!parcel.value || !machineDevice.value) return "先选择田块和农机";
  const d = machineDevice.value;
  if (d.protocol !== "SIMULATED") return "尚未接入此设备的实体调度接口";
  if (d.lifecycle !== "ACTIVE") return "设备已停用或处于维护中";
  if (d.freshness !== "FRESH") return "反馈过期，请先打开设备详情并采集";
  if (d.alertCount > 0) return "设备存在告警，请先处理";
  if (
    props.spatial.jobs.some(
      (j) => j.deviceId === d.id && ["RUNNING", "PAUSED"].includes(j.status),
    )
  )
    return "设备已有未结束的任务";
  if (!preview.value) return "请先预览路线；修改参数后需重新预览";
  return "";
});
watch(
  machine,
  () => {
    const p = profile.value;
    if (p) {
      width.value = p.widthMeters;
      speed.value = p.speedKmh;
      headland.value = p.headlandMeters;
      altitude.value = p.altitudeMeters || 4;
      taskType.value = p.taskTypes[0];
      title.value = taskNames[p.taskTypes[0]] + "作业";
    }
    step.value = 1;
    emit("machine", machine.value);
  },
  { flush: "sync" },
);
watch([mode, planningMode, step], () =>
  emit(
    "route-edit",
    mode.value === "work" &&
      planningMode.value === "MANUAL" &&
      step.value === 2,
  ),
);
watch(mode, (value) => {
  if (value === "work") emit("machine", machine.value);
});
watch(
  () => props.selectedParcel,
  () => {
    emit("route-points", []);
    step.value = 1;
  },
);
const zone = computed(() =>
  props.spatial.zones.find((z) => z.parcelId === props.selectedParcel),
);
const pump = computed(() =>
  props.devices.find((d) => d.id === zone.value?.pumpId),
);
const linkedDevices = computed(() =>
  props.devices.filter((d) =>
    parcel.value
      ? containsPoint(parcel.value.boundary, deviceCoordinates(d, props.geo)) ||
        props.spatial.zones.some(
          (z) => z.parcelId === parcel.value.id && z.pumpId === d.id,
        )
      : true,
  ),
);
const fieldJobs = computed(() =>
  props.spatial.jobs.filter(
    (j) => !parcel.value || j.parcelId === parcel.value.id,
  ),
);
const visibleJobs = computed(() =>
  (mode.value === "water"
    ? props.spatial.jobs.filter((j) => j.kind === "IRRIGATION")
    : mode.value === "work"
      ? props.spatial.jobs.filter((j) => j.kind === "MACHINERY")
      : fieldJobs.value
  ).slice(0, 20),
);
const activeWater = computed(() =>
  props.spatial.jobs.find(
    (j) =>
      j.kind === "IRRIGATION" &&
      j.deviceId === pump.value?.id &&
      j.status === "RUNNING",
  ),
);
const admin = computed(() => props.role === "ADMIN"),
  writer = computed(() => ["ADMIN", "OPERATOR"].includes(props.role));
watch(
  machines,
  (v) => {
    if (!v.some((d) => d.id === machine.value)) machine.value = v[0]?.id || "";
  },
  { immediate: true },
);
watch(
  [
    () => props.selectedParcel,
    machine,
    title,
    taskType,
    width,
    bearing,
    headland,
    speed,
    simulationRate,
    planningMode,
    reverse,
    altitude,
    recommendBearing,
    () => props.manualPoints,
  ],
  () => {
    previewVersion++;
    preview.value = null;
    requestId = crypto.randomUUID();
    emit("preview", null);
  },
  { deep: true },
);
watch([() => props.selectedParcel, waterDuration], () => {
  waterRequestId = crypto.randomUUID();
});
watch([mode, zone], () =>
  emit("zone", mode.value === "water" ? zone.value?.id || "" : ""),
);
watch(
  () => props.selectedJob,
  (id) => {
    const j = props.spatial.jobs.find((j) => j.id === id);
    if (j) {
      mode.value = j.kind === "IRRIGATION" ? "water" : "work";
      if (j.kind === "MACHINERY") {
        machine.value = j.deviceId;
        title.value = j.title;
        taskType.value = j.parameters.taskType;
        width.value = j.parameters.widthMeters;
        bearing.value = j.parameters.bearing;
        headland.value = j.parameters.headlandMeters;
        speed.value = j.parameters.speedKmh;
        simulationRate.value = j.parameters.simulationRate || 10;
        planningMode.value = j.parameters.planningMode || "AUTO";
        reverse.value = !!j.parameters.reverse;
        recommendBearing.value = !!j.parameters.recommendBearing;
        altitude.value = j.parameters.altitudeMeters || 4;
        emit("route-points", j.parameters.manualPoints || []);
        step.value = 3;
      }
    }
  },
);
watch(
  () => props.selectedZone,
  (id) => {
    if (id) mode.value = "water";
  },
);
const path = (suffix) => `/farms/${props.farmId}/field-map${suffix}`;
function workInput() {
  return {
    parcelId: props.selectedParcel,
    deviceId: machine.value,
    title: title.value,
    taskType: taskType.value,
    widthMeters: width.value,
    bearing: bearing.value,
    headlandMeters: headland.value,
    speedKmh: speed.value,
    durationSeconds: 0,
    planningMode: planningMode.value,
    manualPoints: planningMode.value === "MANUAL" ? props.manualPoints : [],
    simulationRate: simulationRate.value,
    reverse: reverse.value,
    altitudeMeters: drone.value ? altitude.value : null,
    recommendBearing: recommendBearing.value,
    requestId,
  };
}
async function run(action) {
  pending.value = true;
  error.value = "";
  try {
    await action();
  } catch (e) {
    error.value = e.message;
  } finally {
    pending.value = false;
  }
}
function routePreview() {
  run(async () => {
    const v = previewVersion,
      result = await api(path("/routes/preview"), "POST", workInput());
    if (v === previewVersion) {
      preview.value = result;
      step.value = 3;
      emit("preview", result);
    }
  });
}
function dispatch() {
  run(async () => {
    if (dispatchBlock.value) throw new Error(dispatchBlock.value);
    const job = await api(path("/jobs"), "POST", workInput());
    await emitRefresh();
    emit("job", job.id);
    preview.value = null;
    emit("preview", null);
    requestId = crypto.randomUUID();
  });
}
function startWater() {
  run(async () => {
    const job = await api(path("/irrigation"), "POST", {
      zoneId: zone.value.id,
      durationSeconds: waterDuration.value,
      requestId: waterRequestId,
    });
    await emitRefresh();
    emit("job", job.id);
    waterRequestId = crypto.randomUUID();
  });
}
async function emitRefresh() {
  const data = await api(path(""));
  emit("refresh", data);
}
function act(job, action) {
  run(async () => {
    await api(path(`/jobs/${job.id}/actions`), "POST", { action });
    await emitRefresh();
  });
}
</script>

<template>
  <aside class="field-operations panel" aria-label="田块设备与作业管理">
    <div class="field-operation-tabs" role="tablist" aria-label="地图操作">
      <button
        v-for="tab in [
          ['field', '田块设备'],
          ['work', '农机作业'],
          ['water', '分区灌溉'],
        ]"
        :key="tab[0]"
        role="tab"
        :aria-selected="mode === tab[0]"
        :class="{ selected: mode === tab[0] }"
        @click="mode = tab[0]"
      >
        {{ tab[1] }}
      </button>
    </div>
    <div class="field-operation-content">
      <label class="field-label"
        >当前小田块
        <select
          :value="selectedParcel"
          @change="emit('parcel', $event.target.value)"
          aria-label="当前小田块"
        >
          <option value="">选择地图上的小田块</option>
          <option v-for="p in spatial.parcels" :key="p.id" :value="p.id">
            {{ p.name }} · {{ p.plotName }}
          </option>
        </select>
      </label>
      <div v-if="parcel" class="parcel-summary">
        <strong
          >{{ parcel.name }} <span class="source-tag">估绘边界</span></strong
        >
        <span
          >{{ parcel.crop || "作物未登记" }} · 估绘
          {{ num(parcel.estimatedAreaMu, 2) }} 亩</span
        >
        <small>{{ parcel.plotName }} 的局部区域，经营登记面积单独统计。</small>
      </div>
      <p v-else class="muted">
        先选择田块，查看设备、规划路线或灌溉分区。无小田块时可通过地图编辑新增估绘边界。
      </p>
      <p v-if="error" class="field-operation-error" role="alert">{{ error }}</p>

      <template v-if="mode === 'field'">
        <h4>
          关联设备 <small>{{ linkedDevices.length }}</small>
        </h4>
        <div class="field-device-list">
          <button
            v-for="d in linkedDevices"
            :key="d.id"
            :class="{ selected: d.id === selectedDevice }"
            @click="emit('device', d.id)"
          >
            <i v-html="deviceSvg(d.deviceType)" aria-hidden="true"></i
            ><span
              ><strong>{{ d.name }}</strong
              ><small
                >{{ deviceState(d, spatial.jobs).label }} ·
                {{
                  d.protocol === "SIMULATED" ? "模拟设备" : "接入设备"
                }}</small
              ></span
            >
          </button>
          <p v-if="!linkedDevices.length" class="muted">
            该田块内尚无设备点位。
          </p>
        </div>
        <section v-if="device" class="field-device-detail">
          <div class="section-title">
            <h4>{{ device.name }}</h4>
            <small>{{ typeNames[device.deviceType] }}</small>
          </div>
          <p>{{ deviceState(device, spatial.jobs).label }}</p>
          <div class="field-metrics">
            <div v-for="c in deviceMetrics" :key="c.metric">
              <small>{{ c.name }}</small
              ><strong
                >{{ c.latest ? num(c.latest.value, 2) : "—" }}
                <small>{{ c.unit }}</small></strong
              ><small v-if="c.freshness !== 'FRESH'">{{
                c.latest ? "数据过期" : "未上报"
              }}</small>
            </div>
          </div>
          <p class="muted">
            {{
              ["PUMP", "GATE"].includes(device.deviceType)
                ? "控制设备：详情中显示可用指令与回执。"
                : device.deviceType === "MACHINERY"
                  ? "农机终端：在农机作业页规划并模拟执行。"
                  : "监测设备：查看采样、历史曲线与告警。"
            }}
          </p>
          <div class="inline-controls">
            <button @click="emit('detail', device.id)">完整详情与历史</button
            ><button
              v-if="writer && device.protocol === 'SIMULATED'"
              :disabled="pending"
              @click="emit('collect', device.id)"
            >
              模拟采集
            </button>
          </div>
        </section>
        <div v-if="parcel" class="field-current-tasks">
          <h4>经营地块任务</h4>
          <p
            v-for="t in tasks
              .filter(
                (t) =>
                  t.plotId === parcel.plotId &&
                  ['PENDING', 'RUNNING'].includes(t.status),
              )
              .slice(0, 3)"
            :key="t.id"
          >
            {{ t.title }} <small>{{ stateNames[t.status] }}</small>
          </p>
        </div>
      </template>

      <form
        v-if="mode === 'work'"
        id="machinery-plan-form"
        @submit.prevent="routePreview"
        class="field-operation-form"
      >
        <nav class="machine-steps" aria-label="农机调度步骤">
          <button
            v-for="(label, i) in ['选农机', '规划路线', '确认下发']"
            :key="label"
            type="button"
            :class="{ selected: step === i + 1 }"
            :disabled="i === 2 && !preview"
            @click="step = i + 1"
          >
            {{ i + 1 }} {{ label }}
          </button>
        </nav>
        <p class="field-simulation-note">
          模拟调度 · 路线与执行结果持久保存，实体设备未接入。
        </p>
        <template v-if="step === 1">
          <label
            >作业农机<select v-model="machine" aria-label="作业农机">
              <option value="">选择农机</option>
              <option v-for="d in machines" :key="d.id" :value="d.id">
                {{ d.model || d.name }} ·
                {{ deviceState(d, spatial.jobs).label }}
              </option>
            </select></label
          >
          <div v-if="profile" class="machine-profile">
            <div class="machine-profile-title">
              <i v-html="deviceSvg(profile.kind)" aria-hidden="true"></i
              ><strong
                >{{ profile.model
                }}<small
                  >{{ profile.brand }} · {{ machineNames[profile.kind] }}</small
                ></strong
              >
            </div>
            <dl>
              <dt>设备编号</dt>
              <dd>{{ profile.serialNumber || "待补充" }}</dd>
              <dt>内部编号</dt>
              <dd>{{ profile.internalId }}</dd>
              <dt>{{ drone ? "动力参数" : "额定马力" }}</dt>
              <dd>
                {{
                  profile.horsepower
                    ? profile.horsepower + " 马力（用户提供）"
                    : "未提供"
                }}
              </dd>
              <dt>当前状态</dt>
              <dd>{{ deviceState(machineDevice, spatial.jobs).label }}</dd>
            </dl>
            <small>{{ profile.specSource }}</small>
          </div>
          <p v-else class="muted">通用农机档案；请核对实际机具能力后配置。</p>
          <button
            type="button"
            class="primary"
            :disabled="!parcel || !machine"
            @click="step = 2"
          >
            下一步：规划路线
          </button>
        </template>
        <template v-if="step === 2">
          <strong
            >{{ profile?.model || machineDevice?.name }} ·
            {{ parcel?.name }}</strong
          >
          <label
            >任务名称<input
              v-model="title"
              maxlength="90"
              required
              aria-label="任务名称"
          /></label>
          <label
            >作业类型<select v-model="taskType" aria-label="作业类型">
              <option v-for="t in taskOptions" :value="t" :key="t">
                {{ taskNames[t] }}
              </option>
            </select></label
          >
          <label
            >路线规划<select v-model="planningMode" aria-label="路线规划">
              <option value="AUTO">自动往复式规划</option>
              <option value="MANUAL">地图手绘路径</option>
            </select></label
          >
          <div v-if="planningMode === 'MANUAL'" class="route-drawing-help">
            <p>在选中田块内点击路径点。服务端检查完整线段与留边距离。</p>
            <strong>{{ manualPoints.length }} 个路径点</strong>
            <div class="inline-controls">
              <button
                type="button"
                :disabled="!manualPoints.length"
                @click="emit('route-points', manualPoints.slice(0, -1))"
              >
                撤销一点</button
              ><button type="button" @click="emit('route-points', [])">
                清空路径
              </button>
            </div>
          </div>
          <div class="field-form-grid">
            <label
              >{{
                drone
                  ? "喷幅 / 航线间距"
                  : profile?.kind === "HARVESTER"
                    ? "割幅"
                    : profile?.kind === "SPRAYER"
                      ? "喷洒幅宽"
                      : "配套机具幅宽"
              }}
              / m<input
                v-model.number="width"
                type="number"
                min="2"
                max="20"
                step="0.5"
                required
                aria-label="作业幅宽"
            /></label>
            <label
              >{{ drone ? "边界退让" : "地头留边" }} / m<input
                v-model.number="headland"
                type="number"
                min="3"
                max="20"
                step="0.5"
                required
                aria-label="留边距离"
            /></label>
            <label
              >规划速度 / km/h<input
                v-model.number="speed"
                type="number"
                min="1"
                :max="drone ? 40 : 12"
                step="0.5"
                required
                aria-label="规划速度"
            /></label>
            <label v-if="drone"
              >相对作物高度 / m<input
                v-model.number="altitude"
                type="number"
                min="2"
                max="30"
                step="0.5"
                required
                aria-label="飞行高度"
            /></label>
          </div>
          <template v-if="planningMode === 'AUTO'"
            ><label class="field-checkbox"
              ><input v-model="recommendBearing" type="checkbox" /><span
                >沿田块长边推荐方向</span
              ></label
            ><label v-if="!recommendBearing"
              >行进角度 / °<input
                v-model.number="bearing"
                type="number"
                min="0"
                max="179"
                step="0.1"
                required
                aria-label="行进角度" /></label
          ></template>
          <label class="field-checkbox"
            ><input v-model="reverse" type="checkbox" /><span
              >对调起终点和行进方向</span
            ></label
          >
          <label
            >演示执行倍速<select
              v-model.number="simulationRate"
              aria-label="演示执行倍速"
            >
              <option
                v-for="rate in [1, 10, 30, 60, 120]"
                :value="rate"
                :key="rate"
              >
                {{ rate }} 倍
              </option>
            </select></label
          >
          <small
            >作业时间由路线长度和规划速度计算。倍速仅加快模拟演示，不改变规划速度；预设参数需按机具和现场核定。</small
          >
        </template>
        <div v-if="step === 3 && preview" class="route-summary">
          <strong
            >{{ profile?.model || machineDevice?.name }} →
            {{ parcel?.name }}</strong
          >
          <span
            >{{ num(preview.lengthMeters, 0) }} m ·
            {{ preview.passes ? preview.passes + " 趟" : "手绘路径" }} · 约
            {{ num(preview.estimatedMinutes, 1) }} 分钟</span
          >
          <span
            >估算作业范围：{{
              preview.workAreaMu == null
                ? "手绘覆盖未核定"
                : num(preview.workAreaMu, 2) + " 亩"
            }}<br />{{ preview.simulationRate }} 倍演示，预计
            {{ num(preview.simulationSeconds, 0) }} 秒</span
          >
          <small>{{ preview.note }}</small>
          <button type="button" @click="step = 2">返回调整路线</button>
        </div>
      </form>

      <section v-if="mode === 'water'" class="field-operation-form">
        <p class="field-simulation-note">
          蓝线为模拟管网，蓝面为关联田块覆盖示意。
        </p>
        <template v-if="zone && pump">
          <strong>{{ zone.name }}</strong
          ><button class="field-pump-link" @click="emit('device', pump.id)">
            <i v-html="deviceSvg('PUMP')" aria-hidden="true"></i
            >{{ pump.name }} · {{ deviceState(pump, spatial.jobs).label }}
          </button>
          <label
            >灌溉时长 / 秒<input
              v-model.number="waterDuration"
              type="number"
              min="10"
              max="300"
              aria-label="灌溉时长"
          /></label>
          <small
            >当前支持按时长模拟启停；目标水量控制、实测水表与实体分区阀门尚未接入。</small
          >
          <button
            v-if="!activeWater"
            class="primary"
            :disabled="
              pending ||
              !writer ||
              waterDuration < 10 ||
              waterDuration > 300 ||
              !Number.isInteger(waterDuration)
            "
            @click="startWater"
          >
            启动模拟灌溉
          </button>
          <button
            v-else
            :disabled="pending || !writer"
            @click="act(activeWater, 'STOP')"
          >
            停止模拟灌溉
          </button>
        </template>
        <p v-else class="muted">当前田块尚未配置灌溉分区及关联水泵。</p>
        <div class="field-water-total">
          <small>本农场地图灌溉记录累计</small
          ><strong
            >{{ num(spatial.waterTotals.estimatedM3, 3) }} m³
            <small>估算用水</small></strong
          ><span
            >实测用水：{{
              spatial.waterTotals.measuredM3 == null
                ? "未接入"
                : num(spatial.waterTotals.measuredM3, 3) + " m³"
            }}</span
          >
        </div>
      </section>

      <section class="field-job-history">
        <h4>
          {{
            mode === "water"
              ? "灌溉与用水记录"
              : mode === "work"
                ? "农机任务记录"
                : "当前田块作业"
          }}
        </h4>
        <p v-if="!visibleJobs.length" class="muted">
          暂无地图任务，下发后将在此保留进度和结果。
        </p>
        <article
          v-for="j in visibleJobs"
          :key="j.id"
          :class="{ selected: selectedJob === j.id }"
        >
          <button class="field-job-link" @click="emit('job', j.id)">
            <strong>{{ j.title }}</strong
            ><span>{{ statuses[j.status] }} · {{ num(j.progress, 0) }}%</span>
          </button>
          <details class="machine-receipt" v-if="j.kind === 'MACHINERY'">
            <summary>调度回执与执行记录</summary>
            <small
              >任务：{{ j.id }}<br />回执：{{ j.receiptId }}<br />{{
                j.plan?.machine?.model || "通用农机"
              }}
              · 模拟执行</small
            >
            <ol>
              <li v-for="e in j.events" :key="e.occurredAt + e.action">
                {{ timeText(e.occurredAt) }} · {{ e.note }}
              </li>
            </ol>
          </details>
          <progress
            :value="j.progress"
            max="100"
            :aria-label="j.title + '模拟进度'"
          ></progress>
          <small
            >{{ spatial.parcels.find((p) => p.id === j.parcelId)?.name }} ·
            {{ devices.find((d) => d.id === j.deviceId)?.name }}</small
          >
          <small
            >{{ timeText(j.startedAt) }} →
            {{ j.finishedAt ? timeText(j.finishedAt) : "尚未结束" }}</small
          >
          <div v-if="j.kind === 'IRRIGATION'" class="field-water-reading">
            <b>本次估算 {{ num(j.estimatedM3, 3) }} m³</b
            ><small
              >实测：{{
                j.measuredM3 == null ? "未接入" : num(j.measuredM3, 3) + " m³"
              }}
              · 目标水量：未设置</small
            ><small
              >基于启动时模拟流量 {{ num(j.flowM3h, 1) }} m³/h ×
              模拟运行时长。</small
            >
          </div>
          <small>{{ j.resultNote }}</small>
          <div
            v-if="writer && ['RUNNING', 'PAUSED'].includes(j.status)"
            class="inline-controls"
          >
            <button
              v-if="j.kind === 'MACHINERY'"
              :disabled="pending"
              @click="act(j, j.status === 'PAUSED' ? 'RESUME' : 'PAUSE')"
            >
              {{ j.status === "PAUSED" ? "继续" : "暂停" }}</button
            ><button :disabled="pending" @click="act(j, 'STOP')">停止</button>
          </div>
        </article>
      </section>
    </div>
    <footer v-if="mode === 'work'" class="machine-dispatch-footer">
      <small role="status">{{
        pending
          ? "正在处理…"
          : dispatchBlock || "路线已预览，可以确认下发模拟任务"
      }}</small>
      <div>
        <button
          type="submit"
          form="machinery-plan-form"
          :disabled="
            pending ||
            !parcel ||
            !machine ||
            step === 1 ||
            (planningMode === 'MANUAL' && manualPoints.length < 2)
          "
        >
          预览规划路线</button
        ><button
          type="button"
          class="primary"
          :disabled="pending || !!dispatchBlock"
          @click="dispatch"
        >
          确认下发
        </button>
      </div>
    </footer>
  </aside>
</template>

<style scoped>
.machine-steps {
  display: flex;
  gap: 4px;
}
.machine-steps button {
  flex: 1;
  padding: 8px 4px;
  font-size: 12px;
}
.machine-steps .selected {
  background: var(--brand);
  color: var(--on-brand);
}
.machine-profile {
  display: grid;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--subtle-bg);
}
.machine-profile-title {
  display: flex;
  align-items: center;
  gap: 12px;
}
.machine-profile-title i {
  width: 40px;
  height: 40px;
  flex-basis: 40px;
}
.machine-profile-title strong {
  display: grid;
  gap: 6px;
}
.machine-profile dl {
  display: grid;
  grid-template-columns: 70px 1fr;
  gap: 8px;
  margin: 0;
  font-size: 12px;
}
.machine-profile dt {
  color: var(--text-2);
}
.machine-profile dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.machine-dispatch-footer {
  padding: 12px 16px;
  background: var(--surface);
  border-top: 1px solid var(--border);
  position: sticky;
  bottom: 0;
  z-index: 5;
  display: grid;
  gap: 8px;
}
.machine-dispatch-footer > div {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}
.machine-dispatch-footer button {
  width: 100%;
  white-space: nowrap;
  font-size: 12px;
  padding: 10px 6px;
}
.machine-dispatch-footer small {
  color: var(--text-2);
  font-size: 11px;
  line-height: 1.6;
}
.machine-receipt {
  font-size: 11px;
  overflow-wrap: anywhere;
}
.machine-receipt ol {
  padding-left: 18px;
}
.machine-receipt li {
  margin: 7px 0;
  line-height: 1.5;
}
.route-drawing-help {
  padding: 10px;
  background: var(--subtle-bg);
  border: 1px dashed var(--brand);
  border-radius: 8px;
  font-size: 12px;
}
.route-drawing-help p {
  margin-top: 0;
  line-height: 1.6;
}
.field-operation-form .field-checkbox {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}
.field-checkbox input[type="checkbox"] {
  flex: 0 0 16px;
  width: 16px;
  min-width: 16px;
  min-height: 16px;
  height: 16px;
  margin: 0;
  padding: 0;
}
.field-checkbox span {
  min-width: 0;
  line-height: 1.5;
}
.field-operations {
  display: flex;
  flex-direction: column;
  max-height: calc(100dvh - 120px);
  padding: 0;
  min-width: 0;
  align-self: start;
  overflow: hidden;
}
.field-operation-tabs {
  flex-shrink: 0;
  display: flex;
  border-bottom: 1px solid var(--border);
  background: var(--subtle-bg);
}
.field-operation-tabs button {
  flex: 1;
  border: 0;
  border-radius: 0;
  padding: 15px 6px;
  background: transparent;
}
.field-operation-tabs .selected {
  color: var(--brand);
  box-shadow: inset 0 -3px var(--brand);
  font-weight: 700;
}
.field-operation-content {
  min-height: 0;
  flex: 1;
  padding: 18px;
  max-height: 790px;
  overflow: auto;
}
.field-operation-content h4 {
  margin: 18px 0 10px;
}
.field-label,
.field-operation-form label {
  display: grid;
  gap: 7px;
  font-size: 12px;
  font-weight: 600;
}
select,
input {
  width: 100%;
  min-width: 0;
  padding: 9px;
  border: 1px solid var(--border);
  background: var(--surface);
  color: var(--text);
  border-radius: 7px;
}
.parcel-summary,
.field-water-total {
  display: grid;
  gap: 7px;
  margin: 14px 0;
  padding: 12px;
  background: var(--subtle-bg);
  border: 1px solid var(--border);
  border-radius: 9px;
}
.parcel-summary strong {
  display: flex;
  justify-content: space-between;
}
.parcel-summary span {
  font-size: 13px;
}
small,
.muted {
  color: var(--text-2);
  line-height: 1.6;
}
.field-operation-form {
  display: grid;
  gap: 12px;
}
.field-form-grid,
.field-metrics {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.field-metrics > div {
  display: grid;
  gap: 3px;
  padding: 9px;
  background: var(--subtle-bg);
  border-radius: 6px;
}
.field-device-list {
  display: grid;
  gap: 5px;
  max-height: 250px;
  overflow: auto;
}
.field-device-list button,
.field-pump-link {
  display: flex;
  align-items: center;
  gap: 9px;
  text-align: left;
  padding: 10px;
}
.field-device-list button.selected {
  border-color: var(--brand);
}
.field-device-list span {
  display: grid;
  gap: 3px;
}
.field-device-list strong {
  font-size: 12px;
}
i {
  display: inline-flex;
  width: 24px;
  height: 24px;
  flex: 0 0 24px;
  color: var(--brand);
}
.field-simulation-note {
  border-left: 3px solid var(--module-irrigation);
  padding-left: 10px;
  font-size: 12px;
  color: var(--text-2);
  line-height: 1.7;
}
.route-summary {
  display: grid;
  gap: 10px;
  padding: 12px;
  border: 1px solid var(--brand);
  border-radius: 8px;
}
.field-operation-error {
  padding: 12px;
  background: var(--subtle-bg);
  border: 1px solid var(--danger);
  color: var(--danger);
  border-radius: 8px;
}
.field-job-history article {
  display: grid;
  gap: 8px;
  border: 1px solid var(--border);
  padding: 12px;
  margin-top: 10px;
  border-radius: 9px;
}
.field-job-history article.selected {
  border-color: var(--brand);
}
.field-job-link {
  display: grid;
  gap: 7px;
  text-align: left;
  padding: 0;
  border: 0;
  background: transparent;
}
.field-job-link span {
  font-size: 12px;
  color: var(--brand);
}
progress {
  width: 100%;
  height: 6px;
  accent-color: var(--brand);
}
.field-water-reading {
  display: grid;
  gap: 4px;
  color: var(--module-irrigation);
}
.field-current-tasks p {
  font-size: 12px;
  line-height: 1.6;
}
@media (max-width: 900px) {
  .field-operation-content {
    max-height: none;
  }
}
</style>
