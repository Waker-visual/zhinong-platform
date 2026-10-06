<script setup>
import { nextTick, onBeforeUnmount, ref } from "vue";
import AppIcon from "./AppIcon.vue";

// 次要操作收进“更多”菜单：方向键移动，Esc 关闭并把焦点还给按钮，点击菜单外关闭。
defineProps({ items: { type: Array, required: true } });
const emit = defineEmits(["select"]);
const open = ref(false),
  root = ref(null),
  trigger = ref(null);
const listId = "menu-" + Math.random().toString(36).slice(2, 9);

function menuItems() {
  return [...(root.value?.querySelectorAll('[role="menuitem"]') || [])];
}
function onOutside(event) {
  if (!root.value?.contains(event.target)) close(false);
}
async function show(focusFirst) {
  open.value = true;
  document.addEventListener("pointerdown", onOutside);
  await nextTick();
  if (focusFirst) menuItems()[0]?.focus();
}
function close(returnFocus) {
  if (!open.value) return;
  open.value = false;
  document.removeEventListener("pointerdown", onOutside);
  if (returnFocus) trigger.value?.focus();
}
function choose(item) {
  close(true);
  emit("select", item.key);
}
function onTriggerKey(event) {
  if (event.key === "ArrowDown" && !open.value) {
    event.preventDefault();
    show(true);
  }
}
function onMenuKey(event) {
  const items = menuItems(),
    index = items.indexOf(document.activeElement);
  if (event.key === "ArrowDown") items[(index + 1) % items.length]?.focus();
  else if (event.key === "ArrowUp")
    items[(index - 1 + items.length) % items.length]?.focus();
  else if (event.key === "Home") items[0]?.focus();
  else if (event.key === "End") items.at(-1)?.focus();
  else if (event.key === "Escape") close(true);
  else if (event.key === "Tab") return close(false);
  else return;
  event.preventDefault();
}
onBeforeUnmount(() => document.removeEventListener("pointerdown", onOutside));
</script>

<template>
  <div ref="root" :class="['action-menu', { open }]">
    <button
      ref="trigger"
      type="button"
      class="action-menu-trigger"
      aria-label="更多操作"
      aria-haspopup="menu"
      :aria-expanded="open"
      :aria-controls="listId"
      @click="open ? close(false) : show(false)"
      @keydown="onTriggerKey"
    >
      更多<AppIcon name="more" />
    </button>
    <ul
      v-if="open"
      :id="listId"
      class="action-menu-list"
      role="menu"
      @keydown="onMenuKey"
    >
      <li
        v-for="item in items"
        :key="item.key"
        role="none"
        :class="{ 'danger-row': item.danger }"
      >
        <button
          type="button"
          role="menuitem"
          tabindex="-1"
          :class="{ danger: item.danger }"
          @click="choose(item)"
        >
          <AppIcon :name="item.icon || 'more'" />
          {{ item.label }}
        </button>
      </li>
    </ul>
  </div>
</template>
