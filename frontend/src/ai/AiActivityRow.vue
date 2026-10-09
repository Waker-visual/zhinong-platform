<script setup>
// 单条活动：图标列（20px）+ 内容列；连接线由本行自己画（从本行图标下沿到下一行图标上沿），
// 不再用列表级的一整根竖线穿过所有行的背景。状态图标是 16px、1.5 描边的内联 SVG，与 AppIcon
// 同一套线条语言：等待（pending）为空心圆，运行（running）为旋转弧线，完成（completed）为细线
// 勾圆，出错（error）为感叹号圆，待确认（approval+pending）为强调色空心圆，和普通 pending 区分开。
//
// 审批活动（kind==="approval"）在时间线内只保留一行摘要（标签本身已经写明“待确认：地块”），
// 不再渲染“去确认”按钮或原因/时长说明——那些现在交给时间线下方的独立操作卡片
// （AiApprovalCard.vue），折叠起来也能看到，不必展开活动详情才能确认。
import { computed } from 'vue';
import { activityStatusLabel, formatActivityDuration } from './agent-events.js';
const props = defineProps({ activity: { type: Object, required: true } });
const isApproval = computed(() => props.activity.kind === 'approval');
// 待确认用强调色空心圆区分普通 pending（例如还没轮到的步骤），其余状态直接取 activity.status。
const iconKind = computed(() => (isApproval.value && props.activity.status === 'pending' ? 'awaiting' : props.activity.status));
const durationText = computed(() => {
  const { startedAt, finishedAt } = props.activity;
  if (!startedAt || !finishedAt) return '';
  const ms = Date.parse(finishedAt) - Date.parse(startedAt);
  return Number.isFinite(ms) && ms >= 0 ? formatActivityDuration(ms) : '';
});
</script>
<template>
  <li class="ai-activity-row" :class="[activity.status, 'ai-activity-kind-' + activity.kind]">
    <span class="ai-activity-dot" :class="'ai-activity-icon-' + iconKind" aria-hidden="true">
      <svg viewBox="0 0 16 16" class="ai-activity-icon">
        <circle v-if="iconKind === 'pending' || iconKind === 'awaiting'" cx="8" cy="8" r="5.5" />
        <g v-else-if="iconKind === 'running'" class="ai-activity-spin">
          <circle cx="8" cy="8" r="5.5" opacity=".28" />
          <path d="M8 2.5A5.5 5.5 0 0 1 13.5 8" />
        </g>
        <g v-else-if="iconKind === 'error'">
          <circle cx="8" cy="8" r="5.5" />
          <path d="M8 5.1v3.6" />
          <path d="M8 11.3v.05" />
        </g>
        <g v-else>
          <circle cx="8" cy="8" r="5.5" />
          <path d="m5.3 8.3 1.8 1.8 3.4-4" />
        </g>
      </svg>
    </span>
    <div class="ai-activity-body">
      <p class="ai-activity-label">{{ activity.label }}<span class="sr-only">，{{ activityStatusLabel(activity.status) }}</span></p>
      <template v-if="!isApproval">
        <p v-if="activity.detail" class="ai-activity-detail">{{ activity.detail }}</p>
        <p v-if="activity.resultSummary" class="ai-activity-result">{{ activity.resultSummary }}</p>
      </template>
    </div>
    <span v-if="durationText" class="ai-activity-duration">{{ durationText }}</span>
  </li>
</template>
