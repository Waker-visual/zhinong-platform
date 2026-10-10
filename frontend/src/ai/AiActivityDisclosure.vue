<script setup>
// 折叠式活动摘要：运行中自动展开，完成后自动收起；用户在本次运行中手动切换过的话，尊重用户的选择。
// 审批卡片（灌溸建议待确认等）不在这里渲染——时间线只负责活动本身，卡片由 FarmAssistant.vue
// 在 <AiActivityDisclosure> 之外单独渲染，这样折叠起来也能看到待确认的操作。
import { computed, ref, watch } from 'vue';
import AiActivityRow from './AiActivityRow.vue';
import AppIcon from '../ui/AppIcon.vue';
import { shouldAutoOpenActivities, activitySummaryText, orderActivitiesForDisplay } from './agent-events.js';
const props = defineProps({ activities: { type: Array, default: () => [] }, runStatus: { type: String, default: 'completed' } });
const emit = defineEmits(['toggle']);
const ordered = computed(() => orderActivitiesForDisplay(props.activities));
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
    <summary><AppIcon name="chevronDown" class="ai-activity-chevron" /><span class="ai-activity-summary-text">{{ activitySummaryText(activities, runStatus) }}</span></summary>
    <div class="ai-activity-collapse">
      <ol class="ai-activity-list">
        <AiActivityRow v-for="a in ordered" :key="a.id || a.activityId" :activity="a" />
      </ol>
    </div>
  </details>
</template>
