// 准备不入库的素材：从既有宣传片工程（只读）复制配乐与小禾问答记录。界面与三维素材一律用 capture/ 下的脚本按新版 UI 重录。
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
