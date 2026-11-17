// 把 capture/out 下的逐帧实录编码成成片素材：
//   capture/out/farm/<shot>/f*.jpg + track.json → public/rec/f-<shot>.mp4 + src/content/farm/<shot>.json
//   capture/out/rec/<name>/f*.jpg               → public/rec/<name>.mp4
// 用法：FFMPEG=<ffmpeg 路径> node scripts/encode.mjs [farm|rec] [name ...]
import fs from 'node:fs'; import path from 'node:path'; import { execFileSync } from 'node:child_process'; import { fileURLToPath } from 'node:url';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const FF = process.env.FFMPEG || 'ffmpeg';
const [kind = 'farm', ...names] = process.argv.slice(2);
const enc = (dir, out, vf) => execFileSync(FF, ['-y', '-loglevel', 'error', '-framerate', '60', '-i', path.join(dir, 'f%04d.jpg'), ...(vf ? ['-vf', vf] : []),
  '-c:v', 'libx264', '-preset', 'slow', '-crf', '14', '-g', '12', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', out], { stdio: 'inherit' });
fs.mkdirSync(path.join(ROOT, 'public/rec'), { recursive: true });
if (kind === 'farm') {
  const src = path.join(ROOT, 'capture/out/farm'); const dst = path.join(ROOT, 'src/content/farm'); fs.mkdirSync(dst, { recursive: true });
  for (const name of fs.readdirSync(src)) {
    if (names.length && !names.includes(name)) continue;
    const dir = path.join(src, name); if (!fs.existsSync(path.join(dir, 'track.json'))) continue;
    enc(dir, path.join(ROOT, 'public/rec', 'f-' + name + '.mp4'));
    const track = JSON.parse(fs.readFileSync(path.join(dir, 'track.json'), 'utf8'));
    fs.writeFileSync(path.join(dst, name + '.json'), JSON.stringify({ fps: track.fps, frames: track.frames.map((f) => ({ H: f.H, p: f.p })) }));
    console.log('farm', name, track.frames.length);
  }
  fs.copyFileSync(path.join(ROOT, 'capture/out/farm-world.json'), path.join(dst, 'world.json'));
} else {
  const src = path.join(ROOT, 'capture/out/rec');
  for (const name of fs.readdirSync(src)) {
    if (names.length && !names.includes(name)) continue;
    enc(path.join(src, name), path.join(ROOT, 'public/rec', name + '.mp4'), name.startsWith('l-') ? 'scale=1920:1080:flags=lanczos' : null);
    console.log('rec', name);
  }
}
