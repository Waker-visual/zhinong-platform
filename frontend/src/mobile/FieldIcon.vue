<script setup>
import AppIcon from "../ui/AppIcon.vue";
const paths = {
  machinery: '<circle cx="7" cy="17" r="3.5"/><circle cx="19" cy="18" r="2.3"/><path d="M3.5 13V6h8l3 8h6v2M5 6V3h7M12 7H7v5h7M10.5 17h6"/>',
  pump: '<path d="M4 19h16M6 19v-8h11v8M5 11V8h9M10 8V4h5M17 13h4v4M10 15h3"/><circle cx="11.5" cy="14.5" r="2.5"/>',
  sensor: '<path d="M12 21V10M8 21h8M6 7a9 9 0 0 1 12 0M9 10a4.5 4.5 0 0 1 6 0"/><circle cx="12" cy="12" r="1.5"/>',
  field: '<path d="M3 11 12 6l9 5-9 5zM3 15l9 5 9-5M8 8l9 5M7 13l9-5"/>',
};
defineProps({ name: { type: String, default: "field" } });
</script>
<template>
  <svg v-if="paths[name]" class="app-icon field-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false" v-html="paths[name]" />
  <AppIcon v-else :name="name" />
</template>
