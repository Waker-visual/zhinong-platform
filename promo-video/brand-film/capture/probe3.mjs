import fs from 'node:fs'; import path from 'node:path'; import crypto from 'node:crypto';
import { open, OUT } from './lib.mjs';
import { enterStage, keepBuffer, everyFrame, grab } from './stage.mjs';
const K = 5, W = 2560, H = 1440;
const { browser, page } = await open({ width: W * K, height: H * K, dsf: 1 / K });
await keepBuffer(page);
await enterStage(page);
await page.clock.install({ time: new Date() }); await page.clock.pauseAt((await page.evaluate(() => Date.now())) + 50);
for (const mode of ['default', 'every']) {
  if (mode === 'every') console.log(await everyFrame(page));
  await page.mouse.move(W * K / 2, H * K / 2); await page.mouse.down(); await page.clock.runFor(100); await page.mouse.move(W * K / 2 + 30, H * K / 2); await page.clock.runFor(100);
  const t0 = Date.now(); const hashes = []; let bytes = 0;
  for (let i = 0; i < 30; i++) { await page.mouse.move(W * K / 2 + 30 + (i + 1) * 3, H * K / 2); await page.clock.runFor(1000 / 60); const b = await grab(page); bytes += b.length; hashes.push(crypto.createHash('md5').update(b).digest('hex').slice(0, 6)); if (i === 29) fs.writeFileSync(path.join(OUT, 'scout', '40-grab-' + mode + '.jpg'), b); }
  await page.mouse.up(); await page.clock.runFor(100);
  console.log(mode, 'ms/frame', ((Date.now() - t0) / 30).toFixed(0), 'distinct', new Set(hashes).size, '/30 avg KB', (bytes / 30 / 1024).toFixed(0));
}
await browser.close();
