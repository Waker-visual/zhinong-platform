<script setup>
// 单条活动：状态点 + 标题 + 供屏幕阅读器使用的明确状态文本 + 可选明细/结果摘要（纯文本）。
// kind==="approval" 的行（例如灌溉建议待确认）额外渲染一个“去确认”按钮：只对 writer（ADMIN/
// OPERATOR）展示，点击只是把用户带到既有的灌溉管理页签并高亮对应建议——聊天本身从不审批。
import { computed } from 'vue';
import { activityStatusLabel, irrigationApprovalTarget } from './agent-events.js';
const props = defineProps({ activity: { type: Object, required: true }, writer: { type: Boolean, default: false } });
const emit = defineEmits(['view-approval']);
const target = computed(() => irrigationApprovalTarget(props.activity));
function viewApproval() {
  if (target.value) emit('view-approval', target.value);
}
</script>
<template>
  <li class="ai-activity-row" :class="[activity.status, activity.kind === 'approval' && 'ai-activity-approval']">
    <span class="ai-activity-dot" aria-hidden="true"></span>
    <div class="ai-activity-body">
      <p class="ai-activity-label">{{ activity.label }}<span class="sr-only">，{{ activityStatusLabel(activity.status) }}</span></p>
      <p v-if="activity.detail" class="ai-activity-detail">{{ activity.detail }}</p>
      <p v-if="activity.resultSummary" class="ai-activity-result">{{ activity.resultSummary }}</p>
      <button v-if="target && writer" type="button" class="ai-activity-approve-btn" @click="viewApproval">去确认 →</button>
    </div>
  </li>
</template>
