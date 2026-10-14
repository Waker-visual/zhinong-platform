const SOURCE_WIDTH=1448,SOURCE_HEIGHT=1086,DEG=Math.PI/180;
const clamp=value=>Math.max(0,Math.min(1,value));
const noise=(x,y)=>{const n=Math.sin(x*127.1+y*311.7)*43758.5453;return n-Math.floor(n);};
const cellKey=(x,y,size=8)=>`${Math.floor(x/size)},${Math.floor(y/size)}`;
const density={clear:.38,cloudy:.7,overcast:.97};
// Thin clouds still need readable ink; optical density is only used for light blocking.
const cloudInk={clear:.76,cloudy:.91,overcast:.98};
const sunsetTransmission={clear:1,cloudy:.78,overcast:.08};
// Conservative ridge sampled from the reference drawing, before the trees and village.
const ridge=[254,250,250,258,250,254,254,266,282,294,306,250,294,250,250,250,274,250,298,274];
export const skyTones=['sky-cloud','sky-cloud-shadow','sky-cloud-rim','sky-cloud-warm',
  'sky-cloud-rose','sky-cloud-night','sky-sun','sky-sun-rim','sky-moon','sky-star','sky-venus'];

export function createSky(seed,sourceClouds) {
  const points=sourceClouds.map(mark=>({...mark,x:mark.u*SOURCE_WIDTH,y:mark.v*SOURCE_HEIGHT}));
  const cells=new Map(),seen=new Set(),groups=[];
  for(let i=0;i<points.length;i++) {
    const key=cellKey(points[i].x,points[i].y);
    if(!cells.has(key))cells.set(key,[]);
    cells.get(key).push(i);
  }
  // Eight-source-pixel connectivity preserves the actual traced islands of cloud ink.
  for(let i=0;i<points.length;i++) {
    if(seen.has(i))continue;
    const members=[i];seen.add(i);
    for(let at=0;at<members.length;at++) {
      const point=points[members[at]],cx=Math.floor(point.x/8),cy=Math.floor(point.y/8);
      for(let dx=-1;dx<=1;dx++)for(let dy=-1;dy<=1;dy++) {
        for(const index of cells.get(`${cx+dx},${cy+dy}`) || []) {
          if(seen.has(index) || Math.hypot(points[index].x-point.x,points[index].y-point.y)>8.01)continue;
          seen.add(index);members.push(index);
        }
      }
    }
    const marks=members.map(index=>points[index]);
    const left=Math.min(...marks.map(p=>p.x)),right=Math.max(...marks.map(p=>p.x));
    const top=Math.min(...marks.map(p=>p.y)),bottom=Math.max(...marks.map(p=>p.y));
    const depth=clamp(1-(top+bottom)/2/260),coverage=new Map();
    for(const point of marks) {
      const key=cellKey(point.x,point.y,16);
      if(!coverage.has(key))coverage.set(key,[]);
      coverage.get(key).push(point);
    }
    groups.push({id:groups.length,marks,left,right,top,bottom,depth,coverage,
      speed:.35+depth*.85,phase:noise(left+seed,top)*Math.PI*2});
  }
  const stars=Array.from({length:58},(_,i)=>({u:.05+noise(seed,i+1)*.9,
    v:noise(seed+4,i+8)*.82,seed:noise(i,seed+3)}));
  return {groups,stars};
}

function cloudPose(group,time) {
  const span=SOURCE_WIDTH+group.right-group.left+48;
  const raw=time*group.speed;
  // No individual glyph wraps: the complete bounding box must clear the right edge.
  const shift=raw-Math.floor((group.right+raw+24)/span)*span;
  return {shift,time};
}

function deform(group,x,pose) {
  const local=x-group.left,amplitude=1+group.depth*2.5;
  return {x:Math.sin(local*.014+pose.time*.08+group.phase)*amplitude,
    y:Math.sin(local*.018+pose.time*.05+group.phase)*amplitude*.65};
}

export function cloudCover(model,{u,v,motionSeconds=0,weather='clear',reduced=false}) {
  const x=u*SOURCE_WIDTH,y=v*SOURCE_HEIGHT,time=reduced ? 0 : motionSeconds;
  let transmission=1;
  for(const group of model.groups) {
    const pose=cloudPose(group,time),localX=x-pose.shift;
    if(localX<group.left-24 || localX>group.right+24 || y<group.top-24 || y>group.bottom+24)continue;
    const curl=deform(group,localX,pose),px=localX-curl.x,py=y-curl.y;
    const cx=Math.floor(px/16),cy=Math.floor(py/16);
    let nearest=Infinity;
    for(let dx=-1;dx<=1;dx++)for(let dy=-1;dy<=1;dy++) {
      for(const point of group.coverage.get(`${cx+dx},${cy+dy}`) || []) {
        nearest=Math.min(nearest,(point.x-px)**2+(point.y-py)**2);
      }
    }
    const cover=clamp(1-nearest/324)*density[weather];
    transmission*=1-cover;
  }
  return clamp(1-transmission);
}

function projection(body,width,height) {
  const scale=width/SOURCE_WIDTH,top=height-SOURCE_HEIGHT*scale;
  const horizon=top+280*scale;
  // North-clockwise azimuth: east is left, south is central, west is right.
  // This fixed corridor avoids both inscriptions without moving the body around them.
  const x=width*(.35+.44*(1+Math.sin((body.azimuth-180)*DEG))/2);
  const y=horizon-Math.sin(Math.max(0,body.altitude)*DEG)*Math.max(40,horizon-44);
  return {x,y,scale,top};
}

function ridgeHeight(x,width,height) {
  const scale=width/SOURCE_WIDTH,top=height-SOURCE_HEIGHT*scale;
  const index=Math.max(0,Math.min(ridge.length-1,x/width*ridge.length-.5));
  const left=Math.floor(index),right=Math.min(ridge.length-1,left+1);
  const skyline=ridge[left]+(ridge[right]-ridge[left])*(index-left);
  return top+(skyline-4)*scale;
}

function aboveRidge(x,y,width,height) {
  return y<ridgeHeight(x,width,height);
}

function reserved(x,y,width) {
  return (x<Math.min(340,width*.38) && y<230) || (x>width-Math.min(116,width*.18) && y<240);
}

function discPixels(body,part,width,height) {
  if(body.altitude<=0 || (part==='moon' && body.fraction<=0))return [];
  const center=projection(body,width,height),radius=part==='sun' ? 6 : 5,pixels=[];
  const cell=Math.max(1.6,Math.min(3.4,3.4*center.scale));
  // SunCalc's bright limb is anticlockwise from zenith after parallactic correction.
  // The fractional mask faces canvas right, so rotate by minus that angle minus 90°.
  // Its bright-side direction already contains waxing/waning; do not reflect by phase.
  const rotation=part==='moon' ? -(body.angle-(body.parallacticAngle || 0)+90)*DEG : 0;
  const cos=Math.cos(rotation),sin=Math.sin(rotation);
  for(let row=-radius;row<=radius;row++)for(let col=-radius;col<=radius;col++) {
    const squared=col*col+row*row;
    if(squared>radius*radius)continue;
    if(part==='moon') {
      const chord=Math.sqrt(radius*radius-row*row);
      if(col<(1-2*body.fraction)*chord || (chord===0 && body.fraction<1))continue;
    }
    const x=center.x+(col*cos-row*sin)*cell,y=center.y+(col*sin+row*cos)*cell;
    // Fade each glyph entirely above the ridge, rather than switching the whole disc.
    const horizon=clamp((ridgeHeight(x,width,height)-y)/cell)*clamp(body.altitude/.8);
    pixels.push({x,y,cell,horizon,rim:squared>radius*radius*.65});
  }
  return pixels;
}

function discTransmission(model,pixel,options) {
  const {x,y,horizon}=pixel,{width,height}=options,scale=width/SOURCE_WIDTH,top=height-SOURCE_HEIGHT*scale;
  if(x<0 || x>width || y<0 || y>height || reserved(x,y,width))return 0;
  return horizon*(1-cloudCover(model,{...options,u:x/width,v:(y-top)/(SOURCE_HEIGHT*scale)}));
}

// Water integrates the same illuminated pixels and moving-cloud transmission as each disc.
export function skyIllumination(model,options) {
  const {width,height,light}=options;
  const transmission=part=>{
    const pixels=discPixels(light[part],part,width,height);
    return pixels.length ? pixels.reduce((sum,pixel)=>sum+discTransmission(model,pixel,options),0)/pixels.length : 0;
  };
  const fusion=clamp(light.fusion || 0);
  const solar=transmission('sun')*(1-fusion)+fusion*.9,lunar=transmission('moon')*(1-fusion);
  return {illumination:clamp(light.daylight*(.42+.58*solar)),
    warmLight:clamp(light.warmLight*solar),moonlight:clamp(light.moonlight*lunar)};
}

export function makeSkyMarks(model,{width,height,light,weather='clear',motionSeconds=0,reduced=false}) {
  if(width<=0 || height<=0)return [];
  const marks=[],scale=width/SOURCE_WIDTH,top=height-SOURCE_HEIGHT*scale,time=reduced ? 0 : motionSeconds;
  const fusion=clamp(light.fusion || 0);
  const coverAt=(x,y)=>cloudCover(model,{u:x/width,v:(y-top)/(SOURCE_HEIGHT*scale),motionSeconds:time,weather,reduced});
  const sunX=projection(light.sun,width,height).x;
  const visible=(x,y)=>x>=0 && x<=width && y>=0 && y<=height && aboveRidge(x,y,width,height) && !reserved(x,y,width);
  const push=mark=>{if(mark.opacity>.012 && visible(mark.x,mark.y))marks.push(mark);};
  if(light.night>.01)for(const star of model.stars) {
    const x=star.u*width,y=24+star.v*Math.max(0,top+220*scale-24);
    const opacity=light.night*(.3+star.seed*.5)*(1-coverAt(x,y))*(weather==='overcast' ? .2 : 1);
    push({x,y,char:star.seed>.9 ? '+' : '.',tone:'sky-star',size:Math.max(5,6*scale),opacity,part:'star'});
  }
  for(const part of ['sun','moon']) {
    for(const pixel of discPixels(light[part],part,width,height)) {
      const {x,y,cell,rim}=pixel;
      const transmission=discTransmission(model,pixel,{width,height,motionSeconds:time,weather,reduced});
      const opacity=transmission*(part==='sun' ? .88 : .25+light.night*.7)*(1-fusion);
      push({x,y,char:rim ? '+' : '#',pixel:rim ? undefined : Math.min(2.4,cell*.7),
        tone:part==='moon' ? 'sky-moon' : rim ? 'sky-sun-rim' : 'sky-sun',size:Math.max(4,5*scale),opacity,part});
    }
  }
  // At year-tour speed the complete sparse paths replace racing individual discs.
  // Their projection and cloud/ridge clipping are shared with the real bodies.
  if(fusion>0)for(const part of ['sun','moon'])for(let i=1;i<29;i++) {
    const p=i/29,body={altitude:Math.sin(p*Math.PI)*(part==='sun' ? 55 : 42),azimuth:90+p*180};
    const {x,y}=projection(body,width,height);
    push({x,y,char:i%3===0 ? '-' : ':',tone:part==='sun' ? 'sky-sun-rim' : 'sky-moon',
      size:Math.max(4,5*scale),opacity:fusion*(part==='sun' ? .34 : .17)*(1-coverAt(x,y)),part:'year-orbit',body:part});
  }
  // Three restrained threads describe the rose belt above the antisolar horizon.
  if(light.twilight>.01) {
    const anti=projection({altitude:0,azimuth:light.sun.azimuth+180},width,height).x/width;
    for(let row=0;row<3;row++)for(let i=0;i<57;i++) {
      const p=(i-28)/28,u=anti+p*.145,x=u*width;
      const y=top+(214+row*5+7*p*p)*scale;
      const envelope=Math.sin((i+1)/58*Math.PI)**.7;
      push({x,y,char:row===1 ? '=' : i%3 ? ':' : '-',tone:'sky-venus',size:7*scale,
        opacity:light.twilight*.62*sunsetTransmission[weather]*envelope*(row===1 ? 1 : .55)*(1-coverAt(x,y)),part:'venus'});
    }
  }
  // Broken, overlapping short strokes form low sunward air, not ridge contour lines.
  // Every gap and stagger is fixed; only the shared physical light weight changes.
  if(light.warmLight>.01)for(let band=0;band<3;band++)for(let row=0;row<3;row++)for(let i=0;i<81;i++) {
    if((i+band*5+row*2)%18>13)continue;
    const p=(i-40)/40,x=sunX+p*width*.21+(row-1)*2.3*scale;
    const y=top+([222,203,178][band]+row*3.6+Math.sin(p*3.5+band*.8)*5)*scale;
    const envelope=Math.sin((i+1)/82*Math.PI)**.7;
    const opacity=light.warmLight*.94*sunsetTransmission[weather]*[1,.56,.23][band]*envelope*(row===1 ? 1 : .78);
    push({x,y,char:row===1 ? '=' : '-',
      tone:band>=(light.evening>0 ? 1 : 2) ? 'sky-cloud-rose' : 'sky-cloud-warm',size:8.8*scale,
      opacity:opacity*(1-coverAt(x,y)*.65),part:'atmosphere',band});
  }
  for(const group of model.groups) {
    const pose=cloudPose(group,time);
    for(const point of group.marks) {
      const curl=deform(group,point.x,pose),x=(point.x+pose.shift+curl.x)*scale,y=top+(point.y+curl.y)*scale;
      const local=(point.y-group.top)/Math.max(8,group.bottom-group.top);
      const towardSun=1-Math.min(1,Math.abs(x-sunX)/width);
      const altitude=(ridgeHeight(x,width,height)-y)/scale;
      const lowLight=clamp(1-altitude/180)**1.4;
      const warm=clamp(light.warmLight*(.62+.38*towardSun)*sunsetTransmission[weather]*lowLight);
      const edge=local<.3 || point.seed>.82;
      let tone=edge ? 'sky-cloud-rim' : 'sky-cloud';
      if(weather==='overcast' && !edge)tone='sky-cloud-shadow';
      const breath=.94+.06*Math.sin(time*.07+group.phase+(point.x-group.left)*.009);
      const opacity=clamp((.72+point.seed*.22)*cloudInk[weather]*breath),night=clamp(light.night);
      const mark={x,y,char:point.char,tone,size:7.8*scale,part:'cloud',cloudId:group.id};
      // Warm light follows whole traced cloud contours, not a changing random subset.
      const warmTone=local>(light.evening>0 ? .35 : .65) ? 'sky-cloud-rose' : 'sky-cloud-warm';
      push({...mark,opacity:opacity*(1-night)*(1-warm)});
      push({...mark,tone:warmTone,opacity:opacity*(1-night)*warm});
      push({...mark,tone:'sky-cloud-night',opacity:opacity*.66*night});
    }
  }
  return marks;
}
