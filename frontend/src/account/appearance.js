import { ref } from "vue";
import { scheduledMode } from "./scheduled-theme.js";
export const colorMode = ref("LIGHT");
let preference = { themeMode: "SYSTEM", accent: "FOREST" };
let scheduleTimer = null;
let loginSceneMode = null;

// 系统模式按本地时区切换：标准时 07:00–18:00 为日间，夏令时采用 06:00–20:00。
function scheduleNextRender() {
  clearTimeout(scheduleTimer);
  if (loginSceneMode || preference.themeMode !== "SYSTEM") return;
  const now = new Date();
  const delay = Math.max(
    1000,
    (60 - now.getSeconds()) * 1000 - now.getMilliseconds() + 50,
  );
  scheduleTimer = window.setTimeout(render, delay);
}
function render() {
  colorMode.value = loginSceneMode ?? (
    preference.themeMode === "SYSTEM"
      ? scheduledMode(new Date())
      : preference.themeMode);
  document.documentElement.dataset.theme = colorMode.value.toLowerCase();
  document.documentElement.dataset.accent = preference.accent.toLowerCase();
  scheduleNextRender();
}
export function setLoginSceneMode(mode) {
  if(loginSceneMode===mode)return;
  loginSceneMode=mode;render();
}
export function clearLoginSceneMode() {
  loginSceneMode=null;render();
}
export function applyAppearance(value, persist = true) {
  preference = {
    themeMode: value?.themeMode || "SYSTEM",
    accent: value?.accent || "FOREST",
  };
  render();
  if (persist)
    localStorage.setItem("zhinong-appearance", JSON.stringify(preference));
}
try {
  applyAppearance(
    JSON.parse(localStorage.getItem("zhinong-appearance") || "null"),
    false,
  );
} catch {
  applyAppearance(null, false);
}
