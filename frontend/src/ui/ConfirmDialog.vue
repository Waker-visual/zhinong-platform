<script setup>
import ModalDialog from "./ModalDialog.vue";
import { confirmState as state, settleConfirm } from "./confirm";
</script>

<template>
  <ModalDialog
    v-if="state.open"
    class="confirm-dialog"
    :label="state.title"
    @close="settleConfirm(false)"
  >
    <h2>{{ state.title }}</h2>
    <p class="muted">{{ state.message }}</p>
    <div class="modal-actions">
      <!-- 焦点默认落在“取消”，避免误按回车执行危险操作 -->
      <button type="button" class="outline" @click="settleConfirm(false)">
        取消</button
      ><button
        type="button"
        :class="state.danger ? 'danger-button' : 'primary'"
        @click="settleConfirm(true)"
      >
        {{ state.confirmLabel }}
      </button>
    </div>
  </ModalDialog>
</template>
