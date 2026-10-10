// Browser acceptance uses an isolated in-memory server, never the local farm database.
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
  fs.mkdirSync(output, { recursive: true });
  const listener = net.createServer();
  await new Promise(resolve => listener.listen(0, '127.0.0.1', resolve));
  const port = listener.address().port;
  await new Promise(resolve => listener.close(resolve));
  const password = crypto.randomBytes(20).toString('hex');
  const log = fs.openSync(path.join(output, 'camera-browser-server.log'), 'w');
  const settings = spawnSync('java', ['-XshowSettings:properties', '-version'], { windowsHide: true, encoding: 'utf8' }).stderr;
  const java = path.join(settings.match(/java.home = ([^\r\n]+)/)[1].trim(), 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
  const server = spawn(java, ['-Dfarm.llm.file-enabled=false', '-jar', 'target/zhinong-platform-0.3.0.jar',
    `--server.port=${port}`, '--spring.web.resources.static-locations=file:../frontend/dist/', '--spring.datasource.url=jdbc:h2:mem:camera-browser;DB_CLOSE_DELAY=-1',
    '--spring.datasource.username=sa', '--spring.datasource.password=', '--farm.demo=true', '--farm.demo-rich=true',
    '--farm.demo-portfolio=true', '--farm.demo-live=true', '--farm.simulation.use-primary=true'],
  { cwd: path.join(root, 'backend'), windowsHide: true, env: { ...process.env, FARM_BOOTSTRAP_PASSWORD: password }, stdio: ['ignore', log, log] });
  const base = `http://127.0.0.1:${port}`;
  let token, browser, page;
  const errors = [];
  async function api(route, method = 'GET', body) {
    const response = await fetch(base + '/api' + route, { method,
      headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body) });
    assert.ok(response.ok, `${method} ${route}: ${response.status}`);
    return response.json();
  }
  async function option(label, name) {
    await page.getByRole('button', { name: label, exact: true }).click();
    await page.getByRole('option', { name, exact: true }).click();
  }
  async function cameraPage() { await page.getByRole('button', { name: '田间实景', exact: true }).click(); }
  async function ready() { await page.locator('.field-cameras .camera-frame[data-media-state="ready"]').waitFor(); }
  try {
    for (let n = 0; n < 120; n++) {
      if (await fetch(base + '/api/health').then(r => r.ok).catch(() => false)) break;
      if (n === 119) throw Error('Camera server readiness timeout');
      await sleep(500);
    }
    token = (await api('/auth/login', 'POST', { tenantCode: 'demo-a', username: 'admin', password })).token;
    const farms = await api('/farm-workspaces');
    assert.equal(farms.length, 5);
    browser = await chromium.launch({ headless: true, channel: 'msedge' });
    const context = await browser.newContext({ viewport: { width: 1500, height: 1100 }, reducedMotion: 'reduce' });
    await context.addInitScript(value => sessionStorage.setItem('zhinong-session', value), token);
    page = await context.newPage();
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(base);
    await cameraPage();
    const sources = new Set(), farmChecks = [];
    for (const farm of farms) {
      await option('当前农场', farm.name);
      await page.locator(`.field-cameras[data-farm-id="${farm.id}"]`).waitFor();
      await ready();
      const cameras = (await api(`/farms/${farm.id}/cameras`)).devices;
      assert.equal(cameras.length, 3);
      assert.equal(cameras[0].farmId, farm.id);
      const image = page.locator('.field-cameras .camera-frame img');
      assert.equal(await image.getAttribute('src'), cameras[0].camera.playbackUrl);
      assert.ok(await image.evaluate(el => el.naturalWidth > 1000));
      sources.add(await image.getAttribute('src'));
      const farmSources = new Set();
      for (const camera of cameras) {
        await page.locator(`[data-camera-id="${camera.id}"]`).click();
        await ready();
        assert.equal(await image.getAttribute('src'), camera.camera.playbackUrl);
        assert.equal(camera.farmId, farm.id);
        assert.ok(camera.plotId && camera.positioned);
        farmSources.add(camera.camera.playbackUrl);
        await page.getByText('演示画面 · 非实时', { exact: true }).waitFor();
      }
      assert.equal(farmSources.size, 3);
      assert.equal(await page.locator('.field-cameras').getByText('合成演示', { exact: false }).count(), 0);
      farmChecks.push({ name: farm.name, cameras: cameras.length, source: cameras[0].camera.playbackUrl });
    }
    assert.equal(sources.size, 5);
    const farm = farms.find(f => f.name === '青禾设备联动演示场');
    await option('当前农场', farm.name);
    await ready();
    const initialCamera = (await api(`/farms/${farm.id}/cameras`)).devices[0];
    await page.locator(`[data-camera-id="${initialCamera.id}"]`).click();
    await ready();
    await page.locator('.field-cameras').screenshot({ path: path.join(output, 'camera-desktop.png') });
    await page.getByRole('button', { name: '全屏查看', exact: true }).click();
    assert.equal(await page.evaluate(() => document.fullscreenElement?.className), 'camera-frame');
    await page.evaluate(() => document.exitFullscreen());
    await page.getByRole('button', { name: '管理这台摄像头 →', exact: true }).click();
    await page.getByRole('dialog', { name: '设备详情', exact: true }).waitFor();
    await page.getByRole('button', { name: '编辑设备', exact: true }).click();
    await page.getByLabel('机位说明', { exact: true }).fill('东侧稻田观察机位');
    await page.getByRole('button', { name: '保存设备', exact: true }).click();
    await page.getByRole('dialog', { name: '设备详情', exact: true }).waitFor();
    await page.getByRole('button', { name: '关闭设备详情', exact: true }).click();
    assert.equal(await page.locator('[data-asset-id]').count(), 3);
    await page.locator(`[data-asset-id="${initialCamera.id}"]`).getByRole('button', { name: '查看画面', exact: true }).click();
    await ready();
    assert.equal(await page.locator('.camera-heading h3').innerText(), '东侧稻田观察机位');
    await page.reload();
    await cameraPage();
    await ready();
    assert.equal(await page.locator('.camera-heading h3').innerText(), '东侧稻田观察机位');

    const original = (await api(`/farms/${farm.id}/cameras`)).devices[0];
    assert.equal(original.latitude, initialCamera.latitude, 'Editing media must preserve coordinate precision');
    assert.equal(original.longitude, initialCamera.longitude, 'Editing media must preserve coordinate precision');
    async function update(patch) {
      const current = await api('/assets/' + original.id);
      const body = Object.fromEntries(['farmId','name','code','deviceType','protocol','lifecycle','plotId','planX','planY','model','notes','intervalSeconds','revision','locationMode','latitude','longitude','controlEnabled'].map(k => [k, current[k]]));
      body.channels = current.channels.map(c => ({ metric: c.metric, lowerLimit: c.lowerLimit, upperLimit: c.upperLimit }));
      body.camera = { mode: current.camera.mode, demoScene: current.camera.demoScene, sourceUrl: current.camera.sourceUrl, viewLabel: current.camera.viewLabel };
      Object.assign(body, patch);
      await api('/assets/' + original.id, 'PUT', body);
      await page.getByRole('button', { name: '刷新数据', exact: true }).click();
    }
    await update({ lifecycle: 'DISABLED' });
    await page.getByText('摄像头已停用', { exact: true }).waitFor();
    assert.equal(await page.locator('.field-cameras .camera-frame img').count(), 0);
    await update({ lifecycle: 'ACTIVE', camera: { mode: 'NONE' } });
    await page.getByText('尚未配置画面来源', { exact: true }).waitFor();
    await page.route('https://camera-demo.invalid/field.png', route => route.fulfill({ status: 404, body: '' }));
    await update({ camera: { mode: 'IMAGE', sourceUrl: 'https://camera-demo.invalid/field.png' } });
    await page.getByText('画面加载失败', { exact: true }).waitFor();
    await page.unroute('https://camera-demo.invalid/field.png');
    await page.route('https://camera-demo.invalid/field.png', route => route.fulfill({ contentType: 'image/png', body: fs.readFileSync(path.join(root, 'frontend/public/camera-demo/qinghe.png')) }));
    await page.getByRole('button', { name: '重新加载', exact: true }).click();
    await ready();
    await page.getByText('图片源 · 非实时视频', { exact: true }).waitFor();

    // A short in-browser recording exercises native video playback without an external camera.
    const recordingPage = await context.newPage();
    await recordingPage.bringToFront();
    const clip = await recordingPage.evaluate(async () => {
      const canvas = document.createElement('canvas'); canvas.width = 320; canvas.height = 180;
      document.body.appendChild(canvas);
      const ctx = canvas.getContext('2d'); ctx.fillStyle = 'green'; ctx.fillRect(0, 0, 320, 180);
      const stream = canvas.captureStream(0), chunks = [];
      const recorder = new MediaRecorder(stream, { mimeType: 'video/webm;codecs=vp8' });
      const ended = new Promise(resolve => { recorder.onstop = resolve; });
      recorder.ondataavailable = event => chunks.push(event.data); recorder.start();
      let frame = 0;
      const drawing = setInterval(() => { ctx.fillStyle = frame++ % 2 ? 'green' : 'olive'; ctx.fillRect(0, 0, 320, 180); stream.getVideoTracks()[0].requestFrame(); }, 80);
      await new Promise(resolve => setTimeout(resolve, 2200));
      recorder.stop(); await ended; clearInterval(drawing); stream.getTracks().forEach(track => track.stop()); canvas.remove();
      return Array.from(new Uint8Array(await new Blob(chunks).arrayBuffer()));
    });
    await recordingPage.close();
    await page.bringToFront();
    assert.ok(clip.length > 500, 'Test video must contain encoded frames');
    await page.route('https://camera-demo.invalid/field.webm', route => route.fulfill({ contentType: 'video/webm', body: Buffer.from(clip) }));
    await update({ camera: { mode: 'VIDEO', sourceUrl: 'https://camera-demo.invalid/field.webm' } });
    await page.locator('.field-cameras video').waitFor();
    await ready();
    await page.locator('.field-cameras video').evaluate(video => video.play());
    await page.waitForFunction(() => document.querySelector('.field-cameras video')?.currentTime > 0);
    await page.locator('.field-cameras video').evaluate(video => video.pause());
    await update({ camera: { mode: 'DEMO_IMAGE', demoScene: 'qinghe', viewLabel: '东侧稻田观察机位' } });
    await page.locator('.field-cameras img[src="/camera-demo/qinghe.png"]').waitFor();
    await ready();
    await page.evaluate(() => { document.documentElement.dataset.theme = 'dark'; });
    await page.locator('.field-cameras').screenshot({ path: path.join(output, 'camera-dark.png') });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.evaluate(() => { document.documentElement.dataset.theme = 'light'; });
    await page.locator('.camera-main').scrollIntoViewIfNeeded();
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 2), false);
    await page.screenshot({ path: path.join(output, 'camera-mobile.png') });
    await page.setViewportSize({ width: 1500, height: 1100 });
    await page.getByRole('button', { name: '管理这台摄像头 →', exact: true }).click();
    await page.getByRole('button', { name: '关闭设备详情', exact: true }).click();
    await page.getByRole('button', { name: '新增设备', exact: false }).click();
    await page.getByLabel('设备名称', { exact: true }).fill('测试新增田间摄像头');
    await page.getByLabel('设备编码', { exact: true }).fill('TEST-CAMERA-NEW');
    await page.getByRole('button', { name: '保存设备', exact: true }).click();
    await page.getByRole('dialog', { name: '设备详情', exact: true }).waitFor();
    const added = (await api(`/farms/${farm.id}/cameras`)).devices.find(d => d.code === 'TEST-CAMERA-NEW');
    assert.ok(added);
    assert.equal(added.channels.length, 1);
    assert.equal(added.channels[0].metric, 'CAMERA_ONLINE');
    await page.getByRole('button', { name: '关闭设备详情', exact: true }).click();
    await page.locator(`[data-asset-id="${added.id}"]`).getByRole('button', { name: '查看画面', exact: true }).click();
    await ready();
    assert.equal(await page.locator('.camera-heading h3').innerText(), '测试新增田间摄像头');
    assert.equal(await page.locator('[data-camera-id]').count(), 4);
    const emptyFarm = await api('/farm-workspaces', 'POST', { name: '摄像头空状态验收', description: '临时测试', region: '测试区域', farmType: 'FIELD' });
    await page.getByRole('button', { name: '刷新数据', exact: true }).click();
    await option('当前农场', emptyFarm.name);
    await page.getByText('本农场尚无摄像头', { exact: true }).waitFor();
    assert.equal(await page.locator('.field-cameras img').count(), 0);
    assert.deepEqual(errors, []);
    const result = { farmChecks, distinctScenes: sources.size, fullscreen: true, ledgerEditAndReturn: true,
      persistedAfterReload: true, disabledState: true, unconfiguredState: true, failedImageRetry: true, imageSource: true,
      videoPlayPause: true, createFromLedger: true, emptyFarm: true, mobile: true, darkTheme: true, consoleErrors: errors };
    fs.writeFileSync(path.join(output, 'camera-browser-result.json'), JSON.stringify(result, null, 2));
    console.log(JSON.stringify(result));
  } catch (error) {
    if (page) await page.screenshot({ path: path.join(output, 'camera-failure.png'), fullPage: true }).catch(() => {});
    throw error;
  } finally {
    if (token) await api('/auth/logout', 'POST').catch(() => {});
    await browser?.close(); server.kill(); fs.closeSync(log);
  }
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
