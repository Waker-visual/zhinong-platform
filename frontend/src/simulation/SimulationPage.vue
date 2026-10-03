<script setup>
import { ref, reactive, computed, onMounted } from "vue";
import { api } from "../api";
import DataChart from "../workspace/DataChart.vue";
import { num } from "../workspace/presentation";
const props = defineProps({ role: String, initialFarmId: String });
const emit = defineEmits(["farm"]);
const farms = ref([]),
  plots = ref([]),
  catalog = ref(null),
  runs = ref([]),
  result = ref(null),
  runId = ref(""),
  scenario = ref("OUTAGE"),
  busy = ref(false),
  error = ref(""),
  plotFilter = ref("");
const form = reactive({
  farmId: "",
  label: "季度植保资源压力测试",
  startDate: "2025-06-01",
  days: 90,
  initialAge: 40,
  drones: 1,
  droneCapacity: 120,
  manualCapacity: 60,
  inspectionInterval: 7,
  responseDays: 3,
  outbreakDay: 20,
  outageDays: 45,
  severity: 0.75,
  irrigationM3: 500,
  fertilizerCoverage: 0.9,
  cropModels: {},
});
const selected = computed(() =>
  result.value?.scenarios.find((s) => s.id === scenario.value),
);
const canRun = computed(() => ["ADMIN", "OPERATOR"].includes(props.role));
const events = computed(() =>
  (selected.value?.events || []).filter(
    (e) => !plotFilter.value || e.plotId === plotFilter.value,
  ),
);
const comparison = computed(() => ({
  tooltip: { trigger: "axis", renderMode: "richText" },
  legend: { bottom: 0 },
  grid: { left: 75, right: 20, top: 30, bottom: 60 },
  xAxis: {
    type: "category",
    data: result.value?.scenarios.map((s) => s.name) || [],
  },
  yAxis: { type: "value", name: "kg" },
  series: [
    {
      name: "本期收获",
      type: "bar",
      data: result.value?.scenarios.map((s) => s.summary.harvestKg) || [],
      itemStyle: { color: "#359b79" },
    },
    {
      name: "虫害归因损失",
      type: "bar",
      data: result.value?.scenarios.map((s) => s.summary.pestLossKg) || [],
      itemStyle: { color: "#df9366" },
    },
  ],
}));
const resources = computed(() => ({
  tooltip: { trigger: "axis", renderMode: "richText" },
  legend: { bottom: 0 },
  grid: { left: 65, right: 55, top: 30, bottom: 65 },
  xAxis: {
    type: "category",
    data: selected.value?.days.map((d) => d.date) || [],
  },
  yAxis: [
    { type: "value", name: "亩" },
    { type: "value", name: "mm" },
  ],
  dataZoom: [{ type: "inside" }],
  series: [
    {
      name: "待防治面积",
      type: "line",
      showSymbol: false,
      data: selected.value?.days.map((d) => d.backlogMu) || [],
      areaStyle: { opacity: 0.15 },
      itemStyle: { color: "#dd925c" },
    },
    {
      name: "完成防治",
      type: "bar",
      data: selected.value?.days.map((d) => d.treatedMu) || [],
      itemStyle: { color: "#359b79" },
    },
    {
      name: "降雨",
      type: "line",
      yAxisIndex: 1,
      showSymbol: false,
      data: selected.value?.days.map((d) => d.rain) || [],
      itemStyle: { color: "#599aca" },
    },
  ],
}));
async function work(fn) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    await fn();
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
async function farmChanged() {
  emit("farm", form.farmId);
  runId.value = "";
  result.value = null;
  plots.value = form.farmId
    ? await api("/plots?farmId=" + encodeURIComponent(form.farmId))
    : [];
  form.cropModels = Object.fromEntries(
    plots.value.map((p) => [
      p.id,
      p.crop.includes("稻")
        ? "RICE"
        : p.crop.includes("玉米")
          ? "MAIZE"
          : p.crop.includes("麦")
            ? "WHEAT"
            : "VEGETABLE",
    ]),
  );
  runs.value = await api(
    "/simulations" +
      (form.farmId ? "?farmId=" + encodeURIComponent(form.farmId) : ""),
  );
}
function cycle() {
  if (form.days === 365) {
    form.startDate = "2025-01-01";
    form.initialAge = 0;
    form.outbreakDay = 180;
    form.label = "年度植保资源压力测试";
  } else {
    form.startDate = "2025-06-01";
    form.initialAge = 40;
    form.outbreakDay = 20;
    form.label = "季度植保资源压力测试";
  }
}
function preset(kind) {
  form.days = 90;
  cycle();
  Object.assign(form, {
    drones: 1,
    droneCapacity: 120,
    manualCapacity: 30,
    inspectionInterval: 3,
    responseDays: 3,
    outbreakDay: 20,
    outageDays: 45,
    severity: 0.75,
    irrigationM3: 500,
    fertilizerCoverage: 0.9,
  });
  if (kind === "late") {
    form.inspectionInterval = 14;
    form.label = "季度巡检延迟对照";
  }
  if (kind === "water") {
    form.irrigationM3 = 30;
    form.label = "季度灌溉不足对照";
  }
  result.value = null;
  runId.value = "";
}
async function execute() {
  await work(async () => {
    const data = await api("/simulations", "POST", form);
    result.value = data.result;
    runId.value = data.id;
    plotFilter.value = "";
    runs.value = await api(
      "/simulations?farmId=" + encodeURIComponent(form.farmId),
    );
  });
}
async function open(id) {
  await work(async () => {
    const data = await api("/simulations/" + id);
    result.value = data.result;
    runId.value = id;
    plotFilter.value = "";
  });
}
function download() {
  const url = URL.createObjectURL(
    new Blob(
      [JSON.stringify({ id: runId.value, result: result.value }, null, 2)],
      { type: "application/json" },
    ),
  );
  const a = document.createElement("a");
  a.href = url;
  a.download = "zhinong-simulation-" + runId.value + ".json";
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
onMounted(() =>
  work(async () => {
    [farms.value, catalog.value] = await Promise.all([
      api("/farms"),
      api("/simulations/catalog"),
    ]);
    form.farmId = farms.value.some((f) => f.id === props.initialFarmId)
      ? props.initialFarmId
      : farms.value[0]?.id || "";
    await farmChanged();
  }),
);
</script>
<template>
  <div class="simulation-page">
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <section class="panel simulation-intro">
      <div>
        <p class="eyebrow">OPERATIONS LAB</p>
        <h2>让经营方案接受一季天气的考验</h2>
        <p>
          同一组地块与天气，对比资源正常、无人机缺位及人工补位三种情景。查看产量差异背后的排程与资源缺口。
        </p>
      </div>
      <span class="badge"
        >{{ catalog?.storage.engine || "连接中" }} ·
        {{ catalog?.storage.weatherRows || 0 }} 天天气</span
      >
    </section>
    <section class="panel simulation-presets">
      <h3>先选择一个要验证的问题</h3>
      <p class="muted">
        预设只填写假设参数，点击运行后才保存报告。设备台账不会被修改。
      </p>
      <div class="simulation-preset-buttons">
        <button class="outline" :disabled="busy" @click="preset('outage')">
          无人机缺位 · 人工补位</button
        ><button class="outline" :disabled="busy" @click="preset('late')">
          巡检过慢 · 错过处理窗口</button
        ><button class="outline" :disabled="busy" @click="preset('water')">
          灌溉不足 · 作物差异
        </button>
      </div>
    </section>
    <section class="panel simulation-form">
      <form @submit.prevent="execute">
        <div class="simulation-fields">
          <label
            >模拟农场<select
              aria-label="模拟农场"
              v-model="form.farmId"
              required
              :disabled="busy"
              @change="work(farmChanged)"
            >
              <option value="" disabled>选择农场</option>
              <option v-for="f in farms" :key="f.id" :value="f.id">
                {{ f.name }}
              </option>
            </select></label
          ><label
            >模拟周期<select
              aria-label="模拟周期"
              v-model.number="form.days"
              @change="cycle"
            >
              <option :value="90">季度 · 90 天</option>
              <option :value="365">年度 · 365 天（单季种植）</option>
            </select></label
          ><label
            >情景名称<input
              v-model.trim="form.label"
              required
              maxlength="100" /></label
          ><label
            >开始日期<input
              v-model="form.startDate"
              type="date"
              min="2025-01-01"
              max="2025-10-03"
              :disabled="form.days === 365"
              required /></label
          ><label
            >期初生长天数<input
              v-model.number="form.initialAge"
              type="number"
              min="0"
              max="80"
              :disabled="form.days === 365"
              required
          /></label>
        </div>
        <details>
          <summary>调整资源、巡检与人为干预参数</summary>
          <div class="simulation-fields">
            <label
              >可用无人机（架）<input
                v-model.number="form.drones"
                type="number"
                min="0"
                max="50"
                required /></label
            ><label
              >单机日能力（亩）<input
                v-model.number="form.droneCapacity"
                type="number"
                min="1"
                max="10000"
                required /></label
            ><label
              >人工补位日能力（亩）<input
                v-model.number="form.manualCapacity"
                type="number"
                min="0"
                max="10000"
                required /></label
            ><label
              >巡检间隔（天）<input
                v-model.number="form.inspectionInterval"
                type="number"
                min="1"
                max="30"
                required /></label
            ><label
              >防治响应目标（天）<input
                v-model.number="form.responseDays"
                type="number"
                min="1"
                max="30"
                required /></label
            ><label
              >虫害发生日（从 0 起）<input
                v-model.number="form.outbreakDay"
                type="number"
                min="0"
                :max="form.days - 1"
                required /></label
            ><label
              >无人机缺位时长（天）<input
                v-model.number="form.outageDays"
                type="number"
                min="0"
                max="365"
                required /></label
            ><label
              >初始虫害强度（0—1）<input
                v-model.number="form.severity"
                type="number"
                min="0"
                max="1"
                step="0.05"
                required /></label
            ><label
              >日灌溉能力（m³）<input
                v-model.number="form.irrigationM3"
                type="number"
                min="0"
                max="1000000"
                required /></label
            ><label
              >施肥完成比例（0—1）<input
                v-model.number="form.fertilizerCoverage"
                type="number"
                min="0"
                max="1"
                step="0.05"
                required
            /></label>
          </div>
        </details>
        <details>
          <summary>
            地块与作物模型 · {{ plots.length }} 块 /
            {{ num(plots.reduce((s, p) => s + Number(p.areaMu), 0)) }} 亩
          </summary>
          <p class="muted">
            读取当前农场地块快照。请确认模型匹配：其他作物默认使用通用蔬菜模型，仅适合流程演练。
          </p>
          <div class="simulation-fields">
            <label v-for="p in plots" :key="p.id"
              >{{ p.name }} · {{ p.crop }} · {{ p.areaMu }} 亩<select
                v-model="form.cropModels[p.id]"
              >
                <option v-for="c in catalog?.crops" :key="c.id" :value="c.id">
                  {{ c.name }} · {{ c.duration }} 天 · {{ c.yieldKgMu }} kg/亩
                </option>
              </select></label
            >
          </div>
        </details>
        <div class="simulation-run-bar">
          <small
            >这是可复现的经营压力测试；参数及产量未经现场农学标定，不会写入实际生产台账。</small
          ><button
            v-if="canRun"
            class="primary"
            :disabled="busy || !plots.length"
          >
            {{ busy ? "正在计算并保存…" : "运行三方案对照 →" }}</button
          ><span v-else>查看者可以读取历史报告</span>
        </div>
      </form>
    </section>
    <section class="panel simulation-history">
      <label
        >历史模拟<select
          aria-label="历史模拟"
          v-model="runId"
          @change="open($event.target.value)"
          :disabled="busy"
        >
          <option value="" disabled>选择已保存的运行</option>
          <option v-for="r in runs" :key="r.id" :value="r.id">
            {{ r.label }} · {{ new Date(r.createdAt).toLocaleString("zh-CN") }}
          </option>
        </select></label
      ><span class="muted">保留输入、天气指纹、地块快照及每日结果</span>
    </section>
    <template v-if="result"
      ><section class="panel simulation-results">
        <div class="section-title">
          <div>
            <p class="eyebrow">SCENARIO COMPARISON</p>
            <h2>{{ result.input.label }} · {{ result.farmName }}</h2>
            <p class="muted">
              {{ result.input.startDate }} 起 {{ result.input.days }} 天 ·
              {{ result.modelVersion }}
            </p>
          </div>
          <button @click="download">下载完整报告 JSON</button>
        </div>
        <div class="simulation-comparison">
          <button
            v-for="s in result.scenarios"
            :key="s.id"
            :class="['scenario-card', { active: scenario === s.id }]"
            @click="scenario = s.id"
          >
            <span>{{ s.name }}</span
            ><strong
              >{{ num(s.summary.harvestKg) }} <small>kg 收获</small></strong
            ><span>虫害损失 {{ num(s.summary.pestLossKg) }} kg</span
            ><span>模拟作业费 ¥{{ num(s.summary.cost) }}</span
            ><span>超期队列 {{ s.summary.overdueDays }} 天</span>
          </button>
        </div>
        <DataChart :option="comparison" label="三种方案产量对照" />
      </section>
      <section v-if="selected" class="panel simulation-results">
        <h2>{{ selected.name }} · 资源与天气</h2>
        <DataChart :option="resources" label="防治队列与降雨时间线" />
        <div class="simulation-metrics">
          <span
            >灌溉用水 <b>{{ num(selected.summary.irrigationM3) }} m³</b></span
          ><span
            >飞行窗口关闭 <b>{{ selected.summary.closedDays }} 天</b></span
          ><span
            >假设销售额 <b>¥{{ num(selected.summary.revenue) }}</b></span
          ><span
            >扣模拟作业费后贡献额
            <b>¥{{ num(selected.summary.contribution) }}</b></span
          >
        </div>
        <p class="muted">
          贡献额未扣地租、种苗、人员固定工资等全部成本，不等于净利润。图表可滚轮缩放时间范围。
        </p>
        <div class="table-scroll">
          <table>
            <thead>
              <tr>
                <th>地块 / 模型</th>
                <th>成熟</th>
                <th>本期收获 kg</th>
                <th>缺水损失 kg</th>
                <th>虫害损失 kg</th>
                <th>温度损失 kg</th>
                <th>养分损失 kg</th>
                <th>待防治 亩</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="p in selected.plots" :key="p.plotId">
                <td>
                  {{ p.name }}<small>{{ p.crop }}</small>
                </td>
                <td>{{ p.harvested ? "已成熟" : "未成熟" }}</td>
                <td>{{ num(p.harvestKg) }}</td>
                <td>{{ num(p.waterLossKg) }}</td>
                <td>{{ num(p.pestLossKg) }}</td>
                <td>{{ num(p.heatLossKg) }}</td>
                <td>{{ num(p.nutrientLossKg) }}</td>
                <td>{{ num(p.untreatedMu) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <h3>验收发现与改进建议</h3>
        <ul class="simulation-findings">
          <li v-for="(f, i) in selected.findings" :key="i">{{ f }}</li>
        </ul>
        <details>
          <summary>逐日事件与处理依据 · {{ events.length }} 条</summary>
          <label
            >筛选事件地块<select v-model="plotFilter">
              <option value="">全部地块</option>
              <option
                v-for="p in selected.plots"
                :key="p.plotId"
                :value="p.plotId"
              >
                {{ p.name }}
              </option>
            </select></label
          >
          <div class="simulation-events">
            <div v-for="(e, i) in events" :key="i">
              <time>{{ e.date }}</time
              ><b>{{ e.plotName }}</b
              ><span>{{ e.message }} · {{ e.value }}</span>
            </div>
          </div>
        </details>
      </section>
    </template>
    <section v-if="catalog" class="panel simulation-sources">
      <details>
        <summary>数据来源、模型参数与适用范围</summary>
        <p>
          <a :href="catalog.weather.url" target="_blank" rel="noopener"
            >Open-Meteo Historical Weather API</a
          >
          · CC BY 4.0 · {{ catalog.weather.startDate }} 至
          {{ catalog.weather.endDate }} · 网格 {{ catalog.weather.latitude }},
          {{ catalog.weather.longitude }}
        </p>
        <p>
          <a :href="catalog.faoSource" target="_blank" rel="noopener"
            >FAO 作物水分与产量关系</a
          >
        </p>
        <ul>
          <li v-for="a in catalog.assumptions" :key="a">{{ a }}</li>
        </ul>
        <div class="table-scroll">
          <table>
            <thead>
              <tr>
                <th>模型</th>
                <th>周期/天</th>
                <th>参考单产 kg/亩</th>
                <th>Ky</th>
                <th>参数依据</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="c in catalog.crops" :key="c.id">
                <td>{{ c.name }}</td>
                <td>{{ c.duration }}</td>
                <td>{{ c.yieldKgMu }}</td>
                <td>{{ c.ky }}</td>
                <td>{{ c.basis }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <small>天气 SHA-256：{{ catalog.weather.sha256 }}</small>
      </details>
    </section>
  </div>
</template>
<style scoped>
.simulation-preset-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 16px;
}
.simulation-page {
  display: grid;
  gap: 20px;
}
.simulation-page .panel {
  padding: 24px;
}
.simulation-intro {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 20px;
}
.simulation-intro h2 {
  font-size: 24px;
}
.simulation-intro p {
  max-width: 760px;
  line-height: 1.8;
}
.simulation-intro .badge {
  white-space: nowrap;
}
.simulation-fields {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 14px 20px;
}
.simulation-page label {
  margin: 8px 0;
}
.simulation-page summary {
  cursor: pointer;
  padding: 14px 0;
  font-weight: 600;
}
.simulation-page details {
  border-top: 1px solid var(--border);
  margin-top: 14px;
}
.simulation-run-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 20px;
  margin-top: 20px;
}
.simulation-run-bar small {
  max-width: 600px;
  line-height: 1.7;
  color: var(--text-2);
}
.simulation-history {
  display: flex;
  gap: 24px;
  align-items: center;
}
.simulation-history label {
  flex: 1;
}
.simulation-comparison {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin: 20px 0;
}
.scenario-card {
  display: flex;
  flex-direction: column;
  align-items: start;
  gap: 9px;
  padding: 20px;
  text-align: left;
  border: 1px solid var(--border);
  background: var(--surface);
  color: var(--text);
  border-radius: 12px;
}
.scenario-card.active {
  border-color: var(--text);
  box-shadow: 0 0 0 1px var(--text);
  background: var(--nav-selected-bg);
}
.scenario-card strong {
  font-size: 26px;
}
.scenario-card strong small {
  font-size: 12px;
}
.simulation-metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 28px;
  margin: 20px 0;
}
.simulation-metrics span {
  display: grid;
  gap: 8px;
}
.simulation-findings {
  line-height: 2;
  padding-left: 20px;
}
.simulation-page td small {
  display: block;
  color: var(--text-2);
  margin-top: 5px;
}
.simulation-events {
  max-height: 350px;
  overflow: auto;
}
.simulation-events > div {
  display: flex;
  gap: 16px;
  padding: 12px 0;
  border-bottom: 1px solid var(--border);
}
.simulation-sources {
  line-height: 1.9;
}
.simulation-sources small {
  word-break: break-all;
}
@media (max-width: 900px) {
  .simulation-comparison {
    grid-template-columns: 1fr;
  }
  .simulation-intro,
  .simulation-run-bar,
  .simulation-history {
    flex-direction: column;
    align-items: stretch;
  }
  .simulation-page .panel {
    padding: 16px;
  }
}
</style>
