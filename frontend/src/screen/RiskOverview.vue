<script setup>
import { computed, nextTick, ref, watch } from "vue";
import AppIcon from "../ui/AppIcon.vue";
import {
  numberText,
  prioritizedRisks,
  riskForecastFreshness,
  riskSources,
  riskSeverities,
  timeText,
} from "./screen-model.js";
const props = defineProps({
  data: Object,
  resource: Object,
  forecastStatus: String,
  selectedDate: String,
  now: Number,
});
const emit = defineEmits(["retry", "action", "ask", "clear-date"]);
const source = ref("ALL"),
  highOnly = ref(false),
  expandedList = ref(false);
const panel = ref(null);
const scrollBody = ref(null);
defineExpose({
  resetFilters() {
    source.value = "ALL";
    highOnly.value = false;
    expandedList.value = false;
  },
  focus() {
    panel.value?.focus({ preventScroll: true });
  },
});
watch(
  () => props.selectedDate,
  (value) => {
    if (value) source.value = "WEATHER";
  },
);
const filtered = computed(() =>
  prioritizedRisks(props.data, {
    source: source.value,
    highOnly: highOnly.value,
    date: props.selectedDate,
  }),
);
watch([source, highOnly, () => props.selectedDate], async () => {
  await nextTick();
  scrollBody.value?.scrollTo({ top: 0, behavior: "instant" });
});
const visible = computed(() =>
  expandedList.value ? filtered.value : filtered.value.slice(0, 6),
);
const counts = computed(() => ({
  ALL: props.data?.summary?.total,
  WEATHER: props.data?.summary?.weather,
  DEVICE: props.data?.summary?.device,
  FIELD: props.data?.summary?.field,
  DATA_QUALITY: props.data?.summary?.dataQuality,
}));
const weatherStatus = computed(() =>
  props.forecastStatus && props.forecastStatus !== "READY"
    ? props.forecastStatus
    : props.resource?.error
      ? "STALE"
      : riskForecastFreshness(props.data, props.now),
);
function filter(value) {
  source.value = value;
  expandedList.value = false;
  if (props.selectedDate && value !== "WEATHER") emit("clear-date");
}
</script>

<template>
  <section
    ref="panel"
    tabindex="-1"
    class="screen-risk-overview"
    aria-labelledby="screen-risk-title"
  >
    <header class="display-section-heading">
      <div>
        <h2 id="screen-risk-title">优先预警与人工行动</h2>
        <p>
          设备、参考天气与现场问题分来源核对<template v-if="data?.generatedAt">
            · 汇总于 {{ timeText(data.generatedAt, { short: true }) }}</template
          >
        </p>
      </div>
      <button :disabled="resource?.loading" @click="emit('retry')">
        <AppIcon name="refresh" />{{
          resource?.loading ? "更新中" : "更新预警"
        }}
      </button>
    </header>
    <div class="risk-toolbar">
      <div
        class="risk-level-counts"
        role="group"
        aria-label="汇总时规则命中统计"
      >
        <span
          ><i class="risk-high-dot" />汇总时优先处理
          <strong>{{ numberText(data?.summary?.high, 0) }}</strong></span
        ><span
          ><i class="risk-warning-dot" />需要跟进
          <strong>{{ numberText(data?.summary?.warning, 0) }}</strong></span
        ><span
          >参考信息
          <strong>{{ numberText(data?.summary?.info, 0) }}</strong></span
        >
      </div>
      <div class="risk-controls">
        <div class="risk-source-filters" role="group" aria-label="预警来源筛选">
          <button
            v-for="(label, key) in { ALL: '全部', ...riskSources }"
            :key="key"
            :aria-pressed="source === key"
            @click="filter(key)"
          >
            {{ label }} <span>{{ numberText(counts[key], 0) }}</span>
          </button>
        </div>
        <label><input type="checkbox" v-model="highOnly" />只看优先处理</label>
      </div>
    </div>
    <p v-if="resource?.error" class="forecast-state-note">
      预警更新失败：{{ resource.error
      }}<template v-if="data">。当前保留旧汇总，请查看来源时间。</template>
    </p>
    <p v-if="data && weatherStatus !== 'READY'" class="forecast-state-note">
      区域天气信息待更新，天气风险覆盖需重新核对。设备与现场问题可独立核对。
    </p>
    <p v-if="selectedDate" class="risk-date-filter">
      天气日期：{{ selectedDate }}
      <button @click="emit('clear-date')">查看全部日期</button>
    </p>
    <div
      ref="scrollBody"
      class="risk-scroll-body"
      tabindex="0"
      aria-label="预警条目与处理依据"
    >
      <div v-if="visible.length" class="risk-items">
        <details
          v-for="item in visible"
          :key="item.source + ':' + item.id"
          class="risk-item"
          :class="'risk-severity-' + item.severity?.toLowerCase()"
        >
          <summary>
            <span class="risk-severity">{{
              item.source === "WEATHER" && weatherStatus !== "READY"
                ? "待更新参考"
                : riskSeverities[item.severity] || "参考信息"
            }}</span
            ><span class="risk-item-heading"
              ><strong>{{ item.title }}</strong
              ><small
                >{{ riskSources[item.source] || item.source
                }}<template v-if="item.deviceName">
                  · {{ item.deviceName }}</template
                ><template v-if="item.plotName"> · {{ item.plotName }}</template
                ><template v-if="item.affectedDate">
                  · {{ item.affectedDate
                  }}<template
                    v-if="item.endDate && item.endDate !== item.affectedDate"
                  >
                    至 {{ item.endDate }} 范围内</template
                  ></template
                ><template v-if="item.status">
                  ·
                  {{
                    {
                      OPEN: "待处理",
                      ACKNOWLEDGED: "已确认待关闭",
                      ASSIGNED: "已安排",
                      FORECAST: "预报规则线索",
                      RULE: "规则提示",
                      DATA_GAP: "资料缺口",
                    }[item.status] || item.status
                  }}</template
                ></small
              ></span
            ><AppIcon name="chevronDown" />
          </summary>
          <div class="risk-item-content">
            <h3>依据与范围</h3>
            <p
              v-if="item.source === 'WEATHER' && weatherStatus !== 'READY'"
              class="forecast-state-note"
            >
              此天气线索使用的是待重新核对的预报，请更新天气后再安排作业。
            </p>
            <p v-if="item.affectedDates?.length">
              涉及日期：{{ item.affectedDates.join("、") }}
            </p>
            <ul v-if="item.evidence?.length">
              <li v-for="(line, index) in item.evidence" :key="index">
                {{ line }}
              </li>
            </ul>
            <p v-else>当前条目尚无详细依据，请前往来源记录核对。</p>
            <small v-if="item.source === 'WEATHER' && data.forecastFetchedAt"
              >预报获取 {{ timeText(data.forecastFetchedAt) }} · 有效至
              {{ timeText(data.forecastExpiresAt) }}</small
            ><small v-if="item.occurredAt"
              >记录时间 {{ timeText(item.occurredAt) }}</small
            >
            <p v-if="item.linkedDeviceAlerts">
              已合并 {{ item.linkedDeviceAlerts }} 条关联设备告警
            </p>
            <h3>人工行动</h3>
            <p>
              {{
                item.recommendation || "请核对原始记录与现场情况后安排处理。"
              }}
            </p>
            <div class="risk-item-actions">
              <button
                v-if="item.action"
                class="display-primary"
                @click="emit('action', item)"
              >
                {{ item.action.label || "查看来源记录" }}</button
              ><button @click="emit('ask', item)">带入 AI 核对</button>
            </div>
          </div>
        </details>
      </div>
      <div v-else class="forecast-empty">
        <p>
          {{
            !data
              ? resource?.loading
                ? "正在汇总各来源预警，其他农情数据可继续查看。"
                : "预警汇总暂不可用，请更新重试。"
              : highOnly || source !== "ALL" || selectedDate
                ? "当前已载入条目中，没有符合筛选条件的预警。"
                : "当前没有命中的预警规则，仍需持续巡田与核对官方天气信息。"
          }}
        </p>
      </div>
      <button
        v-if="filtered.length > 6"
        class="risk-show-all"
        @click="expandedList = !expandedList"
      >
        {{ expandedList ? "收起条目" : `展开已载入的 ${filtered.length} 条` }}
      </button>
      <p v-if="data?.truncated" class="forecast-state-note">
        当前载入 {{ data.items?.length || 0 }} /
        {{ data.total ?? data.summary?.total }}
        条，筛选仅作用于已载入记录，请通过来源入口继续核对。
      </p>
      <details class="risk-explanation">
        <summary>预警口径与适用范围</summary>
        <div class="display-source-note risk-notice">
          <small>按来源记录计数，天气规则提示需现场核实</small>
          <p v-if="data?.notice">{{ data.notice }}</p>
        </div>
      </details>
    </div>
  </section>
</template>
