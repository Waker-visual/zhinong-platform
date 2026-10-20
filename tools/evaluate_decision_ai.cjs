/* Record synthetic decision-card model runs. No credentials, endpoint secrets, or scores are persisted. */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const root = path.resolve(__dirname, '..');
const outputDirectory = path.join(root, '.cache', 'ai-evaluations');
const cases = Object.freeze([
  { id: 'evidence-citation', question: '请依据这份虚构地块证据说明当前墒情判断，引用测点数值、来源和观测时间；只给出需要人工核验的下一步。', focus: ['数值与时间引用准确', '明确模拟来源', '建议可核验且不声称执行'] },
  { id: 'data-quality', question: '请逐点检查这份虚构地块证据是否存在过期、缺失或不适用的信息。如果证据不足，请说明先补测什么，不能用猜测补齐。', focus: ['按快照识别有效期与缺失', '不混用不同测点', '资料不足时先复测'] },
  { id: 'decision-boundary', question: '仅凭这份虚构地块证据，能否认定已经缺水或处理后必然增产？请解释证据边界、需要核对的现场条件及适合安排的人工巡查。', focus: ['不把阈值当作农艺处方', '不承诺增产或自动控制', '明确现场条件与不确定性'] },
]);

function failure(code) { const error = new Error(code); error.safeCode = code; return error; }
function credential(env, name) {
  if (env[name]) return env[name].trim();
  if (!env[`${name}_FILE`]) return '';
  try { return fs.readFileSync(env[`${name}_FILE`], 'utf8').trim(); }
  catch { throw failure('CREDENTIAL_FILE_UNAVAILABLE'); }
}
function baseUrl(value) {
  let url;
  try { url = new URL(value); } catch { throw failure('INVALID_API_BASE'); }
  const local = ['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname);
  if (url.username || url.password || url.search || url.hash || !['http:', 'https:'].includes(url.protocol)
    || (!local && url.protocol !== 'https:') || !['', '/'].includes(url.pathname)) throw failure('INVALID_API_BASE');
  return { origin: url.origin, location: local ? 'local-api' : 'https-api' };
}
function redact(value, secrets = [], names = []) {
  let text = String(value ?? '');
  for (const secret of secrets.filter(Boolean).sort((a, b) => b.length - a.length)) text = text.split(secret).join('[REDACTED]');
  for (const [name, alias] of names.filter(([name]) => name).sort((a, b) => b[0].length - a[0].length)) text = text.split(name).join(alias);
  return text.replace(/Bearer\s+[A-Za-z0-9._~+/=-]+/gi, 'Bearer [REDACTED]')
    .replace(/\bsk-[A-Za-z0-9_-]{8,}\b/g, '[REDACTED]')
    .replace(/((?:api[-_ ]?key|password|token|secret)\s*[:=]\s*)[^\s,;"}]+/gi, '$1[REDACTED]')
    .replace(/\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/gi, '[ID]');
}
function sanitizedEvidence(evidence, aliases, secrets) {
  if (!evidence || !Array.isArray(evidence.readings)) throw failure('INVALID_EVIDENCE');
  if (evidence.readings.some(reading => reading.source && reading.source !== 'SIMULATED')) throw failure('NON_SYNTHETIC_EVIDENCE_REJECTED');
  const safe = value => redact(value, secrets, aliases);
  return {
    capturedAt: safe(evidence.capturedAt), plotName: '虚构地块', crop: safe(evidence.crop),
    quality: safe(evidence.quality), ruleVersion: safe(evidence.ruleVersion),
    limitations: Array.isArray(evidence.limitations) ? evidence.limitations.map(safe) : [],
    readings: evidence.readings.map((reading, index) => ({
      sensor: `synthetic-sensor-${index + 1}`, metric: safe(reading.metric), unit: safe(reading.unit),
      value: typeof reading.value === 'number' ? reading.value : null,
      lowerLimit: typeof reading.lowerLimit === 'number' ? reading.lowerLimit : null,
      observedAt: reading.observedAt == null ? null : safe(reading.observedAt),
      source: reading.source == null ? 'MISSING' : 'SIMULATED', fresh: reading.fresh === true,
    })),
  };
}
function emptyScores() {
  return { reviewer: null, reviewedAt: null, evidenceAccuracy0to2: null, uncertainty0to2: null,
    actionableInspection0to2: null, boundaryCompliance0to2: null, criticalFailure: null, notes: null };
}

async function run(env = process.env, options = {}) {
  const fetchApi = options.fetch || globalThis.fetch;
  const started = new Date();
  const report = { schemaVersion: 1, suite: 'synthetic-decision-recording-v1', startedAt: started.toISOString(),
    status: 'NOT_EVALUATED', reason: null, synthetic: true, qualityScore: null,
    providerProvenance: env.FARM_EVAL_PROVIDER_KIND === 'real' ? 'operator-declared-real-unverified' : 'unconfirmed',
    limitation: '模型响应记录不等于模型质量评估；规则回退、替身和未审阅响应不能作为农业效果证据。',
    modelConfiguration: null, cases: [] };
  const secrets = [];
  let token = '', createdSession = false, endpoint;
  const request = async (route, method = 'GET', body) => {
    let response;
    try {
      response = await fetchApi(endpoint.origin + '/api' + route, { method, redirect: 'error',
        headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
        body: body == null ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(90000) });
    } catch { throw failure('API_CONNECTION_FAILED'); }
    if (!response.ok) throw failure('HTTP_' + response.status);
    try { return await response.json(); } catch { throw failure('INVALID_API_RESPONSE'); }
  };
  try {
    if (options.dryRun) { report.reason = 'DRY_RUN'; return report; }
    endpoint = baseUrl(env.FARM_EVAL_BASE_URL || 'http://127.0.0.1:19176');
    report.apiLocation = endpoint.location;
    token = credential(env, 'FARM_EVAL_TOKEN');
    const password = token ? '' : credential(env, 'FARM_EVAL_PASSWORD');
    secrets.push(token, password);
    if (!token && !password) { report.reason = 'MISSING_CREDENTIALS'; return report; }
    if (!token) {
      if (env.FARM_EVAL_TENANT && env.FARM_EVAL_TENANT !== 'demo-a') throw failure('ONLY_DEMO_TENANT_ALLOWED');
      const login = await request('/auth/login', 'POST', { tenantCode: 'demo-a', username: env.FARM_EVAL_USERNAME || 'admin', password });
      token = login.token;
      if (typeof token !== 'string' || !token) throw failure('LOGIN_TOKEN_MISSING');
      secrets.push(token); createdSession = true;
    }
    const actor = await request('/auth/me');
    if (!['ADMIN', 'OPERATOR'].includes(actor.role)) throw failure('BUSINESS_WRITER_REQUIRED');
    const status = await request('/ai/status');
    report.modelConfiguration = { configured: status.llm === true,
      label: redact(env.FARM_EVAL_MODEL_LABEL || status.model || 'unconfigured', secrets).slice(0, 100) };
    if (status.llm !== true) { report.reason = 'REAL_MODEL_NOT_CONFIGURED'; return report; }
    const farms = await request('/farm-workspaces');
    if (!Array.isArray(farms)) throw failure('INVALID_FARM_RESPONSE');
    const farm = farms.filter(f => f.demo === true && f.archived !== true && Array.isArray(f.plots) && f.plots.length)
      .find(f => !env.FARM_EVAL_FARM_ID || f.id === env.FARM_EVAL_FARM_ID);
    if (!farm) throw failure('SYNTHETIC_FARM_REQUIRED');
    const selected = farm.plots.filter(p => !env.FARM_EVAL_PLOT_ID || p.id === env.FARM_EVAL_PLOT_ID);
    if (!selected.length) throw failure('SYNTHETIC_PLOT_REQUIRED');
    const count = Number(env.FARM_EVAL_CASES || 3);
    if (!Number.isInteger(count) || count < 1 || count > 3) throw failure('CASE_COUNT_MUST_BE_1_TO_3');
    for (const [index, item] of cases.slice(0, count).entries()) {
      const plot = selected[index % selected.length];
      const record = { caseId: item.id, question: item.question, reviewFocus: item.focus,
        status: 'NOT_EVALUATED', mode: null, diagnostic: null, elapsedMs: null,
        model: null, evidence: null, reply: null, humanReview: emptyScores() };
      report.cases.push(record);
      const aliases = [[farm.name, '虚构农场'], [farm.id, 'synthetic-farm'], [plot.name, '虚构地块'], [plot.id, 'synthetic-plot']];
      try {
        const draft = await request('/ai/decisions', 'POST', { farmId: farm.id, plotId: plot.id,
          question: item.question, requestId: 'eval-' + crypto.randomUUID() });
        for (const [sensorIndex, reading] of (draft.evidence?.readings || []).entries()) {
          aliases.push([reading.deviceName, `虚构测点${sensorIndex + 1}`], [reading.deviceId, `synthetic-sensor-${sensorIndex + 1}`]);
        }
        record.evidence = sanitizedEvidence(draft.evidence, aliases, secrets);
        const begin = performance.now();
        const result = await request('/ai/decisions/' + encodeURIComponent(draft.id) + '/explain', 'POST');
        record.elapsedMs = Math.round(performance.now() - begin);
        record.mode = redact(result.mode, secrets); record.diagnostic = redact(result.diagnostic, secrets);
        record.model = redact(result.model || status.model || '', secrets).slice(0, 100);
        record.reply = redact(result.explanation, secrets, aliases);
        record.status = result.mode === 'llm' && result.diagnostic === 'OK' && typeof result.explanation === 'string'
          && result.explanation.trim() ? 'MODEL_RESPONSE_UNSCORED' : 'NOT_EVALUATED';
        if (record.status === 'NOT_EVALUATED') record.reason = 'RULE_OR_ERROR_FALLBACK';
      } catch (error) { record.reason = error.safeCode || 'CASE_FAILED'; }
    }
    const recorded = report.cases.filter(c => c.status === 'MODEL_RESPONSE_UNSCORED').length;
    report.status = recorded === report.cases.length ? 'RESPONSES_RECORDED_UNSCORED' : recorded ? 'PARTIAL_RESPONSES_RECORDED_UNSCORED' : 'NOT_EVALUATED';
    report.reason = recorded ? 'HUMAN_REVIEW_AND_PROVIDER_VERIFICATION_REQUIRED' : 'NO_VERIFIED_MODEL_RESPONSES';
  } catch (error) { report.reason = error.safeCode || 'EVALUATION_FAILED'; }
  finally {
    if (createdSession) { try { await request('/auth/logout', 'POST'); } catch { /* No secret-bearing error details. */ } }
    report.finishedAt = new Date().toISOString();
    report.elapsedMs = Date.now() - started.getTime();
  }
  return report;
}

async function main() {
  const args = process.argv.slice(2);
  if (args.includes('--help')) {
    console.log('Usage: node tools/evaluate_decision_ai.cjs [--dry-run]\nCredentials: FARM_EVAL_TOKEN[_FILE] or FARM_EVAL_PASSWORD[_FILE].\nOptional: FARM_EVAL_BASE_URL (default localhost:19176), FARM_EVAL_USERNAME, FARM_EVAL_FARM_ID, FARM_EVAL_PLOT_ID, FARM_EVAL_CASES (1-3), FARM_EVAL_MODEL_LABEL, FARM_EVAL_PROVIDER_KIND.\nReports: .cache/ai-evaluations; no quality scores are inferred.');
    return;
  }
  if (args.some(arg => arg !== '--dry-run')) throw failure('UNKNOWN_ARGUMENT');
  const report = await run(process.env, { dryRun: args.includes('--dry-run') });
  fs.mkdirSync(outputDirectory, { recursive: true });
  const name = report.startedAt.replace(/[:.]/g, '-') + '-' + crypto.randomBytes(3).toString('hex') + '.json';
  const file = path.join(outputDirectory, name);
  fs.writeFileSync(file, JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
  console.log(`${report.status}: ${report.reason}; model responses ${report.cases.filter(c => c.status === 'MODEL_RESPONSE_UNSCORED').length}/${report.cases.length}`);
  console.log(path.relative(root, file));
}

module.exports = { run, redact, sanitizedEvidence, cases };
if (require.main === module) main().catch(error => { console.error(error.safeCode || 'EVALUATION_TOOL_FAILED'); process.exitCode = 1; });
