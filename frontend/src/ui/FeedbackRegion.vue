<script setup>
import {
  closeNotice,
  dismissFailure,
  feedback,
  pauseNotice,
  resumeNotice,
  retryFailure,
  takeAction,
} from "./feedback";
</script>

<template>
  <div
    :class="['toast-region', { 'toast-region-top': feedback.notice?.placement === 'top' }]"
  >
    <!-- 报错常驻：读屏软件立即播报，用户手动关闭或重试 -->
    <div class="toast-stack" aria-live="assertive">
      <p
        v-for="item in feedback.failures"
        :key="item.id"
        class="toast toast-failure"
        role="alert"
      >
        <span class="toast-mark" aria-hidden="true">!</span>
        <span class="toast-text">{{ item.text }}</span>
        <button
          v-if="item.retry"
          type="button"
          class="toast-action"
          @click="retryFailure(item.id)"
        >
          重试
        </button>
        <button
          type="button"
          class="toast-close"
          aria-label="关闭报错"
          @click="dismissFailure(item.id)"
        >
          ×
        </button>
      </p>
    </div>
    <div class="toast-stack" aria-live="polite" aria-atomic="true">
      <p
        v-if="feedback.notice"
        :key="feedback.notice.id"
        :class="['toast', 'toast-' + feedback.notice.kind]"
        :style="{ '--toast-duration': feedback.notice.duration + 'ms' }"
        role="status"
        @mouseenter="pauseNotice"
        @mouseleave="resumeNotice"
        @focusin="pauseNotice"
        @focusout="resumeNotice"
      >
        <span class="toast-info-mark" aria-hidden="true">i</span>
        <span class="toast-text">{{ feedback.notice.text }}</span>
        <button
          v-if="feedback.notice.actionLabel"
          type="button"
          class="toast-action"
          @click="takeAction"
        >
          {{ feedback.notice.actionLabel }}
        </button>
        <button
          type="button"
          class="toast-close"
          aria-label="关闭提示"
          @click="closeNotice"
        >
          ×
        </button>
        <i
          v-if="feedback.notice.kind === 'snackbar'"
          class="toast-countdown"
          aria-hidden="true"
        ></i>
      </p>
    </div>
  </div>
</template>
