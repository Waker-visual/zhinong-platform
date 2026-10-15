// Run with the local Vite preview on 4273; no backend or real account is needed.
const {chromium}=require('../../frontend/node_modules/playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const output='.cache/scene-review/seasons';
fs.mkdirSync(output,{recursive:true});
(async()=>{
  const browser=await chromium.launch({channel:'msedge',headless:true});
  try {
    const page=await browser.newPage({viewport:{width:1440,height:960},timezoneId:'America/New_York'}),errors=[];
    page.on('pageerror',error=>errors.push(error.message));
    await page.clock.install({time:new Date('2026-10-14T03:00:00Z')});
    await page.clock.pauseAt(new Date('2026-10-14T03:00:00Z'));
    await page.addInitScript(()=>{
      window.__seasonDrawing={gradients:0,maxPixel:0};
      for(const name of ['createLinearGradient','createRadialGradient']) {
        const original=CanvasRenderingContext2D.prototype[name];
        CanvasRenderingContext2D.prototype[name]=function(...args){window.__seasonDrawing.gradients++;return original.apply(this,args);};
      }
      const original=CanvasRenderingContext2D.prototype.fillRect;
      CanvasRenderingContext2D.prototype.fillRect=function(x,y,w,h){window.__seasonDrawing.maxPixel=Math.max(window.__seasonDrawing.maxPixel,w,h);return original.call(this,x,y,w,h);};
    });
    await page.goto('http://127.0.0.1:4273/');
    await page.locator('.login-scenario-toggle').waitFor();await page.clock.runFor(400);
    const storage=await page.evaluate(()=>JSON.stringify(localStorage));
    await page.locator('.login-scenario-toggle').click();
    assert.equal(await page.getByRole('group',{name:'时令场景',exact:true}).getByRole('button').count(),8);
    await page.locator('.login-field canvas').click({position:{x:450,y:300}});
    assert.equal(await page.locator('#login-scenario-menu').count(),0);
    await page.locator('.login-scenario-toggle').click();
    await page.getByRole('button',{name:'春日',exact:true}).focus();
    await page.keyboard.press('Escape');
    assert.equal(await page.locator('#login-scenario-menu').count(),0);
    assert.equal(await page.locator('.login-scenario-toggle').evaluate(el=>el===document.activeElement),true);
    const dates={spring:'04-15',summer:'07-15',autumn:'10-15',winter:'01-15','mid-autumn':'09-25','spring-festival':'02-17','national-day':'10-01'};
    for(const [id,date] of Object.entries(dates)) {
      await page.clock.setSystemTime(new Date('2026-10-14T03:00:00Z'));
      await page.locator('.login-scenario-toggle').click();
      await page.locator(`[data-scenario="${id}"]`).click();
      await page.clock.runFor(450);
      assert.match(await page.locator('.login-scenario-date').innerText(),new RegExp(`2026-${date}`));
      assert.equal(await page.locator('#login-scenario-menu').count(),0);
      assert.doesNotMatch(await page.locator('.login-clock-copy').innerText(),/预览（|实时（|演示（/);
      assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
      await page.screenshot({path:`${output}/${id}-day.png`});
      await page.clock.setSystemTime(new Date('2026-10-14T13:00:00Z'));await page.clock.runFor(2800);
      assert.equal(await page.locator('html').getAttribute('data-theme'),'dark');
      await page.screenshot({path:`${output}/${id}-night.png`});
    }
    // The preview and tour use one date source; finishing the tour retains the chosen season.
    await page.locator('.login-clock-action[data-tour=day]').click();await page.clock.runFor(12_040);
    assert.equal(await page.locator('.login-scene-clock').getAttribute('data-mode'),'real');
    assert.match(await page.locator('.login-scenario-date').innerText(),/国庆/);
    await page.getByRole('button',{name:'回到现在',exact:true}).click();await page.clock.runFor(400);
    assert.equal(await page.locator('.login-scenario-date').count(),0);
    assert.match(await page.locator('[role=timer]').getAttribute('aria-label'),/2026-10-14/);
    // Automatic festival recognition works without opening the preview selector.
    await page.clock.setSystemTime(new Date('2020-10-01T13:00:00Z'));await page.clock.runFor(500);
    await page.screenshot({path:`${output}/overlapping-festivals.png`});
    await page.emulateMedia({reducedMotion:'reduce'});await page.clock.runFor(1100);
    const staticFrame=await page.locator('.login-field canvas').evaluate(c=>c.toDataURL());
    await page.clock.runFor(500);
    assert.equal(await page.locator('.login-field canvas').evaluate(c=>c.toDataURL()),staticFrame);
    await page.setViewportSize({width:900,height:900});await page.clock.runFor(1200);
    await page.screenshot({path:`${output}/narrow-desktop.png`,fullPage:true});
    await page.setViewportSize({width:390,height:844});
    assert.equal(await page.locator('.login-scenario-toggle').isVisible(),false);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
    assert.equal(await page.evaluate(()=>JSON.stringify(localStorage)),storage);
    assert.deepEqual(errors,[]);
    const drawing=await page.evaluate(()=>window.__seasonDrawing);
    assert.equal(drawing.gradients,0);assert.ok(drawing.maxPixel<=3);
    console.log(JSON.stringify({scenarios:7,dayAndNight:true,tourRetainsSelection:true,returnToActual:true,reduced:true,mobile:true,storageUnchanged:true,drawing,errors}));
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
