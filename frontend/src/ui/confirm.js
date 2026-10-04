import { reactive } from "vue";

// 应用内确认框，替代 window.confirm：写明对象与后果，危险操作用红色按钮。
// 由 App.vue 中的 ConfirmDialog 渲染；同一时间只有一个确认请求。
export const confirmState = reactive({
  open: false,
  title: "",
  message: "",
  confirmLabel: "确定",
  danger: false,
  resolve: null,
});

export function confirmAction(options) {
  confirmState.resolve?.(false);
  return new Promise((resolve) =>
    Object.assign(confirmState, {
      confirmLabel: "确定",
      danger: false,
      ...options,
      open: true,
      resolve,
    }),
  );
}

export function settleConfirm(result) {
  const resolve = confirmState.resolve;
  confirmState.open = false;
  confirmState.resolve = null;
  resolve?.(result);
}
