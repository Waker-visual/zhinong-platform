"""Trace the supplied reference into colored glyph coordinates (Pillow required).

The reference is a build-time input, never a runtime image background.
Usage: python scripts/trace-login-reference.py path/to/reference.png
"""
import argparse
import colorsys
import hashlib
import json
import pathlib

from PIL import Image

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('source', type=pathlib.Path)
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parent.parent
source = Image.open(args.source).convert('RGB')
width, height = source.size
pixels = source.load()
step = 4
cells = []

def is_ink(rgb):
    high, low = max(rgb), min(rgb)
    # Discard the cream paper and its shading, retaining colored/dark strokes.
    return high < 155 or (high-low)/max(1, high) > .23

for y in range(0, height, step):
    for x in range(0, width, step):
        ink = [pixels[xx, yy] for yy in range(y, min(y+step, height))
               for xx in range(x, min(x+step, width)) if is_ink(pixels[xx, yy])]
        if len(ink) < 3:
            continue
        # Paper antialiasing must not dilute the original dark strokes and bright grain.
        core = sorted(ink, key=lambda c: c[0]*.21+c[1]*.72+c[2]*.07)[:max(1, len(ink)//2)]
        rgb = tuple(round(sum(c[i] for c in core)/len(core)) for i in range(3))
        hue, sat, value = colorsys.rgb_to_hsv(*(c/255 for c in rgb))
        water = .43 < hue < .62
        green = .16 < hue < .45
        grain = .08 < hue < .17 and sat > .4
        u, v = (x+2)/width, (y+2)/height
        # Character density follows actual ink coverage, not invented scene shapes.
        char = '.' if len(ink) < 5 else ':' if len(ink) < 9 else '%' if len(ink) < 13 else '#'
        if water and v > .47:
            char = '-' if len(ink) < 10 else '='
        layer = 0
        if v > .59 and (grain or green):
            layer = 1
        if water and v > .49:
            layer = 2
        if water and v < .23:
            layer = 4
        if green and value > .25 and ((.265 < u < .355 and .33 < v < .57) or (u > .9 and .28 < v < .65)):
            layer = 5
        if .665 < u < .785 and .65 < v < .716 and not water:
            layer = 3
        # Separate the traced figure and oar from the hull for articulated slow motion.
        if .748 < u < .765 and .648 < v < .693:
            layer = 6
        pole_x = .762+(v-.667)*.6
        if .667 < v < .718 and abs(u-pole_x) < .0025 and value < .6:
            layer = 7
        cells.append((x+2, y+2, rgb, '.:%#-='.index(char), layer))

swatches = Image.new('RGB', (len(cells), 1))
swatches.putdata([c[2] for c in cells])
palette_size = 48
quantized = swatches.quantize(colors=palette_size, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
palette = quantized.getpalette()[:palette_size*3]
indices = list(quantized.tobytes())
points = [value for c, index in zip(cells, indices) for value in (c[0], c[1], index, c[3], c[4])]
data = {'width': width, 'height': height, 'step': step, 'glyphs': '.:%#-=',
        'paletteSize': palette_size, 'sourceSha256': hashlib.sha256(args.source.read_bytes()).hexdigest(), 'points': points}
(root/'frontend/src/ui/login-reference.json').write_text(json.dumps(data, separators=(',', ':')), encoding='utf-8')

day, night = [], []
for index in range(palette_size):
    rgb = palette[index*3:index*3+3]
    day.append(f'  --pastoral-ink-{index}: #{bytes(rgb).hex()};')
    hue, light, sat = colorsys.rgb_to_hls(*(c/255 for c in rgb))
    night_rgb = colorsys.hls_to_rgb(hue, .4+light*.32, sat*.6)
    night.append(f'  --pastoral-ink-{index}: #{bytes(round(c*255) for c in night_rgb).hex()};')
css = root/'frontend/src/account/theme.css'
text = css.read_text(encoding='utf-8')
start, end = '/* Reference glyph palette: start */', '/* Reference glyph palette: end */'
block = start+'\n.login-story {\n'+'\n'.join(day)+'\n}\nhtml[data-theme="dark"] .login-story {\n'+'\n'.join(night)+'\n}\n'+end
if start in text:
    a, b = text.index(start), text.index(end)+len(end)
    text = text[:a]+block+text[b:]
else:
    text += '\n'+block+'\n'
css.write_text(text, encoding='utf-8')
print(f'Traced {len(cells)} glyphs at {width} x {height}; paper omitted, {palette_size} ink colors.')
