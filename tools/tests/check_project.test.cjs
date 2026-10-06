const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { fingerprint, scanFiles } = require('../check_project.cjs');

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'zhihe-checks-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  return { root, put(relative, value) {
    const target = path.join(root, relative);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, value);
  }};
}

test('fingerprint changes with source but ignores generated outputs', t => {
  const { root, put } = fixture(t);
  put('frontend/src/main.js', 'export const value = 1;');
  const first = fingerprint(root);
  put('frontend/dist/build.js', 'generated output');
  assert.equal(fingerprint(root), first);
  put('frontend/src/main.js', 'export const value = 2;');
  assert.notEqual(fingerprint(root), first);
});

test('scanner blocks private material paths and reports no secret values', t => {
  const { root, put } = fixture(t);
  const secret = 'A'.repeat(20);
  put('copyright/facts.json', '{}');
  put('frontend/src/config.js', `const access_token = "${secret}";`);
  const results = scanFiles(root, ['copyright/facts.json', 'frontend/src/config.js']);
  assert.deepEqual(results.map(f => f.type), ['private_file', 'embedded_secret']);
  assert.equal(JSON.stringify(results).includes(secret), false);
});

test('scanner blocks persisted model credentials from either launch directory', t => {
  const { root, put } = fixture(t);
  const files = ['config/llm.properties', 'backend/config/llm.properties'];
  for (const file of files) put(file, 'api-key=fixture-only');
  assert.deepEqual(scanFiles(root, files), files.map(file => ({ type: 'private_file', path: file })));
});

test('scanner requires frontend colors to come from theme tokens', t => {
  const { root, put } = fixture(t);
  put('frontend/src/account/theme.css', ':root { --text: #0b0b0b; --backdrop: rgba(0, 0, 0, 0.4); }');
  put('frontend/src/style.css', 'body {\n  color: var(--text);\n  background: #f5f7f4;\n}');
  put('frontend/src/Panel.vue', [
    '<script setup>',
    'const chart = { color: "var(--ok)", legacy: "#63aa85" };',
    '</script>',
    '<style scoped>',
    '.panel { border: 1px solid var(--border); }',
    '.warning { box-shadow: 0 0 0 1px rgba(0, 0, 0, 0.1); color: white; }',
    '</style>',
  ].join('\n'));
  const files = ['frontend/src/account/theme.css', 'frontend/src/style.css', 'frontend/src/Panel.vue'];
  assert.deepEqual(scanFiles(root, files), [
    { type: 'raw_color', path: 'frontend/src/style.css', line: 3 },
    { type: 'raw_color', path: 'frontend/src/Panel.vue', line: 2 },
    { type: 'raw_color', path: 'frontend/src/Panel.vue', line: 6 },
  ]);
});

test('scanner allows ordinary application files and rejects escaped paths', t => {
  const { root, put } = fixture(t);
  put('frontend/src/main.js', 'export const demo = true;');
  assert.deepEqual(scanFiles(root, ['frontend/src/main.js']), []);
  assert.throws(() => scanFiles(root, ['../outside.txt']), /inside the project/);
});
