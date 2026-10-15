import test from 'node:test';
import assert from 'node:assert/strict';
import { civilTime, DISPLAY_SITE } from './login-daylight.js';
import { makeYearTour, yearTourInstant, yearTourSpeed } from './login-year-tour.js';

const day=86_400_000;
const start=Date.parse('2026-01-01T12:00:00+08:00');

test('year tour spans the same local date next year, with leap-day clamping and DST offsets',()=>{
  for(const [instant,zone,expected] of [
    ['2026-01-01T12:00:00+08:00','Asia/Shanghai','2027-01-01T04:00:00Z'],
    ['2024-02-29T23:12:34+08:00','Asia/Shanghai','2025-02-28T15:12:34Z'],
    ['2025-03-08T12:00:00-05:00','America/New_York','2026-03-08T16:00:00Z']]) {
    const at=Date.parse(instant),tour=makeYearTour(at,{...DISPLAY_SITE,timeZone:zone});
    assert.equal(yearTourInstant(tour.table,0),at);
    assert.equal(tour.endMs,Date.parse(expected));
    assert.equal(yearTourInstant(tour.table,1),tour.endMs);
    let previous=at;
    for(let i=1;i<=720;i++) {
      const value=yearTourInstant(tour.table,i/720);
      assert.ok(value>previous);previous=value;
      assert.ok(Number.isFinite(yearTourSpeed(tour.table,i/720)));
    }
  }
});

test('first eight seconds cover two days, then speed rises and finally falls continuously',()=>{
  // April's opening and closing ramps contain no holiday stop.
  const from=Date.parse('2026-04-15T12:00:00+08:00'),{table}=makeYearTour(from);
  assert.ok(Math.abs(yearTourInstant(table,8/60)-from-2*day)<2);
  const speed=seconds=>yearTourSpeed(table,seconds/60);
  assert.ok(speed(4)<=.4*day/1000);
  assert.ok(speed(16)>=1.3*day/1000);
  assert.ok(speed(12)>speed(8)*4);
  assert.ok(speed(16)>speed(12));
  assert.ok(speed(52)>speed(56));
  assert.ok(speed(56)>speed(59.99)*4);
  for(const second of [8,16,52])assert.ok(Math.abs(speed(second+.001)-speed(second-.001))/speed(second)<.001);
});

test('each festival slows one complete civil day to about three seconds with smooth shoulders',()=>{
  const {table,festivalWindows}=makeYearTour(start);
  const progressAt=at=>{
    let low=0,high=1;
    for(let i=0;i<45;i++){const mid=(low+high)/2;if(yearTourInstant(table,mid)<at)low=mid;else high=mid;}
    return (low+high)/2;
  };
  assert.equal(festivalWindows.length,3);
  for(const [name,date] of [['spring-festival','2026-02-17'],['mid-autumn','2026-09-25'],['national-day','2026-10-01']]) {
    const window=festivalWindows.find(item=>item.festivals.includes(name));
    assert.equal(civilTime(window.startMs).dateKey,date);
    assert.equal(civilTime(window.startMs).hour,6);
    assert.equal(window.endMs-window.startMs,day);
    const first=progressAt(window.startMs),last=progressAt(window.endMs);
    assert.ok(Math.abs((last-first)*60-3)<.04,`${name} dwell ${(last-first)*60}`);
    assert.ok(yearTourSpeed(table,(first+last)/2)<=.4*day/1000);
    for(const boundary of [window.startMs,window.endMs]) {
      const before=yearTourSpeed(table,progressAt(boundary-1000));
      const after=yearTourSpeed(table,progressAt(boundary+1000));
      assert.ok(Math.abs(before-after)/before<.01);
    }
  }
});

test('coincident festival days share a single stop and do not duplicate dwelling time',()=>{
  const tour=makeYearTour(Date.parse('2020-01-01T04:00:00Z'));
  const october=tour.festivalWindows.filter(window=>civilTime(window.startMs).dateKey==='2020-10-01');
  assert.equal(october.length,1);
  assert.deepEqual(october[0].festivals,['mid-autumn','national-day']);
});

test('a 2026 year tour stops on official 2027 festival dates, including February 6',()=>{
  const {festivalWindows,festivalRanges}=makeYearTour(Date.parse('2026-10-14T04:00:00Z'));
  const spring=festivalWindows.find(window=>window.festivals.includes('spring-festival'));
  const autumn=festivalWindows.find(window=>window.festivals.includes('mid-autumn'));
  assert.equal(civilTime(spring.startMs).dateKey,'2027-02-06');
  assert.equal(civilTime(spring.endMs).dateKey,'2027-02-07');
  assert.equal(civilTime(autumn.startMs).dateKey,'2027-09-15');
  const range=festivalRanges.find(window=>window.festival==='spring-festival');
  assert.equal(civilTime(range.endMs).dateKey,'2027-02-13');
});

test('starting inside a festival preserves a strictly increasing complete year at fixed endpoints',()=>{
  for(const iso of ['2026-02-17T06:00:00+08:00','2026-09-25T23:59:59+08:00','2026-10-01T06:00:00+08:00']) {
    const at=Date.parse(iso),{table,endMs}=makeYearTour(at);
    assert.equal(table[0].instantMs,at);assert.equal(table.at(-1).instantMs,endMs);
    for(let i=1;i<table.length;i++)assert.ok(table[i].instantMs>table[i-1].instantMs);
    assert.equal(table.at(-1).progress,1);
  }
});
