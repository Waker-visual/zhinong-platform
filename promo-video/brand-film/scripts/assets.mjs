// 准备不入库的素材：从既有宣传片工程（只读）复制到 public/。
// 用法：node scripts/assets.mjs [源目录]   源目录默认 E:/智慧农场/promo-video，或环境变量 PROMO_SRC
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const src = process.argv[2] || process.env.PROMO_SRC || "E:/智慧农场/promo-video";
const MAP = [
  ["music/alex-morgan-documentary-nature-528316.mp3", "public/audio/music-src.mp3"],
  ["music/analysis.json", "audio/out/analysis.json"],
  ["film/public/content/xiaohe-run.json", "public/content/xiaohe-run.json"],
  ...["l-daytour", "l-spring", "l-summer", "l-autumn", "l-winter", "u-wide", "u-zone2", "u-drone", "u-reset", "s-overview"].map((n) => [`film/public/rec/${n}.mp4`, `public/rec/${n}.mp4`]),
  ...["d-today-v4", "m-home", "m-devices", "m-jobs", "m-cameras"].map((n) => [`film/public/shots/${n}.png`, `public/shots/${n}.png`]),
];
let missing = 0;
for (const [from, to] of MAP) {
  const a = path.join(src, from), b = path.join(root, to);
  if (!fs.existsSync(a)) { console.warn("缺少", from); missing++; continue; }
  fs.mkdirSync(path.dirname(b), { recursive: true });
  fs.copyFileSync(a, b);
  console.log("复制", to);
}
process.exit(missing ? 1 : 0);
