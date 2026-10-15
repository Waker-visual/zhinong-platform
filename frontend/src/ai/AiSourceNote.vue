<script setup>
// 回答下方的页面资料来源记录：只展示服务端写入的安全标签与摘要（kind=source），
// 让用户知道这条回答参考了哪一页、哪个对象；没有来源记录的老消息不显示。
import { computed } from 'vue';
import { pageSources } from './floating-assistant-model.js';
const props = defineProps({ activities: { type: Array, default: () => [] }, compact: Boolean });
const sources = computed(() => pageSources(props.activities));
</script>
<template>
  <ul v-if="sources.length" class="ai-source-note" :class="{ compact }" aria-label="本条回答参考的页面资料">
    <li v-for="source in sources" :key="source.id"><strong>{{ source.label }}</strong><span v-if="source.summary">{{ source.summary }}</span></li>
  </ul>
</template>
<style>
.ai-source-note { display: grid; gap: 3px; margin: 8px 0 0; padding: 7px 10px; border-left: 2px solid var(--border-strong); background: var(--subtle-bg); color: var(--label-fg); font-size: 12px; line-height: 1.6; list-style: none; }
.ai-source-note li { min-width: 0; overflow-wrap: anywhere; }
.ai-source-note strong { margin-right: 6px; color: var(--text-2); font-weight: 600; }
.ai-source-note.compact { margin-top: 6px; padding: 5px 9px; font-size: 11px; }
</style>
