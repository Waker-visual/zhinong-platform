import { narrowRiverX, riverBanks } from './login-scene-layout.js';

export const festivalTones=['festival-red','festival-gold','festival-wood','festival-cloth','festival-ink'];
const clamp=value=>Math.max(0,Math.min(1,value ?? 0));
const lanternAnchors=[[1061,458],[1137,458]];
const people=['0011100','0011100','0001000','0111110','1001001','0001000','0011100','0010100','0110110'];

export function makeFestivalMarks(width,height,{festivals=[],light={},motionSeconds=0,festivalSeconds=motionSeconds,reduced=false}={}) {
  const active=new Set(festivals),marks=[],scale=width/1448,top=height-1086*scale;
  const time=reduced ? 0 : motionSeconds,night=clamp(light.night);
  const add=(x,y,tone,part,{char='#',opacity=1,pixel=1.2,...extra}={})=>{
    const size=Math.min(3,Math.max(.5,pixel*scale));
    const mark={x:narrowRiverX(x/1448,y/1086)*width,y:top+y*scale,
      char,tone,part,size,pixel:size,opacity,...extra};
    const edge=Math.ceil(size);
    if(mark.x<0 || mark.y<0 || mark.x+edge>width || mark.y+edge>height)return;
    if(mark.x<320 && mark.y<220)return;
    if(mark.x>width-70 && mark.y<180)return;
    marks.push(mark);
  };
  const line=(x1,y1,x2,y2,tone,part,options)=>{
    const count=Math.max(1,Math.ceil(Math.hypot(x2-x1,y2-y1)/1.4));
    for(let i=0;i<=count;i++)add(x1+(x2-x1)*i/count,y1+(y2-y1)*i/count,tone,part,options);
  };
  const lantern=(x,y,index)=>{
    const part='festival-lantern',anchor=`${x},${y}`,sway=Math.sin(time*.45+index*1.4)*1.1;
    const put=(px,py,tone,options)=>add(px,py,tone,part,{anchor,...options});
    const stroke=(x1,y1,x2,y2,tone)=>line(x1,y1,x2,y2,tone,part,{anchor});
    stroke(x,y-5,x+sway,y,'festival-wood');
    x+=sway;
    for(let i=0;i<28;i++) {
      const angle=i*Math.PI*2/28;
      put(x+Math.cos(angle)*6.5,y+8+Math.sin(angle)*8,'festival-red',{pixel:1.8});
    }
    for(const rib of [-3,0,3])for(let yy=2;yy<=14;yy+=2) {
      put(x+rib*(.7+Math.sin(yy/16*Math.PI)*.3),y+yy,rib===0 ? 'festival-gold' : 'festival-red',{opacity:.8+night*.2});
    }
    stroke(x-3,y,x+3,y,'festival-gold');
    stroke(x,y+16,x,y+21,'festival-red');
    put(x,y+22,'festival-gold');
  };

  // Shared eaves: coincident lunar holidays never hang a second lantern on the same hook.
  if(active.has('mid-autumn') || active.has('spring-festival')) {
    lanternAnchors.forEach(([x,y],index)=>lantern(x,y,index));
  }
  if(active.has('spring-festival')) {
    [[185,434],[225,434],[347,440],[389,440],[1185,499],[1254,499]]
      .forEach(([x,y],index)=>lantern(x,y,index+2));
  }

  if(active.has('mid-autumn')) {
    // Lantern poles stand just beyond the right riverbank; only their reflections enter water.
    for(const [index,baseY] of [594,620,646,672].entries()) {
      const baseX=riverBanks(baseY/1086)[1]*1448+16;
      line(baseX,baseY-31,baseX,baseY,'festival-wood','festival-river-pole');
      lantern(baseX,baseY-27,index+8);
      for(let row=0;row<9;row++) {
        const y=baseY+2+row*2.6,bank=riverBanks(y/1086);
        const x=Math.min(baseX-24,bank[1]*1448-10)+Math.sin(time*.65+row*.8+index)*2.5;
        const half=2+(row%3)*1.8;
        for(let dx=-half;dx<=half;dx+=2) {
          add(x+dx,y+Math.sin(time*.45+row)*.5,row%3 ? 'festival-gold' : 'festival-red','festival-reflection',
            {char:'-',pixel:1,opacity:(.1+night*.48)*(1-row/12)});
        }
      }
    }
    // The small court between the two right-hand houses; its table stays off their walls.
    const x=1154,y=584;
    for(let i=0;i<40;i++) {
      const angle=i*Math.PI*2/40;
      add(x+Math.cos(angle)*18,y+Math.sin(angle)*5,'festival-wood','festival-table',{pixel:1.8});
    }
    line(x-12,y+2,x-12,y+15,'festival-wood','festival-table');
    line(x+12,y+2,x+12,y+15,'festival-wood','festival-table');
    line(x-12,y+10,x+12,y+10,'festival-wood','festival-table');
    for(const dx of [-8,7]) {
      line(x+dx-1,y-3,x+dx-1,y,'festival-ink','festival-cup');
      line(x+dx-1,y,x+dx+2,y,'festival-ink','festival-cup');
      line(x+dx+2,y,x+dx+2,y-3,'festival-ink','festival-cup');
    }
    for(const dx of [-2,1])add(x+dx,y-1,'festival-gold','festival-cup');
    for(const [person,[px,py]] of [[1125,577],[1178,577],[1150,559],[1147,601]].entries()) {
      people.forEach((row,r)=>[...row].forEach((bit,c)=>{
        if(bit==='1')add(px+c*1.8,py+r*1.8,r<3 ? 'festival-ink' : 'festival-cloth','festival-person',{person,pixel:1.8});
      }));
      line(px,py+16,px+11,py+16,'festival-wood','festival-person',{person});
      line(px+1,py+16,px+1,py+21,'festival-wood','festival-person',{person});
    }
  }

  if(active.has('spring-festival')) {
    // Paired narrow scrolls frame the existing front-house doorway, not its windows.
    for(const [doorX,doorY,doorWidth,doorHeight] of [[348,449,17,14],[1101,534,22,21],[1211,546,20,20]]) {
      for(const x of [doorX,doorX+doorWidth]) {
        for(let y=doorY;y<=doorY+doorHeight;y+=1.5) {
          add(x,y,'festival-red','festival-couplet',{pixel:1.8});
          add(x+1.5,y,'festival-red','festival-couplet',{pixel:1.2});
        }
        for(let y=doorY+3;y<doorY+doorHeight;y+=5) {
          add(x+1,y,'festival-gold','festival-couplet');
          add(x+1,y+1.5,'festival-gold','festival-couplet');
        }
      }
      line(doorX,doorY-2,doorX+doorWidth+2,doorY-2,'festival-red','festival-couplet',{pixel:1.8});
      for(let x=doorX+5;x<doorX+doorWidth;x+=4)add(x,doorY-2,'festival-gold','festival-couplet');
    }

    // Twilight fades the existing burst to zero before its marks are omitted.
    const darkness=clamp((night-.25)/.55),nightVisibility=darkness*darkness*(3-2*darkness);
    // Reset festivalSeconds on entering a Spring Festival night; other movement keeps its clock.
    // Fast opening blooms retain slow falling tails and a long quiet interval between groups.
    if(nightVisibility>0 && !reduced)for(const [index,[cx,cy,radiusMax]] of [[930,175,43],[1110,145,51],[1240,205,36]].entries()) {
      const age=((festivalSeconds+.25-index*.28)%18+18)%18,part='festival-firework';
      if(age>=4.6)continue;
      if(age<.45) {
        const y=cy+62*(1-age/.45);
        for(let i=0;i<7;i++)add(cx,y+i*3,'festival-gold',part,{char:'.',burst:index,pixel:2,opacity:nightVisibility*(1-i/8)*Math.sin(age/.45*Math.PI/2)});
      } else {
        const bloomAge=age-.45,p=bloomAge/4.15,opening=clamp(bloomAge/.7);
        const radius=7+radiusMax*(1-(1-opening)**3);
        const opacity=nightVisibility*Math.min(1,bloomAge/.12)*(1-p)**.85;
        for(let ray=0;ray<14;ray++) {
          const angle=ray*Math.PI/7+index*.19;
          for(let trail=0;trail<4;trail++) {
            const reach=radius*(1-trail*.065);
            add(cx+Math.cos(angle)*reach,cy+Math.sin(angle)*reach+p*p*8,
              ray%3 ? 'festival-gold' : 'festival-red',part,
              {char:trail ? '.' : '+',burst:index,opacity:opacity*(1-trail*.2),pixel:trail ? 1.5 : 2.6});
          }
        }
      }
    }
  }

  if(active.has('national-day')) {
    for(const [flagId,[x,y]] of [[997,564],[1228,590]].entries()) {
      const w=36,h=24,part='festival-flag';
      const wave=dx=>Math.sin(time*.55+dx*.14)*1.8*dx/w;
      const flag=(dx,dy,tone,options)=>add(x+dx,y+dy+wave(dx),tone,part,options);
      line(x,y,x,y+46,'festival-wood','festival-pole');
      for(let dx=1;dx<=w;dx+=1.5) {
        flag(dx,0,'festival-red');flag(dx,h,'festival-red');
      }
      for(let dy=0;dy<=h;dy+=1.5)flag(w,dy,'festival-red');
      const stars=[[8,8,4.8],[16,3,2],[20,7,2],[20,13,2],[16,18,2]];
      for(let dx=2;dx<w;dx+=2.5)for(let dy=2;dy<h;dy+=2.5) {
        if(!stars.some(([sx,sy,r])=>Math.hypot(dx-sx,dy-sy)<r+1))flag(dx,dy,'festival-red',{opacity:.8,pixel:1});
      }
      stars.forEach(([sx,sy,r],star)=>{
        const points=Array.from({length:10},(_,i)=>{
          const angle=-Math.PI/2+i*Math.PI/5,radius=i%2 ? r*.43 : r;
          return [sx+Math.cos(angle)*radius,sy+Math.sin(angle)*radius];
        });
        for(let i=0;i<points.length;i++) {
          const [ax,ay]=points[i],[bx,by]=points[(i+1)%points.length];
          const steps=Math.max(1,Math.ceil(Math.hypot(bx-ax,by-ay)));
          for(let step=0;step<steps;step++) {
            const dx=ax+(bx-ax)*step/steps,dy=ay+(by-ay)*step/steps;
            add(x+dx,y+dy+wave(dx),'festival-gold','festival-star',{star,flag:flagId,pixel:1});
          }
        }
      });
    }
    for(const [left,right,y] of [[340,390,444],[1048,1145,460],[1182,1260,500]]) {
      line(left,y,right,y,'festival-wood','festival-eave-light',{opacity:.65,pixel:.8});
      for(let x=left+4;x<right;x+=13) {
        line(x,y,x,y+3,'festival-wood','festival-eave-light');
        add(x-1,y+4,'festival-gold','festival-eave-light',{pixel:2,opacity:.75+night*.25});
        add(x+1,y+4,'festival-gold','festival-eave-light',{pixel:1.5,opacity:.75+night*.25});
      }
    }
  }
  if(active.has('spring-festival') || active.has('national-day')) {
    for(const [lx,ly] of [[972,586],[1046,603],[1268,605]]) {
      line(lx,ly,lx,ly+20,'festival-wood','festival-gate-light');
      line(lx-4,ly,lx+4,ly,'festival-wood','festival-gate-light');
      for(const [dx,dy] of [[-2,2],[0,2],[2,2],[-2,4],[0,4],[2,4],[-2,6],[0,6],[2,6]]) {
        add(lx+dx,ly+dy,'festival-gold','festival-gate-light',{opacity:.55+night*.45});
      }
    }
  }
  return marks;
}
