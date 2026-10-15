import test from 'node:test';
import assert from 'node:assert/strict';
import { makeFestivalMarks, festivalTones } from './login-festivals.js';
import { narrowRiverX, riverBanks } from './login-scene-layout.js';
import { daylightAt, DISPLAY_SITE } from './login-daylight.js';

const night={night:1,daylight:0};
const all=['mid-autumn','spring-festival','national-day'];
const make=(festivals,extras={})=>makeFestivalMarks(1448,1086,{festivals,light:night,motionSeconds:3,reduced:false,...extras});

test('no holiday has no marks, and all festive marks are finite small ASCII bounded to the scene',()=>{
  assert.deepEqual(make([]),[]);
  assert.ok(festivalTones.length>0);
  for(const [width,height] of [[1448,1086],[900,900],[556,900],[1600,600]]) {
    const marks=makeFestivalMarks(width,height,{festivals:all,light:night,motionSeconds:3,reduced:false});
    assert.ok(marks.length>0 && marks.length<3500);
    for(const mark of marks) {
      assert.ok(Number.isFinite(mark.x) && Number.isFinite(mark.y));
      assert.ok(mark.x>=0 && mark.x+Math.ceil(mark.pixel)<=width && mark.y>=0 && mark.y+Math.ceil(mark.pixel)<=height);
      assert.match(mark.char,/^[\x20-\x7e]$/);
      assert.ok(mark.size>0 && mark.size<=3 && mark.pixel>0 && mark.pixel<=3);
      assert.ok(festivalTones.includes(mark.tone));
      assert.ok(!(mark.x<320 && mark.y<220),'decorations must leave the clock clear');
    }
  }
});

test('overlapping lunar celebrations share lanterns without duplicating them',()=>{
  const lanterns=marks=>marks.filter(mark=>mark.part==='festival-lantern');
  const mid=lanterns(make(['mid-autumn'])),spring=lanterns(make(['spring-festival']));
  const both=lanterns(make(['mid-autumn','spring-festival']));
  for(const anchor of new Set([...mid,...spring].map(mark=>mark.anchor))) {
    const count=marks=>marks.filter(mark=>mark.anchor===anchor).length;
    assert.equal(count(both),Math.max(count(mid),count(spring)));
  }
  assert.deepEqual(make(all),make([...all,...all]));
  const parts=new Set(make(all).map(mark=>mark.part));
  for(const part of ['festival-table','festival-person','festival-cup','festival-couplet','festival-flag','festival-star'])assert.ok(parts.has(part));
});

test('national flags have five star groups and the reference transform preserves ground anchors',()=>{
  const marks=make(['national-day'],{reduced:true});
  const stars=marks.filter(mark=>mark.part==='festival-star');
  assert.equal(new Set(stars.map(mark=>mark.flag)).size,2);
  for(const flag of new Set(stars.map(mark=>mark.flag)))assert.equal(new Set(stars.filter(mark=>mark.flag===flag).map(mark=>mark.star)).size,5);
  const pole=marks.find(mark=>mark.part==='festival-pole');
  assert.ok(Math.abs(pole.x-narrowRiverX(997/1448,564/1086)*1448)<1e-9);
  const half=makeFestivalMarks(724,900,{festivals:['national-day'],light:night,motionSeconds:3,reduced:true});
  const halfPole=half.find(mark=>mark.part==='festival-pole');
  assert.ok(Math.abs(halfPole.x-pole.x*.5)<1e-9);
  assert.ok(Math.abs(halfPole.y-(357+pole.y*.5))<1e-9);
});

test('reduced motion stays static and actual winter light preserves celebration props',()=>{
  assert.deepEqual(make(all,{reduced:true,motionSeconds:0}),make(all,{reduced:true,motionSeconds:90}));
  for(const hour of [4,12]) {
    const light=daylightAt(Date.parse(`2026-02-17T${String(hour).padStart(2,'0')}:00:00Z`),DISPLAY_SITE,'overcast');
    const marks=make(['spring-festival'],{light});
    assert.ok(marks.some(mark=>mark.part==='festival-lantern'));
    assert.ok(marks.some(mark=>mark.part==='festival-couplet'));
    assert.ok(marks.every(mark=>!(mark.x<320 && mark.y<220)));
  }
});

test('fireworks are sparse, slow, deterministic and appear only on Spring Festival nights',()=>{
  const fireworks=marks=>marks.filter(mark=>mark.part==='festival-firework');
  assert.equal(fireworks(make(['mid-autumn','national-day'])).length,0);
  assert.equal(fireworks(make(['spring-festival'],{light:{night:0,daylight:1}})).length,0);
  assert.equal(fireworks(make(['spring-festival'],{reduced:true})).length,0);
  let lit=0;
  for(let i=0;i<180;i++) {
    const marks=make(['spring-festival'],{motionSeconds:i/10});
    const burst=fireworks(marks);
    assert.ok(burst.length<=180);
    if(burst.length)lit++;
    assert.deepEqual(marks,make(['spring-festival'],{motionSeconds:i/10}));
  }
  assert.ok(lit>20 && lit<100,'brief bursts are separated by long quiet gaps');
  const a=fireworks(make(['spring-festival'],{motionSeconds:3}));
  const b=fireworks(make(['spring-festival'],{motionSeconds:3.05}));
  assert.ok(a.length>0 && a.length===b.length);
  a.forEach((mark,index)=>assert.ok(Math.hypot(mark.x-b[index].x,mark.y-b[index].y)<3));
});

test('firework opacity crosses twilight continuously and fades to zero before removal',()=>{
  const fireworks=night=>make(['spring-festival'],{light:{night},motionSeconds:3})
    .filter(mark=>mark.part==='festival-firework');
  const before=fireworks(.45-.0001),after=fireworks(.45+.0001);
  assert.ok(before.length>0 && before.length===after.length,'the former night threshold cannot toggle a whole burst');
  before.forEach((mark,index)=>{
    assert.equal(mark.x,after[index].x);
    assert.equal(mark.y,after[index].y);
    assert.ok(Math.abs(mark.opacity-after[index].opacity)<.001);
  });
  assert.equal(fireworks(.25).length,0);
  const firstVisible=fireworks(.2501);
  assert.ok(firstVisible.length>0 && firstVisible.every(mark=>mark.opacity<1e-6),'newly retained marks must begin essentially transparent');
  const levels=[.2501,.3,.45,.6,.8,1].map(level=>fireworks(level).reduce((sum,mark)=>sum+mark.opacity,0));
  assert.ok(levels.every((level,index)=>index===0 || level>=levels[index-1]));
  assert.equal(levels.at(-1),levels.at(-2));
});

test('festival entrance time shows grouped fireworks promptly without speeding unrelated decorations',()=>{
  const fireworks=marks=>marks.filter(mark=>mark.part==='festival-firework');
  const entered=make(all,{motionSeconds:11,festivalSeconds:.1});
  assert.ok(fireworks(entered).some(mark=>mark.opacity>.1));
  const opened=fireworks(make(all,{motionSeconds:11,festivalSeconds:1}));
  assert.ok(opened.some(mark=>mark.char==='+'));
  assert.equal(new Set(opened.filter(mark=>mark.char==='+').map(mark=>mark.burst)).size,3,'all three centers must bloom within one real second, not remain launch trails');
  for(const [burst,[cx,cy]] of [[930,175],[1110,145],[1240,205]].entries()) {
    const radius=Math.max(...opened.filter(mark=>mark.burst===burst).map(mark=>Math.hypot(mark.x-cx,mark.y-cy)));
    assert.ok(radius>28,'the first second must show expanded flowers, not tiny opening rings');
  }
  const later=make(all,{motionSeconds:11,festivalSeconds:2});
  assert.deepEqual(entered.filter(mark=>mark.part!=='festival-firework'),later.filter(mark=>mark.part!=='festival-firework'));
  assert.deepEqual(make(all,{reduced:true,festivalSeconds:0}),make(all,{reduced:true,festivalSeconds:5}));
});

test('festival figures and lights occupy several village locations and river reflections follow action time',()=>{
  const spring=make(['spring-festival']),mid=make(['mid-autumn']),national=make(['national-day']);
  assert.ok(new Set(spring.filter(mark=>mark.part==='festival-lantern').map(mark=>mark.anchor)).size>=8);
  assert.ok(spring.some(mark=>mark.part==='festival-gate-light'));
  assert.ok(national.some(mark=>mark.part==='festival-eave-light'));
  const reflections=marks=>marks.filter(mark=>mark.part==='festival-reflection');
  assert.ok(reflections(mid).length>40);
  for(const mark of reflections(mid)) {
    const [left,right]=riverBanks(mark.y/1086);
    assert.ok(mark.x>narrowRiverX(left,mark.y/1086)*1448 && mark.x<right*1448,'lamp reflections stay in the narrowed river');
  }
  assert.notDeepEqual(reflections(mid),reflections(make(['mid-autumn'],{motionSeconds:4})));
  assert.deepEqual(reflections(make(['mid-autumn'],{reduced:true,motionSeconds:0})),reflections(make(['mid-autumn'],{reduced:true,motionSeconds:20})));
  assert.ok(new Set(mid.filter(mark=>mark.part==='festival-person').map(mark=>mark.person)).size>=4);
});
