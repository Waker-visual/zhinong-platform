// 踩点 2：三维农场的各视角、设备与季节选项；总览页的田园插画地图
import { open, login, shot, sleep } from './lib.mjs';
const { browser, page } = await open();
const step = async (name, fn) => { try { await fn(); await shot(page, name); } catch (e) { console.log('FAIL', name, String(e).slice(0, 240)); } };
await login(page);
await page.locator('.sidebar-scene-entry').click(); await sleep(24000);
const selects = page.locator('select');
for (let i = 0; i < await selects.count(); i++) console.log('select', i, JSON.stringify(await selects.nth(i).locator('option').allInnerTexts()));
console.log('buttons', JSON.stringify((await page.locator('button:visible').allInnerTexts()).map((s) => s.replace(/\s+/g, ' ').trim()).filter(Boolean)));
await step('10-reset', async () => { await page.locator('button').filter({ hasText: '复位视角' }).first().click(); await sleep(5000); });
await step('11-zone3', async () => { await page.locator('button').filter({ hasText: '演示分区 3' }).first().click(); await sleep(6000); });
const dev = page.locator('select').filter({ has: page.locator('option', { hasText: '选择设备' }) }).first();
const names = (await dev.locator('option').allInnerTexts()).filter((s) => !s.includes('选择设备'));
let k = 0;
for (const n of names) { if (!/泵|阀|闸|喷|无人机|大疆|水情/.test(n)) continue; await step(`12-dev-${String(k++).padStart(2, '0')}`, async () => { await dev.selectOption({ label: n }); console.log('  device', n); await sleep(6500); }); }
await step('13-zoom-out', async () => { await page.locator('button').filter({ hasText: '复位视角' }).first().click(); await sleep(3000); for (let i = 0; i < 6; i++) { await page.mouse.move(960, 600); await page.mouse.wheel(0, 400); await sleep(250); } await sleep(3000); });
await step('14-orbit', async () => { await page.mouse.move(960, 620); await page.mouse.down(); await page.mouse.move(1260, 560, { steps: 20 }); await page.mouse.up(); await sleep(2500); });
await step('15-satellite', async () => { await page.locator('button').filter({ hasText: '卫星实景' }).first().click(); await sleep(6000); });
await step('16-overview-pastoral', async () => { await page.locator('button, [role=tab]').filter({ hasText: '农场总览' }).first().click(); await sleep(7000); await page.locator('button').filter({ hasText: '田园插画' }).first().click(); await sleep(5000); });
await browser.close();
