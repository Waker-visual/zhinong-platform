import { DISPLAY_SITE, civilTime, weatherForDate, makeDayTour, tourInstant } from './login-daylight.js';
import { previewInstant } from './login-calendar.js';
import { makeYearTour, yearTourSpeed, YEAR_TOUR_DURATION_MS } from './login-year-tour.js';

export const TOUR_DURATION_MS=12_000;

export function createSceneClock({now=Date.now,monotonic=()=>performance.now(),site=DISPLAY_SITE}={}) {
  let mode='real',hidden=false,startedAtMono=0,pausedAtMono=null,pausedMs=0,startInstantMs=0,tourIndex=0;
  let table,tourWeather,scenario='auto',previewOffset=0,tourKind=null,durationMs=0;
  function read() {
    const elapsed=mode==='tour'?Math.max(0,(pausedAtMono??monotonic())-startedAtMono-pausedMs):0;
    if(mode==='tour'&&elapsed>=durationMs) {
      if(tourKind==='year'){scenario='auto';previewOffset=0;}
      mode='real';tourKind=null;durationMs=0;
    }
    const progress=mode==='tour'?elapsed/durationMs:0;
    const atMs=mode==='tour'?tourInstant(table,progress):now()+previewOffset;
    let speed=1;
    if(mode==='tour') {
      if(tourKind==='year')speed=yearTourSpeed(table,progress);
      else {
        const first=Math.max(0,progress-.00001),last=Math.min(1,progress+.00001);
        speed=(tourInstant(table,last)-tourInstant(table,first))/((last-first)*durationMs);
      }
    }
    const yearPhase=tourKind!=='year'?null:elapsed<8000?'opening':elapsed<16000?'accelerating':elapsed<52000?'seasons':'decelerating';
    return {mode,atMs,scenario,progress,tourKind,durationMs,speed,yearPhase,
      weather:mode==='tour'?tourWeather:weatherForDate(atMs,site),civil:civilTime(atMs,site.timeZone)};
  }
  function start(kind='day') {
    const current=read();
    if(current.mode==='tour'&&tourKind===kind)return;
    startInstantMs=current.atMs;
    table=kind==='year'?makeYearTour(startInstantMs,site).table:makeDayTour(startInstantMs,site);
    startedAtMono=monotonic();pausedMs=0;
    pausedAtMono=hidden?startedAtMono:null;
    tourKind=kind;durationMs=kind==='year'?YEAR_TOUR_DURATION_MS:TOUR_DURATION_MS;
    tourWeather=kind==='year'?'clear':['clear','cloudy','overcast'][tourIndex++%3];mode='tour';
  }
  function setScenario(id) {
    const actual=now();
    previewOffset=previewInstant(id,actual,site)-actual;scenario=id;
    mode='real';tourKind=null;durationMs=0;pausedAtMono=null;pausedMs=0;
  }
  function returnToNow() {setScenario('auto');}
  function setHidden(value) {
    if(hidden===value)return;
    hidden=value;
    if(mode!=='tour')return;
    if(hidden)pausedAtMono=monotonic();
    else {pausedMs+=monotonic()-pausedAtMono;pausedAtMono=null;}
  }
  return {read,start,returnToNow,setHidden,setScenario};
}
