// 由 lunar-javascript 生成登录场景所需的农历节日公历日期表，避免把整套历法库打进首屏主包。
// 用法：node tools/generate_lunar_festivals.cjs [--check]
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const lunar = require(path.join(root, 'frontend/node_modules/lunar-javascript'));
const { version } = require(path.join(root, 'frontend/node_modules/lunar-javascript/package.json'));
const target = path.join(root, 'frontend/src/ui/login-lunar-festivals.js');
const [first, last] = [1901, 2100];

const stamp = solar => solar.getMonth() * 100 + solar.getDay();
const rows = [];
for (let year = first; year <= last; year += 10) {
  const cells = [];
  for (let y = year; y < Math.min(year + 10, last + 1); y++) {
    // 正月与八月取正数月份，即非闰月；公历日期必落在同一公历年内。
    const spring = lunar.Lunar.fromYmd(y, 1, 1).getSolar(), autumn = lunar.Lunar.fromYmd(y, 8, 15).getSolar();
    if (spring.getYear() !== y || autumn.getYear() !== y) throw new Error(`Festival outside civil year ${y}`);
    cells.push(`${stamp(spring)},${stamp(autumn)}`);
  }
  rows.push(`  ${cells.join(', ')}, // ${year}`);
}
const code = `// 由 tools/generate_lunar_festivals.cjs 依据 lunar-javascript ${version} 生成，请勿手改。
// 每年两项：农历正月初一、八月十五（非闰月）对应的公历日期，数值为 月×100+日。
export const LUNAR_FESTIVAL_FIRST_YEAR=${first};
export const LUNAR_FESTIVAL_DATES=[
${rows.join('\n')}
];
`;

if (process.argv[2] === '--check') {
  if (!fs.existsSync(target) || fs.readFileSync(target, 'utf8') !== code) {
    console.error('农历节日日期表与 lunar-javascript 不一致，请运行 node tools/generate_lunar_festivals.cjs');
    process.exitCode = 1;
  }
} else fs.writeFileSync(target, code);
