import { ref } from "vue";
export const colorMode = ref("LIGHT");
let preference = { themeMode: "SYSTEM", accent: "FOREST" };
let scheduleTimer = null;

// 系统模式按本地时区切换：标准时 07:00–18:00 为日间，夏令时采用 06:00–20:00。
function isDaylightSavingTime(date) {
  const januaryOffset = new Date(date.getFullYear(), 0, 1).getTimezoneOffset();
  const julyOffset = new Date(date.getFullYear(), 6, 1).getTimezoneOffset();
  return date.getTimezoneOffset() < Math.max(januaryOffset, julyOffset);
}
function scheduledMode(date) {
  const minutes = date.getHours() * 60 + date.getMinutes();
  const daylight = isDaylightSavingTime(date);
  const start = daylight ? 6 * 60 : 7 * 60;
  const end = daylight ? 20 * 60 : 18 * 60;
  return minutes >= start && minutes < end ? "LIGHT" : "DARK";
}
function scheduleNextRender() {
  clearTimeout(scheduleTimer);
  if (preference.themeMode !== "SYSTEM") return;
  const now = new Date();
  const delay = Math.max(
    1000,
    (60 - now.getSeconds()) * 1000 - now.getMilliseconds() + 50,
  );
  scheduleTimer = window.setTimeout(render, delay);
}
function render() {
  colorMode.value =
    preference.themeMode === "SYSTEM"
      ? scheduledMode(new Date())
      : preference.themeMode;
  document.documentElement.dataset.theme = colorMode.value.toLowerCase();
  document.documentElement.dataset.accent = preference.accent.toLowerCase();
  scheduleNextRender();
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
