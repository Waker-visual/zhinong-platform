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
    <p v-if="state.message" class="muted">{{ state.message }}</p>
    <!-- 需要填写说明时：输入框排在按钮前，打开即可输入；空白说明在提交时就地提示 -->
    <form v-if="state.input" v-validate @submit.prevent="settleConfirm(true)">
      <label
        >{{ state.input.label
        }}<textarea
          v-model="state.value"
          required
          rows="3"
          :maxlength="state.input.maxLength"
          :placeholder="state.input.placeholder"
          @input="$event.target.setCustomValidity('')"
          @blur="
            $event.target.setCustomValidity(
              $event.target.value.trim() ? '' : '说明不能只有空格',
            )
          "
        ></textarea
      ></label>
      <div class="modal-actions">
        <button type="button" class="outline" @click="settleConfirm(false)">
          取消</button
        ><button
          type="submit"
          :class="state.danger ? 'danger-button' : 'primary'"
        >
          {{ state.confirmLabel }}
        </button>
      </div>
    </form>
    <div v-else class="modal-actions">
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
