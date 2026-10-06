// v-validate：给表单加上就地校验与软键盘提示，沿用字段上已有的 required / min / max / minlength 等约束。
// - 打字时不报错；离开字段（失焦）后才判定，已报错的字段在停止输入 500ms 后重新判定，改对立即消失；
// - 提交时不用浏览器自带的气泡：逐个字段写出中文原因，焦点移到第一个错误字段并轻微抖动；
// - 数字字段按最小值选择键盘：不允许负数时用数字键盘，允许负数时保留带负号的默认键盘。
// 提交按钮不会因为表单没填完而被置灰，点击后直接指出缺什么。

const DEBOUNCE = 500;

function fields(form) {
  return [...form.elements].filter(
    (el) =>
      el.willValidate && ["INPUT", "SELECT", "TEXTAREA"].includes(el.tagName),
  );
}

// 取标签自身的文字（不含嵌套的输入框和选项文字），去掉“（选填）”
function labelOf(field) {
  const label = field.labels?.[0];
  let text = "";
  if (label)
    for (const node of label.childNodes)
      if (node.nodeType === Node.TEXT_NODE) text += node.textContent;
  text = (text || field.getAttribute("aria-label") || "").trim();
  return text.replace(/（选填）|\(选填\)|[*：:]/g, "").trim() || "此项";
}

function messageOf(field) {
  const v = field.validity,
    name = labelOf(field);
  if (v.valueMissing)
    return field.tagName === "SELECT" || field.type === "date"
      ? `请选择${name}`
      : `请填写${name}`;
  if (v.badInput) return `${name}需要填写数字`;
  if (v.rangeUnderflow) return `${name}不能小于 ${field.min}`;
  if (v.rangeOverflow) return `${name}不能大于 ${field.max}`;
  if (v.stepMismatch)
    return Number(field.step) === 1
      ? `${name}需要是整数`
      : `${name}最多保留 ${String(field.step).split(".")[1]?.length || 0} 位小数`;
  if (v.tooShort) return `${name}至少 ${field.minLength} 个字符，目前 ${field.value.length} 个`;
  if (v.tooLong) return `${name}最多 ${field.maxLength} 个字符`;
  if (v.typeMismatch) return `${name}格式不正确`;
  if (v.patternMismatch) return field.title || `${name}格式不正确`;
  return field.validationMessage;
}

let seq = 0,
  announcer = null;
// 新出现的报错用独立的礼貌播报区读出；字段本身通过 aria-describedby 关联原因
function announce(text) {
  if (!announcer) {
    announcer = document.createElement("div");
    announcer.className = "sr-only";
    announcer.setAttribute("aria-live", "polite");
    document.body.appendChild(announcer);
  }
  announcer.textContent = "";
  setTimeout(() => (announcer.textContent = text), 50);
}
function show(field, text) {
  let note = field._validateNote;
  if (!note) {
    note = document.createElement("small");
    note.className = "field-error";
    note.id = "field-error-" + ++seq;
    // 原因写在标签里，但不计入字段名称（名称仍是“租户代码”），只作为描述
    note.setAttribute("aria-hidden", "true");
    const host = field.closest("label") || field.parentElement;
    host.appendChild(note);
    field._validateNote = note;
    const described = field.getAttribute("aria-describedby");
    field.setAttribute(
      "aria-describedby",
      described ? described + " " + note.id : note.id,
    );
  }
  note.textContent = text;
  field.setAttribute("aria-invalid", "true");
}
function clear(field) {
  const note = field._validateNote;
  if (note) {
    const ids = (field.getAttribute("aria-describedby") || "")
      .split(" ")
      .filter((id) => id && id !== note.id);
    if (ids.length) field.setAttribute("aria-describedby", ids.join(" "));
    else field.removeAttribute("aria-describedby");
    note.remove();
    field._validateNote = null;
  }
  field.removeAttribute("aria-invalid");
}
function check(field, speak = false) {
  if (field.checkValidity()) {
    clear(field);
    return true;
  }
  const text = messageOf(field);
  if (speak && field._validateNote?.textContent !== text) announce(text);
  show(field, text);
  return false;
}

function keyboardHints(form) {
  for (const field of form.querySelectorAll('input[type="number"]')) {
    if (field.hasAttribute("inputmode")) continue;
    // 没写 step 时默认步长为 1，只接受整数
    const step = field.getAttribute("step");
    const whole = !step || (step !== "any" && Number.isInteger(Number(step)));
    if (field.min !== "" && Number(field.min) >= 0)
      field.inputMode = whole ? "numeric" : "decimal";
  }
}

// 摇头式抖动表达“没通过”；系统要求减少动态效果时由样式取消动画
export function shakeElement(target) {
  if (!target) return;
  target.classList.remove("field-shake");
  void target.offsetWidth;
  target.classList.add("field-shake");
  setTimeout(() => target.classList.remove("field-shake"), 450);
  if (matchMedia("(pointer: coarse)").matches) navigator.vibrate?.(30);
}
function shake(field) {
  shakeElement(field.closest("label") || field);
}

export const validate = {
  mounted(form) {
    form.noValidate = true;
    const dirty = new WeakSet(),
      timers = new WeakMap();
    const onInput = (event) => {
      const field = event.target;
      if (!field.willValidate) return;
      dirty.add(field);
      if (!field._validateNote) return;
      clearTimeout(timers.get(field));
      // 改对了立即撤掉报错；仍不对就等停止输入 500ms 再更新原因
      if (field.checkValidity()) clear(field);
      else timers.set(field, setTimeout(() => check(field), DEBOUNCE));
    };
    const onBlur = (event) => {
      const field = event.target;
      if (field.willValidate && (dirty.has(field) || field._validateNote))
        check(field, true);
    };
    const onSubmit = (event) => {
      const invalid = fields(form).filter((field) => !check(field));
      if (!invalid.length) return;
      event.preventDefault();
      event.stopImmediatePropagation();
      invalid[0].focus();
      shake(invalid[0]);
    };
    form.addEventListener("input", onInput);
    form.addEventListener("change", onInput);
    form.addEventListener("focusout", onBlur);
    // 捕获阶段先于组件自己的 @submit 处理
    form.addEventListener("submit", onSubmit, true);
    keyboardHints(form);
    const observer = new MutationObserver(() => keyboardHints(form));
    observer.observe(form, { childList: true, subtree: true });
    form._validate = { onInput, onBlur, onSubmit, observer };
  },
  unmounted(form) {
    const handlers = form._validate;
    if (!handlers) return;
    form.removeEventListener("input", handlers.onInput);
    form.removeEventListener("change", handlers.onInput);
    form.removeEventListener("focusout", handlers.onBlur);
    form.removeEventListener("submit", handlers.onSubmit, true);
    handlers.observer.disconnect();
  },
};
