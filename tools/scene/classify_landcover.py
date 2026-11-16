"""Derive land-cover maps from the cached satellite imagery for the procedural ground.

The 3D farm no longer shows the photograph. Instead Farm/LandCover paints crops, water,
roads and bunds procedurally, using these maps only as a layout guide, so every field,
ditch and road stays where the imagery puts it.

Usage (repository root, after scripts/prepare-scene-imagery.py):
    python tools/scene/classify_landcover.py

Inputs  (ignored by Git): unity/FarmScene/Assets/Resources/SatelliteImagery/{region,context}.jpg
Outputs (ignored by Git, same licence and attribution as the imagery they derive from):
    landcover-<name>.png  R water/ditch, G crop maturity (0 green .. 1 golden stubble),
                          B road/path, A grass bund between fields
    landflow-<name>.png   R,G = cos 2θ, sin 2θ of the crop-row direction in world space
    landcover-<name>-preview.png (in .cache/review) for visual checks
The maps are tonal estimates, not surveyed land use: nothing in the business data is derived
from them.
"""

import os
import sys

import cv2
import numpy as np

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
IMAGERY = os.path.join(ROOT, "unity", "FarmScene", "Assets", "Resources", "SatelliteImagery")
REVIEW = os.path.join(ROOT, ".cache", "review")


def read_image(path):
    # cv2.imread cannot open non-ASCII Windows paths (the repository lives under 智慧农场).
    if not os.path.exists(path):
        return None
    return cv2.imdecode(np.fromfile(path, np.uint8), cv2.IMREAD_COLOR)


def write_image(path, image):
    ok, data = cv2.imencode(os.path.splitext(path)[1], image)
    if not ok:
        raise RuntimeError(f"could not encode {path}")
    data.tofile(path)


def smoothstep(a, b, x):
    t = np.clip((x - a) / (b - a), 0, 1)
    return t * t * (3 - 2 * t)


def keep_lines(mask, length):
    """Keep only elongated features: opening with line kernels at 12 orientations."""
    mask = mask.astype(np.float32)
    best = np.zeros_like(mask)
    centre = length // 2
    for step in range(12):
        angle = np.pi * step / 12
        kernel = np.zeros((length, length), np.uint8)
        dx, dy = np.cos(angle) * centre, np.sin(angle) * centre
        cv2.line(kernel, (int(round(centre - dx)), int(round(centre - dy))), (int(round(centre + dx)), int(round(centre + dy))), 1, 1)
        # Constant zero border: the default float erosion border is +inf and leaks into the result.
        best = np.maximum(best, cv2.morphologyEx(mask, cv2.MORPH_OPEN, kernel, borderType=cv2.BORDER_CONSTANT, borderValue=0))
    return best


def classify(path, name, native_scale, out_scale):
    """native_scale: imagery upsampling factor; out_scale: output size relative to native."""
    image = read_image(path)
    if image is None:
        print(f"skip {name}: {path} missing")
        return False
    h, w = image.shape[:2]
    native = cv2.resize(image, (w // native_scale, h // native_scale), interpolation=cv2.INTER_AREA)
    nh, nw = native.shape[:2]

    # Edge-preserving smoothing flattens texture inside each field but keeps field edges.
    flat = cv2.pyrMeanShiftFiltering(native, sp=6, sr=18)
    lab = cv2.cvtColor(flat, cv2.COLOR_BGR2LAB).astype(np.float32)
    light, yellow = lab[..., 0], lab[..., 2] - 128
    gray = cv2.cvtColor(native, cv2.COLOR_BGR2LAB)[..., 0].astype(np.float32)

    # Thin bright lines are field roads and paths; thin dark lines are ditches.
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))
    bright = cv2.morphologyEx(gray, cv2.MORPH_TOPHAT, kernel)
    dark = cv2.morphologyEx(gray, cv2.MORPH_BLACKHAT, kernel)
    road = smoothstep(14, 30, bright) * smoothstep(80, 120, gray)
    road = keep_lines(road, length=11)
    ditch = smoothstep(10, 24, dark) * (1 - smoothstep(40, 70, gray))

    # Very dark, low-chroma areas are standing water (flooded paddies, ponds, canals).
    water = np.maximum(1 - smoothstep(18, 34, light), ditch)
    # Crop maturity follows field brightness and yellowness: dark green -> golden stubble.
    maturity = np.clip(smoothstep(52, 128, light) * 0.8 + smoothstep(8, 26, yellow) * 0.3, 0, 1)
    # Median removes stubble speckle so each field reads as one crop stage.`n    maturity = cv2.medianBlur((maturity * 255).astype(np.uint8), 5).astype(np.float32) / 255`n    maturity = cv2.GaussianBlur(maturity, (0, 0), 1.0)

    # Grass bunds sit where neighbouring fields differ but no road was found.
    gx = cv2.Sobel(light, cv2.CV_32F, 1, 0, ksize=3)
    gy = cv2.Sobel(light, cv2.CV_32F, 0, 1, ksize=3)
    bund = smoothstep(40, 90, np.hypot(gx, gy)) * (1 - road) * (1 - water)

    # Crop rows run along the dominant texture direction inside each field (structure tensor).
    sx = cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3)
    sy = cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3)
    jxx = cv2.GaussianBlur(sx * sx, (0, 0), 6)
    jyy = cv2.GaussianBlur(sy * sy, (0, 0), 6)
    jxy = cv2.GaussianBlur(sx * sy, (0, 0), 6)
    gradient = 0.5 * np.arctan2(2 * jxy, jxx - jyy)
    # Rows are perpendicular to the gradient; image y points south, world z points north.
    row = -(gradient + np.pi / 2)
    flow = np.stack([np.cos(2 * row), np.sin(2 * row)], -1)
    flow = cv2.GaussianBlur(flow, (0, 0), 4)

    size = (nw * out_scale, nh * out_scale)
    up = lambda a: cv2.resize(a, size, interpolation=cv2.INTER_CUBIC)
    cover = np.stack([up(water), up(maturity), up(road), up(bund)], -1)
    cover = np.clip(cover * 255 + 0.5, 0, 255).astype(np.uint8)
    flow_img = np.clip((cv2.resize(flow, (nw, nh)) * 0.5 + 0.5) * 255 + 0.5, 0, 255).astype(np.uint8)
    flow_img = np.dstack([flow_img, np.full((nh, nw), 128, np.uint8)])

    # OpenCV writes BGR(A); reorder so the PNG channels read R,G,B,A as documented.
    write_image(os.path.join(IMAGERY, f"landcover-{name}.png"), cv2.cvtColor(cover, cv2.COLOR_RGBA2BGRA))
    write_image(os.path.join(IMAGERY, f"landflow-{name}.png"), cv2.cvtColor(flow_img, cv2.COLOR_RGB2BGR))

    os.makedirs(REVIEW, exist_ok=True)
    preview = np.zeros((nh, nw, 3), np.float32)
    crop = np.array([0.20, 0.55, 0.25])[None, None] * (1 - maturity[..., None]) + np.array([0.80, 0.68, 0.35])[None, None] * maturity[..., None]
    preview[:] = crop
    preview = preview * (1 - water[..., None]) + np.array([0.25, 0.45, 0.55]) * water[..., None]
    preview = preview * (1 - bund[..., None]) + np.array([0.45, 0.55, 0.30]) * bund[..., None]
    preview = preview * (1 - road[..., None]) + np.array([0.88, 0.85, 0.78]) * road[..., None]
    preview = cv2.cvtColor((preview * 255).astype(np.uint8), cv2.COLOR_RGB2BGR)
    write_image(os.path.join(REVIEW, f"landcover-{name}-preview.png"), np.hstack([native, preview]))
    print(f"{name}: native {nw}x{nh} -> cover {size[0]}x{size[1]}; water {water.mean():.1%} road {road.mean():.1%} bund {bund.mean():.1%}")
    return True


def main():
    ok = classify(os.path.join(IMAGERY, "region.jpg"), "region", native_scale=4, out_scale=2)
    classify(os.path.join(IMAGERY, "context.jpg"), "context", native_scale=1, out_scale=1)
    if not ok:
        sys.exit("region.jpg is missing: run scripts/prepare-scene-imagery.py first")


if __name__ == "__main__":
    main()
