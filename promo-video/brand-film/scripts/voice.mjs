// 旁白入轨：audio/tts → public/audio/vo，生成 src/content/voice.json（时间轴）与 out/brand-film.srt（字幕）
import fs from 'node:fs'; import path from 'node:path'; import { fileURLToPath } from 'node:url';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const m = JSON.parse(fs.readFileSync(path.join(ROOT, 'audio/tts/manifest.json'), 'utf8'));
fs.mkdirSync(path.join(ROOT, 'public/audio/vo'), { recursive: true }); fs.mkdirSync(path.join(ROOT, 'out'), { recursive: true });
const lines = m.lines.map((l) => { fs.copyFileSync(path.join(ROOT, 'audio/tts', l.file), path.join(ROOT, 'public/audio/vo', l.file)); return { id: l.id, at: l.at, duration: l.duration, text: l.text }; });
for (let i = 1; i < lines.length; i++) if (lines[i].at < lines[i - 1].at + lines[i - 1].duration + 0.15) throw new Error(`旁白重叠：${lines[i - 1].id} → ${lines[i].id}`);
fs.writeFileSync(path.join(ROOT, 'src/content/voice.json'), JSON.stringify(lines, null, 1));
// 字幕：一句一条；长句按句号/逗号切成两条，时长按字数分配
const ts = (s) => { const ms = Math.round(s * 1000); const p = (n, w = 2) => String(n).padStart(w, '0'); return `${p(Math.floor(ms / 3600000))}:${p(Math.floor(ms / 60000) % 60)}:${p(Math.floor(ms / 1000) % 60)},${p(ms % 1000, 3)}`; };
const cues = [];
for (const l of lines) {
  const parts = l.text.length > 18 ? l.text.match(/[^。？！]+[。？！]?/g).flatMap((s) => (s.length > 20 ? s.match(/[^，：]+[，：。？！]?/g).reduce((a, x) => { if (a.length && (a[a.length - 1] + x).length <= 16) a[a.length - 1] += x; else a.push(x); return a; }, []) : [s])) : [l.text];
  const total = parts.reduce((a, s) => a + s.length, 0); let t = l.at;
  for (const s of parts) { const d = l.duration * s.length / total; cues.push({ a: t, b: t + d, text: s.replace(/[，。：]$/, '') }); t += d; }
}
fs.writeFileSync(path.join(ROOT, 'out/brand-film.srt'), cues.map((c, i) => `${i + 1}\n${ts(c.a)} --> ${ts(c.b)}\n${c.text}\n`).join('\n'));
console.log(lines.length, 'lines', cues.length, 'cues');
