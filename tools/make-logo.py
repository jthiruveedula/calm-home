#!/usr/bin/env python3
"""Draws the Calm Home banner (320x180) and icon (192x192) as PNGs. No source images.
Amazon's launcher cannot render vector banners, so the app ships rasters.
Usage: python3 tools/make-logo.py  (run from the repo root)"""
import math
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

SS = 4
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "res", "drawable-xhdpi")
FONT = "/usr/share/fonts/truetype/lato/Lato-Light.ttf"


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def backdrop(w, h):
    img = Image.new("RGB", (w, h))
    px = img.load()
    top, bot = (22, 24, 52), (7, 8, 18)
    for y in range(h):
        for x in range(w):
            t = min(1.0, max(0.0, (y / h) * 0.7 + (x / w) * 0.3))
            px[x, y] = lerp(top, bot, t)
    glow = Image.new("RGB", (w, h), (0, 0, 0))
    d = ImageDraw.Draw(glow)
    d.ellipse([w * -0.1, h * -0.4, w * 0.6, h * 0.7], fill=(70, 60, 130))
    glow = glow.filter(ImageFilter.GaussianBlur(w * 0.12))
    return Image.blend(img, Image.composite(glow, img, glow.convert("L")), 0.55)


def moon(size, tint=(255, 244, 214)):
    s = size * SS
    layer = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    mask = Image.new("L", (s, s), 0)
    d = ImageDraw.Draw(mask)
    d.ellipse([0, 0, s - 1, s - 1], fill=255)
    cut = Image.new("L", (s, s), 0)
    ImageDraw.Draw(cut).ellipse([s * 0.30, -s * 0.06, s * 1.22, s * 0.86], fill=255)
    mask = Image.fromarray(__import__("numpy").clip(
        __import__("numpy").asarray(mask, dtype=int) - __import__("numpy").asarray(cut, dtype=int), 0, 255
    ).astype("uint8"))
    fill = Image.new("RGBA", (s, s), tint + (255,))
    layer.paste(fill, (0, 0), mask)
    return layer


def with_glow(base, layer, pos, radius, color):
    g = Image.new("RGBA", base.size, (0, 0, 0, 0))
    g.paste(Image.new("RGBA", layer.size, color + (150,)), pos, layer)
    g = g.filter(ImageFilter.GaussianBlur(radius))
    base.alpha_composite(g)
    base.alpha_composite(layer, pos)


def banner():
    w, h = 320 * SS, 180 * SS
    img = backdrop(w, h).convert("RGBA")
    m = moon(76 * SS // SS)
    mx, my = 30 * SS, (h - m.size[1]) // 2
    with_glow(img, m, (mx, my), 10 * SS, (255, 220, 150))
    d = ImageDraw.Draw(img)
    big = ImageFont.truetype(FONT, 46 * SS)
    small = ImageFont.truetype(FONT, 17 * SS)
    tx = mx + m.size[0] + 22 * SS
    d.text((tx, 52 * SS), "Calm", font=big, fill=(255, 255, 255, 255))
    d.text((tx, 100 * SS), "H O M E", font=small, fill=(255, 255, 255, 170))
    d.rounded_rectangle([1, 1, w - 2, h - 2], radius=10 * SS, outline=(255, 255, 255, 50), width=SS)
    return img.resize((320, 180), Image.LANCZOS).convert("RGB")


def icon():
    n = 192
    img = backdrop(n * SS, n * SS).convert("RGBA")
    m = moon(104)
    pos = ((n * SS - m.size[0]) // 2 - 6 * SS, (n * SS - m.size[1]) // 2)
    with_glow(img, m, pos, 12 * SS, (255, 220, 150))
    return img.resize((n, n), Image.LANCZOS).convert("RGB")


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    banner().save(os.path.join(OUT, "banner.png"), optimize=True)
    icon().save(os.path.join(OUT, "icon.png"), optimize=True)
    print("wrote banner.png, icon.png")
