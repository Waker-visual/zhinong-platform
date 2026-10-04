import { reactive } from "vue";

// 反馈分三级，对应清单里的选型对比：
// - toast：轻提示，不阻断，3.5 秒后自动消失，只说“已保存”这类结果；
// - snackbar：带一个补救操作（撤销），数秒内可反悔，到时才真正提交；
// - failure：报错常驻，必须手动关闭或点“重试”，视线移开也不会丢。
// 不可逆操作（例如重新生成设备凭据）仍用 ConfirmDialog 二次确认。
export const feedback = reactive({ notice: null, failures: [] });
// 处于撤销期的记录，列表应暂时隐藏
export const pendingIds = reactive(new Set());

let seq = 0,
  timer = null,
  remaining = 0,
  startedAt = 0;
const commits = new Map();

function arm(ms) {
  clearTimeout(timer);
  remaining = ms;
  startedAt = Date.now();
  timer = setTimeout(expire, ms);
}
function expire() {
  clearTimeout(timer);
  timer = null;
  const item = feedback.notice;
  feedback.notice = null;
  item?.onExpire?.();
}
function show(item, duration) {
  // 同一时间只显示一条：新提示出现时，上一条撤销提示按“未撤销”处理
  if (feedback.notice) expire();
  feedback.notice = { id: ++seq, duration, ...item };
  arm(duration);
}

export function toast(text, duration = 3500) {
  show({ kind: "toast", text }, duration);
}
export function snackbar(text, { actionLabel, onAction, onExpire, duration = 6000 }) {
  show({ kind: "snackbar", text, actionLabel, onAction, onExpire }, duration);
}
export function takeAction() {
  clearTimeout(timer);
  timer = null;
  const item = feedback.notice;
  feedback.notice = null;
  item?.onAction?.();
}
// 手动关闭撤销提示等同于接受操作
export function closeNotice() {
  expire();
}
// 鼠标悬停或键盘聚焦时暂停计时，给阅读和点击“撤销”留足时间
export function pauseNotice() {
  if (!timer) return;
  clearTimeout(timer);
  timer = null;
  remaining -= Date.now() - startedAt;
}
export function resumeNotice() {
  if (feedback.notice && !timer) arm(Math.max(remaining, 1500));
}

export function failure(text, retry = null) {
  const index = feedback.failures.findIndex((f) => f.text === text);
  if (index >= 0) feedback.failures.splice(index, 1);
  feedback.failures.push({ id: ++seq, text, retry });
  if (feedback.failures.length > 3) feedback.failures.shift();
}
// 网络或服务端故障才给“重试”；参数、权限或冲突错误重试也没用
export function reportFailure(error, retry = null) {
  failure(error.message, !error.status || error.status >= 500 ? retry : null);
}
export function dismissFailure(id) {
  const index = feedback.failures.findIndex((f) => f.id === id);
  if (index >= 0) feedback.failures.splice(index, 1);
}
export function retryFailure(id) {
  const item = feedback.failures.find((f) => f.id === id);
  dismissFailure(id);
  item?.retry?.();
}

/**
 * 事后撤销的删除：先隐藏，提示条上给“撤销”，计时结束（或被新提示顶替、页面关闭、退出登录）才发送删除。
 * commit(keepalive) 发送删除；settled() 在删除成功后刷新列表。删除被拒绝时恢复显示并常驻报错。
 */
export function deferDelete({ id, label, commit, settled, duration = 6000 }) {
  pendingIds.add(id);
  commits.set(id, { label, commit, settled });
  snackbar(`已删除“${label}”`, {
    actionLabel: "撤销",
    duration,
    onAction: () => {
      commits.delete(id);
      pendingIds.delete(id);
      toast(`已恢复“${label}”`);
    },
    onExpire: () => run(id),
  });
}
async function run(id, keepalive = false) {
  const entry = commits.get(id);
  if (!entry) return;
  commits.delete(id);
  try {
    await entry.commit(keepalive);
    // 先刷新列表再解除隐藏，避免记录闪现
    await entry.settled?.();
    pendingIds.delete(id);
  } catch (e) {
    pendingIds.delete(id);
    failure(
      e.status === 409
        ? `“${entry.label}”仍有关联的地块、设备或业务记录，未删除，已恢复显示。`
        : `未能删除“${entry.label}”：${e.message}`,
    );
  }
}
// 退出登录或关闭页面前，把撤销期内的删除立即提交
export async function flushPending(keepalive = false) {
  if (feedback.notice?.kind === "snackbar") {
    clearTimeout(timer);
    timer = null;
    feedback.notice = null;
  }
  await Promise.all([...commits.keys()].map((id) => run(id, keepalive)));
}
export function resetFeedback() {
  clearTimeout(timer);
  timer = null;
  commits.clear();
  pendingIds.clear();
  feedback.notice = null;
  feedback.failures.splice(0);
}
