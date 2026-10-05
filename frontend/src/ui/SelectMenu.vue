<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";

const props = defineProps({
  modelValue: { type: [String, Number, Boolean], default: "" },
  options: { type: Array, default: () => [] },
  disabled: Boolean,
  required: Boolean,
  ariaLabel: { type: String, default: "选择选项" },
  placeholder: { type: String, default: "请选择" },
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
function toggle() {
  if (props.disabled) return;
  open.value = !open.value;
  if (open.value)
    nextTick(() =>
      root.value
        ?.querySelector(".select-menu-menu button.selected:not(:disabled)")
        ?.focus(),
    );
}
function choose(option) {
  if (props.disabled || option.disabled) return;
  emit("update:modelValue", option.value);
  emit("change", option.value);
  open.value = false;
}
function moveOption(event, index) {
  if (!open.value || !["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key))
    return;
  event.preventDefault();
  const buttons = [...root.value.querySelectorAll(".select-menu-menu button:not(:disabled)")];
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
  <div ref="root" class="select-menu" :class="{ open, disabled }">
    <input
      class="select-menu-value-input"
      type="text"
      :value="modelValue ?? ''"
      :required="required"
      tabindex="-1"
      aria-hidden="true"
      @invalid="open = true"
    />
    <button
      type="button"
      class="select-menu-trigger"
      :aria-label="ariaLabel"
      aria-haspopup="listbox"
      :aria-expanded="open"
      :disabled="disabled"
      @click="toggle"
      @keydown="onKey"
    >
      <span class="select-menu-value">{{ current?.label || placeholder }}</span>
      <span class="select-menu-chevron" aria-hidden="true"></span>
    </button>
    <div v-if="open" class="select-menu-menu" role="listbox" :aria-label="ariaLabel">
      <button
        v-for="(option, index) in options"
        :key="String(option.value)"
        type="button"
        role="option"
        :aria-selected="option.value === modelValue"
        :disabled="option.disabled"
        :class="{ selected: option.value === modelValue }"
        @click="choose(option)"
        @keydown="moveOption($event, index)"
      >
        {{ option.label }}
      </button>
    </div>
  </div>
</template>
