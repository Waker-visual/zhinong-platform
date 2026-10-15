<script setup>
import { computed, nextTick, onUnmounted, ref, useId, watch } from 'vue';
import './sprout-companion.css';

const props = defineProps({
  state: { type: String, default: 'idle' },
  message: { type: String, default: '' },
  compact: Boolean,
  disabled: Boolean,
  suggestedLabel: { type: String, default: '' },
  interactive: { type: Boolean, default: true },
});
const emit = defineEmits(['suggest', 'activate']);
const bubbleId = useId();
const character = ref(null);
const expanded = ref(true), playing = ref(false), greeting = ref('');
const greetings = [
  '收到你的招呼啦！想从哪一块田聊起？',
  '叶子精神起来了。把想了解的农事告诉我吧。',
  '一起把问题说具体，我会帮你找依据、理思路。',
];
const stateMessages = {
  idle: '我是小禾，陪你看农情、理农事。今天想了解什么？',
  loading: '正在读取农场资料，等资料到齐再一起看看。',
  thinking: '正在整理问题和依据，请稍等一下。',
  answering: '正在整理回答，内容会陆续显示。',
  done: '回答已整理好。可以接着追问依据或具体做法。',
  stopped: '已停止这次回答。可以调整问题后再试。',
  error: '这次没有顺利完成，请查看提示后重试。',
};
const currentState = computed(() => Object.hasOwn(stateMessages, props.state) ? props.state : 'idle');
const words = computed(() => currentState.value === 'idle' && greeting.value
  ? greeting.value
  : (props.message || stateMessages[currentState.value]));
const busy = computed(() => ['loading', 'thinking', 'answering'].includes(currentState.value));
let greetingIndex = 0, playTimer, greetingTimer, alive = true;

function handleCharacterClick() {
  if (!props.interactive) return;
  greet();
  emit('activate');
}
async function greet() {
  expanded.value = true;
  clearTimeout(playTimer);
  playing.value = false;
  if (currentState.value === 'idle') {
    greeting.value = greetings[greetingIndex++ % greetings.length];
    clearTimeout(greetingTimer);
    greetingTimer = setTimeout(() => { greeting.value = ''; }, 6500);
  }
  await nextTick();
  if (!alive) return;
  playing.value = true;
  playTimer = setTimeout(() => { playing.value = false; }, 780);
}
async function toggleBubble() {
  expanded.value = !expanded.value;
  await nextTick();
  if (alive) character.value?.focus();
}

watch(() => [props.state, props.message], ([state]) => {
  clearTimeout(greetingTimer);
  greeting.value = '';
  // A fresh run or failure should stay visible even if an earlier greeting was folded.
  if (['loading', 'thinking', 'answering', 'error'].includes(state)) expanded.value = true;
});
onUnmounted(() => {
  alive = false;
  clearTimeout(playTimer);
  clearTimeout(greetingTimer);
});
</script>

<template>
  <component :is="interactive ? 'section' : 'span'" class="sprout-companion" :class="{ 'is-compact': compact, 'is-playing': playing, 'is-folded': !expanded, 'is-busy': busy }" :data-state="currentState" :aria-label="interactive ? '小禾农场助手' : undefined" :aria-hidden="interactive ? undefined : 'true'">
    <component :is="interactive ? 'button' : 'span'" ref="character" class="sprout-character" :type="interactive ? 'button' : undefined" :tabindex="interactive ? undefined : -1" :aria-label="interactive ? '和小禾打个招呼' : undefined" :aria-expanded="interactive ? expanded : undefined" :aria-controls="interactive ? bubbleId : undefined" :title="interactive ? '点点小禾，打个招呼' : undefined" :aria-hidden="interactive ? undefined : 'true'" @click="handleCharacterClick">
      <svg class="sprout-art" viewBox="0 0 150 168" aria-hidden="true" focusable="false">
        <ellipse class="sprout-ground" cx="76" cy="154" rx="33" ry="5" />
        <g class="sprout-figure">
          <g class="sprout-leaves">
            <path class="sprout-leaf-back" d="M76 71C61 51 62 27 74 12C89 35 91 53 76 71Z" />
            <path class="sprout-leaf-light" d="M76 70C53 70 31 51 27 31C51 33 72 45 76 70Z" />
            <path class="sprout-leaf-front" d="M75 69C78 42 98 25 124 24C119 47 102 67 75 69Z" />
            <path class="sprout-leaf-vein" d="M75 68C72 54 71 38 74 24M73 66C62 53 49 44 36 38M78 63C91 47 101 38 114 31" />
          </g>
          <path class="sprout-limb sprout-foot" d="M63 140L60 150L53 150M88 140L91 150L98 150" />
          <path class="sprout-limb sprout-arm-left" d="M48 107Q33 106 34 94" />
          <path class="sprout-limb sprout-arm-right" d="M103 107Q115 104 118 93" />
          <path class="sprout-body" d="M75 64C60 68 43 91 43 111C43 133 55 145 75 145C96 145 108 132 108 111C108 90 91 68 75 64Z" />
          <path class="sprout-body-light" d="M57 94C52 102 51 111 53 118" />
          <ellipse class="sprout-cheek" cx="55" cy="119" rx="6" ry="3.7" />
          <ellipse class="sprout-cheek" cx="97" cy="119" rx="6" ry="3.7" />
          <g class="sprout-eyes">
            <ellipse cx="63" cy="109" rx="2.8" ry="4.1" />
            <ellipse cx="89" cy="109" rx="2.8" ry="4.1" />
          </g>
          <path v-if="currentState === 'error'" class="sprout-mouth" d="M71 124Q76 120 81 124" />
          <path v-else-if="busy" class="sprout-mouth" d="M73 122Q76 124 79 122" />
          <path v-else class="sprout-mouth" d="M69 121Q76 129 83 121" />
          <path class="sprout-grain" d="M73 133L77 133" />
        </g>
        <g class="sprout-hello" aria-hidden="true">
          <path d="M123 72L128 67M126 82L134 81" />
        </g>
      </svg>
      <span v-if="!compact" class="sprout-touch-hint">点点我，打个招呼</span>
    </component>

    <div v-if="interactive && expanded" :id="bubbleId" class="sprout-bubble">
      <div class="sprout-bubble-heading">
        <span class="sprout-name">小禾 <span>· 农场小助手</span></span>
        <button class="sprout-fold" type="button" aria-label="收起小禾对话框" :aria-controls="bubbleId" aria-expanded="true" title="收起对话框" @click="toggleBubble">
          <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M4 8h8" /></svg>
        </button>
      </div>
      <p class="sprout-words" role="status" aria-live="polite" aria-atomic="true">{{ words }}</p>
      <button v-if="suggestedLabel" class="sprout-suggest" type="button" :disabled="disabled || busy" @click="emit('suggest')">
        <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M4 11c5 1 9-3 9-8C8 3 3 5 4 11Zm0 0-2 3M4 11l5-5" /></svg>
        {{ suggestedLabel }}
      </button>
    </div>
    <button v-else-if="interactive" class="sprout-unfold" type="button" :aria-controls="bubbleId" aria-expanded="false" @click="toggleBubble">听小禾说 <span aria-hidden="true">···</span></button>
  </component>
</template>
