// Only the palette size is bundled (named JSON imports are tree-shaken); the ~1 MB trace is a hashed
// static asset fetched once when the login scene mounts, keeping it out of the desktop entry chunk.
import { paletteSize } from "./login-reference.json";
import referenceUrl from "./login-reference.json?url";
import { arrangeScene, narrowRiverX, riverBanks, groundCellXs } from "./login-scene-layout.js";
import { makeBoatMarks, waterMotion } from './login-river-motion.js';
export { narrowRiverX, riverBanks } from "./login-scene-layout.js";
let reference=null,scenePoints=null,boatTrace=[],pending=null;
export function loadLandscape() {
  pending??=fetch(referenceUrl).then(response=>{
    if(!response.ok)throw new Error(`Login scene trace unavailable: ${response.status}`);
    return response.json();
  }).then(data=>{
    const trace=[];
    for(let i=0;i<data.points.length;i+=5)if(data.points[i+4]===3)trace.push(data.points.slice(i,i+4));
    scenePoints=arrangeScene(data.points);boatTrace=trace;reference=data;
  }).catch(error=>{pending=null;throw error;});
  return pending;
}
export const landscapeReady=()=>reference!==null;
export const riverBoat=(width,height,time)=>reference ? makeBoatMarks(width,height,time,boatTrace) : [];

const font='Consolas, "Liberation Mono", monospace';
const noise=(x,y)=>{const n=Math.sin(x*127.1+y*311.7)*43758.5453;return n-Math.floor(n);};
export const referenceTones=Array.from({length:paletteSize},(_,i)=>`ink-${i}`);

export function makeLandscape(width,height) {
  const marks=[],animated=[],clouds=[];
  // All thinning uses source coordinates, so resizing and theme changes never reshuffle ink.
  const scale=width/reference.width,top=height-reference.height*scale;
  for(let i=0;i<scenePoints.length;i+=5) {
    const x=scenePoints[i],y=scenePoints[i+1],color=scenePoints[i+2],glyph=scenePoints[i+3],layer=scenePoints[i+4];
    const u=x/reference.width,v=y/reference.height,seed=noise(x,y);
    const depth=v*v*(3-2*v);
    const village=layer===0 && ((u>.7 && u<.91 && v>.375 && v<.54) || (u>.12 && u<.445 && v>.378 && v<.448));
    const keep=village ? 1 : Math.min(.99,.86+depth*.11+(glyph===2 || glyph===3 ? .035 : 0));
    if(seed>keep)continue;
    const placedU=layer===5 ? u : narrowRiverX(u,v);
    const mark={x:placedU*width,y:top+y*scale,char:reference.glyphs[glyph],
      tone:referenceTones[color],size:7.8*scale,layer,scale,u,v,seed,field:layer===1};
    if(layer===4){clouds.push(mark);continue;}
    const bank=riverBanks(v),ground=v>.46 && bank && u<bank[0] && layer!==5;
    const cells=[];
    if(ground) {
      // Resample expanded ground into regular, staggered cells. Stretching cell positions
      // alone opens long paper-coloured gutters between otherwise intact wheat stalks.
      for(const cellX of groundCellXs(x,y))cells.push({...mark,x:cellX*scale,field:true});
    } else cells.push(mark);
    for(const cell of cells) {
      if(layer===1) {
        // Keep soil and stalks rooted. Only warm grain heads take the stronger wind motion.
        const ear=color<=13 || [17,19,25,26,27].includes(color);
        marks.push({...cell,layer:0,opacity:ear ? .55 : 1});
        if(ear)animated.push(cell);
      } else if(layer)animated.push(cell);else marks.push(cell);
    }
  }
  // A visible forked trunk and separated hanging sprays identify a willow, not a round crown.
  const wood=(x,y,char)=>animated.push({x:x*scale,y:top+y*scale,char,
    tone:referenceTones[40],size:7*scale});
  for(let y=517;y<588;y+=4) {
    const x=410+(588-y)*.055;
    wood(x,y,'|');wood(x+3,y,':');
  }
  for(const reach of [-51,-31,24,47])for(let step=0;step<12;step++) {
    const p=step/12;
    wood(414+reach*p,532-49*Math.sin(p*Math.PI/2),reach<0 ? '/' : '\\');
  }
  for(let branch=0;branch<26;branch++) {
    const spread=(branch-12.5)/12.5;
    const crownX=413+spread*57,crownY=479+Math.abs(spread)*14;
    const length=77+noise(branch,8)*24-Math.abs(spread)*10;
    for(let step=0;step<length;step+=4) {
      const p=step/length,x=crownX+spread*13*Math.sin(p*1.8)+Math.sin(p*3+branch)*2,y=crownY+step;
      animated.push({x:x*scale,y:top+y*scale,char:step%12===0 ? '|' : ':',
        tone:referenceTones[branch%3===0 ? 31 : branch%3===1 ? 35 : 32],
        size:7*scale,layer:5,scale,u:x/1448,v:y/1086,willowTip:p});
      if(step%8===0 && p<.9)animated.push({x:(x+2)*scale,y:top+(y+1)*scale,char:'%',
        tone:referenceTones[branch%2 ? 28 : 31],size:6*scale,layer:5,scale,u:x/1448,v:y/1086,willowTip:p});
    }
  }
  return {marks,animated,clouds};
}

export function drawLandscapeMarks(ctx,marks,colors,time=0,lighting) {
  ctx.globalAlpha=1;ctx.textBaseline='middle';
  let fontSize=0,tone='';
  for(const m of marks) {
    const size=Math.round(m.size*10)/10,s=m.scale||1;
    if(fontSize!==size){ctx.font=`700 ${size}px ${font}`;fontSize=size;}
    let x=m.x,y=m.y,ink=m.tone,char=m.char,alpha=m.opacity ?? 1;
    if(m.layer===1) {
      // Broad wave fronts travel through the grain; close ears bend more than distant ones.
      const depth=Math.max(0,(m.v-.55)/.45);
      const wave=Math.sin(m.u*18+m.v*28-time*.65)+Math.sin(m.u*7-time*.23)*.22;
      x+=wave*s*(1+depth*4.5)*(m.windFactor??1);y-=wave*s*(.3+depth*.85)*(m.windFactor??1);
    } else if(m.layer===2) {
      const water=waterMotion(m.u,m.v,time,m.seed);
      x+=water.x*s;y+=water.y*s;alpha=water.opacity;
      const strength=lighting ? Math.min(1,lighting.illumination+lighting.moonlight*.8) : 1;
      if(water.glint*strength>.3){
        ink=lighting?.warmLight>.35 ? 'waterWarm' : lighting?.moonlight>.15 ? 'waterMoon' : m.seed<.06 ? 'ink-15' : 'glint';
        char=m.seed<.025 ? '+' : '=';alpha=(.55+water.glint*.45)*strength;
      }
    } else if(m.layer===5) {
      const tip=m.willowTip ?? Math.max(0,Math.min(1,(m.v-(m.u<.5 ? .33 : .28))/.3));
      const sway=Math.sin(time*.48+m.u*9)+Math.sin(time*.27+m.u*21)*.25;
      x+=sway*tip*tip*s*5*(m.windFactor??1);y+=Math.sin(time*.48+m.u*9)*tip*s*.4*(m.windFactor??1);
    }
    ctx.globalAlpha=alpha;
    if(tone!==ink){ctx.fillStyle=colors[ink];tone=ink;}
    if(m.pixel) {
      const pixel=Math.min(3,Math.ceil(m.pixel));
      ctx.fillRect(Math.round(x),Math.round(y),pixel,pixel);
    } else if(char==='#' && (m.tone.startsWith('ink-') || m.referenceInk)) {
      const pixel=Math.min(3,size*.47);
      const inkWidth=m.field ? Math.min(3,size*.49) : pixel;
      ctx.fillRect(x,y-pixel*.5,inkWidth,pixel);
    } else {
      if(m.halo) {
        ctx.fillStyle=colors.deviceHalo;
        for(const [dx,dy] of [[-1,0],[1,0],[0,-1],[0,1]])ctx.fillText(char,x+dx,y+dy);
        ctx.fillStyle=colors[ink];
      }
      ctx.fillText(char,x,y);
      if(char==='=' && (m.tone.startsWith('ink-') || m.referenceInk))ctx.fillText(char,x,y+.35);
    }
  }
  ctx.globalAlpha=1;
}
