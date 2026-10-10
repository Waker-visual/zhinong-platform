const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),net=require('node:net'),assert=require('node:assert/strict'),{spawn,spawnSync}=require('node:child_process');
const {chromium}=require('../frontend/node_modules/playwright');
const root=path.resolve(__dirname,'..'), output=path.join(root,'.cache'), password=crypto.randomBytes(20).toString('hex');
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 const listener=net.createServer();await new Promise(r=>listener.listen(0,'127.0.0.1',r));const port=listener.address().port;await new Promise(r=>listener.close(r));
 const log=fs.openSync(path.join(output,'portfolio-browser-server.log'),'w');
 const javaSettings=spawnSync('java',['-XshowSettings:properties','-version'],{windowsHide:true,encoding:'utf8'}).stderr;
 const java=path.join(javaSettings.match(/java.home = ([^\r\n]+)/)[1].trim(),'bin',process.platform==='win32'?'java.exe':'java');
 const server=spawn(java,['-Djava.net.useSystemProxies=true','-Dfarm.llm.file-enabled=false','-jar','target/zhinong-platform-0.3.0.jar',`--server.port=${port}`,'--spring.datasource.url=jdbc:h2:mem:field-browser;DB_CLOSE_DELAY=-1','--spring.datasource.username=sa','--spring.datasource.password=','--farm.demo=true','--farm.demo-rich=true','--farm.research-history=true','--farm.demo-portfolio=true','--farm.demo-live=true','--farm.simulation.use-primary=true'],{cwd:path.join(root,'backend'),windowsHide:true,env:{...process.env,FARM_BOOTSTRAP_PASSWORD:password},stdio:['ignore',log,log]});
 let browser,page,token;const errors=[];const base=`http://127.0.0.1:${port}`;
 async function api(route,method='GET',body){const r=await fetch(base+'/api'+route,{method,headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},body:body===undefined?undefined:JSON.stringify(body)});const data=await r.json();assert.equal(r.ok,true,route+' '+JSON.stringify(data));return data;}
 try {
  for(let n=0;n<120;n++){if(await fetch(base+'/api/health').then(r=>r.ok).catch(()=>false))break;if(n===119)throw Error('Server readiness timeout');await sleep(500);}
  token=(await api('/auth/login','POST',{tenantCode:'demo-a',username:'admin',password})).token;
  const farms=await api('/farm-workspaces'),farm=farms.find(f=>f.name==='青禾设备联动演示场');assert.ok(farm);assert.equal(farms.length,5);
  browser=await chromium.launch({headless:true,channel:'msedge'});const context=await browser.newContext({viewport:{width:1500,height:1080},reducedMotion:'reduce'});
  await context.addInitScript(t=>sessionStorage.setItem('zhinong-session',t),token);
  page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
  await page.goto(base);await page.waitForTimeout(1500);await page.getByRole('button',{name:'当前农场',exact:true}).click();await page.getByRole('option',{name:farm.name,exact:true}).click();
  if(!await page.locator('.farm-workspace').count()) {
   await page.getByRole('button',{name:'农场地图 →',exact:true}).click();
  }
  await page.locator('[data-parcel-id]').first().waitFor();assert.equal(await page.locator('[data-parcel-id]').count(),9);
  await page.locator('.workspace-main-grid').scrollIntoViewIfNeeded();
  await page.locator('.farm-map-panel[data-load-state="ready"]').waitFor({timeout:30000});
  const spatial=await api(`/farms/${farm.id}/field-map`),parcel=spatial.parcels[0];
  await page.locator(`[data-parcel-id="${parcel.id}"]`).press('Enter');
  assert.equal(await page.getByLabel('当前小田块',{exact:true}).inputValue(),parcel.id);
  const assets=await api(`/assets?farmId=${farm.id}`),soil=assets.find(d=>d.code==='DEMO-CTRL-SOIL-P1');
  assert.ok(soil);await page.locator(`[data-device-id="${soil.id}"]`).click();
  await page.locator('.field-device-detail h4').filter({hasText:soil.name}).waitFor();
  const pump=assets.find(d=>d.deviceType==='PUMP');await page.locator('.field-device-list button').filter({hasText:pump.name}).click();
  assert.ok((await page.locator(`[data-device-id="${pump.id}"]`).getAttribute('class')).includes('chosen'));
  await page.locator('.workspace-main-grid').screenshot({path:path.join(output,'portfolio-overview.png')});
  const before=await page.locator(`[data-parcel-id="${parcel.id}"]`).getAttribute('d');
  await page.getByRole('button',{name:'Zoom in',exact:true}).click();await page.waitForTimeout(500);
  const after=await page.locator(`[data-parcel-id="${parcel.id}"]`).getAttribute('d');assert.notEqual(before,after);
  const mapBox=await page.locator('.farm-map').boundingBox();await page.mouse.move(mapBox.x+100,mapBox.y+150);await page.mouse.down();await page.mouse.move(mapBox.x+180,mapBox.y+185,{steps:8});await page.mouse.up();await page.waitForTimeout(400);
  await page.getByRole('button',{name:'全图',exact:true}).click();await page.waitForTimeout(500);await page.locator('.farm-map-panel[data-load-state="ready"]').waitFor({timeout:30000});
  await page.getByRole('tab',{name:'农机作业',exact:true}).click();
  await page.getByLabel('作业农机',{exact:true}).selectOption(assets.find(a=>a.machinery?.kind==='TRACTOR').id);await page.locator('.machine-profile').waitFor();assert.ok((await page.locator('.machine-profile').innerText()).includes('925520100123'));await page.getByRole('button',{name:'下一步：规划路线',exact:true}).click();await page.getByLabel('演示执行倍速',{exact:true}).selectOption('1');
  await page.getByRole('button',{name:'预览规划路线',exact:true}).click();await page.locator('.route-summary').waitFor();
  assert.ok(await page.locator('.field-route-arrow').count()>2);
  const tractor=assets.find(a=>a.machinery?.kind==='TRACTOR');assert.ok((await page.locator(`[data-device-id="${tractor.id}"]`).getAttribute('class')).includes('chosen'));
  await page.locator('.workspace-main-grid').screenshot({path:path.join(output,'portfolio-route.png')});

  await page.getByRole('button',{name:'返回调整路线',exact:true}).click();
  await page.getByLabel('路线规划',{exact:true}).selectOption('MANUAL');
  const svgPath=page.locator('[data-parcel-id="'+parcel.id+'"]');const b=await svgPath.boundingBox();
  await page.mouse.click(b.x+b.width*0.4,b.y+b.height*0.42);await page.mouse.click(b.x+b.width*0.6,b.y+b.height*0.6);
  await page.getByText('2 个路径点',{exact:true}).waitFor();
  await page.getByRole('button',{name:'撤销一点',exact:true}).click();await page.getByText('1 个路径点',{exact:true}).waitFor();
  await page.getByLabel('路线规划',{exact:true}).selectOption('AUTO');
  await page.getByRole('button',{name:'预览规划路线',exact:true}).click();await page.locator('.route-summary').waitFor();
  await page.getByRole('button',{name:'确认下发',exact:true}).click();
  await page.locator('.field-job-history article').first().waitFor();
  await page.getByRole('button',{name:'暂停',exact:true}).click();await page.getByRole('button',{name:'继续',exact:true}).waitFor();
  await page.locator(`[data-device-id="${tractor.id}"][title*="模拟任务已暂停"]`).waitFor();
  await page.getByRole('button',{name:'继续',exact:true}).click();await page.getByRole('button',{name:'暂停',exact:true}).waitFor();
  await page.getByRole('button',{name:'停止',exact:true}).click();await page.getByText('已停止 ·',{exact:false}).waitFor();
  await page.reload();await page.getByRole('button',{name:'农场地图 →',exact:true}).click();await page.locator('[data-parcel-id]').first().waitFor();
  await page.getByRole('tab',{name:'农机作业',exact:true}).click();await page.locator('.field-job-link').first().click();await page.waitForTimeout(300);
  assert.equal(await page.getByLabel('当前小田块',{exact:true}).inputValue(),parcel.id);assert.ok(await page.locator('.field-route-arrow').count()>2);
  await page.getByRole('tab',{name:'分区灌溉',exact:true}).click();
  await page.getByLabel('灌溉时长',{exact:true}).fill('10');await page.getByRole('button',{name:'启动模拟灌溉',exact:true}).click();
  await page.getByRole('button',{name:'停止模拟灌溉',exact:true}).waitFor();
  assert.equal(await page.locator('[data-zone-id]').count(),9);
  await page.locator('.workspace-main-grid').screenshot({path:path.join(output,'portfolio-irrigation.png')});
  await page.getByText('已完成 · 100%',{exact:true}).waitFor({timeout:22000});
  const done=await api(`/farms/${farm.id}/field-map`),water=done.jobs.find(j=>j.kind==='IRRIGATION');assert.equal(water.status,'COMPLETED');assert.ok(water.estimatedM3>0);assert.equal(water.measuredM3,null);
  await page.locator('.field-operations').screenshot({path:path.join(output,'portfolio-water-record.png')});
  const farmChecks=[];
  for(const [index,other] of farms.filter(f=>f.id!==farm.id).entries()) {
    await page.getByRole('button',{name:'当前农场',exact:true}).click();await page.getByRole('option',{name:other.name,exact:true}).click();
    const otherMap=await api(`/farms/${other.id}/field-map`),otherAssets=await api(`/assets?farmId=${other.id}`);
    assert.equal(otherMap.parcels.length,9);assert.equal(otherMap.zones.length,9);assert.equal(otherAssets.filter(a=>a.machinery).length,4);
    await page.locator(`[data-parcel-id="${otherMap.parcels[0].id}"]`).waitFor();
    await page.locator('.farm-map-panel[data-load-state="ready"]').waitFor({timeout:30000});
    await page.getByLabel('当前小田块',{exact:true}).selectOption(otherMap.parcels[0].id);
    await page.getByRole('tab',{name:'分区灌溉',exact:true}).click();assert.equal(await page.locator('[data-zone-id]').count(),9);
    await page.getByRole('tab',{name:'农机作业',exact:true}).click();
    await page.getByLabel('作业农机',{exact:true}).selectOption(otherAssets.find(a=>a.machinery?.kind==='DRONE').id);
    await page.getByRole('button',{name:'下一步：规划路线',exact:true}).click();await page.getByLabel('飞行高度',{exact:true}).fill('5');
    await page.getByRole('button',{name:'预览规划路线',exact:true}).click();await page.locator('.route-summary').waitFor();
    await page.locator('.workspace-main-grid').screenshot({path:path.join(output,`portfolio-farm-${index+1}.png`)});
    farmChecks.push({name:other.name,parcels:9,zones:9,machines:4,dronePreview:true});
  }
  await page.setViewportSize({width:390,height:844});await page.locator('.farm-map-panel').scrollIntoViewIfNeeded();await page.screenshot({path:path.join(output,'portfolio-mobile.png')});
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+2),false,'Mobile horizontal overflow');
  assert.deepEqual(errors,[]);
  const result={farms:farms.length,farmChecks,modelsPerFarm:4,manualRouteDrawingAndUndo:true,parcels:9,satelliteLoaded:true,zoomAndPan:true,mapAndDeviceLinkage:true,routePreview:true,machineryPauseResumeStop:true,persistedHistoryAfterReload:true,irrigationAutoStop:true,estimatedM3:water.estimatedM3,measuredM3:water.measuredM3,mobile:true,consoleErrors:errors};
  fs.writeFileSync(path.join(output,'portfolio-browser-result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
 }catch(e){if(page){await page.screenshot({path:path.join(output,'portfolio-failure.png'),fullPage:true});fs.writeFileSync(path.join(output,'portfolio-failure.txt'),await page.locator('body').innerText());}throw e;}
 finally{if(token)await api('/auth/logout','POST').catch(()=>{});await browser?.close();server.kill();fs.closeSync(log);}
})().catch(e=>{console.error(e.stack);process.exitCode=1;});
