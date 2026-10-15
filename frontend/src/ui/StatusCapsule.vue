<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { connection } from "../api";

// 顶栏状态胶囊：取代原来固定为绿色的“本地服务”。状态来自真实请求结果。
const emit = defineEmits(["refresh"]);
const syncing = ref(false),
  announcement = ref("");
let delay;
// 请求超过 0.4 秒才显示“同步中”，避免每次切页都闪烁
watch(
  () => connection.inflight,
  (count) => {
    clearTimeout(delay);
    if (count > 0) delay = setTimeout(() => (syncing.value = true), 400);
    else syncing.value = false;
  },
);
const state = computed(() => {
  if (connection.state === "offline") return "offline";
  if (syncing.value) return "syncing";
  if (connection.state === "slow") return "slow";
  return connection.state === "online" ? "online" : "unknown";
});
const time = computed(() =>
  connection.lastSync
    ? connection.lastSync.toLocaleTimeString("zh-CN", {
        hour: "2-digit",
        minute: "2-digit",
        hour12: false,
      })
    : "",
);
const text = computed(
  () =>
    ({
      offline: "连接中断 · 点击重试",
      slow: "响应超时 · 点击重试",
      syncing: "正在同步…",
      online: `已同步 ${time.value}`,
      unknown: "正在连接…",
    })[state.value],
);
const title = computed(() =>
  state.value === "offline"
    ? "无法连接本地服务。确认服务已启动后点击重试。"
    : state.value === "slow"
      ? `有请求超过等待期限，本地服务可能仍在处理。最近一次成功通信：${time.value || "尚未完成"}。点击重新读取当前页面数据。`
      : `最近一次与本地服务通信：${time.value || "尚未完成"}。点击刷新当前页面数据。`,
);
// 只在断开、超时和恢复时播报，平常的同步不打扰读屏用户
watch(
  () => connection.state,
  (next, previous) => {
    if (next === "offline") announcement.value = "与本地服务的连接已中断";
    else if (next === "slow") announcement.value = "本地服务响应超时，部分数据可能未更新";
    else if (next === "online" && previous === "offline")
      announcement.value = "已重新连接本地服务";
    else if (next === "online" && previous === "slow")
      announcement.value = "本地服务已恢复响应";
  },
);
function offline() {
  connection.state = "offline";
}
function online() {
  emit("refresh");
}
onMounted(() => {
  window.addEventListener("offline", offline);
  window.addEventListener("online", online);
});
onBeforeUnmount(() => {
  clearTimeout(delay);
  window.removeEventListener("offline", offline);
  window.removeEventListener("online", online);
});
</script>

<template>
  <button
    type="button"
    :class="['status-capsule', state]"
    :title="title"
    :aria-label="text"
    @click="emit('refresh')"
  >
    <i class="connection-dot" aria-hidden="true"></i
    ><span class="capsule-full">{{ text }}</span
    ><span class="capsule-short" aria-hidden="true">离线</span>
  </button>
  <span class="sr-only" aria-live="polite">{{ announcement }}</span>
</template>
