<script setup>
// 统一线宽的界面图标，取代菜单里字体相关的 Unicode 字符。路径为本项目绘制。
const icons = {
  today:
    '<circle cx="12" cy="12" r="4"/><path d="M12 2.5v2M12 19.5v2M4.6 4.6l1.4 1.4M18 18l1.4 1.4M2.5 12h2M19.5 12h2M4.6 19.4 6 18M18 6l1.4-1.4"/>',
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
  settings:
    '<path d="M3.5 7h10M18.5 7h2M3.5 17h4M12.5 17h8"/><circle cx="16" cy="7" r="2.5"/><circle cx="10" cy="17" r="2.5"/>',
  enter: '<path d="M19.5 5v7a3 3 0 0 1-3 3h-12"/><path d="m8.5 11-4 4 4 4"/>',
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
