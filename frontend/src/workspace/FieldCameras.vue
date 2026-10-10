<script setup>
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { api, loadError } from "../api";
import AppIcon from "../ui/AppIcon.vue";
import CameraPlayer from "./CameraPlayer.vue";
import { stateNames, timeText } from "./presentation";
const props = defineProps({
  farmId: String,
  revision: Number,
  initialDeviceId: String,
});
const emit = defineEmits(["manage"]);
const data = ref(null),
  selectedId = ref(""),
  error = ref(""),
  loading = ref(false);
let sequence = 0,
  timer,
  alive = true;
const devices = computed(() => data.value?.devices || []);
const selected = computed(() =>
  devices.value.find((d) => d.id === selectedId.value),
);
const modes = {
  NONE: "未配置画面",
  DEMO_IMAGE: "图片源",
  IMAGE: "图片源",
  VIDEO: "视频源",
};
async function load(clear = false) {
  const current = ++sequence,
    farm = props.farmId;
  if (clear) {
    data.value = null;
    selectedId.value = "";
  }
  if (!farm) return;
  loading.value = true;
  try {
    const result = await api(`/farms/${encodeURIComponent(farm)}/cameras`);
    if (!alive || current !== sequence || farm !== props.farmId) return;
    data.value = result;
    if (!result.devices.some((d) => d.id === selectedId.value))
      selectedId.value =
        result.devices.find((d) => d.id === props.initialDeviceId)?.id ||
        result.devices[0]?.id ||
        "";
    error.value = "";
  } catch (e) {
    if (alive && current === sequence) error.value = loadError(e);
  } finally {
    if (alive && current === sequence) loading.value = false;
  }
}
watch(
  () => props.farmId,
  () => load(true),
  { immediate: true },
);
watch(
  () => props.revision,
  () => load(),
);
timer = setInterval(() => {
  if (!document.hidden && !loading.value) load();
}, 30000);
onBeforeUnmount(() => {
  alive = false;
  ++sequence;
  clearInterval(timer);
});
</script>

<template>
  <section class="field-cameras" :aria-busy="loading" :data-farm-id="farmId">
    <div class="camera-overview panel">
      <div>
        <span class="eyebrow">FIELD VIEW / 田间观察</span>
        <h2>{{ data?.farmName || "田间实景" }}</h2>
        <p>集中查看田间机位，联动地块、设备状态与画面配置。</p>
      </div>
      <div class="camera-overview-counts">
        <span
          ><strong>{{ devices.length }}</strong> 摄像头</span
        ><span
          ><strong>{{
            devices.filter((d) => d.lifecycle === "ACTIVE").length
          }}</strong>
          已启用</span
        ><span
          ><strong>{{
            devices.filter((d) => d.camera?.mode && d.camera.mode !== "NONE")
              .length
          }}</strong>
          已配置画面</span
        >
      </div>
    </div>
    <p v-if="error" role="alert" class="error">
      {{ error }} <button @click="load()">重试</button>
    </p>
    <p v-if="loading && !data" role="status" class="empty">
      正在加载本农场摄像头…
    </p>
    <div v-else-if="selected" class="camera-workspace">
      <section class="panel camera-main">
        <div class="camera-heading">
          <div>
            <h3>{{ selected.camera?.viewLabel || selected.name }}</h3>
            <p>{{ selected.name }} · {{ selected.code }}</p>
          </div>
          <AppIcon name="camera" />
        </div>
        <CameraPlayer :key="selected.id" :device="selected" />
        <dl class="camera-metadata">
          <div>
            <dt>所属地块</dt>
            <dd>{{ selected.plotName || "公共区域" }}</dd>
          </div>
          <div>
            <dt>设备上报</dt>
            <dd>
              {{ stateNames[selected.freshness] }}
            </dd>
          </div>
          <div>
            <dt>最近设备采样</dt>
            <dd>{{ timeText(selected.lastSampledAt) }}</dd>
          </div>
          <div>
            <dt>画面来源</dt>
            <dd>{{ modes[selected.camera?.mode || "NONE"] }}</dd>
          </div>
        </dl>
      </section>
      <aside class="panel camera-sidebar">
        <div class="camera-sidebar-title">
          <h3>本农场机位</h3>
          <span>{{ devices.length }} 台</span>
        </div>
        <div class="camera-device-list">
          <button
            v-for="device in devices"
            :key="device.id"
            :class="{ selected: selectedId === device.id }"
            :aria-pressed="selectedId === device.id"
            :data-camera-id="device.id"
            @click="selectedId = device.id"
          >
            <AppIcon name="camera" /><span
              ><strong>{{ device.camera?.viewLabel || device.name }}</strong
              ><small
                >{{ device.plotName || "公共区域" }} ·
                {{ modes[device.camera?.mode || "NONE"] }}</small
              ><small>{{
                device.lifecycle === "ACTIVE"
                  ? "已启用"
                  : device.lifecycle === "MAINTENANCE"
                    ? "维护中"
                    : "已停用"
              }}</small></span
            >
          </button>
        </div>
        <div class="camera-ledger-link">
          <h4>摄像头设备台账</h4>
          <p>在台账中维护名称、地块、安装位置、画面来源和使用状态。</p>
          <button
            class="primary"
            @click="emit('manage', { farmId, deviceId: selected.id })"
          >
            管理这台摄像头 →
          </button>
        </div>
        <p class="camera-sidebar-note">
          切换顶部“当前农场”，查看该农场的独立摄像头。设备状态每 30
          秒更新，支持在设备台账配置独立画面地址。
        </p>
      </aside>
    </div>
    <section v-else-if="!loading && !error" class="panel empty-state">
      <AppIcon name="camera" />
      <h3>{{ farmId ? "本农场尚无摄像头" : "请选择一座农场" }}</h3>
      <p>在设备台账新增“视频监测”设备，选择所属农场和画面来源。</p>
      <button v-if="farmId" @click="emit('manage', { farmId, deviceId: '' })">
        前往设备台账
      </button>
    </section>
  </section>
</template>

<style scoped>
.field-cameras {
  display: grid;
  gap: 20px;
}
.camera-overview {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
}
.eyebrow {
  color: var(--muted);
  font-size: 10px;
  letter-spacing: 0.12em;
}
.camera-overview h2 {
  margin: 8px 0;
}
.camera-overview p,
.camera-heading p {
  color: var(--muted);
  font-size: 12px;
  margin: 7px 0 0;
}
.camera-overview-counts {
  display: flex;
  gap: 24px;
  flex-shrink: 0;
}
.camera-overview-counts span {
  font-size: 12px;
  color: var(--muted);
}
.camera-overview-counts strong {
  display: block;
  font-size: 26px;
  font-weight: 500;
  color: var(--text);
  margin-bottom: 4px;
}
.camera-workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 290px;
  gap: 20px;
  align-items: start;
}
.camera-main,
.camera-sidebar {
  min-width: 0;
}
.camera-heading,
.camera-sidebar-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 18px;
}
.camera-heading h3,
.camera-sidebar-title h3 {
  margin: 0;
}
.camera-heading > .app-icon {
  width: 26px;
  height: 26px;
  color: var(--muted);
}
.camera-sidebar-title > span {
  font-size: 12px;
  color: var(--muted);
}
.camera-device-list {
  display: grid;
  gap: 10px;
}
.camera-device-list button {
  display: flex;
  align-items: start;
  gap: 10px;
  text-align: left;
  padding: 12px;
  width: 100%;
}
.camera-device-list button.selected {
  border-color: var(--brand);
  background: var(--subtle-bg);
}
.camera-device-list button > span {
  display: grid;
  gap: 8px;
  min-width: 0;
}
.camera-device-list strong {
  font-size: 12px;
  line-height: 1.5;
}
.camera-device-list small {
  color: var(--muted);
  font-size: 11px;
}
.camera-device-list .app-icon {
  flex-shrink: 0;
}
.camera-ledger-link {
  margin-top: 24px;
  padding-top: 20px;
  border-top: 1px solid var(--border);
}
.camera-ledger-link h4 {
  margin: 0;
}
.camera-ledger-link p,
.camera-sidebar-note {
  font-size: 12px;
  color: var(--muted);
  line-height: 1.7;
}
.camera-sidebar-note {
  margin: 22px 0 0;
}
.camera-metadata {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
  border-top: 1px solid var(--border);
  padding-top: 18px;
  margin: 20px 0 0;
  font-size: 12px;
}
.camera-metadata dt {
  color: var(--muted);
  margin-bottom: 7px;
}
.camera-metadata dd {
  margin: 0;
  overflow-wrap: anywhere;
}
@media (max-width: 1100px) {
  .camera-workspace {
    grid-template-columns: minmax(0, 1fr);
  }
  .camera-overview {
    align-items: start;
    flex-direction: column;
  }
}
@media (max-width: 600px) {
  .field-cameras {
    gap: 12px;
  }
  .camera-metadata {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
