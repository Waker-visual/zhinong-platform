import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { DISPLAY_SITE, civilTime } from './login-daylight.js';
import { SCENE_SCENARIOS, seasonAt, calendarAt, previewInstant } from './login-calendar.js';
import lunar from 'lunar-javascript';

const at=date=>Date.parse(`${date}T12:34:56+08:00`);

test('seasons follow farm civil months, including winter across calendar years',()=>{
  for(const [date,want] of [['2025-12-31','winter'],['2026-01-01','winter'],['2026-02-28','winter'],
    ['2026-03-01','spring'],['2026-05-31','spring'],['2026-06-01','summer'],['2026-08-31','summer'],
    ['2026-09-01','autumn'],['2026-11-30','autumn'],['2026-12-01','winter']])assert.equal(seasonAt(at(date)),want);
  const instant=Date.parse('2026-02-28T16:30:00Z');
  assert.equal(seasonAt(instant),'spring');
  assert.equal(seasonAt(instant,{...DISPLAY_SITE,timeZone:'America/New_York'}),'winter');
});

// Independent Hong Kong Observatory Gregorian–Lunar calendar fixtures.
// https://www.hko.gov.hk/en/gts/time/calendar/pdf/files/2026e.pdf
test('2026 lunar festivals begin on verified dates and end at their configured civil-day boundaries',()=>{
  for(const [date,want] of [['2026-02-16',[]],['2026-02-17',['spring-festival']],
    ['2026-02-23',['spring-festival']],['2026-02-24',[]],['2026-09-24',[]],
    ['2026-09-25',['mid-autumn']],['2026-09-27',['mid-autumn']],['2026-09-28',[]]])
    assert.deepEqual(calendarAt(at(date)).festivals,want,date);
  assert.deepEqual(calendarAt(at('2026-02-17')),{season:'winter',festivals:['spring-festival']});
});

// https://www.hko.gov.hk/en/gts/time/calendar/pdf/files/2027e.pdf
test('2027 Spring Festival begins February 6 and ends February 12, independent of ICU lunar drift',()=>{
  for(const [date,want] of [['2027-02-05',[]],['2027-02-06',['spring-festival']],
    ['2027-02-07',['spring-festival']], // Lunar day 2, not another first day.
    ['2027-02-12',['spring-festival']],['2027-02-13',[]]])
    assert.deepEqual(calendarAt(at(date)).festivals,want,date);
  const preview=civilTime(previewInstant('spring-festival',at('2027-10-14')));
  assert.equal(preview.dateKey,'2027-02-06');
  assert.deepEqual([preview.hour,preview.minute,preview.second],[12,34,56]);
});

test('2027 mid-autumn begins September 15 and preview uses that same official date',()=>{
  for(const [date,want] of [['2027-09-14',[]],['2027-09-15',['mid-autumn']],
    ['2027-09-17',['mid-autumn']],['2027-09-18',[]]])
    assert.deepEqual(calendarAt(at(date)).festivals,want,date);
  assert.equal(civilTime(previewInstant('mid-autumn',at('2027-10-14'))).dateKey,'2027-09-15');
});

test('2027 lunar boundary follows the farm civil date, including a different timezone',()=>{
  const instant=Date.parse('2027-02-05T16:00:00Z');
  assert.deepEqual(calendarAt(instant).festivals,['spring-festival']);
  assert.deepEqual(calendarAt(instant,{...DISPLAY_SITE,timeZone:'America/New_York'}).festivals,[]);
  assert.deepEqual(calendarAt(Date.parse('2027-02-06T05:00:00Z'),{...DISPLAY_SITE,timeZone:'America/New_York'}).festivals,['spring-festival']);
});

test('mid-autumn and national day coexist, while national day lasts October 1–7',()=>{
  assert.deepEqual(calendarAt(at('2020-10-01')).festivals,['mid-autumn','national-day']);
  assert.deepEqual(calendarAt(at('2020-10-03')).festivals,['mid-autumn','national-day']);
  assert.deepEqual(calendarAt(at('2020-10-04')).festivals,['national-day']);
  assert.deepEqual(calendarAt(at('2020-10-07')).festivals,['national-day']);
  assert.deepEqual(calendarAt(at('2020-10-08')).festivals,[]);
});

test('2033 leap month does not create another lunar festival or shift the next new year',()=>{
  // HKO 2033 table places leap month 11 on Dec 22, not a leap eighth month.
  assert.deepEqual(calendarAt(at('2033-12-22')).festivals,[]);
  assert.deepEqual(calendarAt(at('2033-09-08')).festivals,['mid-autumn']);
  assert.deepEqual(calendarAt(at('2034-02-19')).festivals,['spring-festival']);
});

test('a leap eighth month does not repeat mid-autumn',()=>{
  assert.deepEqual(calendarAt(at('1995-09-09')).festivals,['mid-autumn']);
  assert.deepEqual(calendarAt(at('1995-10-09')).festivals,[]);
});

test('the bundled festival table matches lunar-javascript on every civil day from 1901 to 2100',()=>{
  // The page ships a generated table instead of lunar-javascript; regenerate with tools/generate_lunar_festivals.cjs.
  for(let day=Date.UTC(1901,0,1);day<Date.UTC(2101,0,1);day+=86_400_000) {
    const date=new Date(day),lunarDate=lunar.Solar.fromYmd(date.getUTCFullYear(),date.getUTCMonth()+1,date.getUTCDate()).getLunar();
    const month=lunarDate.getMonth(),dayOfMonth=lunarDate.getDay(),want=[];
    if(month===1&&dayOfMonth<=7)want.push('spring-festival');
    if(month===8&&dayOfMonth>=15&&dayOfMonth<=17)want.push('mid-autumn');
    const key=date.toISOString().slice(0,10),got=calendarAt(at(key)).festivals.filter(name=>name!=='national-day');
    if(got.length!==want.length || got.some((name,i)=>name!==want[i]))assert.fail(`${key}: ${got} != ${want}`);
  }
  for(const year of [1901,2026,2100])for(const id of ['spring-festival','mid-autumn']) {
    const preview=civilTime(previewInstant(id,at(`${year}-06-01`)));
    assert.deepEqual(calendarAt(at(preview.dateKey)).festivals.filter(name=>name===id),[id],`${year} ${id}`);
  }
  assert.deepEqual(calendarAt(at('2101-01-29')).festivals,[]);
  assert.throws(()=>previewInstant('spring-festival',at('2101-06-01')),RangeError);
});

test('previews choose the current farm year and preserve civil time for every scenario',()=>{
  const now=at('2026-10-14');
  for(const [id,date] of [['spring','2026-04-15'],['summer','2026-07-15'],['autumn','2026-10-15'],
    ['winter','2026-01-15'],['spring-festival','2026-02-17'],['mid-autumn','2026-09-25'],['national-day','2026-10-01']]) {
    const civil=civilTime(previewInstant(id,now));
    assert.equal(civil.dateKey,date,id);
    assert.deepEqual([civil.hour,civil.minute,civil.second],[12,34,56]);
  }
  assert.equal(previewInstant('auto',now),now);
  assert.equal(SCENE_SCENARIOS.length,8);
  assert.deepEqual(SCENE_SCENARIOS.map(s=>s.id),['auto','spring','summer','autumn','winter','mid-autumn','spring-festival','national-day']);
});

test('season and festival previews resolve site offsets rather than assuming UTC+8',()=>{
  const site={...DISPLAY_SITE,timeZone:'America/New_York'};
  const winter=Date.parse('2026-01-10T14:34:56Z');
  assert.equal(previewInstant('spring',winter,site),Date.parse('2026-04-15T13:34:56Z'));
  assert.equal(previewInstant('mid-autumn',winter,site),Date.parse('2026-09-25T13:34:56Z'));
  const summer=Date.parse('2026-07-10T13:34:56Z');
  assert.equal(previewInstant('winter',summer,site),Date.parse('2026-01-15T14:34:56Z'));
  assert.equal(previewInstant('spring-festival',summer,site),Date.parse('2026-02-17T14:34:56Z'));
  const kathmandu={...DISPLAY_SITE,timeZone:'Asia/Kathmandu'};
  assert.equal(previewInstant('summer',Date.parse('2026-01-10T03:49:56Z'),kathmandu),Date.parse('2026-07-15T03:49:56Z'));
  const newYear=Date.parse('2025-12-31T16:30:45Z');
  assert.equal(civilTime(previewInstant('winter',newYear)).dateKey,'2026-01-15');
});

test('host timezone cannot change farm festival dates or previews',()=>{
  const url=new URL('./login-calendar.js',import.meta.url).href;
  const code=`import {calendarAt,previewInstant} from ${JSON.stringify(url)}; console.log(JSON.stringify([calendarAt(Date.parse('2026-09-24T16:00:00Z')),calendarAt(Date.parse('2027-02-05T16:00:00Z')),previewInstant('spring-festival',Date.parse('2027-10-14T04:34:56Z'))]));`;
  const outputs=['UTC','America/Los_Angeles','Pacific/Auckland'].map(TZ=>execFileSync(process.execPath,['--input-type=module','-e',code],{env:{...process.env,TZ},encoding:'utf8'}));
  assert.equal(outputs[0],outputs[1]);assert.equal(outputs[1],outputs[2]);
});

test('a preview landing inside a DST gap advances to the next valid local time',()=>{
  const site={...DISPLAY_SITE,timeZone:'Australia/Sydney'};
  // October 1, 2023 jumps from 01:59:59 to 03:00:00; 02:30 cannot be preserved.
  const now=Date.parse('2023-06-01T02:30:00+10:00');
  assert.equal(previewInstant('national-day',now,site),Date.parse('2023-10-01T03:30:00+11:00'));
});
