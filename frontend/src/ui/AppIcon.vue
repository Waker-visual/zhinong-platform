<script setup>
// 统一线宽的界面图标，取代菜单里字体相关的 Unicode 字符。路径为本项目绘制。
const icons = {
  profile:
    '<circle cx="12" cy="8" r="3.2"/><path d="M4 20c.7-3.8 3.5-6 8-6s7.3 2.2 8 6"/>',
  security:
    '<path d="M12 3.2 19 6v5.4c0 4.7-2.8 7.9-7 9.5-4.2-1.6-7-4.8-7-9.5V6z"/><circle cx="12" cy="11" r="1.7"/><path d="M12 12.7v3.1"/>',
  appearance:
    '<rect x="3.5" y="4.5" width="17" height="12" rx="2"/><path d="M8 20h8M12 16.5V20M17.5 9.5h.01"/>',
  team:
    '<circle cx="9" cy="8" r="3.2"/><path d="M3.2 20c.6-3.6 3.2-5.8 6-5.8s5.4 2.2 6 5.8M16 5.2a3.2 3.2 0 0 1 0 5.6M17 14.7c2 .8 3.4 2.5 3.8 5.3"/>',
  info:
    '<circle cx="12" cy="12" r="8.8"/><path d="M12 10.5v5.4M12 7.4v.1"/>',
  today:
    '<circle cx="12" cy="12" r="4"/><path d="M12 2.5v2M12 19.5v2M4.6 4.6l1.4 1.4M18 18l1.4 1.4M2.5 12h2M19.5 12h2M4.6 19.4 6 18M18 6l1.4-1.4"/>',
  calendar:
    '<rect x="4" y="5.5" width="16" height="15" rx="1.5"/><path d="M8 3.5v4M16 3.5v4M4 10h16"/>',
  simulation: '<path d="M3.5 3.5v17h17"/><path d="m7.5 15 4-4.5 3 3 5-6"/>',
  overview:
    '<path d="m9 4.5-5.5 2v13l5.5-2 6 2 5.5-2v-13l-5.5 2z"/><path d="M9 4.5v13M15 6.5v13"/>',
  farms:
    '<path d="M3.5 10.5 12 4l8.5 6.5"/><path d="M5.5 9v11h13V9"/><path d="M10 20v-5.5h4V20"/>',
  plots:
    '<rect x="3.5" y="3.5" width="7" height="7" rx="1.2"/><rect x="13.5" y="3.5" width="7" height="7" rx="1.2"/><rect x="3.5" y="13.5" width="7" height="7" rx="1.2"/><rect x="13.5" y="13.5" width="7" height="7" rx="1.2"/>',
  plantings:
    '<path d="M12 21v-9"/><path d="M12 12c0-4-3-6.5-7.5-6.5 0 4.2 3 6.5 7.5 6.5z"/><path d="M12 10c0-3.6 2.6-6.5 7.5-6.5 0 4.3-3 6.5-7.5 6.5z"/>',
  tasks:
    '<path d="M10.5 6.5h10M10.5 12h10M10.5 17.5h10"/><path d="m3.5 6.5 1.5 1.5L7.5 5.5M3.5 12l1.5 1.5L7.5 11M3.5 17.5 5 19l2.5-2.5"/>',
  production:
    '<path d="M12 21V8"/><path d="M12 8c-1.7-.8-2.6-2.4-2.6-4.3 1.7 0 2.6 1.3 2.6 2.6 0-1.3.9-2.6 2.6-2.6 0 1.9-.9 3.5-2.6 4.3z"/><path d="M12 13c-1.9-.6-3.3-2-3.5-4M12 13c1.9-.6 3.3-2 3.5-4M12 17.5c-1.9-.6-3.3-2-3.5-4M12 17.5c1.9-.6 3.3-2 3.5-4"/>',
  devices: '<path d="M2.5 12h4l3-7 5 14 3-7h4"/>',
  members:
    '<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20c.6-3.6 3.2-6 6.5-6s5.9 2.4 6.5 6"/><path d="M15.5 4.7a3.5 3.5 0 0 1 0 6.6M17.5 14.4c2.2.8 3.6 2.8 4 5.6"/>',
  audit:
    '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>',
  tenants:
    '<path d="M4.5 20.5v-15l7-2.5v18"/><path d="M11.5 8.5h8v12"/><path d="M2.5 20.5h19M7.5 8.5h1M7.5 12h1M7.5 15.5h1M14.5 12h2M14.5 15.5h2"/>',
  operations:
    '<path d="M3.5 15.5a8.5 8.5 0 0 1 17 0"/><path d="m12 15.5 4-5"/><path d="M3.5 19.5h17"/>',
  patrol:
    '<path d="M2.5 12s3.5-6 9.5-6 9.5 6 9.5 6-3.5 6-9.5 6-9.5-6-9.5-6z"/><circle cx="12" cy="12" r="2.8"/>',
  irrigation:
    '<path d="M12 3.5s6 6.4 6 10.8a6 6 0 0 1-12 0c0-4.4 6-10.8 6-10.8z"/><path d="M9 14.5a3 3 0 0 0 3 3"/>',
  protection:
    '<path d="M5 19c0-8 5-13 14.5-14 0 9-5 14-13.5 14"/><path d="m5 19 8-8"/>',
  search: '<circle cx="11" cy="11" r="6.5"/><path d="m20 20-4.2-4.2"/>',
  sidebar:
    '<rect x="3" y="4" width="18" height="16" rx="2.5"/><path d="M9 4v16"/>',
  refresh: '<path d="M20 11a8 8 0 1 0-2.3 5.7"/><path d="M20 4v7h-7"/>',
  logout:
    '<path d="M14.5 4.5h3a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2h-3"/><path d="M10 16.5 5.5 12 10 7.5M5.5 12h10"/>',
  moon: '<path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z"/>',
  more: '<path d="M5 12h.01M12 12h.01M19 12h.01" stroke-width="3"/>',
  moreVertical: '<path d="M12 5h.01M12 12h.01M12 19h.01" stroke-width="3"/>',
  pin: '<path d="M8.5 3.5h7"/><path d="M10 3.5v5.2L7 12.5h10l-3-3.8V3.5"/><path d="M12 12.5v8"/>',
  history:
    '<rect x="4" y="4.5" width="16" height="15" rx="1.8"/><path d="M7.5 9h9M7.5 12.5h7M7.5 16h4.5"/>',
  edit:
    '<path d="m4.5 16.8-.9 3.6 3.6-.9L19 7.7a2.1 2.1 0 0 0-3-3z"/><path d="m14.7 6 3.3 3.3"/>',
  trash:
    '<path d="M4.5 7h15"/><path d="M9.5 7V5.3c0-.7.6-1.3 1.3-1.3h2.4c.7 0 1.3.6 1.3 1.3V7"/><path d="m6.5 7 .9 11.7c.1 1 .9 1.8 1.9 1.8h5.4c1 0 1.8-.8 1.9-1.8L17.5 7"/><path d="M10.2 11v5.5M13.8 11v5.5"/>',
  settings:
    '<path d="M12.2 2.5h-.4a1.8 1.8 0 0 0-1.8 1.8v.2a1.8 1.8 0 0 1-.9 1.55l-.35.2a1.8 1.8 0 0 1-1.8 0l-.18-.1a1.8 1.8 0 0 0-2.45.65l-.2.35a1.8 1.8 0 0 0 .65 2.45l.18.1a1.8 1.8 0 0 1 .9 1.55v.4a1.8 1.8 0 0 1-.9 1.55l-.18.1a1.8 1.8 0 0 0-.65 2.45l.2.35a1.8 1.8 0 0 0 2.45.65l.18-.1a1.8 1.8 0 0 1 1.8 0l.35.2a1.8 1.8 0 0 1 .9 1.55v.2a1.8 1.8 0 0 0 1.8 1.8h.4a1.8 1.8 0 0 0 1.8-1.8v-.2a1.8 1.8 0 0 1 .9-1.55l.35-.2a1.8 1.8 0 0 1 1.8 0l.18.1a1.8 1.8 0 0 0 2.45-.65l.2-.35a1.8 1.8 0 0 0-.65-2.45l-.18-.1a1.8 1.8 0 0 1-.9-1.55v-.4a1.8 1.8 0 0 1 .9-1.55l.18-.1a1.8 1.8 0 0 0 .65-2.45l-.2-.35a1.8 1.8 0 0 0-2.45-.65l-.18.1a1.8 1.8 0 0 1-1.8 0l-.35-.2a1.8 1.8 0 0 1-.9-1.55v-.2a1.8 1.8 0 0 0-1.8-1.8z"/><circle cx="12" cy="12" r="3"/>',
  enter: '<path d="M19.5 5v7a3 3 0 0 1-3 3h-12"/><path d="m8.5 11-4 4 4 4"/>',
  copy: '<rect x="8.5" y="8.5" width="11" height="11" rx="1.8"/><path d="M15.5 8.5V6.3a1.8 1.8 0 0 0-1.8-1.8H6.3a1.8 1.8 0 0 0-1.8 1.8v7.4a1.8 1.8 0 0 0 1.8 1.8h2.2"/>',
  check: '<path d="m4.5 12.5 5 5 10-11"/>',
  branch: '<circle cx="6" cy="6" r="2.3"/><circle cx="6" cy="18" r="2.3"/><circle cx="18" cy="12" r="2.3"/><path d="M6 8.3V18"/><path d="M6 8.3c0 4 3.5 4 7 4h3"/>',
  speaker: '<path d="M4 9.5v5h3.5l5 4v-13l-5 4z"/><path d="M16.2 9.3a4 4 0 0 1 0 5.4M18.8 7a7.5 7.5 0 0 1 0 10"/>',
  speakerOff: '<path d="M4 9.5v5h3.5l5 4v-13l-5 4z"/><path d="m15.5 10 4.5 4M20 10l-4.5 4"/>',
  chevronDown: '<path d="m5.5 9 6.5 6.5L18.5 9"/>',
  send: '<path d="M4 12 20 4l-4.5 16-4-7-7.5-1z"/>',
  arrowDown: '<path d="M12 4.5v14M6 13l6 6 6-6"/>',
  arrowUp: '<path d="M12 19.5v-14M6 11l6-6 6 6"/>',
  stop: '<rect x="7" y="7" width="10" height="10" rx="2" fill="currentColor"/>',
  arrowRight: '<path d="M4.5 12h14M13 6l6 6-6 6"/>',
};
defineProps({ name: { type: String, required: true } });
</script>

<template>
  <!-- v-html 只渲染上方的常量路径，不含用户输入 -->
  <svg
    class="app-icon"
    viewBox="0 0 24 24"
    aria-hidden="true"
    focusable="false"
    v-html="icons[name]"
  />
</template>
