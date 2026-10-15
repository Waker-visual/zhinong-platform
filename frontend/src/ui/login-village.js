import { narrowRiverX } from './login-scene-layout.js';

// Roof ridges and existing window openings, measured in the 1448 × 1086 drawing.
const roofs=[{x:365,y:411,delay:0},{x:1100,y:420,delay:.13},{x:1238,y:451,delay:.27}];
const windows=[
  {x:351,y:450,w:4,h:6,late:0},{x:615,y:445,w:4,h:7,late:.08,bedtime:23.5},
  {x:1082,y:475,w:5,h:6,late:.14,bedtime:23},{x:1115,y:476,w:5,h:7,late:.2,bedtime:22.5},
  {x:1200,y:520,w:8,h:12,late:0},{x:1266,y:464,w:4,h:7,late:.26,bedtime:22},
];
const clamp=value=>Math.max(0,Math.min(1,value ?? 0));

export function createVillage() {
  let time=0,light={},particles=[],nextBirth=null,serial=0;
  return {
    advance({motionSeconds,light:nextLight,reduced=false}) {
      time=motionSeconds;light=nextLight;
      particles=particles.filter(p=>time-p.born<p.life);
      if(reduced) {particles=[];nextBirth=null;return;}
      if(!nextBirth)nextBirth=roofs.map(roof=>time+roof.delay);
      const evening=clamp(light.evening);
      roofs.forEach((roof,index)=>{
        if(evening<.08) {nextBirth[index]=time+roof.delay;return;}
        // A resumed scene never backfills expired smoke from a long hidden interval.
        nextBirth[index]=Math.max(nextBirth[index],time-4);
        while(nextBirth[index]<=time) {
          const seed=(serial++*0.61803398875)%1;
          const particle={roof,index,born:nextBirth[index],life:2.5+seed*1.2,seed};
          if(time-particle.born<particle.life)particles.push(particle);
          nextBirth[index]+=.18/Math.max(.3,evening);
        }
      });
    },
    marks(width,height) {
      const scale=width/1448,top=height-1086*scale,marks=[];
      const place=(x,y)=>({x:narrowRiverX(x/1448,y/1086)*width,y:top+y*scale});
      const darkness=clamp(light.night),evening=clamp(light.evening);
      for(const window of windows) {
        // Civil hour keeps homes lit after twilight; without it use reachable dusk thresholds.
        const awake=window.late===0 ? 1 : Number.isFinite(light.hour)
          ? (light.hour<12 ? 0 : clamp((window.bedtime-light.hour)/.8))
          : clamp((evening-window.late)*6);
        if(darkness<.08 || awake<=0)continue;
        const opacity=darkness*awake*(window.late===0 ? .72 : .85);
        for(let y=0;y<window.h;y+=3)for(let x=0;x<window.w;x+=3) {
          marks.push({...place(window.x+x,window.y+y),char:'+',tone:'villageWindow',
            size:3*scale,pixel:1.4*scale,opacity,part:'window'});
        }
      }
      for(const particle of particles) {
        const age=time-particle.born,p=age/particle.life;
        if(age<=0 || p>=1)continue;
        const {roof,seed,index}=particle;
        const root=place(roof.x,roof.y);
        const drift=age*(2.8+index*.25)+Math.sin(age*1.5+index)*p*3;
        const rise=age*(9+seed*2);
        const opacity=Math.min(1,age/.2)*(1-p)*.58;
        for(let strand=0;strand<3;strand++) {
          marks.push({x:root.x+(drift+(strand-1)*(1+p*3))*scale,
            y:root.y-(rise+Math.sin(strand+seed*4)*p*2)*scale,
            char:strand===1 ? ':' : '.',tone:'villageSmoke',size:(6+p*2)*scale,
            opacity:opacity*(strand===1 ? 1 : .62),part:'smoke'});
        }
      }
      return marks;
    },
  };
}
