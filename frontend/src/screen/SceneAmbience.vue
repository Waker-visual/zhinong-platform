<script setup>
import { onBeforeUnmount, onMounted, ref } from "vue";
import { createSceneAmbience } from "./scene-ambience.js";
import AppIcon from "../ui/AppIcon.vue";

const playing = ref(false),
  starting = ref(false),
  volume = ref(24),
  message = ref("");
const ambience = createSceneAmbience({
  onPlaybackChange(value) {
    playing.value = value;
  },
});

async function toggle() {
  message.value = "";
  if (playing.value) {
    ambience.pause();
    return;
  }
  starting.value = true;
  try {
    await ambience.play();
  } catch {
    message.value = "音乐暂不可用，请再次点击或检查浏览器声音权限。";
  } finally {
    starting.value = false;
  }
}

function adjustVolume(event) {
  volume.value = Number(event.target.value);
  ambience.setVolume(volume.value / 100);
}

function pauseWhenHidden() {
  if (document.hidden) ambience.pause();
}

onMounted(() => document.addEventListener("visibilitychange", pauseWhenHidden));
onBeforeUnmount(() => {
  document.removeEventListener("visibilitychange", pauseWhenHidden);
  ambience.dispose();
});
</script>

<template>
  <div class="scene-ambience" role="group" aria-label="大屏背景音乐">
    <button
      type="button"
      class="scene-ambience__toggle"
      :aria-pressed="playing"
      :disabled="starting"
      :title="playing ? '暂停舒缓音乐' : '播放舒缓音乐，切换大屏视图时继续播放'"
      @click="toggle"
    >
      <AppIcon :name="playing ? 'speaker' : 'speakerOff'" />
      {{ starting ? "准备音乐" : playing ? "暂停音乐" : "舒缓音乐" }}
    </button>
    <details
      class="scene-ambience__settings"
      @keydown.esc.stop.prevent="$event.currentTarget.open = false"
    >
      <summary aria-label="调整背景音乐音量" :title="`音量 ${volume}%`">
        <span>{{ volume }}%</span><AppIcon name="chevronDown" />
      </summary>
      <label class="scene-ambience__volume">
        <span>音量</span>
        <input
          type="range"
          min="0"
          max="100"
          step="1"
          :value="volume"
          :aria-valuetext="`${volume}%`"
          aria-label="背景音乐音量"
          @input="adjustVolume"
        />
        <output>{{ volume }}%</output>
      </label>
    </details>
    <span v-if="message" class="scene-ambience__message" role="status">{{
      message
    }}</span>
  </div>
</template>

<style scoped>
.scene-ambience {
  position: relative;
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
  color: var(--display-muted);
  font-size: 12px;
  border: 1px solid var(--border-strong);
  border-radius: 9px;
  background: var(--display-lake);
}
.scene-ambience .scene-ambience__toggle {
  min-height: 36px;
  gap: 6px;
  padding: 7px 10px;
  font-size: 12px;
  white-space: nowrap;
  border: 0;
  border-radius: 8px 0 0 8px;
  background: transparent;
}
.scene-ambience .scene-ambience__toggle[aria-pressed="true"] {
  color: var(--display-leaf);
  background: var(--display-leaf-soft);
}
.scene-ambience__settings > summary {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 0 8px;
  min-height: 28px;
  border-left: 1px solid var(--border-strong);
  list-style: none;
  cursor: pointer;
}
.scene-ambience__settings > summary::-webkit-details-marker {
  display: none;
}
.scene-ambience__settings > summary .app-icon {
  width: 12px;
  height: 12px;
}
.scene-ambience__volume {
  position: absolute;
  top: calc(100% + 12px);
  left: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px;
  width: 230px;
  border: 1px solid var(--border-strong);
  border-radius: 10px;
  background: var(--display-lake);
  box-shadow: 0 8px 24px var(--scene-parcel-shadow);
  white-space: nowrap;
}
.scene-ambience__volume input {
  flex: 1;
  min-width: 0;
  width: 100px;
  height: 24px;
  padding: 0;
  accent-color: var(--display-leaf);
  cursor: pointer;
}
.scene-ambience__volume input:focus-visible {
  outline: 2px solid var(--display-gold);
  outline-offset: 4px;
}
.scene-ambience__volume output {
  min-width: 2.4em;
  font-variant-numeric: tabular-nums;
}
.scene-ambience__message {
  position: absolute;
  left: 0;
  top: calc(100% + 12px);
  width: 230px;
  padding: 12px;
  background: var(--display-lake);
  border: 1px solid var(--border-strong);
  border-radius: 10px;
  line-height: 1.5;
}
</style>
