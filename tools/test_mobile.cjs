// Acceptance against an isolated memory database; never writes to the user's farms.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const net = require('node:net');
const assert = require('node:assert/strict');
const { spawn, spawnSync } = require('node:child_process');
const { chromium } = require('../frontend/node_modules/playwright');
const root = path.resolve(__dirname, '..');
const output = path.join(root, '.cache');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

(async () => {
  const { normalize } = await import('../frontend/src/mobile/client.mjs');
  fs.mkdirSync(output, { recursive: true });
  const listener = net.createServer();
  await new Promise(resolve => listener.listen(0, '127.0.0.1', resolve));
  const port = listener.address().port; await new Promise(resolve => listener.close(resolve));
  const password = crypto.randomBytes(20).toString('hex');
  const log = fs.openSync(path.join(output, 'mobile-browser-server.log'), 'w');
  const settings = spawnSync('java', ['-XshowSettings:properties', '-version'], { windowsHide: true, encoding: 'utf8' }).stderr;
  const java = path.join(settings.match(/java.home = ([^\r\n]+)/)[1].trim(), 'bin', 'java.exe');
  const server = spawn(java, ['-Dfarm.llm.file-enabled=false', '-jar', 'target/zhinong-platform-0.3.0.jar',
    `--server.port=${port}`, '--spring.web.resources.static-locations=file:../frontend/dist/', '--spring.datasource.url=jdbc:h2:mem:mobile-browser;DB_CLOSE_DELAY=-1',
    '--spring.datasource.username=sa', '--spring.datasource.password=', '--farm.demo=true', '--farm.demo-rich=true',
    '--farm.demo-portfolio=true', '--farm.demo-live=true', '--farm.simulation.use-primary=true'],
  { cwd: path.join(root, 'backend'), windowsHide: true, env: { ...process.env, FARM_BOOTSTRAP_PASSWORD: password }, stdio: ['ignore', log, log] });
  const base = `http://127.0.0.1:${port}`;
  const errors = [], checks = [], tokens = [];
  let browser, page, desktop;
  async function api(route, method = 'GET', body, token = desktop, expected = 200) {
    const response = await fetch(base + '/api' + route, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) }, body: body === undefined ? undefined : JSON.stringify(body) });
    assert.equal(response.status, expected, `${method} ${route}: ${await response.clone().text()}`);
    return normalize(await response.json());
  }
  async function loginToken(tenantCode, username) { const result = await api('/auth/login', 'POST', { tenantCode, username, password }, ''); tokens.push(result.token); return result.token; }
  async function loginUi(username = 'admin', tenantCode = 'demo-a') {
    await page.getByLabel('租户代码', { exact: true }).fill(tenantCode);
    await page.getByLabel('账号', { exact: true }).fill(username);
    await page.getByLabel('密码', { exact: true }).fill(password);
    await page.getByRole('button', { name: '登录农场', exact: true }).click();
    await page.locator('.sync-line').getByText('已连接', { exact: false }).waitFor();
  }
  const nav = name => page.getByRole('navigation', { name: '手机主导航' }).getByRole('button', { name, exact: true }).click();
  async function showDevice(id) { await nav('设备'); const back = page.getByRole('button', { name: '← 返回设备列表' }); if (await back.count()) await back.click(); await page.locator(`[data-device-id="${id}"]`).click(); await page.getByRole('heading', { name: '指令记录', exact: false }).waitFor(); }
  async function noOverflow() { assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, 'Mobile page must fit the viewport'); }
  try {
    for (let n = 0; n < 120; n++) { if (await fetch(base + '/api/health').then(r => r.ok).catch(() => false)) break; if (n === 119) throw Error('Mobile server readiness timeout'); await sleep(500); }
    desktop = await loginToken('demo-a', 'admin');
    const farms = await api('/farm-workspaces'); assert.equal(farms.length, 5);
    browser = await chromium.launch({ headless: true, channel: 'msedge' });
    const context = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, reducedMotion: 'reduce' });
    page = await context.newPage(); page.on('pageerror', e => errors.push(e.message));
    await page.goto(base + '/mobile/index.html'); await noOverflow();
    await page.screenshot({ path: path.join(output, 'mobile-login.png'), fullPage: true });
    await loginUi(); await noOverflow();
    await page.screenshot({ path: path.join(output, 'mobile-home.png'), fullPage: true });
    checks.push('手机账号登录、首页与 390px 布局');
    const sources = new Set();
    for (const farm of farms) {
      await page.getByLabel('当前农场', { exact: true }).selectOption(farm.id);
      await nav('设备');
      const devices = await api(`/assets?farmId=${farm.id}`);
      await page.locator(`[data-device-id="${devices[0].id}"]`).waitFor();
      assert.equal(await page.locator('[data-device-id]').count(), devices.length);
      await nav('实景');
      const cameraDevices = devices.filter(d => d.deviceType === 'CAMERA');
      assert.equal(cameraDevices.length, 3);
      const camera = cameraDevices[0];
      await page.locator(`[data-camera-id="${camera.id}"] .camera-frame[data-media-state="ready"]`).waitFor();
      sources.add(await page.locator(`[data-camera-id="${camera.id}"] .camera-frame img`).getAttribute('src'));
      assert.equal(await page.locator('[data-camera-id]').count(), 3);
      for (const camera of cameraDevices) {
        const frame = page.locator(`[data-camera-id="${camera.id}"]`);
        await frame.locator('.camera-frame[data-media-state="ready"]').waitFor();
        await frame.getByText('演示画面 · 非实时', { exact: true }).waitFor();
      }
      await noOverflow();
    }
    assert.equal(sources.size, 5); checks.push('五个农场的设备和摄像头隔离，画面各不相同');
    await page.screenshot({ path: path.join(output, 'mobile-camera.png'), fullPage: true });
    const farm = farms.find(f => f.name === '青禾设备联动演示场');
    await page.getByLabel('当前农场', { exact: true }).selectOption(farm.id);
    await nav('设备');
    const devices = await api(`/assets?farmId=${farm.id}`), pump = devices.find(d => d.deviceType === 'PUMP'), soil = devices.find(d => d.deviceType === 'SOIL');
    await page.locator(`[data-device-id="${soil.id}"]`).click();
    await page.locator('.trend').waitFor(); await noOverflow();
    checks.push('共享设备详情与 24 小时历史趋势');
    await showDevice(pump.id);
    await page.getByLabel('操作', { exact: true }).selectOption('PUMP_STOP');
    await page.getByRole('button', { name: '确认操作信息', exact: true }).click();
    await page.getByRole('dialog').waitFor();
    const requestId = await page.getByRole('dialog').locator('.request-id').innerText();
    assert.equal((await api(`/assets/${pump.id}/commands`)).commands.some(c => c.requestId === requestId), false, 'No command before human confirmation');
    await page.getByRole('button', { name: '确认下发', exact: true }).click();
    await page.getByRole('dialog').waitFor({ state: 'hidden' });
    const submitted = (await api(`/assets/${pump.id}/commands`)).commands.find(c => c.requestId === requestId); assert.equal(submitted.status, 'SUCCEEDED');
    const fromDesktop = crypto.randomUUID();
    await api(`/assets/${pump.id}/commands`, 'POST', { requestId: fromDesktop, action: 'PUMP_STOP', value: null, note: 'Independent desktop session test' });
    await page.getByRole('button', { name: '刷新农场数据' }).click();
    await page.locator(`[data-request-id="${fromDesktop}"]`).waitFor();
    await page.screenshot({ path: path.join(output, 'mobile-device.png'), fullPage: true });
    checks.push('手机下发前确认，电脑独立会话可读；电脑指令返回手机记录');
    // Simulate a lost response after the server actually accepted the command.
    let droppedId;
    await page.route(`**/api/assets/${pump.id}/commands`, async route => {
      if (route.request().method() !== 'POST') return route.continue();
      droppedId = route.request().postDataJSON().requestId; await route.fetch(); await route.abort('failed');
    });
    await page.getByRole('button', { name: '确认操作信息', exact: true }).click();
    await page.getByRole('button', { name: '确认下发', exact: true }).click();
    await page.getByRole('button', { name: '查询操作结果', exact: true }).waitFor();
    await page.unroute(`**/api/assets/${pump.id}/commands`);
    await page.getByRole('button', { name: '查询操作结果', exact: true }).click();
    await page.getByRole('dialog').waitFor({ state: 'hidden' });
    assert.equal((await api(`/assets/${pump.id}/commands`)).commands.filter(c => c.requestId === droppedId).length, 1);
    checks.push('回执丢失后主动查询，未重复下发');
    await nav('作业'); await page.getByRole('button', { name: '农机规划', exact: true }).click();
    await page.getByLabel('任务名称', { exact: true }).fill('手机联动验证任务');
    await page.getByRole('button', { name: '预览往复式路线' }).click();
    await page.getByRole('heading', { name: '路线预览', exact: true }).waitFor();
    await noOverflow(); await page.screenshot({ path: path.join(output, 'mobile-route.png'), fullPage: true });
    await page.getByRole('button', { name: '确认下发方案' }).click(); await page.getByRole('button', { name: '确认下发', exact: true }).click();
    await page.getByRole('dialog').waitFor({ state: 'hidden' });
    await page.getByRole('button', { name: '任务记录', exact: true }).click();
    const job = (await api(`/farms/${farm.id}/field-map`)).jobs.find(j => j.title === '手机联动验证任务'); assert.ok(job?.plan.points.length > 2);
    const row = page.locator(`[data-job-id="${job.id}"]`); await row.waitFor();
    await row.getByRole('button', { name: '暂停任务', exact: true }).click(); await page.getByRole('button', { name: '确认下发', exact: true }).click(); await page.getByRole('dialog').waitFor({ state: 'hidden' });
    await row.getByRole('button', { name: '继续任务', exact: true }).click(); await page.getByRole('button', { name: '确认下发', exact: true }).click(); await page.getByRole('dialog').waitFor({ state: 'hidden' });
    await row.getByRole('button', { name: '停止任务', exact: true }).click(); await page.getByRole('button', { name: '确认下发', exact: true }).click(); await page.getByRole('dialog').waitFor({ state: 'hidden' });
    assert.equal((await api(`/farms/${farm.id}/field-map`)).jobs.find(j => j.id === job.id).status, 'STOPPED');
    checks.push('农机型号、田块路线预览、模拟下发、暂停、继续和停止共享记录');
    await page.getByRole('button', { name: '分区灌溉', exact: true }).click(); await page.getByRole('button', { name: '确认灌溉方案' }).click(); await page.getByRole('button', { name: '确认下发', exact: true }).click(); await page.getByRole('dialog').waitFor({ state: 'hidden' });
    const water = (await api(`/farms/${farm.id}/field-map`)).jobs.find(j => j.kind === 'IRRIGATION'); assert.ok(water);
    await sleep(1200);
    await page.locator(`[data-job-id="${water.id}"]`).getByRole('button', { name: '停止任务', exact: true }).click(); await page.getByRole('button', { name: '确认下发', exact: true }).click(); await page.getByRole('dialog').waitFor({ state: 'hidden' });
    const waterResult = (await api(`/farms/${farm.id}/field-map`)).jobs.find(j => j.id === water.id); assert.equal(waterResult.status, 'STOPPED'); assert.equal(waterResult.measuredM3, null);
    checks.push('分区灌溉启停与估算/实测用水记录分离');
    const viewer = await loginToken('demo-a', 'viewer'), other = await loginToken('demo-b', 'admin'), platform = await loginToken('platform', 'platform');
    await api(`/assets/${pump.id}/commands`, 'POST', { requestId: crypto.randomUUID(), action: 'PUMP_STOP', value: null, note: 'role test' }, viewer, 403);
    await api(`/assets/${pump.id}`, 'GET', undefined, other, 404);
    await api(`/farms/${farm.id}/field-map`, 'GET', undefined, other, 404);
    await api('/farm-workspaces', 'GET', undefined, platform, 403);
    await page.getByRole('button', { name: '账号与连接' }).click(); await page.getByRole('button', { name: '退出登录', exact: true }).click();
    await loginUi('viewer'); await page.getByLabel('当前农场', { exact: true }).selectOption(farm.id); await showDevice(pump.id);
    assert.equal(await page.getByRole('button', { name: '确认操作信息' }).isDisabled(), true);
    checks.push('只读账号不可下发，跨租户和平台经营数据访问被后端拒绝');
    // A delayed old-farm response must not replace the new farm after a switch.
    let releaseOld, markStarted;
    const started = new Promise(r => { markStarted = r; });
    const gate = new Promise(r => { releaseOld = r; });
    const oldFarmRoute = `**/api/assets?farmId=${farm.id}`;
    await page.route(oldFarmRoute, async route => { markStarted(); await gate; await route.continue(); });
    await page.getByRole('button', { name: '刷新农场数据' }).click(); await started;
    const nextFarm = farms.find(f => f.id !== farm.id), nextDevices = await api(`/assets?farmId=${nextFarm.id}`);
    await page.getByLabel('当前农场', { exact: true }).selectOption(nextFarm.id);
    await page.locator(`[data-device-id="${nextDevices[0].id}"]`).waitFor();
    const oldFinished = page.waitForResponse(r => r.url().includes(`/api/assets?farmId=${farm.id}`));
    releaseOld(); await oldFinished; await page.unroute(oldFarmRoute); await sleep(100);
    assert.equal(await page.locator(`[data-device-id="${pump.id}"]`).count(), 0);
    assert.equal(await page.locator('[data-device-id]').count(), nextDevices.length);
    checks.push('旧农场响应延迟返回时不会覆盖当前农场');
    for (const width of [360, 390, 768]) { await page.setViewportSize({ width, height: 844 }); await noOverflow(); }
    assert.equal(await page.evaluate(() => localStorage.length + sessionStorage.length), 0, 'No persisted login credentials');
    // A 401 must clear farm data rather than leaving a previous tenant visible.
    await page.route('**/api/assets?*', route => route.fulfill({ status: 401, contentType: 'application/json', body: '{"message":"Session expired"}' }));
    await page.getByRole('button', { name: '刷新农场数据' }).click(); await page.getByRole('button', { name: '登录农场', exact: true }).waitFor();
    assert.equal(await page.locator('[data-device-id]').count(), 0);
    checks.push('会话过期清空数据，不持久化账号令牌；360/390/768px 无横向溢出');
    assert.deepEqual(errors, []);
    fs.writeFileSync(path.join(output, 'mobile-browser-result.json'), JSON.stringify({ passed: true, checks }, null, 2));
    console.log(JSON.stringify({ passed: true, checks }, null, 2));
  } catch (e) {
    if (page) { await page.screenshot({ path: path.join(output, 'mobile-failure.png'), fullPage: true }).catch(() => {}); fs.writeFileSync(path.join(output, 'mobile-failure.txt'), await page.locator('body').innerText().catch(() => '')); }
    console.error(e); console.error('Browser errors:', errors); process.exitCode = 1;
  } finally {
    if (browser) await browser.close();
    for (const token of tokens) await api('/auth/logout', 'POST', undefined, token).catch(() => {});
    server.kill(); fs.closeSync(log);
  }
})();
