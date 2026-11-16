<script setup>
import { computed, onMounted, onBeforeUnmount, ref } from "vue";
import ModalDialog from "./ModalDialog.vue";
import {
  solarTermAt,
  termCaptions,
  seasonNotes,
  seasons,
  seasonTerms,
  seasonalDiscoveries,
} from "./solar-terms.js";
import "./solar-terms.css";
const now = ref(Date.now()),
  open = ref(false),
  tab = ref("notes");
const currentTerm = computed(() => solarTermAt(now.value));
const preview = ref(null),
  discovery = ref(""),
  visited = ref([]),
  playing = ref(false),
  effectKey = ref(0);
const term = computed(() => preview.value || currentTerm.value);
const exploring = computed(() => term.value?.at !== currentTerm.value?.at);
const cycleYear = computed(() =>
  term.value ? term.value.year - Number(term.value.index < 2) : 0,
);
const season = computed(() =>
  seasons.find((item) => item.id === term.value?.season),
);
const choices = computed(() =>
  seasonTerms(cycleYear.value, term.value?.season),
);
const discoveries = computed(
  () => seasonalDiscoveries[term.value?.season] || [],
);
const found = computed(() =>
  discoveries.value.find((item) => item.id === discovery.value),
);
const previousTerm = computed(() =>
  term.value ? solarTermAt(term.value.at - 1) : null,
);
const nextTerm = computed(() =>
  term.value ? solarTermAt(term.value.next.at) : null,
);
let effectTimer;
function resetScene() {
  discovery.value = "";
  visited.value = [];
  playing.value = false;
  clearTimeout(effectTimer);
}
function browse(value) {
  if (value) {
    preview.value = value;
    resetScene();
  }
}
function returnToday() {
  preview.value = null;
  resetScene();
}
function chooseSeason(id) {
  browse(seasonTerms(cycleYear.value, id)[0]);
}
function discover(id) {
  discovery.value = id;
  if (!visited.value.includes(id)) visited.value = [...visited.value, id];
}
function playSeason() {
  clearTimeout(effectTimer);
  effectKey.value++;
  playing.value = true;
  effectTimer = setTimeout(() => {
    playing.value = false;
  }, 2400);
}
function show() {
  returnToday();
  tab.value = "notes";
  now.value = Date.now();
  open.value = true;
}
function close() {
  open.value = false;
  resetScene();
}
const date = (at) =>
  new Intl.DateTimeFormat("zh-CN", {
    timeZone: "Asia/Shanghai",
    month: "long",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(at);
let timer;
function refresh() {
  now.value = Date.now();
}
onMounted(() => {
  timer = setInterval(refresh, 30000);
  document.addEventListener("visibilitychange", refresh);
});
onBeforeUnmount(() => {
  clearInterval(timer);
  clearTimeout(effectTimer);
  document.removeEventListener("visibilitychange", refresh);
});
</script>
<template>
  <button
    class="solar-term-chip"
    aria-haspopup="dialog"
    :aria-expanded="open"
    @click="show"
  >
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path
        d="M12 21V10M12 15C3 15 3 5 3 5s10 0 9 10ZM12 11C12 3 21 3 21 3s0 9-9 8Z"
      />
    </svg>
    <span>{{ currentTerm?.name || "四时农事" }}</span
    ><span class="solar-term-dot" aria-hidden="true">·</span
    ><small>时令手记</small>
  </button>
  <ModalDialog
    v-if="open"
    class="solar-dialog-window"
    label="节气手记"
    @close="close"
  >
    <section class="solar-term-dialog" :data-season="term?.season || 'winter'">
      <div class="solar-term-landscape" :class="{ 'season-playing': playing }">
        <svg
          viewBox="0 0 760 270"
          preserveAspectRatio="xMidYMax meet"
          aria-hidden="true"
        >
          <rect width="760" height="270" fill="var(--season-sky)" />
          <circle cx="635" cy="64" r="28" fill="var(--season-sun)" />
          <g class="season-cloud" fill="var(--atlas-edge)" opacity=".75">
            <path
              d="M485 62c-25 0-22-23-4-25 1-23 35-22 37-6 23-8 39 25 8 31Z"
            />
            <path
              d="M659 102c-15 0-19-15-5-20 0-15 23-18 28-6 19-5 25 26 2 26Z"
            />
          </g>
          <path
            d="M0 170Q150 104 330 171T760 123V270H0Z"
            fill="var(--season-hill)"
          />
          <path
            d="M0 219Q170 136 365 208T760 175V270H0Z"
            fill="var(--season-field)"
          />
          <path
            d="M476 270Q555 227 647 223T760 188"
            fill="none"
            stroke="var(--season-water)"
            stroke-width="25"
          />
          <path
            d="M0 256Q180 187 320 235M0 270Q180 202 345 256M165 270Q290 231 395 270"
            fill="none"
            stroke="var(--atlas-edge)"
            stroke-width="3"
            opacity=".6"
          />
          <g transform="translate(535 156)">
            <path d="M0 22H78V76H0Z" fill="var(--season-house)" />
            <path d="M-10 24 37-10 88 24Z" fill="var(--season-roof)" />
            <rect
              x="13"
              y="37"
              width="16"
              height="18"
              rx="2"
              fill="var(--season-water)"
            />
            <rect
              x="46"
              y="36"
              width="18"
              height="40"
              rx="2"
              fill="var(--season-roof)"
            />
            <path d="M63 0V-20H74V9" fill="var(--season-roof)" />
          </g>
          <g class="season-tree" transform="translate(430 180)">
            <path d="M0 57V-12" stroke="var(--season-roof)" stroke-width="7" />
            <circle cy="-9" r="28" fill="var(--season-leaf)" />
            <circle cx="-13" cy="-21" r="20" fill="var(--season-hill)" />
          </g>
          <g
            v-for="n in 5"
            :key="n"
            class="season-seedling"
            :style="{ transformOrigin: 195 + n * 27 + 'px 236px' }"
            :transform="`translate(${195 + n * 27} ${224 + (n % 2) * 9})`"
            fill="var(--season-leaf)"
            stroke="var(--season-leaf)"
            stroke-width="2"
          >
            <path
              d="M0 12V-15M0 0C-16-1-17-13-15-15C-3-14 0-10 0 0ZM0-6C14-10 15-21 13-22C3-21 0-15 0-6Z"
            />
          </g>
          <g
            v-if="term?.season === 'winter'"
            fill="var(--atlas-edge)"
            opacity=".9"
          >
            <path d="m523 180 49-37 52 37h-9l-43-27-39 27Z" />
            <ellipse cx="430" cy="151" rx="18" ry="5" />
          </g>
          <g v-if="term?.season === 'autumn'" fill="var(--season-sun)">
            <ellipse
              v-for="n in 6"
              :key="n"
              :cx="215 + n * 18"
              :cy="242 + (n % 2) * 9"
              rx="4"
              ry="2"
            />
          </g>
          <g v-if="term?.season === 'spring'" fill="var(--season-flower)">
            <circle
              v-for="n in 7"
              :key="n"
              :cx="375 + n * 12"
              :cy="255 - (n % 3) * 6"
              r="3"
            />
          </g>
        </svg>
        <div class="solar-landscape-copy">
          <small>{{
            exploring ? "四季漫游 · " + term.year + " 年" : "智禾 · 此刻的节气"
          }}</small>
          <h2>{{ term?.name || "四时农事" }}</h2>
          <p>
            {{ term ? termCaptions[term.index] : "循着四季，照料每一块田。" }}
          </p>
        </div>
        <div
          v-if="playing"
          :key="effectKey"
          class="season-particles"
          aria-hidden="true"
        >
          <i
            v-for="n in 18"
            :key="n"
            :style="{
              '--particle-x': 8 + ((n * 37) % 87) + '%',
              '--particle-delay': (n % 5) * 90 + 'ms',
              '--particle-turn': n * 23 + 'deg',
            }"
          />
        </div>
        <button
          class="solar-term-close"
          aria-label="关闭节气手记"
          @click="close"
        >
          ×
        </button>
        <div
          v-if="term"
          class="season-hotspots"
          role="group"
          aria-label="探索田间景物"
        >
          <button
            v-for="item in discoveries"
            :key="item.id"
            :class="['season-hotspot', 'hotspot-' + item.id]"
            :aria-label="'探索' + item.object"
            :aria-pressed="discovery === item.id"
            @click="discover(item.id)"
          >
            <span aria-hidden="true">{{
              visited.includes(item.id) ? "✓" : "+"
            }}</span
            ><small>{{ item.object }}</small>
          </button>
        </div>
      </div>
      <div v-if="term" class="solar-term-body">
        <div class="season-explore-tools">
          <span
            >{{ found ? "发现了一个农事提示" : "点点画面里的田地、水渠与农舍"
            }}<small>已探索 {{ visited.length }} / 3</small></span
          >
          <button class="outline season-play" @click="playSeason">
            {{ season?.gesture }} <span aria-hidden="true">✧</span>
          </button>
        </div>
        <div v-if="found" class="season-discovery" role="status">
          <strong>{{ found.title }}</strong>
          <p>{{ found.text }}</p>
        </div>
        <p class="sr-only" role="status">
          {{ playing ? season?.gesture + "，季节场景已响应。" : "" }}
        </p>
        <div class="season-browser">
          <div class="season-browser-heading">
            <strong>翻一翻四季</strong
            ><button
              class="text-button"
              :disabled="!exploring"
              @click="returnToday"
            >
              {{
                exploring
                  ? "回到当下 · " + (currentTerm?.name || "今日")
                  : "正在当季"
              }}
            </button>
          </div>
          <div class="season-selectors" role="group" aria-label="选择季节">
            <button
              v-for="item in seasons"
              :key="item.id"
              :aria-pressed="term.season === item.id"
              @click="chooseSeason(item.id)"
            >
              <span>{{ item.name }}</span
              >{{ item.label }}
            </button>
          </div>
          <div class="season-terms" role="group" aria-label="浏览节气">
            <button
              class="season-step"
              aria-label="上一个节气"
              :disabled="!previousTerm"
              @click="browse(previousTerm)"
            >
              ‹</button
            ><button
              v-for="item in choices"
              :key="item.at"
              :aria-pressed="item.at === term.at"
              @click="browse(item)"
            >
              {{ item.name
              }}<i
                v-if="item.at === currentTerm?.at"
                aria-label="当前节气"
              /></button
            ><button
              class="season-step"
              aria-label="下一个节气"
              :disabled="!nextTerm"
              @click="browse(nextTerm)"
            >
              ›
            </button>
          </div>
        </div>
        <div class="solar-term-timeline">
          <span
            >{{ term.name }}<small>{{ date(term.at) }}</small></span
          ><span
            >下一站 · {{ term.next.name
            }}<small>{{ date(term.next.at) }}</small></span
          >
        </div>
        <p v-if="exploring" class="season-preview-note">
          正在预览 {{ term.year }} 年{{ term.name }}，顶部入口仍显示当前节气。
        </p>
        <div
          v-if="!exploring"
          class="solar-term-progress"
          role="progressbar"
          :aria-valuenow="Math.round(term.progress * 100)"
          aria-valuemin="0"
          aria-valuemax="100"
          :aria-label="term.name + '节气进度'"
        >
          <i :style="{ width: term.progress * 100 + '%' }" />
        </div>
        <div class="solar-term-tabs" role="group" aria-label="手记内容">
          <button :aria-pressed="tab === 'notes'" @click="tab = 'notes'">
            当季农事</button
          ><button :aria-pressed="tab === 'about'" @click="tab = 'about'">
            认识节气
          </button>
        </div>
        <div v-if="tab === 'notes'" class="solar-term-notes">
          <article v-for="(note, i) in seasonNotes[term.season]" :key="note[0]">
            <span aria-hidden="true">{{ i === 0 ? "◌" : "✧" }}</span>
            <div>
              <h3>{{ note[0] }}</h3>
              <p>{{ note[1] }}</p>
            </div>
          </article>
          <p class="solar-term-footnote">
            时令手记为通用农事提示。不同地区农时有差异，请结合当地天气、作物阶段与现场情况判断。
          </p>
        </div>
        <div v-else class="solar-term-about">
          <h3>与土地一起感受时间</h3>
          <p>
            二十四节气以太阳周年运动为依据。这里按北京时间的交节时刻更新，跨过交节时刻后进入下一个节气。
          </p>
          <p>
            场景里的风雨、落叶和雪花是互动插画，不是当地天气实况。点击景物可发现当季提示；四季漫游不会改变系统日期或农事记录。
          </p>
          <small>历法计算：lunar-javascript（MIT）；日期范围 2019—2051。</small>
        </div>
      </div>
      <p v-else class="solar-term-body">
        当前日期超出内置节气表范围，暂不显示交节时间。
      </p>
    </section>
  </ModalDialog>
</template>
