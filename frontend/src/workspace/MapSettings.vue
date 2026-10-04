<script setup>
import { reactive, ref } from "vue";
import { api } from "../api";
const props = defineProps({ farmId: String, config: Object }),
  emit = defineEmits(["saved", "close"]);
const model = reactive(
    Object.fromEntries(
      [
        "mode",
        "latitude",
        "longitude",
        "widthMeters",
        "heightMeters",
        "locationLabel",
        "revision",
      ].map((k) => [k, props.config[k]]),
    ),
  ),
  error = ref(""),
  busy = ref(false);
async function save() {
  busy.value = true;
  error.value = "";
  try {
    emit("saved", await api(`/farms/${props.farmId}/map-config`, "PUT", model));
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div class="modal-backdrop">
    <section
      class="modal"
      role="dialog"
      aria-modal="true"
      aria-label="地图位置校准"
    >
      <h2>地图位置校准</h2>
      <p>
        将原有地块和设备布局映射到真实位置。调整中心和覆盖范围会移动全部叠加点位；保存后请对照影像重新绘制实际边界。
      </p>
      <p class="muted">
        默认位置为建三江公开农业区域，示例地块不代表实际权属。使用 WGS84
        坐标；GCJ-02 / BD-09 需先转换。
      </p>
      <form v-validate @submit.prevent="save">
        <label
          >位置说明<input
            v-model.trim="model.locationLabel"
            required
            maxlength="120"
        /></label>
        <div class="form-grid">
          <label
            >中心纬度<input
              v-model.number="model.latitude"
              type="number"
              min="-80"
              max="80"
              step="0.000001"
              required /></label
          ><label
            >中心经度<input
              v-model.number="model.longitude"
              type="number"
              min="-180"
              max="180"
              step="0.000001"
              required /></label
          ><label
            >东西覆盖（米）<input
              v-model.number="model.widthMeters"
              type="number"
              min="100"
              max="20000"
              required /></label
          ><label
            >南北覆盖（米）<input
              v-model.number="model.heightMeters"
              type="number"
              min="100"
              max="20000"
              required
          /></label>
        </div>
        <label
          >默认底图<select v-model="model.mode">
            <option value="SATELLITE">卫星影像</option>
            <option value="STREET">街道地图</option>
            <option value="PLAN">离线平面图</option>
          </select></label
        >
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <div class="modal-actions">
          <button type="button" @click="emit('close')" :disabled="busy">
            取消</button
          ><button class="primary" :disabled="busy">保存地图位置</button>
        </div>
      </form>
    </section>
  </div>
</template>
