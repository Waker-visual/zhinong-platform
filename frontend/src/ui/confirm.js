import { reactive } from "vue";

// 应用内确认框，替代 window.confirm / window.prompt：写明对象与后果，危险操作用红色按钮。
// 由 App.vue 中的 ConfirmDialog 渲染；同一时间只有一个确认请求。
export const confirmState = reactive({
  open: false,
  title: "",
  message: "",
  confirmLabel: "确定",
  danger: false,
  input: null,
  value: "",
  resolve: null,
});

export function confirmAction(options) {
  confirmState.resolve?.(false);
  return new Promise((resolve) =>
    Object.assign(confirmState, {
      confirmLabel: "确定",
      danger: false,
      input: null,
      value: "",
      ...options,
      open: true,
      resolve,
    }),
  );
}

// 需要填写一段说明的确认：返回去掉首尾空白的文字，取消时返回 null
export async function promptText({ label, placeholder = "", maxLength = 500, ...options }) {
  const ok = await confirmAction({ ...options, input: { label, placeholder, maxLength } });
  return ok ? confirmState.value.trim() : null;
}

export function settleConfirm(result) {
  const resolve = confirmState.resolve;
  confirmState.open = false;
  confirmState.resolve = null;
  resolve?.(result);
}
