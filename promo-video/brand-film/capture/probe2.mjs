import fs from 'node:fs'; import path from 'node:path';
import { open, sleep, OUT } from './lib.mjs';
import { enterStage, send, points } from './stage.mjs';
import { fitH, applyH } from './homography.mjs';
const K = 5; // 视口放大倍数：1 个 CSS 像素 = 1/K 个画面像素
const W = 2560, H = 1440;
const { browser, page } = await open({ width: W * K, height: H * K, dsf: 1 / K });
await enterStage(page);
const scene = JSON.parse(await page.evaluate(() => window.__farm.scene));
const cen = (pts) => [pts.reduce((a, p) => a + p.x, 0) / pts.length, pts.reduce((a, p) => a + p.z, 0) / pts.length];
const world = Object.fromEntries(scene.plots.map((p) => [p.parcelId, cen(p.points)]));
const fit = async () => { const pts = (await points(page)).filter((p) => p.kind === 'parcel' && world[p.id]); const src = pts.map((p) => world[p.id]), dst = pts.map((p) => [p.x, p.y]); const Hm = fitH(src, dst);
  const err = Math.max(...src.map((s, i) => Math.hypot((applyH(Hm, s)[0] - dst[i][0]) * W, (applyH(Hm, s)[1] - dst[i][1]) * H))); return { Hm, err, pts }; };
const spread = async () => { const { pts } = await fit(); const a = pts.find((p) => p.id === scene.plots[3].parcelId), b = pts.find((p) => p.id === scene.plots[5].parcelId); return Math.hypot((a.x - b.x) * W, (a.y - b.y) * H); };
const step = async (n = 1) => { for (let i = 0; i < n; i++) await page.clock.runFor(1000 / 60); };
await page.clock.install({ time: new Date() }); await page.clock.resume(); await sleep(500);
await send(page, 'ResetView'); await sleep(1500);
let f = await fit(); console.log('reset fit err px', f.err.toFixed(2), 'n', f.pts.length, 'kinds', [...new Set((await points(page)).map((p) => p.kind))].join(','));
const t0 = Date.now(); await page.screenshot({ path: path.join(OUT, 'scout', '30-big.jpg'), type: 'jpeg', quality: 92 }); console.log('shot ms', Date.now() - t0);
// 滚轮标定：Zoom in = ×0.78
const s0 = await spread(); await send(page, 'Zoom', 'in'); await sleep(800); const s1 = await spread(); await send(page, 'ResetView'); await sleep(800);
await page.mouse.move(W * K / 2, H * K / 2); await page.mouse.wheel(0, -100); await sleep(800); const s2 = await spread(); await send(page, 'ResetView'); await sleep(800);
await page.mouse.wheel(0, -10); await sleep(800); const s3 = await spread(); await send(page, 'ResetView'); await sleep(800);
console.log('spread base', s0.toFixed(1), 'zoomIn', (s1 / s0).toFixed(4), 'wheel-100', (s2 / s0).toFixed(4), 'wheel-10', (s3 / s0).toFixed(4));
// 拖动标定：左键拖 100 CSS px 应为 100/K 画面像素 × 0.25°
await page.mouse.move(W * K / 2, H * K / 2); await page.mouse.down(); await page.mouse.move(W * K / 2 + 50, H * K / 2); await sleep(300); await page.mouse.move(W * K / 2 + 400, H * K / 2, { steps: 5 }); await sleep(600);
f = await fit(); console.log('after drag fit err', f.err.toFixed(2)); await page.screenshot({ path: path.join(OUT, 'scout', '31-big-drag.jpg'), type: 'jpeg', quality: 92 }); await page.mouse.up();
// 虚拟时钟逐帧：暂停后步进 30 帧，记录每帧耗时与点位是否更新
const now = await page.evaluate(() => Date.now()); await page.clock.pauseAt(now + 50);
await page.mouse.move(W * K / 2, H * K / 2); await page.mouse.down(); await page.mouse.move(W * K / 2 + 20, H * K / 2); await step();
const t1 = Date.now(); const xs = [];
for (let i = 0; i < 30; i++) { await page.mouse.move(W * K / 2 + 20 + (i + 1) * 2, H * K / 2); await page.mouse.wheel(0, -1); await step(); xs.push((await fit()).pts[4].x.toFixed(5)); await page.screenshot({ path: path.join(OUT, 'scout', 'tmp.jpg'), type: 'jpeg', quality: 92 }); }
console.log('ms/frame', ((Date.now() - t1) / 30).toFixed(0), 'x series', xs.slice(0, 12).join(' '));
await browser.close();
