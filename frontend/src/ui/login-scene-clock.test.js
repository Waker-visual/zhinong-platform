import test from 'node:test';
import assert from 'node:assert/strict';
import { createSceneClock, TOUR_DURATION_MS } from './login-scene-clock.js';

function fixture() {
  const time={wall:Date.parse('2026-10-14T04:00:00Z'),mono:0};
  return {time,clock:createSceneClock({now:()=>time.wall,monotonic:()=>time.mono})};
}

test('tour ends at 12 seconds and real mode always follows wall time',()=>{
  const {time,clock}=fixture();
  assert.equal(clock.read().atMs,time.wall);
  clock.start();time.mono=11_999;
  assert.equal(clock.read().mode,'tour');
  assert.ok(clock.read().progress>.99);
  time.wall+=90_000;time.mono=12_000;
  assert.equal(clock.read().mode,'real');
  assert.equal(clock.read().atMs,time.wall);
  assert.equal(TOUR_DURATION_MS,12_000);
});

test('hiding at 11.9 seconds pauses exactly once and preserves the last 100 ms',()=>{
  const {time,clock}=fixture();clock.start();
  time.mono=11_900;clock.setHidden(true);
  const paused=clock.read();
  time.mono=41_900;clock.setHidden(true);
  assert.deepEqual(clock.read(),paused);
  clock.setHidden(false);clock.setHidden(false);
  time.mono=41_950;assert.equal(clock.read().mode,'tour');
  time.mono=42_000;assert.equal(clock.read().mode,'real');
});

test('weather cycles per accepted start, repeated start is inert and return can restart immediately',()=>{
  const {time,clock}=fixture();
  for(const expected of ['clear','cloudy','overcast','clear']) {
    clock.start();assert.equal(clock.read().weather,expected);
    time.mono+=100;const before=clock.read();
    clock.start();assert.deepEqual(clock.read(),before);
    clock.returnToNow();assert.equal(clock.read().mode,'real');
    assert.equal(clock.read().atMs,time.wall);
  }
});

test('wall-clock adjustments cannot change an active tour, including a hidden start',()=>{
  const {time,clock}=fixture();
  clock.setHidden(true);clock.start();const start=clock.read();
  time.wall-=86_400_000;time.mono=30_000;
  assert.deepEqual(clock.read(),start);
  clock.setHidden(false);time.mono=36_000;
  const mid=clock.read();assert.equal(mid.progress,.5);
  time.wall+=3*86_400_000;
  assert.deepEqual(clock.read(),mid);
  clock.returnToNow();assert.equal(clock.read().atMs,time.wall);
});

test('frame rate does not alter endpoints or the next weather',()=>{
  for(const fps of [12,30,60]) {
    const {time,clock}=fixture();clock.start();
    for(let i=0;i<fps*12;i++) {
      time.mono=i*1000/fps;assert.equal(clock.read().mode,'tour');
    }
    time.mono=12_000;assert.equal(clock.read().mode,'real');
    clock.start();assert.equal(clock.read().weather,'cloudy');
  }
});

test('season preview shares the scene clock and returns to actual time without changing it',()=>{
  const {time,clock}=fixture();
  clock.setScenario('spring');
  assert.equal(clock.read().civil.dateKey,'2026-04-15');
  assert.equal(clock.read().civil.hour,12);
  const previewStart=clock.read().atMs;
  clock.start();
  assert.equal(clock.read().atMs,previewStart);
  time.mono=12_000;
  assert.equal(clock.read().mode,'real');
  assert.equal(clock.read().scenario,'spring');
  assert.equal(clock.read().civil.dateKey,'2026-04-15');
  clock.returnToNow();
  assert.equal(clock.read().scenario,'auto');
  assert.equal(clock.read().atMs,time.wall);
});

test('changing a scenario cancels an old tour and cannot consume the next weather',()=>{
  const {time,clock}=fixture();
  clock.start();time.mono=3000;
  clock.setScenario('mid-autumn');
  assert.equal(clock.read().mode,'real');
  assert.equal(clock.read().civil.dateKey,'2026-09-25');
  clock.start();assert.equal(clock.read().weather,'cloudy');
  clock.setScenario('auto');assert.equal(clock.read().atMs,time.wall);
});

test('year tour lasts sixty visible seconds and clears preview only when it finishes',()=>{
  const {time,clock}=fixture();clock.setScenario('spring');
  const from=clock.read().atMs;clock.start('year');
  assert.equal(clock.read().atMs,from);
  assert.equal(clock.read().tourKind,'year');assert.equal(clock.read().durationMs,60_000);
  assert.equal(clock.read().weather,'clear');assert.ok(clock.read().speed>1);
  time.mono=59_900;clock.setHidden(true);const paused=clock.read();
  time.mono=89_900;assert.deepEqual(clock.read(),paused);
  clock.setHidden(false);time.mono=89_999;assert.equal(clock.read().mode,'tour');
  time.mono=90_000;const done=clock.read();
  assert.equal(done.mode,'real');assert.equal(done.tourKind,null);
  assert.equal(done.scenario,'auto');assert.equal(done.atMs,time.wall);
  assert.equal(done.speed,1);assert.equal(done.durationMs,0);
});

test('switching day and year starts at the visible instant; duplicate starts stay inert',()=>{
  const {time,clock}=fixture();clock.start();time.mono=3000;
  const dayAt=clock.read().atMs;clock.start('year');
  assert.equal(clock.read().atMs,dayAt);
  time.mono=4000;const year=clock.read();clock.start('year');assert.deepEqual(clock.read(),year);
  clock.start('day');assert.equal(clock.read().atMs,year.atMs);
  assert.equal(clock.read().weather,'cloudy');assert.equal(clock.read().durationMs,12_000);
  time.mono=16_000;assert.equal(clock.read().mode,'real');
});

test('year weather is stable, does not consume day weather, and ignores wall adjustments',()=>{
  const {time,clock}=fixture();clock.start('year');time.mono=30_000;
  const first=clock.read();time.wall+=80*86_400_000;
  assert.deepEqual(clock.read(),first);
  for(const millis of [31_000,45_000,59_000]){time.mono=millis;assert.equal(clock.read().weather,'clear');}
  clock.returnToNow();clock.start();assert.equal(clock.read().weather,'clear');
});

test('year completion is independent of coarse or reduced update rates',()=>{
  for(const interval of [1000,1000/12,1000/60]) {
    const {time,clock}=fixture();clock.start('year');
    for(let elapsed=0;elapsed<60_000;elapsed+=interval) {
      time.mono=elapsed;assert.equal(clock.read().mode,'tour');
    }
    time.mono=60_000;assert.equal(clock.read().mode,'real');
  }
});
