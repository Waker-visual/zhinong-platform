import test from 'node:test';
import assert from 'node:assert/strict';
import { DISPLAY_SITE, civilTime, daylightAt, weatherForDate, makeDayTour, tourInstant } from './login-daylight.js';

test('civil time uses the farm zone across midnight and both DST transitions',()=>{
  const zone='America/New_York';
  for(const [instant,hour] of [['2026-03-08T06:59:00Z',1],['2026-03-08T07:00:00Z',3],
    ['2026-11-01T05:30:00Z',1],['2026-11-01T06:30:00Z',1]]) {
    assert.equal(civilTime(Date.parse(instant),zone).hour,hour);
  }
  assert.deepEqual(civilTime(Date.parse('2026-10-13T16:00:00Z'),DISPLAY_SITE.timeZone),
    {dateKey:'2026-10-14',year:2026,month:10,day:14,hour:0,minute:0,second:0});
});

test('tour advances exactly 24 actual hours, monotonically, including DST days',()=>{
  for(const zone of [DISPLAY_SITE.timeZone,'America/New_York'])for(const hour of [0,5,12,18,23]) {
    const start=Date.UTC(2026,2,8,hour),table=makeDayTour(start,{...DISPLAY_SITE,timeZone:zone});
    assert.equal(tourInstant(table,0),start);
    assert.equal(tourInstant(table,1),start+86_400_000);
    let previous=start;
    for(let i=1;i<=240;i++) {
      const at=tourInstant(table,i/240);
      assert.ok(at>previous);previous=at;
    }
  }
});

test('weighted tour gives each horizon crossing more viewing time than high noon',()=>{
  const table=makeDayTour(Date.parse('2026-06-20T16:00:00Z'));
  let horizon=0,noon=0;
  for(let i=1;i<table.length;i++) {
    const altitude=daylightAt(table[i].instantMs).sun.altitude;
    const duration=table[i].progress-table[i-1].progress;
    if(Math.abs(altitude)<1)horizon=Math.max(horizon,duration);
    if(altitude>60)noon=Math.max(noon,duration);
  }
  assert.ok(horizon>noon*3);
});

test('seasonal daylight follows solar altitude and intensities remain finite',()=>{
  for(const [date,min,max] of [['2026-06-21',15,18],['2026-12-21',6,9]]) {
    const start=Date.parse(`${date}T00:00:00+08:00`);
    let hours=0;
    for(let i=0;i<144;i++) {
      const light=daylightAt(start+i*600_000);
      if(light.sun.altitude>-.833)hours+=1/6;
      for(const key of ['daylight','twilight','warmLight','evening','night','moonlight'])
        assert.ok(Number.isFinite(light[key])&&light[key]>=0&&light[key]<=1,key);
      for(const body of [light.sun,light.moon])for(const value of Object.values(body))assert.ok(Number.isFinite(value));
    }
    assert.ok(hours>min&&hours<max,`${date}: ${hours}`);
  }
  const midnight=daylightAt(Date.parse('2026-06-21T00:00:00+08:00'));
  const noon=daylightAt(Date.parse('2026-06-21T12:00:00+08:00'));
  assert.ok(midnight.sun.altitude<0);
  assert.ok(noon.sun.altitude>60);
  assert.ok(Math.abs(noon.moon.altitude+noon.sun.altitude)>10);
  assert.equal(noon.period,'day');
  assert.equal(midnight.period,'night');
});

test('dawn and dusk distinguish rising and setting sun; weather stays stable per local date',()=>{
  const start=Date.parse('2026-10-14T00:00:00+08:00'),periods=new Set();
  const weather=weatherForDate(start);
  for(let i=0;i<144;i++) {
    const at=start+i*600_000;
    periods.add(daylightAt(at).period);
    assert.equal(weatherForDate(at),weather);
  }
  assert.deepEqual([...periods].sort(),['dawn','day','dusk','night']);
  assert.ok(['clear','cloudy','overcast'].includes(weather));
  const noon=start+12*3_600_000;
  assert.ok(daylightAt(noon,DISPLAY_SITE,'clear').daylight>daylightAt(noon,DISPLAY_SITE,'overcast').daylight);
});
