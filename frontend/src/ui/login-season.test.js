import test from 'node:test';
import assert from 'node:assert/strict';
import { makeSeasonLandscape, makeSeasonMarks, seasonTones } from './login-season.js';
import { groundCellXs } from './login-scene-layout.js';

const light={daylight:1,night:0,twilight:0,warmLight:0,moonlight:0};
const field=[];
for(let row=0;row<8;row++)for(let col=0;col<18;col++) {
  const y=710+row*4,x=82+col*4;
  for(const placedX of groundCellXs(x,y))field.push({x:placedX,y,char:col%3 ? '#' : '%',tone:`ink-${col%14}`,
    layer:0,field:true,opacity:.55,size:7.8,scale:1,u:x/1448,v:y/1086,seed:((row*17+col*11)%97)/97});
}
const foliage=Array.from({length:40},(_,i)=>({x:380+i%5*4,y:480+Math.floor(i/5)*4,
  char:i%3 ? ':' : '#',tone:'ink-31',layer:5,size:7,scale:1,u:(380+i%5*4)/1448,v:(480+Math.floor(i/5)*4)/1086,seed:i/40}));
const willow=Array.from({length:24},(_,i)=>({x:410+Math.sin(i*.2)*4,y:500+i*4,
  char:i%3 ? ':' : '|',tone:'ink-32',layer:5,size:7,scale:1,u:.285,v:(500+i*4)/1086,willowTip:i/24}));
const house={x:1170,y:450,char:'#',tone:'ink-8',layer:0,size:7.8,field:false};
const river={x:830,y:790,char:'=',tone:'ink-15',layer:2,size:7.8,u:.6,v:.7,seed:.2};
const trunk={x:413,y:552,char:'|',tone:'ink-40',size:7};
const scene={marks:[...field,house],animated:[...field.map(m=>({...m,layer:1,opacity:1})),...foliage,...willow,trunk,river],
  clouds:[{u:.4,v:.12,char:':',tone:'ink-14',seed:.2}]};
const freeze=value=>{if(value && typeof value==='object'){Object.values(value).forEach(freeze);Object.freeze(value);}return value;};
freeze(scene);

test('season transforms are deterministic, preserve the source and leave autumn unchanged',()=>{
  const original=JSON.stringify(scene);
  for(const season of ['spring','summer','autumn','winter']) {
    const a=makeSeasonLandscape(scene,season),b=makeSeasonLandscape(scene,season);
    assert.deepEqual(a,b);
    assert.equal(JSON.stringify(scene),original);
    assert.deepEqual(a.clouds,scene.clouds);
    assert.notEqual(a,scene);
  }
  assert.deepEqual(makeSeasonLandscape(scene,'autumn'),scene);
});

test('every resampled field coordinate and its soil coverage survives all seasons',()=>{
  const coordinates=marks=>marks.filter(m=>m.field).map(m=>[m.x,m.y]);
  for(const season of ['spring','summer','winter']) {
    const result=makeSeasonLandscape(scene,season);
    assert.deepEqual(coordinates(result.marks),coordinates(scene.marks));
    assert.deepEqual(coordinates(result.animated),coordinates(scene.animated));
    assert.ok(result.marks.filter(m=>m.field).every(m=>(m.opacity ?? 1)>=.5));
  }
});

test('semantic colors affect crops and leaf layers while houses, trunks and water retain their original marks',()=>{
  for(const season of ['spring','summer','winter']) {
    const result=makeSeasonLandscape(scene,season);
    assert.deepEqual(result.marks.find(m=>m.x===house.x),house);
    assert.deepEqual(result.animated.find(m=>m.layer===2),river);
    assert.deepEqual(result.animated.find(m=>m.x===trunk.x && m.y===trunk.y),trunk);
    assert.ok(result.marks.filter(m=>m.field).every(m=>seasonTones.includes(m.tone)));
    assert.ok(result.marks.filter(m=>m.field && m.char==='#').every(m=>m.referenceInk));
  }
});

test('spring seedlings, summer leaves and winter stubble have different shapes and wind amplitudes',()=>{
  const crop=season=>makeSeasonLandscape(scene,season).animated.filter(m=>m.field);
  const spring=crop('spring'),summer=crop('summer'),winter=crop('winter');
  assert.ok(spring.every(m=>['/','\\','|'].includes(m.char) && m.windFactor<=.4));
  assert.ok(summer.every(m=>['/','\\','|'].includes(m.char) && m.windFactor>.6));
  assert.ok(winter.every(m=>[',','|'].includes(m.char) && m.windFactor<.2));
  assert.ok(spring[0].size<summer[0].size && winter[0].size<spring[0].size);
  assert.notDeepEqual(spring.map(m=>m.char),summer.map(m=>m.char));
});

test('winter thins foliage but keeps explicit willow branches at their existing coordinates',()=>{
  const result=makeSeasonLandscape(scene,'winter');
  assert.ok(result.animated.filter(m=>m.layer===5).length<scene.animated.filter(m=>m.layer===5).length*.4);
  const branches=result.marks.filter(m=>m.part==='winter-branch');
  assert.equal(branches.length,willow.length);
  assert.deepEqual(branches.map(m=>[m.x,m.y]),willow.map(m=>[m.x,m.y]));
  assert.ok(branches.every(m=>m.char==='|' && m.layer===0 && m.windFactor===0));
});

test('seasonal details are finite, bounded, deterministic and still in reduced motion',()=>{
  for(const season of ['spring','summer','autumn','winter'])for(const [width,height] of [[1448,1246],[600,1200],[1600,1360]]) {
    const options={season,light:{...light,night:1,daylight:0},motionSeconds:0,reduced:false};
    const first=makeSeasonMarks(width,height,options);
    assert.deepEqual(first,makeSeasonMarks(width,height,options));
    assert.ok(first.length>0 && first.length<=500);
    for(const time of [0,1,12,60,600]) {
      const marks=makeSeasonMarks(width,height,{...options,motionSeconds:time});
      assert.ok(marks.length<=500);
      for(const mark of marks) {
        assert.ok(Number.isFinite(mark.x) && Number.isFinite(mark.y));
        assert.ok(mark.x>=0 && mark.x<=width && mark.y>=0 && mark.y<=height);
        assert.ok(/^[\x20-\x7e]$/.test(mark.char));
        assert.ok(mark.opacity>0 && mark.opacity<=1 && (mark.pixel ?? 0)<=3);
        assert.ok(seasonTones.includes(mark.tone));
      }
    }
    assert.deepEqual(makeSeasonMarks(width,height,{...options,reduced:true}),
      makeSeasonMarks(width,height,{...options,reduced:true,motionSeconds:600}));
  }
});

test('summer fireflies appear only at night; falling leaves move without moving the ground',()=>{
  const options={season:'summer',light,motionSeconds:0,reduced:false};
  assert.equal(makeSeasonMarks(1448,1246,options).length,0);
  const summer=makeSeasonMarks(1448,1246,{...options,light:{...light,night:1,daylight:0}});
  assert.ok(summer.every(m=>m.part==='summer-firefly'));
  const autumn={...options,season:'autumn'};
  assert.notDeepEqual(makeSeasonMarks(1448,1246,autumn),makeSeasonMarks(1448,1246,{...autumn,motionSeconds:10}));
  assert.deepEqual(makeSeasonLandscape(scene,'autumn'),scene);
});

test('winter retains soil-particle raster density and spring keeps the sampled willow crown',()=>{
  const winter=makeSeasonLandscape(scene,'winter'),spring=makeSeasonLandscape(scene,'spring');
  const originalPixels=scene.marks.filter(m=>m.field && m.char==='#');
  const soilPixels=winter.marks.filter(m=>m.field && m.char==='#');
  assert.ok(soilPixels.length>=originalPixels.length*.8);
  assert.ok(soilPixels.every(m=>m.referenceInk && m.size>=7.4 && m.opacity>=.85));
  const originalLeaves=foliage.filter(m=>m.char==='#');
  const springLeaves=spring.animated.filter(m=>m.layer===5 && m.char==='#');
  assert.equal(springLeaves.length,originalLeaves.length);
  assert.ok(springLeaves.every(m=>m.size===7 && m.opacity>=.9));
});

test('connected leaf ink crosses the old tree and building cut lines without removing source cells',()=>{
  const point=(x,y,tone,layer=0)=>({x,y,u:x/1448,v:y/1086,char:'#',tone,layer,size:7.8,scale:1,seed:.4});
  const connected=[point(1296,398,'ink-31',5),point(1292,398,'ink-18'),
    ...[406,410,414,418].map(y=>point(1018,y,'ink-26')),point(1022,422,'ink-26')];
  const protectedMarks=[point(1288,398,'ink-43'),point(1100,440,'ink-8'),point(1200,510,'ink-20'),
    point(1360,620,'ink-15',2),point(500,400,'ink-31')];
  const original=freeze({marks:[...connected.filter(m=>m.layer===0),...protectedMarks],animated:connected.filter(m=>m.layer===5),clouds:[]});
  for(const season of ['spring','summer','winter']) {
    const result=makeSeasonLandscape(original,season),all=[...result.marks,...result.animated];
    for(const mark of connected) {
      const changed=all.find(m=>m.x===mark.x && m.y===mark.y);
      assert.ok(changed,'seasonal foliage must retain every original source position');
      assert.equal(changed.tone,season==='winter' ? 'seasonCropShade' : 'seasonLeaf');
    }
    for(const mark of protectedMarks)assert.ok(all.includes(mark),'unconnected walls, roof and river remain untouched');
    assert.equal(all.length,connected.length+protectedMarks.length,'no replacement background or invented tree outline');
  }
  assert.deepEqual(makeSeasonLandscape(original,'autumn'),original);
});

test('winter retains the reference branch geometry and uses fine twig ink instead of synthetic arches',()=>{
  const point=(x,y,tone)=>({x,y,u:x/1448,v:y/1086,char:'#',tone,layer:5,size:7.8,scale:1,seed:.4});
  const branches=[point(1390,430,'ink-43'),point(1386,434,'ink-44'),point(1394,434,'ink-40')];
  const leaves=[point(1390,426,'ink-31'),point(1386,426,'ink-18')];
  const original=freeze({marks:[],animated:[...branches,...leaves],clouds:[]});
  const result=makeSeasonLandscape(original,'winter');
  assert.equal(result.animated.length,0);
  assert.equal(result.marks.length,original.animated.length);
  for(const branch of branches) {
    const retained=result.marks.find(m=>m.x===branch.x && m.y===branch.y);
    assert.deepEqual([retained.char,retained.tone,retained.size],[branch.char,branch.tone,branch.size]);
    assert.equal(retained.part,'winter-reference-branch');
  }
  const twigs=result.marks.filter(m=>m.part==='winter-fine-twig');
  assert.equal(twigs.length,leaves.length);
  assert.ok(twigs.every(m=>m.char===':' && m.opacity>0 && m.opacity<.5 && m.size<7.8));
});

test('ground connected to a crown keeps its surface instead of becoming a paper-colored twig strip',()=>{
  const marks=[];
  for(let y=402;y<=602;y+=4)marks.push({x:1018,y,u:1018/1448,v:y/1086,
    char:'#',tone:'ink-26',layer:0,size:7.8,scale:1,seed:.4});
  const original=freeze({marks,animated:[],clouds:[]});
  const winter=makeSeasonLandscape(original,'winter');
  assert.equal(winter.marks.length,marks.length);
  assert.equal(winter.marks[0].part,'winter-fine-twig');
  assert.ok(winter.marks.filter(m=>m.y>=554).every(m=>m.char==='#' && !m.part),
    'a touching ground component must retain its original dense land ink');
  assert.deepEqual(winter.marks.map(m=>[m.x,m.y]),marks.map(m=>[m.x,m.y]));
});

test('winter soil preserves the reference light, middle and dark field bands',()=>{
  const original={marks:[2,8,26,31].map((color,i)=>({...field[i],tone:`ink-${color}`})),animated:[],clouds:[]};
  const winter=makeSeasonLandscape(original,'winter');
  assert.deepEqual(winter.marks.map(m=>m.tone),['seasonCropLight','seasonCropMid','seasonSoil','seasonCropShade']);
  assert.deepEqual(winter.marks.map(m=>[m.x,m.y]),original.marks.map(m=>[m.x,m.y]));
});

test('only warm right-bank ground connected to original crop anchors gains seasonal color',()=>{
  const point=(x,y,layer,field=false)=>({x,y,u:x/1448,v:y/1086,char:'#',tone:'ink-8',layer,field,size:7.8,seed:.4});
  const strip=[point(1002,626,0),point(1002,630,0),point(1002,634,0),point(1002,638,0)];
  const seed=point(1002,642,1,true),disconnectedHouse=point(1070,626,0),water=point(1006,630,2);
  const original={marks:[...strip,disconnectedHouse],animated:[seed,water],clouds:[]};
  for(const season of ['spring','summer','winter']) {
    const result=makeSeasonLandscape(original,season);
    assert.ok(result.marks.slice(0,strip.length).every(m=>seasonTones.includes(m.tone)));
    assert.deepEqual(result.marks.at(-1),disconnectedHouse);
    assert.deepEqual(result.animated.at(-1),water);
    assert.deepEqual(result.marks.map(m=>[m.x,m.y]),original.marks.map(m=>[m.x,m.y]));
  }
});
