// Run with the local login preview on 4273; uses no backend account.
const {chromium}=require('../../frontend/node_modules/playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const {pathToFileURL}=require('node:url');
const path=require('node:path');
const output='.cache/scene-review/year-cycle';
fs.mkdirSync(output,{recursive:true});
(async()=>{
  const {makeYearTour,yearTourSpeed}=await import(pathToFileURL(path.resolve('frontend/src/ui/login-year-tour.js')));
  const browser=await chromium.launch({channel:'msedge',headless:true});
  try {
    const page=await browser.newPage({viewport:{width:1440,height:960}}),errors=[];
    page.on('pageerror',error=>errors.push(error.message));
    await page.clock.install({time:new Date('2026-10-14T03:00:00Z')});
    await page.clock.pauseAt(new Date('2026-10-14T03:00:00Z'));
    await page.addInitScript(()=>{
      window.__yearDrawing={gradients:0,maxPixel:0,frames:0};
      for(const name of ['createLinearGradient','createRadialGradient']) {
        const original=CanvasRenderingContext2D.prototype[name];
        CanvasRenderingContext2D.prototype[name]=function(...args){window.__yearDrawing.gradients++;return original.apply(this,args);};
      }
      const clear=CanvasRenderingContext2D.prototype.clearRect;
      CanvasRenderingContext2D.prototype.clearRect=function(...args){window.__yearDrawing.frames++;return clear.apply(this,args);};
      const fill=CanvasRenderingContext2D.prototype.fillRect;
      CanvasRenderingContext2D.prototype.fillRect=function(x,y,w,h){window.__yearDrawing.maxPixel=Math.max(window.__yearDrawing.maxPixel,w,h);return fill.call(this,x,y,w,h);};
    });
    await page.goto('http://127.0.0.1:4273/');
    const year=page.getByRole('button',{name:'快进一年 60 秒',exact:true});
    const day=page.getByRole('button',{name:'快进一天 12 秒',exact:true});
    await year.waitFor();await page.clock.runFor(400);
    const storage=await page.evaluate(()=>JSON.stringify(localStorage));
    const startMs=await page.evaluate(()=>Date.now()),tour=makeYearTour(startMs);
    function wallAt(instant) {
      const i=tour.table.findIndex(item=>item.instantMs>=instant);
      if(i<1)return i===0?0:60000;
      const a=tour.table[i-1],b=tour.table[i];
      return 60000*(a.progress+(b.progress-a.progress)*(instant-a.instantMs)/(b.instantMs-a.instantMs));
    }
    const samples=[4,8,12,16,24,32,40,48,56,59.9].map(second=>({at:second*1000,name:`second-${second}`}));
    for(const stop of tour.festivalWindows)for(const [hours,period] of [[6,'day'],[18,'night']]) {
      samples.push({at:wallAt(stop.startMs+hours*3600000),name:`${stop.festivals.join('-')}-${period}`});
    }
    for(const [date,name] of [['2027-03-01','spring-blend'],['2027-06-15','summer'],['2027-09-01','autumn-blend']])
      samples.push({at:wallAt(Date.parse(`${date}T03:00:00Z`)),name});
    samples.sort((a,b)=>a.at-b.at);
    await year.click();let elapsed=0,stableSamples=0;
    for(const sample of samples) {
      const next=Math.round(sample.at);
      await page.clock.runFor(next-elapsed);elapsed=next;
      assert.equal(await page.locator('.login-scene-clock').getAttribute('data-tour-kind'),'year');
      if(yearTourSpeed(tour.table,elapsed/60000)/86400>=1.3) {
        assert.equal(await page.locator('html').getAttribute('data-theme'),'light');
        assert.equal(await page.locator('.login-field').evaluate(el=>getComputedStyle(el).backgroundColor),'rgb(251, 249, 238)');
        stableSamples++;
      }
      await page.screenshot({path:`${output}/${sample.name}.png`});
    }
    await page.clock.runFor(60400-elapsed);
    assert.equal(await page.locator('.login-scene-clock').getAttribute('data-mode'),'real');
    assert.equal(await page.locator('.login-scenario-date').count(),0);
    assert.match(await page.locator('[role=timer]').getAttribute('aria-label'),/2026-10-14/);
    assert.ok(stableSamples>=5);
    // Switching modes starts a fresh tour; returning cancels either one.
    await year.click();await page.clock.runFor(1500);await day.click();
    assert.equal(await page.locator('.login-scene-clock').getAttribute('data-tour-kind'),'day');
    await page.clock.runFor(12040);
    assert.equal(await page.locator('.login-scene-clock').getAttribute('data-mode'),'real');
    await year.click();await page.clock.runFor(2000);
    await page.getByRole('button',{name:'回到现在',exact:true}).click();
    assert.equal(await page.locator('.login-scene-clock').getAttribute('data-mode'),'real');
    // A hidden page consumes no journey time, including at the final boundary.
    await page.emulateMedia({reducedMotion:'reduce'});await year.click();
    await page.clock.runFor(59900);
    await page.evaluate(()=>{Object.defineProperty(document,'hidden',{configurable:true,get:()=>true});document.dispatchEvent(new Event('visibilitychange'));});
    const frames=await page.evaluate(()=>window.__yearDrawing.frames);
    await page.clock.runFor(2000);
    assert.equal(await page.evaluate(()=>window.__yearDrawing.frames),frames);
    assert.equal(await page.locator('.login-scene-clock').getAttribute('data-mode'),'tour');
    await page.evaluate(()=>{delete document.hidden;document.dispatchEvent(new Event('visibilitychange'));});
    await page.clock.runFor(140);
    assert.equal(await page.locator('.login-scene-clock').getAttribute('data-mode'),'real');
    assert.equal(await page.evaluate(()=>JSON.stringify(localStorage)),storage);
    assert.deepEqual(errors,[]);
    const drawing=await page.evaluate(()=>window.__yearDrawing);
    assert.equal(drawing.gradients,0);assert.ok(drawing.maxPixel<=3);
    console.log(JSON.stringify({durationSeconds:60,festivalStops:tour.festivalWindows.length,stableSamples,modeSwitch:true,hiddenAndReduced:true,storageUnchanged:true,drawing,errors}));
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
