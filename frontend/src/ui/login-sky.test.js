import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createSky, makeSkyMarks, cloudCover, skyIllumination, skyTones } from './login-sky.js';
import { daylightAt } from './login-daylight.js';

const source=Array.from({length:72},(_,i)=>({u:.45+(i%12)*4/1448,
  v:.11+Math.floor(i/12)*4/1086,char:i%3 ? ':' : '=',tone:'ink-10',seed:i/72}));
const light={sun:{altitude:35,azimuth:130},moon:{altitude:-20,azimuth:250,phase:.5,fraction:1,angle:0},
  daylight:1,twilight:0,warmLight:0,evening:0,night:0,moonlight:0};
const options={width:1448,height:1086,light,weather:'cloudy',motionSeconds:0,reduced:false};
const clouds=marks=>marks.filter(mark=>mark.part==='cloud');
const body=(model,state,part)=>makeSkyMarks(model,{...options,light:state}).filter(mark=>mark.part===part);

test('year fusion replaces racing discs with bounded stable ASCII orbit strokes',()=>{
  const model=createSky(1,[]),fused={...light,fusion:1};
  const a=makeSkyMarks(model,{...options,light:fused});
  const b=makeSkyMarks(model,{...options,light:{...fused,sun:{altitude:-45,azimuth:0},moon:{...light.moon,altitude:70}}});
  assert.equal(a.filter(m=>m.part==='sun'||m.part==='moon').length,0);
  assert.deepEqual(a,b);
  const orbit=a.filter(m=>m.part==='year-orbit');
  assert.ok(orbit.length>=12 && orbit.length<=60);
  assert.ok(orbit.every(m=>/^[.:'-]$/.test(m.char) && m.opacity<=.4 && skyTones.includes(m.tone)));
  assert.deepEqual(skyIllumination(model,{...options,light:fused}),
    skyIllumination(model,{...options,light:{...fused,sun:{altitude:-45,azimuth:0}}}));
  assert.ok(body(model,{...light,fusion:0},'sun').length>0);
});

test('year orbit glyphs remain sparse, finite and clear of inscriptions at responsive sizes',()=>{
  const model=createSky(1,[]);
  for(const [width,height] of [[600,1000],[890,1000],[1582,1347]]) {
    const orbit=makeSkyMarks(model,{...options,width,height,light:{...light,fusion:1}}).filter(m=>m.part==='year-orbit');
    assert.ok(orbit.length>=12 && orbit.length<=56);
    for(const mark of orbit) {
      assert.ok(Number.isFinite(mark.x) && Number.isFinite(mark.y));
      assert.ok(mark.x>=0 && mark.x<=width && mark.y>=0 && mark.y<=height);
      assert.ok(!(mark.x<Math.min(340,width*.38) && mark.y<230));
      assert.ok(!(mark.x>width-Math.min(116,width*.18) && mark.y<240));
    }
  }
  assert.doesNotMatch(readFileSync(new URL('./login-sky.js',import.meta.url),'utf8'),/createLinearGradient|createRadialGradient|blur\(/);
});

test('clouds retain a stable connected identity and move coherently without resampling',()=>{
  const model=createSky(14,source),first=clouds(makeSkyMarks(model,options));
  const later=clouds(makeSkyMarks(model,{...options,motionSeconds:30}));
  assert.equal(new Set(first.map(mark=>mark.cloudId)).size,1);
  assert.deepEqual(first,clouds(makeSkyMarks(model,options)));
  assert.equal(first.length,source.length);
  assert.equal(later.length,first.length);
  assert.ok(later[0].x-first[0].x>8);
  assert.ok(Math.abs((later[1].x-later[0].x)-(first[1].x-first[0].x))<.3);
  assert.deepEqual(makeSkyMarks(model,{...options,reduced:true}),
    makeSkyMarks(model,{...options,reduced:true,motionSeconds:300}));
});

test('near and distant connected clouds drift at different slow speeds',()=>{
  const far=source.map(mark=>({...mark,u:mark.u-.25,v:mark.v+.1}));
  const model=createSky(14,[...source,...far]);
  const start=clouds(makeSkyMarks(model,options)),end=clouds(makeSkyMarks(model,{...options,motionSeconds:30}));
  const delta=id=>end.find(mark=>mark.cloudId===id).x-start.find(mark=>mark.cloudId===id).x;
  const ids=[...new Set(start.map(mark=>mark.cloudId))];
  assert.equal(ids.length,2);
  assert.ok(Math.abs(delta(ids[0])-delta(ids[1]))>3);
});

test('sun follows altitude and azimuth while underground bodies are hidden',()=>{
  const model=createSky(1,[]),low=body(model,{...light,sun:{altitude:8,azimuth:95}},'sun');
  const high=body(model,{...light,sun:{altitude:60,azimuth:190}},'sun');
  assert.ok(low.length && high.length);
  const average=(points,key)=>points.reduce((sum,p)=>sum+p[key],0)/points.length;
  assert.ok(average(low,'y')-average(high,'y')>100);
  assert.ok(Math.abs(average(low,'x')-average(high,'x'))>200);
  assert.equal(body(model,{...light,sun:{altitude:-1,azimuth:95}},'sun').length,0);
  assert.equal(body(model,{...light,moon:{...light.moon,altitude:-1}},'moon').length,0);
});

test('lunar illuminated area follows the phase and waxing and waning face opposite ways',()=>{
  const model=createSky(1,[]),night={...light,daylight:0,night:1,sun:{altitude:-30,azimuth:0}};
  const moon=(fraction,phase)=>body(model,{...night,moon:{altitude:45,azimuth:180,fraction,phase,
    angle:phase<=.5 ? -90 : 90,parallacticAngle:0}},'moon');
  const full=moon(1,.5),crescent=moon(.08,.08),newMoon=moon(0,0);
  assert.ok(full.length>30);
  assert.ok(crescent.length>0 && crescent.length<full.length*.25);
  assert.equal(newMoon.length,0);
  const halfA=moon(.5,.25),halfB=moon(.5,.75);
  assert.ok(halfA.reduce((sum,p)=>sum+p.x,0)/halfA.length>halfB.reduce((sum,p)=>sum+p.x,0)/halfB.length);
});

test('thick cloud coverage suppresses moonlight and stars using the moving cloud shape',()=>{
  const model=createSky(14,source),at={u:.465,v:.12,motionSeconds:0,reduced:false};
  assert.ok(cloudCover(model,{...at,weather:'overcast'})>.75);
  assert.ok(cloudCover(model,{...at,weather:'overcast'})>cloudCover(model,{...at,weather:'clear'}));
  assert.equal(cloudCover(model,{...at,u:.1,weather:'overcast'}),0);
  assert.ok(cloudCover(model,{...at,motionSeconds:300,weather:'overcast'})<.1);
  const night={...light,daylight:0,night:1,sun:{altitude:-30,azimuth:0},moon:{...light.moon,altitude:45}};
  const clear=makeSkyMarks(model,{...options,light:night,weather:'clear'}).filter(p=>p.part==='star');
  const overcast=makeSkyMarks(model,{...options,light:night,weather:'overcast'}).filter(p=>p.part==='star');
  assert.ok(clear.length>0);
  assert.ok(overcast.reduce((sum,p)=>sum+p.opacity,0)<clear.reduce((sum,p)=>sum+p.opacity,0));
});

test('all twelve-second samples stay bounded, finite, ASCII and within the visible sky',()=>{
  const model=createSky(14,source);
  for(const [width,height] of [[1448,1246],[600,1200],[1600,1360]])for(const weather of ['clear','cloudy','overcast']) {
    for(let t=0;t<=12;t+=.5) {
      const marks=makeSkyMarks(model,{...options,width,height,weather,motionSeconds:t});
      assert.ok(marks.length>source.length && marks.length<source.length*3+1100);
      for(const mark of marks) {
        assert.ok(Number.isFinite(mark.x) && Number.isFinite(mark.y));
        assert.ok(mark.x>=0 && mark.x<=width && mark.y>=0 && mark.y<=height);
        assert.ok(mark.opacity>=0 && mark.opacity<=1);
        assert.ok(skyTones.includes(mark.tone));
        assert.ok(/^[\x20-\x7e]$/.test(mark.char));
        if(mark.part==='cloud')assert.ok(mark.y<height-1086*width/1448+310*width/1448);
      }
    }
  }
});

test('weather and daylight recolor existing geometry instead of regenerating clouds',()=>{
  const model=createSky(14,source),a=clouds(makeSkyMarks(model,options));
  const b=clouds(makeSkyMarks(model,{...options,weather:'overcast',light:{...light,warmLight:1,evening:1}}));
  const geometry=marks=>[...new Set(marks.map(p=>JSON.stringify([p.x,p.y,p.char,p.cloudId])))];
  assert.deepEqual(geometry(a),geometry(b));
  assert.notDeepEqual(a.map(p=>p.tone),b.map(p=>p.tone));
});

test('whole clouds leave the frame and return gradually without an on-screen wrap jump',()=>{
  const model=createSky(14,source),dark={...light,sun:{altitude:-30,azimuth:0}};
  let previous=source.length,empty=false,returned=false;
  for(let time=0;time<3000;time++) {
    const count=clouds(makeSkyMarks(model,{...options,light:dark,motionSeconds:time})).length;
    assert.ok(Math.abs(count-previous)<=12,'only a small edge strip may enter or leave each second');
    if(!count)empty=true;
    if(empty && count)returned=true;
    previous=count;
  }
  assert.ok(empty && returned);
});

test('occluded lunar pixels and water moonlight dim together',()=>{
  const night={...light,daylight:0,night:1,moonlight:1,sun:{altitude:-30,azimuth:0},
    moon:{altitude:45,azimuth:180,phase:.5,fraction:1,angle:0}};
  const uncovered=createSky(1,[]),visible=body(uncovered,night,'moon');
  const x=visible.reduce((sum,p)=>sum+p.x,0)/visible.length;
  const y=visible.reduce((sum,p)=>sum+p.y,0)/visible.length;
  const veil=Array.from({length:400},(_,i)=>({u:(x-40+i%20*4)/1448,v:(y-40+Math.floor(i/20)*4)/1086,
    char:':',tone:'ink-10',seed:i/400}));
  const covered=createSky(1,veil),state={...options,light:night,weather:'overcast'};
  const dim=makeSkyMarks(covered,state).filter(mark=>mark.part==='moon');
  assert.ok(dim.reduce((sum,p)=>sum+p.opacity,0)<visible.reduce((sum,p)=>sum+p.opacity,0)*.15);
  assert.ok(skyIllumination(covered,state).moonlight<skyIllumination(uncovered,state).moonlight*.15);
});

test('the full reference cloud trace stays stable and finite across sizes and twelve-second samples',()=>{
  const reference=JSON.parse(readFileSync(new URL('./login-reference.json',import.meta.url),'utf8'));
  const trace=[];
  for(let i=0;i<reference.points.length;i+=5)if(reference.points[i+4]===4)trace.push({
    u:reference.points[i]/reference.width,v:reference.points[i+1]/reference.height,
    char:reference.glyphs[reference.points[i+3]],tone:`ink-${reference.points[i+2]}`,seed:(i%97)/97,
  });
  const model=createSky(14,trace);
  assert.ok(model.groups.length>2);
  for(const [width,height] of [[1448,1246],[600,1200],[1600,1360]])for(let t=0;t<=12;t+=2) {
    const marks=makeSkyMarks(model,{...options,width,height,motionSeconds:t});
    assert.ok(marks.some(mark=>mark.part==='sun') && marks.some(mark=>mark.part==='cloud'));
    assert.ok(marks.length<trace.length*3+1100);
    for(const point of marks) {
      assert.ok(Number.isFinite(point.x) && Number.isFinite(point.y));
      assert.ok(point.x>=0 && point.x<=width && point.y>=0 && point.y<=height);
    }
  }
});

test('cloud opacity and the night-color blend remain continuous through dusk and dawn',()=>{
  const model=createSky(14,source);
  const frame=night=>clouds(makeSkyMarks(model,{...options,light:{...light,night}}));
  const total=marks=>marks.reduce((sum,p)=>sum+p.opacity,0);
  const before=frame(.549999),after=frame(.550001);
  assert.ok(Math.abs(total(before)-total(after))<.001);
  assert.ok(before.some(p=>p.tone==='sky-cloud-night') && before.some(p=>p.tone!=='sky-cloud-night'));
  assert.ok(after.length<=source.length*2);
});

test('real waxing and waning moons use observer-relative bright-limb angles without a phase flip',()=>{
  const model=createSky(1,[]);
  for(const date of ['2026-10-24T16:00:00Z','2026-11-02T16:00:00Z']) {
    const state=daylightAt(Date.parse(date));
    assert.ok(Number.isFinite(state.moon.parallacticAngle));
    const lit=body(model,state,'moon'),full=body(model,{...state,moon:{...state.moon,fraction:1}},'moon');
    const center=points=>[points.reduce((sum,p)=>sum+p.x,0)/points.length,points.reduce((sum,p)=>sum+p.y,0)/points.length];
    const [cx,cy]=center(full),[lx,ly]=center(lit),length=Math.hypot(lx-cx,ly-cy);
    const angle=(state.moon.angle-state.moon.parallacticAngle)*Math.PI/180;
    assert.ok(length>.05);
    assert.ok(((lx-cx)*-Math.sin(angle)+(ly-cy)*-Math.cos(angle))/length>.98);
    const phaseOnly={...state,moon:{...state.moon,phase:1-state.moon.phase}};
    assert.deepEqual(lit,body(model,phaseOnly,'moon'),'angle already describes the bright side');
  }
});

test('water illumination tracks partially emerged discs and changes smoothly at the ridge',()=>{
  const model=createSky(1,[]),state={...light,warmLight:1,sun:{azimuth:95,altitude:45}};
  const full=body(model,state,'sun').reduce((sum,p)=>sum+p.opacity,0);
  let previous=0,sawPartial=false;
  for(let altitude=.01;altitude<12;altitude+=.02) {
    const sample={...state,sun:{...state.sun,altitude}};
    const visible=body(model,sample,'sun').reduce((sum,p)=>sum+p.opacity,0)/full;
    const river=skyIllumination(model,{...options,light:sample}).warmLight;
    assert.ok(Math.abs(river-visible)<.005);
    assert.ok(Math.abs(river-previous)<.025,'the whole river must not switch at the disc center');
    if(river>.1 && river<.9)sawPartial=true;
    previous=river;
  }
  assert.ok(sawPartial);
});

test('actual left-pane sizes with the responsive height contract retain visible sun and moon paths',()=>{
  const model=createSky(1,[]);
  for(const viewportWidth of [1024,1440,1920,3440,5120]) {
    const width=viewportWidth*.618,height=Math.max(900,width*.75+160);
    let solar=0,lunar=0;
    for(let hour=0;hour<24;hour++) {
      const state=daylightAt(Date.parse('2026-10-24T00:00:00Z')+hour*3600_000);
      const marks=makeSkyMarks(model,{...options,width,height,light:state});
      solar+=marks.filter(p=>p.part==='sun').length;
      lunar+=marks.filter(p=>p.part==='moon').length;
    }
    assert.ok(solar>200 && lunar>200,`visible paths required for ${viewportWidth}px browser`);
  }
});

test('clear-day cloud ink remains legible while optical cloud cover stays thin',()=>{
  const model=createSky(14,source),marks=clouds(makeSkyMarks(model,{...options,weather:'clear'}));
  const average=marks.reduce((sum,p)=>sum+p.opacity,0)/source.length;
  assert.ok(average>.5 && average<.85,'preserve traced cloud lines at a readable strength');
  assert.ok(cloudCover(model,{u:.465,v:.12,weather:'clear',motionSeconds:0})<.45);
});

test('dawn and dusk light the existing cloud shapes coherently without scattering new warm points',()=>{
  const model=createSky(14,source.map(mark=>({...mark,v:mark.v+.105})));
  for(const period of ['dawn','dusk']) {
    const state={...light,period,warmLight:1,twilight:1,evening:period==='dusk' ? 1 : 0,
      sun:{altitude:-2,azimuth:period==='dawn' ? 90 : 270}};
    const marks=clouds(makeSkyMarks(model,{...options,weather:'clear',light:state}));
    const warm=marks.filter(p=>['sky-cloud-warm','sky-cloud-rose'].includes(p.tone));
    assert.equal(warm.length,source.length,'the same cloud trace receives continuous warm light');
    assert.ok(warm.some(p=>p.tone==='sky-cloud-warm') && warm.some(p=>p.tone==='sky-cloud-rose'));
    assert.ok(warm.reduce((sum,p)=>sum+p.opacity,0)>source.length*.32);
    const overcast=clouds(makeSkyMarks(model,{...options,weather:'overcast',light:state}))
      .filter(p=>['sky-cloud-warm','sky-cloud-rose'].includes(p.tone));
    assert.ok(overcast.reduce((sum,p)=>sum+p.opacity,0)<warm.reduce((sum,p)=>sum+p.opacity,0)*.25);
    const next=clouds(makeSkyMarks(model,{...options,weather:'clear',light:{...state,warmLight:.999}}))
      .filter(p=>['sky-cloud-warm','sky-cloud-rose'].includes(p.tone));
    assert.equal(next.length,warm.length);
    assert.ok(warm.every((p,i)=>Math.abs(p.opacity-next[i].opacity)<.002));
  }
});

test('the Venus belt is a readable sparse arc opposite sunrise or sunset and subdued by overcast',()=>{
  const model=createSky(1,[]),sum=marks=>marks.reduce((total,p)=>total+p.opacity,0);
  const belt=(azimuth,weather)=>makeSkyMarks(model,{...options,weather,
    light:{...light,twilight:1,warmLight:1,sun:{altitude:-3,azimuth}}}).filter(p=>p.part==='venus');
  for(const azimuth of [90,270]) {
    const clear=belt(azimuth,'clear'),cloudy=belt(azimuth,'cloudy'),overcast=belt(azimuth,'overcast');
    assert.ok(sum(clear)>20 && sum(cloudy)>10);
    assert.ok(sum(overcast)<sum(clear)*.15);
    assert.ok(clear.length<200);
    const center=clear.reduce((total,p)=>total+p.x*p.opacity,0)/sum(clear)/options.width;
    assert.ok(azimuth===90 ? center>.65 : center<.45);
    assert.ok(new Set(clear.map(p=>Math.round(p.y))).size>5,'the band has a restrained curved contour');
  }
  assert.equal(makeSkyMarks(model,options).filter(p=>p.part==='venus').length,0);
});

test('warm cloud light is concentrated above the ridge and fades upward into cool high clouds',()=>{
  const high=source.map(mark=>({...mark,v:mark.v-.08}));
  const low=source.map(mark=>({...mark,v:mark.v+.105}));
  const model=createSky(14,[...high,...low]);
  const state={...light,warmLight:1,twilight:1,sun:{altitude:-2,azimuth:90}};
  const warm=makeSkyMarks(model,{...options,light:state,weather:'clear'})
    .filter(p=>p.part==='cloud' && ['sky-cloud-warm','sky-cloud-rose'].includes(p.tone));
  const strength=(min,max)=>warm.filter(p=>p.y>min && p.y<max).reduce((sum,p)=>sum+p.opacity,0);
  assert.ok(strength(200,270)>source.length*.32);
  assert.ok(strength(0,90)<strength(200,270)*.12);
});

test('low atmospheric strokes fade upward, stay above the terrain and use the same distribution in all weather',()=>{
  const model=createSky(1,[]),state={...light,warmLight:1,twilight:1,sun:{altitude:-2,azimuth:90}};
  const strokes=weather=>makeSkyMarks(model,{...options,light:state,weather}).filter(p=>p.part==='atmosphere');
  const clear=strokes('clear'),cloudy=strokes('cloudy'),overcast=strokes('overcast');
  assert.ok(clear.length>=350 && clear.length<=800);
  assert.ok(clear.every(p=>p.y<310 && p.y>140 && /^[=:-]$/.test(p.char)));
  const average=band=>clear.filter(p=>p.band===band).reduce((sum,p)=>sum+p.opacity,0)/clear.filter(p=>p.band===band).length;
  assert.ok(average(0)>average(1)*1.5 && average(1)>average(2)*1.7);
  assert.ok(new Set(clear.filter(p=>p.band===0).map(p=>Math.round(p.y))).size>8,'overlapping strokes must have some body');
  const coordinateSet=new Set(clear.map(p=>`${p.x},${p.y}`));
  assert.ok([...cloudy,...overcast].every(p=>coordinateSet.has(`${p.x},${p.y}`)));
  const sum=marks=>marks.reduce((total,p)=>total+p.opacity,0);
  assert.ok(sum(overcast)<sum(clear)*.1 && sum(cloudy)>sum(overcast)*5);
  assert.equal(makeSkyMarks(model,options).filter(p=>p.part==='atmosphere').length,0);
});
