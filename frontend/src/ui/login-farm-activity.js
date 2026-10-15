// Decorative machinery in the traced scene, not live farm telemetry or device control.
import { scheduledMode } from '../account/scheduled-theme.js';

const sprinklers=[[.16,.72],[.35,.79],[.6,.9]];
const drones=[{u:.25,v:.57,range:.12,size:1.45,spray:true},
  {u:.43,v:.75,range:.12,size:1.8,spray:true},
  {u:.65,v:.33,range:.1,size:1.1,spray:false}];
const sprayPressure=age=>{const p=Math.max(0,Math.min(1,age/1.1));return p*p*(3-2*p);};
const droneWindows=[[8,14],[22,25],[33,36]];

// Keep recent emission windows so a fast sunset stops births, not airborne droplets.
export function createFarmOperation() {
  let last=null,daytime=false,machineSeconds=0,windows=[];
  return {
    advance(time,nextDaytime) {
      if(last!==null && daytime)machineSeconds+=Math.max(0,time-last);
      if(nextDaytime!==daytime || last===null) {
        if(nextDaytime)windows.push({start:time,end:Infinity});
        else if(windows.length)windows.at(-1).end=time;
      }
      windows=windows.filter(window=>window.end>=time-2.4);
      daytime=nextDaytime;last=time;
    },
    read:()=>({daytime,machineSeconds,emissionWindows:windows.map(window=>({...window}))})
  };
}

export function makeFarmActivity(width,height,time,date,{night=false,reduced=false,operation}={}) {
  const marks=[],scale=width/1448,top=height-width*1086/1448;
  const add=(x,y,char,tone,size=7)=>marks.push({x:x*scale,y:top+y*scale,char,tone,size:size*scale});
  const t=reduced ? 0 : time;
  const daytime=(operation ? operation.daytime : scheduledMode(date)==='LIGHT' && !night) && !reduced;
  const alive=operation ? !reduced && operation.emissionWindows.some(window=>window.end>=t-2.4) : daytime;
  const bornDuringWork=born=>!operation || operation.emissionWindows.some(window=>born>=window.start && born<window.end);
  const activeSprinklers=sprinklers.map((_,i)=>alive && (t+i*11)%36<8);
  const watering=activeSprinklers.some(Boolean);
  for(const [i,[u,v]] of sprinklers.entries()) {
    const x=u*1448,y=v*1086;
    const head=(dx,dy,char,tone='machine',size=13)=>{
      add(x+dx,y+dy,char,tone,size);marks.at(-1).halo=true;
    };
    head(-7,2,'[_]');head(-2,-9,'||');head(-2,-20,'||');
    head(-11,-30,'-<o>-','machine',14);head(-4,-19,'+','patrol',12);
    const cycle=(t+i*11)%36;
    if(!activeSprinklers[i])continue;
    // Emit at the nozzle, then carry each drop through a complete umbrella trajectory.
    // Stop emitting early enough for the last drops to land before drones may spray.
    for(let jet=0;jet<14;jet++)for(let drop=0;drop<18;drop++) {
      const lifetime=2.4,age=(cycle+drop*lifetime/18+jet*.017)%lifetime;
      const born=cycle-age,pressure=sprayPressure(born);
      if(born<0 || born>=8-lifetime || pressure<=0 || (drop*7+jet*3)%19/19>.2+.8*pressure)continue;
      if(!bornDuringWork(t-age))continue;
      const p=age/lifetime,angle=jet*Math.PI*2/14+born*.12;
      const reach=.3+.7*pressure,radius=p*(142+Math.sin(jet*2.7)*8)*reach;
      const xx=x+Math.cos(angle)*radius;
      const yy=y-30-72*reach*4*p*(1-p)+p*28+Math.sin(angle)*radius*.22;
      add(xx,yy,drop%3 ? ':' : "'",drop%4 ? 'spray' : 'mist',9);
      marks.at(-1).halo=drop%5===0;
      marks.at(-1).effect='irrigation';
      marks.at(-1).opacity=Math.min(1,age/.12,(lifetime-age)/.4)*(.35+.65*pressure);
    }
  }
  for(const [i,drone] of drones.entries()) {
    const flight=t*.13+i*2.1;
    const x=(drone.u+Math.sin(flight)*drone.range)*1448;
    const y=(drone.v+Math.sin(flight*2)*.008)*1086;
    const put=(dx,dy,char,tone='machine',size=11,halo=true)=>{
      add(x+dx*drone.size,y+dy*drone.size,char,tone,size*drone.size);
      marks.at(-1).halo=halo;
    };
    const rotor=Math.sin(t*4+i)>0 ? '(=)' : '(-)';
    put(-31,-10,rotor);put(16,-10,rotor);put(-31,10,rotor);put(16,10,rotor);
    put(-15,-5,'\\');put(10,-5,'/');put(-15,5,'/');put(10,5,'\\');
    put(-9,0,'[##]');
    put(-7,13,drone.spray ? '[U]' : '(o)','patrol',9);
    if(!drone.spray) {
      const beat=t%2.4;
      if(reduced || beat<.55 || (beat>=1.2 && beat<1.75)) {
        put(-3,-9,'*',beat<1.2 ? 'beaconRed' : 'beaconOrange',14);
      }
    }
    if(!drone.spray || !alive || watering)continue;
    const cycle=t%36,window=droneWindows.find(([start,end])=>cycle>=start && cycle<end);
    if(!window)continue;
    const elapsed=cycle-window[0],duration=window[1]-window[0],lifetime=1.8;
    for(const side of [-1,1])for(let drop=0;drop<52;drop++) {
      const age=(elapsed+drop*lifetime/52+(side+1)*.013)%lifetime;
      const born=elapsed-age,pressure=sprayPressure(born);
      if(born<0 || born>=duration-lifetime || pressure<=0 || (drop*11+3)%17/17>.2+.8*pressure)continue;
      if(!bornDuringWork(t-age))continue;
      const p=age/lifetime,reach=.3+.7*pressure;
      const spread=Math.sin(drop*17.3)*p*(5+p*32)*reach;
      put(side*(12+p*17*reach)+spread,21+p*52*reach,
        drop%5 ? ':' : '+',drop%3 ? 'spray' : 'mist',8,drop%8===0);
      const mist=marks.at(-1),emissionFlight=(t-age)*.13+i*2.1;
      // Released mist stays behind the moving nozzle rather than following the aircraft rigidly.
      mist.x+=((drone.u+Math.sin(emissionFlight)*drone.range)*1448-x)*scale;
      mist.y+=((drone.v+Math.sin(emissionFlight*2)*.008)*1086-y)*scale;
      mist.effect='droneSpray';
      mist.opacity=Math.min(1,age/.12,(lifetime-age)/.35)*(.35+.65*pressure);
    }
  }
  // Eye-level parallel projection: front and side faces only; no visible roof plane.
  const machineTime=reduced ? 0 : operation?.machineSeconds ?? t;
  const phase=machineTime*Math.PI/32,direction=Math.cos(phase)>=0 ? 1 : -1;
  const hx=(.085+Math.sin(phase)*.03)*1448,hy=.514*1086,cell=2.5;
  // Lengthen the rear body by 28%, translating the rear wheel without stretching its circle.
  const bodyX=(x,y)=>x<17 ? x+(y<16 ? (x-17)*.28 : -2.24) : x;
  const paint=(px,py,tone,fine=false)=>{
    add(hx+(bodyX(px,py)-17)*cell*direction,hy+(py-21)*cell,'#',tone,cell);
    marks.at(-1).pixel=cell*scale*(fine ? .5 : 1);marks.at(-1).equipment='harvester';
  };
  const tones={k:'harvesterTire',b:'harvesterBody',l:'harvesterLight',
    g:'harvesterGlass',m:'harvesterMetal',w:'harvesterTrim'};
  const sprite=[
    '                 kwwwwwwwwwwwk',
    '                 kgggggggkgggk',
    '                 kgggggggkgggk',
    '                 kgggggggkgggk',
    '    kllllllllllkkkgggggggkgggk',
    '    kllllllllllkbkgggggggkgggk',
    '    klbbbbbbbllkbkgggggggkgggk',
    '    klbbbbbbbllkbkgggggggkgggk',
    '    klbbbbbbbllkbkmmmmmmmkmmmk',
    '    klbbbbbbbllkbklllllllkbbbk',
    '    klbbbbbbbllkbkbbbbbbbkbbbk',
    '    kllllllllllkbkbbbbbbbkbbbk',
    '    kbbbbbbbbbbkbkbbbbbbbkbbbk',
    '    kbbbbbbbbbbkkkkkkkkkkkkkkk',
    '    kbbbbbbbbbbbbbbbbbbbbkmmm',
    '    kkkkkkkkkkkkkkkkkkkkkmmmmm',
    '                            mm',
    '                             mm',
    '                              m',
    '                             mmmmmmmmmmm',
    '                             m m m m m m'
  ];
  for(const [row,line] of sprite.entries())for(let col=0;col<line.length;col++) {
    if(tones[line[col]])paint(col,row,line[col]==='k' && row<16 ? 'harvesterEdge' : tones[line[col]]);
  }
  // Fine mechanical details occupy half-size cells rather than broad icon outlines.
  const box=(x,y,w,h,tone)=>{
    for(let yy=y;yy<y+h;yy+=.5)for(let xx=x;xx<x+w;xx+=.5)paint(xx,yy,tone,true);
  };
  box(6,6.5,8,5,'harvesterEdge');box(6.5,7,7,4,'harvesterBody');
  for(let slot=0;slot<6;slot++)box(7+slot,8,.5,2.5,'harvesterMetal');
  box(5,12.5,10,.5,'harvesterTrim');box(12.5,6,1,1,'harvesterTrim');
  box(6,1.5,1,3,'harvesterMetal');box(5.5,1,2,.5,'harvesterEdge');
  box(18,1.5,6.5,6,'harvesterGlass');box(25.5,1.5,2,6,'harvesterGlass');
  box(18.5,2,4.5,.5,'harvesterTrim');box(19,2.5,.5,3,'harvesterTrim');
  box(23.5,5,1,2.5,'harvesterEdge');box(21,6,3,.5,'harvesterMetal');
  box(24,8.5,.5,4,'harvesterMetal');box(22.5,10,1,.5,'harvesterTrim');
  box(15,11,.5,6,'harvesterMetal');box(17,11,.5,6,'harvesterMetal');
  for(let rung=0;rung<4;rung++)box(15,12+rung*1.3,2.5,.5,'harvesterTrim');
  box(27,9,1,1,'harvesterTrim');box(27,12,1,1,'harvesterTrim');
  // Different wheel diameters, steel hubs and individual tread blocks.
  for(const [cx,cy,radius] of [[9,18.5,2.8],[22,18,4]]) {
    for(let y=-radius;y<=radius;y+=.5)for(let x=-radius;x<=radius;x+=.5) {
      const r=Math.hypot(x,y);
      if(r>radius)continue;
      paint(cx+x,cy+y,r<radius*.42 ? 'harvesterMetal' : 'harvesterTire',true);
    }
    for(let tread=0;tread<12;tread++) {
      const a=machineTime*1.6+tread*Math.PI/6;
      paint(cx+Math.cos(a)*(radius-.4),cy+Math.sin(a)*(radius-.4),'harvesterEdge',true);
    }
    paint(cx,cy,'harvesterTrim',true);
  }
  // The reel stands above the cutter: a full rim and radial bars rotate visibly through the crop.
  for(let rim=0;rim<36;rim++) {
    const a=rim*Math.PI/18;
    paint(31+Math.cos(a)*3.7,14+Math.sin(a)*3.7,'harvesterMetal',true);
    paint(35+Math.cos(a)*4.5,14+Math.sin(a)*4.5,'harvesterEdge',true);
  }
  for(let spoke=0;spoke<6;spoke++) {
    const a=machineTime*2.4+spoke*Math.PI/3;
    for(let r=0;r<=4;r+=.6) {
      paint(35+Math.cos(a)*r,14+Math.sin(a)*r,'harvesterMetal',true);
      marks.at(-1).part='reel';
    }
    for(let bar=0;bar<5;bar++) {
      paint(31+bar+Math.cos(a)*4,14+Math.sin(a)*4,'harvesterMetal',true);
      marks.at(-1).part='reel';
    }
    paint(35+Math.cos(a)*4.5,14+Math.sin(a)*4.5,'harvesterTrim');
    marks.at(-1).part='reel';
  }
  for(const center of [9,21]) {
    paint(center+Math.cos(machineTime*1.6)*1.2,18+Math.sin(machineTime*1.6)*1.2,'harvesterMetal');
  }

  // Threshed grain leaves the elevator behind the cab and falls into the rear hopper.
  if(!reduced)for(let grain=0;grain<16;grain++) {
    const p=(t*.48+grain/16)%1;
    if(operation && !bornDuringWork(t-p/.48))continue;
    const px=18-8*p,py=3-7*4*p*(1-p)+2*p;
    add(hx+(bodyX(px,py)-17)*cell*direction,hy+(py-21)*cell,
      grain%3 ? '.' : ':',grain%2 ? 'harvesterGrain' : 'harvesterTrim',4.5);
    marks.at(-1).effect='harvestGrain';
  }

  return marks;
}
