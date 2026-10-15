export const seasonTones=['seasonCropLight','seasonCropMid','seasonCropShade','seasonLeaf',
  'seasonSoil','seasonSnow','seasonFlower','seasonFirefly'];
const warm=new Set([...Array.from({length:14},(_,i)=>i),17,19,25,26,27]);
const green=new Set([16,18,23,...Array.from({length:20},(_,i)=>i+28)]);
const noise=(x,y)=>{const n=Math.sin(x*127.1+y*311.7)*43758.5453;return n-Math.floor(n);};
const seedOf=mark=>mark.seed ?? noise(mark.u*1448,mark.v*1086);
const clamp=value=>Math.max(0,Math.min(1,value));
const leafColors=new Set([...warm,16,18,23,28,29,30,31,32,33,34,35,36,38,39]);
const winterCropTone=color=>color<6 ? 'seasonCropLight' : color<14 ? 'seasonCropMid' : warm.has(color) ? 'seasonSoil' : 'seasonCropShade';

// Grow through touching vegetation ink, rather than cutting a geometric hole around
// a tree. Blue walls/water and the dark original roof/branch ink never enter this graph.
function treeLeaves(scene) {
  const cells=new Map(),pending=[],seen=new Set(),leaves=new Set(),treeSeeds=new Set(),groundSeeds=new Set();
  for(const mark of [...scene.marks,...scene.animated]) {
    const color=Number(mark.tone.slice(4)),x=mark.u*1448,y=mark.v*1086;
    if(mark.field || ![0,5].includes(mark.layer) || !leafColors.has(color))continue;
    const key=Math.round(x/4+1e-6)+','+Math.round(y/4+1e-6);
    if(!cells.has(key))cells.set(key,[]);
    cells.get(key).push(mark);
    const seed=(mark.layer===5 && x>1290) || (warm.has(color) && x>950 && x<1250 && y>280 && y<410);
    if(seed)treeSeeds.add(key);
    if(mark.layer===0 && (y>550 || x<900))groundSeeds.add(key);
  }
  // Competing ground anchors keep a touching crop/grass component below the houses
  // from being mistaken for the tree above it. Layer-5 canopy anchors take priority.
  for(const key of groundSeeds) {
    const [x,y]=key.split(',').map(Number);
    let nearCanopy=false;
    for(let dx=-3;dx<=3;dx++)for(let dy=-3;dy<=3;dy++)if(treeSeeds.has((x+dx)+','+(y+dy)))nearCanopy=true;
    if(!nearCanopy){seen.add(key);pending.push([key,false]);}
  }
  for(const key of treeSeeds)if(!seen.has(key)){seen.add(key);pending.push([key,true]);}
  for(let i=0;i<pending.length;i++) {
    const [key,isTree]=pending[i],[x,y]=key.split(',').map(Number);
    if(isTree)for(const mark of cells.get(key))leaves.add(mark);
    for(let dx=-1;dx<=1;dx++)for(let dy=-1;dy<=1;dy++) {
      const next=(x+dx)+','+(y+dy);
      if(!seen.has(next) && cells.has(next)){seen.add(next);pending.push([next,isTree]);}
    }
  }
  return leaves;
}

function rightBankCrops(scene) {
  const cells=new Map(),pending=[],seen=new Set(),crop=new Set();
  for(const mark of [...scene.marks,...scene.animated]) {
    // The village-front crop strip includes layer-0 grain adjoining original layer-1 heads.
    if(![0,1].includes(mark.layer) || mark.u<.6 || mark.v<.53 || mark.v>.61 || !warm.has(Number(mark.tone.slice(4))))continue;
    const x=Math.round(mark.u*1448/4),y=Math.round(mark.v*1086/4),key=`${x},${y}`;
    if(!cells.has(key))cells.set(key,[]);
    cells.get(key).push(mark);
    if(mark.layer===1 && !seen.has(key)){seen.add(key);pending.push([x,y]);}
  }
  for(let at=0;at<pending.length;at++) {
    const [x,y]=pending[at];
    for(const mark of cells.get(`${x},${y}`))crop.add(mark);
    for(let dx=-2;dx<=2;dx++)for(let dy=-2;dy<=2;dy++) {
      const key=`${x+dx},${y+dy}`;
      if(dx*dx+dy*dy>4 || seen.has(key) || !cells.has(key))continue;
      seen.add(key);pending.push([x+dx,y+dy]);
    }
  }
  return crop;
}

export function makeSeasonLandscape(scene,season) {
  if(season==='autumn')return {marks:scene.marks.slice(),animated:scene.animated.slice(),clouds:scene.clouds.slice()};
  const marks=[],animated=[],bankCrops=rightBankCrops(scene),leaves=treeLeaves(scene);
  const transform=mark=>{
    const color=Number(mark.tone.slice(4)),seed=seedOf(mark);
    if(leaves.has(mark)) {
      if(season==='winter')return {...mark,char:mark.char==='#' || mark.char==='%' ? ':' : mark.char,
        tone:'seasonCropShade',referenceInk:true,size:mark.size*.82,opacity:.32,
        layer:0,windFactor:0,part:'winter-fine-twig'};
      return {...mark,tone:'seasonLeaf',referenceInk:true,
        opacity:season==='spring' ? .96 : 1,windFactor:season==='spring' ? .55 : .9};
    }
    if(mark.layer===5 && color>=40)return season==='winter'
      ? {...mark,layer:0,windFactor:0,part:'winter-reference-branch'} : mark;
    if((mark.field || bankCrops.has(mark)) && mark.layer!==2 && (warm.has(color) || green.has(color))) {
      let tone=warm.has(color) ? color<6 ? 'seasonCropLight' : color<14 ? 'seasonCropMid' : 'seasonCropShade'
        : color<28 ? 'seasonCropMid' : 'seasonCropShade';
      if(season==='winter')tone=winterCropTone(color);
      else if(season==='spring' && mark.layer!==1 && seed<.12)tone='seasonSoil';
      const next={...mark,tone,referenceInk:true};
      if(mark.layer===1) {
        next.char=season==='winter' ? seed<.5 ? '|' : ',' : season==='spring'
          ? seed<.4 ? '/' : seed<.8 ? '\\' : '|' : seed<.25 ? '|' : seed<.62 ? '/' : '\\';
        next.size=mark.size*(season==='winter' ? .48 : season==='spring' ? .72 : 1.06);
        next.windFactor=season==='winter' ? .12 : season==='spring' ? .35 : .78;
        if(season==='winter')next.opacity=.75;
      } else if(season==='winter' && warm.has(color)) {
        // Soil keeps the reference's small raster grains; fine stubble sits above it.
        next.char=(mark.char==='#' && seed<.92) || seed<.52 ? '#' : seed<.8 ? ':' : '-';
        next.size=mark.size*(next.char==='#' ? 1 : .9);
        next.opacity=Math.max(mark.opacity ?? 1,.94);
      }
      return next;
    }
    if(mark.layer===5 && !mark.field) {
      if(season==='winter' && seed> (mark.willowTip===undefined ? .22 : .1))return null;
      return {...mark,tone:'seasonLeaf',referenceInk:true,
        char:season==='winter' ? ':' : season==='spring' && mark.char==='%' && seed<.12 ? '^' : mark.char,
        size:mark.size*(season==='winter' ? .65 : 1),
        opacity:season==='winter' ? .48 : season==='spring' ? .94 : mark.opacity ?? 1,
        windFactor:season==='winter' ? .18 : season==='spring' ? .55 : .9};
    }
    return mark;
  };
  for(const mark of scene.marks) {
    const next=transform(mark);if(next)marks.push(next);
  }
  for(const mark of scene.animated) {
    // Trace bare twigs along the already-placed explicit willow, never rebuild its geometry.
    if(season==='winter' && mark.willowTip!==undefined && mark.char!=='%')marks.push({...mark,
      char:'|',tone:'seasonSoil',size:mark.size*.72,opacity:.48,layer:0,windFactor:0,part:'winter-branch'});
    const next=transform(mark);
    if(next)(next.layer===0 ? marks : animated).push(next);
  }
  return {marks,animated,clouds:scene.clouds.slice()};
}

export function makeSeasonMarks(width,height,{season,light,motionSeconds=0,reduced=false}) {
  if(width<=0 || height<=0)return [];
  const scale=width/1448,top=height-1086*scale,time=reduced ? 0 : motionSeconds,marks=[];
  const visible=.48+.52*clamp(light.daylight);
  const add=(x,y,char,tone,size,opacity,part,pixel)=>{
    const mark={x:x*scale,y:top+y*scale,char,tone,size:Math.max(3.5,size*scale),opacity:clamp(opacity),part};
    if(pixel)mark.pixel=Math.min(3,pixel*scale);
    if(mark.x>=0 && mark.x<=width && mark.y>=0 && mark.y<=height && mark.opacity>.01)marks.push(mark);
  };
  if(season==='spring') {
    for(const [patch,[cx,cy]] of [[150,718],[272,785],[450,887],[620,964]].entries())for(let i=0;i<9;i++) {
      const seed=noise(patch+1,i+9),x=cx+(seed-.5)*54,y=cy+(noise(i,patch+2)-.5)*23;
      const sway=Math.sin(time*.45+seed*6)*1.5;
      add(x,y,'|','seasonLeaf',5.5,visible*.7,'spring-stem');
      add(x+sway,y-5,'*','seasonFlower',7,visible*.92,'spring-flower');
    }
  } else if(season==='summer') {
    if(light.night<=0)return marks;
    for(let i=0;i<24;i++) {
      const seed=noise(i,31),x=390+noise(i,19)*440+Math.sin(time*.27+seed*6)*12;
      const y=560+noise(i,12)*180+Math.cos(time*.21+seed*8)*7;
      const pulse=.25+.75*(.5+.5*Math.sin(time*1.1+seed*9))**2;
      add(x,y,'.','seasonFirefly',5,light.night*pulse,'summer-firefly',1.7);
    }
  } else if(season==='autumn') {
    for(let i=0;i<22;i++) {
      const seed=noise(i,44),phase=(seed+time*.025)%1;
      const anchor=i<16 ? [413,510,60,100] : [525,426,45,70];
      const x=anchor[0]+(noise(i,8)-.5)*anchor[2]+Math.sin(phase*8+seed*7)*9;
      const y=anchor[1]+phase*anchor[3];
      add(x,y,seed<.5 ? '/' : '-','seasonLeaf',6,Math.sin(phase*Math.PI)*visible*.8,'autumn-leaf');
    }
  } else if(season==='winter') {
    // Small settled patches stay on the existing left fields; the river remains open.
    for(const [patch,[cx,cy]] of [[160,708],[400,810],[735,970]].entries())for(let i=0;i<36;i++) {
      const seed=noise(i,patch+61),x=cx+(seed-.5)*110,y=cy+(noise(patch,i+4)-.5)*22;
      add(x,y,'-','seasonSnow',6,.5+noise(i,patch+2)*.35,'winter-snow',i%3 ? undefined : 1.8);
    }
    for(let i=0;i<14;i++) {
      const seed=noise(i,72),phase=(seed+time*.018)%1;
      const x=160+noise(i,38)*650+Math.sin(time*.2+seed*8)*9,y=370+phase*350;
      add(x,y,'+','seasonSnow',5,Math.sin(phase*Math.PI)*.62,'winter-flake');
    }
  }
  return marks;
}
