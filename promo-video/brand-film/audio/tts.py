"""按 script/narration.json 准备旁白音频 → audio/tts/<id>.mp3 + manifest.json。
带 cache 的句子直接复制旧片已合成的同文本音频；其余新句调用 MiniMax（沿用旧片的 minimax.py，密钥由它自己读取，本脚本不接触、不输出）。
用法：python -I audio/tts.py            （PROMO_SRC 可指定旧片目录，默认 E:/智慧农场/promo-video）
"""
import json, os, pathlib, re, shutil, sys

HERE = pathlib.Path(__file__).parent
SRC = pathlib.Path(os.environ.get("PROMO_SRC", "E:/智慧农场/promo-video"))
OLD = SRC / "voice" / "out" / "minimax-keynoteB-v4"
OUT = HERE / "tts"
OUT.mkdir(parents=True, exist_ok=True)
script = json.loads((HERE.parent / "script" / "narration.json").read_text(encoding="utf-8"))
voice, speed = script["voice"]["voice_id"], script["voice"]["speed"]

sys.path.insert(0, str(SRC / "voice"))
import minimax  # noqa: E402  旧片的合成函数

items, new_chars = [], 0
for l in script["lines"]:
    mp3, meta = OUT / f"{l['id']}.mp3", OUT / f"{l['id']}.json"
    key = {"text": l["text"], "speed": speed, "voice": voice, "emotion": None}
    have = json.loads(meta.read_text(encoding="utf-8")) if meta.exists() and mp3.exists() else None
    if have and have.get("key") == key:
        dur, subs, how = have["dur"], have["subs"], "kept"
    elif l.get("cache"):
        old = json.loads((OLD / f"{l['cache']}.json").read_text(encoding="utf-8"))
        # 旧音频的文本里带停顿标记（<#0.3#>），去掉标记后字句须与脚本完全一致
        strip = lambda t: re.sub(r"<#[0-9.]+#>", "", t)
        if strip(old["key"]["text"]) != l["text"] or {k: v for k, v in old["key"].items() if k != "text"} != {k: v for k, v in key.items() if k != "text"}:
            raise SystemExit(f"{l['id']}: 缓存 {l['cache']} 的文本或参数与脚本不一致，拒绝复用")
        shutil.copyfile(OLD / f"{l['cache']}.mp3", mp3)
        dur, subs, how = old["dur"], old["subs"], "cache"
        meta.write_text(json.dumps({"key": key, "dur": dur, "subs": subs}, ensure_ascii=False), encoding="utf-8")
    else:
        dur, subs, used = minimax.tts(l["text"], voice, mp3, speed=speed)
        new_chars += used or len(l["text"])
        how = "new"
        meta.write_text(json.dumps({"key": key, "dur": dur, "subs": subs}, ensure_ascii=False), encoding="utf-8")
    items.append({"id": l["id"], "at": l["at"], "text": l["text"], "file": f"{l['id']}.mp3", "duration": round(dur, 3), "source": how})
    print(l["id"], how, round(dur, 2))
(OUT / "manifest.json").write_text(json.dumps({"voice": voice, "engine": script["voice"]["engine"], "lines": items}, ensure_ascii=False, indent=1), encoding="utf-8")
print("new characters billed:", new_chars)
