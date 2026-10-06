<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import AppIcon from "./AppIcon.vue";
import "./date-picker.css";

const props = defineProps({
  modelValue: { type: String, default: "" },
  min: { type: String, default: "" },
  max: { type: String, default: "" },
  required: Boolean,
  disabled: Boolean,
  ariaLabel: { type: String, default: "日期" },
});
const emit = defineEmits(["update:modelValue", "change"]);

const root = ref(null);
const control = ref(null);
const open = ref(false);
const popoverStyle = ref({});
const cursor = ref(new Date(new Date().getFullYear(), new Date().getMonth(), 1));
const weekdays = ["一", "二", "三", "四", "五", "六", "日"];

function pad(value) {
  return String(value).padStart(2, "0");
}
function keyOf(date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}
function parse(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value || "")) return null;
  const [year, month, day] = value.split("-").map(Number);
  const date = new Date(year, month - 1, day);
  return date.getFullYear() === year && date.getMonth() === month - 1 && date.getDate() === day
    ? date
    : null;
}
function monthStart(date) {
  return new Date(date.getFullYear(), date.getMonth(), 1);
}
function syncCursor(value) {
  const date = parse(value);
  if (date) cursor.value = monthStart(date);
}
function inBounds(date) {
  const value = keyOf(date);
  return (!props.min || value >= props.min) && (!props.max || value <= props.max);
}

const displayValue = computed(() => {
  const date = parse(props.modelValue);
  return date ? `${date.getFullYear()}/${pad(date.getMonth() + 1)}/${pad(date.getDate())}` : "";
});
const monthLabel = computed(
  () => `${cursor.value.getFullYear()}年${pad(cursor.value.getMonth() + 1)}月`,
);
const days = computed(() => {
  const year = cursor.value.getFullYear();
  const month = cursor.value.getMonth();
  const offset = (new Date(year, month, 1).getDay() + 6) % 7;
  return Array.from({ length: 42 }, (_, index) => {
    const date = new Date(year, month, index - offset + 1);
    return {
      key: keyOf(date),
      date,
      current: date.getMonth() === month,
      selected: keyOf(date) === props.modelValue,
      today: keyOf(date) === keyOf(new Date()),
      disabled: !inBounds(date),
    };
  });
});
const todayDisabled = computed(() => !inBounds(new Date()));

function toggle() {
  if (props.disabled) return;
  if (!open.value) syncCursor(props.modelValue);
  open.value = !open.value;
  if (open.value) nextTick(reposition);
}
function close() {
  open.value = false;
}
function moveMonth(delta) {
  cursor.value = new Date(cursor.value.getFullYear(), cursor.value.getMonth() + delta, 1);
}
function select(day) {
  if (day.disabled) return;
  emit("update:modelValue", day.key);
  emit("change", day.key);
  close();
}
function clear() {
  emit("update:modelValue", "");
  emit("change", "");
  close();
}
function selectToday() {
  if (todayDisabled.value) return;
  select({ key: keyOf(new Date()), disabled: false });
}
function onKeydown(event) {
  if (event.key === "Escape") close();
  if ((event.key === "Enter" || event.key === " ") && !open.value) {
    event.preventDefault();
    toggle();
  }
}
function onDocumentPointerDown(event) {
  if (open.value && root.value && !root.value.contains(event.target)) close();
}
function reposition() {
  if (!open.value || !control.value) return;
  const rect = control.value.getBoundingClientRect();
  const width = Math.min(326, window.innerWidth - 32);
  const left = Math.max(16, Math.min(rect.right - width, window.innerWidth - width - 16));
  popoverStyle.value = {
    left: `${left}px`,
    top: `${Math.max(16, rect.bottom - 1)}px`,
    width: `${width}px`,
  };
}

watch(
  () => props.modelValue,
  (value) => {
    if (!open.value) syncCursor(value);
  },
);
onMounted(() => {
  document.addEventListener("pointerdown", onDocumentPointerDown);
  window.addEventListener("resize", reposition);
  window.addEventListener("scroll", reposition, true);
});
onBeforeUnmount(() => {
  document.removeEventListener("pointerdown", onDocumentPointerDown);
  window.removeEventListener("resize", reposition);
  window.removeEventListener("scroll", reposition, true);
});
</script>

<template>
  <div ref="root" :class="['date-picker', { open, disabled }]" @keydown="onKeydown">
    <div ref="control" class="date-picker-control">
      <input
        class="date-picker-field"
        type="text"
        :value="displayValue"
        :required="required"
        :disabled="disabled"
        :aria-label="ariaLabel"
        aria-haspopup="dialog"
        :aria-expanded="open"
        readonly
        @click="toggle"
      />
      <button
        type="button"
        class="date-picker-toggle"
        :aria-label="open ? `关闭${ariaLabel}` : `打开${ariaLabel}`"
        :aria-expanded="open"
        :disabled="disabled"
        @click.stop="toggle"
      >
        <AppIcon name="calendar" />
      </button>
    </div>
    <Transition name="date-popover">
      <div
        v-if="open"
        class="date-picker-popover"
        :style="popoverStyle"
        role="dialog"
        :aria-label="ariaLabel"
      >
        <div class="date-picker-nav">
          <strong>{{ monthLabel }}</strong>
          <div class="date-picker-nav-actions">
            <button type="button" aria-label="上个月" @click="moveMonth(-1)">
              <span class="date-picker-arrow previous" aria-hidden="true"></span>
            </button>
            <button type="button" aria-label="下个月" @click="moveMonth(1)">
              <span class="date-picker-arrow next" aria-hidden="true"></span>
            </button>
          </div>
        </div>
        <div class="date-picker-weekdays" aria-hidden="true">
          <span v-for="weekday in weekdays" :key="weekday">{{ weekday }}</span>
        </div>
        <div class="date-picker-grid">
          <button
            v-for="day in days"
            :key="day.key"
            type="button"
            :class="[
              'date-picker-day',
              { outside: !day.current, selected: day.selected, today: day.today },
            ]"
            :aria-label="day.key"
            :aria-current="day.selected ? 'date' : undefined"
            :disabled="day.disabled"
            @click="select(day)"
          >
            {{ day.date.getDate() }}
          </button>
        </div>
        <div class="date-picker-footer">
          <button type="button" class="date-picker-clear" @click="clear">清除</button>
          <button type="button" class="date-picker-today" :disabled="todayDisabled" @click="selectToday">
            今天
          </button>
        </div>
      </div>
    </Transition>
  </div>
</template>
