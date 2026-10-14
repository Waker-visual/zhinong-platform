const DAY=86_400_000;
const smooth=value=>{const t=Math.max(0,Math.min(1,value));return t*t*(3-2*t);};
const seasons=['winter','spring','summer','autumn'];

function seasonBlend(civil) {
  const {year,month,day,hour=0,minute=0,second=0}=civil;
  const now=Date.UTC(year,month-1,day,hour,minute,second);
  // Civil-date arithmetic keeps the blend independent of UTC offsets and leap years.
  for(const [index,boundaryMonth] of [3,6,9,12].entries()) {
    const distance=(now-Date.UTC(year,boundaryMonth-1,1))/DAY;
    if(Math.abs(distance)<=20)return {from:seasons[index],to:seasons[(index+1)%4],amount:smooth((distance+20)/40)};
  }
  const season=month>=3&&month<=5?'spring':month>=6&&month<=8?'summer':month>=9&&month<=11?'autumn':'winter';
  return {from:season,to:season,amount:0};
}

export function yearVisualState(state,rawLight) {
  const fusion=state.mode==='tour' && state.tourKind==='year' ? smooth((state.speed/86400-.4)/.9) : 0;
  const light={...rawLight,fusion};
  for(const key of ['daylight','surfaceDaylight','night','twilight','warmLight','evening','moonlight']) {
    if(rawLight[key]===undefined)continue;
    const target=key==='daylight'||key==='surfaceDaylight' ? 1 : 0;
    light[key]=rawLight[key]+(target-rawLight[key])*fusion;
  }
  if(fusion>=.5)light.period='day';
  return {light,fusion,seasonBlend:seasonBlend(state.civil)};
}
