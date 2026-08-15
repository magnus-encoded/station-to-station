"""Render the mark's PNGs from the same geometry as the Android vector.

The Android icon is a vector and needs no raster. The App Store and the Play
Store listing both want a PNG, and those two were hand-made once and then drifted
from ic_launcher_foreground.xml. This keeps one source for the geometry, in the
108-unit viewport the vector uses, so a colour change lands everywhere at once.

    python tools/render_icon.py

Compare against android/app/src/main/res/drawable/ic_launcher_foreground.xml —
if that file changes, change this one too.
"""

from pathlib import Path

from PIL import Image, ImageDraw

GROUND = "#0E0B14"
AMBER = "#E7B24C"  # mine, and it happened
SLATE = "#6D7E9B"  # the future

VIEWPORT = 108
STROKE = 4
RADIUS = 10
SS = 8  # supersample; ImageDraw has no antialiasing of its own

ROOT = Path(__file__).resolve().parent.parent
TARGETS = [
    (ROOT / "ios/StationToStation/Assets.xcassets/AppIcon.appiconset/icon-1024.png", 1024),
    (ROOT / "docs/img/mark-512.png", 512),
]


def render(size: int) -> Image.Image:
    px = size * SS
    scale = px / VIEWPORT
    img = Image.new("RGB", (px, px), GROUND)
    d = ImageDraw.Draw(img)

    def seg(y0, y1, colour):
        # Round caps, as in the vector: a circle at each end plus the shaft.
        x, r = 54 * scale, STROKE / 2 * scale
        for y in (y0, y1):
            cy = y * scale
            d.ellipse([x - r, cy - r, x + r, cy + r], fill=colour)
        d.rectangle([x - r, y0 * scale, x + r, y1 * scale], fill=colour)

    def circle(cy, colour, filled):
        x, y, r = 54 * scale, cy * scale, RADIUS * scale
        box = [x - r, y - r, x + r, y + r]
        if filled:
            d.ellipse(box, fill=colour)
        else:
            d.ellipse(box, outline=colour, width=round(STROKE * scale))

    seg(22, 28, SLATE)  # off the top: still to come
    seg(48, 60, AMBER)
    seg(80, 86, AMBER)
    circle(38, SLATE, filled=False)
    circle(70, AMBER, filled=True)

    return img.resize((size, size), Image.LANCZOS)


if __name__ == "__main__":
    for path, size in TARGETS:
        render(size).save(path)
        print(f"{path.relative_to(ROOT)}  {size}x{size}")
