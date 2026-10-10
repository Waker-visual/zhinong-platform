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
import { api, loadError } from "../api";
import { deferDelete, failure, pendingIds } from "../ui/feedback";
import FarmThumbnail from "./FarmThumbnail.vue";
import FarmWorkspace from "./FarmWorkspace.vue";
import { num } from "./presentation";
import SelectMenu from "../ui/SelectMenu.vue";
import { important } from "../ui/feedback";
const props = defineProps({
  role: String,
  revision: Number,
  initialFarmId: String,
});
const emit = defineEmits(["farm", "changed"]);
const archived = ref(false);
watch(archived, () => load());
async function restoreFarm(farm) {
  try {
    await api(`/farms/${farm.id}/restore`, "POST", {});
    await load();
    emit("changed");
    important("农场已恢复，历史记录完整保留");
  } catch (e) {
    error.value = e.message;
  }
}
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
    .filter(
      (f) =>
        !pendingIds.has(f.id) &&
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
const farmTypeOptions = [
  { value: "FIELD", label: "大田种植" },
  { value: "GREENHOUSE", label: "设施农业" },
  { value: "ORCHARD", label: "果园" },
  { value: "MIXED", label: "综合经营" },
];
async function load() {
  const run = ++sequence;
  busy.value = true;
  try {
    const data = await api("/farm-workspaces?archived=" + archived.value);
    if (alive && run === sequence) farms.value = data;
  } catch (e) {
    if (alive) error.value = loadError(e);
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
    const creating = !editing.value;
    if (editing.value)
      await api("/farms/" + editing.value.id + "/profile", "PUT", model);
    else await api("/farm-workspaces", "POST", model);
    dialog.value = false;
    await load();
    important(creating ? "农场已创建" : "农场资料已保存");
    // 顶栏的“当前农场”列表同步更新
    emit("changed");
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
// 还有地块或设备的农场不能删除：点击时直接说明原因，而不是等撤销期结束后才失败
function remove(farm) {
  if (farm.plotCount || farm.deviceCount) {
    failure(
      `“${farm.name}”下还有 ${farm.plotCount} 块地块、${farm.deviceCount} 台设备，请先删除或移走它们再删除农场。`,
    );
    return;
  }
  deferDelete({
    id: farm.id,
    label: farm.name,
    commit: (keepalive) =>
      api("/farms/" + farm.id, "DELETE", undefined, { keepalive }),
    settled: async () => {
      await load();
      emit("changed");
    },
  });
}
// 共享元素转场：点中的农场封面放大成工作台地图，返回时再收回卡片（清单：空间连续性）。
// 浏览器不支持 View Transitions 或系统要求减少动态效果时直接切换。
const morphId = ref("");
function morph(update, id) {
  if (
    !document.startViewTransition ||
    matchMedia("(prefers-reduced-motion: reduce)").matches
  )
    return update();
  morphId.value = id;
  document.documentElement.classList.add("farm-morphing");
  const transition = document.startViewTransition(async () => {
    update();
    await nextTick();
  });
  transition.finished.finally(() => {
    morphId.value = "";
    document.documentElement.classList.remove("farm-morphing");
  });
}
function openFarm(id) {
  morph(() => (selected.value = id), id);
}
function closeFarm() {
  const id = selected.value;
  morph(() => (selected.value = ""), id);
  load();
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
    @back="closeFarm"
  />
  <section v-else class="farm-hub">
    <div class="hub-banner">
      <div>
        <p class="eyebrow">农场工作台</p>
        <h2>从一张农场图，进入生产现场。</h2>
        <p>查看地块布局、设备点位与经营数据，让每条记录都能找到所属的田地。</p>
      </div>
      <div
        :class="['hub-totals', { 'numbers-loading': busy && !farms.length }]"
      >
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
        <h3>{{ archived ? "已归档农场" : "我的农场" }}</h3>
        <small>点击农场图片，进入地图与数据工作台。</small>
      </div>
      <div class="inline-controls">
        <button @click="archived = !archived">
          {{ archived ? "返回在用农场" : "查看归档" }}
        </button>
        <input
          v-model="search"
          type="search"
          enterkeyhint="search"
          autocomplete="off"
          aria-label="搜索农场"
          placeholder="搜索农场、区域…"
        /><button v-if="role === 'ADMIN'" class="primary" @click="openForm()">
          ＋ 新增农场
        </button>
      </div>
    </div>
    <p v-if="error && !dialog" class="error" role="alert">{{ error }}</p>
    <div class="farm-card-grid">
      <template v-if="busy && !farms.length">
        <article
          v-for="n in 3"
          :key="'s' + n"
          class="farm-card skeleton-card"
          aria-hidden="true"
        >
          <span class="skeleton skeleton-block skeleton-cover"></span>
          <div class="farm-card-body">
            <span class="skeleton" style="width: 55%; height: 18px"></span>
            <span class="skeleton" style="width: 80%"></span>
            <span class="skeleton" style="width: 68%"></span>
          </div>
        </article>
      </template>
      <article
        v-for="farm in filtered"
        :key="farm.id"
        class="farm-card"
        :data-farm-id="farm.id"
      >
        <button
          class="farm-cover-button"
          :style="
            morphId === farm.id ? { viewTransitionName: 'farm-morph' } : null
          "
          @click="!archived && openFarm(farm.id)"
          :aria-label="'打开农场 ' + farm.name"
        >
          <FarmThumbnail :plots="farm.plots" :name="farm.name" /><span
            class="cover-caption"
            >{{
              archived
                ? "经营与设备记录已保留，可恢复后查看"
                : "查看地图与设备 →"
            }}</span
          ><span v-if="farm.demo" class="cover-demo">虚构演示</span>
        </button>
        <div class="farm-card-body">
          <h3>
            <button @click="!archived && openFarm(farm.id)">
              {{ farm.name }}
            </button>
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
            <button
              v-if="archived && role === 'ADMIN'"
              class="primary"
              @click="restoreFarm(farm)"
            >
              恢复农场</button
            ><button
              v-else-if="!archived"
              class="primary"
              @click="openFarm(farm.id)"
            >
              进入农场</button
            ><button
              v-if="role === 'ADMIN' && !archived"
              @click="openForm(farm)"
            >
              编辑资料</button
            ><button
              v-if="role === 'ADMIN' && !archived"
              class="danger-text"
              @click="remove(farm)"
            >
              删除
            </button>
          </div>
        </div>
      </article>
    </div>
    <p v-if="busy && !farms.length" class="sr-only" role="status">
      正在加载农场…
    </p>
    <div v-else-if="!filtered.length" class="panel empty empty-state">
      <template v-if="search">
        <p>没有匹配“{{ search }}”的农场。</p>
        <button class="outline" @click="search = ''">清除搜索</button>
      </template>
      <template v-else>
        <p>还没有农场。新增农场后，再建立地块、设备和种植计划。</p>
        <button v-if="role === 'ADMIN'" class="primary" @click="openForm()">
          ＋ 新增第一座农场
        </button>
        <p v-else>请联系农场主建立农场。</p>
      </template>
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
          <button
            class="close-button"
            aria-label="关闭农场表单"
            @click="dialog = false"
          >
            ×
          </button>
        </div>
        <form v-validate @submit.prevent="save">
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
            >经营类型<SelectMenu
              v-model="model.farmType"
              :options="farmTypeOptions"
              aria-label="经营类型" /></label
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
