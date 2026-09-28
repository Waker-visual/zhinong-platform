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

test('scanner allows ordinary application files and rejects escaped paths', t => {
  const { root, put } = fixture(t);
  put('frontend/src/main.js', 'export const demo = true;');
  assert.deepEqual(scanFiles(root, ['frontend/src/main.js']), []);
  assert.throws(() => scanFiles(root, ['../outside.txt']), /inside the project/);
});
