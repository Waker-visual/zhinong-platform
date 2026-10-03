const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');

function filesIn(root, relative) {
  const target = path.join(root, relative);
  if (!fs.existsSync(target)) return [];
  if (fs.lstatSync(target).isSymbolicLink()) throw new Error('Project inputs cannot be symbolic links');
  if (fs.statSync(target).isFile()) return [relative.replaceAll('\\', '/')];
  return fs.readdirSync(target).sort().flatMap(name => filesIn(root, path.join(relative, name)));
}

function fingerprint(root = path.resolve(__dirname, '..')) {
  const inputs = ['backend/src', 'frontend/src', 'backend/pom.xml', 'frontend/package.json',
    'frontend/package-lock.json', 'frontend/index.html', 'frontend/vite.config.js'];
  const entries = inputs.flatMap(p => filesIn(root, p)).sort().map(relative => [relative,
    crypto.createHash('sha256').update(fs.readFileSync(path.join(root, relative))).digest('hex')]);
  return crypto.createHash('sha256').update(JSON.stringify(entries)).digest('hex');
}

// 样式只引用 theme.css 中的令牌；其他样式表和 Vue <style> 块里出现的色值字面量视为违规。
// 图表与地图脚本里的颜色暂未纳入，后续改为运行时读取令牌后再收紧。
const TOKEN_SOURCE = 'frontend/src/account/theme.css';
const COLOR_LITERAL = /#[0-9a-f]{3,8}\b|\b(?:rgba?|hsla?|hwb|lab|lch|oklab|oklch)\(|:\s*(?:white|black)\b/i;

function styleLines(relative, lines) {
  if (relative.endsWith('.css')) return lines.map((line, index) => [index + 1, line]);
  if (!relative.endsWith('.vue')) return [];
  const result = [];
  let inStyle = false;
  lines.forEach((line, index) => {
    if (/<style\b/i.test(line)) inStyle = true;
    else if (/<\/style>/i.test(line)) inStyle = false;
    else if (inStyle) result.push([index + 1, line]);
  });
  return result;
}

function scanFiles(root, files) {
  const findings = [];
  const forbidden = /^(?:copyright\/|skills\/|private-materials\/|artifacts\/|\.cache\/|data\/|backend\/data\/|docs\/source-provenance\.json$)|(?:^|\/)(?:\.env(?:\..*)?|facts\.private\.json)$|(?:\.bundle|\.pdf|\.docx)$|(?:copyright_pipeline|materials\.ps1|capture_ui\.cjs|capture_v03\.cjs|test_pipeline\.py)/i;
  const patterns = {
    private_network: /\b(?:10\.\d{1,3}|192\.168|172\.(?:1[6-9]|2\d|3[01]))\.\d{1,3}\.\d{1,3}\b/,
    hardware_endpoint: /(?:rtsp|mqtt|tcp):\/\//i,
    private_key: /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/,
    embedded_secret: /(?:api[_-]?key|access[_-]?token|client[_-]?secret)\s*[:=]\s*['"][A-Za-z0-9_\-]{16,}['"]/i,
    hardware_serial: /\b(?:IMEI|ICCID|deviceSN)\s*[:=]/i,
  };
  for (const relative of files) {
    const full = path.resolve(root, relative), local = path.relative(root, full);
    if (local.startsWith('..') || path.isAbsolute(local)) throw new Error('Paths must stay inside the project');
    if (!fs.existsSync(full)) continue;
    if (forbidden.test(relative.replaceAll('\\', '/'))) findings.push({ type: 'private_file', path: relative });
    // Scan application sources/configuration; dependency lockfiles and test fixtures contain public samples.
    if (!/^(backend\/src\/main\/|frontend\/src\/)/.test(relative)) continue;
    if (!/\.(java|vue|js|sql|yml|json|css)$/.test(relative)) continue;
    const lines = fs.readFileSync(full, 'utf8').split(/\r?\n/);
    lines.forEach((line, index) => {
      for (const [type, pattern] of Object.entries(patterns)) {
        if (pattern.test(line)) findings.push({ type, path: relative, line: index + 1 });
      }
    });
    if (relative.startsWith('frontend/src/') && relative !== TOKEN_SOURCE) {
      for (const [line, text] of styleLines(relative, lines)) {
        if (COLOR_LITERAL.test(text)) findings.push({ type: 'raw_color', path: relative, line });
      }
    }
  }
  return findings;
}

if (require.main === module) {
  const root = path.resolve(__dirname, '..');
  if (process.argv[2] === 'fingerprint') console.log(fingerprint(root));
  else if (process.argv[2] === 'scan') {
    const files = execFileSync('git', ['ls-files', '--cached', '--others', '--exclude-standard', '-z'], { cwd: root, encoding: 'utf8' }).split('\0').filter(Boolean);
    const findings = scanFiles(root, [...new Set(files)]);
    console.log(JSON.stringify({ findings }, null, 2));
    process.exitCode = findings.length ? 1 : 0;
  } else {
    console.error('Usage: node tools/check_project.cjs <scan|fingerprint>');
    process.exitCode = 2;
  }
}

module.exports = { fingerprint, scanFiles };
