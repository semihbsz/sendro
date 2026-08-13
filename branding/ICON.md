# The Sendro Icon

## Concept — "the beam"

The mark is a single flowing S-curve that travels from a source point
(lower left — your PC, where the stroke begins with a full round cap) to a
separated bright node (upper right — your iPhone, the destination). The gap
between the end of the stream and the destination dot is deliberate: the
file is *in flight*, almost there. The curve doubles as a stylized **S**
for Sendro.

One shape, one accent color, dark premium base. No text, no cloud, no
arrows borrowed from anyone else's transfer app. The silhouette survives at
16 px because it is just two elements: a thick ribbon and a dot.

## System

- **Base**: layered near-black (`#151A21 → #0A0C10` vertical gradient) with
  a faint teal ambient light behind the mark and a 1 px inner hairline
  (`white @ 5.5%`) for edge definition on dark backgrounds.
- **Beam**: 88 px stroke (at 1024), round caps, gradient along the flow
  `#1FB78F → #37E6C4 → #6BF2D6` — energy increases as the file approaches
  the destination. A soft 32%-opacity blurred duplicate provides restrained
  glow (premium, not neon).
- **Destination node**: `#6BF2D6`, the brightest element — the payoff.

The accent `#37E6C4` is the same "Sendro beam" color used for primary
actions and progress bars in both apps, so brand and UI share one voice.

## Variants

| file | use |
|---|---|
| `master/sendro-icon-rounded.svg` / `-1024-rounded.png` | Windows app, installer, marketing (transparent rounded corners) |
| `master/sendro-icon-fullbleed.svg` / `-1024-fullbleed.png` | iOS (square, opaque, no alpha — iOS masks its own corners) |
| `../desktop/src-tauri/icons/icon.ico` | Windows executable/installer (16–256) |
| `../desktop/src-tauri/icons/*.png` | Tauri bundle set (32/128/256/512) |
| `tray/tray-white-*.png`, `tray/tray-black-*.png` | System tray glyph — bare beam + dot, heavier stroke, monochrome so it reads at 16 px on any taskbar |
| `../ios/Sendro/Assets.xcassets/AppIcon.appiconset/AppIcon1024.png` | iOS single-size app icon (Xcode generates all sizes) |

## Regenerating

Everything is generated from one parametric SVG:

```
pip install cairosvg pillow
python3 scripts/generate_icons.py
```

Edit the geometry/colors in `scripts/generate_icons.py`; never hand-edit
the PNGs.
