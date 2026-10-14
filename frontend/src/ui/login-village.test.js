import test from 'node:test';
import assert from 'node:assert/strict';
import { createVillage } from './login-village.js';
import { civilTime, daylightAt, DISPLAY_SITE } from './login-daylight.js';

const evening={ evening:1,night:.7,daylight:.1,moonlight:.2,warmLight:1 };
const night={ evening:0,night:1,daylight:0,moonlight:.2,warmLight:0 };
const smoke=village=>village.marks(1448,1086).filter(m=>m.part==='smoke');

test('evening smoke stays anchored to three roofs and survives the end of a short evening',()=>{
  const village=createVillage();
  for(let i=0;i<=8;i++)village.advance({motionSeconds:i/10,light:evening,reduced:false});
  const before=smoke(village);
  assert.ok(before.length>0);
  for(const [x,y] of [[365,411],[1100,420],[1238,451]]) {
    assert.ok(before.some(m=>Math.abs(m.x-x)<15 && m.y<=y && m.y>y-25));
  }
  village.advance({motionSeconds:.9,light:night,reduced:false});
  assert.ok(smoke(village).length>=before.length,'sunset stops births without removing live particles');
  village.advance({motionSeconds:5,light:night,reduced:false});
  assert.equal(smoke(village).length,0);
});

test('smoke is deterministic and follows real motion time independently of day phase',()=>{
  const a=createVillage(),b=createVillage();
  for(let i=0;i<=120;i++) {
    const args={motionSeconds:i/10,light:i<20 ? evening : night,reduced:false};
    a.advance(args);b.advance(args);
    assert.deepEqual(a.marks(1448,1086),b.marks(1448,1086));
  }
  assert.equal(smoke(a).length,0);
});

test('roofs and windows use the bottom aligned reference transform',()=>{
  const village=createVillage();
  village.advance({motionSeconds:0,light:evening,reduced:false});
  village.advance({motionSeconds:1,light:evening,reduced:false});
  const full=village.marks(1448,1086),half=village.marks(724,900);
  assert.equal(full.length,half.length);
  full.forEach((m,i)=>{
    assert.ok(Math.abs(half[i].x-m.x*.5)<1e-9);
    assert.ok(Math.abs(half[i].y-(900-543+m.y*.5))<1e-9);
  });
});

test('deep night leaves fewer illuminated windows and reduced motion emits no smoke',()=>{
  const village=createVillage();
  village.advance({motionSeconds:0,light:evening,reduced:true});
  const dusk=village.marks(1448,1086).filter(m=>m.part==='window');
  village.advance({motionSeconds:1,light:night,reduced:true});
  const late=village.marks(1448,1086).filter(m=>m.part==='window');
  assert.ok(dusk.length>late.length && late.length>0);
  assert.equal(smoke(village).length,0);
  village.advance({motionSeconds:2,light:{evening:0,night:0,daylight:1},reduced:false});
  assert.equal(village.marks(1448,1086).length,0);
});

test('actual dusk lights all intended houses in every weather, then local late night reduces them',()=>{
  const start=Date.parse('2026-10-14T04:00:00Z');
  for(const weather of ['clear','cloudy','overcast']) {
    const village=createVillage(),seen=new Set();
    for(let minute=0;minute<=360;minute++) {
      const at=start+minute*60_000,light=daylightAt(at,DISPLAY_SITE,weather);
      village.advance({motionSeconds:minute/30,light,reduced:true});
      for(const mark of village.marks(1448,1086).filter(m=>m.part==='window' && m.opacity>.01)) {
        for(const x of [351,615,1082,1115,1200,1266])if(Math.abs(mark.x-x)<12)seen.add(x);
      }
    }
    assert.equal(seen.size,6,`${weather}: every configured window must actually light at dusk`);
    const counts=[];
    for(const hour of [20,22,22.5,23,23.5,24]) {
      const at=Date.parse('2026-10-14T12:00:00Z')+(hour-20)*3_600_000;
      const civil=civilTime(at,DISPLAY_SITE.timeZone);
      const light={...daylightAt(at,DISPLAY_SITE,weather),hour:civil.hour+civil.minute/60};
      village.advance({motionSeconds:20+hour,light,reduced:true});
      counts.push(village.marks(1448,1086).filter(m=>m.part==='window' && m.opacity>.01).length);
    }
    assert.ok(counts[0]>counts[1] && counts[1]>counts[2] && counts[2]>counts[3] && counts[3]>counts[4],`${weather}: lights retire in separate late-night groups`);
    assert.equal(counts[4],counts[5]);
    assert.ok(counts[5]>0,`${weather}: some houses remain warmly lit after midnight`);
  }
});
