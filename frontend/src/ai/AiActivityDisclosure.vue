<script setup>
// 折叠式活动摘要：运行中自动展开，完成后自动收起；用户在本次运行中手动切换过的话，尊重用户的选择。
import { ref, watch } from 'vue';
import AiActivityRow from './AiActivityRow.vue';
import { shouldAutoOpenActivities, activitySummaryText } from './agent-events.js';
const props = defineProps({ activities: { type: Array, default: () => [] }, runStatus: { type: String, default: 'completed' }, writer: { type: Boolean, default: false } });
const emit = defineEmits(['toggle', 'view-approval']);
const manualOverride = ref(false);
const open = ref(shouldAutoOpenActivities(props.runStatus));
watch(
  () => props.runStatus,
  (status, previous) => {
    if (status === 'submitted' && previous !== 'submitted') manualOverride.value = false; // 新一轮运行开始，重置手动状态
    if (!manualOverride.value) open.value = shouldAutoOpenActivities(status);
  },
);
function onToggle(event) {
  manualOverride.value = true;
  open.value = event.target.open;
  emit('toggle', open.value);
}
</script>
<template>
  <details class="ai-activity-disclosure" :open="open" @toggle="onToggle">
    <summary>{{ activitySummaryText(activities, runStatus) }}</summary>
    <div class="ai-activity-collapse">
      <ol class="ai-activity-list">
        <AiActivityRow v-for="a in activities" :key="a.id || a.activityId" :activity="a" :writer="writer" @view-approval="t => emit('view-approval', t)" />
      </ol>
    </div>
  </details>
</template>
