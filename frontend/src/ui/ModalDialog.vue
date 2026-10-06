<script setup>
import { onBeforeUnmount, onMounted, ref } from "vue";

// 原生 <dialog>：打开时焦点进入对话框，Tab 不会移到背后的页面，Esc 或点击遮罩关闭；
// 关闭后焦点回到打开它的按钮。locked 为真（例如正在保存）时忽略关闭请求。
const props = defineProps({ label: String, locked: Boolean });
const emit = defineEmits(["close"]);
const dialog = ref(null);
let opener = null;

onMounted(() => {
  opener = document.activeElement;
  dialog.value.showModal();
});
onBeforeUnmount(() => {
  if (dialog.value?.open) dialog.value.close();
  if (opener?.isConnected) opener.focus();
});
function request() {
  if (!props.locked) emit("close");
}
</script>

<template>
  <dialog
    ref="dialog"
    class="modal app-dialog"
    :aria-label="label"
    @cancel.prevent="request"
    @click.self="request"
  >
    <div class="dialog-body"><slot /></div>
  </dialog>
</template>
