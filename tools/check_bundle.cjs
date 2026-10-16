// 前端包体预算：在 npm run build 之后检查 frontend/dist，任一文件或入口首屏脚本超出预算即失败。
// 用法：node tools/check_bundle.cjs [dist 目录]
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');

const root = path.resolve(__dirname, '..');
const dist = path.resolve(root, process.argv[2] || 'frontend/dist');
const KB = 1000; // 与 Vite 构建报告一致：1 kB = 1000 字节。
const FILE_BUDGETS = [
  // 与 Vite 默认 chunkSizeWarningLimit 相同，超出时构建也会出现大包提示。
  { pattern: /\.js$/, limit: 500 * KB, label: '脚本块' },
  { pattern: /\.css$/, limit: 200 * KB, label: '样式表' },
  // 字体按 unicode-range 切片，单片过大说明又引入了未切片的整套字体。
  { pattern: /\.woff2$/, limit: 500 * KB, label: '字体切片' },
];
// 入口页面直接加载的脚本（入口块与 modulepreload 依赖）合计。桌面端入口目前静态引用地图与图表块
// （约 0.75 MB），预算按现状留出余量，防止登录场景数据等大文件重新进入首屏。
const ENTRY_BUDGET = 1400 * KB;

function files(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(entry =>
    entry.isDirectory() ? files(path.join(dir, entry.name)) : [path.join(dir, entry.name)]);
}

if (!fs.existsSync(path.join(dist, 'index.html'))) {
  console.error(`未找到构建产物 ${dist}，请先运行 npm run build`);
  process.exit(1);
}
const problems = [], rows = [];
const kb = bytes => `${(bytes / KB).toFixed(2)} kB`;
for (const file of files(dist)) {
  const budget = FILE_BUDGETS.find(item => item.pattern.test(file));
  if (!budget) continue;
  const bytes = fs.statSync(file).size, name = path.relative(dist, file).replaceAll('\\', '/');
  const gzip = /\.(?:js|css)$/.test(file) ? zlib.gzipSync(fs.readFileSync(file)).length : null;
  rows.push([name, bytes, gzip]);
  if (bytes > budget.limit) problems.push(`${budget.label} ${name} 为 ${kb(bytes)}，超过预算 ${kb(budget.limit)}`);
}
for (const html of files(dist).filter(file => file.endsWith('.html'))) {
  const text = fs.readFileSync(html, 'utf8'), name = path.relative(dist, html).replaceAll('\\', '/');
  const scripts = [...text.matchAll(/<(?:script[^>]*type="module"[^>]*src|link[^>]*rel="modulepreload"[^>]*href)="([^"]+)"/g)]
    .map(match => match[1]).filter(src => src.startsWith('/assets/'));
  if (!scripts.length) continue;
  const bytes = scripts.reduce((sum, src) => sum + fs.statSync(path.join(dist, src)).size, 0);
  console.log(`入口 ${name}：首屏脚本 ${scripts.length} 个，合计 ${kb(bytes)}（预算 ${kb(ENTRY_BUDGET)}）`);
  if (bytes > ENTRY_BUDGET) problems.push(`入口 ${name} 的首屏脚本合计 ${kb(bytes)}，超过预算 ${kb(ENTRY_BUDGET)}`);
}
rows.sort((a, b) => b[1] - a[1]);
for (const [name, bytes, gzip] of rows.slice(0, 12)) {
  console.log(`  ${kb(bytes).padStart(11)}${gzip === null ? '' : `  gzip ${kb(gzip).padStart(10)}`}  ${name}`);
}
if (problems.length) {
  console.error('前端包体超出预算：');
  for (const problem of problems) console.error(`  ${problem}`);
  process.exitCode = 1;
} else console.log(`包体预算检查通过：${rows.length} 个脚本、样式与字体文件均在预算内。`);
