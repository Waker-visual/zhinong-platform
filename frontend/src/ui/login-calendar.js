import { DISPLAY_SITE, civilTime } from './login-daylight.js';
import { LUNAR_FESTIVAL_FIRST_YEAR, LUNAR_FESTIVAL_DATES } from './login-lunar-festivals.js';

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
const seasonForMonth=month=>month>=3&&month<=5?'spring':month>=6&&month<=8?'summer':month>=9&&month<=11?'autumn':'winter';
const civilStamp=civil=>Date.UTC(civil.year,civil.month-1,civil.day,civil.hour??0,civil.minute??0,civil.second??0);

// The table holds the non-leap first and eighth lunar months only, so a leap month never repeats a festival.
// Both dates always fall in their own civil year; years outside the table simply have no lunar festivals.
function lunarFestivalDate(year,index) {
  const value=LUNAR_FESTIVAL_DATES[(year-LUNAR_FESTIVAL_FIRST_YEAR)*2+index];
  return value===undefined ? null : [Math.floor(value/100),value%100];
}
function lunarFestivalDay(civil,index,length) {
  const date=lunarFestivalDate(civil.year,index);
  if(!date)return false;
  const day=(Date.UTC(civil.year,civil.month-1,civil.day)-Date.UTC(civil.year,date[0]-1,date[1]))/86_400_000;
  return day>=0 && day<length;
}

export function seasonAt(atMs,site=DISPLAY_SITE) {
  return seasonForMonth(civilTime(atMs,site.timeZone).month);
}

export function calendarAt(atMs,site=DISPLAY_SITE) {
  const civil=civilTime(atMs,site.timeZone),festivals=[];
  // Spring Festival spans lunar 1/1–1/7; mid-autumn spans lunar 8/15–8/17.
  if(lunarFestivalDay(civil,0,7))festivals.push('spring-festival');
  if(lunarFestivalDay(civil,1,3))festivals.push('mid-autumn');
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
  if(id==='spring-festival'||id==='mid-autumn')date=lunarFestivalDate(civil.year,id==='spring-festival'?0:1);
  if(!date)throw new RangeError(`Unknown or unavailable scene scenario: ${id}`);
  return instantForCivil({...civil,month:date[0],day:date[1]},site.timeZone);
}
