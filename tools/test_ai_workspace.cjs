// AI/browser integration on a disposable H2 server. Private model configuration is disabled.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const net = require('node:net');
const assert = require('node:assert/strict');
const { spawn, spawnSync } = require('node:child_process');
const { chromium } = require('../frontend/node_modules/playwright');
const root = path.resolve(__dirname, '..'), output = path.join(root, '.cache');
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
  const server = spawn(java, ['-Dfarm.llm.file-enabled=false', '-jar', 'target/zhinong-platform-0.3.0.jar',
    `--server.port=${port}`, '--spring.web.resources.static-locations=file:../frontend/dist/',
    '--spring.datasource.url=jdbc:h2:mem:ai-browser;DB_CLOSE_DELAY=-1', '--spring.datasource.username=sa', '--spring.datasource.password=',
    '--farm.demo=true', '--farm.demo-rich=true', '--farm.demo-portfolio=true', '--farm.simulation.use-primary=true',
    '--farm.llm.url=', '--farm.llm.api-key=', '--farm.llm.model='],
    { cwd: path.join(root, 'backend'), windowsHide: true, env: { ...process.env, FARM_BOOTSTRAP_PASSWORD: password }, stdio: ['ignore', log, log] });
  const base = `http://127.0.0.1:${port}`;
  let token, browser, page;
  const errors = [];
  async function api(route, method = 'GET', body) {
    const response = await fetch(base + '/api' + route, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) }, body: body === undefined ? undefined : JSON.stringify(body) });
    assert.ok(response.ok, route + ': ' + response.status); return normalize(await response.json());
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
    await page.getByLabel('农事问题', { exact: true }).fill('分析今天的天气和四情数据，不执行设备操作。');
    const streamed = page.waitForResponse(r => r.url().endsWith('/stream') && r.request().method() === 'POST');
    await page.getByRole('button', { name: '发送', exact: true }).click();
    const response = await streamed;
    assert.equal(response.status(), 200); assert.match(response.headers()['content-type'], /text\/event-stream/);
    await page.getByRole('button', { name: '从此处分支', exact: true }).waitFor();
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
    await page.getByRole('button', { name: '农事对话', exact: true }).click();
    await page.evaluate(() => { document.documentElement.dataset.theme = 'dark'; });
    await page.screenshot({ path: path.join(output, 'ai-merge-dark.png'), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.evaluate(() => { document.documentElement.dataset.theme = 'light'; });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 2), true);
    await page.screenshot({ path: path.join(output, 'ai-merge-mobile.png'), fullPage: true });
    assert.deepEqual(errors, []);
    const result = { stream: true, ruleFallback: true, renamedAndPinned: true, branch: true, persisted: true, conditions: 4, weatherCharts: 3, irrigationPanel: true, narrowAndDark: true, consoleErrors: errors };
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
