// Contours are measured in the 1448 × 1086 source drawing, not screen rectangles.
const trees=[
  {root:[525,477],scale:.70,offset:260,outline:[[432,379],[438,354],[458,350],[470,327],[492,321],[504,312],[514,326],[529,314],[547,335],[562,375],[586,398],[597,440],[589,477],[546,473],[519,445],[490,413],[466,420],[438,408]]},
  {root:[408,584],scale:.66,outline:[[304,495],[310,470],[323,447],[344,432],[366,412],[397,405],[419,415],[436,427],[454,424],[477,440],[496,470],[513,501],[520,537],[524,574],[512,609],[493,609],[478,587],[459,582],[435,570],[410,571],[390,585],[359,584],[330,571],[302,545]]},
  {root:[250,604],scale:.66,offset:-160,outline:[[170,563],[178,538],[190,524],[207,522],[213,496],[224,482],[232,508],[248,518],[262,498],[270,529],[292,532],[307,552],[326,558],[333,584],[314,603],[278,606],[239,603],[207,608],[182,595]]}
];
const foliage=new Set([16,18,19,23,27,28,29,30,31,32,33,34,35,36,37,38,39,40,41,42,43,44,45,46,47]);
const key=(x,y)=>`${Math.floor(x/4)},${Math.floor(y/4)}`;

function inside(x,y,outline) {
  let hit=false;
  for(let i=0,j=outline.length-1;i<outline.length;j=i++) {
    const [ax,ay]=outline[i],[bx,by]=outline[j];
    if((ay>y)!==(by>y) && x<(bx-ax)*(y-ay)/(by-ay)+ax)hit=!hit;
  }
  return hit;
}

export function arrangeScene(points) {
  const source=new Map(),result=new Map(),canopies=new Map();
  for(let i=0;i<points.length;i+=5) {
    const point=points.slice(i,i+5),[x,y]=point;
    source.set(key(x,y),point);result.set(key(x,y),point);
  }
  for(const tree of trees) {
    const leaves=[];
    // Reconstruct only the silhouette that will be exposed, following the existing field bands.
    const left=Math.floor(Math.min(...tree.outline.map(p=>p[0]))/4)*4+2;
    const right=Math.max(...tree.outline.map(p=>p[0]));
    const top=Math.floor(Math.min(...tree.outline.map(p=>p[1]))/4)*4+2;
    const bottom=Math.max(...tree.outline.map(p=>p[1]));
    for(let y=top;y<=bottom;y+=4)for(let x=left;x<=right;x+=4) {
      if(!inside(x,y,tree.outline) || (tree!==trees[1] && inside(x,y,trees[1].outline)))continue;
      const cell=key(x,y),original=source.get(cell);
      if(original && foliage.has(original[2]))leaves.push(original);
      result.delete(cell);
      const dx=tree.offset ?? (y<470 ? 272 : x>474 && y>540 ? 120 : -288);
      // Exposed ground uses a clear crop strip, avoiding duplicated bushes behind the willow.
      const bank=riverBanks(y/1086);
      const onField=!bank || x/1448<bank[0];
      const donorX=y>=470 && (tree===trees[1] || tree===trees[2]) && onField
        ? 66+((x-left)%80) : x+dx;
      const donor=source.get(key(donorX,y));
      if(donor)result.set(cell,[x,y,donor[2],donor[3],onField ? 0 : donor[4]]);
    }
    // Resample onto the same glyph grid: smaller trees must not become dense ink blobs.
    for(const [x,y,color,glyph] of tree===trees[2] ? [] : leaves) {
      const tx=Math.floor((tree.root[0]+(x-tree.root[0])*tree.scale)/4)*4+2;
      const ty=Math.floor((tree.root[1]+(y-tree.root[1])*tree.scale)/4)*4+2;
      // Keep the willow's sampled foliage in separated, drooping curtains instead of a solid crown.
      if(tree===trees[1] && (tx+Math.sin(ty*.035)*3)%12>6)continue;
      canopies.set(key(tx,ty),[tx,ty,color,glyph,5]);
    }
  }
  // Clear scattered shrubs along the combine's working strip, continuing the surrounding wheat.
  for(const [cell,[x,y,color]] of result) {
    if(x<42 || x>330 || y<490 || y>620 || !foliage.has(color))continue;
    const donorX=66+(x%80);
    const donor=[0,-8,8,-16,16].map(dy=>source.get(key(donorX,y+dy)))
      .find(p=>p && !foliage.has(p[2]) && p[4]!==2);
    if(donor)result.set(cell,[x,y,donor[2],donor[3],1]);
  }
  // Thin only the terminal reed belt beside the inlet; taper to zero at both ends.
  // Keep each root fixed and restore exposed cells from water or nearby grain, not bare paper.
  const reeds=[];
  for(const [x,y,color,glyph,layer] of source.values()) {
    if(x<=440 || x>=760 || !foliage.has(color) || layer===2)continue;
    const root=676+x*.13;
    if(y<=root-110 || y>=root)continue;
    const amount=.38*Math.sin((x-440)/320*Math.PI);
    const ty=Math.floor((root+(y-root)*(1-amount))/4)*4+2;
    if(ty===y)continue;
    const cell=key(x,y);
    result.delete(cell);
    const bankX=500+(y-600)*1.7;
    const donor=x>bankX
      ? [96,160,224].map(dx=>source.get(key(x+dx,y))).find(p=>p?.[4]===2)
      : source.get(key(x,y+116));
    if(donor)result.set(cell,[x,y,donor[2],donor[3],donor[4]===2 ? 2 : 1]);
    reeds.push([x,ty,color,glyph,0]);
  }
  for(const point of reeds)result.set(key(point[0],point[1]),point);
  // Remove the complete original vessel, its oar and reflection regardless of sampled colour/layer.
  const oldBoat=[[950,735],[982,727],[984,708],[1076,704],[1082,696],[1116,696],
    [1120,735],[1164,770],[1160,824],[1100,814],[1040,794],[976,775],[950,752]];
  for(let y=698;y<=822;y+=4)for(let x=950;x<=1166;x+=4) {
    const original=source.get(key(x,y));
    if(!inside(x,y,oldBoat) && ![3,6,7].includes(original?.[4]))continue;
    const donor=[160,-160,200,-200,240,-240].map(dx=>source.get(key(x+dx,y)))
      .find(p=>p && p[4]===2 && !inside(p[0],p[1],oldBoat));
    result.set(key(x,y),[x,y,donor?.[2] ?? 21,donor?.[3] ?? 4,2]);
  }
  return [...result.values(),...canopies.values()].flat();
}

// Trace the two banks in source coordinates; the right village bank stays fixed.
const banks=[[.43,.52,.52],[.47,.454,.532],[.5,.393,.515],[.53,.345,.504],
  [.56,.344,.529],[.59,.378,.598],[.62,.452,.698],[.65,.508,.847],
  [.68,.57,.997],[.71,.622,1.05],[.75,.737,1.17],[.8,.875,1.32],[.86,1.02,1.48],[1,1.55,1.55]];
export function riverBanks(v) {
  if(v<=banks[0][0] || v>=banks.at(-1)[0])return null;
  const i=banks.findIndex(p=>p[0]>=v),a=banks[i-1],b=banks[i],t=(v-a[0])/(b[0]-a[0]);
  return [a[1]+(b[1]-a[1])*t,a[2]+(b[2]-a[2])*t];
}
export function narrowRiverX(u,v) {
  const bank=riverBanks(v);
  if(!bank)return u;
  // A depth-independent displacement in the foreground keeps wheat stalks upright.
  // Only the low middle-distance transition changes with depth; the river absorbs the shift.
  const depth=Math.max(0,Math.min(1,(v-.57)/.11));
  const bend=Math.max(0,1-Math.abs(v-.55)/.09);
  const shift=.145*depth*depth*(3-2*depth)+.045*bend*bend*(3-2*bend);
  const fieldOffset=x=>{const p=Math.max(0,Math.min(1,x/.52));return shift*p*p*(3-2*p);};
  const [left,right]=bank,newLeft=left+fieldOffset(left);
  if(u<=left)return u+fieldOffset(u);
  if(u<right)return newLeft+(u-left)*(right-newLeft)/(right-left);
  return u;
}


export function groundCellXs(x,y) {
  const v=y/1086,stagger=(Math.floor(y/4)%2)*2;
  const left=narrowRiverX((x-2)/1448,v)*1448;
  const right=narrowRiverX((x+2)/1448,v)*1448;
  const first=Math.ceil((left-2-stagger)/4),end=Math.ceil((right-2-stagger)/4),cells=[];
  for(let cell=first;cell<end;cell++)cells.push(cell*4+2+stagger);
  return cells;
}
