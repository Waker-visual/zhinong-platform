"""配乐剪辑：Documentary Nature（169.6 s）在小节线处删去 B 段 8 小节，得到约 150 s。
输出 public/audio/music-cut.wav 与 src/beats.json（成片时间轴上的拍点、小节线、段落）。
用法：python -I audio/music.py"""
import json, pathlib, subprocess, tempfile
import numpy as np
from scipy.io import wavfile
import imageio_ffmpeg

HERE = pathlib.Path(__file__).parent
ROOT = HERE.parent
FF = imageio_ffmpeg.get_ffmpeg_exe()
SR = 48000
an = json.loads((HERE / "out" / "analysis.json").read_text(encoding="utf-8"))
down = np.array(an["downbeats"]); beats = np.array(an["beats"])

# B 段 48.1–67.2 s：取最接近 48.1 的小节线，向后数 8 小节
ia = int(np.argmin(np.abs(down - 48.1)))
cut_a, cut_b = float(down[ia]), float(down[ia + 8])
gap = cut_b - cut_a
total = an["duration"] - gap

with tempfile.TemporaryDirectory() as d:
    w = pathlib.Path(d) / "a.wav"
    subprocess.run([FF, "-v", "error", "-y", "-i", str(ROOT / "public/audio/music-src.mp3"), "-ac", "2", "-ar", str(SR), "-acodec", "pcm_f32le", str(w)], check=True)
    _, x = wavfile.read(w)
x = x.astype(np.float32)
xf = 0.08  # 小节线上等功率交叉淡化
h = int(xf * SR / 2)
a_end, b_start = int(cut_a * SR), int(cut_b * SR)
A = x[: a_end + h].copy(); B = x[b_start - h:].copy()
ramp = np.linspace(0, 1, 2 * h, dtype=np.float32)
A[-2 * h:] *= np.cos(ramp * np.pi / 2)[:, None]
B[: 2 * h] *= np.sin(ramp * np.pi / 2)[:, None]
out = np.zeros((a_end - h + len(B), 2), np.float32)
out[: len(A)] += A
out[a_end - h:] += B
(ROOT / "public/audio").mkdir(parents=True, exist_ok=True)
wavfile.write(ROOT / "public/audio/music-cut.wav", SR, out)

m = lambda t: t if t <= cut_a + 0.05 else t - gap
keep = lambda arr: [round(m(float(t)), 3) for t in arr if not (cut_a < t < cut_b)]
bounds = list(an["segments"]) + [an["duration"]]  # 段落边界点
segs = []
for a, b in zip(bounds, bounds[1:]):
    if a >= cut_a - 0.5 and b <= cut_b + 0.5: continue
    segs.append([round(m(a), 2), round(m(b), 2)])
(ROOT / "src/beats.json").write_text(json.dumps({"cut": [round(cut_a, 3), round(cut_b, 3)], "total": round(total, 3), "beats": keep(beats), "downbeats": keep(down), "segments": segs}, ensure_ascii=False), encoding="utf-8")
print("cut", round(cut_a, 3), round(cut_b, 3), "total", round(total, 2), "bars", len(keep(down)))
print("segments", segs)
