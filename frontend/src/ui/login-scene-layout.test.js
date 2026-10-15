import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { arrangeScene, groundCellXs, narrowRiverX, riverBanks } from './login-scene-layout.js';

const reference=JSON.parse(readFileSync(new URL('./login-reference.json',import.meta.url),'utf8'));
const rows=points=>Array.from({length:points.length/5},(_,i)=>points.slice(i*5,i*5+5));

test('foreground wheat retains the same horizontal displacement from head to root',()=>{
  for(const u of [.15,.35,.55,.6]) {
    const offsets=[.72,.78,.86,.94].map(v=>narrowRiverX(u,v)-u);
    assert.ok(Math.max(...offsets)-Math.min(...offsets)<1e-10);
  }
});

test('river keeps its right bank and approximately two thirds of the near width',()=>{
  for(const v of [.68,.71,.75,.8]) {
    const [left,right]=riverBanks(v);
    assert.equal(narrowRiverX(right,v),right);
    const ratio=(right-narrowRiverX(left,v))/(right-left);
    assert.ok(Math.abs(ratio-2/3)<.012);
  }
  for(let v=.57;v<.96;v+=.01) {
    let previous=-1;
    for(let u=0;u<1;u+=.005) {
      const next=narrowRiverX(u,v);
      assert.ok(next>=previous,'folded field or river');previous=next;
    }
  }
});

test('expanded fields resample without missing columns and stagger adjacent rows',()=>{
  const row=y=>Array.from({length:180},(_,i)=>groundCellXs(i*4+2,y)).flat();
  const a=row(902),b=row(906);
  for(let i=1;i<a.length;i++)assert.equal(a[i]-a[i-1],4);
  assert.equal(new Set(a).size,a.length);
  assert.notEqual(a[0]%4,b[0]%4,'paper gaps must not align into vertical gutters');
});

test('local vegetation and boat cleanup preserve the village, outer landscape and source asset',()=>{
  const snapshot=reference.points.slice(),edited=arrangeScene(reference.points);
  assert.deepEqual(reference.points,snapshot);
  const unchanged=([x,y])=>(x<170 || x>760 || y<310 || y>790) &&
    !(x>=42 && x<=330 && y>=490 && y<=620) && !(x>=950 && x<=1166 && y>=696 && y<=824);
  const key=p=>`${p[0]},${p[1]}`;
  const before=new Map(rows(snapshot).filter(unchanged).map(p=>[key(p),p]));
  const after=new Map(rows(edited).filter(unchanged).map(p=>[key(p),p]));
  assert.deepEqual(after,before);
  const points=rows(edited);
  const coverage=new Map();
  for(const point of points)coverage.set(key(point),(coverage.get(key(point))||0)+1);
  assert.ok(Math.max(...coverage.values())<=2,'only a canopy may overlay its reconstructed background');
  assert.ok(points.every(([x,y,color,glyph,layer])=>x>0 && x<1448 && y>0 && y<=1086 && color>=0 && color<48 && glyph>=0 && glyph<6 && layer>=0 && layer<=7));
});

test('the old hull, boatman, oar and reflection become water without stray boat layers',()=>{
  const edited=rows(arrangeScene(reference.points));
  assert.equal(edited.some(p=>[3,6,7].includes(p[4])),false);
  const points=new Map(edited.map(p=>[`${p[0]},${p[1]}`,p]));
  for(const [x,y] of [[1030,734],[1094,718],[1126,766],[1110,802]])
    assert.equal(points.get(`${x},${y}`)?.[4],2);
});

test('tightening the middle inlet adds field while keeping both the distant entry and lower river unchanged',()=>{
  const [left,right]=riverBanks(.55);
  assert.ok(narrowRiverX(left,.55)>left+.02);
  assert.equal(narrowRiverX(right,.55),right);
  assert.equal(narrowRiverX(.2,.4),.2);
  assert.ok(Math.abs(narrowRiverX(.6,.75)-.6-.145)<1e-10);
});
