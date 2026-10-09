// 朗读（speechSynthesis）的最小封装：全局只允许一个 utterance 同时播放，所有
// AiMessageActions 实例共享同一个 speakingId，谁在说话、按钮状态是否要切回“朗读”都由此驱动。
// 依赖浏览器的 window/SpeechSynthesis，不在 node:test 环境里被直接测试或导入。
import { ref } from "vue";

export const speakingId = ref(null);

export function speechSupported() {
  return typeof window !== "undefined" && "speechSynthesis" in window && typeof window.SpeechSynthesisUtterance === "function";
}

export function speak(id, text) {
  if (!speechSupported() || !text) return;
  window.speechSynthesis.cancel(); // 保证同一时间只有一个 utterance 在播放
  const utterance = new window.SpeechSynthesisUtterance(text);
  utterance.lang = "zh-CN";
  const clear = () => { if (speakingId.value === id) speakingId.value = null; };
  utterance.onend = clear;
  utterance.onerror = clear;
  speakingId.value = id;
  window.speechSynthesis.speak(utterance);
}

export function stopSpeaking(id) {
  if (!speechSupported()) return;
  if (id == null || speakingId.value === id) {
    window.speechSynthesis.cancel();
    speakingId.value = null;
  }
}
