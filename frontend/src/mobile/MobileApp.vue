<script setup>
import {
  computed,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
  watch,
} from "vue";
import AppIcon from "../ui/AppIcon.vue";
import brandLogo from "../assets/zhihe-logo.svg";
import FieldScene from "./FieldScene.vue";
import FieldIcon from "./FieldIcon.vue";
import FieldPicker from "./FieldPicker.vue";
import CameraPlayer from "../workspace/CameraPlayer.vue";
import MobileDevice from "./MobileDevice.vue";
import MobileJobs from "./MobileJobs.vue";
import {
  createClient,
  freshness,
  number,
  statuses,
  time,
  typeNames,
} from "./client.mjs";

const identity = ref(null),
  farms = ref([]),
  farmId = ref(""),
  devices = ref([]),
  alerts = ref([]);
const fieldJobs = ref([]), jobMode = ref("records");
const tab = ref("home"),
  search = ref(""),
  filter = ref(""),
  selectedId = ref(""),
  revision = ref(0);
const error = ref(""),
  message = ref(""),
  busy = ref(false),
  refreshing = ref(false),
  lastSync = ref(null),
  connected = ref(false);
const credentials = reactive({ tenantCode: "", username: "", password: "" });
const location = window.location;
const mustChange = ref(false),
  password = reactive({ currentPassword: "", newPassword: "" });
const confirmation = ref(null),
  sending = ref(false),
  uncertain = ref(false),
  confirmationError = ref("");
let generation = 0,
  refreshVersion = 0,
  timer;
const client = createClient(undefined, () => {
  clearSession();
  error.value = "登录已过期，请重新登录。";
});
const tabs = [
  ["home", "概览", "farms"],
  ["devices", "设备", "devices"],
  ["jobs", "作业", "operations"],
  ["cameras", "实景", "camera"],
];
const farm = computed(() => farms.value.find((f) => f.id === farmId.value));
const farmOptions = computed(() => farms.value.map(f => ({ ...f, caption: `${f.region || '农场工作空间'} · ${f.demo ? '演示农场' : '经营农场'}`, meta: `${number(f.areaMu)} 亩 · ${f.plotCount} 个地块 · ${f.deviceCount} 台设备` })));
const activeJobs = computed(() => fieldJobs.value.filter(j => ["RUNNING", "PAUSED"].includes(j.status)));
const selected = computed(() =>
  devices.value.find((d) => d.id === selectedId.value),
);
const cameras = computed(() =>
  devices.value.filter((d) => d.deviceType === "CAMERA"),
);
const filtered = computed(() =>
  devices.value.filter(
    (d) =>
      (!filter.value || d.deviceType === filter.value) &&
      `${d.name} ${d.code} ${d.model} ${d.plotName || ""}`
        .toLowerCase()
        .includes(search.value.toLowerCase()),
  ),
);
const metrics = computed(() =>
  ["TEMPERATURE", "SOIL_MOISTURE", "RAINFALL", "PEST_COUNT"].map((metric) => {
    const device = devices.value.find((d) =>
      d.channels.some((c) => c.metric === metric),
    );
    return {
      device,
      channel: device?.channels.find((c) => c.metric === metric),
      metric,
    };
  }),
);
const roleName = computed(
  () =>
    ({ ADMIN: "农场管理员", OPERATOR: "操作员", VIEWER: "只读成员" })[
      identity.value?.role
    ] || "",
);
function clearFarm() {
  generation++;
  refreshVersion++;
  devices.value = [];
  fieldJobs.value = [];
  jobMode.value = "records";
  refreshing.value = false;
  alerts.value = [];
  selectedId.value = "";
  search.value = "";
  filter.value = "";
  lastSync.value = null;
  connected.value = false;
  error.value = "";
  message.value = "";
}
function clearSession() {
  client.setToken("");
  clearFarm();
  farms.value = [];
  farmId.value = "";
  identity.value = null;
  mustChange.value = false;
  confirmation.value = null;
  uncertain.value = false;
  tab.value = "home";
}
async function login() {
  busy.value = true;
  error.value = "";
  try {
    const result = await client.request("/auth/login", "POST", {
      tenantCode: credentials.tenantCode.trim(),
      username: credentials.username.trim(),
      password: credentials.password,
    });
    credentials.password = "";
    client.setToken(result.token);
    if (result.identity.role === "PLATFORM_ADMIN") {
      await client.request("/auth/logout", "POST").catch(() => {});
      client.setToken("");
      throw Error("平台账号不能查看经营数据，请使用农场租户账号登录。");
    }
    identity.value = result.identity;
    mustChange.value = result.mustChangePassword;
    if (mustChange.value) tab.value = "account";
    else await loadFarms();
  } catch (e) {
    if (!e.stale) error.value = e.message;
  } finally {
    busy.value = false;
    credentials.password = "";
  }
}
async function loadFarms() {
  const own = generation;
  const result = await client.request("/farm-workspaces");
  if (own !== generation) return;
  farms.value = result;
  farmId.value = result[0]?.id || "";
}
async function refresh() {
  if (!farmId.value || !identity.value || mustChange.value) return;
  const own = generation,
    version = ++refreshVersion,
    id = farmId.value;
  refreshing.value = true;
  try {
    const [assets, warnings, spatial] = await Promise.all([
      client.request(`/assets?farmId=${encodeURIComponent(id)}`),
      client.request(`/alerts?farmId=${encodeURIComponent(id)}&status=OPEN`),
      client.request(`/farms/${encodeURIComponent(id)}/field-map`),
    ]);
    if (own !== generation || version !== refreshVersion) return;
    devices.value = assets;
    alerts.value = warnings;
    fieldJobs.value = spatial.jobs;
    connected.value = true;
    lastSync.value = new Date().toISOString();
    revision.value++;
    error.value = "";
  } catch (e) {
    if (own === generation && version === refreshVersion && !e.stale) {
      error.value = e.message;
      connected.value = false;
    }
  } finally {
    if (version === refreshVersion) refreshing.value = false;
  }
}
watch(farmId, () => {
  clearFarm();
  refresh();
});
watch(
  tab,
  () => {
    selectedId.value = "";
  },
  { flush: "sync" },
);
function openDevice(id) {
  tab.value = "devices";
  selectedId.value = id;
}
function openJobs(mode = "records") {
  jobMode.value = mode;
  tab.value = "jobs";
}
async function logout() {
  const request = client.request("/auth/logout", "POST").catch(() => {});
  clearSession();
  await request;
}
async function changePassword() {
  busy.value = true;
  error.value = "";
  try {
    await client.request("/account/password", "POST", { ...password });
    clearSession();
    message.value = "密码已更新，所有旧会话已失效，请重新登录。";
  } catch (e) {
    if (!e.stale) error.value = e.message;
  } finally {
    busy.value = false;
    password.currentPassword = "";
    password.newPassword = "";
  }
}
function confirm(payload) {
  if (confirmation.value || !connected.value) return;
  confirmation.value = {
    ...payload,
    farmId: farmId.value,
    farmName: farm.value.name,
  };
  confirmationError.value = "";
  uncertain.value = false;
}
async function accepted(result, own) {
  if (own !== generation) return;
  const kind = confirmation.value?.kind;
  confirmation.value = null;
  uncertain.value = false;
  message.value = `${statuses[result.status] || "服务已接收"}：${result.resultNote || "请在记录中核对后续状态。"}`;
  if (kind === "job") openJobs("records");
  await refresh();
}
async function send() {
  if (!confirmation.value || sending.value) return;
  if (!connected.value) { confirmationError.value = "请先恢复连接并刷新农场，再确认本次操作。"; return; }
  const own = generation,
    payload = confirmation.value;
  sending.value = true;
  confirmationError.value = "";
  try {
    const result = await client.request(payload.path, "POST", payload.body);
    await accepted(result, own);
  } catch (e) {
    if (own === generation && !e.stale) {
      uncertain.value = !!e.uncertain;
      confirmationError.value = e.message;
    }
  } finally {
    sending.value = false;
  }
}
async function reconcile() {
  if (!confirmation.value || sending.value) return;
  const own = generation,
    payload = confirmation.value;
  sending.value = true;
  try {
    const data = await client.request(payload.lookup);
    if (own !== generation) return;
    const records = data[payload.collection] || [];
    const result =
      payload.kind === "job-action"
        ? records.find(
            (r) =>
              r.id === payload.jobId &&
              (r.status === payload.desiredStatus ||
                ["COMPLETED", "STOPPED", "FAILED"].includes(r.status)),
          )
        : records.find(
            (r) =>
              (r.requestId || r.parameters?.requestId) ===
              payload.body.requestId,
          );
    if (result) await accepted(result, own);
    else
      confirmationError.value =
        "尚未找到对应结果。可稍后再查，或人工确认后使用相同编号重试；不会自动重新下发。";
  } catch (e) {
    if (own === generation && !e.stale) confirmationError.value = e.message;
  } finally {
    sending.value = false;
  }
}
function visibleRefresh() {
  if (!document.hidden) refresh();
}
function offline() {
  refreshVersion++;
  refreshing.value = false;
  connected.value = false;
  error.value = "网络已断开。已接收的任务由服务器继续处理，恢复连接后请核对记录。";
}
onMounted(() => {
  timer = setInterval(visibleRefresh, 30000);
  document.addEventListener("visibilitychange", visibleRefresh);
  window.addEventListener("offline", offline);
  window.addEventListener("online", visibleRefresh);
});
onBeforeUnmount(() => {
  clearInterval(timer);
  document.removeEventListener("visibilitychange", visibleRefresh);
  window.removeEventListener("offline", offline);
  window.removeEventListener("online", visibleRefresh);
  clearSession();
});
</script>
<template>
  <div class="mobile-app">
    <template v-if="!identity">
      <main class="login-page">
        <section class="login-welcome">
          <div class="login-brand"><img :src="brandLogo" alt="智禾农场标志" width="48" height="48" /><span>智禾农场<small>ZHIHE FARM</small></span></div>
          <p class="eyebrow">YOUR FIELD, AT YOUR FINGERTIPS</p>
          <h1>田间有你，<br />农场在手边。</h1>
          <p class="login-intro">智禾随行 · 从一株禾苗，到每一次安心作业。</p>
          <FieldScene />
        </section>
        <form class="card login-form" @submit.prevent="login">
          <h2>连接你的农场</h2>
          <p class="muted">使用农场分配的账号，继续今天的田间工作</p>
          <label
            >租户代码<input
              v-model="credentials.tenantCode"
              autocomplete="organization"
              required
              maxlength="40"
              placeholder="输入租户代码" /></label
          ><label
            >账号<input
              v-model="credentials.username"
              autocomplete="username"
              required
              maxlength="60"
              placeholder="输入登录账号" /></label
          ><label
            >密码<input
              type="password"
              v-model="credentials.password"
              autocomplete="off"
              required
              maxlength="72"
              placeholder="输入账号密码"
          /></label>
          <p v-if="error" role="alert" class="error">{{ error }}</p>
          <p v-if="message" role="status" class="notice">{{ message }}</p>
          <button class="primary full" :disabled="busy">
            {{ busy ? "登录中…" : "登录农场" }}
          </button>
        </form>
        <p class="login-footer">
          账号权限与电脑端一致 · 不保存密码<br />服务器：{{ location.origin }}
        </p>
      </main>
    </template>
    <template v-else>
      <header class="mobile-header">
        <div class="brand">
          <img :src="brandLogo" width="32" height="32" alt="智禾农场标志" /><strong>智禾随行</strong><span>田间工作伙伴</span>
        </div>
        <button
          class="icon-button"
          aria-label="账号与连接"
          @click="tab = 'account'"
          :disabled="!!confirmation"
        >
          <AppIcon name="profile" />
        </button>
      </header>
      <main class="mobile-main">
        <section v-if="!mustChange" class="farm-switch">
          <FieldPicker label="当前农场" v-model="farmId" :options="farmOptions" :disabled="!!confirmation" />
          <div class="sync-line">
            <span :class="{ stale: !connected }"
              >{{ connected ? "已连接" : "未同步" }} ·
              {{ lastSync ? time(lastSync) : "等待数据" }}</span
            ><button
              @click="
                farms.length
                  ? refresh()
                  : loadFarms().catch((e) => (error = e.message))
              "
              :disabled="refreshing || !!confirmation"
              aria-label="刷新农场数据"
            >
              <AppIcon name="refresh" />
            </button>
          </div>
        </section>
        <p v-if="error" class="error" role="alert">
          {{ error
          }}{{
            lastSync && !connected
              ? " 当前展示上次读取的数据，恢复连接后再操作。"
              : ""
          }}
        </p>
        <p v-if="message" class="notice" role="status">
          {{ message }}
          <button aria-label="关闭提示" @click="message = ''">×</button>
        </p>
        <template v-if="tab === 'account' || mustChange"
          ><section class="card">
            <h2>{{ identity.displayName }}</h2>
            <p>{{ identity.tenantName }} · {{ roleName }}</p>
            <p class="muted break">服务器：{{ location.origin }}</p>
            <p class="muted">
              前台每 30 秒刷新，返回应用时重新读取；不在离线时排队执行指令。
            </p>
            <button @click="logout" :disabled="!!confirmation">退出登录</button>
          </section>
          <form class="card" @submit.prevent="changePassword">
            <h3>{{ mustChange ? "首次使用需更新密码" : "修改密码" }}</h3>
            <label
              >当前密码<input
                type="password"
                v-model="password.currentPassword"
                autocomplete="off"
                required
                maxlength="72" /></label
            ><label
              >新密码<input
                type="password"
                v-model="password.newPassword"
                autocomplete="new-password"
                required
                minlength="12"
                maxlength="72"
            /></label>
            <p class="muted">至少 12 个字符，修改后手机和电脑需要重新登录。</p>
            <button class="primary" :disabled="busy || !!confirmation">
              更新密码
            </button>
          </form></template
        >
        <template v-else-if="!farm"
          ><p class="empty">
            当前账号下暂无可用农场。可刷新重试，或在电脑端农场档案中创建。
          </p></template
        >
        <template v-else-if="tab === 'home'">
          <section class="mobile-hero">
            <p class="eyebrow">{{ roleName }} · {{ identity.displayName }}</p>
            <h1>今天，也照顾好<br />每一块田。</h1>
            <p>先看现场，再安排作业。</p>
            <span v-if="farm.demo" class="tag">虚构农场 · 学术演示</span>
            <FieldScene />
          </section>
          <div class="field-actions" aria-label="田间快捷操作">
            <button @click="openJobs('water')"><span class="symbol-tile water"><FieldIcon name="irrigation" /></span><strong>去灌溉</strong><small>选分区 · 定时开泵</small></button>
            <button @click="openJobs('machine')"><span class="symbol-tile harvest"><FieldIcon name="machinery" /></span><strong>农机作业</strong><small>选设备 · 预览路线</small></button>
            <button @click="tab = 'cameras'"><span class="symbol-tile"><FieldIcon name="camera" /></span><strong>看现场</strong><small>查看田间机位</small></button>
          </div>
          <button class="running-strip" @click="openJobs('records')">
            <span class="symbol-tile"><AppIcon name="history" /></span><span><strong>{{ activeJobs.length ? `${activeJobs.length} 项作业进行中` : '查看作业与执行回执' }}</strong><small>{{ activeJobs.length ? activeJobs[0].title : '进度、暂停和停止，随时可查' }}</small></span><AppIcon name="arrowRight" />
          </button>
          <div class="summary-grid">
            <button @click="tab = 'devices'">
              <strong>{{ devices.length }}</strong
              ><span>在册设备</span></button
            ><button @click="tab = 'devices'">
              <strong>{{
                devices.filter((d) => d.freshness === "FRESH").length
              }}</strong
              ><span>正常上报</span>
            </button>
            <div>
              <strong>{{ alerts.length }}</strong
              ><span>待处理告警</span>
            </div>
          </div>
          <header class="section-heading">
            <h2>田间快照</h2>
            <small>最近一次采样</small>
          </header>
          <div class="metric-grid">
            <button
              class="card metric"
              v-for="m in metrics"
              :key="m.metric"
              @click="m.device && openDevice(m.device.id)"
              :disabled="!m.device"
            >
              <span>{{
                m.channel?.name ||
                {
                  TEMPERATURE: "空气温度",
                  SOIL_MOISTURE: "土壤水分",
                  RAINFALL: "降雨量",
                  PEST_COUNT: "虫情数量",
                }[m.metric]
              }}</span
              ><strong
                >{{ number(m.channel?.latest?.value) }}
                <small>{{ m.channel?.unit }}</small></strong
              ><small>{{
                m.device
                  ? freshness(m.device) +
                    " · " +
                    (m.channel?.latest?.source === "SIMULATED"
                      ? "模拟"
                      : m.channel?.latest?.source || "暂无来源")
                  : "尚无对应设备"
              }}</small
              ><small>{{ m.device?.name || "—" }}</small>
            </button>
          </div>
          <article class="card">
            <h3>待处理告警</h3>
            <p v-if="!alerts.length" class="muted">
              {{ connected ? "当前没有待处理告警" : "等待告警数据" }}
            </p>
            <button
              class="alert-row"
              v-for="a in alerts.slice(0, 5)"
              :key="a.id"
              @click="openDevice(a.deviceId)"
            >
              <strong>{{ a.deviceName || "设备告警" }}</strong
              ><span
                >{{ a.message || a.metricName || a.metric }} ·
                {{ time(a.occurredAt || a.createdAt) }}</span
              >
            </button>
          </article>
        </template>
        <template v-else-if="tab === 'devices'">
          <MobileDevice
            v-if="selected"
            :key="selected.id"
            :device="selected"
            :client="client"
            :role="identity.role"
            :revision="revision"
            :locked="!!confirmation || !connected"
            @close="selectedId = ''"
            @confirm="confirm"
          />
          <section v-else>
            <header class="section-heading">
              <div>
                <p class="eyebrow">SHARED DEVICE LEDGER</p>
                <h2>设备台账</h2>
              </div>
              <span class="tag">{{ devices.length }} 台</span>
            </header>
            <label class="search-label"
              ><AppIcon name="search" /><input
                v-model="search"
                aria-label="搜索设备"
                placeholder="搜索名称、型号或田块" /></label
            ><label class="filter-label"
              >设备类型<select v-model="filter">
                <option value="">全部类型</option>
                <option
                  v-for="t in [...new Set(devices.map((d) => d.deviceType))]"
                  :key="t"
                  :value="t"
                >
                  {{ typeNames[t] || t }}
                </option>
              </select></label
            >
            <p class="empty" v-if="!filtered.length">没有匹配的设备</p>
            <button
              class="card device-card"
              v-for="d in filtered"
              :key="d.id"
              :data-device-id="d.id"
              @click="selectedId = d.id"
            >
              <div class="device-symbol">
                <FieldIcon
                  :name="
                    d.deviceType === 'CAMERA'
                      ? 'camera'
                      : d.deviceType === 'MACHINERY'
                        ? 'machinery'
                        : ['PUMP', 'GATE'].includes(d.deviceType)
                          ? 'pump'
                          : 'sensor'
                  "
                />
              </div>
              <div class="device-copy">
                <strong>{{ d.name }}</strong
                ><span
                  >{{ typeNames[d.deviceType] || d.deviceType }} ·
                  {{ d.plotName || "公共区域" }}</span
                ><small
                  >{{ freshness(d) }}{{ d.alertCount ? " · 有告警" : "" }} ·
                  {{
                    d.protocol === "SIMULATED" ? "模拟设备" : d.protocol
                  }}</small
                >
              </div>
              <span aria-hidden="true">›</span>
            </button>
          </section>
        </template>
        <MobileJobs
          v-else-if="tab === 'jobs'"
          :key="farmId"
          :farm-id="farmId"
          :client="client"
          :devices="devices"
          :role="identity.role"
          :revision="revision"
          :locked="!!confirmation || !connected"
          :initial-mode="jobMode"
          @mode="jobMode = $event"
          @confirm="confirm"
          @device="openDevice"
        />
        <section
          v-else-if="tab === 'cameras'"
          :key="farmId"
          aria-label="田间实景"
        >
          <header class="section-heading">
            <div>
              <p class="eyebrow">FIELD CAMERAS</p>
              <h2>田间实景</h2>
            </div>
            <span class="tag">{{ cameras.length }} 个机位</span>
          </header>
          <p class="muted">仅展示当前农场摄像头，设备资料与电脑端台账同步。</p>
          <p class="empty" v-if="!cameras.length">
            当前农场尚未配置摄像头，请在电脑端设备台账添加。
          </p>
          <article
            class="card camera-card"
            v-for="d in cameras"
            :key="d.id"
            :data-camera-id="d.id"
          >
            <h3>{{ d.camera?.viewLabel || d.name }}</h3>
            <CameraPlayer :device="d" /><button
              class="full"
              @click="openDevice(d.id)"
            >
              查看设备与上报状态
            </button>
          </article>
        </section>
      </main>
      <nav class="mobile-nav" aria-label="手机主导航" v-if="!mustChange">
        <button
          v-for="t in tabs"
          :key="t[0]"
          :aria-current="tab === t[0] ? 'page' : undefined"
          :class="{ active: tab === t[0] }"
          :disabled="!!confirmation"
          @click="t[0] === 'jobs' ? openJobs('records') : (tab = t[0])"
        >
          <FieldIcon :name="t[0] === 'devices' ? 'sensor' : t[0] === 'jobs' ? 'machinery' : t[2]" /><span>{{ t[1] }}</span>
        </button>
      </nav>
      <div class="confirm-backdrop" v-if="confirmation">
        <section
          class="confirm-dialog card"
          role="dialog"
          aria-modal="true"
          aria-labelledby="confirm-title"
        >
          <p class="eyebrow">人工确认 · {{ confirmation.farmName }}</p>
          <h2 id="confirm-title">{{ confirmation.title }}</h2>
          <p>{{ confirmation.description }}</p>
          <dl v-if="confirmation.facts?.length" class="facts confirm-facts"><template v-for="fact in confirmation.facts" :key="fact[0]"><dt>{{ fact[0] }}</dt><dd>{{ fact[1] }}</dd></template></dl>
          <p class="notice">{{ confirmation.source }}</p>
          <p v-if="confirmation.body.note">
            操作说明：{{ confirmation.body.note }}
          </p>
          <p v-if="confirmationError" class="error" role="alert">
            {{ confirmationError }}
          </p>
          <small class="request-id">{{
            confirmation.body.requestId || confirmation.jobId
          }}</small>
          <div class="button-row" v-if="!uncertain">
            <button @click="confirmation = null" :disabled="sending">
              返回修改</button
            ><button class="primary" @click="send" :disabled="sending">
              {{ sending ? "正在提交…" : "确认下发" }}
            </button>
          </div>
          <div v-else>
            <button class="primary full" @click="reconcile" :disabled="sending">
              查询操作结果</button
            ><button class="full" @click="send" :disabled="sending">
              使用相同编号重试
            </button>
          </div>
        </section>
      </div>
    </template>
  </div>
</template>
