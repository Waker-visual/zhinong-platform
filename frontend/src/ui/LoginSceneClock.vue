<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { daylightAt } from './login-daylight.js';
import { calendarAt, SCENE_SCENARIOS } from './login-calendar.js';

const props=defineProps({snapshot:{type:Object,required:true},period:{type:String,default:''}});
const emit=defineEmits(['start','return','scenario']);
const scenarioOpen=ref(false),scenarioControls=ref(null),scenarioToggle=ref(null);
const scenarioName=computed(()=>SCENE_SCENARIOS.find(item=>item.id===props.snapshot.scenario)?.label);
const yearSceneName=computed(()=>{
  const {season,festivals}=calendarAt(props.snapshot.atMs);
  return (festivals.length?festivals:[season]).map(id=>SCENE_SCENARIOS.find(item=>item.id===id)?.label).join(' · ');
});
const tourChoices=[{id:'day',label:'一日',duration:12,accessible:'快进一天 12 秒'},
  {id:'year',label:'一年',duration:60,accessible:'快进一年 60 秒'}];
function selectScenario(id){emit('scenario',id);closeScenario(true);}
function closeScenario(restoreFocus=false){scenarioOpen.value=false;if(restoreFocus)scenarioToggle.value?.focus();}
function closeOutside(event){if(scenarioOpen.value && !scenarioControls.value?.contains(event.target))closeScenario();}
onMounted(()=>document.addEventListener('pointerdown',closeOutside));
onBeforeUnmount(()=>document.removeEventListener('pointerdown',closeOutside));
const minuteAngle=ref(0),hourAngle=ref(0);
let previous;
watch(()=>props.snapshot,snapshot=>{
  const civil=snapshot.civil,seconds=civil.second+((snapshot.atMs%1000)+1000)%1000/1000;
  const minute=(civil.minute+seconds/60)*6;
  const hour=((civil.hour%12)+civil.minute/60+seconds/3600)*30;
  const reset=!previous||previous.mode!==snapshot.mode||snapshot.atMs<previous.atMs||
    (snapshot.mode==='real'&&previous.civil.dateKey!==civil.dateKey);
  if(reset) {minuteAngle.value=minute;hourAngle.value=hour;}
  else {
    // Select the actual number of revolutions, even when a slow frame skips > 180°.
    const elapsed=snapshot.atMs-previous.atMs;
    minuteAngle.value=minute+360*Math.round((minuteAngle.value+elapsed/10_000-minute)/360);
    hourAngle.value=hour+360*Math.round((hourAngle.value+elapsed/120_000-hour)/360);
  }
  previous=snapshot;
},{immediate:true});
const timeText=computed(()=>`${String(props.snapshot.civil.hour).padStart(2,'0')}:${String(props.snapshot.civil.minute).padStart(2,'0')}`);
const periodText=computed(()=>props.snapshot.tourKind==='year' && props.snapshot.speed/86400>=1.3 ? '穿行'
  : {dawn:'晨曦',day:'白昼',dusk:'暮色',night:'夜色'}[props.period||daylightAt(props.snapshot.atMs).period]);
const weatherText=computed(()=>({clear:'晴天',cloudy:'有云',overcast:'阴天'}[props.snapshot.weather]));
</script>

<template>
  <div class="login-scene-clock" :data-mode="snapshot.mode" :data-tour-kind="snapshot.tourKind" :data-year-phase="snapshot.yearPhase">
    <svg class="login-clock-dial" viewBox="0 0 64 64" aria-hidden="true">
      <circle cx="32" cy="32" r="29" class="login-clock-ring" />
      <line v-for="tick in 12" :key="tick" x1="32" y1="7" x2="32" :y2="tick%3===0?11:9"
        :transform="`rotate(${tick*30} 32 32)`" class="login-clock-tick" />
      <g class="login-clock-numbers" text-anchor="middle" dominant-baseline="central">
        <text x="32" y="14">12</text><text x="50" y="32">3</text>
        <text x="32" y="50">6</text><text x="14" y="32">9</text>
      </g>
      <line x1="32" y1="34" x2="32" y2="22" :transform="`rotate(${hourAngle} 32 32)`" class="login-clock-hour" />
      <line x1="32" y1="35" x2="32" y2="19" :transform="`rotate(${minuteAngle} 32 32)`" class="login-clock-minute" />
      <circle cx="32" cy="32" r="2" class="login-clock-pin" />
    </svg>
    <div class="login-clock-copy">
      <div class="login-clock-time" role="timer" aria-live="off" :aria-label="`农场当地时间 ${snapshot.civil.dateKey} ${timeText}，${periodText}`">
        <span>{{ timeText }}</span><span class="login-clock-period" :title="`${weatherText}情景`">{{ periodText }}</span>
      </div>
      <p v-if="snapshot.scenario!=='auto' || snapshot.tourKind==='year'" class="login-scenario-date">{{ snapshot.tourKind==='year'?yearSceneName:scenarioName }} · {{ snapshot.civil.dateKey }}</p>
      <div class="login-tour-actions">
        <button v-for="choice in tourChoices" :key="choice.id" type="button" class="login-clock-action" :data-tour="choice.id"
          :aria-label="choice.accessible" :aria-pressed="snapshot.tourKind===choice.id" @click="$emit('start',choice.id)">
          {{ choice.label }}<span class="login-clock-duration">{{ snapshot.tourKind===choice.id?`${Math.floor(snapshot.progress*100)}%`:`${choice.duration} 秒` }}</span>
        </button>
      </div>
      <div ref="scenarioControls" class="login-scenario-controls" @keydown.esc.stop.prevent="closeScenario(true)">
        <button ref="scenarioToggle" class="login-scenario-toggle" type="button" :aria-expanded="scenarioOpen" aria-controls="login-scenario-menu" @click="scenarioOpen=!scenarioOpen">时令预览 <span class="login-scenario-chevron" aria-hidden="true"></span></button>
        <button v-if="snapshot.scenario!=='auto' || snapshot.mode==='tour'" class="login-scenario-reset" type="button" @click="$emit('return')">回到现在</button>
        <div v-if="scenarioOpen" id="login-scenario-menu" class="login-scenario-menu">
          <div class="login-scenario-options" role="group" aria-label="时令场景">
            <button v-for="item in SCENE_SCENARIOS" :key="item.id" type="button" :data-scenario="item.id"
              :aria-pressed="snapshot.scenario===item.id" @click="selectScenario(item.id)">{{ item.label }}</button>
          </div>
          <small>仅预览画面，不改变农场业务日期</small>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-scene-clock {
  position:absolute;top:116px;left:clamp(32px,4.5vw,80px);z-index:2;display:flex;align-items:center;gap:14px;
  color:var(--pastoral-machine);pointer-events:auto;
}
.login-clock-dial {width:80px;height:80px;flex:none;overflow:visible;}
.login-clock-ring {fill:none;stroke:var(--pastoral-machine);stroke-width:1;opacity:.55;}
.login-clock-tick {stroke:var(--pastoral-machine);stroke-width:1;opacity:.65;}
.login-clock-numbers {fill:var(--pastoral-machine);font-size:6px;font-variant-numeric:tabular-nums;}
.login-clock-hour,.login-clock-minute {stroke:var(--pastoral-machine);stroke-linecap:round;}
.login-clock-hour {stroke-width:2;}
.login-clock-minute {stroke-width:1.2;}
.login-clock-pin {fill:var(--pastoral-machine);}
.login-clock-time {display:flex;align-items:baseline;gap:10px;font-size:22px;line-height:1.2;font-variant-numeric:tabular-nums;}
.login-clock-period {font-size:12px;letter-spacing:.12em;}
.login-clock-copy p {margin:5px 0 4px;font-size:11px;opacity:.7;}
.login-tour-actions {display:flex;gap:18px;margin-top:5px;}
.login-clock-action {
  display:inline-flex;align-items:center;gap:8px;border:0;border-bottom:1px solid currentColor;
  padding:3px 0;background:none;color:inherit;font:inherit;font-size:11px;line-height:1.5;cursor:pointer;
}
.login-clock-action:hover {color:var(--pastoral-patrol);}
.login-clock-action[aria-pressed="true"] {color:var(--pastoral-patrol);font-weight:600;}
.login-clock-action:focus-visible {outline:2px solid var(--pastoral-machine);outline-offset:4px;}
.login-clock-duration {font-size:10px;opacity:.6;}
.login-scenario-controls {position:relative;display:flex;gap:12px;margin-top:5px;}
.login-scenario-toggle,.login-scenario-reset {border:0;padding:3px 0;background:none;color:inherit;font:inherit;font-size:11px;cursor:pointer;}
.login-scenario-toggle {display:inline-flex;align-items:center;gap:7px;}
.login-scenario-chevron {width:5px;height:5px;border-right:1px solid currentColor;border-bottom:1px solid currentColor;transform:translateY(-1px) rotate(45deg);}
.login-scenario-toggle[aria-expanded="true"] .login-scenario-chevron {transform:translateY(1px) rotate(225deg);}
.login-scenario-toggle:focus-visible,.login-scenario-reset:focus-visible {outline:2px solid currentColor;outline-offset:3px;}
.login-scenario-menu {position:absolute;top:28px;left:0;width:230px;padding:14px;border:1px solid var(--pastoral-machine);border-radius:8px;background:var(--login-sky);z-index:3;}
.login-scenario-options {display:grid;grid-template-columns:1fr 1fr;gap:5px;}
.login-scenario-options button {min-height:32px;padding:6px 8px;border:1px solid transparent;border-radius:4px;background:none;color:inherit;font:inherit;font-size:12px;text-align:left;cursor:pointer;}
.login-scenario-options button[aria-pressed="true"] {border-color:currentColor;font-weight:600;}
.login-scenario-options button:hover,.login-scenario-options button:focus-visible {background:var(--login-input-bg);outline:1px solid currentColor;outline-offset:-1px;}
.login-scenario-menu small {display:block;font-size:10px;line-height:1.6;margin-top:9px;opacity:.7;}
.login-clock-copy .login-scenario-date {font-size:10px;}
@media(max-width:760px) {.login-scene-clock {display:none;}}
</style>
