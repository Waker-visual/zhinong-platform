import test from 'node:test';
import assert from 'node:assert/strict';
import { makeFarmActivity, createFarmOperation } from './login-farm-activity.js';
import { daylightHours } from '../account/scheduled-theme.js';

const at=(hour,minute=0)=>new Date(2026,9,14,hour,minute);
const frame=(time,date=at(12),options)=>makeFarmActivity(890,960,time,date,options);

const hasWater=marks=>marks.some(m=>m.effect==='irrigation');
const hasMist=marks=>marks.some(m=>m.effect==='droneSpray');

test('sprinklers work throughout local daytime, with the same seasonal boundaries as the theme',()=>{
  const [start,end]=daylightHours(at(12));
  assert.equal(hasWater(frame(0,at(start-1,59))),false);
  for(const hour of [start,9,12,15,end-1])assert.equal(hasWater(frame(2,at(hour))),true);
  assert.equal(hasWater(frame(0,at(end))),false);
  assert.equal(hasWater(frame(0,at(0))),false);
});

test('watering has actual rest intervals, while the three drones stay present',()=>{
  assert.equal(hasWater(frame(0)),false);
  assert.equal(hasWater(frame(.3)),true);
  assert.equal(hasWater(frame(10)),false);
  assert.equal(hasWater(frame(15)),true);
  for(const time of [0,10,15,36,90]) {
    assert.equal(frame(time).filter(m=>m.char==='[##]').length,3);
    assert.ok(frame(time).every(m=>Number.isFinite(m.x)&&Number.isFinite(m.y)));
  }
});

test('drone spraying resumes only when every sprinkler is off, across multiple cycles',()=>{
  for(let time=0;time<108;time+=.5) {
    const marks=frame(time,at(15));
    assert.equal(hasWater(marks)&&hasMist(marks),false,`overlapping spray at ${time}s`);
    assert.equal(marks.filter(m=>m.char==='[##]').length,3);
  }
  assert.equal(hasMist(frame(8)),false);
  assert.equal(hasMist(frame(8.3)),true);
  assert.equal(hasMist(frame(14)),false);
});

test('sprinkler drops begin at the nozzle, build pressure and drain before handover',()=>{
  const drops=time=>frame(time).filter(m=>m.effect==='irrigation');
  const reach=marks=>Math.max(...marks.map(m=>Math.abs(m.x-890*.16)));
  const start=drops(.2),growing=drops(1),full=drops(3.5),tail=drops(7.7);
  assert.ok(start.length>0 && start.length<growing.length && growing.length<full.length);
  assert.ok(reach(start)<6,'new drops must originate near the nozzle');
  assert.ok(reach(growing)<reach(full)*.6);
  assert.ok(tail.length>0 && tail.length<full.length/3,'last drops must drain gradually');
  assert.equal(drops(8).length,0);
  assert.ok([...start,...full,...tail].every(m=>m.opacity>=0 && m.opacity<=1));
});

test('drone mist also grows from its nozzles and clears before ground watering',()=>{
  const mist=time=>frame(time).filter(m=>m.effect==='droneSpray');
  assert.equal(mist(8).length,0);
  assert.ok(mist(8.2).length>0 && mist(8.2).length<mist(9).length);
  assert.ok(mist(9).length<mist(11).length);
  assert.ok(mist(13.8).length>0 && mist(13.8).length<mist(11).length/3);
  assert.equal(mist(14).length,0);
});

test('reduced motion is static without spray; night retains aircraft without spraying',()=>{
  assert.deepEqual(frame(0,at(12),{reduced:true}),frame(40,at(12),{reduced:true}));
  const night=frame(5,at(23),{night:true});
  assert.equal(night.filter(m=>m.char==='[##]').length,3);
  assert.equal(hasWater(night),false);
  assert.equal(hasMist(night),false);
  assert.notDeepEqual(frame(0),frame(5));
});

test('patrol beacon alternates red and orange with dark intervals',()=>{
  const beacon=time=>frame(time).filter(m=>m.tone.startsWith('beacon')).map(m=>m.tone);
  assert.deepEqual(beacon(0),['beaconRed']);
  assert.deepEqual(beacon(.8),[]);
  assert.deepEqual(beacon(1.3),['beaconOrange']);
  assert.deepEqual(beacon(2),[]);
  assert.deepEqual(beacon(2.4),['beaconRed']);
});

test('harvester travels back and forth on a fixed level while the reel turns',()=>{
  const chassis=time=>{
    const pixels=frame(time).filter(m=>m.equipment==='harvester');
    assert.ok(pixels.length>200);
    assert.ok(pixels.every(m=>m.pixel>0 && !m.halo));
    return {x:(Math.min(...pixels.map(m=>m.x))+Math.max(...pixels.map(m=>m.x)))/2,
      y:Math.min(...pixels.map(m=>m.y))};
  };
  assert.equal(chassis(0).y,chassis(16).y);
  assert.equal(chassis(16).y,chassis(48).y);
  assert.ok(chassis(16).x>chassis(0).x);
  assert.ok(chassis(48).x<chassis(0).x);
  assert.ok(chassis(16).x<890*.15,'harvester must stay in the cleared left field');
  const reel=time=>frame(time).filter(m=>m.part==='reel');
  assert.ok(reel(0).length>30,'the cutter must have visible radial bars, not isolated dots');
  assert.notDeepEqual(reel(0),reel(.3));
});

test('grain rises above the cab and falls into the rear hopper, mirroring with travel',()=>{
  const grain=time=>frame(time).filter(m=>m.effect==='harvestGrain');
  const a=grain(0),b=grain(25);
  assert.equal(a.length,16);
  assert.ok(Math.min(...a.map(m=>m.y))<a[0].y-5);
  assert.ok(a.at(-1).x<a[0].x,'grain must return towards the rear hopper');
  assert.ok(b.at(-1).x>b[0].x,'grain must mirror with the machine');
  assert.equal(frame(0,at(12),{reduced:true}).filter(m=>m.effect==='harvestGrain').length,0);
});

test('solar work windows drain existing spray after sunset, without spawning new drops',()=>{
  const operations=createFarmOperation();
  operations.advance(0,true);
  operations.advance(3,true);
  const before=frame(3,at(23),{operation:operations.read()});
  assert.ok(hasWater(before),'solar state overrides old fixed clock boundaries');
  operations.advance(3.01,false);
  const tail=frame(3.2,at(23),{operation:operations.read()});
  assert.ok(hasWater(tail));
  operations.advance(6,false);
  const after=frame(6,at(23),{operation:operations.read()});
  assert.equal(hasWater(after)||hasMist(after),false);
  for(let t=6;t<45;t+=.13){
    operations.advance(t,t>=7&&t<13 || t>=18&&t<24 || t>=29&&t<35);
    const marks=frame(t,at(23),{operation:operations.read()});
    assert.equal(hasWater(marks)&&hasMist(marks),false,`overlap at ${t}`);
  }
});

test('night parks the harvester in place, while grain tails drain and morning resumes',()=>{
  const operations=createFarmOperation(); operations.advance(0,true); operations.advance(3,true);
  operations.advance(3,false);
  const machine=t=>frame(t,at(12),{operation:operations.read()}).filter(m=>m.equipment==='harvester');
  const stopped=machine(3);
  operations.advance(8,false);
  assert.deepEqual(machine(8),stopped);
  assert.ok(!frame(8,at(12),{operation:operations.read()}).some(m=>m.effect==='harvestGrain'));
  operations.advance(8,true);operations.advance(9,true);
  assert.notDeepEqual(machine(9),stopped);
});
