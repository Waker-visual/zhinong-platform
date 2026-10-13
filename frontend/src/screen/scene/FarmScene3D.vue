<script setup>
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from "vue";
import AppIcon from "../../ui/AppIcon.vue";
import { numberText, timeText } from "../screen-model.js";
import { buildSceneModel } from "./scene-model.js";
import "./scene.css";

const props = defineProps({
  workspace: Object,
  analysis: Object,
  farmName: String,
  now: { type: Number, default: () => Date.now() },
  reducedMotion: Boolean,
  readAt: [String, Number],
});
const emit = defineEmits(["navigate", "ask", "alerts"]);
const canvas = ref(null),
  attempt = ref(0),
  ready = ref(false),
  failure = ref("");
const selected = ref("weather"),
  projected = ref([]),
  tour = ref(false);
const light = ref("day"),
  motion = ref(true),
  labels = ref(true);
let world,
  generation = 0,
  disposed = false;
const model = computed(() =>
  buildSceneModel(props.workspace, props.analysis, props.now),
);
const spot = computed(() =>
  model.value.hotspots.find((item) => item.id === selected.value),
);
const plot = computed(() =>
  model.value.plots.find((item) => item.id === selected.value),
);
const effectiveMotion = computed(() => motion.value && !props.reducedMotion);
const markers = computed(() => {
  const positions = new Map(projected.value.map((item) => [item.id, item]));
  return model.value.hotspots.flatMap((item) => {
    const position = positions.get(item.id);
    return position?.visible ? [{ ...item, ...position }] : [];
  });
});
const selectedIcon = computed(
  () =>
    ({
      plot: "plantings",
      weather: "today",
      water: "irrigation",
      yard: "farms",
    })[spot.value?.kind] || "farms",
);
const detailTitle = computed(() => spot.value?.label || "探索农场");
const detailCaption = computed(
  () =>
    ({
      plot: "田块档案",
      weather: "环境监测",
      water: "水源与灌溉",
      yard: "农场作业区",
    })[spot.value?.kind] || "农场信息",
);
const moisture = computed(
  () =>
    plot.value?.moisture ||
    model.value.metrics.find((item) => item.metric === "SOIL_MOISTURE"),
);
const stateLabel = (metric) =>
  metric?.state === "FRESH"
    ? "有效观测"
    : metric?.state === "STALE"
      ? "观测已过期"
      : "暂无有效观测";
const value = (metric) => numberText(metric?.value);
const metricIcon = (metric) =>
  ({
    TEMPERATURE: "today",
    HUMIDITY: "irrigation",
    SOIL_MOISTURE: "plantings",
  })[metric];

function choose(id, focus = true) {
  if (!model.value.hotspots.some((item) => item.id === id)) return;
  selected.value = id;
  world?.select(id, { focus });
}
function reset() {
  world?.reset();
}
function toggleTour() {
  world?.setTour(!tour.value);
}
function setLight(mode) {
  light.value = mode;
  world?.setTime(mode);
}
function ask() {
  emit("ask", {
    title: plot.value
      ? `${plot.value.label}的生产建议`
      : `${detailTitle.value}的运行情况`,
    recommendation: plot.value
      ? `核对${plot.value.crop}的当前监测、待办与风险，给出下一步巡田建议`
      : "核对当前农场的有效观测和设备异常，按优先级提出处理建议",
    evidence: plot.value
      ? [
          `登记面积 ${numberText(plot.value.areaMu)} 亩`,
          `土壤水分 ${value(moisture.value)}%，${stateLabel(moisture.value)}`,
        ]
      : ["3D 位置和建筑为示意模型，业务判断应以系统记录为准"],
  });
}
async function start() {
  const ticket = ++generation;
  world?.dispose();
  world = null;
  ready.value = false;
  failure.value = "";
  tour.value = false;
  projected.value = [];
  attempt.value += 1;
  await nextTick();
  try {
    const { createFarmWorld } = await import("./scene-world.js");
    if (disposed || ticket !== generation || !canvas.value) return;
    world = createFarmWorld({
      canvas: canvas.value,
      model: model.value,
      reducedMotion: !effectiveMotion.value,
      onSelect: (id) => {
        if (ticket === generation) choose(id);
      },
      onProject: (points) => {
        if (ticket === generation) projected.value = points;
      },
      onState: (state) => {
        if (ticket !== generation || disposed) return;
        if (state.tour !== undefined) tour.value = state.tour;
        if (state.ready !== undefined) ready.value = state.ready;
        if (state.ready && !state.lost) failure.value = "";
        if (state.lost) {
          ready.value = false;
          failure.value = "3D 画面已中断，农场资料仍可查看。可重新加载场景。";
        }
      },
    });
    world.setTime(light.value);
    world.select(selected.value, { focus: false });
    ready.value = true;
  } catch {
    if (ticket !== generation || disposed) return;
    world?.dispose();
    world = null;
    ready.value = false;
    failure.value =
      "当前环境暂时无法显示 3D 场景。可重试，或继续使用下方地点列表和农场资料。";
  }
}
watch(model, (next) => {
  world?.setModel(next);
  if (!next.hotspots.some((item) => item.id === selected.value))
    choose("weather", false);
});
watch(effectiveMotion, (enabled) => {
  world?.setMotion(enabled);
  if (!enabled) tour.value = false;
});
onMounted(start);
onBeforeUnmount(() => {
  disposed = true;
  generation += 1;
  world?.dispose();
  world = null;
});
</script>

<template>
  <section
    class="farm-scene"
    aria-label="3D 农场场景"
    :data-scene-state="failure ? 'error' : ready ? 'ready' : 'loading'"
  >
    <header class="farm-scene__toolbar">
      <div class="farm-scene__heading">
        <h2>走进农场</h2>
        <span>拖动旋转 · 滚轮缩放 · 点击地点探索</span>
      </div>
      <div class="farm-scene__tools" role="group" aria-label="场景操作">
        <div class="farm-scene__light" role="group" aria-label="场景光照">
          <button
            type="button"
            :aria-pressed="light === 'day'"
            :disabled="!ready"
            @click="setLight('day')"
          >
            <AppIcon name="today" />白昼
          </button>
          <button
            type="button"
            :aria-pressed="light === 'dusk'"
            :disabled="!ready"
            @click="setLight('dusk')"
          >
            <AppIcon name="moon" />暮色
          </button>
        </div>
        <button
          type="button"
          :aria-pressed="tour"
          :disabled="!ready || !effectiveMotion"
          :title="
            !effectiveMotion
              ? '开启动态效果后可巡游'
              : '缓慢环绕整个农场，拖动即可停止'
          "
          @click="toggleTour"
        >
          <AppIcon :name="tour ? 'stop' : 'patrol'" />{{
            tour ? "停止巡游" : "环绕巡游"
          }}
        </button>
        <button
          type="button"
          :aria-pressed="motion && !reducedMotion"
          :disabled="reducedMotion"
          :title="
            reducedMotion
              ? '已遵循系统减少动态效果设置'
              : '控制风杯与水面等场景动态'
          "
          @click="motion = !motion"
        >
          <AppIcon name="operations" />{{
            effectiveMotion ? "动态开启" : "画面静止"
          }}
        </button>
      </div>
    </header>

    <div class="farm-scene__layout">
      <div
        class="farm-scene__viewport"
        :class="{ 'is-dusk': light === 'dusk' }"
      >
        <canvas
          :key="attempt"
          ref="canvas"
          class="farm-scene__canvas"
          aria-label="可旋转缩放的农场三维示意场景，地点也可通过下方列表选择"
          tabindex="0"
        ></canvas>
        <div class="farm-scene__identity">
          <span>智禾 · 3D 农场</span
          ><strong>{{ farmName || "当前农场" }}</strong
          ><small>场景示意建模 · 非实景测绘</small>
        </div>
        <div
          v-if="ready && labels"
          class="farm-scene__hotspots"
          role="group"
          aria-label="模型地点"
        >
          <button
            v-for="item in markers"
            :key="item.id"
            type="button"
            class="farm-scene__hotspot"
            :class="{
              'is-selected': selected === item.id,
              'is-plot': item.kind === 'plot',
            }"
            :style="{ left: `${item.x}px`, top: `${item.y}px` }"
            :aria-label="`查看${item.label}`"
            :aria-pressed="selected === item.id"
            @click="choose(item.id)"
          >
            <span class="farm-scene__hotspot-dot" aria-hidden="true">+</span
            ><span>{{ item.label }}</span>
          </button>
        </div>
        <div
          v-if="!ready"
          class="farm-scene__status"
          :role="failure ? 'alert' : 'status'"
        >
          <AppIcon :name="failure ? 'info' : 'farms'" /><strong>{{
            failure ? "场景暂不可用" : "正在搭建农场场景"
          }}</strong>
          <p>
            {{ failure || "首次进入按需加载 3D 引擎，农场资料可先行查看。" }}
          </p>
          <button v-if="failure" type="button" @click="start">
            <AppIcon name="refresh" />重新加载场景
          </button>
        </div>
        <div class="farm-scene__viewport-tools">
          <button
            type="button"
            :disabled="!ready"
            aria-label="放大3D场景"
            title="放大"
            @click="world?.zoom(1.2)"
          >
            +
          </button>
          <button
            type="button"
            :disabled="!ready"
            aria-label="缩小3D场景"
            title="缩小"
            @click="world?.zoom(1 / 1.2)"
          >
            −
          </button>
          <button
            type="button"
            :disabled="!ready"
            aria-label="复位3D视野"
            title="复位视野"
            @click="reset"
          >
            <AppIcon name="expand" />
          </button>
          <button
            type="button"
            :disabled="!ready"
            :aria-pressed="labels"
            aria-label="显示地点标签"
            title="显示地点标签"
            @click="labels = !labels"
          >
            <AppIcon name="pin" />
          </button>
        </div>
        <div class="farm-scene__scene-note">
          <span class="farm-scene__north" aria-hidden="true">3D</span
          ><span>{{
            reducedMotion
              ? "已减少动态效果"
              : tour
                ? "正在环绕 · 拖动画面停止"
                : "选择地点，让视野靠近"
          }}</span>
        </div>
      </div>

      <aside class="farm-scene__inspector" aria-label="选中地点详情">
        <div class="farm-scene__inspector-heading">
          <span><AppIcon :name="selectedIcon" />{{ detailCaption }}</span
          ><button
            type="button"
            :disabled="!ready"
            aria-label="定位选中地点"
            title="靠近选中地点"
            @click="choose(selected)"
          >
            <AppIcon name="pin" />
          </button>
        </div>
        <h3>{{ detailTitle }}</h3>
        <template v-if="plot">
          <p class="farm-scene__description">
            {{ plot.crop
            }}<span class="farm-scene__subtle"> · 当前农场登记地块</span>
          </p>
          <dl class="farm-scene__facts">
            <div>
              <dt>登记面积</dt>
              <dd>{{ numberText(plot.areaMu) }}<small>亩</small></dd>
            </div>
            <div>
              <dt>关联设备</dt>
              <dd>{{ numberText(plot.deviceCount, 0) }}<small>台</small></dd>
            </div>
          </dl>
          <div class="farm-scene__moisture">
            <span>土壤水分</span
            ><strong>{{ value(moisture) }}<small>%</small></strong
            ><span
              class="farm-scene__data-state"
              :class="{ 'is-fresh': moisture?.state === 'FRESH' }"
              >{{ stateLabel(moisture) }}</span
            >
          </div>
          <p v-if="plot.hasAlerts" class="farm-scene__alert">
            {{
              plot.alertCount
                ? `${plot.alertLimitReached ? "至少 " : ""}${plot.alertCount} 项未关闭设备预警，建议先核查监测状态。`
                : "有关联设备预警，明细未完整加载，请进入预警中心核查。"
            }}
          </p>
          <p v-else class="farm-scene__hint">
            作物模型为视觉示意，生长状态需结合巡田与有效测量判断。
          </p>
          <button
            type="button"
            class="farm-scene__action"
            @click="emit('navigate', 'plantings')"
          >
            查看种植计划<AppIcon name="arrowRight" />
          </button>
        </template>
        <template v-else-if="selected === 'weather'">
          <p class="farm-scene__description">把田间变化，看得更清楚。</p>
          <dl class="farm-scene__readings">
            <div v-for="item in model.metrics" :key="item.metric">
              <dt>
                <AppIcon :name="metricIcon(item.metric)" />{{ item.label }}
              </dt>
              <dd>
                {{ value(item) }}<small>{{ item.unit }}</small>
              </dd>
              <dd
                class="farm-scene__reading-state"
                :class="{ 'is-fresh': item.state === 'FRESH' }"
              >
                {{ stateLabel(item) }}
              </dd>
            </div>
          </dl>
          <p class="farm-scene__hint">
            来自当前农场的有效测点汇总。模型气象杆的位置为示意，不对应实测安装点。
          </p>
          <button
            type="button"
            class="farm-scene__action"
            @click="emit('navigate', 'devices')"
          >
            查看监测设备<AppIcon name="arrowRight" />
          </button>
        </template>
        <template v-else-if="selected === 'water'">
          <p class="farm-scene__description">让水分判断回到田间数据。</p>
          <div class="farm-scene__moisture">
            <span>全场有效土壤水分均值</span
            ><strong>{{ value(moisture) }}<small>%</small></strong
            ><span
              class="farm-scene__data-state"
              :class="{ 'is-fresh': moisture?.state === 'FRESH' }"
              >{{ stateLabel(moisture) }}</span
            >
          </div>
          <p class="farm-scene__hint">
            水塘、泵房与水流为场景演示。尚未接入模型水位与流量；灌溉操作仍需在业务工作区确认。
          </p>
          <button
            type="button"
            class="farm-scene__action"
            @click="emit('navigate', 'devices')"
          >
            查看灌溉设备<AppIcon name="arrowRight" />
          </button>
        </template>
        <template v-else>
          <p class="farm-scene__description">从全场概况，走向今日行动。</p>
          <dl class="farm-scene__facts">
            <div>
              <dt>登记地块</dt>
              <dd>{{ numberText(model.totalPlots, 0) }}<small>块</small></dd>
            </div>
            <div>
              <dt>正常接收设备</dt>
              <dd>{{ numberText(model.devices.fresh, 0) }}<small>台</small></dd>
            </div>
          </dl>
          <p class="farm-scene__hint">
            农舍、筒仓、温室与农机为示意设施。场景不推算仓储库存、设备位置或作业产量。
          </p>
          <button
            type="button"
            class="farm-scene__action"
            @click="emit('navigate', 'daily')"
          >
            查看今日农事<AppIcon name="arrowRight" />
          </button>
        </template>
        <div class="farm-scene__inspector-bottom">
          <button type="button" class="farm-scene__ask" @click="ask">
            <AppIcon name="sprout" />让小禾分析这里</button
          ><button
            v-if="model.hasAlerts"
            type="button"
            class="farm-scene__alerts"
            @click="emit('alerts')"
          >
            <AppIcon name="bell" />{{
              model.alertCount
                ? `${model.alertLimitReached ? "至少 " : ""}${model.alertCount} 项待核查预警`
                : "查看关联设备预警"
            }}
          </button>
        </div>
      </aside>
    </div>

    <nav class="farm-scene__places" aria-label="3D农场地点选择">
      <span>探索地点</span
      ><button
        v-for="item in model.hotspots"
        :key="item.id"
        type="button"
        :aria-pressed="selected === item.id"
        @click="choose(item.id)"
      >
        {{ item.label }}
      </button>
      <small v-if="model.omittedPlots"
        >另有 {{ model.omittedPlots }} 个登记地块可在农场总览查看</small
      >
    </nav>
    <div class="farm-scene__footnote">
      <span v-if="model.status === 'loading'"
        >等待当前农场资料；模型设施仍可浏览。</span
      ><span v-else-if="model.status === 'empty'"
        >当前农场尚未登记地块，可先在种植管理中补充资料。</span
      ><span v-else
        >田块与设施按展示布局排列，真实地理位置请查看“农场总览”。</span
      ><span>{{
        readAt
          ? `农场资料读取于 ${timeText(readAt, { short: true })}`
          : "农场资料尚未读取"
      }}</span>
    </div>
  </section>
</template>
