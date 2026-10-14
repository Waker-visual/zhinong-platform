import { getPosition, getMoonPosition, getMoonIllumination } from 'suncalc';

// Public demonstration coordinates, independent of unauthenticated tenant data.
export const DISPLAY_SITE=Object.freeze({latitude:47.26,longitude:132.73,timeZone:'Asia/Shanghai'});
const formatters=new Map();
const clamp=value=>Math.max(0,Math.min(1,value));
const smooth=(low,high,value)=>{const t=clamp((value-low)/(high-low));return t*t*(3-2*t);};

export function civilTime(atMs,timeZone=DISPLAY_SITE.timeZone) {
  if(!formatters.has(timeZone))formatters.set(timeZone,new Intl.DateTimeFormat('en-CA',{
    timeZone,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'
  }));
  const parts=Object.fromEntries(formatters.get(timeZone).formatToParts(atMs)
    .filter(part=>part.type!=='literal').map(part=>[part.type,part.value]));
  return {dateKey:`${parts.year}-${parts.month}-${parts.day}`,year:+parts.year,month:+parts.month,
    day:+parts.day,hour:+parts.hour,minute:+parts.minute,second:+parts.second};
}

export function daylightAt(atMs,site=DISPLAY_SITE,weather='clear') {
  const date=new Date(atMs),sun=getPosition(date,site.latitude,site.longitude);
  const position=getMoonPosition(date,site.latitude,site.longitude),illumination=getMoonIllumination(date);
  // SunCalc 2 returns degrees and north-clockwise azimuth, including lunar angles.
  const moon={altitude:position.altitude,azimuth:position.azimuth,phase:illumination.phase,
    fraction:illumination.fraction,angle:illumination.angle,parallacticAngle:position.parallacticAngle};
  const rising=getPosition(new Date(atMs+60_000),site.latitude,site.longitude).altitude>sun.altitude;
  const clearLight=smooth(-6,12,sun.altitude),night=1-smooth(-12,-3,sun.altitude);
  const transmission=weather==='overcast'?.62:weather==='cloudy'?.84:1;
  const warmLight=Math.exp(-((sun.altitude/7)**2))*transmission;
  return {sun,moon,daylight:clearLight*transmission,
    twilight:smooth(-12,-3,sun.altitude)*(1-smooth(0,12,sun.altitude)),warmLight,
    evening:rising?0:warmLight,night,
    period:sun.altitude<=-6?'night':sun.altitude<6?(rising?'dawn':'dusk'):'day',
    moonlight:smooth(-2,15,moon.altitude)*moon.fraction*night*transmission};
}

export function weatherForDate(atMs,site=DISPLAY_SITE) {
  const key=civilTime(atMs,site.timeZone).dateKey;
  let seed=0;for(const char of key)seed=(seed*31+char.charCodeAt(0))>>>0;
  return ['clear','cloudy','overcast'][seed%3];
}

export function makeDayTour(startMs,site=DISPLAY_SITE) {
  const weight=instant=>1+3*Math.exp(-((getPosition(new Date(instant),site.latitude,site.longitude).altitude/9)**2));
  const table=[{instantMs:startMs,progress:0}];
  let mass=0,previousWeight=weight(startMs);
  for(let i=1;i<=288;i++) {
    const instantMs=startMs+i*300_000,nextWeight=weight(instantMs);
    mass+=300_000*(previousWeight+nextWeight)/2;
    table.push({instantMs,progress:mass});previousWeight=nextWeight;
  }
  for(const entry of table)entry.progress/=mass;
  table[0]={instantMs:startMs,progress:0};
  table[table.length-1]={instantMs:startMs+86_400_000,progress:1};
  return table;
}

export function tourInstant(table,progress) {
  if(progress<=0)return table[0].instantMs;
  if(progress>=1)return table[table.length-1].instantMs;
  let low=0,high=table.length-1;
  while(high-low>1) {
    const mid=(low+high)>>1;
    if(table[mid].progress<=progress)low=mid;else high=mid;
  }
  const before=table[low],after=table[high],fraction=(progress-before.progress)/(after.progress-before.progress);
  return before.instantMs+(after.instantMs-before.instantMs)*fraction;
}
