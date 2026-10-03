<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from "vue";
import { api, setToken } from "./api";
import { forms, labels, menus } from "./catalog";
import FarmHub from "./workspace/FarmHub.vue";
import DeviceManager from "./workspace/DeviceManager.vue";
import "./workspace/workspace.css";
import SettingsDialog from "./account/SettingsDialog.vue";
import { applyAppearance, colorMode } from "./account/appearance";
import SimulationPage from "./simulation/SimulationPage.vue";
import DailyFarm from "./fieldwork/DailyFarm.vue";
import AppIcon from "./ui/AppIcon.vue";
import CommandPalette from "./ui/CommandPalette.vue";
import ModalDialog from "./ui/ModalDialog.vue";
import "./ui/shell.css";

const identity = ref(null);
const account = ref(null),
  settingsOpen = ref(false),
  farmScope = ref(""),
  formFarm = ref("");
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
function passwordChanged() {
  expire();
  message("密码已修改，请使用新密码重新登录。");
}
const page = ref("daily");
const moduleRevision = ref(0);
const farmToOpen = ref("");
const navigationRevision = ref(0);
const error = ref("");
const notice = ref("");
// busy 只锁写操作；页面加载用 loading，导航不再被写操作或加载阻塞。
const busy = ref(false);
const loading = ref(false);
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
  tenantCode: "demo-a",
  username: "admin",
  password: "",
});
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
// 顶栏“当前农场”是各页面共用的农场范围；今日农场与经营模拟必须选定一座农场。
const farmRequired = computed(() =>
  ["daily", "simulation"].includes(page.value),
);
const simulationKey = ref(0);
function selectFarm() {
  if (page.value === "daily") return;
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
  decorated.value.filter((row) =>
    JSON.stringify(row).toLowerCase().includes(search.value.toLowerCase()),
  ),
);
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
let timer;
let loadSequence = 0;

function message(value) {
  notice.value = value;
  clearTimeout(timer);
  timer = setTimeout(() => {
    notice.value = "";
  }, 3500);
}
function display(value) {
  if (typeof value === "boolean") return value ? "已启用" : "已停用";
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
    error.value = e.message;
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
  const [farms, plots, summary, taskList] = await Promise.all([
    api("/farms"),
    api("/plots"),
    api("/dashboard"),
    api("/tasks"),
  ]);
  if (sequence !== loadSequence) return;
  farmRows.value = farms;
  if (
    farms.length === 1 ||
    (farmScope.value && !farms.some((f) => f.id === farmScope.value)) ||
    (target === "daily" && !farmScope.value)
  )
    farmScope.value = farms[0]?.id || "";
  plotRows.value = plots;
  dashboard.value = summary;
  tasks.value = taskList;
  if (
    target === "daily" ||
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
  } catch (e) {
    if (ticket === reloadTicket) error.value = e.message;
  } finally {
    if (ticket === reloadTicket) loading.value = false;
  }
}
async function refreshActive() {
  moduleRevision.value++;
  await reload();
}
async function dailyNavigate({ page: target, farmId, create }) {
  farmScope.value = farmId;
  await navigate(target);
  if (create && canCreate.value) openForm();
}
async function launchFarm(id) {
  farmScope.value = id;
  await navigate("dashboard");
  farmToOpen.value = id;
}
async function navigate(id) {
  page.value = id;
  navigationRevision.value++;
  // 农场概览直接打开顶栏选中的农场；选“全部农场”时显示农场列表
  farmToOpen.value = id === "dashboard" ? farmScope.value : "";
  search.value = "";
  rows.value = [];
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
  } catch (e) {
    applyAppearance(previous);
    error.value = "主题未保存：" + e.message;
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
async function signIn() {
  await action(async () => {
    const result = await api("/auth/login", "POST", login);
    setToken(result.token);
    identity.value = result.identity;
    login.password = "";
    page.value =
      result.identity.role === "PLATFORM_ADMIN" ? "platform/tenants" : "daily";
    await loadAccount();
    await load();
  });
}
async function signOut() {
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
async function remove(row) {
  if (
    !window.confirm("确定删除“" + row.name + "”？已有业务引用的记录不能删除。")
  )
    return;
  await action(async () => {
    await api("/" + page.value + "/" + row.id, "DELETE");
    await load();
  }, "已删除", "delete:" + row.id);
}
async function transition(row, status) {
  await action(async () => {
    await api("/" + page.value + "/" + row.id + "/status", "PATCH", { status });
    await load();
  }, "状态已更新", "status:" + row.id + ":" + status);
}
async function toggle(row) {
  await action(async () => {
    await api("/" + page.value + "/" + row.id, "PATCH", {
      enabled: !row.enabled,
    });
    await load();
  }, "启用状态已更新", "toggle:" + row.id);
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
onMounted(async () => {
  window.addEventListener("session-expired", expire);
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
  window.removeEventListener("session-expired", expire);
  window.removeEventListener("keydown", onShortcut);
  clearTimeout(timer);
});
</script>

<template>
  <div v-if="!identity" class="login-layout">
    <section class="login-story">
      <div class="brand"><span class="brand-mark">禾</span>智禾农场</div>
      <div>
        <p class="eyebrow">农场经营工作空间</p>
        <h1>田间有序<br />经营有据</h1>
        <p class="story-copy">
          连接每一块田地的种植、农事与收获。<br />为不同经营主体提供独立的农场工作空间。
        </p>
      </div>
      <div class="field-art" aria-hidden="true">
        <i></i><i></i><i></i><i></i><i></i>
      </div>
      <small>多租户农场管理 · 0.3.0</small>
    </section>
    <section class="login-panel">
      <form @submit.prevent="signIn">
        <p class="eyebrow">欢迎回来</p>
        <h2>登录农场工作空间</h2>
        <p class="muted">请输入租户代码及您的成员账号</p>
        <label
          >租户代码<input
            v-model.trim="login.tenantCode"
            required
            autocomplete="organization"
            placeholder="例如 demo-a"
        /></label>
        <label
          >登录账号<input
            v-model.trim="login.username"
            required
            autocomplete="username"
        /></label>
        <label
          >密码<input
            v-model="login.password"
            required
            type="password"
            autocomplete="current-password"
        /></label>
        <p v-if="error" role="alert" class="error">{{ error }}</p>
        <p v-if="notice" role="status" class="success">{{ notice }}</p>
        <button class="primary login-button" :disabled="busy" :aria-busy="busy">
          {{ busy ? "正在登录…" : "进入工作空间 →" }}
        </button>
        <p class="login-hint">
          本地演示租户：demo-a / demo-b<br />演示密码由启动脚本在本机生成。
        </p>
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
          <span class="brand-mark">禾</span
          ><span class="sidebar-label">智禾农场<small>ZHIHE FARM</small></span>
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
      <div class="tenant-box" :title="identity.tenantName">
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
        <button
          class="account-entry"
          aria-label="账号设置"
          :title="collapsed ? '账号设置' : undefined"
          @click="settingsOpen = true"
        >
          <img
            v-if="account?.avatarData"
            class="avatar"
            :src="account.avatarData"
            alt="头像"
          />
          <span v-else class="avatar">{{
            identity.displayName.slice(0, 1)
          }}</span>
          <span class="sidebar-label"
            >{{ identity.displayName
            }}<small>{{ identity.username }} · 设置</small></span
          >
        </button>
        <button
          type="button"
          class="icon-button"
          :aria-label="colorMode === 'DARK' ? '切换到浅色主题' : '切换到深色主题'"
          :title="colorMode === 'DARK' ? '切换到浅色主题' : '切换到深色主题'"
          :disabled="!account"
          @click="toggleTheme"
        >
          <AppIcon name="moon" />
        </button>
        <button
          type="button"
          class="icon-button"
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
          ><span class="brand-mark">禾</span>智禾农场</span
        ><span class="topbar-trail"
          >工作空间 <b>/</b> {{ current.title }}</span
        ><label
          v-if="!platform && farmRows.length && !account?.mustChangePassword"
          class="topbar-farm"
          ><span class="tenant-dot" aria-hidden="true"></span
          ><select
            v-model="farmScope"
            aria-label="当前农场"
            :disabled="loading"
            @change="selectFarm"
          >
            <option value="" :disabled="farmRequired">全部农场</option>
            <option v-for="f in farmRows" :key="f.id" :value="f.id">
              {{ f.name }}
            </option>
          </select></label
        ><span class="topbar-meta"
          >{{ today }} <i class="connection-dot"></i> 本地服务</span
        >
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
      <main v-if="account && !account.mustChangePassword">
        <div class="page-heading">
          <div>
            <h1>{{ current.title }}</h1>
            <p class="muted">{{ current.description }}</p>
          </div>
          <button
            class="outline with-icon"
            @click="refreshActive"
            :disabled="loading"
            :aria-busy="loading"
          >
            <AppIcon name="refresh" />{{ loading ? "正在加载…" : "刷新数据" }}
          </button>
        </div>
        <p v-if="error" role="alert" class="error">{{ error }}</p>
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
        <FarmHub
          v-else-if="['dashboard', 'farms'].includes(page)"
          :key="page + ':' + navigationRevision"
          :role="role"
          :revision="moduleRevision"
          :initial-farm-id="farmToOpen"
          @farm="farmScope = $event"
        />
        <DeviceManager
          v-else-if="page === 'devices'"
          :role="role"
          :revision="moduleRevision"
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
                  aria-label="搜索当前列表"
                  placeholder="搜索当前列表…"
                /><button
                  v-if="canCreate"
                  class="primary"
                  @click="openForm()"
                  :disabled="busy"
                >
                  ＋ 新增{{
                    page === "platform/tenants"
                      ? "租户"
                      : current.title.slice(0, 2)
                  }}
                </button>
              </div>
            </div>
            <div class="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th v-for="col in current.columns" :key="col[0]">
                      {{ col[1] }}
                    </th>
                    <th v-if="writer || platform">操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="row in filtered" :key="row.id">
                    <td v-for="col in current.columns" :key="col[0]">
                      <span
                        v-if="col[0] === 'status'"
                        :class="['badge', row.status.toLowerCase()]"
                        >{{ display(row[col[0]]) }}</span
                      ><span v-else>{{ display(row[col[0]]) }}</span>
                    </td>
                    <td v-if="writer || platform" class="actions">
                      <template
                        v-if="admin && ['farms', 'plots'].includes(page)"
                        ><button @click="openForm(row)">编辑</button
                        ><button
                          class="danger-text"
                          @click="remove(row)"
                          :disabled="busy"
                          :aria-busy="pending === 'delete:' + row.id"
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
                      <button
                        v-if="['members', 'platform/tenants'].includes(page)"
                        @click="toggle(row)"
                        :disabled="busy || row.id === identity.memberId"
                        :aria-busy="pending === 'toggle:' + row.id"
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
            <div v-if="!filtered.length && !loading" class="empty">
              {{
                search
                  ? "没有匹配的记录，请调整搜索内容。"
                  : "暂无记录。请先创建所属农场或地块，再添加业务数据。"
              }}
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
    <CommandPalette
      v-model:open="paletteOpen"
      :items="paletteItems"
      @choose="choosePalette"
    />
    <div class="toast-region" aria-live="polite" aria-atomic="true">
      <p v-if="notice" class="toast" role="status">
        <span>{{ notice }}</span
        ><button type="button" aria-label="关闭提示" @click="notice = ''">
          ×
        </button>
      </p>
    </div>
    <SettingsDialog
      v-if="settingsOpen && account"
      :account="account"
      @updated="accountUpdated"
      @close="settingsOpen = false"
      @password-changed="passwordChanged"
    />
    <ModalDialog
      v-if="dialog"
      :label="current.title"
      :locked="busy"
      @close="dialog = false"
    >
        <div class="section-title">
          <h2>{{ editing ? "编辑" : "新增" }}{{ current.title }}</h2>
          <button @click="dialog = false" :disabled="busy" aria-label="关闭">
            ×
          </button>
        </div>
        <form @submit.prevent="save">
          <label v-if="fields.some((f) => f.source === 'plots')"
            >所属农场<select
              v-model="formFarm"
              aria-label="表单所属农场"
              @change="model.plotId = ''"
            >
              <option value="">全部农场</option>
              <option v-for="f in farmRows" :key="f.id" :value="f.id">
                {{ f.name }}
              </option>
            </select></label
          >
          <label v-for="field in fields" :key="field.key"
            >{{ field.label }}<small v-if="field.optional">（选填）</small
            ><select
              v-if="field.type === 'select'"
              v-model="model[field.key]"
              required
            >
              <option value="" disabled>请选择</option>
              <option
                v-for="option in options(field)"
                :value="option.value"
                :key="option.value"
              >
                {{ option.label }}
              </option></select
            ><input
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
        <form @submit.prevent="recordObservation">
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
