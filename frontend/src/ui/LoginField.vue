<script setup>
import { onMounted, onUnmounted, ref, shallowRef } from 'vue';
import { setLoginSceneMode, clearLoginSceneMode } from '../account/appearance.js';
import { daylightAt } from './login-daylight.js';
import { createSceneClock } from './login-scene-clock.js';
import LoginSceneClock from './LoginSceneClock.vue';
import { makeLandscape, drawLandscapeMarks, referenceTones, riverBoat } from './login-landscape.js';
import { makeFarmActivity, createFarmOperation } from './login-farm-activity.js';
import { createSky, makeSkyMarks, skyIllumination, skyTones } from './login-sky.js';
import { createVillage } from './login-village.js';
import { makeLetteringMarks } from './login-lettering.js';
import { calendarAt } from './login-calendar.js';
import { makeSeasonLandscape, makeSeasonMarks, seasonTones } from './login-season.js';
import { makeFestivalMarks, festivalTones } from './login-festivals.js';
import { yearVisualState } from './login-year-visuals.js';

const canvas=ref(null),daySample=ref(null),nightSample=ref(null),reduced=ref(false);
const clock=createSceneClock(),snapshot=shallowRef(clock.read()),period=ref('day');
const operations=createFarmOperation(),harvestOperations=createFarmOperation(),village=createVillage();
let context,observer,motionQuery,baseScene,skyModel,signature=[],terrainFrame,terrainBlend;
let frame=0,last=0,lastPaint=0,lastState,transition=null;
let motionSeconds=0,cloudSeconds=0,width=0,height=0,inputFocused=false,festivalStarted=null;
const seasonAssets=new Map();
let activeTheme=null;
const baseTones=['haze','sun','glint','machine','spray','mist','patrol','deviceHalo','beaconRed','beaconOrange',
  'harvesterTire','harvesterBody','harvesterLight','harvesterGlass','harvesterMetal','harvesterTrim','harvesterEdge','harvesterGrain',
  'villageSmoke','villageWindow','sealInk','letteringInk','waterWarm','waterMoon','warmTint',...skyTones,...referenceTones,...seasonTones,...festivalTones];
const clamp=value=>Math.max(0,Math.min(1,value));
const smooth=(low,high,value)=>{const t=clamp((value-low)/(high-low));return t*t*(3-2*t);};
const blend=(a,b,p)=>a.map((channel,i)=>channel+(b[i]-channel)*p);
const css=channels=>'#'+channels.map(value=>Math.round(value).toString(16).padStart(2,'0')).join('');
const parseColor=value=>value.startsWith('#') ? value.slice(1).match(/../g).map(v=>parseInt(v,16)) : value.match(/[\d.]+/g).slice(0,3).map(Number);
const mixColors=(a,b,p)=>Object.fromEntries(baseTones.map(tone=>[tone,css(blend(a[tone],b[tone],p))]));
function readPalette(element) {
  const style=getComputedStyle(element);
  const result=Object.fromEntries(baseTones.map(tone=>[tone,parseColor(style.getPropertyValue('--pastoral-'+tone.replace(/[A-Z]/g,l=>'-'+l.toLowerCase())).trim())]));
  result.paper=parseColor(style.getPropertyValue('--login-sky').trim());
  result.twilightPaper=parseColor(style.getPropertyValue('--login-twilight-paper').trim());
  return result;
}
function rebuild() {
  if(!context || !width || !height)return;
  baseScene=makeLandscape(width,height);skyModel=createSky(14,baseScene.clouds);
  signature=makeLetteringMarks(width,height);
  seasonAssets.clear();terrainFrame=makeBuffer();terrainBlend=makeBuffer();
}
function makeBuffer() {
  const buffer=document.createElement('canvas');buffer.width=canvas.value.width;buffer.height=canvas.value.height;
  const ctx=buffer.getContext('2d'),ratio=buffer.width/width;ctx.setTransform(ratio,0,0,ratio,0,0);
  return {buffer,ctx};
}
function getSeason(season) {
  if(seasonAssets.has(season))return seasonAssets.get(season);
  const scene=makeSeasonLandscape(baseScene,season),caches={};
  daySample.value.dataset.season=season;nightSample.value.dataset.season=season;
  const harvestForeground=scene.marks.filter(m=>m.field && m.u<.19 && m.v>.50 && m.v<.53);
  const palettes={day:readPalette(daySample.value),night:readPalette(nightSample.value)};
  palettes.warm=Object.fromEntries(baseTones.map(tone=>[tone,blend(palettes.day[tone],palettes.day.warmTint,
    tone.startsWith('ink-') ? .19 : 0)]));
  for(const name of ['day','night','warm']) {
    const {buffer,ctx}=makeBuffer();
    drawLandscapeMarks(ctx,scene.marks,mixColors(palettes[name],palettes[name],0));caches[name]=buffer;
  }
  const asset={scene,palettes,caches,harvestForeground};seasonAssets.set(season,asset);return asset;
}
function colorsFor(palettes,light) {
  const brightness=light.surfaceDaylight,colors=mixColors(palettes.night,palettes.day,brightness);
  for(const tone of referenceTones)colors[tone]=css(blend(blend(palettes.night[tone],palettes.day[tone],brightness),palettes.warm[tone],light.warmLight*.3*brightness));
  return colors;
}
function drawTerrain(layers,light,illumination) {
  const target=terrainBlend.ctx,ctx=terrainFrame.ctx;
  target.clearRect(0,0,width,height);target.globalCompositeOperation='lighter';
  for(const {asset,weight} of layers) {
    ctx.clearRect(0,0,width,height);ctx.globalAlpha=1;
    ctx.drawImage(asset.caches.night,0,0,width,height);
    ctx.globalAlpha=light.surfaceDaylight;ctx.drawImage(asset.caches.day,0,0,width,height);
    ctx.globalAlpha=light.warmLight*.3*light.surfaceDaylight;ctx.drawImage(asset.caches.warm,0,0,width,height);ctx.globalAlpha=1;
    drawLandscapeMarks(ctx,asset.scene.animated,colorsFor(asset.palettes,light),motionSeconds,illumination);
    // Add premultiplied layers, so matching field cells do not acquire white gaps mid-blend.
    target.globalAlpha=weight;target.drawImage(terrainFrame.buffer,0,0,width,height);
  }
  target.globalAlpha=1;target.globalCompositeOperation='source-over';
  context.drawImage(terrainBlend.buffer,0,0,width,height);
}
function currentState() {
  const state=clock.read();let light=daylightAt(state.atMs,undefined,state.weather);
  light.hour=state.civil.hour+state.civil.minute/60+state.civil.second/3600;
  light.surfaceDaylight=smooth(-12,8,light.sun.altitude);
  return {...state,...yearVisualState(state,light),...calendarAt(state.atMs)};
}
function paint(now) {
  const state=currentState();snapshot.value=state;period.value=state.light.period;
  const theme=state.fusion>0 ? state.light.surfaceDaylight>.5 ? 'LIGHT' : 'DARK' : state.light.sun.altitude < -3 ? 'DARK' : 'LIGHT';
  if(activeTheme!==theme){activeTheme=theme;setLoginSceneMode(theme);}
  if(!context || !baseScene || !width || !height)return;
  const seasons=state.tourKind==='year' ? state.seasonBlend : {from:state.season,to:state.season,amount:0};
  const layers=[{season:seasons.from,asset:getSeason(seasons.from),weight:1-seasons.amount}];
  if(seasons.from!==seasons.to)layers.push({season:seasons.to,asset:getSeason(seasons.to),weight:seasons.amount});
  for(const season of seasonAssets.keys())if(!layers.some(layer=>layer.season===season))seasonAssets.delete(season);
  const primary=layers.reduce((a,b)=>a.weight>b.weight?a:b),palettes=primary.asset.palettes;
  if(lastState && (lastState.mode!==state.mode || lastState.tourKind!==state.tourKind || lastState.weather!==state.weather || lastState.scenario!==state.scenario))transition={from:lastState,at:now};
  const progress=transition ? clamp((now-transition.at)/300) : 1;
  const light={...state.light};
  if(transition && progress<1)for(const key of ['daylight','night','warmLight','twilight','evening','moonlight','surfaceDaylight'])
    light[key]=transition.from.light[key]*(1-progress)+light[key]*progress;
  else transition=null;
  lastState=state;
  const brightness=light.surfaceDaylight;
  const colors=colorsFor(palettes,light);
  // A uniform paper tone changes over time; atmospheric shapes remain glyphs.
  const twilightPaper=blend(palettes.day.twilightPaper,palettes.day.paper,state.weather==='overcast' ? .7 : 0);
  const paper=brightness<.5 ? blend(palettes.night.paper,twilightPaper,brightness*2)
    : blend(twilightPaper,palettes.day.paper,brightness*2-1);
  canvas.value.parentElement.style.backgroundColor=css(paper);
  const skyOptions={width,height,light,weather:state.weather,motionSeconds:cloudSeconds,reduced:reduced.value};
  context.clearRect(0,0,width,height);
  const skyMarks=makeSkyMarks(skyModel,skyOptions);
  if(transition) {
    const old=makeSkyMarks(skyModel,{...skyOptions,light:transition.from.light,weather:transition.from.weather});
    drawLandscapeMarks(context,old.map(m=>({...m,opacity:(m.opacity??1)*(1-progress)})),colors);
    drawLandscapeMarks(context,skyMarks.map(m=>({...m,opacity:(m.opacity??1)*progress})),colors);
  } else drawLandscapeMarks(context,skyMarks,colors);
  const illumination=skyIllumination(skyModel,skyOptions);
  drawTerrain(layers,light,illumination);
  for(const layer of layers)drawLandscapeMarks(context,makeSeasonMarks(width,height,{season:layer.season,light,motionSeconds,reduced:reduced.value})
    .map(mark=>({...mark,opacity:mark.opacity*layer.weight})),colorsFor(layer.asset.palettes,light));
  drawLandscapeMarks(context,riverBoat(width,height,motionSeconds),colors);
  const daylight=state.fusion>0 ? light.surfaceDaylight>.5 : state.light.sun.altitude>0;
  operations.advance(motionSeconds,daylight && state.season!=='winter');
  harvestOperations.advance(motionSeconds,daylight && state.season==='autumn');
  const activity=makeFarmActivity(width,height,motionSeconds,new Date(state.atMs),{
    night:light.night>.7,reduced:reduced.value,
    operation:{...operations.read(),machineSeconds:harvestOperations.read().machineSeconds}
  }).filter(mark=>state.season==='autumn' || (mark.equipment!=='harvester' && mark.effect!=='harvestGrain'));
  drawLandscapeMarks(context,activity,colors);
  const machine=activity.filter(m=>m.equipment==='harvester');
  const left=Math.min(...machine.map(m=>m.x)),right=Math.max(...machine.map(m=>m.x));
  const bottom=Math.max(...machine.map(m=>m.y)),scale=width/1448;
  drawLandscapeMarks(context,primary.asset.harvestForeground.filter(m=>m.x>=left && m.x<=right+2*scale &&
    m.y>=bottom-7*scale && m.y<=bottom+4*scale).map(m=>({...m,opacity:1})),colors);
  village.advance({motionSeconds,light,reduced:reduced.value});
  drawLandscapeMarks(context,village.marks(width,height),colors);
  const festivalNight=state.festivals.includes('spring-festival') && light.night>.25;
  if(!festivalNight)festivalStarted=null;
  else if(festivalStarted===null)festivalStarted=motionSeconds;
  drawLandscapeMarks(context,makeFestivalMarks(width,height,{festivals:state.festivals,light,motionSeconds,
    festivalSeconds:festivalStarted===null?0:motionSeconds-festivalStarted,reduced:reduced.value}),colors);
  drawLandscapeMarks(context,signature,colors);
}
function tick(now) {
  frame=0;if(document.hidden)return;
  const delta=Math.min(Math.max(0,now-last)/1000,.12);last=now;
  if(!reduced.value && width>0) {
    motionSeconds+=delta*(inputFocused ? .65 : 1);
    cloudSeconds+=delta*(snapshot.value.mode==='tour' ? 1.6 : 1);
  }
  const reading=clock.read(),modeChanged=reading.mode!==snapshot.value.mode;
  if(!reduced.value && width>0)snapshot.value=reading;
  if(modeChanged || now-lastPaint>=(reduced.value || !width ? 1000 : 1000/12)){lastPaint=now;paint(now);}
  frame=requestAnimationFrame(tick);
}
function sync() {
  cancelAnimationFrame(frame);frame=0;clock.setHidden(document.hidden);
  if(document.hidden)return;
  last=performance.now();paint(last);frame=requestAnimationFrame(tick);
}
function startTour(kind){clock.start(kind);sync();}
function returnToNow(){clock.returnToNow();sync();}
function selectScenario(id){clock.setScenario(id);sync();}
function resize() {
  const box=canvas.value.getBoundingClientRect();width=box.width;height=box.height;
  if(!width)clock.returnToNow();
  const ratio=Math.min(window.devicePixelRatio || 1,1.5);
  canvas.value.width=Math.round(width*ratio);canvas.value.height=Math.round(height*ratio);
  context?.setTransform(ratio,0,0,ratio,0,0);rebuild();sync();
}
function focusChanged(){queueMicrotask(()=>{inputFocused=!!document.activeElement?.closest('.login-panel');});}
function motionChanged(){reduced.value=motionQuery.matches;sync();}
onMounted(()=>{
  context=canvas.value.getContext('2d');
  motionQuery=matchMedia('(prefers-reduced-motion: reduce)');reduced.value=motionQuery.matches;
  motionQuery.addEventListener('change',motionChanged);
  observer=new ResizeObserver(resize);observer.observe(canvas.value);
  document.addEventListener('visibilitychange',sync);
  document.addEventListener('focusin',focusChanged);document.addEventListener('focusout',focusChanged);
  focusChanged();resize();
});
onUnmounted(()=>{
  cancelAnimationFrame(frame);observer?.disconnect();motionQuery?.removeEventListener('change',motionChanged);
  document.removeEventListener('visibilitychange',sync);
  document.removeEventListener('focusin',focusChanged);document.removeEventListener('focusout',focusChanged);
  clearLoginSceneMode();
});
</script>

<template>
  <div class="login-field">
    <canvas ref="canvas" aria-hidden="true"></canvas>
    <span ref="daySample" class="login-color-sample" data-palette="day" aria-hidden="true"></span>
    <span ref="nightSample" class="login-color-sample" data-palette="night" aria-hidden="true"></span>
    <span class="login-art-accessible" role="img" aria-label="禾风水韵，李東旭作"></span>
    <LoginSceneClock :snapshot="snapshot" :period="period" @start="startTour" @return="returnToNow" @scenario="selectScenario" />
  </div>
</template>
