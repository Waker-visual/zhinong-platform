<script setup>
// 单条活动：状态点 + 标题 + 供屏幕阅读器使用的明确状态文本 + 可选明细/结果摘要（纯文本）。
defineProps({ activity: { type: Object, required: true } });
const statusLabel = { pending: '等待中', running: '进行中', completed: '已完成', error: '出错' };
</script>
<template>
  <li class="ai-activity-row" :class="activity.status">
    <span class="ai-activity-dot" aria-hidden="true"></span>
    <div class="ai-activity-body">
      <p class="ai-activity-label">{{ activity.label }}<span class="sr-only">，{{ statusLabel[activity.status] || activity.status }}</span></p>
      <p v-if="activity.detail" class="ai-activity-detail">{{ activity.detail }}</p>
      <p v-if="activity.resultSummary" class="ai-activity-result">{{ activity.resultSummary }}</p>
    </div>
  </li>
</template>
