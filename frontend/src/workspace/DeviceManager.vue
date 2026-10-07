<script setup>
import {
  computed,
  nextTick,
  onMounted,
  onBeforeUnmount,
  ref,
  watch,
} from "vue";
import { api, loadError } from "../api";
import DeviceEditor from "./DeviceEditor.vue";
import DeviceDetail from "./DeviceDetail.vue";
import SelectMenu from "../ui/SelectMenu.vue";
import { reportFailure, toast } from "../ui/feedback";
import {
  typeNames,
  typeIcons,
  stateNames,
  sourceNames,
  timeText,
  num,
} from "./presentation";
const props = defineProps({ role: String, revision: Number });
const emit = defineEmits(["farm"]);
const devices = ref([]),
  farms = ref([]),
  plots = ref([]),
  catalog = ref(null),
  error = ref(""),
  busy = ref(false),
  search = ref(""),
  farmId = ref(""),
  type = ref(""),
  status = ref(""),
  page = ref(1);
const editor = ref(false),
  editAsset = ref(null),
  detailId = ref(""),
  detailMorphSource = ref(""),
  detailMorphActive = ref(false);
let alive = true,
  sequence = 0;
const writer = computed(() => ["ADMIN", "OPERATOR"].includes(props.role));
const filtered = computed(() =>
  devices.value.filter(
    (d) =>
      (!farmId.value || d.farmId === farmId.value) &&
      (!type.value || d.deviceType === type.value) &&
      (!status.value || d.freshness === status.value) &&
      (d.name + " " + d.code + " " + d.model)
        .toLowerCase()
        .includes(search.value.toLowerCase()),
  ),
);
const pageCount = computed(() =>
    Math.max(1, Math.ceil(filtered.value.length / 15)),
  ),
  rows = computed(() =>
    filtered.value.slice((page.value - 1) * 15, page.value * 15),
  );
const farmOptions = computed(() => [
  { value: "", label: "选择农场" },
  ...farms.value.map((farm) => ({ value: farm.id, label: farm.name })),
]);
const typeOptions = computed(() => [
  { value: "", label: "全部类型" },
  ...Object.entries(typeNames).map(([value, label]) => ({ value, label })),
]);
const statusOptions = [
  { value: "", label: "全部上报状态" },
  ...["FRESH", "STALE", "NO_DATA", "MAINTENANCE", "DISABLED"].map((value) => ({
    value,
    label: stateNames[value],
  })),
];
async function load() {
  const current = ++sequence;
  busy.value = true;
  try {
    const [d, f, p, c] = await Promise.all([
      api("/assets"),
      api("/farms"),
      api("/plots"),
      api("/assets/catalog"),
    ]);
    if (!alive || current !== sequence) return;
    devices.value = d;
    farms.value = f;
    plots.value = p;
    catalog.value = c;
    error.value = "";
    page.value = Math.min(page.value, pageCount.value);
  } catch (e) {
    if (alive) error.value = loadError(e);
  } finally {
    if (alive) busy.value = false;
  }
}
function create() {
  editAsset.value = null;
  editor.value = true;
}
function edit(asset) {
  detailId.value = "";
  detailMorphSource.value = "";
  detailMorphActive.value = false;
  editAsset.value = asset;
  editor.value = true;
}
function canMorph() {
  return (
    document.startViewTransition &&
    !matchMedia("(prefers-reduced-motion: reduce)").matches
  );
}
async function openDetail(id) {
  if (!canMorph()) {
    detailId.value = id;
    detailMorphSource.value = "";
    detailMorphActive.value = false;
    return;
  }
  detailMorphSource.value = `row:${id}`;
  detailMorphActive.value = true;
  document.documentElement.classList.add("device-morphing");
  const transition = document.startViewTransition(async () => {
    detailId.value = id;
    await nextTick();
  });
  await transition.finished.catch(() => {});
  detailMorphActive.value = false;
  document.documentElement.classList.remove("device-morphing");
}
async function closeDetail() {
  if (!detailId.value || !canMorph() || !detailMorphSource.value) {
    detailId.value = "";
    detailMorphSource.value = "";
    detailMorphActive.value = false;
    return;
  }
  detailMorphActive.value = true;
  document.documentElement.classList.add("device-morphing");
  const transition = document.startViewTransition(() => {
    detailId.value = "";
  });
  await transition.finished.catch(() => {});
  detailMorphActive.value = false;
  detailMorphSource.value = "";
  document.documentElement.classList.remove("device-morphing");
}
async function saved(asset) {
  const created = !editAsset.value;
  editor.value = false;
  await load();
  detailId.value = asset.id;
  toast(created ? "设备已创建" : "设备资料已保存");
}
async function collect(device) {
  busy.value = true;
  try {
    await api("/assets/" + device.id + "/collect", "POST");
    await load();
    toast("模拟采集已完成");
  } catch (e) {
    reportFailure(e, () => collect(device));
  } finally {
    busy.value = false;
  }
}
watch([search, farmId, type, status], () => (page.value = 1));
watch(() => props.revision, load);
onMounted(load);
onBeforeUnmount(() => {
  alive = false;
  ++sequence;
});
</script>
<template>
  <section class="device-manager">
    <div
      :class="[
        'workspace-stats',
        { 'numbers-loading': busy && !devices.length },
      ]"
    >
      <article>
        <span>设备台账</span
        ><strong>{{ devices.length }}<small>台</small></strong>
      </article>
      <article>
        <span>正常上报</span
        ><strong
          >{{ devices.filter((d) => d.freshness === "FRESH").length
          }}<small>台</small></strong
        >
      </article>
      <article>
        <span>超时未报</span
        ><strong
          >{{ devices.filter((d) => d.freshness === "STALE").length
          }}<small>台</small></strong
        >
      </article>
      <article>
        <span>未定位设备</span
        ><strong
          >{{ devices.filter((d) => !d.positioned).length
          }}<small>台</small></strong
        >
      </article>
      <article>
        <span>待处理告警</span
        ><strong
          >{{ devices.reduce((sum, d) => sum + d.alertCount, 0)
          }}<small>项</small></strong
        >
      </article>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <section class="panel">
      <div class="device-filter-toolbar">
        <input
          v-model="search"
          type="search"
          enterkeyhint="search"
          autocomplete="off"
          aria-label="搜索设备"
          placeholder="搜索设备名称、编码、型号…"
        /><SelectMenu
          v-model="farmId"
          aria-label="筛选设备农场"
          :options="farmOptions"
        /><SelectMenu
          v-model="type"
          aria-label="筛选设备类型"
          :options="typeOptions"
        /><SelectMenu
          v-model="status"
          aria-label="筛选上报状态"
          :options="statusOptions"
        />
        <button
          v-if="role === 'ADMIN'"
          class="primary"
          @click="create"
          :disabled="!catalog || !farms.length"
        >
          <span class="add-device-icon" aria-hidden="true">＋</span>新增设备
        </button>
      </div>
      <div class="table-scroll">
        <!-- 手机上每台设备显示为一张卡片；显式 ARIA 角色让读屏软件仍按表格朗读 -->
        <table class="asset-table stack-table" role="table">
          <thead role="rowgroup">
            <tr role="row">
              <th role="columnheader">设备 / 编码</th>
              <th role="columnheader">农场 / 地块</th>
              <th role="columnheader">接入与状态</th>
              <th role="columnheader">最新监测</th>
              <th role="columnheader">最近采样</th>
              <th role="columnheader">操作</th>
            </tr>
          </thead>
          <tbody v-if="busy && !rows.length" aria-hidden="true">
            <tr v-for="n in 5" :key="n" class="skeleton-row">
              <td v-for="c in 6" :key="c">
                <span
                  class="skeleton"
                  :style="{ width: 40 + ((n * c) % 4) * 12 + '%' }"
                ></span>
              </td>
            </tr>
          </tbody>
          <tbody role="rowgroup">
            <tr
              v-for="device in rows"
              :key="device.id"
              :data-asset-id="device.id"
              role="row"
              :style="
                detailMorphActive && detailMorphSource === `row:${device.id}`
                  ? { viewTransitionName: 'device-morph' }
                  : null
              "
            >
              <td role="cell" class="stack-head">
                <button class="asset-name" @click="openDetail(device.id)">
                  {{ typeIcons[device.deviceType] }} {{ device.name }}</button
                ><small class="asset-meta"
                  ><span class="asset-code" :title="device.code">{{
                    device.code
                  }}</span
                  ><span class="asset-type">
                    · {{ typeNames[device.deviceType] }}</span
                  ></small
                >
              </td>
              <td role="cell" data-label="农场 / 地块">
                <button @click="emit('farm', device.farmId)">
                  {{ device.farmName }} ↗</button
                ><small
                  >{{ device.plotName || "公共区域" }} ·
                  {{ device.positioned ? "已定位" : "未定位" }}</small
                >
              </td>
              <td role="cell" data-label="接入与状态">
                <span
                  class="status-chip"
                  :class="device.freshness.toLowerCase()"
                  >{{ stateNames[device.freshness] }}</span
                ><small>{{ sourceNames[device.protocol] }}</small>
                <small
                  >{{ device.freshChannelCount }} /
                  {{ device.channels.length }} 项指标新鲜</small
                >
              </td>
              <td role="cell" data-label="最新监测">
                <div
                  v-for="c in device.channels.slice(0, 2)"
                  :key="c.metric"
                  class="metric-reading"
                >
                  <span class="metric-key"
                    >{{ c.name
                    }}<small v-if="c.latest && c.freshness !== 'FRESH'"
                      >已过期</small
                    ></span
                  >
                  <span class="metric-value"
                    ><b>{{ c.latest ? num(c.latest.value, 2) : "—" }}</b
                    ><span class="metric-unit">{{ c.unit }}</span></span
                  >
                </div>
                <small v-if="device.channels.length > 2" class="metric-count"
                  >共 {{ device.channels.length }} 个指标</small
                >
              </td>
              <td role="cell" data-label="最近采样">
                {{ timeText(device.lastSampledAt)
                }}<small v-if="device.alertCount" class="alarm-text"
                  >{{ device.alertCount }} 项告警待处理</small
                >
              </td>
              <td class="actions" role="cell" data-label="操作">
                <button @click="openDetail(device.id)">详情</button
                ><button v-if="role === 'ADMIN'" @click="edit(device)">
                  编辑</button
                ><button
                  v-if="
                    writer &&
                    device.protocol === 'SIMULATED' &&
                    device.lifecycle === 'ACTIVE'
                  "
                  @click="collect(device)"
                  :disabled="busy"
                >
                  采集
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-if="busy && !rows.length" class="sr-only" role="status">
        正在加载设备…
      </p>
      <div v-else-if="!rows.length" class="empty empty-state">
        <template v-if="search || farmId || type || status">
          <p>没有符合筛选条件的设备。</p>
          <button
            class="outline"
            @click="
              search = '';
              farmId = '';
              type = '';
              status = '';
            "
          >
            清除筛选
          </button>
        </template>
        <template v-else-if="role === 'ADMIN' && !farms.length">
          <p>还没有设备。设备要挂在农场下，请先在农场档案新增农场。</p>
        </template>
        <template v-else>
          <p>还没有设备。新增后配置监测指标与接入方式，再到农场平面图定位。</p>
          <button
            v-if="role === 'ADMIN' && catalog"
            class="primary"
            @click="create"
          >
            ＋ 新增第一台设备
          </button>
        </template>
      </div>
      <div v-if="!(busy && !devices.length)" class="pagination">
        <span>共 {{ filtered.length }} 台 · 每页 15 台</span
        ><button class="outline" @click="page--" :disabled="page <= 1">
          上一页</button
        ><span>{{ page }} / {{ pageCount }}</span
        ><button class="outline" @click="page++" :disabled="page >= pageCount">
          下一页
        </button>
      </div>
    </section>
    <section class="integration-guide panel">
      <h3>从设备建档到数据进入大屏</h3>
      <div>
        <p><b>01</b>新增设备，选择所属农场和地块</p>
        <p><b>02</b>配置监测指标、阈值与接入方式</p>
        <p><b>03</b>在农场平面图定位设备</p>
        <p><b>04</b>采集或上报数据，查看趋势与告警</p>
      </div>
      <p class="muted">
        人工录入、本地模拟、HTTP
        上报均已实现。上报状态依据数据接收时间判断；模拟数据有独立来源标识。接口详情和凭据在设备详情中管理。
      </p>
    </section>
    <DeviceEditor
      v-if="editor && catalog"
      :asset="editAsset"
      :farms="farms"
      :plots="plots"
      :catalog="catalog"
      :farm-id="farmId"
      @close="editor = false"
      @saved="saved"
    />
    <DeviceDetail
      v-if="detailId"
      :key="detailId"
      :id="detailId"
      :role="role"
      :transition-name="detailMorphActive ? 'device-morph' : ''"
      @close="closeDetail"
      @edit="edit"
      @changed="load"
    />
  </section>
</template>
