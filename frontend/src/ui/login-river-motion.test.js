import test from 'node:test';
import assert from 'node:assert/strict';
import { boatPose, makeBoatMarks, waterMotion } from './login-river-motion.js';
import { narrowRiverX, riverBanks } from './login-scene-layout.js';

test('boat follows the channel downstream and exits before the return vessel appears',()=>{
  let last=boatPose(-61);
  for(let t=-60;t<266;t++) {
    const pose=boatPose(t),[left,right]=riverBanks(pose.v);
    assert.ok(pose.u>last.u && pose.v>last.v);
    assert.ok(pose.u-84*pose.scale/1448>narrowRiverX(left,pose.v));
    assert.ok(pose.u+90*pose.scale/1448<right);
    last=pose;
  }
  const end=boatPose(266);
  assert.ok(end.u-88*end.scale/1448>1,'the whole hull must leave before changing vessels');
  assert.equal(boatPose(800/3).kind,'cargo');
  assert.ok(boatPose(10).u-boatPose(0).u<.02,'the boat should glide slowly');
});

test('a distinct open skiff returns upstream, shrinking into the distance',()=>{
  const near=boatPose(300),far=boatPose(550);
  assert.equal(near.direction,-1);
  assert.ok(far.u<near.u && far.v<near.v && far.scale<near.scale);
  const outbound=makeBoatMarks(1448,1086,0,[]),inbound=makeBoatMarks(1448,1086,400,[]);
  assert.ok(outbound.some(p=>p.part==='canopy') && !outbound.some(p=>p.part==='cargo'));
  assert.ok(inbound.some(p=>p.part==='cargo') && !inbound.some(p=>p.part==='canopy'));
  assert.ok(boatPose(599.9).opacity<.01);
  assert.equal(boatPose(600).kind,'canopy');
});

test('hull is a rigid object while the boatman and oar articulate',()=>{
  const frames=[0,1.2].map(t=>makeBoatMarks(1448,1086,t,[]));
  const hull=frames.map(m=>m.filter(p=>p.part==='hull'));
  assert.equal(hull[0].length,hull[1].length);
  const distance=points=>Math.hypot(points.at(-1).x-points[0].x,points.at(-1).y-points[0].y);
  assert.ok(Math.abs(distance(hull[0])/boatPose(0).scale-distance(hull[1])/boatPose(1.2).scale)<1e-8);
  const oars=frames.map(m=>m.filter(p=>p.part==='oar'));
  const angle=points=>Math.atan2(points.at(-1).y-points[0].y,points.at(-1).x-points[0].x);
  assert.ok(Math.abs(angle(oars[0])-angle(oars[1]))>.2);
  assert.ok(frames.every(m=>m.some(p=>p.part==='boatman') && m.some(p=>p.part==='wake')));
});

test('flowing water remains between the narrowed banks and glints change over time',()=>{
  for(const v of [.5,.56,.63,.7,.78]) {
    const [left,right]=riverBanks(v);
    for(const fraction of [.01,.2,.5,.8,.99])for(const time of [0,1,3,5]) {
      const u=left+(right-left)*fraction,m=waterMotion(u,v,time,.08);
      const destV=v+m.y/1086,[a,b]=riverBanks(destV),destU=narrowRiverX(u,v)+m.x/1448;
      assert.ok(destU>=narrowRiverX(a,destV)-1e-9 && destU<=b+1e-9);
      assert.ok(m.y>=0);
    }
  }
  assert.equal(waterMotion(.1,.7,2,.01).glint,0);
  assert.notEqual(waterMotion(.8,.7,0,.01).glint,waterMotion(.8,.7,1,.01).glint);
});
