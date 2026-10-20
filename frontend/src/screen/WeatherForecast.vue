<script setup>
import { computed } from "vue";
import AppIcon from "../ui/AppIcon.vue";
import {
  forecastPresentation,
  forecastWeatherType,
  numberText,
  publicWebUrl,
  riskForecastFreshness,
  riskSeverities,
  weatherRisksOnDate,
} from "./screen-model.js";
const props = defineProps({
  data: Object,
  resource: Object,
  risks: Object,
  riskResource: Object,
  now: Number,
  role: String,
});
const emit = defineEmits(["retry", "map", "risks"]);
const model = computed(() =>
  forecastPresentation(props.data, props.resource, props.now),
);
const dateLabel = (date) =>
  new Intl.DateTimeFormat("zh-CN", {
    timeZone: "UTC",
    month: "2-digit",
    day: "2-digit",
    weekday: "short",
  }).format(new Date(date + "T00:00:00Z"));
function time(value) {
  const at = Date.parse(value);
  if (!Number.isFinite(at)) return "未标注";
  return new Date(at).toLocaleString("zh-CN", {
    timeZone: props.data?.timeZone || "Asia/Shanghai",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
}
const days = computed(() =>
  model.value.days.map((day) => ({
    ...day,
    risks: weatherRisksOnDate(props.risks, day.date),
    type: forecastWeatherType(day.weatherCode),
  })),
);
const riskReady = computed(
  () =>
    model.value.status === "READY" &&
    riskForecastFreshness(props.risks, props.now) === "READY" &&
    !props.riskResource?.error,
);
const emptyText = computed(
  () =>
    ({
      UNCONFIGURED:
        "尚未配置气象参考位置。请先在农场地图核对并保存参考位置，再读取区域预报。",
      DISABLED: "天气服务尚未启用，请联系管理员检查天气服务配置。",
      UNAVAILABLE:
        "暂未取得可用的七日预报。可重试；田间监测请切换到“智能农情”查看。",
      LOADING: "正在读取区域天气预报，其他农情数据可继续查看。",
    })[model.value.status] || "",
);
const diagnosticText = computed(
  () =>
    ({
      LOCATION_NOT_SAVED: "尚未保存参考位置。",
      INVALID_LOCATION: "参考位置不在可用范围，请在地图核对。",
      LOCATION_CHANGED: "参考位置已改变，请重新读取预报。",
      FORECAST_DISABLED: "管理员尚未启用天气服务。",
      TIMEOUT: "天气服务响应超时，请稍后重试。",
      NETWORK_ERROR: "暂时无法连接天气来源，请稍后重试。",
      UPSTREAM_HTTP_ERROR: "天气来源暂未提供可用响应，请稍后重试。",
      INVALID_RESPONSE: "天气来源的本次数据不完整，未用于预报。",
      BUSY: "天气服务繁忙，请稍后重试。",
      INTERRUPTED: "天气读取已中断，可重新更新。",
      CACHE_EXPIRED: "已超过有效期或进入新的预报日期，请重新更新。",
      CACHE_TOO_OLD: "旧预报已超过保留时限，请重新获取。",
      NO_CURRENT_FORECAST: "尚未取得当前日期的有效预报。",
      UNAVAILABLE: "暂时无法取得有效预报。",
    })[props.data?.diagnostic] || "请核对天气状态并稍后重试。",
);
const paths = {
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5"/>',
  cloud:
    '<path d="M6 18h12a4 4 0 0 0 .4-8A6.5 6.5 0 0 0 6 9.5 4.3 4.3 0 0 0 6 18Z"/>',
  rain: '<path d="M6 14h12a4 4 0 0 0 .4-8A6.5 6.5 0 0 0 6 5.5 4.3 4.3 0 0 0 6 14ZM7 18l-1 3m6-3-1 3m6-3-1 3"/>',
  snow: '<path d="M6 13h12a4 4 0 0 0 .4-8A6.5 6.5 0 0 0 6 4.5 4.3 4.3 0 0 0 6 13ZM7 17v5m-2.2-3.7 4.4 2.5m0-2.5-4.4 2.5M17 17v5m-2.2-3.7 4.4 2.5m0-2.5-4.4 2.5"/>',
  storm:
    '<path d="M6 14h12a4 4 0 0 0 .4-8A6.5 6.5 0 0 0 6 5.5 4.3 4.3 0 0 0 6 14ZM12 15l-3 4h4l-2 4"/>',
  fog: '<path d="M6 11h12a4 4 0 0 0 .4-8A6.5 6.5 0 0 0 6 .5 4.3 4.3 0 0 0 6 11ZM3 16h18M5 20h14"/>',
  unknown: '<path d="M5 12h14"/>',
};
</script>

<template>
  <section
    class="screen-forecast"
    aria-labelledby="screen-forecast-title"
    tabindex="0"
  >
    <header class="display-section-heading">
      <div>
        <h2 id="screen-forecast-title">未来 7 天 · 区域天气参考</h2>
        <p>
          {{ data?.location?.label || "参考位置待配置"
          }}<template v-if="data?.timeZone"> · {{ data.timeZone }}</template>
        </p>
      </div>
      <div class="forecast-heading-actions">
        <span
          class="display-tag"
          :class="
            model.status === 'READY' ? 'display-tag-leaf' : 'display-tag-gold'
          "
          >{{ model.label }}</span
        ><button :disabled="resource?.loading" @click="emit('retry')">
          <AppIcon name="refresh" />{{
            resource?.loading ? "更新中" : "更新天气"
          }}
        </button>
      </div>
    </header>
    <p class="forecast-boundary">
      虚构农场参考位置的公开区域预报，供作业前核对；田间实测与官方预警请另行确认。
    </p>
    <p v-if="resource?.error" class="forecast-state-note">
      本次更新失败：{{ resource.error
      }}<template v-if="model.days.length"
        >。保留先前取得的预报，执行前请重新核对。</template
      >
    </p>
    <p v-if="model.expired && model.days.length" class="forecast-state-note">
      预报已过期，以下数值只作旧预报参考，天气风险也需更新后核对。
    </p>
    <div
      v-if="days.length"
      class="forecast-days-scroll"
      tabindex="0"
      role="region"
      aria-label="未来七日天气，窄屏可横向滚动"
    >
      <ol class="forecast-days">
        <li
          v-for="day in days"
          :key="day.date"
          :class="{ 'forecast-day-risk': day.risks[0]?.severity === 'HIGH' }"
        >
          <h3>
            <time :datetime="day.date">{{ dateLabel(day.date) }}</time>
          </h3>
          <div class="forecast-condition">
            <svg
              viewBox="0 0 24 24"
              aria-hidden="true"
              focusable="false"
              v-html="paths[day.type]"
            /><span>{{ day.weatherLabel || "天气类型缺失" }}</span>
          </div>
          <p class="forecast-temperature">
            <strong>{{ numberText(day.tempMaxC) }}°</strong
            ><span>{{ numberText(day.tempMinC) }}°</span
            ><small>最高 / 最低 ℃</small>
          </p>
          <dl>
            <div>
              <dt>降水概率</dt>
              <dd>
                {{ numberText(day.precipitationProbabilityPct, 0)
                }}<small>%</small>
              </dd>
            </div>
            <div>
              <dt>降水量</dt>
              <dd>{{ numberText(day.precipitationMm) }}<small>mm</small></dd>
            </div>
            <div>
              <dt>最大阵风</dt>
              <dd>{{ numberText(day.gustMaxMs) }}<small>m/s</small></dd>
            </div>
          </dl>
          <button
            v-if="day.risks.length"
            class="forecast-day-warning"
            @click="emit('risks', day.date)"
          >
            <span
              >{{ riskSeverities[day.risks[0].severity] || "需核对"
              }}<template v-if="!riskReady"> · 旧参考</template></span
            >{{ day.risks[0].title
            }}<small v-if="day.risks.length > 1"
              >另 {{ day.risks.length - 1 }} 项天气线索</small
            >
          </button>
          <p v-else class="forecast-day-note">
            {{ riskReady ? "暂无天气规则提示" : "天气风险信息待更新" }}
          </p>
        </li>
      </ol>
    </div>
    <div v-else class="forecast-empty">
      <AppIcon name="overview" />
      <p>{{ emptyText }}</p>
      <button v-if="model.status === 'UNCONFIGURED'" @click="emit('map')">
        {{ role === "ADMIN" ? "核对并配置参考位置" : "查看农场地图" }}
      </button>
    </div>
    <footer class="forecast-provenance">
      <span
        >来源：<a
          v-if="publicWebUrl(data?.attribution?.url)"
          :href="publicWebUrl(data.attribution.url)"
          target="_blank"
          rel="noopener noreferrer"
          >{{ data.attribution.name || "Open-Meteo" }}</a
        ><template v-else>{{
          data?.attribution?.name || "Open-Meteo"
        }}</template
        ><template v-if="data?.attribution?.licence">
          ·
          <a
            v-if="publicWebUrl(data.attribution.licenceUrl)"
            :href="publicWebUrl(data.attribution.licenceUrl)"
            target="_blank"
            rel="noopener noreferrer"
            >{{ data.attribution.licence }}</a
          ><template v-else>{{ data.attribution.licence }}</template></template
        ></span
      ><span v-if="data?.fetchedAt"
        >获取 {{ time(data.fetchedAt) }} · 有效至
        {{ time(data.expiresAt) }}</span
      ><span v-if="days.length && days.length < 7"
        >本次只取得 {{ days.length }} 天预报，缺失日期未补造</span
      >
    </footer>
    <div class="forecast-tools">
      <details
        v-if="days.length"
        class="forecast-explanation forecast-more-metrics"
      >
        <summary>更多气象指标 · 最大风速与参考蒸散量</summary>
        <div
          class="forecast-metrics-scroll"
          tabindex="0"
          role="region"
          aria-label="七日更多气象指标，窄屏可横向滚动"
        >
          <table class="forecast-metrics-table">
            <caption class="sr-only">
              同一份区域预报的七日最大风速与参考蒸散量
            </caption>
            <thead>
              <tr>
                <th scope="col">气象指标</th>
                <th v-for="day in days" :key="day.date" scope="col">
                  <time :datetime="day.date">{{ dateLabel(day.date) }}</time>
                </th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <th scope="row">最大风速 <small>m/s</small></th>
                <td v-for="day in days" :key="day.date">
                  {{ numberText(day.windMaxMs) }}
                </td>
              </tr>
              <tr>
                <th scope="row">参考蒸散量 ET₀ <small>mm</small></th>
                <td v-for="day in days" :key="day.date">
                  {{ numberText(day.et0Mm) }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p>
          ET₀
          为参考蒸散量，不能直接作为灌溉量；请结合田间墒情、作物阶段与现场条件核对。
        </p>
      </details>
      <details
        v-if="data?.notice || data?.diagnostic"
        class="forecast-explanation"
      >
        <summary>天气来源与适用范围</summary>
        <p>{{ data.notice }}</p>
        <p v-if="data.diagnostic && model.status !== 'READY'">
          {{ diagnosticText }}
        </p>
      </details>
    </div>
  </section>
</template>
