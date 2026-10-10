<script setup>
import { computed, onBeforeUnmount, ref, watch } from "vue";
import AppIcon from "../ui/AppIcon.vue";
const props = defineProps({ device: { type: Object, required: true } });
const frame = ref(null),
  attempt = ref(0),
  state = ref("loading"),
  fullError = ref("");
let timeout;
const media = computed(() => props.device.camera || { mode: "NONE" });
const demo = computed(() => media.value.mode === "DEMO_IMAGE");
const enabled = computed(() => props.device.lifecycle === "ACTIVE");
const playable = computed(
  () =>
    enabled.value && media.value.mode !== "NONE" && !!media.value.playbackUrl,
);
const label = computed(() =>
  media.value.mode === "NONE"
    ? "未配置画面来源"
    : demo.value
      ? "演示画面 · 非实时"
      : media.value.mode === "VIDEO"
        ? "视频画面"
        : "图片源 · 非实时视频",
);
function reset() {
  clearTimeout(timeout);
  state.value = "loading";
  fullError.value = "";
  attempt.value++;
  if (playable.value)
    timeout = setTimeout(() => {
      if (state.value === "loading") state.value = "error";
    }, 15000);
}
function loaded() {
  clearTimeout(timeout);
  state.value = "ready";
}
function failed() {
  clearTimeout(timeout);
  state.value = "error";
}
async function fullscreen() {
  try {
    if (document.fullscreenElement === frame.value)
      await document.exitFullscreen();
    else if (frame.value?.requestFullscreen)
      await frame.value.requestFullscreen();
    else fullError.value = "当前浏览器不支持全屏，请放大页面查看。";
  } catch {
    fullError.value = "未能进入全屏，请重试。";
  }
}
watch(
  () =>
    [
      props.device.id,
      props.device.lifecycle,
      media.value.mode,
      media.value.playbackUrl,
    ].join("|"),
  reset,
  { immediate: true },
);
onBeforeUnmount(() => clearTimeout(timeout));
</script>

<template>
  <section class="camera-player" aria-label="摄像头画面">
    <div class="camera-frame" ref="frame" :data-media-state="state">
      <template v-if="playable && state !== 'error'">
        <video
          v-if="media.mode === 'VIDEO'"
          :key="attempt"
          :src="media.playbackUrl"
          controls
          playsinline
          preload="metadata"
          referrerpolicy="no-referrer"
          @loadedmetadata="loaded"
          @error="failed"
          :aria-label="device.name + '视频'"
        />
        <img
          v-else
          :key="attempt"
          :src="media.playbackUrl"
          referrerpolicy="no-referrer"
          @load="loaded"
          @error="failed"
          :alt="
            device.farmName +
            ' · ' +
            device.name +
            (demo ? '，演示画面，非实时' : '，配置的图片源')
          "
        />
      </template>
      <div
        v-if="!playable || state === 'error'"
        class="camera-placeholder"
        role="status"
      >
        <AppIcon name="camera" />
        <strong>{{
          !enabled
            ? device.lifecycle === "MAINTENANCE"
              ? "摄像头维护中"
              : "摄像头已停用"
            : media.mode === "NONE"
              ? "尚未配置画面来源"
              : "画面加载失败"
        }}</strong>
        <p>
          {{
            !enabled
              ? "启用后可继续查看画面。"
              : media.mode === "NONE"
                ? "请在设备台账中为这台摄像头配置画面来源。"
                : "请检查画面地址、网络及浏览器支持的视频格式，然后重新加载。"
          }}
        </p>
      </div>
      <div class="camera-frame-caption">
        <span>{{ device.farmName }} · {{ device.plotName || "公共区域" }}</span>
        <strong>{{ label }}</strong>
      </div>
    </div>
    <div class="camera-player-tools">
      <span role="status">{{
        !playable
          ? "未播放"
          : state === "ready"
            ? "画面已加载"
            : state === "error"
              ? "加载失败"
              : "正在加载画面…"
      }}</span>
      <div>
        <button @click="reset" :disabled="!playable">
          <AppIcon name="refresh" />重新加载</button
        ><button @click="fullscreen" :disabled="!playable || state !== 'ready'">
          全屏查看
        </button>
      </div>
    </div>
    <p v-if="fullError" role="alert" class="error">{{ fullError }}</p>
    <p class="camera-source-note">
      {{
        demo
          ? "当前画面与设备状态为演示数据，可在设备台账配置独立图片或视频地址。"
          : "画面与设备上报独立更新；视频实时性由接入源提供。"
      }}
    </p>
  </section>
</template>

<style scoped>
.camera-player {
  min-width: 0;
}
.camera-frame {
  position: relative;
  width: 100%;
  min-width: 0;
  aspect-ratio: 16 / 9;
  overflow: hidden;
  border-radius: 10px;
  background: var(--sunken);
}
.camera-frame > img,
.camera-frame > video {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.camera-frame:fullscreen {
  width: 100%;
  height: 100%;
  border-radius: 0;
  background: var(--nav-bg);
}
.camera-frame-caption {
  position: absolute;
  top: 12px;
  left: 12px;
  right: 12px;
  display: flex;
  flex-wrap: wrap;
  align-items: start;
  justify-content: space-between;
  gap: 8px;
  pointer-events: none;
}
.camera-frame-caption span,
.camera-frame-caption strong {
  color: var(--on-image);
  background: var(--image-scrim);
  border-radius: 5px;
  padding: 6px 9px;
  font-size: 11px;
  font-weight: 500;
}
.camera-placeholder {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 58px 24px 24px;
  text-align: center;
  gap: 10px;
}
.camera-placeholder .app-icon {
  width: 30px;
  height: 30px;
}
.camera-placeholder p {
  font-size: 12px;
  color: var(--muted);
  margin: 0;
  max-width: 420px;
}
.camera-player-tools {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 12px;
  font-size: 12px;
}
.camera-player-tools > div {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.camera-player-tools button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.camera-source-note {
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
  margin-bottom: 0;
}
@media (max-width: 600px) {
  .camera-frame {
    aspect-ratio: auto;
    height: 240px;
  }
  .camera-frame-caption {
    top: 8px;
    left: 8px;
    right: 8px;
  }
}
</style>
