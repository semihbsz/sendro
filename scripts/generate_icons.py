#!/usr/bin/env python3
"""Sendro icon pipeline.

Generates every icon asset the project needs from a single parametric SVG:

  branding/master/           master SVGs + 1024 PNGs (rounded + full-bleed)
  branding/tray/             monochrome tray glyphs (16/32, white + black)
  desktop/src-tauri/icons/   Tauri bundle icons (icon.ico, 32/128/256/512 PNG)
  ios/.../AppIcon1024.png    iOS marketing icon (opaque, no alpha)

Run from repo root:  python3 scripts/generate_icons.py
Requires: cairosvg, Pillow.
"""

from __future__ import annotations

import io
import os
from pathlib import Path

import cairosvg
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent

# ---------------------------------------------------------------------------
# The mark: the "Sendro beam" — an S-shaped flow between a source (the round
# cap, lower left) and a destination node (the bright dot, upper right).
# ---------------------------------------------------------------------------

BEAM = """
  <defs>
    <linearGradient id="beam" x1="300" y1="712" x2="756" y2="312"
                    gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="#1FB78F"/>
      <stop offset="0.62" stop-color="#37E6C4"/>
      <stop offset="1" stop-color="#6BF2D6"/>
    </linearGradient>
    <linearGradient id="bg" x1="0" y1="0" x2="0" y2="1024"
                    gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="#151A21"/>
      <stop offset="1" stop-color="#0A0C10"/>
    </linearGradient>
    <radialGradient id="ambient" cx="512" cy="330" r="620"
                    gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="#37E6C4" stop-opacity="0.10"/>
      <stop offset="0.55" stop-color="#37E6C4" stop-opacity="0.03"/>
      <stop offset="1" stop-color="#37E6C4" stop-opacity="0"/>
    </radialGradient>
    <filter id="glow" x="-40%" y="-40%" width="180%" height="180%">
      <feGaussianBlur stdDeviation="22"/>
    </filter>
  </defs>
"""

# S-curve: starts lower-left travelling right, sweeps up, exits toward the
# destination dot. One continuous stroke, round caps.
BEAM_PATH = "M 252 708 C 560 708, 452 316, 700 316"

def mark(scale_glow: bool = True) -> str:
    glow = (
        f'<path d="{BEAM_PATH}" fill="none" stroke="#37E6C4" stroke-width="92" '
        f'stroke-linecap="round" opacity="0.32" filter="url(#glow)"/>'
        f'<circle cx="806" cy="316" r="46" fill="#37E6C4" opacity="0.35" '
        f'filter="url(#glow)"/>'
    ) if scale_glow else ""
    return f"""
  {glow}
  <path d="{BEAM_PATH}" fill="none" stroke="url(#beam)" stroke-width="88"
        stroke-linecap="round"/>
  <circle cx="806" cy="316" r="42" fill="#6BF2D6"/>
"""

def svg(rounded: bool) -> str:
    radius = 228 if rounded else 0
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">
{BEAM}
  <rect x="0" y="0" width="1024" height="1024" rx="{radius}" fill="url(#bg)"/>
  <rect x="0" y="0" width="1024" height="1024" rx="{radius}" fill="url(#ambient)"/>
  <rect x="3" y="3" width="1018" height="1018" rx="{max(radius - 3, 0)}"
        fill="none" stroke="#FFFFFF" stroke-opacity="0.055" stroke-width="6"/>
{mark()}
</svg>"""

def tray_svg(color: str) -> str:
    # Bare glyph, thicker relative stroke so it survives 16 px.
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">
  <path d="M 216 748 C 604 748, 380 276, 668 276" fill="none" stroke="{color}"
        stroke-width="150" stroke-linecap="round"/>
  <circle cx="838" cy="276" r="74" fill="{color}"/>
</svg>"""

def render(svg_text: str, size: int) -> Image.Image:
    png = cairosvg.svg2png(bytestring=svg_text.encode(), output_width=size,
                           output_height=size)
    return Image.open(io.BytesIO(png)).convert("RGBA")

def save(img: Image.Image, path: Path, opaque: bool = False) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if opaque:
        img = img.convert("RGB")
    img.save(path)
    print(f"  wrote {path.relative_to(ROOT)}")

def main() -> None:
    master = ROOT / "branding" / "master"
    tray = ROOT / "branding" / "tray"
    tauri_icons = ROOT / "desktop" / "src-tauri" / "icons"
    ios_icon = (ROOT / "ios" / "Sendro" / "Assets.xcassets" /
                "AppIcon.appiconset" / "AppIcon1024.png")

    rounded_svg, fullbleed_svg = svg(rounded=True), svg(rounded=False)
    master.mkdir(parents=True, exist_ok=True)
    (master / "sendro-icon-rounded.svg").write_text(rounded_svg)
    (master / "sendro-icon-fullbleed.svg").write_text(fullbleed_svg)

    rounded_1024 = render(rounded_svg, 1024)
    fullbleed_1024 = render(fullbleed_svg, 1024)
    save(rounded_1024, master / "sendro-icon-1024-rounded.png")
    save(fullbleed_1024, master / "sendro-icon-1024-fullbleed.png", opaque=True)

    # iOS marketing icon: full-bleed, opaque (Apple rejects alpha), square —
    # iOS masks its own corners.
    save(fullbleed_1024, ios_icon, opaque=True)

    # Tauri bundle icons (rounded, transparent corners).
    for size, name in [(32, "32x32.png"), (128, "128x128.png"),
                       (256, "128x128@2x.png"), (512, "icon.png")]:
        save(render(rounded_svg, size), tauri_icons / name)

    # Windows ICO: classic multi-resolution.
    ico_sizes = [16, 24, 32, 48, 64, 128, 256]
    ico_base = render(rounded_svg, 256)
    ico_path = tauri_icons / "icon.ico"
    ico_base.save(ico_path, sizes=[(s, s) for s in ico_sizes])
    print(f"  wrote {ico_path.relative_to(ROOT)} ({ico_sizes})")

    # Tray glyphs.
    for color, label in [("#FFFFFF", "white"), ("#0B0D10", "black")]:
        t = tray_svg(color)
        for size in (16, 32):
            save(render(t, size), tray / f"tray-{label}-{size}.png")
    # Default tray icon for the desktop app (white, 32).
    save(render(tray_svg("#FFFFFF"), 32), tauri_icons / "tray.png")

    print("done.")

if __name__ == "__main__":
    os.chdir(ROOT)
    main()
