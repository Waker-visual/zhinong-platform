<script setup>
// 提交/读取/生成/完成/出错的状态条；role=status 供屏幕阅读器播报，出错时提供重试入口（停止入口在输入框的发送按钮位置）。
// 运行中额外显示一个每秒刷新的本地计时“已用 Ns”——只在客户端走秒表，不依赖服务端再发时间戳。
import { computed, onMounted, onUnmounted, watch, ref } from 'vue';
import { streamingStatusText, formatElapsedStatus } from './agent-events.js';
const props = defineProps({ status: { type: String, default: 'submitted' }, diagnostic: { type: String, default: '' }, canRetry: { type: Boolean, default: false }, usingSyncFallback: { type: Boolean, default: false } });
const emit = defineEmits(['retry']);
const elapsedSeconds = ref(0);
let start = Date.now();
let timer = null;
function running() { return props.status === 'submitted' || props.status === 'running'; }
function startTimer() {
  stopTimer();
  start = Date.now();
  elapsedSeconds.value = 0;
  timer = setInterval(() => { elapsedSeconds.value = (Date.now() - start) / 1000; }, 1000);
}
function stopTimer() {
  if (timer) { clearInterval(timer); timer = null; }
}
onMounted(() => { if (running()) startTimer(); });
watch(() => props.status, () => { if (running()) { if (!timer) startTimer(); } else stopTimer(); });
onUnmounted(stopTimer);
const text = computed(() => streamingStatusText(props.status, props.diagnostic, props.usingSyncFallback));
</script>
<template>
  <div class="ai-streaming-status" :class="status" role="status" aria-live="polite">
    <!-- 运行中：3×3 点阵，外圈 8 格依次点亮形成环绕（orbit），中心格常暗 -->
    <span v-if="running()" class="ai-orbit" aria-hidden="true"><i v-for="n in 9" :key="n"></i></span>
    <span v-else-if="status !== 'cancelled'" class="ai-status-dot" aria-hidden="true"></span>
    <span class="ai-status-text">{{ text }}<template v-if="running()"> · <span class="ai-status-elapsed">{{ formatElapsedStatus(elapsedSeconds) }}</span></template></span>
    <button v-if="status === 'error' && canRetry" type="button" class="text-button" @click="emit('retry')">重试本次回答</button>
  </div>
</template>
