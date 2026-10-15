import test from 'node:test';
import assert from 'node:assert/strict';
import { yearVisualState } from './login-year-visuals.js';

const day={sun:{altitude:65,azimuth:180},moon:{altitude:-30,azimuth:30},
  daylight:1,surfaceDaylight:1,night:0,twilight:0,warmLight:0,evening:0,moonlight:0,period:'day',extra:'retained'};
const night={...day,sun:{altitude:-40,azimuth:0},moon:{altitude:50,azimuth:160},
  daylight:0,surfaceDaylight:0,night:1,twilight:.2,warmLight:.1,moonlight:.7,period:'night'};
const state=(daysPerSecond=0,civil={year:2026,month:4,day:15,hour:12})=>({mode:'tour',tourKind:'year',speed:daysPerSecond*86400,civil});

test('fusion preserves slow and ordinary daylight and retains astronomy objects',()=>{
  for(const speed of [0,.2,.4]) {
    const result=yearVisualState(state(speed),night);
    assert.equal(result.fusion,0);
    assert.deepEqual(result.light,{...night,fusion:0});
    assert.equal(result.light.sun,night.sun);
    assert.equal(result.light.moon,night.moon);
  }
  assert.equal(yearVisualState({...state(5),tourKind:'day'},night).fusion,0);
  assert.equal(yearVisualState({...state(5),mode:'real'},night).fusion,0);
});

test('fusion is continuous at both speed thresholds and cannot flash at full acceleration',()=>{
  const low=yearVisualState(state(.4),night),lowNext=yearVisualState(state(.40001),night);
  const high=yearVisualState(state(1.3),night),highBefore=yearVisualState(state(1.29999),night);
  assert.ok(Math.abs(low.fusion-lowNext.fusion)<1e-8);
  assert.ok(Math.abs(high.fusion-highBefore.fusion)<1e-8);
  let previous=0;
  for(let speed=.4;speed<=1.31;speed+=.01) {
    const result=yearVisualState(state(speed),night);
    assert.ok(result.fusion>=previous && result.fusion<=1);
    previous=result.fusion;
  }
  const keys=['daylight','surfaceDaylight','night','twilight','warmLight','moonlight','evening','period'];
  for(const speed of [1.3,2,8,40]) {
    const a=yearVisualState(state(speed),day),b=yearVisualState(state(speed),night);
    assert.equal(a.fusion,1);
    for(const key of keys)assert.equal(a.light[key],b.light[key]);
    assert.equal(a.light.daylight,1);
    assert.equal(a.light.extra,'retained');
  }
});

test('season weights change continuously across boundaries, leap day and New Year',()=>{
  const at=date=>yearVisualState(state(2,{year:date.getUTCFullYear(),month:date.getUTCMonth()+1,
    day:date.getUTCDate(),hour:date.getUTCHours()}),day).seasonBlend;
  const weights=blend=>{
    const result={spring:0,summer:0,autumn:0,winter:0};
    result[blend.from]+=1-blend.amount;result[blend.to]+=blend.amount;return result;
  };
  for(const year of [2026,2028])for(const month of [0,2,5,8,11]) {
    const center=Date.UTC(year,month,1),before=weights(at(new Date(center-3600000))),after=weights(at(new Date(center+3600000)));
    for(const season of Object.keys(before))assert.ok(Math.abs(before[season]-after[season])<.004);
  }
  assert.deepEqual(at(new Date('2026-01-01T00:00:00Z')),{from:'winter',to:'winter',amount:0});
  assert.deepEqual(at(new Date('2026-06-01T00:00:00Z')),{from:'spring',to:'summer',amount:.5});
  assert.deepEqual(at(new Date('2026-08-01T00:00:00Z')),{from:'summer',to:'summer',amount:0});
});
