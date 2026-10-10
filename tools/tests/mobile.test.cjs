const { test } = require('node:test');
const assert = require('node:assert/strict');
const load = () => import('../../frontend/src/mobile/client.mjs');

test('missing measurements remain missing; values and state are not fabricated', async () => {
  const { number, freshness, normalize, requestId } = await load();
  assert.equal(number(null), '—'); assert.equal(number(undefined), '—'); assert.equal(number(''), '—'); assert.equal(number(0), '0');
  assert.equal(freshness({ freshness: 'NO_DATA' }), '尚无上报');
  assert.deepEqual(normalize({ DEVICE_ID: 'id', result_note: 'test', lastReceivedAt: null }), { deviceId: 'id', resultNote: 'test', lastReceivedAt: null });
  const ids = new Set(Array.from({ length: 100 }, requestId)); assert.equal(ids.size, 100);
  assert.match([...ids][0], /^mobile-[a-f0-9]{32}$/);
});
test('control eligibility respects roles, lifecycle, protocol and stale feedback; stops remain possible', async () => {
  const { canControl } = await load();
  const d = { controlEnabled: true, lifecycle: 'ACTIVE', freshness: 'FRESH', protocol: 'SIMULATED' };
  const a = { code: 'PUMP_START', role: 'OPERATOR' };
  assert.equal(canControl(d, a, 'VIEWER'), false); assert.equal(canControl(d, a, 'OPERATOR'), true);
  assert.equal(canControl(d, { ...a, role: 'ADMIN' }, 'OPERATOR'), false);
  assert.equal(canControl({ ...d, freshness: 'STALE' }, a, 'ADMIN'), false);
  assert.equal(canControl({ ...d, freshness: 'STALE' }, { ...a, code: 'PUMP_STOP' }, 'OPERATOR'), true);
  assert.equal(canControl({ ...d, lifecycle: 'DISABLED' }, a, 'ADMIN'), false);
  assert.equal(canControl({ ...d, protocol: 'HTTP_PUSH', credentialConfigured: false }, a, 'ADMIN'), false);
});
test('API client does not retry uncertain commands or persist authentication', async () => {
  const { createClient } = await load(); let calls = 0;
  const client = createClient(async (url, init) => { calls++; assert.equal(url, '/api/assets/id/commands'); assert.equal(init.headers.Authorization, 'Bearer temporary'); assert.equal(init.credentials, 'omit'); throw Error('connection lost'); });
  client.setToken('temporary');
  await assert.rejects(client.request('/assets/id/commands', 'POST', { requestId: 'test' }), e => e.uncertain === true && e.status === 0);
  assert.equal(calls, 1);
});
test('late response from a previous session cannot be consumed; expired current session is cleared', async () => {
  const { createClient } = await load(); let resolve;
  const client = createClient(() => new Promise(r => { resolve = r; })); client.setToken('old');
  const request = client.request('/assets'); client.setToken('new');
  resolve(new Response('{}', { status: 200 })); await assert.rejects(request, e => e.stale === true);
  let expired = 0; const next = createClient(async () => new Response('{"message":"expired"}', { status: 401 }), () => expired++);
  next.setToken('temporary'); await assert.rejects(next.request('/assets'), /expired/); assert.equal(expired, 1);
});
