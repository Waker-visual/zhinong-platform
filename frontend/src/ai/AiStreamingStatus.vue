<script setup>
// 提交/读取/生成/完成/出错的状态条；role=status 供屏幕阅读器播报，出错时提供重试与停止入口。
import { streamingStatusText } from './agent-events.js';
defineProps({ status: { type: String, default: 'submitted' }, diagnostic: { type: String, default: '' }, canRetry: { type: Boolean, default: false } });
const emit = defineEmits(['retry', 'cancel']);
</script>
<template>
  <div class="ai-streaming-status" :class="status" role="status" aria-live="polite">
    <span v-if="status !== 'cancelled'" class="ai-status-dot" aria-hidden="true"></span>
    <span class="ai-status-text">{{ streamingStatusText(status, diagnostic) }}</span>
    <button v-if="status === 'error' && canRetry" type="button" class="text-button" @click="emit('retry')">重试本次回答</button>
    <button v-if="status === 'submitted' || status === 'running'" type="button" class="text-button ai-stop-button" @click="emit('cancel')">停止</button>
  </div>
</template>
