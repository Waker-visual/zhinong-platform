// 采集共用（品牌片）：只连本工作区自己的演示环境，口令从 .cache 读取，绝不输出
import fs from 'node:fs'; import path from 'node:path';
import { createRequire } from 'node:module'; import { fileURLToPath } from 'node:url';
export const HERE = path.dirname(fileURLToPath(import.meta.url));
export const APP_ROOT = process.env.FARM_APP || path.resolve(HERE, '../../..');
export const WEB = process.env.FARM_WEB || 'http://127.0.0.1:4374';
export const OUT = path.join(HERE, 'out');
export const password = () => fs.readFileSync(path.join(APP_ROOT, '.cache/demo-password.txt'), 'utf8').trim();
export const { chromium } = createRequire(path.join(APP_ROOT, 'frontend/package.json'))('playwright');
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
export async function open({ width = 1920, height = 1080, scheme = 'light', mobile = false, dsf = 0 } = {}) {
  const browser = await chromium.launch({ channel: 'msedge', headless: false, args: ['--use-angle=d3d11', '--enable-gpu', '--window-position=0,0', '--hide-scrollbars', '--disable-backgrounding-occluded-windows', '--disable-renderer-backgrounding', '--disable-features=CalculateNativeWinOcclusion'] });
  const ctx = await browser.newContext({ viewport: { width, height }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', colorScheme: scheme, isMobile: mobile, hasTouch: mobile, deviceScaleFactor: dsf || (mobile ? 3 : 1) });
  const page = await ctx.newPage();
  return { browser, ctx, page };
}
export async function login(page, user = 'admin') {
  await page.goto(WEB + '/'); await page.waitForSelector('form input');
  const inputs = page.locator('form input'); const v = ['demo-a', user, password()];
  for (let i = 0; i < 3; i++) await inputs.nth(i).fill(v[i]);
  await page.locator('form button').last().click();
  await page.locator('text=今日农场 >> visible=true').first().waitFor({ timeout: 30000 }); await sleep(2500);
}
export const shot = async (page, name) => { fs.mkdirSync(path.join(OUT, 'scout'), { recursive: true }); await page.screenshot({ path: path.join(OUT, 'scout', name + '.jpg'), type: 'jpeg', quality: 88 }); console.log('shot', name); };
