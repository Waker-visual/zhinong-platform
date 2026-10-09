<script setup>
// 折叠式活动摘要：运行中自动展开，完成后自动收起；用户在本次运行中手动切换过的话，尊重用户的选择。
import { ref, watch } from 'vue';
import AiActivityRow from './AiActivityRow.vue';
const props = defineProps({ activities: { type: Array, default: () => [] }, runStatus: { type: String, default: 'completed' } });
const emit = defineEmits(['toggle']);
function autoOpen(status) { return status === 'submitted' || status === 'running' || status === 'error'; }
const manualOverride = ref(false);
const open = ref(autoOpen(props.runStatus));
watch(
  () => props.runStatus,
  (status, previous) => {
    if (status === 'submitted' && previous !== 'submitted') manualOverride.value = false; // 新一轮运行开始，重置手动状态
    if (!manualOverride.value) open.value = autoOpen(status);
  },
);
function onToggle(event) {
  manualOverride.value = true;
  open.value = event.target.open;
  emit('toggle', open.value);
}
function summaryText() {
  const total = props.activities.length;
  if (props.runStatus === 'error') return '出错';
  if (props.runStatus === 'completed') return `已完成 ${total} 项操作`;
  if (props.runStatus === 'cancelled') return `已停止 · ${total} 项`;
  const running = props.activities.find((a) => a.status === 'running');
  if (running) return `${running.label} · ${total} 项`;
  return total ? `正在处理 · ${total} 项` : '正在准备…';
}
</script>
<template>
  <details class="ai-activity-disclosure" :open="open" @toggle="onToggle">
    <summary>{{ summaryText() }}</summary>
    <ol class="ai-activity-list">
      <AiActivityRow v-for="a in activities" :key="a.id" :activity="a" />
    </ol>
  </details>
</template>
