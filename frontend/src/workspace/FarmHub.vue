<script setup>
import {
  computed,
  onMounted,
  onBeforeUnmount,
  reactive,
  ref,
  watch,
  nextTick,
} from "vue";
import { api } from "../api";
import FarmThumbnail from "./FarmThumbnail.vue";
import FarmWorkspace from "./FarmWorkspace.vue";
import { num } from "./presentation";
const props = defineProps({
  role: String,
  revision: Number,
  initialFarmId: String,
});
const emit = defineEmits(["farm"]);
const farms = ref([]),
  selected = ref(props.initialFarmId || ""),
  search = ref(""),
  error = ref(""),
  busy = ref(false),
  dialog = ref(false),
  editing = ref(null);
const model = reactive({
  name: "",
  description: "",
  region: "",
  farmType: "FIELD",
});
let alive = true,
  sequence = 0;
const filtered = computed(() =>
  farms.value
    .filter((f) =>
      (f.name + " " + f.description + " " + f.region)
        .toLowerCase()
        .includes(search.value.toLowerCase()),
    )
    .sort(
      (a, b) =>
        Number(b.plots.some((p) => p.boundary.length)) -
          Number(a.plots.some((p) => p.boundary.length)) ||
        b.deviceCount - a.deviceCount,
    ),
);
const totals = computed(() => ({
  area: farms.value.reduce((n, f) => n + Number(f.areaMu), 0),
  plots: farms.value.reduce((n, f) => n + f.plotCount, 0),
  devices: farms.value.reduce((n, f) => n + f.deviceCount, 0),
}));
async function load() {
  const run = ++sequence;
  busy.value = true;
  try {
    const data = await api("/farm-workspaces");
    if (alive && run === sequence) farms.value = data;
  } catch (e) {
    if (alive) error.value = e.message;
  } finally {
    if (alive) busy.value = false;
  }
}
function openForm(farm = null) {
  editing.value = farm;
  Object.assign(model, {
    name: farm?.name || "",
    description: farm?.description || "",
    region: farm?.region || "",
    farmType: farm?.farmType || "FIELD",
  });
  error.value = "";
  dialog.value = true;
}
async function save() {
  busy.value = true;
  error.value = "";
  try {
    if (editing.value)
      await api("/farms/" + editing.value.id + "/profile", "PUT", model);
    else await api("/farm-workspaces", "POST", model);
    dialog.value = false;
    await load();
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
async function remove(farm) {
  if (!window.confirm(`删除“${farm.name}”？已有地块或设备引用时不能删除。`))
    return;
  try {
    await api("/farms/" + farm.id, "DELETE");
    await load();
  } catch (e) {
    error.value = e.message;
  }
}
watch(() => props.revision, load);
watch(
  () => props.initialFarmId,
  (id) => {
    selected.value = id || "";
  },
);
// 打开或退出某座农场时同步顶栏的“当前农场”
watch(selected, (id) => {
  emit("farm", id);
  nextTick(() => window.scrollTo(0, 0));
});
onMounted(load);
onBeforeUnmount(() => {
  alive = false;
  ++sequence;
});
</script>
<template>
  <FarmWorkspace
    v-if="selected"
    :key="selected"
    :farm-id="selected"
    :role="role"
    :revision="revision"
    @back="
      selected = '';
      load();
    "
  />
  <section v-else class="farm-hub">
    <div class="hub-banner">
      <div>
        <p class="eyebrow">农场工作台</p>
        <h2>从一张农场图，进入生产现场。</h2>
        <p>查看地块布局、设备点位与经营数据，让每条记录都能找到所属的田地。</p>
      </div>
      <div class="hub-totals">
        <span
          ><b>{{ farms.length }}</b
          >农场</span
        ><span
          ><b>{{ num(totals.area) }}</b
          >亩</span
        ><span
          ><b>{{ totals.devices }}</b
          >设备</span
        >
      </div>
    </div>
    <div class="hub-toolbar">
      <div>
        <h3>我的农场</h3>
        <small>点击农场图片，进入地图与数据工作台。</small>
      </div>
      <div class="inline-controls">
        <input
          v-model="search"
          aria-label="搜索农场"
          placeholder="搜索农场、区域…"
        /><button v-if="role === 'ADMIN'" class="primary" @click="openForm()">
          ＋ 新增农场
        </button>
      </div>
    </div>
    <p v-if="error && !dialog" class="error" role="alert">{{ error }}</p>
    <div class="farm-card-grid">
      <article
        v-for="farm in filtered"
        :key="farm.id"
        class="farm-card"
        :data-farm-id="farm.id"
      >
        <button
          class="farm-cover-button"
          @click="selected = farm.id"
          :aria-label="'打开农场 ' + farm.name"
        >
          <FarmThumbnail :plots="farm.plots" :name="farm.name" /><span
            class="cover-caption"
            >查看平面图与设备 →</span
          ><span v-if="farm.demo" class="cover-demo">虚构演示</span>
        </button>
        <div class="farm-card-body">
          <h3>
            <button @click="selected = farm.id">{{ farm.name }}</button>
          </h3>
          <p class="farm-region">{{ farm.region || "尚未填写区域说明" }}</p>
          <p class="farm-description">
            {{ farm.description || "为农场添加地块和设备，开始维护经营记录。" }}
          </p>
          <div class="farm-card-stats">
            <span
              ><b>{{ farm.plotCount }}</b
              >地块</span
            ><span
              ><b>{{ num(farm.areaMu) }}</b
              >亩</span
            ><span
              ><b>{{ farm.deviceCount }}</b
              >设备</span
            ><span
              ><b>{{ farm.pendingTasks }}</b
              >待办</span
            >
          </div>
          <div class="farm-card-footer">
            <button class="primary" @click="selected = farm.id">进入农场</button
            ><button v-if="role === 'ADMIN'" @click="openForm(farm)">
              编辑资料</button
            ><button
              v-if="role === 'ADMIN' && !farm.plotCount && !farm.deviceCount"
              class="danger-text"
              @click="remove(farm)"
            >
              删除
            </button>
          </div>
        </div>
      </article>
    </div>
    <div v-if="!filtered.length" class="panel empty">
      {{
        busy
          ? "正在加载农场…"
          : search
            ? "没有匹配的农场。"
            : "暂无农场，请先新增农场，再建立地块和设备。"
      }}
    </div>
    <p class="muted">
      农场图片为本地平面预览，方向与布局用于管理示意；登记面积以地块档案为准。
    </p>
    <div
      v-if="dialog"
      class="modal-backdrop workspace-overlay"
      @click.self="!busy && (dialog = false)"
    >
      <section
        class="modal"
        role="dialog"
        aria-modal="true"
        aria-label="农场资料"
      >
        <div class="section-title">
          <h2>{{ editing ? "编辑农场资料" : "新增农场" }}</h2>
          <button aria-label="关闭农场表单" @click="dialog = false">×</button>
        </div>
        <form @submit.prevent="save">
          <label
            >农场名称<input
              v-model.trim="model.name"
              maxlength="100"
              required /></label
          ><label
            >区域说明<input
              v-model="model.region"
              maxlength="120"
              placeholder="例如：东区生产基地" /></label
          ><label
            >经营类型<select v-model="model.farmType">
              <option value="FIELD">大田种植</option>
              <option value="GREENHOUSE">设施农业</option>
              <option value="ORCHARD">果园</option>
              <option value="MIXED">综合经营</option>
            </select></label
          ><label
            >经营说明<textarea
              v-model="model.description"
              maxlength="500"
              rows="3"
            />
          </label>
          <p v-if="error" class="error" role="alert">{{ error }}</p>
          <div class="modal-actions">
            <button type="button" class="outline" @click="dialog = false">
              取消</button
            ><button class="primary" :disabled="busy">保存农场</button>
          </div>
        </form>
      </section>
    </div>
  </section>
</template>
