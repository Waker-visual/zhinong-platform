// Bounded, read-only workload for comparing the same farm before/after a release.
// Business payloads, credentials, session tokens and farm IDs never enter the report.
const fs = require('node:fs');
const path = require('node:path');
const { performance } = require('node:perf_hooks');

const root = path.resolve(__dirname, '..');
function argument(name, fallback) {
  const index = process.argv.indexOf(`--${name}`);
  return index < 0 ? fallback : process.argv[index + 1];
}
function bounded(name, fallback, maximum) {
  const value = Number(argument(name, fallback));
  if (!Number.isInteger(value) || value < 1 || value > maximum) throw new Error(`--${name} must be between 1 and ${maximum}`);
  return value;
}
function percentile(values, fraction) {
  const sorted = [...values].sort((a, b) => a - b);
  return Math.round(sorted[Math.max(0, Math.ceil(sorted.length * fraction) - 1)]);
}

async function main() {
  const base = new URL(argument('url', 'http://127.0.0.1:9175'));
  if (!['127.0.0.1', 'localhost', '[::1]'].includes(base.hostname) || base.username || base.password) {
    throw new Error('This benchmark only supports local test services.');
  }
  const rounds = bounded('rounds', 3, 10), clients = bounded('clients', 2, 4);
  let token = process.env.FARM_BENCHMARK_TOKEN, ownSession = false;
  const request = async (route, options = {}) => {
    const response = await fetch(new URL(`/api${route}`, base), {
      ...options,
      headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      signal: AbortSignal.timeout(20000),
    });
    const body = await response.text();
    return { status: response.status, body };
  };
  try {
    if (!token) {
      const credentialFile = path.resolve(root, argument('password-file', '.cache/demo-password.txt'));
      const password = fs.readFileSync(credentialFile, 'utf8').trim();
      const login = await request('/auth/login', {
        method: 'POST', body: JSON.stringify({
          tenantCode: argument('tenant', 'demo-a'), username: argument('user', 'admin'), password,
        }),
      });
      if (login.status !== 200) throw new Error(`Benchmark login failed (HTTP ${login.status})`);
      token = JSON.parse(login.body).token;
      ownSession = true;
    }
    const farms = await request('/farms');
    if (farms.status !== 200) throw new Error(`Farm access failed (HTTP ${farms.status})`);
    const list = JSON.parse(farms.body);
    const farmId = argument('farm', list[0]?.id || list[0]?.ID);
    if (!farmId || !list.some((farm) => (farm.id || farm.ID) === farmId)) throw new Error('Select an accessible farm for this benchmark.');
    const id = encodeURIComponent(farmId);
    const endpoints = [
      ['workspace', `/farms/${id}/workspace?days=30`],
      ['operations', `/farms/${id}/operations?hours=168`],
      ['analysis', `/ai/analysis?farmId=${id}`],
      ['briefing', `/ai/briefings/today?farmId=${id}`],
      ['mapConfig', `/farms/${id}/map-config`],
      ['fieldMap', `/farms/${id}/field-map`],
    ];
    const observations = [], refreshes = [];
    for (let round = 0; round < rounds; round++) {
      await Promise.all(Array.from({ length: clients }, async () => {
        const start = performance.now();
        await Promise.all(endpoints.map(async ([endpoint, route]) => {
          const started = performance.now();
          try {
            const response = await request(route);
            observations.push({ endpoint, milliseconds: performance.now() - started, status: response.status });
          } catch (error) {
            observations.push({ endpoint, milliseconds: performance.now() - started, status: 0 });
          }
        }));
        refreshes.push(performance.now() - start);
      }));
    }
    const summary = endpoints.map(([endpoint]) => {
      const rows = observations.filter((item) => item.endpoint === endpoint);
      const durations = rows.map((item) => item.milliseconds);
      return { endpoint, requests: rows.length, failures: rows.filter((item) => item.status !== 200).length,
        p50Ms: percentile(durations, 0.5), p95Ms: percentile(durations, 0.95), maxMs: Math.round(Math.max(...durations)) };
    });
    const report = {
      measuredAt: new Date().toISOString(), rounds, clients,
      note: 'Small bounded local comparison; percentiles describe these samples, not a production capacity guarantee. Excludes external satellite images and model generation.',
      refresh: { samples: refreshes.length, p50Ms: percentile(refreshes, 0.5), p95Ms: percentile(refreshes, 0.95) },
      endpoints: summary,
    };
    const output = path.resolve(root, argument('output', '.cache/runtime-benchmark.json'));
    fs.mkdirSync(path.dirname(output), { recursive: true });
    fs.writeFileSync(output, `${JSON.stringify(report, null, 2)}\n`);
    console.log(JSON.stringify(report, null, 2));
    if (summary.some((item) => item.failures)) process.exitCode = 1;
  } finally {
    if (ownSession) await request('/auth/logout', { method: 'POST' }).catch(() => {});
  }
}

main().catch((error) => { console.error(error.message); process.exitCode = 1; });
