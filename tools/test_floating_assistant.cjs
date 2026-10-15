// 小禾悬浮助手浏览器验收：共享会话状态、页面资料来源、生成中切到完整 AI、失败重试、切换农场与手机底板。
// 在一次性 H2 服务上运行，模型配置禁用（全部走规则回退），不访问任何外部模型服务。
// 用法：node tools/test_floating_assistant.cjs [backend jar]，需先构建 frontend/dist 与后端 jar。
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const net = require('node:net');
const assert = require('node:assert/strict');
const { spawn, spawnSync } = require('node:child_process');
const { chromium } = require('../frontend/node_modules/playwright');
const { holdResponse } = require('./ai_refresh_checks.cjs');
const root = path.resolve(__dirname, '..'), output = path.join(root, '.cache');
const serverJar = process.argv[2] || 'target/zhinong-platform-0.3.0.jar';
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const STREAM = '**/api/ai/conversations/*/stream';

(async () => {
  fs.mkdirSync(output, { recursive: true });
  const listener = net.createServer();
  await new Promise(resolve => listener.listen(0, '127.0.0.1', resolve));
  const port = listener.address().port; await new Promise(resolve => listener.close(resolve));
  const password = crypto.randomBytes(20).toString('hex');
  const log = fs.openSync(path.join(output, 'floating-assistant-server.log'), 'w');
  const settings = spawnSync('java', ['-XshowSettings:properties', '-version'], { windowsHide: true, encoding: 'utf8' }).stderr;
  const java = path.join(settings.match(/java.home = ([^\r\n]+)/)[1].trim(), 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
  const server = spawn(java, ['-Dfarm.llm.file-enabled=false', '-jar', serverJar,
    `--server.port=${port}`, '--spring.web.resources.static-locations=file:../frontend/dist/',
    '--spring.datasource.url=jdbc:h2:mem:floating-browser;DB_CLOSE_DELAY=-1', '--spring.datasource.username=sa', '--spring.datasource.password=',
    '--farm.demo=true', '--farm.demo-rich=true', '--farm.demo-portfolio=true',
    '--farm.llm.url=', '--farm.llm.api-key=', '--farm.llm.model='],
    { cwd: path.join(root, 'backend'), windowsHide: true, env: { ...process.env, FARM_BOOTSTRAP_PASSWORD: password }, stdio: ['ignore', log, log] });
  const base = `http://127.0.0.1:${port}`;
  let token, browser;
  const errors = [];
  async function api(route, method = 'GET', body) {
    const response = await fetch(base + '/api' + route, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) }, body: body === undefined ? undefined : JSON.stringify(body) });
    if (!response.ok) throw Error(route + ': ' + response.status + ' ' + await response.text());
    return response.json();
  }
  async function newPage(options) {
    const context = await browser.newContext({ reducedMotion: 'reduce', ...options });
    await context.addInitScript(value => sessionStorage.setItem('zhinong-session', value), token);
    const page = await context.newPage();
    page.on('pageerror', e => errors.push(e.message));
    await page.goto(base);
    await page.getByRole('button', { name: '打开小禾农场助手' }).waitFor();
    return page;
  }
  const panel = page => page.locator('.floating-assistant-panel');
  async function openFloating(page) {
    if (!await panel(page).count()) await page.getByRole('button', { name: '打开小禾农场助手' }).click();
    await panel(page).waitFor();
    await page.waitForFunction(() => !document.querySelector('.floating-assistant-loading'));
  }
  async function askFloating(page, text) {
    await openFloating(page);
    await page.locator('#floating-question').fill(text);
    await panel(page).getByRole('button', { name: '发送', exact: true }).click();
  }

  try {
    for (let n = 0; n < 120; n++) {
      if (await fetch(base + '/api/health').then(r => r.ok).catch(() => false)) break;
      if (n === 119) throw Error('server readiness timeout'); await sleep(500);
    }
    token = (await api('/auth/login', 'POST', { tenantCode: 'demo-a', username: 'admin', password })).token;
    assert.equal((await api('/ai/status')).llm, false);
    browser = await chromium.launch({ headless: true, channel: 'msedge' });

    // ── 1. 生成中切换到完整 AI：同一次运行在完整页继续显示，完成后两侧都拿到落库消息和来源记录 ──
    const page = await newPage({ viewport: { width: 1440, height: 960 } });
    const held = await holdResponse(page, STREAM);
    await askFloating(page, '验收一：今天优先做什么？');
    await held.ready;
    await panel(page).getByRole('button', { name: '停止生成' }).waitFor();
    await panel(page).getByRole('button', { name: '打开完整 AI' }).click();
    const full = page.locator('.farm-assistant');
    await full.waitFor();
    assert.equal(await page.locator('.floating-assistant').isVisible(), false, 'floating entry hides on the full AI page');
    const fullLog = full.getByLabel('农事对话记录');
    await fullLog.getByText('验收一：今天优先做什么？').waitFor();
    assert.equal(await fullLog.getByText('验收一：今天优先做什么？').count(), 1, 'the shared run is shown once, not duplicated by history');
    await full.getByRole('button', { name: '停止生成' }).waitFor();
    await full.locator('.ai-history-surface', { hasText: '小窗' }).first().waitFor();
    await held.release();
    await full.getByText('模型服务暂不可用', { exact: false }).first().waitFor();
    await full.getByRole('button', { name: '停止生成' }).waitFor({ state: 'detached' });
    await full.locator('.ai-source-note', { hasText: '参考页面：今日农场' }).waitFor();
    await page.screenshot({ path: path.join(output, 'floating-switch-full.png') });
    // 回到业务页再打开小窗：同一会话、同一份回答与来源记录
    await page.getByRole('button', { name: '今日农场', exact: true }).first().click();
    await openFloating(page);
    await panel(page).getByText('验收一：今天优先做什么？').waitFor();
    await panel(page).locator('.ai-source-note', { hasText: '参考页面：今日农场' }).waitFor();

    // ── 2. 失败重试：沿用同一个 requestId，第二次走真实服务端并成功 ──
    const requestIds = [];
    let failOnce = true;
    const failing = async route => {
      requestIds.push(JSON.parse(route.request().postData()).requestId);
      if (failOnce) {
        failOnce = false;
        await route.fulfill({ status: 200, headers: { 'Content-Type': 'text/event-stream' },
          body: 'data: {"sequence":1,"type":"run.started"}\n\ndata: {"sequence":2,"type":"run.error","error":"模型服务繁忙","diagnostic":"BUSY"}\n\n' });
      } else await route.continue();
    };
    await page.route(STREAM, failing);
    const answersBefore = await panel(page).locator('.floating-assistant-message.assistant').count();
    await askFloating(page, '验收二：核查设备异常');
    await panel(page).getByRole('button', { name: '重试本次回答' }).click();
    await page.waitForFunction(count => document.querySelectorAll('.floating-assistant-panel .floating-assistant-message.assistant').length > count && !document.querySelector('.floating-assistant-panel [aria-label="停止生成"]'), answersBefore);
    await page.unroute(STREAM, failing);
    assert.equal(requestIds.length, 2);
    assert.equal(requestIds[0], requestIds[1], 'retry reuses the same requestId');
    assert.equal(await page.locator('#floating-question').inputValue(), '', 'successful retry clears the restored draft');

    // ── 3. 切换农场：中止生成、关闭小窗、清空草稿，新农场看不到旧农场的问题与回答 ──
    const heldSwitch = await holdResponse(page, STREAM);
    await askFloating(page, '验收三：切换农场前的问题');
    await heldSwitch.ready;
    const farmButton = page.getByRole('button', { name: '当前农场' });
    const oldFarm = (await farmButton.textContent()).trim();
    await farmButton.click();
    await page.getByRole('option', { selected: false }).first().click();
    await page.waitForFunction(old => !document.querySelector('[aria-label="当前农场"]')?.textContent.includes(old), oldFarm);
    assert.equal(await panel(page).count(), 0, 'farm switch closes the floating panel');
    await heldSwitch.release().catch(() => { /* 请求已被中止，fulfill 失败是预期结果 */ });
    await openFloating(page);
    assert.equal(await page.locator('#floating-question').inputValue(), '');
    assert.equal(await panel(page).getByText('验收三：切换农场前的问题').count(), 0);
    assert.equal(await panel(page).getByRole('button', { name: '停止生成' }).count(), 0);
    await page.keyboard.press('Escape');
    assert.equal(await panel(page).count(), 0, 'Escape closes the desktop panel');
    await page.close();

    // ── 4. 手机底板：390/360 宽度、深色模式、触控热区、无横向溢出、Escape 归还焦点 ──
    for (const width of [390, 360]) {
      const mobile = await newPage({ viewport: { width, height: 844 }, isMobile: true, hasTouch: true, colorScheme: 'dark' });
      // 应用主题跟随账号外观设置（默认按本地时段），这里直接切到深色主题验收 token 在深色下的表现。
      await mobile.evaluate(() => { document.documentElement.dataset.theme = 'dark'; });
      const trigger = mobile.getByRole('button', { name: '打开小禾农场助手' });
      const triggerBox = await trigger.boundingBox(), tabbar = await mobile.locator('nav.tabbar').boundingBox();
      if (tabbar) assert.ok(triggerBox.y + triggerBox.height <= tabbar.y, 'entry stays above the bottom tab bar');
      await openFloating(mobile);
      const sheet = await panel(mobile).boundingBox();
      assert.equal(Math.round(sheet.width), width, 'sheet spans the viewport');
      assert.ok(Math.round(sheet.y + sheet.height) <= 844);
      assert.ok(await mobile.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), 'no horizontal overflow');
      assert.equal(await mobile.evaluate(() => getComputedStyle(document.body).overflow), 'hidden', 'background scroll is locked');
      const small = await panel(mobile).locator('button:visible').evaluateAll(buttons => buttons
        .map(b => ({ name: b.getAttribute('aria-label') || b.textContent.trim(), w: b.getBoundingClientRect().width, h: b.getBoundingClientRect().height }))
        .filter(b => b.h < 44 || b.w < 44 && !b.name));
      assert.deepEqual(small, [], 'touch targets are at least 44px tall');
      await panel(mobile).getByText('验收一：今天优先做什么？').waitFor();
      await mobile.screenshot({ path: path.join(output, `floating-mobile-${width}-dark.png`) });
      await mobile.keyboard.press('Escape');
      assert.equal(await panel(mobile).count(), 0);
      await mobile.waitForFunction(() => document.activeElement?.getAttribute('aria-label') === '打开小禾农场助手', null, { timeout: 3000 })
        .catch(async () => assert.fail('focus returns to the entry, got ' + await mobile.evaluate(() => document.activeElement?.outerHTML.slice(0, 160))));
      assert.notEqual(await mobile.evaluate(() => getComputedStyle(document.body).overflow), 'hidden', 'scroll lock is released');
      await mobile.close();
    }

    assert.deepEqual(errors, [], 'no uncaught page errors');
    console.log('floating assistant browser acceptance passed');
  } finally {
    await browser?.close();
    server.kill();
  }
})().catch(error => { console.error(error); process.exit(1); });
