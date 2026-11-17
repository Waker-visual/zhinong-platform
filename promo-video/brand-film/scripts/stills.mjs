// 抽帧审查：按秒数批量出静帧并拼成联系表
// 用法：node scripts/stills.mjs 3 12 26.5 ...   → out/stills/*.jpg + out/sheet.jpg（需环境变量 FFMPEG 才拼表）
import { bundle } from "@remotion/bundler";
import { renderStill, selectComposition } from "@remotion/renderer";
import path from "node:path";
import fs from "node:fs";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const times = process.argv.slice(2).map(Number);
const out = path.join(root, "out", "stills");
fs.mkdirSync(out, { recursive: true });
// 与 remotion.config.ts 相同的别名：三维渲染代码引用 ../task-film/src
const webpackOverride = (c) => ({
  ...c,
  resolve: { ...c.resolve, alias: { ...(c.resolve?.alias ?? {}), "@task": path.resolve(root, "../task-film/src") }, modules: [path.resolve(root, "node_modules"), "node_modules"] },
});
const serveUrl = await bundle({ entryPoint: path.join(root, "src", "index.ts"), webpackOverride });
const composition = await selectComposition({ serveUrl, id: "BrandFilmSilent", chromiumOptions: { gl: "angle" } });
const files = [];
for (const s of times) {
  const file = path.join(out, `t${s.toFixed(2).padStart(6, "0")}.jpg`);
  await renderStill({ serveUrl, composition, frame: Math.round(s * 60), output: file, imageFormat: "jpeg", jpegQuality: 90, chromiumOptions: { gl: "angle" }, overwrite: true });
  files.push(file);
  console.log("still", s);
}
const ff = process.env.FFMPEG;
if (ff && files.length > 1) {
  const cols = Math.min(4, files.length);
  const args = ["-v", "error", "-y"];
  files.forEach((f) => args.push("-i", f));
  const scaled = files.map((_, i) => `[${i}]scale=640:360[s${i}]`).join(";");
  const ins = files.map((_, i) => `[s${i}]`).join("");
  const layout = files.map((_, i) => `${(i % cols) * 640}_${Math.floor(i / cols) * 360}`).join("|");
  args.push("-filter_complex", `${scaled};${ins}xstack=inputs=${files.length}:layout=${layout}:fill=black`, "-q:v", "3", path.join(root, "out", "sheet.jpg"));
  execFileSync(ff, args, { stdio: "inherit" });
  console.log("sheet", times.join(" "));
}
