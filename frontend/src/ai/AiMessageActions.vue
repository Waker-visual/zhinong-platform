<script setup>
// 消息操作栏：常驻显示（不是 hover 才出现），助手消息有复制/从此处分支/朗读，用户消息只有复制；
// 行尾始终显示时间。复制/朗读都是纯前端行为；分支调用新增的 POST /conversations/{id}/branch。
import { ref, computed, onUnmounted } from 'vue';
import AppIcon from '../ui/AppIcon.vue';
import { api } from '../api.js';
import { speak, stopSpeaking, speakingId, speechSupported } from './speech.js';
import { markdownToPlainText } from './markdown.js';

const props = defineProps({
  id: { type: String, default: '' }, // 消息已持久化的 id；流式占位消息还没有 id，分支/朗读按钮据此禁用或隐藏
  text: { type: String, default: '' },
  role: { type: String, required: true }, // 'user' | 'assistant'
  time: { type: Object, default: null }, // formatMessageTime() 的结果，还没有持久化时间就传 null
  conversationId: { type: String, default: '' },
});
const emit = defineEmits(['branched']);

const copied = ref(false);
const announce = ref('');
const branching = ref(false);
const canSpeak = speechSupported();
// 朗读/复制都应该是纯正文，不包含 Markdown 语法标记（粗体、表格竖线、列表符号等）。
const plainText = computed(() => markdownToPlainText(props.text || ''));
const speaking = computed(() => !!props.id && speakingId.value === props.id);

async function copy() {
  let ok = true;
  try {
    await navigator.clipboard.writeText(plainText.value);
  } catch {
    ok = fallbackCopy(plainText.value);
  }
  if (!ok) return; // 两种复制方式都失败：静默放弃，不打断用户，也不谎称已复制
  copied.value = true;
  announce.value = '已复制';
  setTimeout(() => { copied.value = false; }, 1500);
}
function fallbackCopy(value) {
  try {
    const area = document.createElement('textarea');
    area.value = value;
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    const ok = document.execCommand('copy');
    area.remove();
    return ok;
  } catch {
    return false;
  }
}
function toggleSpeak() {
  if (!props.id) return;
  if (speaking.value) stopSpeaking(props.id);
  else speak(props.id, plainText.value);
}
async function branch() {
  if (!props.id || branching.value) return;
  branching.value = true;
  try {
    const conversation = await api(`/ai/conversations/${props.conversationId}/branch`, 'POST', { messageId: props.id });
    emit('branched', conversation.id);
  } finally {
    branching.value = false;
  }
}
onUnmounted(() => { if (props.id) stopSpeaking(props.id); }); // 切换对话/卸载时不能让朗读继续播放
</script>
<template>
  <div class="ai-message-actions">
    <button type="button" class="ai-action-btn" :class="{ copied }" :aria-label="copied ? '已复制' : '复制'" :data-tooltip="copied ? '已复制' : '复制'" @click="copy">
      <AppIcon :name="copied ? 'check' : 'copy'" />
    </button>
    <template v-if="role === 'assistant'">
      <button type="button" class="ai-action-btn" aria-label="从此处分支" data-tooltip="从此处分支" :disabled="!id || branching" @click="branch">
        <AppIcon name="branch" />
      </button>
      <button v-if="canSpeak" type="button" class="ai-action-btn" :aria-label="speaking ? '停止朗读' : '朗读'" :data-tooltip="speaking ? '停止朗读' : '朗读'" @click="toggleSpeak">
        <AppIcon :name="speaking ? 'speakerOff' : 'speaker'" />
      </button>
    </template>
    <time v-if="time" class="ai-message-time" :datetime="time.iso" :data-tooltip="time.full">{{ time.display }}</time>
    <span class="sr-only" role="status" aria-live="polite">{{ announce }}</span>
  </div>
</template>
