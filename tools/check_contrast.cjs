// 设计令牌对比度检查：读取 frontend/src/account/theme.css，按浅色、深色和四种主题色
// 计算文字、控件与其底色的 WCAG 对比度，任一组合低于要求即失败。
// 用法：node tools/check_contrast.cjs [--all]   （--all 同时列出达标的组合）
const fs = require('node:fs');
const path = require('node:path');

const SOURCE = path.resolve(__dirname, '../frontend/src/account/theme.css');
const TEXT = 4.5, GRAPHIC = 3;
// [前景, 底色, 最低对比度, 说明]
const PAIRS = [
  ['--text', '--bg', TEXT, '正文 / 页面底'],
  ['--text', '--surface', TEXT, '正文 / 卡片底'],
  ['--text', '--subtle-bg', TEXT, '正文 / 内嵌底'],
  ['--text-2', '--bg', TEXT, '次级文字 / 页面底'],
  ['--text-2', '--surface', TEXT, '次级文字 / 卡片底'],
  ['--text-2', '--nav-bg', TEXT, '次级文字 / 侧栏底'],
  ['--text-2', '--sunken', TEXT, '次级文字 / 控件底'],
  ['--label-fg', '--bg', TEXT, '标签 / 页面底'],
  ['--label-fg', '--surface', TEXT, '标签 / 卡片底'],
  ['--label-fg', '--subtle-bg', TEXT, '标签 / 内嵌底'],
  ['--label-fg', '--nav-bg', TEXT, '标签 / 侧栏底'],
  ['--text', '--nav-selected-bg', TEXT, '导航选中文字 / 选中底'],
  ['--primary-fg', '--primary-bg', TEXT, '主按钮文字 / 主按钮底'],
  ['--on-brand', '--brand', TEXT, '主题色上的文字'],
  ['--brand', '--surface', TEXT, '主题色文字 / 卡片底'],
  ['--brand', '--brand-soft', TEXT, '主题色文字 / 主题浅底'],
  ['--text', '--brand-soft', TEXT, '正文 / 主题浅底'],
  ['--text-2', '--brand-soft', TEXT, '次级文字 / 主题浅底'],
  ['--link-fg', '--bg', TEXT, '链接 / 页面底'],
  ['--ok', '--ok-bg', TEXT, '正常状态文字 / 底'],
  ['--info', '--info-bg', TEXT, '信息状态文字 / 底'],
  ['--amber', '--amber-bg', TEXT, '提醒状态文字 / 底'],
  ['--caution', '--caution-bg', TEXT, '受阻状态文字 / 底'],
  ['--danger', '--danger-bg', TEXT, '危险状态文字 / 底'],
  ['--danger', '--surface', TEXT, '危险文字 / 卡片底'],
  ['--caution', '--surface', TEXT, '受阻文字 / 卡片底'],
  ['--ok', '--surface', TEXT, '正常文字 / 卡片底'],
  ['--input-line', '--input-bg', GRAPHIC, '输入框描边 / 输入底'],
  ['--focus-ring', '--bg', GRAPHIC, '焦点环 / 页面底'],
  ['--health-green', '--surface', GRAPHIC, '健康色块 / 卡片底'],
  ['--health-brown', '--surface', GRAPHIC, '偏低色块 / 卡片底'],
];
const ACCENTS = ['forest', 'blue', 'amber', 'rose'];

function blocks(source) {
  // 注释里常带冒号和分号（例如“5.04:1”），先整体去掉再拆声明。
  const css = source.replace(/\/\*[\s\S]*?\*\//g, '');
  const result = [];
  const pattern = /([^{}]+)\{([^{}]*)\}/g;
  for (let match; (match = pattern.exec(css));) {
    const declarations = {};
    for (const line of match[2].split(';')) {
      const index = line.indexOf(':');
      if (index < 0) continue;
      const name = line.slice(0, index).trim();
      if (name.startsWith('--')) declarations[name] = line.slice(index + 1).trim();
    }
    result.push({ selectors: match[1].split(',').map(item => item.trim()), declarations });
  }
  return result;
}

function tokens(all, theme, accent) {
  const merged = {};
  const apply = selector => {
    for (const block of all) if (block.selectors.includes(selector)) Object.assign(merged, block.declarations);
  };
  apply(':root');
  apply(`html[data-accent="${accent}"]`);
  if (theme === 'dark') apply('html[data-theme="dark"]');
  return merged;
}

function parseHex(value) {
  const hex = value.replace('#', '');
  const full = hex.length === 3 ? [...hex].map(ch => ch + ch).join('') : hex.slice(0, 6);
  return [0, 2, 4].map(offset => parseInt(full.slice(offset, offset + 2), 16));
}

function splitArguments(text) {
  const parts = [];
  let depth = 0, current = '';
  for (const ch of text) {
    if (ch === '(') depth++;
    if (ch === ')') depth--;
    if (ch === ',' && depth === 0) { parts.push(current.trim()); current = ''; } else current += ch;
  }
  parts.push(current.trim());
  return parts;
}

function resolve(value, table, seen = new Set()) {
  const text = value.trim();
  if (/^#[0-9a-f]{3,8}$/i.test(text)) return parseHex(text);
  const reference = /^var\((--[\w-]+)\)$/.exec(text);
  if (reference) {
    if (seen.has(reference[1]) || !(reference[1] in table)) throw new Error(`无法解析令牌 ${reference[1]}`);
    return resolve(table[reference[1]], table, new Set(seen).add(reference[1]));
  }
  const mix = /^color-mix\(in srgb,\s*([\s\S]+)\)$/.exec(text);
  if (mix) {
    const [first, second] = splitArguments(mix[1]).map(part => {
      const share = /\s(\d+(?:\.\d+)?)%$/.exec(part);
      return { color: resolve(share ? part.slice(0, share.index) : part, table, seen), share: share ? Number(share[1]) / 100 : null };
    });
    const a = first.share ?? (second.share === null ? 0.5 : 1 - second.share), b = second.share ?? 1 - a;
    return first.color.map((channel, index) => Math.round((channel * a + second.color[index] * b) / (a + b)));
  }
  throw new Error(`不支持的颜色写法：${text}`);
}

function luminance(rgb) {
  const [r, g, b] = rgb.map(channel => {
    const value = channel / 255;
    return value <= 0.03928 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(a, b) {
  const [light, dark] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (light + 0.05) / (dark + 0.05);
}

function check(css) {
  const all = blocks(css), rows = [];
  for (const theme of ['light', 'dark']) {
    for (const accent of ACCENTS) {
      const table = tokens(all, theme, accent);
      for (const [fg, bg, minimum, label] of PAIRS) {
        // 与主题色无关的组合只在默认主题色下核算一次。
        const accentBound = /brand|primary|link|focus/.test(fg + bg);
        if (accent !== 'forest' && !accentBound) continue;
        const ratio = contrast(resolve(`var(${fg})`, table), resolve(`var(${bg})`, table));
        rows.push({ theme, accent, fg, bg, label, minimum, ratio, ok: ratio >= minimum });
      }
    }
  }
  return rows;
}

if (require.main === module) {
  const rows = check(fs.readFileSync(SOURCE, 'utf8'));
  const failed = rows.filter(row => !row.ok);
  const line = row => `  ${row.theme === 'light' ? '浅色' : '深色'} · ${row.accent.padEnd(6)} ${row.ratio.toFixed(2).padStart(5)} : 1（要求 ${row.minimum}）  ${row.label}  ${row.fg} / ${row.bg}`;
  if (process.argv.includes('--all')) for (const row of rows) console.log(line(row));
  if (failed.length) {
    console.error(`对比度不足的令牌组合 ${failed.length} 项：`);
    for (const row of failed) console.error(line(row));
    process.exitCode = 1;
  } else console.log(`对比度检查通过：浅色与深色共 ${rows.length} 组令牌组合均达标。`);
}

module.exports = { check, contrast, resolve };
