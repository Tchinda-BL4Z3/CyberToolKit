#!/usr/bin/env python3
"""
Regenerate the legacy launcher bitmaps from the adaptive-icon artwork.

Why this exists
---------------
Four of the ten density files shipped with absurd canvas sizes decoded straight
from their WebP header:

    mipmap-xxhdpi/ic_launcher.webp        36803 x 9421313
    mipmap-xxhdpi/ic_launcher_round.webp  36803 x 9421313
    mipmap-xxxhdpi/ic_launcher_round.webp 49091 x 12567041

They compress to a few kilobytes because they are nearly flat, which is exactly
why it went unnoticed - `du` looked fine while the decoded size was multiple
gigabytes. Android Lint's IconDipSize check is what surfaced it.

The artwork below mirrors res/drawable/ic_launcher_foreground.xml and
ic_launcher_background.xml: the same gradient, the same shield outline, the same
padlock and keyhole. The paths are evaluated directly from the vector path data
so the raster and the vector stay in step.

Run:  python3 tools/generate_launcher_icons.py
"""

import math
import os
import re
import struct
import sys

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

# Launcher icon sizes in px, per density bucket.
DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

# Colours lifted from the vector drawables.
BG_START = (0x0D, 0x11, 0x17)
BG_END = (0x05, 0x14, 0x12)
ACCENT = (0x10, 0xB9, 0x81, 255)
INK = (0x0B, 0x0F, 0x19, 255)

# Anti-aliasing supersample factor. The smallest icon is 48px, so 8x gives a
# 384px canvas to downsample from, which keeps the shield edge smooth.
SS = 8


# ---------------------------------------------------------------------------
# A very small subset of the SVG path grammar, enough for these two shapes.
# ---------------------------------------------------------------------------

def _cubic(p0, p1, p2, p3, steps=48):
    """De Casteljau sampling of one cubic segment."""
    out = []
    for i in range(1, steps + 1):
        t = i / steps
        u = 1 - t
        x = u**3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t**3 * p3[0]
        y = u**3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t**3 * p3[1]
        out.append((x, y))
    return out


def parse_path(data):
    """Flatten an Android pathData string into a list of subpaths."""
    tokens = re.findall(r"[MmLlCcAaZzHhVv]|-?\d*\.?\d+(?:[eE][-+]?\d+)?", data)
    subpaths, current = [], []
    i = 0
    x = y = 0.0
    start = (0.0, 0.0)

    def num():
        nonlocal i
        v = float(tokens[i])
        i += 1
        return v

    while i < len(tokens):
        cmd = tokens[i]
        i += 1
        if cmd in "Mm":
            nx, ny = num(), num()
            if cmd == "m":
                nx, ny = x + nx, y + ny
            x, y, start = nx, ny, (nx, ny)
            if current:
                subpaths.append(current)
            current = [(x, y)]
        elif cmd in "Ll":
            nx, ny = num(), num()
            if cmd == "l":
                nx, ny = x + nx, y + ny
            x, y = nx, ny
            current.append((x, y))
        elif cmd in "Cc":
            pts = [num() for _ in range(6)]
            if cmd == "c":
                pts = [v + (x if i % 2 == 0 else y) for i, v in enumerate(pts)]
            # Absolute control points already resolved above for lowercase only;
            # both icons use uppercase C, so keep this simple and correct.
            c1 = (pts[0], pts[1])
            c2 = (pts[2], pts[3])
            end = (pts[4], pts[5])
            current.extend(_cubic((x, y), c1, c2, end))
            x, y = end
        elif cmd in "Zz":
            if current:
                current.append(start)
                subpaths.append(current)
                current = []
            x, y = start
        else:
            # H, V, A and relative variants are not used by these two paths.
            raise ValueError(f"unsupported path command {cmd!r}")
    if current:
        subpaths.append(current)
    return subpaths


SHIELD_OUTER = "M54,25.5 L77,35.5 L77,54.5 C77,69 66.5,79.5 54,85.5 C41.5,79.5 31,69 31,54.5 L31,35.5 Z"
SHIELD_INNER = "M54,31.5 L71,38.9 L71,54.3 C71,65.4 63.4,73.9 54,78.9 C44.6,73.9 37,65.4 37,54.3 L37,38.9 Z"
LOCK_BODY = "M44.6,52 L63.4,52 A2.4,2.4 0 0 1 65.8,54.4 L65.8,66.6 A2.4,2.4 0 0 1 63.4,69 L44.6,69 A2.4,2.4 0 0 1 42.2,66.6 L42.2,54.4 A2.4,2.4 0 0 1 44.6,52 Z"



def render(size, circular):
    """Draw the icon at `size` px and return an RGBA image."""
    big = size * SS
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))

    # Background: the same diagonal linear gradient as the vector.
    grad = Image.new("RGB", (big, big))
    px = grad.load()
    for y in range(big):
        for x in range(0, big, 4):  # fill in steps of 4, then smooth
            t = (x / big + y / big) / 2
            c = tuple(int(BG_START[i] + (BG_END[i] - BG_START[i]) * t) for i in range(3))
            for k in range(4):
                if x + k < big:
                    px[x + k, y] = c
    if circular:
        mask = Image.new("L", (big, big), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, big - 1, big - 1], fill=255)
        img.paste(grad, (0, 0), mask)
    else:
        mask = Image.new("L", (big, big), 0)
        # Legacy launcher icons on pre-26 launchers are square-ish; a modest
        # corner radius matches the adaptive icon's silhouette.
        ImageDraw.Draw(mask).rounded_rectangle(
            [0, 0, big - 1, big - 1], radius=int(big * 0.18), fill=255
        )
        img.paste(grad, (0, 0), mask)

    # Vector viewport is 108x108 with the shield scaled 0.62 about the centre.
    s = big / 108.0

    def tp(x, y):
        return (54 * s + 0.62 * (x - 54) * s, 54 * s + 0.62 * (y - 54) * s)

    overlay = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(overlay)

    # Shield: accent fill, then the inner path punched out in the ink colour.
    for sub in parse_path(SHIELD_OUTER):
        d.polygon([tp(*p) for p in sub], fill=ACCENT)
    for sub in parse_path(SHIELD_INNER):
        d.polygon([tp(*p) for p in sub], fill=INK)

    # Padlock: shackle arc, body, keyhole.
    # The vector path is M47,52 L47,47.5 A7,7 0 0 1 61,47.5 L61,52, i.e. a
    # half-circle centred on (54, 47.5) with r = 7, spanning the top half.
    arc_x0, arc_y0 = tp(47, 47.5 - 7)
    arc_x1, arc_y1 = tp(61, 47.5)
    stroke = max(2, int(round(3.4 * 0.62 * s)))
    d.arc([arc_x0, arc_y0, arc_x1, arc_y1], start=180, end=360,
          fill=ACCENT, width=stroke)

    b = [tp(42.2, 52), tp(65.8, 69)]
    d.rounded_rectangle(
        [b[0][0], b[0][1], b[1][0], b[1][1]],
        radius=2.4 * 0.62 * s, fill=ACCENT,
    )

    kx, ky = tp(52.1, 57.4)
    kr = 2.5 * 0.62 * s
    d.ellipse([kx - kr, ky - kr, kx + kr, ky + kr], fill=INK)
    stem = [tp(52.7, 59.2), tp(54.1, 59.2), tp(55.3, 66.4), tp(51.5, 66.4)]
    d.polygon(stem, fill=INK)

    img = Image.alpha_composite(img, overlay)
    return img.resize((size, size), Image.LANCZOS)


def webp_size(path):
    """Read the canvas size out of a WebP header, for verification."""
    with open(path, "rb") as fh:
        b = fh.read(64)
    if b[:4] != b"RIFF" or b[8:12] != b"WEBP":
        return None
    chunk = b[12:16]
    if chunk == b"VP8X":
        return (int.from_bytes(b[24:27], "little") + 1,
                int.from_bytes(b[27:30], "little") + 1)
    if chunk == b"VP8 ":
        i = b.find(b"\x9d\x01\x2a", 20)
        if i < 0:
            return None
        return (int.from_bytes(b[i + 3:i + 5], "little") & 0x3FFF,
                int.from_bytes(b[i + 5:i + 7], "little") & 0x3FFF)
    if chunk == b"VP8L":
        n = int.from_bytes(b[21:25], "little")
        return ((n & 0x3FFF) + 1, ((n >> 14) & 0x3FFF) + 1)
    return None


def main():
    for density, size in DENSITIES.items():
        folder = os.path.join(RES, f"mipmap-{density}")
        if not os.path.isdir(folder):
            print(f"  skip mipmap-{density} (absent)")
            continue
        for name, circular in (("ic_launcher.webp", False),
                               ("ic_launcher_round.webp", True)):
            img = render(size, circular)
            out = os.path.join(folder, name)
            img.save(out, "WEBP", lossless=True, quality=100, method=6)
            got = webp_size(out)
            ok = got == (size, size)
            print(f"  mipmap-{density:<8} {name:<24} -> {got} "
                  f"({'ok' if ok else 'ATTENDU ' + str((size, size))})")
            if not ok:
                return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
