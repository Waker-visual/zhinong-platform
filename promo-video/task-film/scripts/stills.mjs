// 抽帧审查：按秒数批量出静帧并拼成联系表
// 用法：node scripts/stills.mjs 0.5 3 5.1 ...   → out/stills/*.png + out/sheet.jpg
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
const serveUrl = await bundle({ entryPoint: path.join(root, "src", "index.ts") });
const composition = await selectComposition({ serveUrl, id: "TaskFilmSilent", chromiumOptions: { gl: "angle" } });
const files = [];
for (const s of times) {
  const file = path.join(out, `t${s.toFixed(2).padStart(6, "0")}.png`);
  await renderStill({ serveUrl, composition, frame: Math.round(s * 60), output: file, chromiumOptions: { gl: "angle" }, overwrite: true });
  files.push(file);
  console.log("still", s);
}
const ff = process.env.FFMPEG;
if (ff && files.length) {
  const cols = Math.min(4, files.length), rows = Math.ceil(files.length / cols);
  const args = ["-v", "error", "-y"];
  files.forEach((f) => args.push("-i", f));
  const scaled = files.map((_, i) => `[${i}]scale=640:360,drawtext=text='${times[i]}s':x=12:y=10:fontsize=26:fontcolor=yellow:box=1:boxcolor=black@0.6[s${i}]`).join(";");
  const pad = files.length < cols * rows ? `;color=black:s=640x360:d=1[blank]` : "";
  const ins = files.map((_, i) => `[s${i}]`).join("");
  const layout = Array.from({ length: files.length }, (_, i) => `${(i % cols) * 640}_${Math.floor(i / cols) * 360}`).join("|");
  args.push("-filter_complex", `${scaled};${ins}xstack=inputs=${files.length}:layout=${layout}:fill=black`, path.join(root, "out", "sheet.jpg"));
  execFileSync(ff, args, { stdio: "inherit" });
}