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
    page.on('console', message => { if (message.type() === 'error' || message.type() === 'warning' && message.text().includes('Unhandled error')) errors.push(message.text()); });
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
  async function dragLauncher(page, point) {
    const box = await page.locator('.floating-assistant-trigger').boundingBox();
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.mouse.down();
    await page.mouse.move(point.x, point.y, { steps: 8 });
    await page.mouse.up();
  }
  async function assertPanelFits(page) {
    const box = await panel(page).boundingBox(), viewport = page.viewportSize();
    assert.ok(box.x >= 0 && box.y >= 0 && box.x + box.width <= viewport.width && box.y + box.height <= viewport.height, 'dialog stays on screen near a dragged opener');
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
    const sentQuestions = [];
    page.on('request', request => { if (request.method() === 'POST' && request.url().endsWith('/stream')) sentQuestions.push(JSON.parse(request.postData())); });
    const launcher = page.locator('.floating-assistant-trigger');
    const art = await launcher.locator('.sprout-art').boundingBox();
    assert.equal(art.width, 96); assert.equal(art.height, 108);
    const initial = await launcher.boundingBox();
    await dragLauncher(page, { x: initial.x + initial.width / 2 - 160, y: initial.y + initial.height / 2 - 180 });
    const moved = await launcher.boundingBox();
    assert.equal(Math.round(initial.x - moved.x), 160); assert.equal(Math.round(initial.y - moved.y), 180);
    assert.equal(await panel(page).count(), 0, 'releasing a drag never opens the assistant');
    await page.mouse.move(100, 100);
    assert.deepEqual(await launcher.boundingBox(), moved, 'the opener stops moving after release');
    await page.mouse.move(moved.x + moved.width / 2, moved.y + moved.height / 2);
    await page.mouse.down({ button: 'right' });
    await page.mouse.move(200, 200); await page.mouse.up({ button: 'right' });
    await page.keyboard.press('Escape');
    assert.deepEqual(await launcher.boundingBox(), moved, 'right-button motion cannot move the opener');
    // Small hand movement is still a click; Enter remains available immediately after a drag.
    await page.mouse.move(moved.x + moved.width / 2, moved.y + moved.height / 2);
    await page.mouse.down(); await page.mouse.move(moved.x + moved.width / 2 + 2, moved.y + moved.height / 2 + 2); await page.mouse.up();
    await panel(page).waitFor(); await page.keyboard.press('Escape');
    for (const point of [{ x: 0, y: 0 }, { x: 1439, y: 959 }, { x: 720, y: 480 }]) {
      await dragLauncher(page, point);
      assert.equal(await panel(page).count(), 0);
      await launcher.focus(); await page.keyboard.press('Enter'); await panel(page).waitFor();
      await assertPanelFits(page); await page.keyboard.press('Escape');
    }
    await openFloating(page);
    await dragLauncher(page, { x: 80, y: 80 });
    assert.equal(await panel(page).count(), 1, 'an open dialog follows a dragged opener');
    await assertPanelFits(page); await page.keyboard.press('Escape');
    await dragLauncher(page, { x: 1439, y: 959 });
    await page.setViewportSize({ width: 900, height: 700 });
    await page.waitForFunction(() => { const box = document.querySelector('.floating-assistant-trigger').getBoundingClientRect(); return box.right <= innerWidth && box.bottom <= innerHeight; });
    await openFloating(page); await assertPanelFits(page); await page.keyboard.press('Escape');
    await page.setViewportSize({ width: 1440, height: 960 });
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

    // A completed rule fallback needs a fresh request, rather than an idempotent replay of the outage.
    await panel(page).getByText('模型服务尚未配置', { exact: true }).waitFor();
    const originalRequestId = sentQuestions[0].requestId;
    let recoveredRequest;
    const captureRecovery = async route => { recoveredRequest = JSON.parse(route.request().postData()); await route.continue(); };
    await page.route(STREAM, captureRecovery);
    await page.locator('#floating-question').fill('重试时保留的草稿');
    const beforeRecovery = await panel(page).locator('.floating-assistant-message.assistant').count();
    await panel(page).getByRole('button', { name: '重新请求模型', exact: true }).click();
    await page.waitForFunction(count => document.querySelectorAll('.floating-assistant-panel .floating-assistant-message.assistant').length > count && !document.querySelector('.floating-assistant-panel [aria-label="停止生成"]'), beforeRecovery);
    await page.unroute(STREAM, captureRecovery);
    assert.equal(recoveredRequest.question, '验收一：今天优先做什么？');
    assert.notEqual(recoveredRequest.requestId, originalRequestId, 'a completed fallback is retried as a new request');
    assert.equal(await page.locator('#floating-question').inputValue(), '重试时保留的草稿');

    const failedRecovery = async route => route.fulfill({ status: 200, headers: { 'Content-Type': 'text/event-stream' },
      body: 'data: {"sequence":1,"type":"run.started"}\n\ndata: {"sequence":2,"type":"run.error","error":"模型服务繁忙","diagnostic":"BUSY"}\n\n' });
    await page.route(STREAM, failedRecovery);
    await page.locator('#floating-question').fill('');
    await panel(page).getByRole('button', { name: '重新请求模型', exact: true }).first().click();
    await panel(page).getByRole('button', { name: '重试本次回答' }).waitFor();
    await page.waitForFunction(() => document.querySelector('#floating-question').value === '验收一：今天优先做什么？');
    await page.unroute(STREAM, failedRecovery);

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
    const answersBefore = await panel(page).locator('.floating-assistant-message.assistant').filter({ hasNot: page.locator('.ai-streaming-status') }).count();
    await askFloating(page, '验收二：核查设备异常');
    await panel(page).getByRole('button', { name: '重试本次回答' }).click();
    await page.waitForFunction(count => document.querySelectorAll('.floating-assistant-panel .floating-assistant-message.assistant:not(:has(.ai-streaming-status))').length > count && !document.querySelector('.floating-assistant-panel [aria-label="停止生成"]'), answersBefore);
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
      await dragLauncher(mobile, { x: width - 1, y: 843 });
      const dragged = await trigger.boundingBox();
      if (tabbar) assert.ok(dragged.y + dragged.height <= tabbar.y, 'dragging cannot cover the mobile navigation');
      assert.equal(await panel(mobile).count(), 0, 'mobile drag does not open the panel');
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
      await panel(mobile).getByText('验收一：今天优先做什么？').first().waitFor();
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
