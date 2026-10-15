import { narrowRiverX, riverBanks } from './login-scene-layout.js';

const smooth=p=>{p=Math.max(0,Math.min(1,p));return p*p*(3-2*p);};

export function boatPose(time) {
  const travel=time*.3+20,returning=Math.floor(travel/100)%2===1;
  const phase=(travel%100)/100,progress=returning ? 1-phase : phase,v=.635+progress*.13;
  const [left,right]=riverBanks(v),bank=narrowRiverX(left,v);
  return {u:bank+(right-bank)*.56,v,scale:.58+progress*.65,
    opacity:smooth(progress/.06),progress,direction:returning ? -1 : 1,kind:returning ? 'cargo' : 'canopy'};
}

export function waterMotion(u,v,time,seed) {
  const bank=riverBanks(v),phase=(time*.16+Math.floor(v*1086/12)*.073+seed*.18)%1;
  let edge=0,slope=1;
  if(bank && u>bank[0] && u<bank[1]) {
    edge=Math.min(1,Math.min(u-bank[0],bank[1]-u)/((bank[1]-bank[0])*.14));
    const next=riverBanks(v+.002);
    if(next)slope=((next[0]+next[1])-(bank[0]+bank[1]))/.004;
  }
  const distance=(8+Math.max(0,v-.5)*24)*phase*edge;
  const y=distance*.75,target=riverBanks(v+y/1086),origin=narrowRiverX(u,v);
  let x=distance*Math.min(4,Math.max(0,slope));
  if(edge && target)x=(Math.max(narrowRiverX(target[0],v+y/1086),
    Math.min(target[1],origin+x/1448))-origin)*1448;
  return {x,y,
    opacity:.35+.65*Math.min(1,phase*9,(1-phase)*9),
    glint:edge && seed<.19 ? Math.pow(Math.max(0,Math.sin(u*35-v*26-time*1.2)),8) : 0};
}

export function makeBoatMarks(width,height,time,trace) {
  const pose=boatPose(time),unit=width/1448*pose.scale;
  const cx=pose.u*width,cy=height-width*1086/1448+pose.v*width*1086/1448;
  const marks=[],stroke=time*Math.PI*2/4.8,lean=Math.sin(stroke)*3;
  let part='wake';
  const add=(x,y,tone,char='#',pixel=2)=>marks.push({x:cx+(x-1048)*unit*pose.direction,
    y:cy+(y-760)*unit+Math.sin(time*.8)*unit*.6,char,tone,
    size:6*unit,pixel:char==='#' ? pixel*unit : undefined,opacity:pose.opacity,part});
  const line=(ax,ay,bx,by,tone)=>{
    const steps=Math.ceil(Math.hypot(bx-ax,by-ay)/1.8);
    for(let i=0;i<=steps;i++)add(ax+(bx-ax)*i/steps,ay+(by-ay)*i/steps,tone);
  };
  // A short broken wake stays behind the stern; it is drawn underneath the complete boat.
  for(let i=0;i<25;i++) {
    const p=i/25;
    for(const side of [-1,1]) {
      add(964-p*54,754+side*(4+p*12),i%3 ? 'ink-21' : 'glint',i%4 ? '-' : '=');
      marks.at(-1).opacity*=smooth((1-pose.progress)/.08);
    }
  }
  for(let row=0;row<5;row++)for(let x=990+row*6;x<1120-row*6;x+=8) {
    add(x+Math.sin(time*.9+row)*3,770+row*3,'ink-24',row%2 ? '-' : '=');
    marks.at(-1).opacity*=.55-row*.07;
  }
  // Small opaque cells join the sampled hull and woven canopy without a filled path.
  part='hull';
  for(let x=960;x<=1140;x+=2) {
    const p=(x-960)/180,curve=Math.sin(p*Math.PI),rim=738+p*10+curve*5;
    for(let y=rim-curve*7;y<=rim+curve*12;y+=2)
      add(x,y,y<rim ? 'ink-37' : pose.kind==='cargo' ? 'ink-38' : 'ink-43');
    add(x,rim,'ink-27');
  }
  if(pose.kind==='canopy') {
    part='canopy';
  // The barrel canopy recedes along the hull: woven roof, curved ribs and a dark near opening.
  for(let along=0;along<=64;along+=2)for(let arc=0;arc<=Math.PI;arc+=.065) {
    const x=1002+along+Math.cos(arc)*11,y=744+along*.07-Math.sin(arc)*28;
    add(x,y,along%10<2 ? 'ink-38' : Math.sin(arc)>.65 ? 'ink-27' : 'ink-37');
  }
  for(let y=723;y<749;y+=2)for(let x=1055;x<1078;x+=2)
    if(((x-1066)/11)**2+((y-748.5)/27)**2<1)add(x,y,'ink-43');
  for(let arc=0;arc<=Math.PI;arc+=.065)
    add(1066+Math.cos(arc)*11,748.5-Math.sin(arc)*28,'ink-17');
  part='boatDetail';
  for(const [x,y,color,glyph] of trace) {
    if(!(x>=1000 && x<=1056 && y>=723 && y<=742 && [17,19,25,26,27,30,37].includes(color)))continue;
    add(x,y,`ink-${color}`,'.:%#-='[glyph]);
  }
  } else {
    // The return vessel is an open wooden skiff with low woven baskets, not a mirrored canopy boat.
    part='cargo';
    for(const [bx,by] of [[1008,742],[1044,746]]) {
      for(let y=-14;y<=0;y+=2)for(let x=-10;x<=10;x+=2)
        if(Math.abs(x)<10+y*.14)add(bx+x,by+y,x%6===0 || y%6===0 ? 'ink-37' : 'ink-17');
      for(let arc=0;arc<Math.PI*2;arc+=.14)
        add(bx+Math.cos(arc)*8,by-14+Math.sin(arc)*3,'ink-27');
    }
    line(987,744,1067,752,'ink-27');
  }
  // The boatman has a continuous coat, planted legs and a straw hat; only his upper body leans.
  part='boatman';
  line(1091,738,1089,750,'ink-43');line(1098,739,1102,750,'ink-43');
  for(let y=718;y<=739;y+=2)for(let x=-5;x<=5;x+=2)
    add(1095+x+lean*(739-y)/21,y,pose.kind==='cargo' ? (x<0 ? 'ink-37' : 'ink-25') : (x<0 ? 'ink-34' : 'ink-23'));
  for(let y=711;y<719;y+=2)for(let x=-3;x<=3;x+=2)add(1095+lean+x,y,'ink-37');
  for(let y=704;y<=711;y+=2)for(let x=-(y-703);x<=y-703;x+=2)add(1095+lean+x,y,'ink-8');
  const handX=1103+lean,handY=730+Math.cos(stroke)*2;
  line(1097+lean,722,handX,handY,pose.kind==='cargo' ? 'ink-25' : 'ink-23');
  const angle=.8+Math.sin(stroke)*.32,length=60;
  const tipX=handX+Math.cos(angle)*length,tipY=handY+Math.sin(angle)*length;
  part='oar';
  line(handX-7,handY-7,tipX,tipY,'ink-37');
  for(let along=0;along<9;along+=2)for(let across=-2;across<=2;across+=2)
    add(tipX+Math.cos(angle)*along-Math.sin(angle)*across,
      tipY+Math.sin(angle)*along+Math.cos(angle)*across,'ink-38');
  part='splash';
  if(Math.cos(stroke)>0)for(let i=0;i<7;i++)add(tipX-10+i*4,tipY+7+Math.sin(i)*2,'glint',i%2 ? '-' : ':');
  return marks;
}
