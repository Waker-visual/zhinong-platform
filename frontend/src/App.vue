<script setup>
import { computed, defineAsyncComponent, nextTick, onMounted, onUnmounted, reactive, ref, shallowRef, watch } from "vue";
import { api, connection, isConnectionError, setToken } from "./api";
import { forms, labels, menus } from "./catalog";
import FarmHub from "./workspace/FarmHub.vue";
import DeviceManager from "./workspace/DeviceManager.vue";
import FieldCameras from "./workspace/FieldCameras.vue";
import "./workspace/workspace.css";
import SettingsDialog from "./account/SettingsDialog.vue";
import { applyAppearance, colorMode } from "./account/appearance";
import SimulationPage from "./simulation/SimulationPage.vue";
import FarmAssistant from "./ai/FarmAssistant.vue";
import { aiSession } from "./ai/ai-session.js";
import FarmBriefing from "./ai/FarmBriefing.vue";
import AlertCenter from "./workspace/AlertCenter.vue";
import DailyFarm from "./fieldwork/DailyFarm.vue";
import OperationsOverview from "./workspace/OperationsOverview.vue";
import AppIcon from "./ui/AppIcon.vue";
import brandLogo from "./assets/zhihe-logo.svg";
import LoginField from "./ui/LoginField.vue";
import FarmSelect from "./ui/FarmSelect.vue";
import SelectMenu from "./ui/SelectMenu.vue";
import DatePicker from "./ui/DatePicker.vue";
import CommandPalette from "./ui/CommandPalette.vue";
import ModalDialog from "./ui/ModalDialog.vue";
import ConfirmDialog from "./ui/ConfirmDialog.vue";
import FeedbackRegion from "./ui/FeedbackRegion.vue";
import { shakeElement } from "./ui/validate";
import { watchReadiness } from "./ui/service-readiness.js";
import StatusCapsule from "./ui/StatusCapsule.vue";
import {
  deferDelete,
  failure,
  flushPending,
  pendingIds,
  reportFailure,
  resetFeedback,
  toast,
  important,
} from "./ui/feedback";
import "./ui/shell.css";

// 小窗入口在工作台加载后再取，不占主包；面板资料也只在打开时读取。
const FloatingAssistant = defineAsyncComponent(() => import("./ai/FloatingAssistant.vue"));
const identity = ref(null);
const account = ref(null),
  settingsOpen = ref(false),
  farmScope = ref(readFarmScope()),
  formFarm = ref("");
function readFarmScope() {
  try { return sessionStorage.getItem("zhinong-farm-scope") || ""; } catch { return ""; }
}
watch(farmScope, id => {
  // 切换农场：中止共享 AI 会话里正在生成的回答并清空待重试请求，旧农场的回答不会落到新农场视图。
  aiSession.reset();
  try { if(id) sessionStorage.setItem("zhinong-farm-scope",id); else sessionStorage.removeItem("zhinong-farm-scope"); } catch { /* Session storage is optional. */ }
});
const scopedPage = computed(() =>
  ["plots", "plantings", "tasks", "production"].includes(page.value),
);
async function loadAccount() {
  account.value = await api("/account");
  applyAppearance(account.value);
  if (account.value.mustChangePassword) settingsOpen.value = true;
}
function accountUpdated(value) {
  account.value = value;
  identity.value.displayName = value.displayName;
  applyAppearance(value);
}
// 改密后回到登录页，提示常驻在登录表单里，直到重新登录
const loginNotice = ref("");
function passwordChanged() {
  expire();
  loginNotice.value = "密码已修改，请使用新密码重新登录。";
}
const page = ref("daily");
const moduleRevision = ref(0);
const farmToOpen = ref("");
const cameraFocus = ref("");
const ledgerFocus = ref({ farmId: "", deviceId: "", cameraOnly: false });
const navigationRevision = ref(0);
const error = ref("");
// busy 只锁写操作；页面加载用 loading，导航不再被写操作或加载阻塞。
const busy = ref(false);
const loading = ref(false);
const farmAssistant = ref(null);
const alertCenter = ref(null);
const assistantQuestion = ref("");
const assistantConversationId = ref("");
const assistantFocus = computed(() => page.value === "devices"
  ? { type: "device", id: ledgerFocus.value.deviceId }
  : page.value === "cameras"
    ? { type: "device", id: cameraFocus.value }
    : {});
const screenOpen = ref(false), screenOpening = ref(false), screenComponent = shallowRef(null);
let displayOpener;
const refreshingPage = ref(false);
const pageLoading = computed(() => loading.value || refreshingPage.value ||
  (page.value === "ai" && farmAssistant.value?.refreshing));
const refreshDisabled = computed(() => pageLoading.value ||
  (page.value === "ai" && farmAssistant.value?.refreshBlocked));
const pending = ref("");
const paletteOpen = ref(false);
const sidebarKey = "zhinong-sidebar";
const collapsed = ref(readSidebar());
function readSidebar() {
  try {
    return localStorage.getItem(sidebarKey) === "collapsed";
  } catch {
    return false;
  }
}
function toggleSidebar() {
  collapsed.value = !collapsed.value;
  try {
    localStorage.setItem(sidebarKey, collapsed.value ? "collapsed" : "expanded");
  } catch {
    // 浏览器禁用存储时只在本次会话内生效
  }
}
const search = ref("");
const rows = ref([]);
const dashboard = ref({});
const farmRows = ref([]);
const plotRows = ref([]);
const tasks = ref([]);
const observations = ref([]);
const devices = ref([]);
const editing = ref(null);
const dialog = ref(false);
const captureDevice = ref(null);
const captureValue = ref("");
const model = reactive({});
const login = reactive({
  tenantCode: "",
  username: "",
  password: "",
});
// 登录页只在服务以演示模式启动时预填演示租户和管理员，正式部署保持空白。
const service = reactive({ state: "checking", demo: false });
let stopReadiness = null;
function applyReadiness(status) {
  service.state = status.state;
  if (status.state === "unreachable") return;
  service.demo = status.demo;
  if (status.demo && !login.tenantCode && !login.username) {
    login.tenantCode = "demo-a";
    login.username = "admin";
  }
}
function watchLoginReadiness(onLogin) {
  stopReadiness?.();
  stopReadiness = onLogin ? watchReadiness(applyReadiness) : null;
}
watch(() => !identity.value, watchLoginReadiness, { immediate: true });
const role = computed(() => identity.value?.role);
const admin = computed(() => role.value === "ADMIN");
const writer = computed(() => ["ADMIN", "OPERATOR"].includes(role.value));
const platform = computed(() => role.value === "PLATFORM_ADMIN");
const visibleMenus = computed(() =>
  menus.filter((m) =>
    platform.value ? m.platform : !m.platform && (!m.admin || admin.value),
  ),
);
const current = computed(
  () => menus.find((m) => m.id === page.value) || menus[0],
);
// 顶栏“当前农场”是各页面共用的农场范围；今日农场、运行概览与经营模拟必须选定一座农场。
const farmRequired = computed(() =>
  ["daily", "operations", "simulation", "ai", "cameras"].includes(page.value),
);
const simulationKey = ref(0);
function selectFarm() {
  if (["daily", "operations"].includes(page.value)) return;
  if (["dashboard", "farms"].includes(page.value)) {
    farmToOpen.value = farmScope.value;
    return;
  }
  if (page.value === "simulation") {
    simulationKey.value++;
    return;
  }
  if (scopedPage.value) refreshActive();
}
const groupOrder = ["日常作业", "农场与设备", "分析与管理", "平台管理"];
const menuGroups = computed(() =>
  groupOrder
    .map((label) => ({
      label,
      items: visibleMenus.value.filter((m) => m.group === label),
    }))
    .filter((g) => g.items.length),
);
// 今日农场的计数覆盖本租户全部农场：逾期、受阻（不含已逾期）与待处理现场问题，颜色取最紧急的一项。
const attention = computed(() => {
  const d = dashboard.value;
  const overdue = Number(d.overdueTasks || 0),
    blocked = Number(d.blockedTasks || 0),
    issues = Number(d.openIssues || 0);
  const total = overdue + blocked + issues;
  if (!total) return null;
  return {
    total,
    tone: overdue ? "danger" : blocked ? "caution" : "neutral",
    label: `全部农场需要处理：${overdue} 项逾期，${blocked} 项受阻，${issues} 项现场问题`,
  };
});
const tabPriority = ["daily", "tasks", "dashboard", "devices", "platform/tenants"];
const tabItems = computed(() =>
  tabPriority
    .map((id) => visibleMenus.value.find((m) => m.id === id))
    .filter(Boolean)
    .slice(0, 4),
);
const paletteItems = computed(() => [
  ...menuGroups.value.flatMap((g) =>
    g.items.map((m) => ({ ...m, current: m.id === page.value })),
  ),
  { id: "action:settings", title: "账号设置", icon: "settings", group: "账号" },
  {
    id: "action:theme",
    title: colorMode.value === "DARK" ? "切换到浅色主题" : "切换到深色主题",
    icon: "moon",
    group: "账号",
  },
  { id: "action:signout", title: "退出登录", icon: "logout", group: "账号" },
]);
const fields = computed(() => forms[page.value] || []);
const canCreate = computed(
  () =>
    forms[page.value] &&
    page.value !== "tasks" &&
    (admin.value ||
      platform.value ||
      (writer.value && page.value === "production")),
);
const decorated = computed(() =>
  rows.value.map((row) => ({
    ...row,
    farmName:
      farmRows.value.find(
        (f) =>
          f.id ===
          (row.farmId ||
            plotRows.value.find((p) => p.id === row.plotId)?.farmId),
      )?.name || "—",
    plotName: plotRows.value.find((p) => p.id === row.plotId)?.name || "—",
  })),
);
const filtered = computed(() =>
  decorated.value.filter(
    (row) =>
      !pendingIds.has(row.id) &&
      JSON.stringify(row).toLowerCase().includes(search.value.toLowerCase()),
  ),
);
// 当前农场范围内的地块：种植计划和生产记录都要挂在地块上
const scopePlots = computed(() =>
  plotRows.value.filter((p) => !farmScope.value || p.farmId === farmScope.value),
);
// 撤销期内的农场不出现在顶栏选择里
const farmOptions = computed(() =>
  farmRows.value.filter((f) => !pendingIds.has(f.id)),
);
const farmSelectOptions = computed(() => [
  ...farmOptions.value.map((farm) => ({ value: farm.id, label: farm.name })),
]);
const activeMorph = ref("");
const statCards = computed(() => [
  ["管理农场", dashboard.value.farms || 0, "个"],
  ["地块总面积", dashboard.value.areaMu || 0, "亩"],
  ["待办农事", dashboard.value.pendingTasks || 0, "项"],
  ["累计产量", dashboard.value.yieldKg || 0, "kg"],
]);
const today = new Intl.DateTimeFormat("zh-CN", {
  year: "numeric",
  month: "long",
  day: "numeric",
  weekday: "long",
}).format(new Date());
let loadSequence = 0;

function message(value) {
  if (value && typeof value === "object") {
    (value.placement === "top" ? important : toast)(value.text);
    return;
  }
  toast(value);
}
function display(value) {
  if (typeof value === "boolean") return value ? "已启用" : "已停用";
  // 数字按千分位分组，长数字一眼读出量级（清单：自动分段）
  if (typeof value === "number")
    return value.toLocaleString("zh-CN", { maximumFractionDigits: 3 });
  if (typeof value === "string" && /^\d{4}-\d{2}-\d{2}T/.test(value)) {
    const time = new Date(value);
    if (!Number.isNaN(time.getTime()))
      return time.toLocaleString("zh-CN", { hour12: false });
  }
  return (
    labels[value] ||
    (value === null || value === undefined || value === ""
      ? "—"
      : String(value))
  );
}
function options(field) {
  if (field.source)
    return (
      field.source === "farms"
        ? farmRows.value
        : plotRows.value.filter(
            (p) => !formFarm.value || p.farmId === formFarm.value,
          )
    ).map((r) => ({
      value: r.id,
      label: r.name,
    }));
  return field.options.map((value) => ({ value, label: display(value) }));
}
const formFarmOptions = computed(() => [
  { value: "", label: "选择农场" },
  ...farmRows.value.map((farm) => ({ value: farm.id, label: farm.name })),
]);
function selectOptions(field) {
  return [
    { value: "", label: "请选择", disabled: true },
    ...options(field),
  ];
}
// key 标记触发本次写操作的按钮：只有它显示加载中，其余按钮在保存期间禁用以免重复提交。
async function action(work, success, key = "") {
  if (busy.value) return;
  busy.value = true;
  pending.value = key;
  error.value = "";
  try {
    await work();
    if (success) message(success);
  } catch (e) {
    // 登录页和对话框里的报错就近显示；页面上的操作失败用常驻报错条，不会滚出视线
    if (!identity.value || dialog.value || captureDevice.value)
      error.value = e.message;
    else reportFailure(e, () => action(work, success, key));
  } finally {
    busy.value = false;
    pending.value = "";
  }
}
async function load() {
  if (account.value?.mustChangePassword) return;
  const sequence = ++loadSequence;
  const target = page.value;
  if (platform.value) {
    const data = await api("/platform/tenants");
    if (sequence === loadSequence) rows.value = data;
    return;
  }
  const results = await Promise.allSettled([
    api("/farm-workspaces"),
    api("/plots"),
    api("/dashboard"),
    api("/tasks"),
  ]);
  if (sequence !== loadSequence) return;
  const failed = results.find(result => result.status === "rejected");
  if (failed) throw failed.reason;
  const [farms, plots, summary, taskList] = results.map(result => result.value);
  farmRows.value = farms;
  if (
    farms.length === 1 ||
    (farmScope.value && !farms.some((f) => f.id === farmScope.value)) ||
    (["daily", "operations", "ai", "cameras"].includes(target) && !farmScope.value)
  )
    farmScope.value = (farms.find((farm) => farm.operatingDemo && farm.name === "青禾设备联动演示场") || farms.find((farm) => farm.operatingDemo) || farms[0])?.id || "";
  plotRows.value = plots;
  dashboard.value = summary;
  tasks.value = taskList;
  if (
    target === "daily" ||
    target === "operations" ||
    target === "ai" ||
    target === "cameras" ||
    target === "dashboard" ||
    target === "devices" ||
    target === "simulation"
  ) {
    rows.value = [];
    return;
  }
  const data = scopedPage.value
    ? await api(
        "/" +
          target +
          (farmScope.value
            ? "?farmId=" + encodeURIComponent(farmScope.value)
            : ""),
      )
    : target === "farms"
      ? farms
      : await api("/" + target);
  if (sequence !== loadSequence) return;
  rows.value = data;
  if (target === "devices") {
    devices.value = data;
    const values = await api("/observations");
    if (sequence === loadSequence) observations.value = values;
  }
}
let reloadTicket = 0;
async function reload() {
  const ticket = ++reloadTicket;
  loading.value = true;
  error.value = "";
  try {
    await load();
    return ticket === reloadTicket;
  } catch (e) {
    if (ticket === reloadTicket && !isConnectionError(e)) error.value = e.message;
    return false;
  } finally {
    if (ticket === reloadTicket) loading.value = false;
  }
}
async function refreshActive() {
  if (refreshDisabled.value) return;
  const target = page.value, farm = farmScope.value, assistant = farmAssistant.value;
  refreshingPage.value = true;
  try {
    // AI 刷新有可等待的结果；其他模块继续沿用 revision 通知。
    if (target !== "ai") moduleRevision.value++;
    const [commonReady, moduleReady] = await Promise.all([
      reload(), target === "ai" ? assistant?.refresh() : true,
    ]);
    if (commonReady && moduleReady && page.value === target && farmScope.value === farm &&
        (target !== "ai" || farmAssistant.value === assistant))
      toast(target === "daily" ? "今日农场已刷新" : "数据已刷新");
  } finally {
    refreshingPage.value = false;
  }
}
async function dailyNavigate({ page: target, farmId, create }) {
  farmScope.value = farmId;
  await navigate(target);
  if (create && canCreate.value) openForm();
}
async function openFullAssistant({ conversationId = "", question = "" } = {}) {
  assistantConversationId.value = conversationId;
  assistantQuestion.value = question;
  await navigate({ page: "ai", farmId: farmScope.value });
}
async function openAssistantIrrigation(target = null) {
  await navigate({ page: "ai", farmId: farmScope.value });
  if (target?.type === "irrigation-run" && farmAssistant.value?.viewApproval) {
    await farmAssistant.value.viewApproval(target);
  } else if (farmAssistant.value) {
    farmAssistant.value.openIrrigation();
  }
}
async function askFromInsight({ farmId, question }) {
  if (aiSession.state.sending) {
    toast("请先等待当前 AI 回答完成，或停止生成后再带入排查问题。");
    return;
  }
  farmScope.value = farmId;
  assistantQuestion.value = question;
  await navigate({ page: "ai", farmId });
}
async function openDisplay(event) {
  if (screenOpening.value || !["ADMIN", "OPERATOR", "VIEWER"].includes(role.value) ||
      !account.value || account.value.mustChangePassword) return;
  if (!farmOptions.value.some(farm => farm.id === farmScope.value))
    farmScope.value = farmOptions.value[0]?.id || "";
  const actor = identity.value;
  displayOpener = event?.currentTarget || document.activeElement;
  screenOpening.value = true;
  try {
    const module = await import("./screen/FarmDisplay.vue");
    if (identity.value !== actor || !account.value || account.value.mustChangePassword) return;
    screenComponent.value = module.default;
    screenOpen.value = true;
  } catch (e) {
    toast("展示大屏加载失败，请刷新页面后重试。");
  } finally {
    screenOpening.value = false;
  }
}
async function leaveDisplay() {
  screenOpen.value = false;
  await nextTick();
  if (displayOpener?.isConnected) displayOpener.focus();
}
function selectDisplayFarm(id) {
  farmScope.value = id;
  selectFarm();
  if (page.value === "devices") {
    ledgerFocus.value = { ...ledgerFocus.value, farmId: id, deviceId: "" };
    navigationRevision.value++;
  }
}
async function displayNavigate(payload) {
  await leaveDisplay();
  await navigate(payload);
}
async function displayAsk(payload) {
  await leaveDisplay();
  await askFromInsight(payload);
}
async function displayAlerts() {
  await leaveDisplay();
  alertCenter.value?.open();
}
function alertChanged() {
  moduleRevision.value++;
  reload();
}
async function launchFarm(id) {
  farmScope.value = id;
  await navigate("dashboard");
  farmToOpen.value = id;
}
async function navigate(payload) {
  const id = typeof payload === "string" ? payload : payload.page;
  if (id === "display") return openDisplay();
  const morph = typeof payload === "string" ? "" : payload.morph || "";
  if (typeof payload === "object" && payload.farmId) farmScope.value = payload.farmId;
  if (id === "cameras") cameraFocus.value = typeof payload === "object" ? payload.deviceId || "" : "";
  if (id === "devices") ledgerFocus.value = typeof payload === "object" ? { farmId: payload.farmId || "", deviceId: payload.deviceId || "", cameraOnly: !!payload.cameraOnly } : { farmId: "", deviceId: "", cameraOnly: false };
  const update = async () => {
    page.value = id;
    navigationRevision.value++;
    // 农场概览直接打开顶栏选中的农场；未选农场时显示农场列表
    farmToOpen.value = id === "dashboard" ? farmScope.value : "";
    search.value = "";
    rows.value = [];
    await nextTick();
  };
  if (
    !morph ||
    !document.startViewTransition ||
    matchMedia("(prefers-reduced-motion: reduce)").matches
  ) {
    activeMorph.value = "";
    await update();
    await reload();
    return;
  }
  activeMorph.value = morph;
  document.documentElement.classList.add("ops-morphing");
  const transition = document.startViewTransition(update);
  await transition.finished.catch(() => {});
  activeMorph.value = "";
  document.documentElement.classList.remove("ops-morphing");
  await reload();
}
async function toggleTheme() {
  const previous = {
    themeMode: account.value.themeMode,
    accent: account.value.accent,
  };
  const next = {
    themeMode: colorMode.value === "DARK" ? "LIGHT" : "DARK",
    accent: account.value.accent || "FOREST",
  };
  applyAppearance(next);
  try {
    accountUpdated(await api("/account/appearance", "PUT", next));
    toast("主题已切换");
  } catch (e) {
    applyAppearance(previous);
    reportFailure({ ...e, message: "主题未保存：" + e.message }, toggleTheme);
  }
}
function choosePalette(item) {
  if (item.id === "action:settings") settingsOpen.value = true;
  else if (item.id === "action:theme") toggleTheme();
  else if (item.id === "action:signout") signOut();
  else navigate(item.id);
}
function onShortcut(event) {
  if (
    (event.ctrlKey || event.metaKey) &&
    event.key.toLowerCase() === "k" &&
    identity.value &&
    !account.value?.mustChangePassword
  ) {
    event.preventDefault();
    paletteOpen.value = true;
  }
}
const loginForm = ref(null);
const showLoginPassword = ref(false);
async function signIn() {
  await action(async () => {
    const result = await api("/auth/login", "POST", login);
    setToken(result.token);
    identity.value = result.identity;
    login.password = "";
    showLoginPassword.value = false;
    loginNotice.value = "";
    page.value =
      result.identity.role === "PLATFORM_ADMIN" ? "platform/tenants" : "daily";
    await loadAccount();
    await load();
  });
  // 账号或密码被拒绝时整张登录表单轻摇，报错写在按钮上方
  if (!identity.value && error.value) shakeElement(loginForm.value);
}
async function signOut() {
  await flushPending();
  await action(async () => {
    try {
      await api("/auth/logout", "POST");
    } finally {
      expire();
      error.value = "";
    }
  });
}
function expire() {
  aiSession.reset();
  screenOpen.value = false;
  resetFeedback();
  setToken("");
  identity.value = null;
  account.value = null;
  settingsOpen.value = false;
  farmScope.value = "";
  farmToOpen.value = "";
  rows.value = [];
  farmRows.value = [];
  plotRows.value = [];
  dashboard.value = {};
  tasks.value = [];
  observations.value = [];
  dialog.value = false;
  captureDevice.value = null;
  paletteOpen.value = false;
  assistantQuestion.value = "";
  assistantConversationId.value = "";
  loading.value = false;
  ++loadSequence;
}
function openForm(row = null) {
  editing.value = row;
  Object.keys(model).forEach((key) => delete model[key]);
  for (const field of fields.value)
    model[field.key] = row?.[field.key] ?? (field.type === "number" ? 1 : "");
  formFarm.value =
    row?.farmId ||
    plotRows.value.find((p) => p.id === row?.plotId)?.farmId ||
    farmScope.value;
  if (!row && page.value === "plots") model.farmId = farmScope.value;
  if (!row && ["production", "tasks"].includes(page.value))
    model[page.value === "production" ? "recordDate" : "dueDate"] = new Date(
      Date.now() - new Date().getTimezoneOffset() * 60000,
    )
      .toISOString()
      .slice(0, 10);
  const availablePlots = plotRows.value.filter(
    (p) => !formFarm.value || p.farmId === formFarm.value,
  );
  if (
    !row &&
    fields.value.some((f) => f.key === "plotId") &&
    availablePlots.length === 1
  )
    model.plotId = availablePlots[0].id;
  dialog.value = true;
  error.value = "";
}
async function save() {
  await action(async () => {
    const body = Object.fromEntries(
      fields.value.map((field) => [
        field.key,
        field.type === "number" ? Number(model[field.key]) : model[field.key],
      ]),
    );
    await api(
      "/" + page.value + (editing.value ? "/" + editing.value.id : ""),
      editing.value ? "PUT" : "POST",
      body,
    );
    dialog.value = false;
    await load();
  }, "已保存", "save");
}
// 普通删除不再弹确认框：先隐藏，提示条上 6 秒内可撤销，到时才提交（清单：事后撤销替代二次确认）
function remove(row) {
  const target = page.value;
  const linked = tasks.value.filter((t) => t.plotId === row.id).length;
  if (target === "plots" && linked) {
    failure(`“${row.name}”还有 ${linked} 项农事任务，不能删除地块。`);
    return;
  }
  deferDelete({
    id: row.id,
    label: row.name,
    commit: (keepalive) =>
      api("/" + target + "/" + row.id, "DELETE", undefined, { keepalive }),
    settled: reload,
  });
}
async function transition(row, status) {
  await action(async () => {
    await api("/" + page.value + "/" + row.id + "/status", "PATCH", { status });
    await load();
  }, "状态已更新", "status:" + row.id + ":" + status);
}
// 启用/停用是可逆的轻操作：界面立即切换，请求失败再回滚并常驻报错（清单：乐观更新）
const toggling = new Set();
async function toggle(row) {
  if (toggling.has(row.id)) return;
  const target = page.value,
    next = !row.enabled,
    name = row.displayName || row.name || row.username;
  const record = rows.value.find((r) => r.id === row.id);
  toggling.add(row.id);
  record.enabled = next;
  try {
    await api("/" + target + "/" + row.id, "PATCH", { enabled: next });
    message(`已${next ? "启用" : "停用"}“${name}”`);
  } catch (e) {
    record.enabled = !next;
    reportFailure(
      { ...e, message: `未能${next ? "启用" : "停用"}“${name}”：${e.message}` },
      () => toggle({ ...row, enabled: !next }),
    );
  } finally {
    toggling.delete(row.id);
  }
}
async function sample(row) {
  await action(async () => {
    await api("/devices/" + row.id + "/sample", "POST");
    await load();
  }, "模拟记录已生成", "sample:" + row.id);
}
async function recordObservation() {
  await action(async () => {
    const now = new Date();
    const local = new Date(now.getTime() - now.getTimezoneOffset() * 60000)
      .toISOString()
      .slice(0, 19);
    await api("/devices/" + captureDevice.value.id + "/observations", "POST", {
      value: Number(captureValue.value),
      measuredAt: local,
    });
    captureDevice.value = null;
    await load();
  }, "监测记录已保存", "observe");
}
function flushOnLeave() {
  flushPending(true);
}
onMounted(async () => {
  window.addEventListener("session-expired", expire);
  window.addEventListener("pagehide", flushOnLeave);
  window.addEventListener("keydown", onShortcut);
  if (!sessionStorage.getItem("zhinong-session")) return;
  await action(async () => {
    identity.value = await api("/auth/me");
    page.value = platform.value ? "platform/tenants" : "daily";
    await loadAccount();
    await load();
  });
});
onUnmounted(() => {
  stopReadiness?.();
  window.removeEventListener("session-expired", expire);
  window.removeEventListener("pagehide", flushOnLeave);
  window.removeEventListener("keydown", onShortcut);
});
</script>

<template>
  <div v-if="!identity" class="login-layout">
    <section class="login-story">
      <div class="brand">
        <img class="brand-mark" :src="brandLogo" alt="智禾农场标志" width="40" height="40" />
        智禾农场
      </div>
      <LoginField />
    </section>
    <section class="login-panel">
      <form ref="loginForm" v-validate aria-labelledby="login-title" @submit.prevent="signIn">
        <div class="login-mobile-brand">
          <img :src="brandLogo" alt="" width="32" height="32" />智禾农场
        </div>
        <header class="login-heading">
          <h1 id="login-title">田间有序<br />经营有据</h1>
          <p class="muted">连接每一块田地的种植、农事与收获。</p>
        </header>
        <label
          >租户代码<input
            v-model.trim="login.tenantCode"
            name="tenantCode"
            :disabled="busy"
            aria-describedby="login-tenant-help"
            required
            autocomplete="organization"
            autocapitalize="none"
            spellcheck="false"
            enterkeyhint="next"
            placeholder="例如 demo-a"
        /></label>
        <p id="login-tenant-help" class="login-field-help">使用农场管理员提供的代码</p>
        <label
          >登录账号<input
            v-model.trim="login.username"
            name="username"
            :disabled="busy"
            placeholder="请输入成员账号"
            required
            autocomplete="username"
            autocapitalize="none"
            spellcheck="false"
            enterkeyhint="next"
        /></label>
        <label for="login-password">密码</label>
        <div class="login-password">
          <input
            id="login-password"
            name="password"
            v-model="login.password"
            :disabled="busy"
            required
            :type="showLoginPassword ? 'text' : 'password'"
            placeholder="请输入密码"
            autocomplete="current-password"
            enterkeyhint="go"
          />
          <button
            type="button"
            class="login-password-toggle"
            :disabled="busy"
            :aria-label="showLoginPassword ? '隐藏密码' : '显示密码'"
            :aria-pressed="showLoginPassword"
            aria-controls="login-password"
            @click="showLoginPassword = !showLoginPassword"
          >{{ showLoginPassword ? '隐藏' : '显示' }}</button>
        </div>
        <p v-if="error" role="alert" class="error">{{ error }}</p>
        <p v-if="loginNotice" role="status" class="success">
          {{ loginNotice }}
        </p>
        <p v-if="service.state === 'starting'" role="status" class="login-service">
          正在准备农场数据，首次启动通常需要 1–3 分钟，准备好后即可登录。
        </p>
        <p v-else-if="service.state === 'unreachable'" role="status" class="login-service">
          暂时连不上本地服务，请确认服务已启动，恢复后这里会自动更新。
        </p>
        <button
          class="primary login-button"
          :disabled="busy || service.state === 'starting'"
          :aria-busy="busy || service.state === 'starting'"
        >
          {{ busy ? "正在登录…" : service.state === "starting" ? "服务准备中…" : "进入工作空间" }}
        </button>
        <details v-if="service.demo" class="login-hint">
          <summary>查看本地演示账号说明</summary>
          <p>租户代码：demo-a / demo-b<br />演示密码请查看本机启动提示。</p>
        </details>
      </form>
    </section>
  </div>
  <div v-else :class="['app-layout', { 'sidebar-collapsed': collapsed }]">
    <div
      class="page-loading"
      :class="{ active: loading }"
      role="status"
      aria-live="polite"
    >
      <span class="sr-only">{{ loading ? "正在加载页面" : "" }}</span>
    </div>
    <p v-if="attention" id="attention-note" class="sr-only">
      {{ attention.label }}
    </p>
    <aside id="app-sidebar" class="sidebar" aria-label="主导航">
      <div class="sidebar-head">
        <div class="brand">
          <img class="brand-mark" :src="brandLogo" alt="智禾农场标志" width="40" height="40" />
          <span class="sidebar-label">智禾农场<small>ZHIHE FARM</small></span>
        </div>
        <button
          type="button"
          class="icon-button sidebar-toggle"
          aria-controls="app-sidebar"
          :aria-expanded="!collapsed"
          :aria-label="collapsed ? '展开侧栏' : '收起侧栏'"
          :title="collapsed ? '展开侧栏' : '收起侧栏'"
          @click="toggleSidebar"
        >
          <AppIcon name="sidebar" />
        </button>
      </div>
      <div class="tenant-box">
        <span class="tenant-dot"></span>
        <div class="sidebar-label">
          {{ identity.tenantName }}<small>{{ display(identity.role) }}</small>
        </div>
      </div>
      <button
        type="button"
        class="sidebar-search"
        aria-haspopup="dialog"
        title="搜索页面（Ctrl K）"
        :disabled="account?.mustChangePassword"
        @click="paletteOpen = true"
      >
        <AppIcon name="search" /><span class="sidebar-label">搜索页面…</span
        ><kbd class="sidebar-label">Ctrl K</kbd>
      </button>
      <nav aria-label="工作空间">
        <div v-for="group in menuGroups" :key="group.label" class="nav-group">
          <p class="nav-caption sidebar-label">{{ group.label }}</p>
          <button
            v-for="item in group.items"
            :key="item.id"
            :aria-label="item.title"
            :title="collapsed ? item.title : undefined"
            :aria-current="page === item.id ? 'page' : undefined"
            :aria-describedby="
              item.id === 'daily' && attention ? 'attention-note' : undefined
            "
            :class="{ selected: page === item.id }"
            @click="navigate(item.id)"
            :disabled="account?.mustChangePassword"
          >
            <AppIcon :name="item.icon" /><span class="nav-label sidebar-label">{{
              item.title
            }}</span
            ><span
              v-if="item.id === 'daily' && attention"
              :class="['nav-count', attention.tone]"
              :title="attention.label"
              aria-hidden="true"
              >{{ attention.total }}</span
            >
          </button>
        </div>
      </nav>
      <div class="sidebar-bottom">
        <div
          class="sidebar-avatar"
          role="img"
          aria-label="当前用户头像"
        >
          <img
            v-if="account?.avatarData"
            class="avatar"
            :src="account.avatarData"
            alt=""
            aria-hidden="true"
          />
          <span v-else class="avatar" aria-hidden="true">{{
            identity.displayName.slice(0, 1)
          }}</span>
        </div>
        <button
          type="button"
          class="icon-button sidebar-action"
          aria-label="账号设置"
          :title="collapsed ? '账号设置' : undefined"
          @click="settingsOpen = true"
        >
          <AppIcon name="settings" />
        </button>
        <button
          type="button"
          class="icon-button sidebar-action"
          :aria-label="colorMode === 'DARK' ? '切换到浅色主题' : '切换到深色主题'"
          :title="colorMode === 'DARK' ? '切换到浅色主题' : '切换到深色主题'"
          :disabled="!account"
          @click="toggleTheme"
        >
          <AppIcon name="moon" />
        </button>
        <button
          type="button"
          class="icon-button sidebar-action"
          @click="signOut"
          title="退出登录"
          aria-label="退出登录"
          :disabled="busy"
        >
          <AppIcon name="logout" />
        </button>
      </div>
    </aside>
    <div class="main-shell">
      <header class="topbar">
        <span class="topbar-brand"
          ><img class="brand-mark" :src="brandLogo" alt="智禾农场标志" width="40" height="40" /><span>智禾农场</span></span
        ><span class="topbar-trail"
          >工作空间 <b>/</b> {{ current.title }}</span
        ><FarmSelect
          v-if="!platform && farmRows.length && !account?.mustChangePassword"
          v-model="farmScope"
          :options="farmSelectOptions"
          :disabled="pageLoading"
          @change="selectFarm"
        />
        <span class="topbar-meta">{{ today }}</span
        ><StatusCapsule @refresh="refreshActive" />
        <button
          v-if="!platform && account && !account.mustChangePassword"
          type="button"
          class="display-entry"
          aria-label="进入展示大屏"
          aria-haspopup="dialog"
          title="进入展示大屏"
          :disabled="screenOpening"
          :aria-busy="screenOpening"
          @click="openDisplay"
        ><AppIcon name="screen" /><span>{{ screenOpening ? "加载中…" : "展示大屏" }}</span></button>
        <AlertCenter
          v-if="!platform && account && !account.mustChangePassword"
          ref="alertCenter"
          :farm-id="farmScope"
          :farm-name="farmRows.find(f => f.id === farmScope)?.name"
          :role="role"
          :revision="moduleRevision"
          @navigate="navigate"
          @ask="askFromInsight"
          @changed="alertChanged"
        />
        <button
          type="button"
          class="icon-button topbar-search"
          aria-label="搜索页面"
          :disabled="account?.mustChangePassword"
          @click="paletteOpen = true"
        >
          <AppIcon name="search" />
        </button>
      </header>
      <main
        v-if="account && !account.mustChangePassword"
        :style="activeMorph && page !== 'operations' ? { viewTransitionName: 'ops-morph' } : null"
      >
        <div class="page-heading" :class="{ 'page-heading-ai': page === 'ai' }">
          <div class="page-heading-copy">
            <h1>{{ current.title }}</h1>
            <p class="muted">{{ current.description }}</p>
          </div>
          <div class="page-heading-actions">
          <div v-if="page === 'ai' && farmScope" id="farm-assistant-companion" class="page-heading-companion"></div>
          <button
            class="outline with-icon"
            @click="refreshActive"
            :disabled="refreshDisabled"
            :aria-busy="pageLoading"
            :title="page === 'ai' && farmAssistant?.sending ? '回答生成中，请等待回答完成或手动停止后刷新' : undefined"
          >
            <AppIcon name="refresh" />{{ pageLoading ? "正在加载…" : "刷新数据" }}
          </button>
          </div>
        </div>
        <p v-if="connection.state === 'offline'" role="alert" class="error">
          无法连接本地服务，页面上的数据可能不是最新的。
          <button class="text-button" :disabled="refreshDisabled" @click="refreshActive">重新连接</button>
        </p>
        <p v-if="error" role="alert" class="error">{{ error }}</p>
        <FarmBriefing
          v-if="!platform && ['daily', 'operations'].includes(page)"
          :farm-id="farmScope"
          :revision="moduleRevision"
          @ask="askFromInsight"
          @navigate="navigate"
          @alerts="alertCenter?.open()"
        />
        <DailyFarm
          v-if="page === 'daily'"
          :identity="identity"
          :farms="farmRows"
          :plots="plotRows"
          v-model:farm-id="farmScope"
          :revision="moduleRevision"
          @navigate="dailyNavigate"
          @farm="launchFarm"
          @notice="message"
        />
        <FarmAssistant v-else-if="page === 'ai'" ref="farmAssistant" :key="farmScope" :farm-id="farmScope" :role="role" companion-target="#farm-assistant-companion" :initial-question="assistantQuestion" :initial-conversation-id="assistantConversationId" :page-refreshing="refreshingPage" @farm="launchFarm" @question-prepared="assistantQuestion = ''" @conversation-selected="assistantConversationId = ''" />
        <FieldCameras v-else-if="page === 'cameras'" :key="farmScope" :farm-id="farmScope" :initial-device-id="cameraFocus" :revision="moduleRevision" @manage="navigate({ page: 'devices', ...$event, cameraOnly: true })" />
        <OperationsOverview
          v-else-if="page === 'operations'"
          :farm-id="farmScope"
          :revision="moduleRevision"
          @navigate="navigate"
        />
        <FarmHub
          v-else-if="['dashboard', 'farms'].includes(page)"
          :key="page + ':' + navigationRevision"
          :role="role"
          :revision="moduleRevision"
          :initial-farm-id="farmToOpen"
          @farm="farmScope = $event"
          @changed="reload"
        />
        <DeviceManager
          v-else-if="page === 'devices'"
          :key="navigationRevision"
          :role="role"
          :revision="moduleRevision"
          :initial-farm-id="ledgerFocus.farmId"
          :initial-device-id="ledgerFocus.deviceId"
          :camera-only="ledgerFocus.cameraOnly"
          @camera="navigate({ page: 'cameras', farmId: $event.farmId, deviceId: $event.id })"
          @farm="launchFarm"
        />
        <SimulationPage
          v-else-if="page === 'simulation'"
          :key="simulationKey"
          :role="role"
          :initial-farm-id="farmScope"
          @farm="farmScope = $event"
        />
        <template v-else>
          <section class="panel table-panel" :aria-busy="loading">
            <div class="table-toolbar">
              <div>
                <h2>
                  {{ current.title }}列表
                  <span class="count">{{ filtered.length }}</span>
                </h2>
                <small>数据范围：{{ identity.tenantName }}</small>
              </div>
              <div class="table-tools">
                <button
                  v-if="page === 'tasks' && admin"
                  class="primary"
                  @click="navigate('daily')"
                >
                  到今日农场安排
                </button>
                <input
                  v-model="search"
                  type="search"
                  enterkeyhint="search"
                  autocomplete="off"
                  aria-label="搜索当前列表"
                  placeholder="搜索当前列表…"
                /><button
                  v-if="canCreate"
                  class="primary"
                  @click="openForm()"
                  :disabled="busy"
                >
                  ＋ 新增{{ current.noun }}
                </button>
              </div>
            </div>
            <div class="table-scroll">
              <table class="stack-table" role="table">
                <!-- 显式 ARIA 角色：手机上改成卡片排列后，读屏软件仍按表格朗读 -->
                <thead role="rowgroup">
                  <tr role="row">
                    <th
                      v-for="col in current.columns"
                      :key="col[0]"
                      role="columnheader"
                    >
                      {{ col[1] }}
                    </th>
                    <th v-if="writer || platform" role="columnheader">操作</th>
                  </tr>
                </thead>
                <tbody role="rowgroup">
                  <tr v-for="row in filtered" :key="row.id" role="row">
                    <td
                      v-for="col in current.columns"
                      :key="col[0]"
                      role="cell"
                      :data-label="col[1]"
                    >
                      <span
                        v-if="col[0] === 'status'"
                        :class="['badge', row.status.toLowerCase()]"
                        >{{ display(row[col[0]]) }}</span
                      ><span v-else>{{ display(row[col[0]]) }}</span>
                    </td>
                    <td
                      v-if="writer || platform"
                      class="actions"
                      role="cell"
                      data-label="操作"
                    >
                      <template
                        v-if="admin && ['farms', 'plots'].includes(page)"
                        ><button @click="openForm(row)">编辑</button
                        ><button
                          class="danger-text"
                          @click="remove(row)"
                          :disabled="busy"
                        >
                          删除
                        </button></template
                      >
                      <template v-if="page === 'tasks'"
                        ><button
                          @click="
                            dailyNavigate({
                              page: 'daily',
                              farmId: plotRows.find((p) => p.id === row.plotId)
                                ?.farmId,
                            })
                          "
                        >
                          查看作业与回执
                        </button></template
                      >
                      <template v-if="page === 'plantings'"
                        ><button
                          v-if="row.status === 'PLANNED'"
                          @click="transition(row, 'ACTIVE')"
                          :disabled="busy"
                          :aria-busy="pending === 'status:' + row.id + ':ACTIVE'"
                        >
                          开始种植</button
                        ><button
                          v-if="row.status === 'ACTIVE'"
                          @click="transition(row, 'FINISHED')"
                          :disabled="busy"
                          :aria-busy="pending === 'status:' + row.id + ':FINISHED'"
                        >
                          结束周期
                        </button></template
                      >
                      <template v-if="page === 'devices'"
                        ><button
                          v-if="row.adapter === 'SIMULATED'"
                          @click="sample(row)"
                          :disabled="busy"
                          :aria-busy="pending === 'sample:' + row.id"
                        >
                          模拟采集</button
                        ><button
                          v-else
                          @click="
                            captureDevice = row;
                            captureValue = '';
                          "
                        >
                          录入数据
                        </button></template
                      >
                      <span
                        v-if="page === 'members' && row.id === identity.memberId"
                        class="muted"
                        >当前登录账号</span
                      >
                      <button
                        v-else-if="['members', 'platform/tenants'].includes(page)"
                        @click="toggle(row)"
                      >
                        {{ row.enabled ? "停用" : "启用" }}
                      </button>
                    </td>
                  </tr>
                </tbody>
                <tbody v-if="loading && !rows.length" aria-hidden="true">
                  <tr v-for="n in 4" :key="n" class="skeleton-row">
                    <td
                      v-for="col in current.columns.length +
                      (writer || platform ? 1 : 0)"
                      :key="col"
                    >
                      <span class="skeleton"></span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <!-- 空状态给出下一步：搜索无结果可一键清除；按农场筛选为空可看全部；没有数据时直接新增第一条 -->
            <div v-if="!filtered.length && !loading" class="empty empty-state">
              <template v-if="search">
                <p>没有匹配“{{ search }}”的{{ current.noun }}。</p>
                <button class="outline" @click="search = ''">清除搜索</button>
              </template>
              <template
                v-else-if="
                  ['plantings', 'production'].includes(page) && !scopePlots.length
                "
              >
                <p>还没有地块。{{ current.noun }}要登记到具体地块上，请先建立地块。</p>
                <button v-if="admin" class="primary" @click="navigate('plots')">
                  去地块管理
                </button>
              </template>
              <template v-else-if="scopedPage && farmScope">
                <p>
                  “{{ farmOptions.find((f) => f.id === farmScope)?.name }}”下还没有{{
                    current.noun
                  }}。
                </p>
                <button v-if="canCreate" class="primary" @click="openForm()">
                  ＋ 新增{{ current.noun }}</button
                ><button
                  v-if="!farmRequired && farmOptions.length > 1"
                  class="outline"
                  @click="
                    farmScope = '';
                    refreshActive();
                  "
                >
                  查看全部农场
                </button>
              </template>
              <template v-else>
                <p>{{ current.empty }}</p>
                <button v-if="canCreate" class="primary" @click="openForm()">
                  ＋ 新增第一条{{ current.noun }}</button
                ><button
                  v-else-if="page === 'tasks' && admin"
                  class="primary"
                  @click="navigate('daily')"
                >
                  到今日农场安排
                </button>
              </template>
            </div>
          </section>
          <section
            v-if="page === 'devices'"
            class="panel table-panel observation-panel"
          >
            <div class="section-title">
              <h2>监测记录</h2>
              <small>模拟数据仅用于演示，不代表真实设备测量结果</small>
            </div>
            <div class="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>监测点</th>
                    <th>数值</th>
                    <th>来源</th>
                    <th>采集时间</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="row in observations" :key="row.id">
                    <td>
                      {{ devices.find((d) => d.id === row.deviceId)?.name }}
                    </td>
                    <td>
                      {{ row.measuredValue }}
                      {{ devices.find((d) => d.id === row.deviceId)?.unit }}
                    </td>
                    <td>{{ display(row.source) }}</td>
                    <td>{{ display(row.measuredAt) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p v-if="!observations.length" class="empty">
              选择监测点进行人工录入或模拟采集。
            </p>
          </section>
        </template>
        <footer>智禾农场 · 多租户农场管理 <span>当前版本 0.3.0</span></footer>
      </main>
      <main v-else>
        <h1>请先修改临时密码</h1>
        <p>管理员重置密码后，需要设置自己的新密码才能进入工作空间。</p>
        <button @click="settingsOpen = true">打开账号安全</button>
      </main>
    </div>
    <nav
      v-if="!account?.mustChangePassword"
      class="tabbar"
      aria-label="常用页面"
    >
      <button
        v-for="item in tabItems"
        :key="item.id"
        :aria-current="page === item.id ? 'page' : undefined"
        :aria-describedby="
          item.id === 'daily' && attention ? 'attention-note' : undefined
        "
        :class="{ selected: page === item.id }"
        @click="navigate(item.id)"
      >
        <AppIcon :name="item.icon" /><span>{{ item.title }}</span
        ><i
          v-if="item.id === 'daily' && attention"
          :class="['tab-dot', attention.tone]"
          aria-hidden="true"
        ></i>
      </button>
      <button aria-haspopup="dialog" @click="paletteOpen = true">
        <AppIcon name="more" /><span>更多</span>
      </button>
    </nav>
    <FloatingAssistant
      v-if="!platform && account && !account.mustChangePassword && farmScope"
      v-show="page !== 'ai'"
      :farm-id="farmScope"
      :farm-name="farmRows.find(f => f.id === farmScope)?.name"
      :page-id="page"
      :focus="assistantFocus"
      :role="role"
      @open-full="openFullAssistant"
      @irrigation="openAssistantIrrigation"
    />
    <ConfirmDialog />
    <component
      :is="screenComponent"
      v-if="screenOpen && !platform && account && !account.mustChangePassword"
      :farm-id="farmScope"
      @update:farm-id="selectDisplayFarm"
      :farms="farmOptions"
      :role="role"
      @close="leaveDisplay"
      @navigate="displayNavigate"
      @ask="displayAsk"
      @alerts="displayAlerts"
    />
    <CommandPalette
      v-model:open="paletteOpen"
      :items="paletteItems"
      @choose="choosePalette"
    />
    <FeedbackRegion />
    <SettingsDialog
      v-if="settingsOpen && account"
      :account="account"
      @updated="accountUpdated"
      @close="settingsOpen = false"
      @password-changed="passwordChanged"
    />
    <ModalDialog
      v-if="dialog"
      :label="(editing ? '编辑' : '新增') + current.noun"
      :locked="busy"
      @close="dialog = false"
    >
        <div class="section-title">
          <h2>{{ editing ? "编辑" : "新增" }}{{ current.noun }}</h2>
          <button
            class="close-button"
            @click="dialog = false"
            :disabled="busy"
            aria-label="关闭"
          >
            ×
          </button>
        </div>
        <form v-validate @submit.prevent="save">
          <label v-if="fields.some((f) => f.source === 'plots')"
            >所属农场<SelectMenu
              v-model="formFarm"
              aria-label="表单所属农场"
              :options="formFarmOptions"
              @change="model.plotId = ''"
            /></label
          >
          <label v-for="field in fields" :key="field.key"
            >{{ field.label }}<small v-if="field.optional">（选填）</small
            ><SelectMenu
              v-if="field.type === 'select'"
              v-model="model[field.key]"
              :options="selectOptions(field)"
              :aria-label="field.label"
              required
            />
            <DatePicker
              v-else-if="field.type === 'date'"
              v-model="model[field.key]"
              :aria-label="field.label"
              :required="!field.optional"
            />
            <input
              v-else
              v-model="model[field.key]"
              :type="field.type"
              :required="!field.optional"
              :maxlength="field.max"
              :minlength="field.minLength"
              :min="field.min"
              :max="field.type === 'number' ? field.max : undefined"
              :step="field.type === 'number' ? '0.01' : undefined"
              :autocomplete="
                field.type === 'password' ? 'new-password' : 'off'
              "
          /></label>
          <p v-if="error" class="error" role="alert">{{ error }}</p>
          <div class="modal-actions">
            <button
              type="button"
              class="outline"
              @click="dialog = false"
              :disabled="busy"
            >
              取消</button
            ><button
              class="primary"
              :disabled="busy"
              :aria-busy="pending === 'save'"
            >
              {{ pending === "save" ? "正在保存…" : "保存" }}
            </button>
          </div>
        </form>
    </ModalDialog>
    <ModalDialog
      v-if="captureDevice"
      label="录入监测数据"
      :locked="busy"
      @close="captureDevice = null"
    >
        <h2>录入监测数据</h2>
        <p class="muted">
          {{ captureDevice.name }} · {{ display(captureDevice.metric) }}
        </p>
        <form v-validate @submit.prevent="recordObservation">
          <label
            >监测值（{{ captureDevice.unit }}）<input
              v-model="captureValue"
              type="number"
              step="0.001"
              :min="captureDevice.metric === 'TEMPERATURE' ? -80 : 0"
              max="100"
              required
          /></label>
          <p v-if="error" class="error">{{ error }}</p>
          <div class="modal-actions">
            <button
              type="button"
              class="outline"
              @click="captureDevice = null"
              :disabled="busy"
            >
              取消</button
            ><button
              class="primary"
              :disabled="busy"
              :aria-busy="pending === 'observe'"
            >
              保存记录
            </button>
          </div>
        </form>
    </ModalDialog>
  </div>
</template>
