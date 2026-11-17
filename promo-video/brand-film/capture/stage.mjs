// 三维农场“摄影棚”：把展示大屏里的 Unity 画面铺满 1920×1080、隐藏面板，
// 并提供镜头控制（拖动=环绕、滚轮=推拉、右键=平移）和设备演示状态设定。
// 演示状态（水泵运行、闸门开度、分区出水）由脚本经产品自己的 UpdateDevices 通道设定，属模拟演示，不代表真实设备。
import { login, sleep } from './lib.mjs';
export async function enterStage(page, { markers = false } = {}) {
  await login(page);
  await page.locator('.sidebar-scene-entry').click();
  await page.locator('.farm-landscape iframe').waitFor({ timeout: 60000 });
  await page.evaluate(() => {
    const frame = document.querySelector('.farm-landscape iframe'); const w = frame.contentWindow;
    const post = w.postMessage.bind(w);
    window.__farm = { points: [], last: null, scene: null, override: null, send: (method, value = '') => post({ type: 'farm-command', method, value }, location.origin) };
    w.postMessage = (data, origin) => {
      if (data?.type === 'farm-command' && data.method === 'LoadFarm') window.__farm.scene = data.value;
      if (data?.type === 'farm-command' && data.method === 'UpdateDevices') { window.__farm.last = data.value; if (window.__farm.override) data = { ...data, value: window.__farm.override(data.value) }; }
      return post(data, origin);
    };
    window.addEventListener('message', (e) => { if (e.data?.type === 'farm-points') window.__farm.points = e.data.points; });
  });
  await page.waitForFunction(() => !document.querySelector('.farm-landscape-loading'), null, { timeout: 120000 });
  await page.addStyleTag({ content: `
    .farm-landscape{position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;z-index:99990!important;border-radius:0!important;margin:0!important;border:0!important}
    .farm-landscape iframe{position:absolute!important;inset:0!important;width:100%!important;height:100%!important;border:0!important;border-radius:0!important}
    .farm-landscape-heading,.farm-landscape-plots,.farm-landscape-device,.farm-landscape-dock,.farm-landscape-caption,.farm-landscape-summary{display:none!important}
    ${markers ? '' : '.farm-landscape-markers{display:none!important}'}
    *{cursor:none!important}` });
  await page.waitForFunction(() => window.__farm.last, null, { timeout: 30000 }).catch(() => console.log('WARN: 未截到 UpdateDevices'));
  await sleep(1500);
}
// 设定演示状态：{ devices: { [id]: {running, opening} }, zones: { [id]: true } }
export const setDemo = (page, state) => page.evaluate((s) => {
  window.__farmState = s;
  window.__farm.override = (json) => { const v = JSON.parse(json);
    for (const d of v.devices || []) if (s.devices?.[d.id]) Object.assign(d, { known: true }, s.devices[d.id]);
    for (const z of v.zones || []) if (s.zones?.[z.id] !== undefined) z.running = s.zones[z.id];
    return JSON.stringify(v); };
  if (window.__farm.last) window.__farm.send('UpdateDevices', window.__farm.override(window.__farm.last));
}, state);
export const send = (page, method, value = '') => page.evaluate(([m, v]) => window.__farm.send(m, v), [method, value]);
export const points = (page) => page.evaluate(() => window.__farm.points);
// 直接从 Unity 画布取帧（比整页截图快一个量级）。
// keepBuffer 须在打开页面前调用：让场景的 WebGL 上下文保留绘图缓冲，之后任何时刻都能读到刚渲染的一帧。
export const keepBuffer = (page) => page.addInitScript(() => {
  const get = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = function (type, attrs) { return get.call(this, type, /webgl/.test(type) ? { ...(attrs || {}), preserveDrawingBuffer: true } : attrs); };
});
// 场景默认按 30 帧/秒自限；录制时改为每个动画帧都渲染，配合 60 帧/秒的虚拟时钟
export const everyFrame = (page) => page.evaluate(() => {
  const w = document.querySelector('.farm-landscape iframe').contentWindow; const inst = w.eval('typeof farmInstance === "undefined" ? null : farmInstance');
  const fn = inst?.Module?._emscripten_set_main_loop_timing; if (fn) fn(1, 1);
  const c = w.document.getElementById('farm'); return { timing: !!fn, size: [c.width, c.height] };
});
export const grab = async (page) => { const url = await page.evaluate(() => document.querySelector('.farm-landscape iframe').contentWindow.document.getElementById('farm').toDataURL('image/jpeg', 0.94)); return Buffer.from(url.slice(url.indexOf(',') + 1), 'base64'); };
