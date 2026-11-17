// 手机端首页截图（新版 UI），供片尾“智禾随行”一格使用
import fs from 'node:fs'; import path from 'node:path';
import { open, sleep, OUT, WEB, password } from './lib.mjs';
const { browser, page } = await open({ width: 390, height: 844, mobile: true });
await page.goto(WEB + '/'); await page.waitForSelector('form input'); await sleep(1500);
const inputs = page.locator('form input'); const v = ['demo-a', 'admin', password()];
for (let i = 0; i < 3; i++) await inputs.nth(i).fill(v[i]);
await page.locator('form button').last().click(); await sleep(6000);
fs.mkdirSync(path.join(OUT, 'shots'), { recursive: true });
await page.screenshot({ path: path.join(OUT, 'shots', 'm-home.png') });
console.log('url', page.url().replace(/^https?:\/\/[^/]+/, ''));
await browser.close();
