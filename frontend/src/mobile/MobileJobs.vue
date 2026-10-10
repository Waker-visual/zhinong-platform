<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from "vue";
import { number, requestId, statuses, taskNames, time } from "./client.mjs";
import RoutePreview from "./RoutePreview.vue";
const props = defineProps({
  farmId: String,
  client: Object,
  devices: Array,
  role: String,
  revision: Number,
  locked: Boolean,
});
const emit = defineEmits(["confirm", "device"]);
const spatial = ref(null),
  error = ref(""),
  busy = ref(false),
  preview = ref(null),
  selectedJob = ref("");
const mode = ref("records"),
  zoneId = ref(""),
  duration = ref(60);
const input = reactive({
  parcelId: "",
  deviceId: "",
  title: "手机端农机作业",
  taskType: "INSPECTION",
  widthMeters: 5,
  headlandMeters: 4,
  speedKmh: 4,
  bearing: 90,
  recommendBearing: true,
  altitudeMeters: 4,
  reverse: false,
  simulationRate: 10,
});
const machines = computed(() =>
  props.devices.filter((d) => d.deviceType === "MACHINERY"),
);
const machine = computed(() =>
  machines.value.find((d) => d.id === input.deviceId),
);
const parcel = computed(() =>
  spatial.value?.parcels.find((p) => p.id === input.parcelId),
);
const zone = computed(() =>
  spatial.value?.zones.find((z) => z.id === zoneId.value),
);
const job = computed(() =>
  spatial.value?.jobs.find((j) => j.id === selectedJob.value),
);
const writer = computed(() => ["ADMIN", "OPERATOR"].includes(props.role));
let generation = 0,
  previewVersion = 0;
const path = (suffix) => `/farms/${props.farmId}/field-map${suffix}`;
async function load() {
  const v = ++generation;
  try {
    const result = await props.client.request(path(""));
    if (v !== generation) return;
    spatial.value = result;
    error.value = "";
    if (!input.parcelId) input.parcelId = result.parcels[0]?.id || "";
    if (!input.deviceId) input.deviceId = machines.value[0]?.id || "";
    if (!zoneId.value) zoneId.value = result.zones[0]?.id || "";
  } catch (e) {
    if (v === generation && !e.stale) error.value = e.message;
  }
}
watch(() => props.revision, load, { immediate: true });
watch(
  () => input.deviceId,
  () => {
    const profile = machine.value?.machinery;
    if (profile) {
      for (const key of ["widthMeters", "headlandMeters", "speedKmh"])
        input[key] = profile[key];
      input.altitudeMeters = profile.altitudeMeters || 4;
      input.taskType = profile.taskTypes[0];
    }
  },
);
watch(
  input,
  () => {
    previewVersion++;
    preview.value = null;
  },
  { flush: "sync" },
);
onBeforeUnmount(() => {
  generation++;
  previewVersion++;
});
async function plan() {
  const version = ++previewVersion;
  busy.value = true;
  error.value = "";
  const body = {
    ...input,
    durationSeconds: 0,
    planningMode: "AUTO",
    manualPoints: [],
    requestId: requestId(),
  };
  if (machine.value?.machinery?.kind !== "DRONE") body.altitudeMeters = null;
  try {
    const data = await props.client.request(
      path("/routes/preview"),
      "POST",
      body,
    );
    if (version === previewVersion) preview.value = { data, body };
  } catch (e) {
    if (version === previewVersion && !e.stale) error.value = e.message;
  } finally {
    busy.value = false;
  }
}
function dispatch() {
  if (!preview.value || props.role !== "ADMIN") return;
  emit("confirm", {
    title: "下发农机任务",
    description: `${parcel.value.name} · ${machine.value.name} · ${input.title}`,
    source: "模拟作业：路线按估绘边界计算，未接入厂家导航与实机轨迹。",
    path: path("/jobs"),
    body: { ...preview.value.body },
    lookup: path(""),
    collection: "jobs",
    kind: "job",
  });
}
function water() {
  const pump = props.devices.find((d) => d.id === zone.value?.pumpId);
  emit("confirm", {
    title: "启动分区灌溉",
    description: `${zone.value.name} · ${pump?.name || "未关联水泵"} · ${duration.value} 秒`,
    source: "模拟灌溉：用水量由模拟流量估算，不是实测水表读数。",
    path: path("/irrigation"),
    body: {
      zoneId: zoneId.value,
      durationSeconds: Number(duration.value),
      requestId: requestId(),
    },
    lookup: path(""),
    collection: "jobs",
    kind: "job",
  });
}
function act(j, action) {
  const label = { PAUSE: "暂停", RESUME: "继续", STOP: "停止" }[action];
  emit("confirm", {
    title: label + "任务",
    description: j.title,
    source: "此操作会同步至电脑端任务记录。",
    path: path(`/jobs/${j.id}/actions`),
    body: { action },
    lookup: path(""),
    collection: "jobs",
    jobId: j.id,
    desiredStatus: { PAUSE: "PAUSED", RESUME: "RUNNING", STOP: "STOPPED" }[
      action
    ],
    kind: "job-action",
  });
}
</script>
<template>
  <section aria-label="农场作业">
    <header class="section-heading">
      <div>
        <p class="eyebrow">FIELD OPERATIONS</p>
        <h2>田间作业</h2>
      </div>
      <span class="tag">模拟调度</span>
    </header>
    <div class="segmented" aria-label="作业类型">
      <button
        v-for="t in [
          ['records', '任务记录'],
          ['machine', '农机规划'],
          ['water', '分区灌溉'],
        ]"
        :key="t[0]"
        :class="{ active: mode === t[0] }"
        @click="mode = t[0]"
      >
        {{ t[1] }}
      </button>
    </div>
    <p v-if="error" class="error" role="alert">
      {{ error }} <button @click="load">刷新记录</button>
    </p>
    <p v-if="!spatial" class="muted">正在读取田块与任务…</p>
    <template v-else>
      <template v-if="mode === 'machine'">
        <form class="card" @submit.prevent="plan">
          <h3>选择田块与农机</h3>
          <label
            >小田块<select
              v-model="input.parcelId"
              required
              :disabled="locked"
              aria-label="小田块"
            >
              <option v-for="p in spatial.parcels" :key="p.id" :value="p.id">
                {{ p.name }} · {{ p.plotName }}
              </option>
            </select></label
          ><label
            >农机型号<select
              v-model="input.deviceId"
              aria-label="农机型号"
              required
              :disabled="locked"
            >
              <option v-for="m in machines" :key="m.id" :value="m.id">
                {{ m.name }} · {{ m.machinery?.model || m.model }}
              </option>
            </select></label
          >
          <p class="muted" v-if="machine">
            {{ machine.machinery?.brand }} ·
            {{
              machine.machinery?.horsepower
                ? machine.machinery.horsepower + " 马力"
                : "马力未登记 / 不适用"
            }}
            · {{ machine.code }}
          </p>
          <label
            >任务名称<input
              v-model="input.title"
              maxlength="90"
              required
              :disabled="locked" /></label
          ><label
            >作业类型<select
              v-model="input.taskType"
              :disabled="locked"
              aria-label="作业类型"
            >
              <option
                v-for="t in machine?.machinery?.taskTypes || ['INSPECTION']"
                :key="t"
                :value="t"
              >
                {{ taskNames[t] }}
              </option>
            </select></label
          >
          <div class="form-grid">
            <label
              >作业幅宽 / m<input
                type="number"
                v-model.number="input.widthMeters"
                min="2"
                max="20"
                step="0.1"
                required
                :disabled="locked" /></label
            ><label
              >边界退让 / m<input
                type="number"
                v-model.number="input.headlandMeters"
                min="3"
                max="20"
                step="0.1"
                required
                :disabled="locked" /></label
            ><label
              >速度 / km/h<input
                type="number"
                v-model.number="input.speedKmh"
                min="1"
                :max="machine?.machinery?.kind === 'DRONE' ? 40 : 12"
                step="0.1"
                required
                :disabled="locked" /></label
            ><label
              >演示倍速<select
                v-model.number="input.simulationRate"
                aria-label="演示倍速"
                :disabled="locked"
              >
                <option :value="1">1 倍</option>
                <option :value="10">10 倍</option>
                <option :value="30">30 倍</option>
              </select></label
            >
          </div>
          <label class="check-row"
            ><input
              type="checkbox"
              v-model="input.recommendBearing"
              :disabled="locked"
            />沿田块长边推荐方向</label
          ><label v-if="!input.recommendBearing"
            >方向角 / °<input
              type="number"
              v-model.number="input.bearing"
              min="0"
              max="179"
              step="1"
              required
              :disabled="locked" /></label
          ><label class="check-row"
            ><input
              type="checkbox"
              v-model="input.reverse"
              :disabled="locked"
            />对调起终点</label
          ><label v-if="machine?.machinery?.kind === 'DRONE'"
            >相对作物高度 / m<input
              type="number"
              v-model.number="input.altitudeMeters"
              min="2"
              max="30"
              step="0.1"
              required
              :disabled="locked"
          /></label>
          <button
            class="primary full"
            :disabled="locked || busy || !parcel || !machine"
          >
            {{ busy ? "规划中…" : "预览往复式路线" }}
          </button>
        </form>
        <article v-if="preview" class="card">
          <h3>路线预览</h3>
          <RoutePreview
            :boundary="parcel?.boundary"
            :points="preview.data.points"
          />
          <p>
            {{ number(preview.data.lengthMeters) }} m ·
            {{ number(preview.data.workAreaMu, 2) }} 亩 · 预计
            {{ number(preview.data.estimatedMinutes) }} 分钟
          </p>
          <p class="muted">{{ preview.data.note }}</p>
          <button
            class="primary full"
            :disabled="locked || role !== 'ADMIN'"
            @click="dispatch"
          >
            确认下发方案
          </button>
          <p class="muted" v-if="role !== 'ADMIN'">
            只有农场管理员可以下发农机任务。
          </p>
        </article>
      </template>
      <template v-if="mode === 'water'"
        ><form class="card" @submit.prevent="water">
          <h3>分区灌溉</h3>
          <label
            >灌溉分区<select
              v-model="zoneId"
              required
              :disabled="locked"
              aria-label="灌溉分区"
            >
              <option v-for="z in spatial.zones" :key="z.id" :value="z.id">
                {{ z.name }}
              </option>
            </select></label
          >
          <p v-if="zone">
            {{
              props.devices.find((d) => d.id === zone.pumpId)?.name ||
              "水泵未配置"
            }}
          </p>
          <label
            >演示灌溉时长 / 秒<input
              type="number"
              v-model="duration"
              min="10"
              max="300"
              step="1"
              required
              :disabled="locked"
          /></label>
          <p class="muted">
            现有接口支持 10–300
            秒的模拟限时灌溉；实体泵闸请在设备中下发受支持的指令。
          </p>
          <button class="primary full" :disabled="locked || !zone || !writer">
            确认灌溉方案
          </button>
        </form>
        <article class="card">
          <h3>用水记录</h3>
          <p>
            累计估算
            <strong>{{ number(spatial.waterTotals.estimatedM3, 3) }} m³</strong>
          </p>
          <p>累计实测 {{ number(spatial.waterTotals.measuredM3, 3) }} m³</p>
          <small>未接入水表的数据以“—”显示，估算与实测分开统计。</small>
        </article></template
      >
      <div v-if="mode === 'records' || mode === 'water'">
        <p v-if="!spatial.jobs.length" class="empty">
          当前农场暂无作业记录，可先规划一项模拟任务。
        </p>
        <article
          class="card job-card"
          v-for="j in spatial.jobs.filter(
            (j) => mode !== 'water' || j.kind === 'IRRIGATION',
          )"
          :key="j.id"
          :data-job-id="j.id"
        >
          <header>
            <h3>{{ j.title }}</h3>
            <span class="tag">{{ statuses[j.status] || j.status }}</span>
          </header>
          <p>
            {{ spatial.parcels.find((p) => p.id === j.parcelId)?.name }} ·
            {{ devices.find((d) => d.id === j.deviceId)?.name }}
          </p>
          <progress :value="j.progress" max="100" /><small
            >{{ number(j.progress) }}% · {{ time(j.startedAt) }}</small
          >
          <p class="muted">{{ j.resultNote }}</p>
          <p v-if="j.kind === 'IRRIGATION'">
            估算 {{ number(j.estimatedM3, 3) }} m³ · 实测
            {{ number(j.measuredM3, 3) }} m³
          </p>
          <div class="button-row">
            <button @click="selectedJob = selectedJob === j.id ? '' : j.id">
              {{ selectedJob === j.id ? "收起详情" : "路线与记录" }}</button
            ><button @click="emit('device', j.deviceId)">关联设备</button>
          </div>
          <div
            v-if="writer && ['RUNNING', 'PAUSED'].includes(j.status)"
            class="button-row"
          >
            <button
              :disabled="locked"
              v-if="j.kind === 'MACHINERY'"
              @click="act(j, j.status === 'RUNNING' ? 'PAUSE' : 'RESUME')"
            >
              {{ j.status === "RUNNING" ? "暂停任务" : "继续任务" }}</button
            ><button
              class="danger-button"
              :disabled="locked"
              @click="act(j, 'STOP')"
            >
              停止任务
            </button>
          </div>
          <div v-if="job?.id === j.id">
            <RoutePreview :boundary="j.boundary" :points="j.route" />
            <p class="muted">
              {{ j.receiptId }} ·
              {{ j.actualTrack?.length ? "有执行数据" : "未接入实机轨迹" }}
            </p>
            <div class="record" v-for="(e, i) in j.events" :key="i">
              <p>{{ e.note }}</p>
              <small>{{ time(e.occurredAt) }}</small>
            </div>
          </div>
        </article>
      </div>
    </template>
  </section>
</template>
