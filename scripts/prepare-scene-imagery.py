"""Reassemble the project's existing public map cache; never downloads new imagery."""
import base64
import io
import json
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.cache/map-tiles'
OUT = ROOT / 'unity/FarmScene/Assets/Resources/SatelliteImagery'
Z, X, Y, WIDTH, HEIGHT, SCALE = 15, 28463, 11489, 1536, 1024, 4

def build():
    result = Image.new('RGB', (WIDTH*SCALE, HEIGHT*SCALE), (64, 75, 52))
    coverage = Image.new('L', result.size, 0)
    used = []
    for p in sorted(CACHE.glob('SATELLITE-*.json'), key=lambda p: int(p.stem.split('-')[1])):
        _, zoom, x, y = p.stem.split('-')
        zoom, x, y = map(int, (zoom, x, y))
        k = 2**(zoom-Z)
        left, top, size = (x/k-X)*256*SCALE, (y/k-Y)*256*SCALE, 256*SCALE/k
        if left+size<=0 or top+size<=0 or left>=result.width or top>=result.height:
            continue
        data = json.loads(p.read_text(encoding='utf-8'))
        tile = Image.open(io.BytesIO(base64.b64decode(data['bytes']))).convert('RGB')
        box = (round(left), round(top))
        result.paste(tile.resize((round(size), round(size)), Image.Resampling.LANCZOS), box)
        coverage.paste(255, (round(left), round(top), round(left+size), round(top+size)))
        used.append(p.name)
    if coverage.getextrema()[0] != 255:
        raise RuntimeError('Satellite footprint contains uncached pixels; do not publish incomplete ground')
    OUT.mkdir(parents=True, exist_ok=True)
    result.save(OUT / 'region.jpg', quality=94)
    # Wider existing z13 cache supplies the horizon beyond the detailed central image.
    context = Image.new('RGB', (2048, 2048))
    for x in range(7114, 7118):
        for y in range(2871, 2875):
            path = CACHE / f'SATELLITE-13-{x}-{y}.json'
            data = json.loads(path.read_text(encoding='utf-8'))
            tile = Image.open(io.BytesIO(base64.b64decode(data['bytes']))).convert('RGB')
            context.paste(tile.resize((512,512),Image.Resampling.LANCZOS), ((x-7114)*512, (y-2871)*512))
            if path.name not in used:
                used.append(path.name)
    # The cache also has closer views beyond the central footprint. Overlay them
    # so the surrounding image shares the same colors/date at the detail seam.
    for p in sorted(CACHE.glob('SATELLITE-*.json'), key=lambda p: int(p.stem.split('-')[1])):
        _, zoom, x, y = p.stem.split('-')
        zoom, x, y = map(int, (zoom, x, y))
        if zoom<=13:
            continue
        k=2**(zoom-13)
        left,top,size=(x/k-7114)*512,(y/k-2871)*512,512/k
        if left+size<=0 or top+size<=0 or left>=context.width or top>=context.height:
            continue
        data=json.loads(p.read_text(encoding='utf-8'))
        tile=Image.open(io.BytesIO(base64.b64decode(data['bytes']))).convert('RGB')
        context.paste(tile.resize((round(size),round(size)),Image.Resampling.LANCZOS),(round(left),round(top)))
        if p.name not in used:
            used.append(p.name)
    context.save(OUT / 'context.jpg', quality=94)
    (OUT / 'SOURCE.txt').write_text('Esri World Imagery / Esri, Vantor, Earthstar Geographics, GIS User Community.\nReassembled solely from existing application tile cache. Non-live imagery.\nDetail: z15 x28463..28468 y11489..11492. Context: z13 x7114..7117 y2871..2874, overlaid with available higher-resolution cached tiles. North-up.\nOriginal image attribution retained; not a surveyed terrain model.\n'+'\n'.join(used), encoding='utf-8')
    print(f'Assembled {len(used)} cached tiles into {result.width} x {result.height} detail and 2048 x 2048 surrounding image')

if __name__ == '__main__':
    build()
