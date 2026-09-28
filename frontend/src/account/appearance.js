import { ref } from "vue";
export const colorMode = ref("LIGHT");
let preference = { themeMode: "SYSTEM", accent: "FOREST" };
const system = window.matchMedia("(prefers-color-scheme: dark)");
function render() {
  colorMode.value =
    preference.themeMode === "SYSTEM"
      ? system.matches
        ? "DARK"
        : "LIGHT"
      : preference.themeMode;
  document.documentElement.dataset.theme = colorMode.value.toLowerCase();
  document.documentElement.dataset.accent = preference.accent.toLowerCase();
}
system.addEventListener("change", render);
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
