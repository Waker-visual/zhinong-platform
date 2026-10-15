import { test } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";

const moduleUrl = new URL('./scheduled-theme.js', import.meta.url).href;
function modes(zone, month, day = 15) {
  const source = `import { scheduledMode } from ${JSON.stringify(moduleUrl)};
    const times = [[5,59],[6,0],[6,59],[7,0],[17,59],[18,0],[19,59],[20,0]];
    console.log(JSON.stringify(times.map(([h,m]) => scheduledMode(new Date(2026,${month-1},${day},h,m)))));`;
  return JSON.parse(execFileSync(process.execPath, ['--input-type=module','-e',source], {
    env: { ...process.env, TZ: zone }, encoding: 'utf8',
  }));
}
const standard = ['DARK','DARK','DARK','LIGHT','LIGHT','DARK','DARK','DARK'];
const summer = ['DARK','LIGHT','LIGHT','LIGHT','LIGHT','LIGHT','LIGHT','DARK'];
test('non-DST time zones retain 07:00–18:00 boundaries in both seasons', () => {
  for (const zone of ['Asia/Taipei','Asia/Shanghai','UTC']) {
    assert.deepEqual(modes(zone,1), standard);
    assert.deepEqual(modes(zone,7), standard);
  }
});
test('northern DST switches to 06:00–20:00 including transition dates', () => {
  assert.deepEqual(modes('America/New_York',1), standard);
  assert.deepEqual(modes('America/New_York',7), summer);
  assert.deepEqual(modes('America/New_York',3,8), summer);
  assert.deepEqual(modes('America/New_York',11,1), standard);
});
test('southern DST uses January summer and July standard time', () => {
  assert.deepEqual(modes('Australia/Sydney',1), summer);
  assert.deepEqual(modes('Australia/Sydney',7), standard);
});
