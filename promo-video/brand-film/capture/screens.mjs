// 界面实录（新版 UI）：登录页的像素田园（只取左侧风景）、四季、展示大屏的三维农场页。
// 用法：node capture/screens.mjs login | seasons | display     ONLY=l-spring,l-summer 可只录部分
import fs from 'node:fs'; import path from 'node:path';
import { open, login, sleep, OUT, WEB } from './lib.mjs';
const group = process.argv[2];
const FPS = 60, STEP = 1000 / FPS;
const ROOT = path.join(OUT, 'rec');
// 登录页风景区：左侧 1188 px 宽；取其中 16:9 的一条（2 倍像素密度录制，成片缩到 1920）
const SCENERY = { x: 0, y: 372, width: 1188, height: 668 };
const { browser, page } = await open({ dsf: group === 'display' ? 1 : 2 });
const startAt = group === 'login' ? new Date('2026-11-17T05:20:00+08:00') : new Date('2026-11-17T09:40:00+08:00');
if (group !== 'display') { await page.clock.install({ time: startAt }); await page.clock.resume(); }
const park = () => page.mouse.move(1919, 1070);
async function rec(name, seconds, { clip, action } = {}) {
  if (process.env.ONLY && !process.env.ONLY.split(',').includes(name)) { console.log('skip', name); return; }
  const dir = path.join(ROOT, name); fs.mkdirSync(dir, { recursive: true });
  const total = Math.round(seconds * FPS); const t0 = Date.now();
  for (let i = 0; i < total; i++) {
    if (action && i === action.at) { await action.do(); await park(); }
    await page.clock.runFor(STEP);
    await page.screenshot({ path: path.join(dir, 'f' + String(i).padStart(4, '0') + '.jpg'), type: 'jpeg', quality: 93, clip });
  }
  console.log(name, total, 'frames', ((Date.now() - t0) / total).toFixed(0), 'ms/frame');
}
// 逐帧录制期间按原样回放最近一次接口响应，画面数据停在开录那一刻
const cache = new Map();
page.on('response', async (res) => {
  if (res.request().method() !== 'GET' || !res.url().includes('/api/') || res.url().includes('/map-tiles')) return;
  try { cache.set(res.url(), { status: res.status(), headers: res.headers(), body: await res.body() }); } catch {}
});
const freeze = () => page.route('**/api/**', async (route) => {
  const hit = route.request().method() === 'GET' && cache.get(route.request().url());
  if (hit) await route.fulfill({ status: hit.status, headers: hit.headers, body: hit.body }); else await route.continue();
});
const pause = async () => { await freeze(); const now = await page.evaluate(() => Date.now()); await page.clock.pauseAt(now + 50); };

if (group === 'login' || group === 'seasons') {
  await page.goto(WEB + '/'); await page.waitForSelector('form input'); await sleep(3000);
  await park(); await pause();
  if (group === 'login') {
    await rec('l-daytour', 14, { clip: SCENERY, action: { at: 6, do: () => page.locator('[data-tour="day"]').dispatchEvent('click') } });
  } else {
    await rec('l-day', 3, { clip: SCENERY });
    for (const s of ['spring', 'summer', 'autumn', 'winter']) {
      await page.locator('.login-scenario-toggle').dispatchEvent('click'); await page.clock.runFor(300);
      await page.locator('[data-scenario="' + s + '"]').dispatchEvent('click'); await page.clock.runFor(2000); await park();
      await rec('l-' + s, 3, { clip: SCENERY });
    }
  }
}
if (group === 'display') {
  await login(page);
  await page.locator('.sidebar-scene-entry').click();
  await page.locator('.farm-landscape iframe').waitFor({ timeout: 60000 });
  await page.waitForFunction(() => !document.querySelector('.farm-landscape-loading'), null, { timeout: 120000 });
  await sleep(6000); await park();
  await page.clock.install({ time: new Date() }); await pause();
  await rec('d-farm3d', 6, { action: { at: 45, do: () => page.locator('button').filter({ hasText: '演示分区 3' }).first().dispatchEvent('click') } });
}
await browser.close();
