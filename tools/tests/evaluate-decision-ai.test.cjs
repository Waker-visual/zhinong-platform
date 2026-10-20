const { test } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { run } = require('../evaluate_decision_ai.cjs');

function fixture(options = {}) {
  const loginSecret = crypto.randomBytes(24).toString('base64url');
  const sessionSecret = crypto.randomBytes(32).toString('base64url');
  const providerSecret = 'sk-' + crypto.randomBytes(24).toString('hex');
  const env = { FARM_EVAL_BASE_URL: 'http://127.0.0.1:19176', FARM_EVAL_TOKEN: sessionSecret,
    FARM_EVAL_CASES: '1', FARM_EVAL_PROVIDER_KIND: 'real', ...options.env };
  const calls = [];
  const evidence = { capturedAt: '2026-10-16T12:00:00Z', plotName: '私有演示地块名', crop: '虚构作物',
    quality: 'FRESH', ruleVersion: 'test-v1', limitations: ['模拟读数用于流程测试'],
    readings: [{ deviceId: 'private-sensor-id', deviceName: '私有演示设备名', metric: 'SOIL_MOISTURE',
      unit: '%', value: 20, lowerLimit: 30, observedAt: '2026-10-16T11:59:00Z', fresh: true, source: 'SIMULATED' }],
    ...options.evidence };
  async function fetch(url, request) {
    const route = new URL(url).pathname;
    calls.push({ route, method: request.method });
    let data;
    if (route.endsWith('/auth/login')) {
      assert.equal(JSON.parse(request.body).password === loginSecret, true);
      data = { token: sessionSecret };
    } else if (route.endsWith('/auth/logout')) data = { ok: true };
    else if (route.endsWith('/auth/me')) data = { role: 'ADMIN' };
    else if (route.endsWith('/ai/status')) data = { llm: true, model: 'fixture-model', ...options.status };
    else if (route.endsWith('/farm-workspaces')) data = [{ id: 'private-farm-id', name: '私有演示农场名',
      demo: true, plots: [{ id: 'private-plot-id', name: evidence.plotName }], ...options.farm }];
    else if (route.endsWith('/ai/decisions')) {
      assert.equal(request.method, 'POST');
      data = { id: 'private-decision-id', evidence };
    } else if (route.endsWith('/explain')) {
      assert.equal(request.method, 'POST');
      data = { mode: 'llm', diagnostic: 'OK', model: 'fixture-model', explanation: '仅为工具传输替身的回复，不是农业质量证据。', ...options.result };
    } else throw new Error('Unexpected evaluation API route');
    return { ok: true, json: async () => data };
  }
  return { env, calls, fetch, loginSecret, sessionSecret, providerSecret, evidence };
}

test('without credentials or in dry-run the recorder never connects', async () => {
  let requests = 0;
  const fetch = async () => { requests++; throw new Error('No network expected'); };
  assert.equal((await run({}, { fetch })).reason, 'MISSING_CREDENTIALS');
  assert.equal((await run({ FARM_EVAL_TOKEN: 'unused-test-only-value' }, { fetch, dryRun: true })).reason, 'DRY_RUN');
  assert.equal(requests, 0);
});

test('insecure remote origins and secret-bearing API addresses are rejected before connecting', async () => {
  for (const base of ['http://example.invalid', 'ftp://localhost', 'https://example.invalid/api',
    'https://example.invalid?token=unused', 'https://user:unused@example.invalid', 'not a URL']) {
    let requests = 0;
    const report = await run({ FARM_EVAL_BASE_URL: base }, { fetch: async () => { requests++; } });
    assert.equal(report.status, 'NOT_EVALUATED');assert.equal(report.reason, 'INVALID_API_BASE');assert.equal(requests, 0);
  }
});

test('manual or hardware-origin evidence is rejected without requesting a model explanation', async () => {
  for (const source of ['MANUAL', 'PUMP_MQTT', 'LAN_DTU']) {
    const context = fixture({ evidence: { readings: [{ value: 22, source }] } });
    const report = await run(context.env, { fetch: context.fetch });
    assert.equal(report.status, 'NOT_EVALUATED');
    assert.equal(report.cases[0].reason, 'NON_SYNTHETIC_EVIDENCE_REJECTED');
    assert.equal(report.cases[0].evidence, null);
    assert.equal(context.calls.some(c => c.route.endsWith('/explain')), false);
  }
});

test('rule fallbacks and unconfigured models do not count as evaluated answers', async () => {
  const context = fixture({ result: { mode: 'rule', diagnostic: 'TIMEOUT', explanation: '规则回退内容' } });
  const report = await run(context.env, { fetch: context.fetch });
  assert.equal(report.status, 'NOT_EVALUATED');assert.equal(report.qualityScore, null);
  assert.equal(report.cases[0].reason, 'RULE_OR_ERROR_FALLBACK');
  assert.equal(report.cases[0].diagnostic, 'TIMEOUT');
  assert.equal(report.cases[0].reply, '规则回退内容');
  const missing = fixture({ status: { llm: false } });
  const unconfigured = await run(missing.env, { fetch: missing.fetch });
  assert.equal(unconfigured.reason, 'REAL_MODEL_NOT_CONFIGURED');
  assert.equal(unconfigured.cases.length, 0);
  assert.equal(missing.calls.some(c => c.route.endsWith('/ai/decisions')), false);
});

test('successful model replies stay unscored and provider provenance still needs verification', async () => {
  const context = fixture();
  const report = await run(context.env, { fetch: context.fetch });
  assert.equal(report.status, 'RESPONSES_RECORDED_UNSCORED');assert.equal(report.qualityScore, null);
  assert.equal(report.providerProvenance, 'operator-declared-real-unverified');
  assert.equal(report.reason, 'HUMAN_REVIEW_AND_PROVIDER_VERIFICATION_REQUIRED');
  const record = report.cases[0];
  assert.equal(record.status, 'MODEL_RESPONSE_UNSCORED');
  assert.equal(Object.values(record.humanReview).every(value => value === null), true);
  assert.equal(Number.isInteger(record.elapsedMs), true);assert.equal(record.model, 'fixture-model');
  assert.equal(record.evidence.readings[0].value, 20);
  assert.equal(context.calls.some(c => /approve|reviews|control/.test(c.route)), false);
});

test('blank model responses do not count as usable model answers', async () => {
  const context = fixture({ result: { explanation: ' ' } });
  const report = await run(context.env, { fetch: context.fetch });
  assert.equal(report.status, 'NOT_EVALUATED');assert.equal(report.cases[0].status, 'NOT_EVALUATED');
});

test('credential echoes and private resource names are removed from every persisted report field', async () => {
  const context = fixture();
  const echo = `${context.loginSecret} ${context.sessionSecret} Bearer ${context.sessionSecret} ${context.providerSecret}`;
  context.env.FARM_EVAL_TOKEN = '';
  context.env.FARM_EVAL_PASSWORD = context.loginSecret;
  context.env.FARM_EVAL_MODEL_LABEL = echo;
  context.evidence.limitations = [echo];context.evidence.crop = echo;
  const report = await run(context.env, { fetch: async (url, request) => {
    const result = await context.fetch(url, request);
    const data = await result.json();
    if (new URL(url).pathname.endsWith('/explain')) Object.assign(data, { model: echo,
      explanation: `${echo} 私有演示农场名 私有演示地块名 私有演示设备名 private-farm-id private-plot-id private-sensor-id` });
    return { ok: true, json: async () => data };
  } });
  const serialized = JSON.stringify(report);
  for (const forbidden of [context.loginSecret, context.sessionSecret, context.providerSecret,
    '私有演示农场名', '私有演示地块名', '私有演示设备名', 'private-farm-id', 'private-plot-id', 'private-sensor-id', 'private-decision-id'])
    assert.equal(serialized.includes(forbidden), false);
  assert.equal(serialized.includes('[REDACTED]'), true);
  assert.equal(context.calls.at(-1).route, '/api/auth/logout');
});

test('ordinary farms cannot be used and an externally supplied session is not logged out', async () => {
  const context = fixture({ farm: { demo: false } });
  const report = await run(context.env, { fetch: context.fetch });
  assert.equal(report.reason, 'SYNTHETIC_FARM_REQUIRED');
  assert.equal(context.calls.some(c => c.route.endsWith('/ai/decisions')), false);
  assert.equal(context.calls.some(c => c.route.endsWith('/logout')), false);
});
