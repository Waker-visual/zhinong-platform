"""旁白合成：复用 promo-video/voice/minimax.py 的 keynoteB 原创音色（密钥从 music/voice.env 读取，不写入输出）。
用法：python tts.py  →  audio/tts/<id>.mp3 + manifest.json（时长供时间轴对位）
"""
import importlib.util, json, pathlib, sys

HERE = pathlib.Path(__file__).parent
VOICE_DIR = HERE.parents[1] / "voice"
if not (VOICE_DIR / "minimax.py").exists():  # worktree 内没有采集工程时，回到主检出读取
    VOICE_DIR = pathlib.Path(r"E:\智慧农场\promo-video\voice")
spec = importlib.util.spec_from_file_location("minimax", VOICE_DIR / "minimax.py")
mm = importlib.util.module_from_spec(spec); spec.loader.exec_module(mm)

VOICE = "ttv-voice-2026101123070226-oonzt5In"  # keynoteB
LINES = [
    ("v1", "发现需要补灌的田块。"),
    ("v2", "核对范围、设备与时长，由人确认启动。"),
    ("v3", "指令进入对应田块，设备模拟运行。"),
    ("v4", "从动作到状态，执行过程清晰可见。"),
]

out = HERE / "tts"; out.mkdir(exist_ok=True)
manifest = {}
for key, text in LINES:
    dur, subs, used = mm.tts(text, VOICE, out / f"{key}.mp3", speed=0.98, subtitles=False)
    manifest[key] = {"text": text, "seconds": round(dur, 3)}
    print(key, f"{dur:.2f}s", text)
(out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
