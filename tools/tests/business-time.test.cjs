const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { scanFiles } = require('../check_project.cjs');

const JAVA = 'backend/src/main/java/app/zhinong';

function fixture(t, files) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zhihe-business-time-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  for (const [relative, value] of Object.entries(files)) {
    const target = path.join(root, relative);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, value);
  }
  return scanFiles(root, Object.keys(files));
}

test('business code may not read the server date directly', t => {
  const service = `${JAVA}/fieldwork/FieldWorkService.java`;
  assert.deepEqual(fixture(t, {
    [service]: [
      'class FieldWorkService {',
      '  Object today() { return LocalDate.now(); }',
      '  Object zoned() { return java.time.LocalDate.now(zone); }',
      '  Object farm() { return clock.today(); }',
      '}',
    ].join('\n'),
  }), [
    { type: 'server_date', path: service, line: 2 },
    { type: 'server_date', path: service, line: 3 },
  ]);
});

test('the farm clock and demo generators are the only date exemptions', t => {
  assert.deepEqual(fixture(t, {
    [`${JAVA}/business/FarmClock.java`]: 'public static final String DEFAULT_ZONE = "Asia/Shanghai";\nLocalDate today() { return LocalDate.now(clock); }',
    [`${JAVA}/bootstrap/DemoData.java`]: 'LocalDate start = LocalDate.now().minusDays(60);',
    'backend/src/test/java/app/zhinong/FieldWorkIntegrationTest.java': 'var due = LocalDate.now();',
  }), []);
});

test('the business zone is configured once, not hard-coded in services or generators', t => {
  const files = {
    [`${JAVA}/ai/AgronomyAnalysis.java`]: 'var zone = ZoneId.of("Asia/Shanghai");',
    [`${JAVA}/bootstrap/ResearchDemoData.java`]: 'ZoneId zone = ZoneId.of("Asia/Shanghai");',
    [`${JAVA}/device/SmartFarmMqttGateway.java`]: 'ZoneId.of(broker.timeZone());',
  };
  assert.deepEqual(fixture(t, files), [
    { type: 'hardcoded_zone', path: `${JAVA}/ai/AgronomyAnalysis.java`, line: 1 },
    { type: 'hardcoded_zone', path: `${JAVA}/bootstrap/ResearchDemoData.java`, line: 1 },
  ]);
});

test('the repository business code follows the farm clock', () => {
  const root = path.resolve(__dirname, '../..');
  const sources = [];
  const walk = relative => {
    for (const entry of fs.readdirSync(path.join(root, relative), { withFileTypes: true })) {
      const next = `${relative}/${entry.name}`;
      if (entry.isDirectory()) walk(next);
      else if (next.endsWith('.java')) sources.push(next);
    }
  };
  walk('backend/src/main/java');
  const findings = scanFiles(root, sources).filter(f => ['server_date', 'hardcoded_zone'].includes(f.type));
  assert.deepEqual(findings, []);
});
