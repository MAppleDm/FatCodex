"""Builds the launcher icon layers from the source picture (a glass apple on a cream card).

    python android/tools/make_icon.py docs/icon-source.webp

Writes res/mipmap-*/ic_launcher_foreground.png (the card's inside, every bit of the apple, leaf tip included,
within a 32dp radius of the 108dp adaptive-icon canvas's centre, so a round mask keeps it whole; the card's rounded
corners faded out) and ic_launcher_monochrome.png (the apple's
shape only, for Android 13+ themed icons).
"""

import sys
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter

RES = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res"
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

# where things are on the source picture (1254 x 1254)
CARD = (150, 135, 1105, 1095)       # the card's flat inside, rounded corners included
RADIUS = 32                         # dp: the round mask shows 36, a little air around the apple
TOP, BOTTOM = (253, 248, 240), (245, 238, 225)   # the card's colour, top and bottom


def main(source: str) -> None:
    src = Image.open(source).convert("RGB")
    r, g, _ = src.split()
    green = ImageChops.subtract(g, r).point(lambda v: 255 if v > 12 else 0)
    ax0, ay0, ax1, ay1 = green.getbbox()
    cx, cy = (ax0 + ax1) / 2, (ay0 + ay1) / 2
    # the farthest green pixel from the centre (the leaf tip) decides the scale
    small = green.resize((green.width // 4, green.height // 4))
    far = max(
        ((x * 4 - cx) ** 2 + (y * 4 - cy) ** 2) ** 0.5
        for y in range(small.height) for x in range(small.width) if small.getpixel((x, y)) > 128
    )
    side = round(far * 108 / RADIUS)

    # the card's colour, as a vertical gradient, under everything
    canvas = Image.new("RGB", (side, side))
    draw = ImageDraw.Draw(canvas)
    for y in range(side):
        t = y / (side - 1)
        draw.line([(0, y), (side, y)], fill=tuple(round(a + (b - a) * t) for a, b in zip(TOP, BOTTOM)))

    # the card's inside, faded out at its edges so its corners and shadow do not show
    card = src.crop(CARD)
    mask = Image.new("L", card.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([60, 60, card.width - 60, card.height - 60], radius=150, fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(30))
    at = (round(side / 2 - (cx - CARD[0])), round(side / 2 - (cy - CARD[1])))
    canvas.paste(card, at, mask)

    # themed icons: the apple's shape in white
    shape = Image.new("L", (side, side), 0)
    # the glass makes the green patchy: close the gaps, then smooth the edge
    solid = green.crop(CARD).filter(ImageFilter.MaxFilter(17)).filter(ImageFilter.MinFilter(15))
    solid = solid.filter(ImageFilter.GaussianBlur(4)).point(lambda v: 255 if v > 128 else 0).filter(ImageFilter.GaussianBlur(1.5))
    shape.paste(solid, at)
    mono = Image.new("RGBA", (side, side), (255, 255, 255, 0))
    mono.putalpha(shape)

    for name, scale in DENSITIES.items():
        px = round(108 * scale)
        out = RES / f"mipmap-{name}"
        out.mkdir(exist_ok=True)
        canvas.resize((px, px), Image.LANCZOS).save(out / "ic_launcher_foreground.png", optimize=True)
        mono.resize((px, px), Image.LANCZOS).save(out / "ic_launcher_monochrome.png", optimize=True)
    print(f"apple {ax1 - ax0}x{ay1 - ay0} px, farthest point {far:.0f} px from its centre, on a {side} px canvas")


if __name__ == "__main__":
    main(sys.argv[1])
