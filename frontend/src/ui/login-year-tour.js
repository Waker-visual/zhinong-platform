import { DISPLAY_SITE, civilTime } from './login-daylight.js';
import { calendarAt, instantForCivil } from './login-calendar.js';

export const YEAR_TOUR_DURATION_MS=60_000;
export { tourInstant as yearTourInstant } from './login-daylight.js';
const DAY=86_400_000,STEPS=7200,STEP=60/STEPS;

function festivalDays(startMs,endMs,site) {
  const first=civilTime(startMs,site.timeZone),last=civilTime(endMs,site.timeZone);
  const ranges=[],recent=new Map(),seen=new Set();
  const limit=Date.UTC(last.year,last.month-1,last.day+9,12);
  for(let at=Date.UTC(first.year,first.month-1,first.day-8,12);at<limit;at+=DAY) {
    const civil=civilTime(at,site.timeZone);
    if(seen.has(civil.dateKey))continue;seen.add(civil.dateKey);
    const midnight=instantForCivil({...civil,hour:0,minute:0,second:0},site.timeZone);
    const nextDate=new Date(Date.UTC(civil.year,civil.month-1,civil.day+1));
    const next=instantForCivil({year:nextDate.getUTCFullYear(),month:nextDate.getUTCMonth()+1,
      day:nextDate.getUTCDate(),hour:0},site.timeZone);
    for(const festival of calendarAt(at,site).festivals) {
      const previous=recent.get(festival);
      if(previous?.endMs===midnight)previous.endMs=next;
      else {
        const range={festival,startMs:midnight,endMs:next};
        ranges.push(range);recent.set(festival,range);
      }
    }
  }
  return ranges.filter(range=>range.endMs>startMs&&range.startMs<endMs);
}

function stopsFor(ranges,startMs,endMs,site) {
  const windows=[];
  for(const range of ranges) {
    const civil=civilTime(range.startMs,site.timeZone);
    const from=instantForCivil({...civil,hour:6},site.timeZone);
    const next=new Date(Date.UTC(civil.year,civil.month-1,civil.day+1));
    const to=instantForCivil({year:next.getUTCFullYear(),month:next.getUTCMonth()+1,day:next.getUTCDate(),hour:6},site.timeZone);
    if(to>startMs&&from<endMs)windows.push({startMs:from,endMs:to,festivals:[range.festival]});
  }
  windows.sort((a,b)=>a.startMs-b.startMs);
  const merged=[];
  for(const window of windows) {
    const previous=merged.at(-1);
    if(previous&&window.startMs<previous.endMs) {
      previous.endMs=Math.max(previous.endMs,window.endMs);
      previous.festivals=[...new Set([...previous.festivals,...window.festivals])];
    } else merged.push(window);
  }
  return merged;
}

export function makeYearTour(startMs,site=DISPLAY_SITE) {
  const civil=civilTime(startMs,site.timeZone),year=civil.year+1;
  const day=Math.min(civil.day,new Date(Date.UTC(year,civil.month,0)).getUTCDate());
  const endMs=instantForCivil({...civil,year,day},site.timeZone)+((startMs%1000)+1000)%1000;
  const festivalRanges=festivalDays(startMs,endMs,site);
  const festivalWindows=stopsFor(festivalRanges,startMs,endMs,site);
  const stops=festivalWindows.map(window=>({from:window.startMs-startMs,to:window.endMs-startMs,
    speed:(window.endMs-window.startMs)/3}));
  const low=DAY/4;
  function velocity(seconds,offset,peak) {
    const factor=seconds<=8?0:seconds<16?(1-Math.cos(Math.PI*(seconds-8)/8))/2:
      seconds<=52?1:(1+Math.cos(Math.PI*(seconds-52)/8))/2;
    const base=low+(peak-low)*factor;
    let extra=0;
    for(const stop of stops) {
      const distance=Math.max(stop.from-offset,offset-stop.to,0),shoulder=DAY/2;
      if(distance>=shoulder)continue;
      const weight=(1+Math.cos(Math.PI*distance/shoulder))/2;
      extra=Math.max(extra,weight*Math.max(0,1/stop.speed-1/base));
    }
    return 1/(1/base+extra);
  }
  function integrate(peak,record=false) {
    let offset=0;
    const table=record?[{instantMs:startMs,progress:0,speed:velocity(0,0,peak)/1000}]:null;
    for(let i=0;i<STEPS;i++) {
      const t=i*STEP,k1=velocity(t,offset,peak);
      const k2=velocity(t+STEP/2,offset+k1*STEP/2,peak);
      const k3=velocity(t+STEP/2,offset+k2*STEP/2,peak);
      const k4=velocity(t+STEP,offset+k3*STEP,peak);
      offset+=STEP*(k1+2*k2+2*k3+k4)/6;
      if(record)table.push({instantMs:startMs+offset,progress:(i+1)/STEPS,speed:velocity(t+STEP,offset,peak)/1000});
    }
    return record?table:offset;
  }
  // Solve only the fast speed: slow opening, smooth ramps and full-day stops stay intact.
  const length=endMs-startMs;
  let lower=low,upper=16*DAY;
  while(integrate(upper)<length)upper*=2;
  for(let i=0;i<36;i++) {
    const middle=(lower+upper)/2;
    if(integrate(middle)<length)lower=middle;else upper=middle;
  }
  const table=integrate((lower+upper)/2,true);
  table.at(-1).instantMs=endMs;
  return {table,endMs,festivalWindows,festivalRanges};
}

export function yearTourSpeed(table,progress) {
  const p=Math.max(0,Math.min(1,progress)),index=p*(table.length-1),before=Math.floor(index),after=Math.min(before+1,table.length-1);
  return table[before].speed+(table[after].speed-table[before].speed)*(index-before);
}
