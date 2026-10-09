<script setup>
// 灌溉审批操作卡片：渲染在活动时间线下方（<AiActivityDisclosure> 外部），折叠时间线也始终可见。
// 只读取已有的 PROPOSED 建议并提示去“灌溉管理”页签确认；批准/取消仍然只能通过该页签原有的
// 确认对话框完成——这里从不直接下发任何设备指令，writer 之外的角色看到的是说明文字而不是按钮。
import { computed } from 'vue';
import { irrigationApprovalTarget } from './agent-events.js';
const props = defineProps({ activity: { type: Object, required: true }, writer: { type: Boolean, default: false } });
const emit = defineEmits(['view-approval']);
const target = computed(() => irrigationApprovalTarget(props.activity));
// 标签约定为“灌溉建议待确认：地块名”；取冒号后的部分作为卡片标题的地块名，没有冒号就整句作标题。
const plotName = computed(() => {
  const label = props.activity.label || '';
  const i = label.indexOf('：');
  return i === -1 ? label : label.slice(i + 1);
});
function viewApproval() {
  if (target.value) emit('view-approval', target.value);
}
</script>
<template>
  <div v-if="target" class="ai-approval-card">
    <div class="ai-approval-head">
      <span class="ai-approval-badge">待确认</span>
      <h4>{{ plotName }}</h4>
    </div>
    <p v-if="activity.resultSummary" class="ai-approval-reason">{{ activity.resultSummary }}</p>
    <button v-if="writer" type="button" class="primary ai-approval-go" @click="viewApproval">去确认</button>
    <p v-else class="ai-approval-hint">需管理员或操作员确认</p>
  </div>
</template>
