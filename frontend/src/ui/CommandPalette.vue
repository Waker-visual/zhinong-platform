<script setup>
import { computed, nextTick, ref, watch } from "vue";
import AppIcon from "./AppIcon.vue";

// Ctrl K 页面搜索：列出当前账号可见的页面和账号操作，方向键选择，回车进入。
// 手机端底部“更多”也打开这里，代替被隐藏的侧栏。
const props = defineProps({
  open: Boolean,
  items: { type: Array, required: true },
});
const emit = defineEmits(["update:open", "choose"]);
const dialog = ref(null),
  input = ref(null),
  query = ref(""),
  active = ref(0);

const matches = computed(() => {
  const q = query.value.trim().toLowerCase();
  return q
    ? props.items.filter((item) =>
        [item.title, item.group, item.description]
          .filter(Boolean)
          .some((text) => text.toLowerCase().includes(q)),
      )
    : props.items;
});
const groups = computed(() => {
  const result = [];
  for (const item of matches.value) {
    const last = result.at(-1);
    if (last?.label === item.group) last.items.push(item);
    else result.push({ label: item.group, items: [item] });
  }
  return result;
});

watch(
  () => props.open,
  async (open) => {
    if (!dialog.value) return;
    if (open && !dialog.value.open) {
      query.value = "";
      active.value = 0;
      dialog.value.showModal();
      await nextTick();
      input.value?.focus();
    } else if (!open && dialog.value.open) dialog.value.close();
  },
);
watch(query, () => (active.value = 0));

// 搜不到时给几个常用页面作为下一步，而不是只说“没有匹配”
const suggestions = computed(() =>
  props.items
    .filter((item) => !item.id.startsWith("action:") && !item.current)
    .slice(0, 3),
);
function close() {
  emit("update:open", false);
}
function choose(item) {
  close();
  emit("choose", item);
}
function move(step) {
  const count = matches.value.length;
  if (!count) return;
  active.value = (active.value + step + count) % count;
  nextTick(() =>
    dialog.value
      ?.querySelector('[aria-selected="true"]')
      ?.scrollIntoView({ block: "nearest" }),
  );
}
function onKey(event) {
  if (event.key === "ArrowDown") move(1);
  else if (event.key === "ArrowUp") move(-1);
  else if (event.key === "Enter" && matches.value[active.value])
    choose(matches.value[active.value]);
  else return;
  event.preventDefault();
}
</script>

<template>
  <dialog
    ref="dialog"
    class="command-palette"
    aria-label="搜索页面"
    @close="close"
    @click.self="close"
  >
    <div class="command-search">
      <AppIcon name="search" />
      <input
        ref="input"
        v-model="query"
        role="combobox"
        aria-autocomplete="list"
        aria-controls="command-results"
        :aria-activedescendant="
          matches[active] ? 'command-' + matches[active].id : undefined
        "
        aria-label="输入页面名称"
        enterkeyhint="go"
        autocomplete="off"
        placeholder="搜索页面或操作…"
        @keydown="onKey"
      />
      <button type="button" class="icon-button" aria-label="关闭" @click="close">
        ×
      </button>
    </div>
    <div id="command-results" class="command-results" role="listbox">
      <section v-for="group in groups" :key="group.label" role="group" :aria-label="group.label">
        <p>{{ group.label }}</p>
        <button
          v-for="item in group.items"
          :id="'command-' + item.id"
          :key="item.id"
          type="button"
          role="option"
          tabindex="-1"
          :aria-selected="matches[active]?.id === item.id"
          @click="choose(item)"
          @mousemove="active = matches.indexOf(item)"
        >
          <AppIcon :name="item.icon" />
          <span>{{ item.title }}</span>
          <small v-if="item.current">当前页面</small>
          <AppIcon v-else class="command-enter" name="enter" />
        </button>
      </section>
      <div v-if="!matches.length" class="command-empty">
        <p>没有匹配“{{ query }}”的页面，试试：</p>
        <button
          v-for="item in suggestions"
          :key="item.id"
          type="button"
          class="outline"
          @click="choose(item)"
        >
          {{ item.title }}
        </button>
      </div>
    </div>
    <footer class="command-footer">
      <span><kbd>↑</kbd><kbd>↓</kbd> 选择</span><span><kbd>Enter</kbd> 打开</span
      ><span><kbd>Esc</kbd> 关闭</span>
    </footer>
  </dialog>
</template>
