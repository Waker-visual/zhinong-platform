// AI/browser integration on a disposable H2 server. Private model configuration is disabled.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const net = require('node:net');
const assert = require('node:assert/strict');
const { spawn, spawnSync } = require('node:child_process');
const { chromium } = require('../frontend/node_modules/playwright');
const { holdResponse, verifyRefresh, verifyAnswerProtection, waitRefreshIdle } = require('./ai_refresh_checks.cjs');
const root = path.resolve(__dirname, '..'), output = path.join(root, '.cache');
const serverJar = process.argv[2] || 'target/zhinong-platform-0.3.0.jar';
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
(async () => {
  const { normalize } = await import('../frontend/src/mobile/client.mjs');
  fs.mkdirSync(output, { recursive: true });
  const listener = net.createServer();
  await new Promise(resolve => listener.listen(0, '127.0.0.1', resolve));
  const port = listener.address().port; await new Promise(resolve => listener.close(resolve));
  const password = crypto.randomBytes(20).toString('hex');
  const log = fs.openSync(path.join(output, 'ai-browser-server.log'), 'w');
  const settings = spawnSync('java', ['-XshowSettings:properties', '-version'], { windowsHide: true, encoding: 'utf8' }).stderr;
  const java = path.join(settings.match(/java.home = ([^\r\n]+)/)[1].trim(), 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
  const server = spawn(java, ['-Dfarm.llm.file-enabled=false', '-jar', serverJar,
    `--server.port=${port}`, '--spring.web.resources.static-locations=file:../frontend/dist/',
    '--spring.datasource.url=jdbc:h2:mem:ai-browser;DB_CLOSE_DELAY=-1', '--spring.datasource.username=sa', '--spring.datasource.password=',
    '--farm.demo=true', '--farm.demo-rich=true', '--farm.demo-portfolio=true', '--farm.research-history=true', '--farm.simulation.use-primary=true',
    '--farm.llm.url=', '--farm.llm.api-key=', '--farm.llm.model='],
    { cwd: path.join(root, 'backend'), windowsHide: true, env: { ...process.env, FARM_BOOTSTRAP_PASSWORD: password }, stdio: ['ignore', log, log] });
  const base = `http://127.0.0.1:${port}`;
  let token, browser, page;
  const errors = [];
  async function api(route, method = 'GET', body) {
    const response = await fetch(base + '/api' + route, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) }, body: body === undefined ? undefined : JSON.stringify(body) });
    if(!response.ok)throw Error(route+': '+response.status+' '+await response.text());return normalize(await response.json());
  }
  try {
    for (let n = 0; n < 120; n++) {
      if (await fetch(base + '/api/health').then(r => r.ok).catch(() => false)) break;
      if (n === 119) throw Error('AI server readiness timeout'); await sleep(500);
    }
    token = (await api('/auth/login', 'POST', { tenantCode: 'demo-a', username: 'admin', password })).token;
    const farms = await api('/farm-workspaces');
    const farm = farms.find(f => f.name === '青禾设备联动演示场');
    assert.equal((await api('/ai/status')).llm, false);
    browser = await chromium.launch({ headless: true, channel: 'msedge' });
    const context = await browser.newContext({ viewport: { width: 1500, height: 1050 }, reducedMotion: 'reduce' });
    await context.addInitScript(value => sessionStorage.setItem('zhinong-session', value), token);
    page = await context.newPage(); page.on('pageerror', e => errors.push(e.message));
    await page.goto(base);
    await page.getByRole('button', { name: '农场 AI 助手', exact: true }).first().click();
    await page.getByText('规则分析模式', { exact: true }).waitFor();
    await verifyRefresh(page);
    await page.getByLabel('农事问题', { exact: true }).fill('分析今天的天气和四情数据，不执行设备操作。');
    const streamed = page.waitForResponse(r => r.url().endsWith('/stream') && r.request().method() === 'POST');
    const heldAnswer = await holdResponse(page, '**/api/ai/conversations/*/stream');
    try {
      await page.getByRole('button', { name: '发送', exact: true }).click();
      await heldAnswer.ready;
      await verifyAnswerProtection(page);
    } finally { await heldAnswer.release(); }
    const response = await streamed;
    assert.equal(response.status(), 200); assert.match(response.headers()['content-type'], /text\/event-stream/);
    await page.getByRole('button', { name: '从此处分支', exact: true }).waitFor();
    await waitRefreshIdle(page);
    const chats = await api('/ai/conversations?farmId=' + farm.id); assert.equal(chats.length, 1);
    const messages = await api(`/ai/conversations/${chats[0].id}/messages`);
    assert.equal(messages.length, 2); assert.ok(messages[1].content.length > 20);
    await page.locator('.ai-history-item.selected .ai-history-menu-trigger').click();
    await page.getByRole('menuitem', { name: '重命名', exact: true }).click();
    await page.locator('.ai-history-rename').fill('合并验证对话'); await page.locator('.ai-history-rename').press('Enter');
    await page.locator('.ai-history-title').getByText('合并验证对话', { exact: true }).waitFor();
    await page.locator('.ai-history-item.selected .ai-history-menu-trigger').click();
    await page.getByRole('menuitem', { name: '置顶', exact: true }).click();
    await page.getByText('已置顶', { exact: true }).waitFor();
    assert.equal((await api('/ai/conversations?farmId=' + farm.id))[0].titleSource, 'user');
    await page.getByRole('button', { name: '从此处分支', exact: true }).click();
    await page.waitForFunction(() => document.querySelectorAll('.ai-history-item').length === 2);
    await page.screenshot({ path: path.join(output, 'ai-merge-chat.png'), fullPage: true });
    await page.reload(); await page.getByRole('button', { name: '农场 AI 助手', exact: true }).first().click();
    await page.getByText('已置顶', { exact: true }).waitFor();
    await page.getByRole('button', { name: '天气与四情', exact: true }).click();
    await page.locator('.ai-condition-grid article').first().waitFor();
    assert.equal(await page.locator('.ai-condition-grid article').count(), 4);
    assert.equal(await page.locator('.ai-weather-grid svg').count(), 3);
    await page.getByRole('button', { name: '灌溉管理', exact: true }).click();
    await page.getByRole('heading', { name: '先审阅，再灌溉', exact: true }).waitFor();
    assert.equal(await page.locator('.ai-policy-grid article').count(), 3);
    await page.getByRole('button', { name: '配置策略', exact: true }).first().click();
    assert.equal(await page.getByLabel('执行模式').inputValue(), 'MANUAL');
    await page.locator('.ai-policy-form').getByRole('button', { name: '取消', exact: true }).click();
    for(const f of farms) {
      const irrigation=await api('/ai/irrigation?farmId='+f.id);
      assert.equal(irrigation.zones.length,9);assert.equal(irrigation.plots.length,3);
      assert.equal(irrigation.devices.filter(d=>d.deviceType==='SOIL').length,3);
      assert.ok(irrigation.zones.every(z=>irrigation.plots.some(p=>p.id===z.plotId)));
    }
    assert.equal(await page.locator('.ai-water-map polygon').count(),9);
    assert.equal(await page.getByText('尚未配置测点与水泵',{exact:true}).count(),0);
    await page.getByRole('button',{name:'配置策略',exact:true}).nth(2).click();
    const scope=await api('/ai/irrigation?farmId='+farm.id);
    const vegetable=scope.plots.find(p=>p.crop==='蔬菜');
    // Resolve seeded demonstration issues through their workflow in this disposable database.
    // The production high-severity interlock remains enabled and is covered by API tests.
    const fieldwork=await api('/field-work?farmId='+farm.id);
    for(const issue of fieldwork.issues.filter(i=>i.plotId===vegetable.id&&i.severity==='HIGH'&&i.status!=='RESOLVED')) {
      let taskId=issue.taskId;
      if(issue.status!=='ASSIGNED')taskId=(await api(`/field-work/issues/${issue.id}/task`,'POST',{plotId:vegetable.id,title:'浏览器验收模拟排查',taskType:'INSPECTION',dueDate:new Date().toISOString().slice(0,10),assigneeId:fieldwork.crew.find(m=>m.role==='ADMIN').id,method:'MANUAL',note:'一次性测试数据'})).id;
      if(issue.taskStatus!=='COMPLETED') {
        if(issue.taskStatus!=='RUNNING')await api(`/field-work/tasks/${taskId}/progress`,'PATCH',{status:'RUNNING',method:'MANUAL',note:'一次性浏览器验收模拟开始',actualAreaMu:0});
        await api(`/field-work/tasks/${taskId}/progress`,'PATCH',{status:'COMPLETED',method:'MANUAL',note:'一次性浏览器验收模拟处理',actualAreaMu:0.1});
      }
      await api(`/field-work/issues/${issue.id}/resolve`,'POST',{note:'一次性测试：模拟问题已处理，验证灌溉审批流程'});
    }
    const selectedZone=scope.zones.filter(z=>z.plotId===vegetable.id)[1];
    await page.getByLabel('地图灌溉覆盖区').selectOption(selectedZone.id);
    await page.getByLabel('低于此水分启灌（%）').fill('80');
    await page.getByRole('button',{name:'保存策略',exact:true}).click();
    await page.getByText('策略已保存，覆盖区与水泵关联已同步。',{exact:true}).waitFor();
    const ready=(await api('/ai/irrigation?farmId='+farm.id)).plots.find(p=>p.id===vegetable.id);
    assert.equal(ready.readyForProposal,true,ready.diagnosis);
    await page.locator('.ai-policy-grid article').nth(2).getByRole('button',{name:'检查并生成建议',exact:true}).click();
    await page.getByRole('button',{name:'确认并启动模拟灌溉',exact:true}).click();
    await page.getByRole('button',{name:'确认启动',exact:true}).click();
    await page.locator('.ai-water-history').getByRole('button',{name:'停止任务',exact:true}).waitFor();
    const running=await api('/ai/irrigation?farmId='+farm.id);
    assert.equal(running.runs[0].mapJobId,running.jobs[0].id);assert.equal(running.jobs[0].parameters.zoneId,selectedZone.id);
    const map=await api(`/farms/${farm.id}/field-map`);assert.ok(map.jobs.some(j=>j.id===running.jobs[0].id));
    await page.locator('.ai-water-history').getByRole('button',{name:'停止任务',exact:true}).click();
    await page.getByText('停止结果已同步。',{exact:true}).waitFor();
    const stopped=await api('/ai/irrigation?farmId='+farm.id);
    assert.equal(stopped.runs[0].status,'COMPLETED');assert.equal(stopped.jobs[0].status,'STOPPED');
    assert.ok(stopped.jobs[0].estimatedM3>0);assert.equal(stopped.jobs[0].measuredM3,null);
    await page.getByLabel('选择灌溉覆盖区').selectOption(scope.zones[0].id);
    await page.getByLabel('人工灌溉时长（秒）').fill('60');
    await page.getByRole('button',{name:'确认分区并下发',exact:true}).click();
    await page.getByRole('button',{name:'确认下发',exact:true}).click();
    await page.locator('.ai-water-history').getByRole('button',{name:'停止任务',exact:true}).waitFor();
    await page.locator('.ai-water-history').getByRole('button',{name:'停止任务',exact:true}).click();
    await page.getByText('停止结果已同步。',{exact:true}).waitFor();
    assert.equal((await api('/ai/irrigation?farmId='+farm.id)).waterTotals.runs,2);
    await page.evaluate(()=>{document.documentElement.dataset.theme='light';});
    await page.screenshot({path:path.join(output,'ai-irrigation-desktop.png'),fullPage:true});
    await page.evaluate(()=>{document.documentElement.dataset.theme='dark';});
    await page.screenshot({path:path.join(output,'ai-irrigation-dark.png'),fullPage:true});
    await page.setViewportSize({width:390,height:844});
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+2),true);
    await page.screenshot({path:path.join(output,'ai-irrigation-narrow.png'),fullPage:true});
    await page.setViewportSize({width:1500,height:1050});
    const otherFarm=farms.find(f=>f.name==='丰穗农机演示场');
    await page.getByRole('button',{name:'当前农场',exact:true}).click();
    await page.getByRole('option',{name:otherFarm.name,exact:true}).click();
    await page.locator('.ai-context p').filter({hasText:otherFarm.name}).waitFor();
    await page.getByRole('button',{name:'灌溉管理',exact:true}).click();
    await page.getByRole('heading',{name:'先审阅，再灌溉',exact:true}).waitFor();
    const otherScope=await api('/ai/irrigation?farmId='+otherFarm.id);
    const displayedZone=await page.getByLabel('选择灌溉覆盖区').inputValue();
    assert.ok(otherScope.zones.some(z=>z.id===displayedZone));
    assert.equal(await page.locator('.ai-water-history tbody tr').count(),0);
    assert.equal(await page.locator('.ai-water-map polygon').count(),9);
    await page.getByRole('button', { name: '农事对话', exact: true }).click();
    await page.evaluate(() => { document.documentElement.dataset.theme = 'dark'; });
    await page.screenshot({ path: path.join(output, 'ai-merge-dark.png'), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.evaluate(() => { document.documentElement.dataset.theme = 'light'; });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 2), true);
    await page.screenshot({ path: path.join(output, 'ai-merge-mobile.png'), fullPage: true });
    assert.deepEqual(errors, []);
    const result = { singleHeadingAndRefresh: true, awaitedRefresh: true, refreshFailureRecovery: true, answerProtectedFromRefresh: true, stream: true, ruleFallback: true, renamedAndPinned: true, branch: true, persisted: true, conditions: 4, weatherCharts: 3, irrigationPanel: true, farmsWithNineZones:farms.length, sharedAiAndMapJob:true, manualWaterRecord:true, estimatedNotMeasured:true, narrowAndDark: true, consoleErrors: errors };
    fs.writeFileSync(path.join(output, 'ai-merge-browser-result.json'), JSON.stringify(result, null, 2));
    console.log(JSON.stringify(result));
  } catch (error) {
    if (page) await page.screenshot({ path: path.join(output, 'ai-merge-failure.png'), fullPage: true }).catch(() => {});
    throw error;
  } finally {
    if (token) await api('/auth/logout', 'POST').catch(() => {});
    await browser?.close(); server.kill(); fs.closeSync(log);
  }
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
