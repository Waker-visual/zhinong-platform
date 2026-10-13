<script setup>
import { onUnmounted, ref, watch } from 'vue';
import { api } from '../api';
import AppIcon from '../ui/AppIcon.vue';
import { createFollowUpLoader } from './follow-up-request.js';

const props = defineProps({
  farmId: String, conversationId: String, messageId: String,
  messages: { type: Array, required: true },
  enabled: Boolean, modelEnabled: Boolean,
});
const emit = defineEmits(['choose', 'updated']);
const state = ref({ suggestions: [], source: 'context' });
const loader = createFollowUpLoader({
  request: (context, signal) => api(`/ai/conversations/${encodeURIComponent(context.conversationId)}/follow-ups`,
    'POST', { messageId: context.messageId }, { signal, timeoutMs: 6500, trackConnection: false }),
  update: value => { state.value = value; emit('updated'); },
});
watch(() => [props.enabled, props.farmId, props.conversationId, props.messageId, props.modelEnabled], () => {
  if (!props.enabled) { loader.cancel(); return; }
  loader.load({ farmId: props.farmId, conversationId: props.conversationId, messageId: props.messageId },
    props.messages, props.modelEnabled);
}, { immediate: true });
function choose(question) { loader.freeze(); emit('choose', question); }
onUnmounted(() => loader.dispose());
</script>

<template>
  <section v-if="enabled && state.suggestions.length" class="ai-follow-ups" aria-label="下一步对话建议" @focusin="loader.freeze()" @pointerdown="loader.freeze()">
    <div class="ai-follow-up-heading"><AppIcon name="sprout" /><strong>可以接着问</strong><span>{{ state.source === 'model' ? '小禾为本轮生成' : '根据当前话题推荐' }}</span></div>
    <div class="ai-follow-up-options">
      <button v-for="suggestion in state.suggestions" :key="suggestion" type="button" :title="'填入问题：'+suggestion" @click="choose(suggestion)">
        <span>{{ suggestion }}</span><AppIcon name="arrowRight" />
      </button>
    </div>
    <p>点选填入输入框，编辑后发送</p>
  </section>
</template>

<style scoped>
.ai-follow-ups { margin-top:20px;padding-top:16px;border-top:1px solid var(--border); }
.ai-follow-up-heading { display:flex;align-items:center;gap:7px;flex-wrap:wrap;margin-bottom:10px; }
.ai-follow-up-heading>.app-icon { width:19px;height:19px;color:var(--brand); }
.ai-follow-up-heading strong { font-size:13px;font-weight:600;color:var(--text); }
.ai-follow-up-heading>span { font-size:11px;color:var(--label-fg);margin-left:3px; }
.ai-follow-up-options { display:flex;gap:8px;flex-wrap:wrap; }
.ai-follow-up-options button { display:flex;align-items:center;justify-content:space-between;gap:12px;max-width:100%;min-height:40px;padding:9px 14px;border:1px solid transparent;border-radius:20px;background:var(--subtle-bg);color:var(--text);font-size:13px;line-height:1.55;text-align:left; }
.ai-follow-up-options button span { overflow-wrap:anywhere; }
.ai-follow-up-options button:hover { background:var(--brand-soft);border-color:var(--border);color:var(--brand); }
.ai-follow-up-options button:focus-visible { outline:2px solid var(--focus-ring);outline-offset:3px; }
.ai-follow-up-options .app-icon { width:15px;height:15px;flex:none; }
.ai-follow-ups>p { font-size:11px;color:var(--label-fg);margin:9px 0 0; }
@media(max-width:600px) { .ai-follow-up-options { flex-direction:column; }.ai-follow-up-options button { width:100%;min-height:44px;border-radius:16px; } }
</style>
