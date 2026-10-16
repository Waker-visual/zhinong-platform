"""生成界面字体子集与 frontend/src/fonts.css。

用法（仓库根目录，需要 Node.js 与 `pip install fonttools brotli`）：
    python tools/subset_fonts.py

思源黑体按浏览器 unicode-range 切片，页面只下载用到的切片：
  first   登录首屏文字（tools/check_fonts.cjs 从登录页源码收集）
  ui      其余界面文字及后端演示数据中的汉字
  common  GB 2312 汉字与符号，按码位连续分段，用于用户输入和 AI 回复中的常用字
  rare    原字体其余字符，按码位连续分段
common 与 rare 只取决于原字体，界面文字变化时重新生成也不会改变这些文件。
Iosevka Aile 四个字重只保留 fonts.css 声明的拉丁字符。
思源黑体带保留字体名“Source”，子集属于 SIL OFL 1.1 所称修改版本，因此改用“Zhihe Han”命名。
"""
import hashlib
import json
import os
import pathlib
import subprocess
from concurrent.futures import ProcessPoolExecutor

from fontTools import subset
from fontTools.ttLib import TTFont

ROOT = pathlib.Path(__file__).resolve().parent.parent
FRONTEND = ROOT / "frontend"
OUT = FRONTEND / "src/assets/fonts"
CACHE = ROOT / ".cache/fonts"
HAN_SOURCE = FRONTEND / "font-sources/source-han-sans-cn-vf-2.005.woff2"
LATIN_SOURCE = FRONTEND / "node_modules/@fontsource/iosevka-aile/files/iosevka-aile-latin-{}-normal.woff2"
LATIN_WEIGHTS = (400, 500, 600, 700)
LATIN_RANGE = [(0x0000, 0x00FF), (0x0131, 0x0131), (0x0152, 0x0153), (0x02C6, 0x02C6), (0x02DC, 0x02DC),
               (0x20AC, 0x20AC), (0x2122, 0x2122), (0x2190, 0x2193), (0x2212, 0x2212)]
# 只放进拉丁字体、不写入其 unicode-range 的字符。Chromium 截断文字时先查首选字体（字体栈中首个覆盖空格的
# Iosevka）有无 U+2026，有才插入“…”并交由思源黑体绘制居中省略号，没有则退化为三个句点。
LATIN_PRIMARY_EXTRAS = [0x2026]
COMMON_SLICE = 600
RARE_SLICE = 1000


def in_ranges(ranges, cp):
    return any(a <= cp <= b for a, b in ranges)


def gb2312_chars():
    chars = set()
    for high in range(0xA1, 0xF8):
        for low in range(0xA1, 0xFF):
            try:
                chars.add(ord(bytes([high, low]).decode("gb2312")))
            except UnicodeDecodeError:
                pass
    return chars


def spans(cps):
    runs = []
    for cp in sorted(cps):
        if runs and runs[-1][1] + 1 == cp:
            runs[-1][1] = cp
        else:
            runs.append([cp, cp])
    return [tuple(run) for run in runs]


def css_range(ranges):
    parts = [f"U+{a:04X}" if a == b else f"U+{a:04X}-{b:04X}" for a, b in ranges]
    lines, line = [], ""
    for part in parts:
        if line and len(line) + len(part) > 94:
            lines.append(line)
            line = ""
        line += (", " if line else "") + part
    lines.append(line)
    return lines[0] if len(lines) == 1 else "\n    " + ",\n    ".join(lines)


def rename_han(font, slice_name):
    # 主字体名不再使用保留字体名“Source”；版权、许可证与商标声明原样保留。
    postscript = "ZhiheHan-" + slice_name.title().replace("-", "")
    for record in font["name"].names:
        text = record.toUnicode()
        if record.nameID in (1, 4, 16, 21):
            record.string = "Zhihe Han"
        elif record.nameID == 3:
            record.string = f"2.005;ZHIHE;{postscript}"
        elif record.nameID in (6, 20, 25):
            record.string = postscript
        elif "SourceHanSans" in text or "Source Han Sans" in text:
            record.string = text.replace("SourceHanSansCNVF", "ZhiheHan").replace("Source Han Sans CN VF", "Zhihe Han")
    font["name"].setName("Zhihe Han: subset of Adobe Source Han Sans CN VF 2.005 for the Zhihe farm web interface. "
                         "Modified Version under the SIL Open Font License 1.1.", 10, 3, 1, 0x409)
    leftover = [r.nameID for r in font["name"].names if r.nameID not in (0, 7, 10, 13) and "Source" in r.toUnicode()]
    if leftover:
        raise SystemExit(f"Reserved Font Name remains in name IDs {leftover}")


def write_subset(source, unicodes, target, han_slice=None):
    options = subset.Options()
    options.flavor = "woff2"
    options.name_IDs = ["*"]
    options.name_languages = ["*"]
    options.notdef_outline = True
    options.hinting = True
    font = TTFont(source, recalcTimestamp=False)
    subsetter = subset.Subsetter(options)
    subsetter.populate(unicodes=sorted(unicodes))
    subsetter.subset(font)
    if han_slice:
        rename_han(font, han_slice)
    font.flavor = "woff2"
    font.save(target, reorderTables=True)
    return target.stat().st_size


def blocks(cps, size):
    cps = sorted(cps)
    return [cps[i:i + size] for i in range(0, len(cps), size)]


def clip(first, last, ranges):
    # 连续码段只保留汉字字面负责的范围，避免 ℃、●、✓ 等落在码段中间的符号触发切片下载。
    return [(max(a, first), min(b, last)) for a, b in ranges if a <= last and b >= first]


def main():
    required = json.loads(subprocess.run(["node", str(ROOT / "tools/check_fonts.cjs"), "chars"], check=True,
                                         capture_output=True, text=True, encoding="utf-8").stdout)
    han_range = [tuple(r) for r in required["range"]]
    source_chars = {cp for cp in TTFont(HAN_SOURCE).getBestCmap() if in_ranges(han_range, cp)}
    first = set(required["first"]) & source_chars
    ui = set(required["ui"]) & source_chars - first
    missing = (set(required["first"]) | set(required["ui"])) - source_chars
    if missing:
        raise SystemExit("思源黑体缺少界面字符：" + "".join(map(chr, sorted(missing))))
    common = gb2312_chars() & source_chars
    rare = source_chars - common

    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob("zhihe-*.woff2"):
        old.unlink()
    jobs, faces = [], []  # faces: (字族, 文件名, 字重, unicode-range 码段)
    latin = {cp for a, b in LATIN_RANGE for cp in range(a, b + 1)} | set(LATIN_PRIMARY_EXTRAS)
    for weight in LATIN_WEIGHTS:
        name = f"zhihe-latin-{weight}.woff2"
        jobs.append((str(LATIN_SOURCE).format(weight), latin, OUT / name, None))
        faces.append(("Zhihe Latin", name, str(weight), LATIN_RANGE))
    # 先解出一份 TTF 并按原字体摘要缓存在 .cache/，避免每个切片重复还原 WOFF2 的 glyf 变换（首次约需数分钟）。
    digest = hashlib.sha256(HAN_SOURCE.read_bytes()).hexdigest()[:16]
    han_ttf = CACHE / f"source-han-{digest}.ttf"
    if not han_ttf.exists():
        CACHE.mkdir(parents=True, exist_ok=True)
        source = TTFont(HAN_SOURCE, recalcTimestamp=False)
        source.flavor = None
        source.save(han_ttf.with_suffix(".tmp"), reorderTables=False)
        han_ttf.with_suffix(".tmp").replace(han_ttf)
    # 浏览器倒序检查同族字面：rare 最先定义、first 最后定义，界面文字因此总是先命中界面切片。
    # common/rare 的 unicode-range 写成连续码段，保持 CSS 精简；码段内缺字时浏览器继续查找前面定义的切片。
    for tier, groups in (("rare", blocks(rare, RARE_SLICE)), ("common", blocks(common, COMMON_SLICE))):
        for index, cps in enumerate(groups, 1):
            name = f"zhihe-han-{tier}-{index:02d}.woff2"
            jobs.append((han_ttf, cps, OUT / name, f"{tier}-{index:02d}"))
            faces.append(("Zhihe Han", name, "250 900", clip(cps[0], cps[-1], han_range)))
    for tier, cps in (("ui", ui), ("first", first)):
        name = f"zhihe-han-{tier}.woff2"
        jobs.append((han_ttf, cps, OUT / name, tier))
        faces.append(("Zhihe Han", name, "250 900", spans(cps)))
    # 每个进程各自载入原字体，限制并发以控制内存占用。
    with ProcessPoolExecutor(max_workers=min(8, os.cpu_count() or 1)) as pool:
        list(pool.map(write_subset, *zip(*jobs)))

    rules = [f"""@font-face {{
  font-family: "{family}";
  src: url("./assets/fonts/{name}") format("woff2");
  font-style: normal;
  font-weight: {weight};
  font-display: swap;
  unicode-range: {css_range(ranges)};
}}""" for family, name, weight, ranges in faces]
    header = """/* 由 tools/subset_fonts.py 生成，请勿手改；界面文字新增汉字后重新运行，并用 node tools/check_fonts.cjs 检查。
 * 界面字体：字母和数字用 Iosevka Aile，汉字和中文标点用思源黑体，两个字面的 unicode-range 互不重叠。
 * Iosevka Aile v22.1.2（@fontsource/iosevka-aile 5.3.0）只保留下列拉丁字符。
 * “Zhihe Han”是 Adobe Source Han Sans CN VF 2.005 的子集切片（SIL OFL 1.1 修改版本，按保留字体名要求改名）：
 * 浏览器倒序检查同族字面，first 登录首屏、ui 其余界面文字最先命中；common 为 GB 2312 常用字，rare 为其余字符。
 * 许可证：两者均为 SIL OFL 1.1，原文随构建产物发布在 /licenses/。 */
"""
    (FRONTEND / "src/fonts.css").write_text(header + "\n" + "\n".join(rules) + "\n", encoding="utf-8", newline="\n")
    sizes = {p.name: p.stat().st_size for p in OUT.glob("zhihe-*.woff2")}
    print(f"first {len(first)} 字 {sizes['zhihe-han-first.woff2']} B；ui {len(ui)} 字 {sizes['zhihe-han-ui.woff2']} B；"
          f"common {len(common)} 字；rare {len(rare)} 字；共 {len(sizes)} 个文件 {sum(sizes.values())} B")


if __name__ == "__main__":
    main()
