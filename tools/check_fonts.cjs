// 界面字体子集检查：扫描前端源码与后端演示数据中的汉字和中文标点，按浏览器的 unicode-range 选字规则
// 确认每个字都由首屏或界面切片直接提供，且切片合起来不少于原始思源黑体的字符覆盖。
// 用法：node tools/check_fonts.cjs          检查 frontend/src/fonts.css 及其字体文件
//       node tools/check_fonts.cjs chars    输出需要放入界面切片的字符（供 tools/subset_fonts.py 使用）
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');

const root = path.resolve(__dirname, '..');
const FONTS_CSS = 'frontend/src/fonts.css';
const HAN_SOURCE = 'frontend/font-sources/source-han-sans-cn-vf-2.005.woff2';
const LATIN_SOURCE = weight => `frontend/node_modules/@fontsource/iosevka-aile/files/iosevka-aile-latin-${weight}-normal.woff2`;
// 汉字字面负责的字符：中文标点、CJK 符号、统一汉字及扩展、兼容汉字和全角形式。
const HAN_RANGE = [[0x2014, 0x2014], [0x2018, 0x2019], [0x201c, 0x201d], [0x2026, 0x2026], [0x3000, 0x303f],
  [0x3400, 0x9fff], [0xf900, 0xfaff], [0xff00, 0xffef], [0x20000, 0x2fa1f]];
// 界面文字与演示数据：农场名、作物名、告警与错误提示等都会显示在界面上。
const UI_SOURCES = ['frontend/index.html', 'frontend/src', 'backend/src/main'];
const UI_FILES = /\.(?:vue|js|css|html|java|json|sql|yml)$/;
// fonts.css 由生成器写出，注释不是界面文字；取样数据只含字符画坐标。
const SKIP_FILES = /(?:\.test\.js|login-reference\.json|\/fonts\.css)$/;
// 登录页首屏文字单独成片，未登录访问只需下载这一片汉字。
const FIRST_SCREEN = [
  { file: 'frontend/index.html' },
  { file: 'frontend/src/App.vue', from: '<div v-if="!identity" class="login-layout">', to: '<div v-else' },
  { file: 'frontend/src/ui/LoginField.vue' },
  { file: 'frontend/src/ui/LoginSceneClock.vue' },
  { file: 'frontend/src/ui/validate.js' },
  { dir: 'frontend/src/ui', match: /^login-(?!.*\.test\.js$).*\.js$/ },
];

const inRange = (ranges, cp) => ranges.some(([a, b]) => cp >= a && cp <= b);
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8');

function walk(relative) {
  const full = path.join(root, relative);
  if (fs.statSync(full).isFile()) return [relative];
  return fs.readdirSync(full).sort().flatMap(name => walk(path.posix.join(relative, name)));
}

function charsOf(text, into = new Set()) {
  for (const ch of text) if (inRange(HAN_RANGE, ch.codePointAt(0))) into.add(ch.codePointAt(0));
  return into;
}

function requiredChars() {
  const ui = new Set(), first = new Set();
  for (const file of UI_SOURCES.flatMap(walk)) {
    if (UI_FILES.test(file) && !SKIP_FILES.test(file)) charsOf(read(file), ui);
  }
  for (const source of FIRST_SCREEN) {
    const files = source.dir ? fs.readdirSync(path.join(root, source.dir)).filter(name => source.match.test(name))
      .map(name => path.posix.join(source.dir, name)) : [source.file];
    for (const file of files) {
      let text = read(file);
      if (source.from) {
        const start = text.indexOf(source.from), end = text.indexOf(source.to, start);
        if (start < 0 || end < 0) throw new Error(`首屏字符范围标记已失效：${file}`);
        text = text.slice(start, end);
      }
      charsOf(text, first);
    }
  }
  return { first: [...first].sort((a, b) => a - b), ui: [...ui].sort((a, b) => a - b) };
}

// WOFF2 只需读出 cmap：表目录给出各表在 Brotli 解压流中的长度，cmap 不做变换。
function readBase128(buffer, state) {
  let value = 0;
  for (let i = 0; i < 5; i++) {
    const byte = buffer[state.offset++];
    value = value * 128 + (byte & 0x7f);
    if (!(byte & 0x80)) return value;
  }
  throw new Error('Invalid UIntBase128');
}

function woff2Cmap(relative) {
  const buffer = fs.readFileSync(path.join(root, relative));
  if (buffer.toString('latin1', 0, 4) !== 'wOF2') throw new Error(`不是 WOFF2 字体：${relative}`);
  const tables = buffer.readUInt16BE(12), compressed = buffer.readUInt32BE(20), state = { offset: 48 };
  let cmapOffset = -1, cmapLength = 0, streamOffset = 0;
  for (let i = 0; i < tables; i++) {
    const flags = buffer[state.offset++], tag = flags & 0x3f, transform = flags >> 6;
    if (tag === 63) state.offset += 4;
    const original = readBase128(buffer, state);
    // glyf/loca 的变换版本 0 表示已变换；其他表的版本 0 表示原样存放。
    const transformed = (tag === 10 || tag === 11) ? transform !== 3 : transform !== 0;
    const length = transformed ? readBase128(buffer, state) : original;
    if (tag === 0) [cmapOffset, cmapLength] = [streamOffset, length];
    streamOffset += length;
  }
  if (cmapOffset < 0) throw new Error(`字体缺少 cmap：${relative}`);
  const stream = zlib.brotliDecompressSync(buffer.subarray(state.offset, state.offset + compressed));
  return parseCmap(stream.subarray(cmapOffset, cmapOffset + cmapLength));
}

function parseCmap(cmap) {
  const subtables = [];
  for (let i = 0, count = cmap.readUInt16BE(2); i < count; i++) {
    const at = 4 + i * 8;
    subtables.push({ platform: cmap.readUInt16BE(at), encoding: cmap.readUInt16BE(at + 2), offset: cmap.readUInt32BE(at + 4) });
  }
  const chars = new Set();
  const pick = subtables.find(s => s.platform === 3 && s.encoding === 10) || subtables.find(s => s.platform === 3 && s.encoding === 1);
  if (!pick) throw new Error('cmap 缺少 Windows Unicode 子表');
  const at = pick.offset, format = cmap.readUInt16BE(at);
  if (format === 12) {
    for (let i = 0, groups = cmap.readUInt32BE(at + 12); i < groups; i++) {
      const g = at + 16 + i * 12;
      for (let cp = cmap.readUInt32BE(g), end = cmap.readUInt32BE(g + 4); cp <= end; cp++) chars.add(cp);
    }
  } else if (format === 4) {
    const segments = cmap.readUInt16BE(at + 6) / 2, ends = at + 14, starts = ends + segments * 2 + 2;
    const deltas = starts + segments * 2, ranges = deltas + segments * 2;
    for (let i = 0; i < segments; i++) {
      const end = cmap.readUInt16BE(ends + i * 2), start = cmap.readUInt16BE(starts + i * 2);
      const delta = cmap.readInt16BE(deltas + i * 2), rangeOffset = cmap.readUInt16BE(ranges + i * 2);
      for (let cp = start; cp <= end && cp !== 0xffff; cp++) {
        const glyph = rangeOffset === 0 ? (cp + delta) & 0xffff
          : cmap.readUInt16BE(ranges + i * 2 + rangeOffset + (cp - start) * 2);
        if (glyph) chars.add(cp);
      }
    }
  } else throw new Error(`不支持的 cmap 格式 ${format}`);
  return chars;
}

function fontFaces() {
  const css = read(FONTS_CSS), faces = [];
  for (const [, body] of css.matchAll(/@font-face\s*\{([^}]*)\}/g)) {
    const family = body.match(/font-family:\s*"([^"]+)"/)[1];
    const url = body.match(/url\("([^"]+)"\)/)[1];
    const weight = body.match(/font-weight:\s*([^;]+);/)[1].trim();
    const ranges = body.match(/unicode-range:([^;]+);/)[1].split(',').map(part => {
      const [a, b] = part.trim().replace(/^U\+/i, '').split('-').map(hex => parseInt(hex, 16));
      return [a, b ?? a];
    });
    const file = path.posix.join(path.posix.dirname(FONTS_CSS), url);
    faces.push({ family, file, weight, ranges, bytes: fs.statSync(path.join(root, file)).size, cmap: woff2Cmap(file) });
  }
  return faces;
}

// 浏览器按定义的倒序检查同一字族的 @font-face：命中 unicode-range 就下载，字形缺失再看下一条。
function lookup(faces, cp) {
  const tried = [];
  for (const face of [...faces].reverse()) {
    if (!inRange(face.ranges, cp)) continue;
    tried.push(face);
    if (face.cmap.has(cp)) return { face, tried };
  }
  return { face: null, tried };
}

function check() {
  const faces = fontFaces(), problems = [];
  const han = faces.filter(face => face.family === 'Zhihe Han'), latin = faces.filter(face => face.family === 'Zhihe Latin');
  const tier = face => path.posix.basename(face.file).match(/^zhihe-han-(first|ui|common|rare)/)?.[1];
  const label = cp => `${String.fromCodePoint(cp)} U+${cp.toString(16).toUpperCase()}`;
  // 汉字切片的 unicode-range 不能超出汉字字面范围，否则 ℃、● 等符号会先下载切片再回退系统字体。
  for (const face of han) for (const [a, b] of face.ranges) {
    if (!HAN_RANGE.some(([low, high]) => a >= low && b <= high)) {
      problems.push(`${path.posix.basename(face.file)} 的 unicode-range U+${a.toString(16).toUpperCase()}-${b.toString(16).toUpperCase()} 超出汉字字面范围`);
    }
  }
  const { first, ui } = requiredChars();
  for (const cp of ui) {
    const { face, tried } = lookup(han, cp);
    if (!face || tried.length > 1 || !['first', 'ui'].includes(tier(face))) {
      problems.push(`${label(cp)} 不在界面字体切片中${face ? `（落到 ${path.posix.basename(face.file)}）` : ''}`);
    }
  }
  for (const cp of first) {
    const { face } = lookup(han, cp);
    if (face && tier(face) !== 'first') problems.push(`${label(cp)} 是登录首屏文字，但不在首屏切片中`);
  }
  // 切片合起来不能少于原字体：原字体在汉字范围内的每个字都要能在某个切片里找到。
  const original = [...woff2Cmap(HAN_SOURCE)].filter(cp => inRange(HAN_RANGE, cp));
  const lost = original.filter(cp => !lookup(han, cp).face).length;
  if (lost) problems.push(`有 ${lost} 个原字体字符未被任何汉字切片覆盖`);
  for (const face of latin) {
    // 首选字体须含省略号，否则 Chromium 的 text-overflow 会把“…”换成三个句点。
    if (!face.cmap.has(0x2026)) problems.push(`${face.file} 缺少 U+2026，截断省略号会退化为三个句点`);
    const source = woff2Cmap(LATIN_SOURCE(face.weight));
    for (const [a, b] of face.ranges) for (let cp = a; cp <= b; cp++) {
      if (source.has(cp) && !face.cmap.has(cp)) problems.push(`${face.file} 缺少 U+${cp.toString(16).toUpperCase()}`);
    }
  }
  const firstFace = han.find(face => tier(face) === 'first'), uiFace = han.find(face => tier(face) === 'ui');
  if (!firstFace || !uiFace) problems.push('fonts.css 缺少首屏或界面汉字切片');
  if (problems.length) {
    console.error(`字体子集检查未通过（${problems.length} 项），请运行 python tools/subset_fonts.py 重新生成：`);
    for (const problem of problems.slice(0, 40)) console.error(`  ${problem}`);
    process.exitCode = 1;
    return;
  }
  const kb = bytes => `${(bytes / 1024).toFixed(1)} KiB`;
  console.log(`字体子集检查通过：界面 ${ui.length} 字（首屏 ${first.length} 字）全部由界面切片提供；` +
    `${han.length} 个汉字切片覆盖原字体汉字范围内全部 ${original.length} 个字符。`);
  console.log(`  首屏汉字切片 ${kb(firstFace.bytes)}，界面汉字切片 ${kb(uiFace.bytes)}，` +
    `拉丁字重 ${latin.map(face => `${face.weight}:${kb(face.bytes)}`).join(' ')}`);
}

if (require.main === module) {
  if (process.argv[2] === 'chars') {
    console.log(JSON.stringify({ range: HAN_RANGE, ...requiredChars() }));
  } else check();
}

module.exports = { requiredChars, woff2Cmap, HAN_RANGE };
