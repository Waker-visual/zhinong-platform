<script setup>
import { computed } from "vue";
import AppIcon from "../ui/AppIcon.vue";
import { agronomyWork } from "./agronomy-work.js";
import { numberText, timeText } from "./screen-model.js";

const props = defineProps({
  workspace: Object,
  operations: Object,
  analysis: Object,
  workspaceTime: String,
  operationsTime: String,
  analysisTime: String,
});
const emit = defineEmits(["navigate"]);
const data = computed(() =>
  agronomyWork(props.workspace, props.operations, props.analysis),
);
const stats = computed(() => [
  { label: "待完成农事", value: data.value.tasks.open },
  { label: "执行中", value: data.value.tasks.running },
  { label: "已逾期", value: data.value.tasks.overdue, attention: true },
  { label: "未关闭现场问题", value: data.value.tasks.issues, attention: true },
]);
const taskStates = computed(() => [
  { key: "pending", label: "待执行", value: data.value.tasks.pending },
  { key: "running", label: "执行中", value: data.value.tasks.running },
  { key: "completed", label: "已完成", value: data.value.tasks.completed },
  { key: "cancelled", label: "已取消", value: data.value.tasks.cancelled },
]);
const taskDescription = computed(() =>
  taskStates.value
    .map((item) => `${item.label} ${numberText(item.value, 0)} 项`)
    .join("，"),
);
const dateText = (value) => value || "—";
</script>

<template>
  <section class="display-work-panel">
    <div class="display-section-heading">
      <div>
        <h2>农事与种植</h2>
        <p>当前农场台账 · {{ operationsTime }}</p>
      </div>
      <button @click="emit('navigate', 'daily')">查看全部农事</button>
    </div>
    <div class="display-work-stats" aria-label="全量农事与巡田统计">
      <div v-for="item in stats" :key="item.label">
        <span>{{ item.label }}</span>
        <strong :class="{ attention: item.attention && item.value > 0 }">
          {{ numberText(item.value, 0) }}<small>项</small>
        </strong>
      </div>
      <div class="display-work-harvest-stat">
        <span>近 30 天登记采收</span>
        <strong>{{ numberText(data.harvest.yieldKg) }}<small>kg</small></strong>
        <p>{{ numberText(data.harvest.records, 0) }} 条有效记录</p>
      </div>
      <div>
        <span>在期种植计划</span>
        <strong
          >{{ numberText(data.plantings.total, 0) }}<small>项</small></strong
        >
        <p>计划面积 {{ numberText(data.plantings.areaMu) }} 亩</p>
      </div>
    </div>
    <div
      class="display-work-body"
      tabindex="0"
      role="region"
      aria-label="农事进度、作业与种植计划明细"
    >
      <div class="display-work-section">
        <div class="display-work-section-title">
          <h3>农事进度</h3>
          <span>全部已登记 {{ numberText(data.tasks.total, 0) }} 项</span>
        </div>
        <div
          v-if="data.tasks.total > 0"
          class="display-work-progress"
          role="img"
          :aria-label="taskDescription"
        >
          <template v-for="item in taskStates" :key="item.key">
            <span
              v-if="item.value > 0"
              :class="item.key"
              :style="{ flexGrow: item.value }"
              :title="`${item.label} ${numberText(item.value, 0)} 项`"
            ></span>
          </template>
        </div>
        <dl class="display-work-legend">
          <div v-for="item in taskStates" :key="item.key" :class="item.key">
            <dt>{{ item.label }}</dt>
            <dd>{{ numberText(item.value, 0) }}</dd>
          </div>
        </dl>
        <p class="display-work-note">
          累计台账状态，含历史任务 · {{ workspaceTime }}
        </p>
      </div>

      <div class="display-work-section">
        <div class="display-work-section-title">
          <h3>作业跟进</h3>
          <span>受阻包含在待完成内</span>
        </div>
        <div class="display-work-activities">
          <article v-for="item in data.activities" :key="item.key">
            <AppIcon
              :name="item.key === 'irrigation' ? 'irrigation' : 'plantings'"
            />
            <div>
              <h4>{{ item.title }}</h4>
              <p>
                最近回执
                {{
                  item.lastCompleted
                    ? timeText(item.lastCompleted, { short: true })
                    : "—"
                }}
              </p>
            </div>
            <div class="display-work-activity-counts">
              <span
                >待完成 <b>{{ numberText(item.open, 0) }}</b></span
              >
              <span :class="{ attention: item.blocked > 0 }"
                >受阻 {{ numberText(item.blocked, 0) }}</span
              >
            </div>
          </article>
        </div>
      </div>

      <div class="display-work-section">
        <div class="display-work-section-title">
          <h3>在期种植计划</h3>
          <button @click="emit('navigate', 'plantings')">查看计划</button>
        </div>
        <ul v-if="data.plantings.items.length" class="display-work-plantings">
          <li
            v-for="(item, index) in data.plantings.items.slice(0, 3)"
            :key="`${item.plotId}-${item.startDate}-${index}`"
          >
            <div>
              <strong>{{ item.plotName || "未命名地块" }}</strong
              ><span
                >{{ item.crop || "未填写作物"
                }}<template v-if="item.variety">
                  · {{ item.variety }}</template
                ></span
              >
            </div>
            <p>
              {{ numberText(item.areaMu) }} 亩 · 播种后
              {{ numberText(item.daysAfterPlanting, 0) }} 天
            </p>
            <small
              >{{ dateText(item.startDate) }} 至
              {{ dateText(item.endDate) }}</small
            >
          </li>
        </ul>
        <p v-else class="display-work-note">
          {{
            data.plantings.available
              ? "暂无覆盖当前业务日的有效计划。"
              : "种植计划暂不可用，请刷新后核对。"
          }}
        </p>
        <p v-if="data.plantings.total > 3" class="display-work-note">
          展示前 3 项，共 {{ data.plantings.total }} 项；完整明细见种植计划。
        </p>
        <details class="display-work-explanation">
          <summary>种植统计口径</summary>
          <p>
            只展示启用且日期覆盖当前业务日的计划。面积按计划加总，同地块多计划可能重叠；播种后天数是日期差，不代表实测生育阶段。
          </p>
          <p>{{ analysisTime }}</p>
        </details>
      </div>
      <div class="display-work-section">
        <div class="display-work-section-title">
          <h3>采收台账</h3>
          <button @click="emit('navigate', 'production')">查看记录</button>
        </div>
        <p class="display-work-note">
          {{ numberText(data.harvest.open, 0) }} 项采收待完成 · 最近登记
          {{ dateText(data.harvest.latest) }}
        </p>
        <details class="display-work-explanation">
          <summary>采收统计口径</summary>
          <p>
            {{ dateText(data.harvest.fromDate) }} 至
            {{
              dateText(data.harvest.toDate)
            }}，含起止日，排除作废记录；登记产量不是亩产量。
          </p>
        </details>
      </div>
    </div>
  </section>
</template>
