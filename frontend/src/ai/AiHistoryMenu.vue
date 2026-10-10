<script setup>
// 阶段F：历史会话项的“更多操作”浮层菜单——竖向三点触发按钮 + 重命名/置顶/删除。
// 和 ui/ActionMenu.vue 同一套交互约定（role=menu、方向键、Esc 还焦点、点外部关闭），但两点不同：
// 1) 触发按钮是 24px 圆角正方形图标按钮，不是带文字的“更多”按钮；
// 2) 菜单用 Teleport 挂到 body，用 position:fixed 按所在历史项的屏幕坐标摆放——历史栏本身是
//    overflow:auto 的可滚动侧栏/移动端横向chip栏，absolute 定位的子元素会被裁出可视区域；
//    fixed + 根据视口空间翻转（向下放不下就改成向上展开）才能保证菜单始终完整可见，不被裁切。
import { nextTick, onBeforeUnmount, ref } from "vue";
import AppIcon from "../ui/AppIcon.vue";

const props = defineProps({
  pinned: { type: Boolean, default: false },
  label: { type: String, default: "更多操作" },
});
const emit = defineEmits(["rename", "pin", "delete", "open-change"]);
const open = ref(false),
  root = ref(null),
  trigger = ref(null),
  list = ref(null);
const style = ref({});
const listId = "ai-history-menu-" + Math.random().toString(36).slice(2, 9);

function menuItems() {
  return [...(list.value?.querySelectorAll('[role="menuitem"]') || [])];
}
function onOutside(event) {
  if (root.value?.contains(event.target) || list.value?.contains(event.target)) return;
  close(false);
}
async function show(focusFirst) {
  // 先隐藏渲染一次以测得真实尺寸，再定位，避免出现在默认位置闪一下
  style.value = { position: "fixed", left: "0px", top: "0px", visibility: "hidden" };
  open.value = true;
  emit("open-change", true);
  document.addEventListener("pointerdown", onOutside);
  window.addEventListener("scroll", reposition, { passive: true, capture: true });
  window.addEventListener("resize", reposition);
  await nextTick();
  reposition();
  if (focusFirst) menuItems()[0]?.focus();
}
function reposition() {
  const el = trigger.value, menu = list.value;
  if (!el || !open.value) return;
  // 菜单右缘对齐所在历史项的右缘（参考桌面端侧栏的上下文菜单），在项下方展开；
  // 宽度按渲染后的实际内容测量，下方空间不够时改为向上展开，左右都不越出视口。
  const anchor = (el.closest(".ai-history-item") || el).getBoundingClientRect();
  const width = menu?.offsetWidth || 160, height = menu?.offsetHeight || 112, gap = 4, margin = 8;
  const left = Math.max(margin, Math.min(anchor.right - width, window.innerWidth - width - margin));
  const flipUp = window.innerHeight - anchor.bottom < height + gap + margin && anchor.top > height + gap + margin;
  style.value = flipUp
    ? { position: "fixed", left: left + "px", right: "auto", top: "auto", bottom: window.innerHeight - anchor.top + gap + "px" }
    : { position: "fixed", left: left + "px", right: "auto", top: anchor.bottom + gap + "px", bottom: "auto" };
}
function close(returnFocus) {
  if (!open.value) return;
  open.value = false;
  emit("open-change", false);
  document.removeEventListener("pointerdown", onOutside);
  window.removeEventListener("scroll", reposition, { capture: true });
  window.removeEventListener("resize", reposition);
  if (returnFocus) trigger.value?.focus();
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
  else if (event.key === "ArrowUp") items[(index - 1 + items.length) % items.length]?.focus();
  else if (event.key === "Home") items[0]?.focus();
  else if (event.key === "End") items.at(-1)?.focus();
  else if (event.key === "Escape") close(true);
  else if (event.key === "Tab") return close(false);
  else return;
  event.preventDefault();
}
function choose(action) {
  close(true);
  emit(action);
}
onBeforeUnmount(() => {
  document.removeEventListener("pointerdown", onOutside);
  window.removeEventListener("scroll", reposition, { capture: true });
  window.removeEventListener("resize", reposition);
});
defineExpose({ close: () => close(false) });
</script>

<template>
  <div ref="root" :class="['ai-history-menu', { open }]">
    <button
      ref="trigger"
      type="button"
      class="ai-history-menu-trigger"
      :aria-label="label"
      aria-haspopup="menu"
      :aria-expanded="open"
      :aria-controls="listId"
      @click.stop="open ? close(false) : show(false)"
      @keydown="onTriggerKey"
    >
      <AppIcon name="moreVertical" />
    </button>
    <Teleport to="body">
      <ul
        v-if="open"
        ref="list"
        :id="listId"
        class="ai-history-menu-list action-menu-list"
        role="menu"
        :style="style"
        @keydown="onMenuKey"
        @click.stop
      >
        <li role="none">
          <button type="button" role="menuitem" tabindex="-1" @click="choose('rename')">
            <AppIcon name="edit" />重命名
          </button>
        </li>
        <li role="none">
          <button type="button" role="menuitem" tabindex="-1" @click="choose('pin')">
            <AppIcon name="pin" />{{ pinned ? "取消置顶" : "置顶" }}
          </button>
        </li>
        <li role="none" class="danger-row">
          <button type="button" role="menuitem" tabindex="-1" class="danger" @click="choose('delete')">
            <AppIcon name="trash" />删除
          </button>
        </li>
      </ul>
    </Teleport>
  </div>
</template>
