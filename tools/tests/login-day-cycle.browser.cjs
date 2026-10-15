// Run against the local Vite preview: node tools/tests/login-day-cycle.browser.cjs
const {chromium}=require('../../frontend/node_modules/playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const output='.cache/scene-review/day-cycle';
fs.mkdirSync(output,{recursive:true});
(async()=>{
  const browser=await chromium.launch({channel:'msedge',headless:true});
  try {
    const context=await browser.newContext({viewport:{width:1440,height:960},timezoneId:'America/New_York'});
    const page=await context.newPage(),errors=[];
    page.on('pageerror',error=>errors.push(error.message));
    await page.clock.install({time:new Date('2026-10-14T03:00:00Z')});
    await page.clock.pauseAt(new Date('2026-10-14T03:00:00Z'));
    await page.addInitScript(()=>{
      localStorage.setItem('zhinong-appearance',JSON.stringify({themeMode:'DARK',accent:'FOREST'}));
      window.__sceneDrawing={gradients:0,paths:0,maxPixel:0,frames:0};
      const clear=CanvasRenderingContext2D.prototype.clearRect;
      CanvasRenderingContext2D.prototype.clearRect=function(...args){window.__sceneDrawing.frames++;return clear.apply(this,args);};
      for(const method of ['createLinearGradient','createRadialGradient']) {
        const original=CanvasRenderingContext2D.prototype[method];
        CanvasRenderingContext2D.prototype[method]=function(...args){window.__sceneDrawing.gradients++;return original.apply(this,args);};
      }
      const original=CanvasRenderingContext2D.prototype.fillRect;
      CanvasRenderingContext2D.prototype.fillRect=function(x,y,w,h){
        window.__sceneDrawing.maxPixel=Math.max(window.__sceneDrawing.maxPixel,w,h);return original.call(this,x,y,w,h);
      };
    });
    await page.goto('http://127.0.0.1:4273/');
    await page.locator('.login-clock-action[data-tour=day]').waitFor();
    await page.clock.runFor(300);
    assert.match(await page.locator('[role=timer]').getAttribute('aria-label'),/2026-10-14 11:00/);
    assert.equal(await page.locator('html').getAttribute('data-theme'),'light');
    await page.screenshot({path:`${output}/day.png`});
    const originalPreference=await page.evaluate(()=>localStorage.getItem('zhinong-appearance'));
    for(const [index,weather] of ['晴天','有云','阴天'].entries()) {
      await page.locator('.login-clock-action[data-tour=day]').click();
      assert.equal(await page.locator('.login-clock-period').getAttribute('title'),`${weather}情景`);
      for(let second=1;second<=11;second++) {
        await page.clock.runFor(1000);
        if(index===0 || second===3 || second===10)await page.screenshot({path:`${output}/tour-${index}-${second}.png`});
      }
      assert.equal(await page.locator('.login-scene-clock').getAttribute('data-mode'),'tour');
      await page.clock.runFor(1350);
      assert.equal(await page.locator('.login-scene-clock').getAttribute('data-mode'),'real');
    }
    await page.locator('.login-clock-action[data-tour=day]').click();
    await page.clock.runFor(2000);
    await page.getByRole('button',{name:'回到现在',exact:true}).click();
    assert.equal(await page.locator('.login-scene-clock').getAttribute('data-mode'),'real');
    assert.equal(await page.evaluate(()=>localStorage.getItem('zhinong-appearance')),originalPreference);
    // Existing visible field edits must not slow the twelve-second scene clock.
    await page.locator('.login-clock-action[data-tour=day]').click();
    await page.getByRole('textbox',{name:'租户代码',exact:true}).focus();
    await page.clock.runFor(12_150);
    assert.equal(await page.locator('.login-scene-clock').getAttribute('data-mode'),'real');
    await page.emulateMedia({reducedMotion:'reduce'});
    await page.clock.runFor(375);
    await page.locator('.login-clock-action[data-tour=day]').click();
    await page.clock.runFor(12_040);
    assert.equal(await page.locator('.login-scene-clock').getAttribute('data-mode'),'real');
    await page.clock.runFor(1300);
    const before=await page.locator('.login-field canvas').evaluate(c=>c.toDataURL());
    await page.clock.runFor(500);
    assert.equal(await page.locator('.login-field canvas').evaluate(c=>c.toDataURL()),before);
    await page.setViewportSize({width:390,height:844});
    assert.equal(await page.locator('.login-scene-clock').isVisible(),false);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
    await page.screenshot({path:`${output}/mobile.png`});
    await page.emulateMedia({reducedMotion:'no-preference'});
    for(const [width,height] of [[900,900],[1920,700]]) {
      await page.setViewportSize({width,height});await page.clock.runFor(300);
      const size=await page.locator('.login-field canvas').boundingBox();
      assert.ok(size.height>=size.width*.75+150);
      await page.screenshot({path:`${output}/size-${width}.png`,fullPage:true});
    }
    // Drive the visibility event consumed by the component at the end boundary.
    await page.locator('.login-clock-action[data-tour=day]').click();
    await page.clock.runFor(11_900);
    await page.evaluate(()=>{
      Object.defineProperty(document,'hidden',{configurable:true,get:()=>true});
      document.dispatchEvent(new Event('visibilitychange'));
    });
    const hiddenFrames=await page.evaluate(()=>window.__sceneDrawing.frames);
    await page.clock.runFor(3000);
    assert.equal(await page.evaluate(()=>window.__sceneDrawing.frames),hiddenFrames);
    assert.equal(await page.locator('.login-scene-clock').getAttribute('data-mode'),'tour');
    await page.evaluate(()=>{
      delete document.hidden;document.dispatchEvent(new Event('visibilitychange'));
    });
    await page.clock.runFor(200);
    assert.equal(await page.locator('.login-scene-clock').getAttribute('data-mode'),'real');
    // Exercise the real Vue unmount hook, without requiring backend authentication.
    await page.evaluate(()=>document.querySelector('#app').__vue_app__.unmount());
    assert.equal(await page.locator('html').getAttribute('data-theme'),'dark');
    assert.equal(await page.evaluate(()=>localStorage.getItem('zhinong-appearance')),originalPreference);
    const finalFrames=await page.evaluate(()=>window.__sceneDrawing.frames);
    await page.clock.runFor(1500);
    assert.equal(await page.evaluate(()=>window.__sceneDrawing.frames),finalFrames);
    assert.deepEqual(errors,[]);
    const drawing=await page.evaluate(()=>window.__sceneDrawing);
    assert.equal(drawing.gradients,0);assert.ok(drawing.maxPixel<=3);
    console.log(JSON.stringify({cycles:3,durationSeconds:12,actualFarmZone:true,preservedPreference:true,reducedMotion:true,hiddenPause:true,unmountCleanup:true,mobileOverflow:false,drawing,errors}));
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
