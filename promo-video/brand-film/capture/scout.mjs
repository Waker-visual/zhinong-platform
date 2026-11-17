// 踩点：新 UI 各关键界面各截一张，用来判断哪些素材要重采
import { open, login, shot, sleep, WEB } from './lib.mjs';
const { browser, page } = await open();
const step = async (name, fn) => { try { await fn(); await shot(page, name); } catch (e) { console.log('FAIL', name, String(e).slice(0, 200)); try { await shot(page, name + '-fail'); } catch {} } };
await page.goto(WEB + '/'); await page.waitForSelector('form input'); await sleep(4000);
await shot(page, '01-login');
await login(page);
await step('02-today', async () => { await sleep(1500); });
await step('03-xiaohe-float', async () => { await page.locator('button[aria-label*="小禾"]').last().click(); await sleep(2500); });
await page.keyboard.press('Escape');
await step('04-ai-page', async () => { await page.locator('.sidebar-assistant-entry').click(); await sleep(3500); });
await step('05-display-3d', async () => { await page.locator('.sidebar-scene-entry').click(); await sleep(25000); });
await step('06-display-overview', async () => { await page.locator('button, [role=tab]').filter({ hasText: '农场总览' }).first().click(); await sleep(9000); });
console.log('tabs', await page.locator('[role=tab], .display-tabs button').allInnerTexts().catch(() => []));
await browser.close();
