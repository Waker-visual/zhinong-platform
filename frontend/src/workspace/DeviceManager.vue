<script setup>
import { computed, onMounted, onBeforeUnmount, ref, watch } from "vue";
import { api, loadError } from "../api";
import DeviceEditor from "./DeviceEditor.vue";
import DeviceDetail from "./DeviceDetail.vue";
import { reportFailure } from "../ui/feedback";
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
  detailId = ref("");
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
  editAsset.value = asset;
  editor.value = true;
}
async function saved(asset) {
  editor.value = false;
  await load();
  detailId.value = asset.id;
}
async function collect(device) {
  busy.value = true;
  try {
    await api("/assets/" + device.id + "/collect", "POST");
    await load();
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
    <div class="workspace-stats">
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
          >{{ devices.filter((d) => d.planX == null).length
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
          aria-label="搜索设备"
          placeholder="搜索设备名称、编码、型号…"
        /><select v-model="farmId" aria-label="筛选设备农场">
          <option value="">全部农场</option>
          <option v-for="farm in farms" :key="farm.id" :value="farm.id">
            {{ farm.name }}
          </option></select
        ><select v-model="type" aria-label="筛选设备类型">
          <option value="">全部类型</option>
          <option v-for="(name, code) in typeNames" :key="code" :value="code">
            {{ name }}
          </option></select
        ><select v-model="status" aria-label="筛选上报状态">
          <option value="">全部上报状态</option>
          <option
            v-for="code in [
              'FRESH',
              'STALE',
              'NO_DATA',
              'MAINTENANCE',
              'DISABLED',
            ]"
            :key="code"
            :value="code"
          >
            {{ stateNames[code] }}
          </option></select
        ><button
          v-if="role === 'ADMIN'"
          class="primary"
          @click="create"
          :disabled="!catalog || !farms.length"
        >
          ＋ 新增设备
        </button>
      </div>
      <div class="table-scroll">
        <table class="asset-table">
          <thead>
            <tr>
              <th>设备 / 编码</th>
              <th>农场 / 地块</th>
              <th>接入与状态</th>
              <th>最新监测</th>
              <th>最近上报</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="device in rows"
              :key="device.id"
              :data-asset-id="device.id"
            >
              <td>
                <button class="asset-name" @click="detailId = device.id">
                  {{ typeIcons[device.deviceType] }} {{ device.name }}</button
                ><small
                  >{{ device.code }} · {{ typeNames[device.deviceType] }}</small
                >
              </td>
              <td>
                <button @click="emit('farm', device.farmId)">
                  {{ device.farmName }} ↗</button
                ><small
                  >{{ device.plotName || "公共区域" }} ·
                  {{ device.planX == null ? "未定位" : "已定位" }}</small
                >
              </td>
              <td>
                <span
                  class="status-chip"
                  :class="device.freshness.toLowerCase()"
                  >{{ stateNames[device.freshness] }}</span
                ><small>{{ sourceNames[device.protocol] }}</small>
              </td>
              <td>
                <div v-for="c in device.channels.slice(0, 2)" :key="c.metric">
                  {{ c.name }}
                  <b>{{ c.latest ? num(c.latest.value, 2) : "—" }}</b>
                  {{ c.unit }}
                </div>
                <small v-if="device.channels.length > 2"
                  >共 {{ device.channels.length }} 个指标</small
                >
              </td>
              <td>
                {{ timeText(device.lastReceivedAt)
                }}<small v-if="device.alertCount" class="alarm-text"
                  >{{ device.alertCount }} 项告警待处理</small
                >
              </td>
              <td class="actions">
                <button @click="detailId = device.id">详情</button
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
      <p v-if="!rows.length" class="empty">
        {{
          busy
            ? "正在加载设备…"
            : "没有符合筛选条件的设备。管理员可以新增设备，配置指标与接入方式。"
        }}
      </p>
      <div class="pagination">
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
      @close="detailId = ''"
      @edit="edit"
      @changed="load"
    />
  </section>
</template>
