<script setup>
import { computed, nextTick, onBeforeUnmount, ref } from "vue";
import AppIcon from "../ui/AppIcon.vue";
import FarmThumbnail from "./FarmStamp.vue";
import FieldIcon from "./FieldIcon.vue";
const props = defineProps({ modelValue: String, label: String, options: { type: Array, default: () => [] }, disabled: Boolean, icon: { type: String, default: "field" } });
const emit = defineEmits(["update:modelValue"]);
const dialog = ref(null), search = ref("");
const selected = computed(() => props.options.find(o => o.id === props.modelValue));
const visible = computed(() => props.options.filter(o => `${o.name} ${o.caption || ""} ${o.meta || ""}`.toLowerCase().includes(search.value.trim().toLowerCase())));
async function open() { if (props.disabled) return; search.value = ""; await nextTick(); dialog.value.showModal(); }
function choose(option) { if (props.disabled || option.disabled) return; emit("update:modelValue", option.id); dialog.value.close(); }
onBeforeUnmount(() => dialog.value?.close());
</script>
<template>
  <div class="field-picker">
    <span class="picker-label">{{ label }}</span>
    <button type="button" class="picker-trigger" :aria-label="label" aria-haspopup="dialog" :disabled="disabled" @click="open">
      <FarmThumbnail v-if="selected?.plots" class="picker-thumbnail" :plots="selected.plots" :name="selected.name" />
      <span v-else class="symbol-tile"><FieldIcon :name="icon" /></span>
      <span class="picker-copy"><strong>{{ selected?.name || `选择${label}` }}</strong><small>{{ selected?.caption || '轻触查看可选项目' }}</small></span>
      <AppIcon name="chevronDown" />
    </button>
    <dialog ref="dialog" class="picker-sheet" :aria-label="`选择${label}`" @click.self="dialog.close()">
      <header><div><p class="eyebrow">田间工作空间</p><h2>选择{{ label }}</h2></div><button type="button" class="icon-button" aria-label="关闭选择" @click="dialog.close()">×</button></header>
      <label class="search-label"><AppIcon name="search" /><input v-model="search" :aria-label="`搜索${label}`" placeholder="搜索名称或位置" /></label>
      <div class="picker-options">
        <button v-for="option in visible" :key="option.id" type="button" class="picker-option" :data-option-id="option.id" :aria-pressed="option.id === modelValue" :disabled="disabled || option.disabled" @click="choose(option)">
          <FarmThumbnail v-if="option.plots" class="picker-thumbnail" :plots="option.plots" :name="option.name" />
          <span v-else class="symbol-tile"><FieldIcon :name="option.icon || icon" /></span>
          <span class="picker-copy"><strong>{{ option.name }}</strong><small>{{ option.caption }}</small><span v-if="option.meta" class="picker-meta">{{ option.meta }}</span></span>
          <AppIcon v-if="option.id === modelValue" name="check" />
        </button>
        <p v-if="!visible.length" class="empty">没有匹配的项目</p>
      </div>
      <p class="picker-footer">{{ options.length }} 个可选项目 · 仅显示当前账号可访问的内容</p>
    </dialog>
  </div>
</template>
