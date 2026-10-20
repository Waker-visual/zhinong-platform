"""80 秒成片混音：配乐剪辑 + 旁白 + 程序化音效，输出 public/audio/mix.wav（-16 LUFS，真峰值 ≤ -1.5 dBTP）。
配乐：Documentary Nature — alex-morgan（Pixabay Content License），在小节线处剪接：0–63.0 s 接尾声 152.53 s 起。
音效全部在本脚本内合成（无第三方素材）。
"""
import json, pathlib, subprocess, tempfile
import numpy as np
from scipy.io import wavfile
from scipy.signal import butter, sosfilt

HERE = pathlib.Path(__file__).parent
ROOT = HERE.parent
FF = r"C:\Users\xx\AppData\Local\Programs\Python\Python313\Lib\site-packages\imageio_ffmpeg\binaries\ffmpeg-win-x86_64-v7.1.exe"
SR = 48000
DUR = 80.0
N = int(SR * DUR)
rng = np.random.default_rng(7)


def load(path):
    with tempfile.TemporaryDirectory() as d:
        out = pathlib.Path(d) / "a.wav"
        subprocess.run([FF, "-v", "error", "-y", "-i", str(path), "-ac", "2", "-ar", str(SR), "-f", "wav", "-acodec", "pcm_f32le", str(out)], check=True)
        sr, x = wavfile.read(out)
    return x.astype(np.float32)


def db(x):
    return 10 ** (x / 20)


def band(x, lo, hi, order=4):
    sos = butter(order, [lo, hi], btype="band", fs=SR, output="sos")
    return sosfilt(sos, x)


def hp(x, f):
    return sosfilt(butter(4, f, btype="high", fs=SR, output="sos"), x)


def lp(x, f):
    return sosfilt(butter(4, f, btype="low", fs=SR, output="sos"), x)


def env_curve(points):
    """分段线性包络：[(秒, 增益dB), ...]"""
    t = np.arange(N) / SR
    xs = [p[0] for p in points]
    ys = [db(p[1]) for p in points]
    return np.interp(t, xs, ys).astype(np.float32)


mixL = np.zeros(N, np.float32)
mixR = np.zeros(N, np.float32)


def place(sig, at, gain_db=0.0, pan=0.0):
    """单声道或立体声信号放到 at 秒处"""
    g = db(gain_db)
    i0 = int(at * SR)
    if sig.ndim == 1:
        l, r = sig * g * np.sqrt(0.5 * (1 - pan)), sig * g * np.sqrt(0.5 * (1 + pan))
        l, r = l * np.sqrt(2), r * np.sqrt(2)
    else:
        l, r = sig[:, 0] * g, sig[:, 1] * g
    n = min(len(l), N - i0)
    if n <= 0:
        return
    mixL[i0:i0 + n] += l[:n]
    mixR[i0:i0 + n] += r[:n]


def tone(freqs, dur, decay=4.0, attack=0.004, amps=None):
    t = np.arange(int(dur * SR)) / SR
    amps = amps or [1.0] * len(freqs)
    s = sum(a * np.sin(2 * np.pi * f * t) for f, a in zip(freqs, amps))
    e = np.minimum(1, t / attack) * np.exp(-decay * t)
    return (s * e / max(1, len(freqs))).astype(np.float32)


def noise(dur):
    return rng.standard_normal(int(dur * SR)).astype(np.float32)


# ---------------- 配乐 ----------------
music = load(ROOT / "public" / "audio" / "music-src.mp3")
cut, tail_in, xf = 63.0, 152.53, 1.6
a = music[: int((cut + xf / 2) * SR)]
b = music[int((tail_in - xf / 2) * SR):]
out = np.zeros((N, 2), np.float32)
na = min(len(a), N)
out[:na] += a[:na] * np.cos(np.clip((np.arange(na) / SR - (cut - xf / 2)) / xf, 0, 1) * np.pi / 2)[:, None]
ib = int((cut - xf / 2) * SR)
nb = min(len(b), N - ib)
fade_b = np.sin(np.clip(np.arange(nb) / SR / xf, 0, 1) * np.pi / 2)
out[ib:ib + nb] += b[:nb] * fade_b[:, None]
# 片尾在 80 s 前 1.2 s 收尾
fin = np.clip((DUR - np.arange(N) / SR) / 1.2, 0, 1)
out *= fin[:, None]

VO = [("v1", 5.7), ("v2", 23.7), ("v3", 30.9), ("v4", 51.3)]
man = json.loads((HERE / "tts" / "manifest.json").read_text(encoding="utf-8"))
duck = [(0, 0)]
for k, at in VO:
    d = man[k]["seconds"]
    duck += [(at - 0.35, 0), (at - 0.05, -7.5), (at + d, -7.5), (at + d + 0.6, 0)]
duck.append((DUR, 0))
duck.sort()
# 设备段为水泵、流水声留出空间；分屏反馈段略收
bed = env_curve([(0, -1.5), (4, 0), (34.5, 0), (36, -3.5), (49.5, -3.5), (51, -2), (61.5, -2), (63, 0), (DUR, 0)])
music_gain = env_curve(duck) * bed * db(-9.0)
place(out * music_gain[:, None], 0.0)

# ---------------- 旁白 ----------------
for k, at in VO:
    v = load(HERE / "tts" / f"{k}.mp3")
    v = v / (np.max(np.abs(v)) + 1e-9) * db(-2.0)
    place(v, at, 0.0)

# ---------------- 音效 ----------------
def whoosh(dur=0.9, lo=250, hi=3500):
    n = noise(dur)
    t = np.arange(len(n)) / SR
    e = np.sin(np.pi * np.clip(t / dur, 0, 1)) ** 2
    s = band(n, lo, hi) * e
    return s / (np.max(np.abs(s)) + 1e-9)

def click():
    t = np.arange(int(0.05 * SR)) / SR
    s = np.sin(2 * np.pi * 2200 * t) * np.exp(-90 * t) + 0.5 * band(noise(0.05), 1500, 6000) * np.exp(-140 * t)
    return (s / np.max(np.abs(s))).astype(np.float32)

def chime(f0=1318.5, dur=1.6):
    return tone([f0, f0 * 1.5, f0 * 2.0], dur, decay=3.2, amps=[1, 0.55, 0.25])

def thud(f=70, dur=0.6):
    t = np.arange(int(dur * SR)) / SR
    sweep = 2 * np.pi * (f * t + 40 * (1 - np.exp(-t * 18)) / 18)
    return (np.sin(sweep) * np.exp(-7 * t)).astype(np.float32)

# 转场
for at in (4.72, 13.85, 29.65, 50.0, 62.1, 71.5):
    place(whoosh(0.95), at, -21)
# 点击确认
place(click(), 24.2, -14)
place(click(), 27.0, -13)
place(chime(1318.5, 1.4), 27.05, -21)
# 校验通过
place(tone([1760], 0.18, decay=24), 28.1, -24)
place(tone([1760], 0.18, decay=24), 28.45, -24)
place(chime(1567.98, 1.2), 28.65, -22)
# 指令抵达 C-01 / 设备模拟接收
place(thud(60, 0.9), 31.0, -14)
place(chime(987.77, 2.2), 31.02, -23)
place(tone([880, 1318.5], 0.5, decay=8), 33.4, -20)
place(tone([1318.5], 0.4, decay=10), 33.55, -22)
# 指示灯点亮
place(tone([1200], 0.25, decay=14), 35.6, -22)

# 水泵：启动、持续运转、停止（频率随转速变化）
t = np.arange(N) / SR
spin_up = np.clip((t - 35.9) / 1.2, 0, 1)
spin_dn = 1 - np.clip((t - 60.1) / 1.4, 0, 1)
speed = spin_up * spin_dn
f0 = 38 + 22 * speed
phase = 2 * np.pi * np.cumsum(f0) / SR
hum = (np.sin(phase) + 0.5 * np.sin(2 * phase) + 0.28 * np.sin(3 * phase) + 0.12 * np.sin(5 * phase))
motor = band(noise(DUR), 120, 900) * 0.35
pump_level = env_curve([(0, -80), (35.85, -80), (36.3, -20), (37.2, -18), (42, -21), (46, -26), (49.5, -24), (50.5, -29), (60.0, -29), (61.6, -45), (62.3, -80), (DUR, -80)])
place(((hum * 0.6 + motor) * speed * pump_level).astype(np.float32), 0.0)
place(thud(48, 0.7), 35.9, -12)

# 流水：管内水声（中频噪声），田间喷洒（高频嘶声）
water = band(noise(DUR), 250, 2200) * (0.75 + 0.25 * np.sin(2 * np.pi * 0.37 * t))
water_level = env_curve([(0, -80), (37.1, -80), (38.0, -27), (42, -24), (46, -22), (49.5, -24), (51, -30), (60.2, -30), (61.8, -60), (62.4, -80), (DUR, -80)])
place((water * water_level).astype(np.float32), 0.0, pan=-0.1)
spray = hp(noise(DUR), 2800) * (0.8 + 0.2 * np.sin(2 * np.pi * 1.3 * t))
spray_level = env_curve([(0, -80), (46.95, -80), (47.6, -27), (49.5, -27), (51, -33), (60.3, -33), (61.7, -70), (62.2, -80), (DUR, -80)])
place((spray * spray_level).astype(np.float32), 0.0, pan=0.15)
# 阀门
clunk = (band(noise(0.25), 400, 2500) * np.exp(-30 * np.arange(int(0.25 * SR)) / SR) + tone([220, 330], 0.25, decay=18) * 2)
place(clunk / np.max(np.abs(clunk)), 46.0, -17)
# 停泵、运行结束
place(tone([660, 440], 0.9, decay=4), 60.1, -24)
place(chime(1174.66, 1.8), 61.0, -19)
# 协作段步骤
for at in (62.9, 65.2, 67.5, 69.6):
    place(tone([1046.5, 1568], 0.3, decay=12, amps=[1, 0.4]), at, -25)
# 品牌落版
place(chime(783.99, 3.0), 76.6, -20)
place(chime(1174.66, 2.6), 76.75, -26)

mix = np.stack([mixL, mixR], axis=1)
tmp = HERE / "out"
tmp.mkdir(exist_ok=True)
raw = tmp / "mix-raw.wav"
wavfile.write(raw, SR, mix.astype(np.float32))
dst = ROOT / "public" / "audio" / "mix.wav"
subprocess.run([FF, "-v", "error", "-y", "-i", str(raw), "-af", "loudnorm=I=-16:TP=-1.5:LRA=11", "-ar", str(SR), "-acodec", "pcm_s16le", str(dst)], check=True)
r = subprocess.run([FF, "-hide_banner", "-i", str(dst), "-af", "ebur128=peak=true", "-f", "null", "-"], capture_output=True, text=True, encoding="utf-8", errors="ignore")
summary = r.stderr[r.stderr.rfind("Summary:"):]
print(summary.strip())
