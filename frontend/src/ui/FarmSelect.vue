<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";

const props = defineProps({
  modelValue: { type: String, default: "" },
  options: { type: Array, default: () => [] },
  disabled: Boolean,
});
const emit = defineEmits(["update:modelValue", "change"]);
const root = ref(null);
const open = ref(false);
const current = computed(
  () => props.options.find((option) => option.value === props.modelValue) || null,
);

function closeOnOutside(event) {
  if (root.value && !root.value.contains(event.target)) open.value = false;
}
function choose(option) {
  if (props.disabled || option.disabled) return;
  emit("update:modelValue", option.value);
  emit("change", option.value);
  open.value = false;
}
function toggle() {
  if (props.disabled) return;
  open.value = !open.value;
  if (open.value)
    nextTick(() =>
      root.value
        ?.querySelector(".farm-select-menu button.selected:not(:disabled)")
        ?.focus(),
    );
}
function moveOption(event, index) {
  if (!open.value || !["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key))
    return;
  event.preventDefault();
  const buttons = [...root.value.querySelectorAll(".farm-select-menu button:not(:disabled)")];
  if (!buttons.length) return;
  const next =
    event.key === "Home"
      ? 0
      : event.key === "End"
        ? buttons.length - 1
        : (index + (event.key === "ArrowDown" ? 1 : -1) + buttons.length) % buttons.length;
  buttons[next]?.focus();
}
function onKey(event) {
  if (props.disabled) return;
  if (event.key === "Escape") {
    open.value = false;
    return;
  }
  if (event.key === "Enter" || event.key === " ") {
    event.preventDefault();
    toggle();
  }
}
onMounted(() => document.addEventListener("pointerdown", closeOnOutside));
onBeforeUnmount(() => document.removeEventListener("pointerdown", closeOnOutside));
</script>

<template>
  <div
    ref="root"
    class="topbar-farm farm-select"
    :class="{ open, disabled }"
  >
    <span class="tenant-dot" aria-hidden="true"></span>
    <button
      type="button"
      class="farm-select-trigger"
      aria-label="当前农场"
      aria-haspopup="listbox"
      :aria-expanded="open"
      :disabled="disabled"
      @click="toggle"
      @keydown="onKey"
    >
      <span class="farm-select-value">{{ current?.label || "选择农场" }}</span>
      <span class="farm-select-chevron" aria-hidden="true"></span>
    </button>
    <div v-if="open" class="farm-select-menu" role="listbox" aria-label="农场选项">
      <button
        v-for="option in options"
        :key="option.value"
        type="button"
        role="option"
        :aria-selected="option.value === modelValue"
        :disabled="option.disabled"
        :class="{ selected: option.value === modelValue }"
        @click="choose(option)"
        @keydown="moveOption($event, options.indexOf(option))"
      >
        {{ option.label }}
      </button>
    </div>
  </div>
</template>
