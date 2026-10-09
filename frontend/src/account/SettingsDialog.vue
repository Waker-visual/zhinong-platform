<script setup>
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
} from "vue";
import { api } from "../api";
import { labels } from "../catalog";
import { diagnosticLabel } from "../ai/diagnostics";
import AppIcon from "../ui/AppIcon.vue";
import SelectMenu from "../ui/SelectMenu.vue";
import { confirmAction } from "../ui/confirm";
import { important, toast } from "../ui/feedback";
import { applyAppearance } from "./appearance";
import {
  buildConfigOverridePayload,
  buildSaveConfigPayload,
  mergeModelIds,
} from "./model-config";
const props = defineProps({ account: Object });
const emit = defineEmits(["close", "updated", "password-changed"]);
const tab = ref(props.account.mustChangePassword ? "security" : "profile"),
  error = ref(""),
  message = ref(""),
  busy = ref(false),
  members = ref([]),
  memberSearch = ref(""),
  settingsSearch = ref("");
const settingsSearchInput = ref(null);
const database = ref(null);
onMounted(async () => {
  if (props.account.mustChangePassword) return;
  try { database.value = await api("/system/database"); } catch { database.value = { connected: false }; }
});
const isPlatformAdmin = props.account.role === "PLATFORM_ADMIN";
const modelInfo = ref(null);
const modelForm = reactive({ url: "", model: "", apiKey: "" });
const showModelKey = ref(false);
const modelTestResult = ref("");
const modelTestCode = ref("");
const modelFetchNotice = ref("");
const modelFetchCode = ref("");
const newModelId = ref("");
onMounted(() => {
  if (isPlatformAdmin && !props.account.mustChangePassword) work(loadModelConfig);
});
async function loadModelConfig() {
  modelInfo.value = await api("/ai/config");
  modelForm.url = modelInfo.value.url || "";
  modelForm.model = modelInfo.value.model || "";
  modelForm.apiKey = "";
  showModelKey.value = false;
  modelTestResult.value = "";
  modelTestCode.value = "";
  modelFetchNotice.value = "";
  modelFetchCode.value = "";
}
const profile = reactive({
  displayName: props.account.displayName,
  avatarData: props.account.avatarData || "",
});
const appearance = reactive({
  themeMode: props.account.themeMode,
  accent: props.account.accent,
});
const password = reactive({
  currentPassword: "",
  newPassword: "",
  confirmation: "",
});
const memberForm = ref(false),
  resetTarget = ref(null),
  showTemporary = ref(false);
const newMember = reactive({
  username: "",
  displayName: "",
  password: "",
  role: "OPERATOR",
});
const themeModeOptions = [
  { value: "SYSTEM", label: "跟随系统" },
  { value: "LIGHT", label: "浅色" },
  { value: "DARK", label: "深色" },
];
const memberRoleOptions = [
  { value: "OPERATOR", label: "操作员" },
  { value: "VIEWER", label: "查看者" },
  { value: "ADMIN", label: "租户管理员" },
];
const reset = reactive({ currentPassword: "", newPassword: "" });
const tabs = computed(() =>
  props.account.mustChangePassword
    ? [{ id: "security", name: "账号安全", icon: "security" }]
    : [
        { id: "profile", name: "个人资料", icon: "profile" },
        { id: "security", name: "账号安全", icon: "security" },
        { id: "appearance", name: "外观设置", icon: "appearance" },
        ...(props.account.role === "ADMIN"
          ? [{ id: "team", name: "成员管理", icon: "team" }]
          : []),
        ...(isPlatformAdmin
          ? [{ id: "model", name: "模型服务", icon: "operations" }]
          : []),
        { id: "about", name: "系统信息", icon: "info" },
      ],
);
const filteredTabs = computed(() => {
  const query = settingsSearch.value.trim().toLowerCase();
  if (!query) return tabs.value;
  return tabs.value.filter((item) => item.name.toLowerCase().includes(query));
});
const filteredMembers = computed(() =>
  members.value.filter((m) =>
    (m.username + " " + m.displayName)
      .toLowerCase()
      .includes(memberSearch.value.toLowerCase()),
  ),
);
async function work(fn) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  message.value = "";
  try {
    await fn();
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
function switchTab(id) {
  tab.value = id;
  error.value = "";
  message.value = "";
  memberForm.value = false;
  resetTarget.value = null;
  reset.currentPassword = "";
  reset.newPassword = "";
  password.currentPassword = "";
  password.newPassword = "";
  password.confirmation = "";
  modelTestResult.value = "";
  modelTestCode.value = "";
  modelFetchNotice.value = "";
  modelFetchCode.value = "";
  newModelId.value = "";
}
function saveProfile() {
  work(async () => {
    const data = await api("/account/profile", "PUT", profile);
    emit("updated", data);
    message.value = "个人资料已保存";
    important("个人资料已保存");
  });
}
function preview() {
  applyAppearance(appearance, false);
}
function saveAppearance() {
  work(async () => {
    const data = await api("/account/appearance", "PUT", appearance);
    emit("updated", data);
    applyAppearance(data);
    message.value = "外观已保存，并在此账号下同步";
    important("外观已保存，并在此账号下同步");
  });
}
function changePassword() {
  work(async () => {
    if (password.newPassword !== password.confirmation)
      throw new Error("两次输入的新密码不一致");
    await api("/account/password", "POST", {
      currentPassword: password.currentPassword,
      newPassword: password.newPassword,
    });
    emit("password-changed");
  });
}
function saveModelConfig() {
  work(async () => {
    await api("/ai/config", "POST", buildSaveConfigPayload(modelForm, false));
    await loadModelConfig();
    message.value = "模型服务配置已保存";
    important("模型服务配置已保存");
  });
}
async function clearModelKey() {
  const ok = await confirmAction({
    title: "清除模型密钥",
    message: "清除后云端模型将不可用，需重新配置密钥才能恢复为启用状态。",
    confirmLabel: "清除密钥",
    danger: true,
  });
  if (!ok) return;
  work(async () => {
    await api("/ai/config", "POST", buildSaveConfigPayload(modelForm, true));
    await loadModelConfig();
    message.value = "模型密钥已清除";
    important("模型密钥已清除");
  });
}
function testModelConnection() {
  modelTestResult.value = "";
  modelTestCode.value = "";
  work(async () => {
    const res = await api("/ai/config/test", "POST", buildConfigOverridePayload(modelForm));
    modelTestCode.value = res.code;
    modelTestResult.value = diagnosticLabel(res.code);
  });
}
function fetchModelOptions() {
  modelFetchNotice.value = "";
  modelFetchCode.value = "";
  work(async () => {
    const res = await api("/ai/config/models", "POST", buildConfigOverridePayload(modelForm));
    modelFetchCode.value = res.diagnostic;
    if (res.diagnostic !== "OK") {
      modelFetchNotice.value = diagnosticLabel(res.diagnostic);
      return;
    }
    const existing = modelInfo.value?.modelOptions || [];
    const merged = mergeModelIds(existing, res.models);
    if (merged.length === existing.length) {
      modelFetchNotice.value = "服务返回的模型均已在选项列表中";
      return;
    }
    const data = await api("/ai/config/models/options", "POST", { ids: res.models });
    modelInfo.value = { ...modelInfo.value, modelOptions: data.modelOptions };
    modelFetchNotice.value = `已合并 ${merged.length - existing.length} 个新模型到选项列表`;
  });
}
function addCustomModelOption() {
  const id = newModelId.value.trim();
  if (!id) return;
  work(async () => {
    const data = await api("/ai/config/models/options", "POST", { ids: [id] });
    modelInfo.value = { ...modelInfo.value, modelOptions: data.modelOptions };
    newModelId.value = "";
    message.value = "模型选项已添加";
  });
}
function removeModelOption(id) {
  work(async () => {
    const data = await api("/ai/config/models/options/remove", "POST", { id });
    modelInfo.value = { ...modelInfo.value, modelOptions: data.modelOptions };
    message.value = "模型选项已移除";
  });
}
async function upload(event) {
  const file = event.target.files?.[0];
  if (!file) return;
  error.value = "";
  if (
    !["image/png", "image/jpeg", "image/webp"].includes(file.type) ||
    file.size > 5 * 1024 * 1024
  ) {
    error.value = "请选择不超过 5 MB 的 PNG、JPEG 或 WebP 图片";
    return;
  }
  const url = URL.createObjectURL(file);
  try {
    const image = new Image();
    image.src = url;
    await image.decode();
    const canvas = document.createElement("canvas");
    canvas.width = canvas.height = 256;
    const size = Math.min(image.width, image.height);
    canvas
      .getContext("2d")
      .drawImage(
        image,
        (image.width - size) / 2,
        (image.height - size) / 2,
        size,
        size,
        0,
        0,
        256,
        256,
      );
    profile.avatarData = canvas.toDataURL("image/png");
  } catch {
    error.value = "图片读取失败，请选择其他图片";
  } finally {
    URL.revokeObjectURL(url);
    event.target.value = "";
  }
}
function randomPassword() {
  const bytes = crypto.getRandomValues(new Uint8Array(18));
  return btoa(String.fromCharCode(...bytes))
    .replaceAll("+", "-")
    .replaceAll("/", "_");
}
async function loadMembers() {
  if (props.account.role === "ADMIN" && !props.account.mustChangePassword)
    members.value = await api("/members");
}
function createMember() {
  work(async () => {
    await api("/members", "POST", newMember);
    memberForm.value = false;
    newMember.password = "";
    await loadMembers();
    message.value = "成员已创建，请将初始密码交给本人";
    toast("成员已创建");
  });
}
function toggleMember(member) {
  work(async () => {
    await api("/members/" + member.id, "PATCH", { enabled: !member.enabled });
    await loadMembers();
    message.value = "成员状态已更新";
    toast("成员状态已更新");
  });
}
function openReset(member) {
  resetTarget.value = member;
  reset.currentPassword = "";
  reset.newPassword = "";
  showTemporary.value = false;
  error.value = "";
  message.value = "";
}
function resetPassword() {
  work(async () => {
    await api(
      "/members/" + resetTarget.value.id + "/reset-password",
      "POST",
      reset,
    );
    resetTarget.value = null;
    reset.currentPassword = "";
    reset.newPassword = "";
    await loadMembers();
    message.value = "密码已重置；成员旧会话已退出，下次登录必须设置新密码";
    toast("成员密码已重置");
  });
}
onMounted(() => work(loadMembers));
onMounted(() => nextTick(() => settingsSearchInput.value?.focus()));
onBeforeUnmount(() => applyAppearance(props.account));
</script>
<template>
  <div
    class="settings-backdrop"
    @click.self="!busy && !account.mustChangePassword && emit('close')"
  >
    <section
      class="settings-dialog"
      role="dialog"
      aria-modal="true"
      aria-label="账号设置"
    >
      <aside class="settings-nav">
        <div class="settings-title">
          <h2>设置</h2>
        </div>
        <label class="settings-search">
          <AppIcon name="search" />
          <input
            ref="settingsSearchInput"
            v-model="settingsSearch"
            type="search"
            autocomplete="off"
            placeholder="搜索设置"
            aria-label="搜索设置"
          />
        </label>
        <button
          v-for="item in filteredTabs"
          :key="item.id"
          :class="{ active: tab === item.id }"
          @click="switchTab(item.id)"
          :disabled="busy"
        >
          <AppIcon :name="item.icon" />{{ item.name }}</button
        ><p v-if="!filteredTabs.length" class="settings-empty">没有匹配的设置</p>
        <small>{{ account.tenantName }}<br />{{ labels[account.role] }}</small>
      </aside>
      <div class="settings-content">
        <header>
          <div>
            <h2>{{ tabs.find((t) => t.id === tab)?.name }}</h2>
          </div>
          <button
            v-if="!account.mustChangePassword"
            class="close-button settings-close"
            aria-label="返回工作空间"
            @click="emit('close')"
          >
            ×
          </button>
        </header>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <p v-if="message" class="success" role="status">{{ message }}</p>
        <form v-validate v-if="tab === 'profile'" @submit.prevent="saveProfile">
          <div class="profile-avatar-row">
            <img
              v-if="profile.avatarData"
              :src="profile.avatarData"
              alt="头像预览"
            /><span v-else class="profile-avatar-fallback">{{
              profile.displayName.slice(0, 1) || "禾"
            }}</span>
            <div>
              <label class="outline upload-avatar"
                >更换头像<input
                  type="file"
                  accept="image/png,image/jpeg,image/webp"
                  aria-label="上传头像"
                  @change="upload" /></label
              ><button
                type="button"
                @click="profile.avatarData = ''"
                :disabled="!profile.avatarData"
              >
                恢复文字头像</button
              ><small>图片居中裁切为正方形；支持 PNG / JPEG / WebP。</small>
            </div>
          </div>
          <label
            >昵称<input
              v-model.trim="profile.displayName"
              required
              maxlength="80"
              autocomplete="nickname"
          /></label>
          <dl class="settings-facts">
            <div>
              <dt>登录账号</dt>
              <dd>{{ account.username }}</dd>
            </div>
            <div>
              <dt>租户代码</dt>
              <dd>{{ account.tenantCode }}</dd>
            </div>
            <div>
              <dt>所属租户</dt>
              <dd>{{ account.tenantName }}</dd>
            </div>
            <div>
              <dt>角色</dt>
              <dd>{{ labels[account.role] }}</dd>
            </div>
            <div v-if="database">
              <dt>数据存储</dt>
              <dd>{{ database.connected ? `${database.location === 'REMOTE' ? '远程' : '本机'} ${database.engine} · 已连接` : '连接状态暂不可用' }}</dd>
            </div>
          </dl>
          <p class="muted">昵称与头像可修改，登录账号及所属租户保持不变。</p>
          <button class="primary" :disabled="busy">保存个人资料</button>
        </form>
        <form v-validate v-if="tab === 'security'" @submit.prevent="changePassword">
          <p v-if="account.mustChangePassword" class="settings-notice">
            管理员已重置你的密码。请先用临时密码设置自己的新密码，再进入业务工作空间。
          </p>
          <p class="muted">修改密码后，所有已登录设备都会退出。</p>
          <label
            >{{ account.mustChangePassword ? "临时密码" : "当前密码"
            }}<input
              type="password"
              v-model="password.currentPassword"
              required
              autocomplete="current-password" /></label
          ><label
            >新密码<input
              type="password"
              v-model="password.newPassword"
              required
              minlength="12"
              maxlength="72"
              autocomplete="new-password" /></label
          ><label
            >确认新密码<input
              type="password"
              v-model="password.confirmation"
              required
              minlength="12"
              maxlength="72"
              autocomplete="new-password" /></label
          ><small
            >至少 12 个字符，UTF-8 编码不超过 72
            字节。建议使用较长且唯一的密码。</small
          >
          <div class="settings-actions">
            <button class="primary" :disabled="busy">修改密码并重新登录</button>
          </div>
        </form>
        <form v-validate v-if="tab === 'appearance'" @submit.prevent="saveAppearance">
          <p class="muted">
            先预览再保存。未保存关闭设置时恢复原来的外观；跟随系统时按本地时区的冬、夏令时日间时段自动切换。
          </p>
          <label
            >显示模式<SelectMenu
              v-model="appearance.themeMode"
              :options="themeModeOptions"
              aria-label="显示模式"
              @change="preview"
            /></label
          >
          <h3>主题颜色</h3>
          <div class="accent-options">
            <button
              type="button"
              v-for="item in [
                { code: 'FOREST', name: '默认黑白' },
                { code: 'BLUE', name: '湖泊蓝' },
                { code: 'AMBER', name: '麦穗金' },
                { code: 'ROSE', name: '玫瑰紫' },
              ]"
              :key="item.code"
              :class="{ selected: appearance.accent === item.code }"
              @click="
                appearance.accent = item.code;
                preview();
              "
            >
              <i :data-accent="item.code.toLowerCase()" />{{ item.name
              }}<span v-if="appearance.accent === item.code">✓</span>
            </button>
          </div>
          <div class="appearance-preview">
            <h3>你的农场工作空间</h3>
            <p>
              主题颜色会应用到品牌标识、主操作、链接、焦点环和当前菜单指示条；任务状态色保持固定，避免混淆含义。
            </p>
            <div class="appearance-preview-nav" aria-hidden="true">
              <b>禾</b>今日农场
            </div>
            <button type="button" class="primary">主按钮预览</button
            ><button type="button" class="outline">次按钮预览</button>
          </div>
          <button class="primary" :disabled="busy">保存外观</button>
        </form>
        <section v-if="tab === 'team'" class="settings-team">
          <div class="settings-team-tools">
            <input
              v-model="memberSearch"
              type="search"
              enterkeyhint="search"
              autocomplete="off"
              placeholder="搜索成员账号或昵称"
              aria-label="设置中搜索成员"
            /><button
              class="primary"
              @click="
                memberForm = !memberForm;
                resetTarget = null;
              "
              :disabled="busy"
            >
              新增成员
            </button>
          </div>
          <form v-validate
            v-if="memberForm"
            class="settings-subform"
            @submit.prevent="createMember"
          >
            <h3>新增成员</h3>
            <label
              >成员账号<input
                v-model.trim="newMember.username"
                required
                pattern="[a-z][a-z0-9_.-]{2,59}" /></label
            ><label
              >成员昵称<input
                v-model.trim="newMember.displayName"
                required
                maxlength="80" /></label
            ><label
              >成员角色<SelectMenu
                v-model="newMember.role"
                :options="memberRoleOptions"
                aria-label="成员角色"
              /></label
            ><label
              >初始密码<input
                type="password"
                v-model="newMember.password"
                minlength="12"
                maxlength="72"
                required
                autocomplete="new-password" /></label
            ><button class="primary" :disabled="busy">创建成员</button
            ><button type="button" @click="memberForm = false">取消</button>
          </form>
          <form v-validate
            v-if="resetTarget"
            class="settings-subform"
            @submit.prevent="resetPassword"
          >
            <h3>重置 {{ resetTarget.displayName }} 的密码</h3>
            <p class="muted">
              旧会话立即失效，成员下次登录必须修改临时密码。此操作记入审计日志。
            </p>
            <label
              >管理员当前密码<input
                type="password"
                v-model="reset.currentPassword"
                required
                autocomplete="current-password" /></label
            ><label
              >成员临时密码<input
                :type="showTemporary ? 'text' : 'password'"
                v-model="reset.newPassword"
                required
                minlength="12"
                maxlength="72"
                autocomplete="new-password"
            /></label>
            <div class="inline-controls">
              <button
                type="button"
                class="outline"
                @click="
                  reset.newPassword = randomPassword();
                  showTemporary = true;
                "
              >
                随机生成临时密码</button
              ><label class="inline-check"
                ><input
                  type="checkbox"
                  v-model="showTemporary"
                />显示临时密码</label
              >
            </div>
            <div class="settings-actions">
              <button class="primary" :disabled="busy">确认重置密码</button
              ><button
                type="button"
                @click="
                  resetTarget = null;
                  reset.currentPassword = '';
                  reset.newPassword = '';
                "
              >
                取消
              </button>
            </div>
          </form>
          <div class="settings-member-list">
            <article v-for="member in filteredMembers" :key="member.id">
              <div>
                <strong
                  >{{ member.displayName }}
                  <small v-if="member.id === account.id">本人</small></strong
                ><small
                  >{{ member.username }} · {{ labels[member.role] }} ·
                  {{ member.enabled ? "已启用" : "已停用" }}</small
                >
              </div>
              <div>
                <button
                  v-if="['OPERATOR', 'VIEWER'].includes(member.role)"
                  class="outline"
                  @click="openReset(member)"
                  :disabled="busy"
                >
                  重置密码</button
                ><button
                  v-if="member.id !== account.id"
                  @click="toggleMember(member)"
                  :disabled="busy"
                >
                  {{ member.enabled ? "停用" : "启用" }}
                </button>
              </div>
            </article>
          </div>
          <p class="muted">
            仅管理当前租户。普通成员密码由管理员重置；管理员自身通过“账号安全”改密。
          </p>
        </section>
        <form v-validate v-if="tab === 'model'" @submit.prevent="saveModelConfig">
          <p class="muted">
            全局模型配置对所有租户生效；密钥只保存在本机私有配置文件中，从不下发到前端，也不会显示原文。
          </p>
          <p v-if="modelInfo" :class="modelInfo.cloudEnabled ? 'success' : 'settings-notice'">
            {{ modelInfo.cloudEnabled ? "已启用云端模型" : "未配置" }}
          </p>
          <label
            >接口地址<input
              v-model.trim="modelForm.url"
              type="url"
              maxlength="300"
              placeholder="https://api.example.com/v1"
          /></label>
          <label
            >模型名称<input
              v-model.trim="modelForm.model"
              maxlength="100"
              placeholder="例如 deepseek-flash"
          /></label>
          <label
            >API 密钥<input
              :type="showModelKey ? 'text' : 'password'"
              v-model="modelForm.apiKey"
              maxlength="300"
              autocomplete="new-password"
              :placeholder="modelInfo?.apiKeySet ? '已设置，留空则保持不变' : '未设置'"
          /></label>
          <label class="inline-check"
            ><input type="checkbox" v-model="showModelKey" />显示输入的密钥</label
          >
          <div class="settings-actions">
            <button class="primary" :disabled="busy">保存</button
            ><button
              type="button"
              class="outline"
              :disabled="busy"
              @click="testModelConnection"
            >
              测试连接</button
            ><button
              type="button"
              class="outline"
              :disabled="busy || !modelInfo?.apiKeySet"
              @click="clearModelKey"
            >
              清除密钥
            </button>
          </div>
          <p v-if="modelTestResult" :class="modelTestCode === 'OK' ? 'success' : 'error'" role="status">
            测试结果：{{ modelTestResult }}
          </p>
          <h3>模型选项</h3>
          <p class="muted">
            勾选上方“模型名称”对应的选项即为当前使用的模型；移除某个选项前须先切换到其他模型。
          </p>
          <div class="model-options-list">
            <p v-if="!modelInfo?.modelOptions?.length" class="muted">
              暂无模型选项，可从服务获取或手动添加。
            </p>
            <label
              v-for="id in modelInfo?.modelOptions || []"
              :key="id"
              class="model-option-row"
              :class="{ active: modelForm.model === id }"
            >
              <span class="model-option-pick"
                ><input
                  type="radio"
                  name="active-model-option"
                  :value="id"
                  v-model="modelForm.model"
                />{{ id }}</span
              ><button
                type="button"
                class="icon-button"
                :aria-label="'移除 ' + id"
                :disabled="busy || id === modelForm.model"
                @click="removeModelOption(id)"
              >
                <AppIcon name="trash" />
              </button>
            </label>
          </div>
          <div class="settings-team-tools">
            <input
              v-model.trim="newModelId"
              maxlength="100"
              placeholder="手动输入模型 id，例如 deepseek-v4-pro"
              aria-label="手动添加模型 id"
            /><button
              type="button"
              class="outline"
              :disabled="busy || !newModelId.trim()"
              @click="addCustomModelOption"
            >
              添加</button
            ><button type="button" class="outline" :disabled="busy" @click="fetchModelOptions">
              从服务获取
            </button>
          </div>
          <p v-if="modelFetchNotice" :class="modelFetchCode === 'OK' || !modelFetchCode ? 'muted' : 'error'" role="status">
            {{ modelFetchNotice }}
          </p>
        </form>
        <section v-if="tab === 'about'">
          <h3>智禾农场 0.3.0</h3>
          <p>多租户农场经营管理、实景地图与情景验收。</p>
          <dl class="settings-facts">
            <div>
              <dt>账号资料</dt>
              <dd>保存到当前本机服务</dd>
            </div>
            <div>
              <dt>地图</dt>
              <dd>公开卫星影像 / 街道图 / 本地布局</dd>
            </div>
            <div>
              <dt>经营模拟</dt>
              <dd>公开天气 + 可调整的情景参数</dd>
            </div>
          </dl>
          <p class="muted">
            地图影像存在拍摄时间差；经营模拟用来检验流程和资源配置，不能代替现场调查或实际产量记录。
          </p>
        </section>
      </div>
    </section>
  </div>
</template>
