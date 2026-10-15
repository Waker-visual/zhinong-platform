import { DISPLAY_SITE, civilTime } from './login-daylight.js';
import lunar from 'lunar-javascript';

export const SCENE_SCENARIOS=[
  {id:'auto',label:'实际时令'},
  {id:'spring',label:'春日'},
  {id:'summer',label:'夏日'},
  {id:'autumn',label:'秋日'},
  {id:'winter',label:'冬日'},
  {id:'mid-autumn',label:'中秋'},
  {id:'spring-festival',label:'春节'},
  {id:'national-day',label:'国庆'},
];
const lunarDates=new Map();
const seasonForMonth=month=>month>=3&&month<=5?'spring':month>=6&&month<=8?'summer':month>=9&&month<=11?'autumn':'winter';
const civilStamp=civil=>Date.UTC(civil.year,civil.month-1,civil.day,civil.hour??0,civil.minute??0,civil.second??0);

function lunarTime(civil) {
  if(!lunarDates.has(civil.dateKey)) {
    const date=lunar.Solar.fromYmd(civil.year,civil.month,civil.day).getLunar();
    // Leap months are negative, so they cannot match ordinary festival months.
    lunarDates.set(civil.dateKey,{month:date.getMonth(),day:date.getDay()});
  }
  return lunarDates.get(civil.dateKey);
}

export function seasonAt(atMs,site=DISPLAY_SITE) {
  return seasonForMonth(civilTime(atMs,site.timeZone).month);
}

export function calendarAt(atMs,site=DISPLAY_SITE) {
  const civil=civilTime(atMs,site.timeZone),date=lunarTime(civil),festivals=[];
  if(date.month===1&&date.day<=7)festivals.push('spring-festival');
  if(date.month===8&&date.day>=15&&date.day<=17)festivals.push('mid-autumn');
  if(civil.month===10&&civil.day<=7)festivals.push('national-day');
  return {season:seasonForMonth(civil.month),festivals};
}

export function instantForCivil(civil,timeZone) {
  const target=civilStamp(civil);
  let instant=target,previous;
  for(let i=0;i<4;i++) {
    const offset=target-civilStamp(civilTime(instant,timeZone));
    if(offset===0)return instant;
    const next=instant+offset;
    // A nonexistent spring-forward time oscillates: use the first later valid time.
    if(next===previous)return Math.max(instant,next);
    previous=instant;instant=next;
  }
  return instant;
}

export function previewInstant(id,nowMs,site=DISPLAY_SITE) {
  if(id==='auto')return nowMs;
  const civil=civilTime(nowMs,site.timeZone);
  const fixed={spring:[4,15],summer:[7,15],autumn:[10,15],winter:[1,15],'national-day':[10,1]};
  let date=fixed[id];
  if(id==='spring-festival'||id==='mid-autumn') {
    const month=id==='spring-festival'?1:8,day=id==='spring-festival'?1:15;
    // Scan UTC noons, then retain the matching date in the site's own civil calendar.
    for(let instant=Date.UTC(civil.year,0,1,12);instant<Date.UTC(civil.year+1,0,1,12);instant+=86_400_000) {
      const found=civilTime(instant,site.timeZone);
      const lunarDate=lunarTime(found);
      if(lunarDate.month!==month||lunarDate.day!==day)continue;
      if(found.year===civil.year){date=[found.month,found.day];break;}
    }
  }
  if(!date)throw new RangeError(`Unknown or unavailable scene scenario: ${id}`);
  return instantForCivil({...civil,month:date[0],day:date[1]},site.timeZone);
}
