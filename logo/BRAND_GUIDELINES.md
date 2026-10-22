# SpeedCam — Brand & Logo Guidelines

## 1. Concept & Rationale
**SpeedCam** is an automotive driver-assistance and road-safety application providing precision GPS proximity warnings for speed cameras, radar zones, average speed corridors, and Head-Up Display (HUD) speed awareness.

- **Primary Mark — "Velocity Iris"**:
  An interlocking 3-blade aerodynamic aperture shutter rotating around a precision central camera sensor pupil. It merges the optical nature of camera detection with the rotational dynamism of a high-speed vehicle turbine / tachometer gauge.
- **Production Craft**:
  Pure geometric construction on a 256×256 viewBox, 100/100 production readiness score, zero near-miss angles, mathematically exact 15°/45°/75° alignment, and true compound vector paths (`fill-rule="evenodd"`).

---

## 2. Brand Color Palette

| Role | Color Name | HEX | RGB | CMYK | Pantone Match |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Primary Accent** | Blaze Orange | `#FF5E1E` | `rgb(255, 94, 30)` | `0, 78, 100, 0` | PMS Orange 021 C |
| **Primary Dark** | Carbon Slate | `#0F172A` | `rgb(15, 23, 42)` | `85, 75, 50, 65` | PMS Black 6 C |
| **Clean Neutral** | Pure White | `#FFFFFF` | `rgb(255, 255, 255)` | `0, 0, 0, 0` | White |
| **Secondary Accent** | Radar Cyan | `#00E5FF` | `rgb(0, 229, 255)` | `60, 0, 5, 0` | PMS 305 C |

---

## 3. Clear Space & Minimum Sizing
- **Clear Space Rule**: Maintain an exclusion zone of at least **1× $X$** around all sides of the mark, where $X$ equals the diameter of the central pupil ($32\text{ px}$ on the $256\times256$ grid).
- **Minimum Digital Sizes**:
  - Full Horizontal Lockup: Minimum width **$120\text{ px}$** (Screen).
  - Standalone Symbol: Minimum **$16\text{ px}$** (Favicon / Android Status Bar).
- **Minimum Print Sizes**:
  - Horizontal Lockup: **$25\text{ mm}$**.
  - Symbol: **$6\text{ mm}$**.

---

## 4. Typography
- **Wordmark Letterforms**: Custom engineered geometric sans-serif constructed as vector paths (no font installation dependency).
- **Proportions**:
  - Stems: 14 units.
  - Cap Height: 84 units.
  - Angular Terminals & Diagonals: Snap to deliberate 45° and 75° angles.
- **Hierarchy Pairing**:
  - Display / App Headers: *Inter Tight Bold*, *Chakra Petch*, or *Rajdhani SemiBold*.
  - UI / Body: *Inter*, *Roboto Flex*, or System UI Sans.

---

## 5. Asset Catalog

```
logo/speedcam/
├── speedcam-symbol-color.svg      # Primary color symbol (Blaze Orange + Carbon Slate)
├── speedcam-symbol-dark.svg       # Dark container symbol (app icon / avatar ready)
├── speedcam-symbol.svg            # Production master monochrome
├── speedcam-lockup-color.svg      # Primary horizontal lockup (Light background)
├── speedcam-lockup-dark.svg       # Dark mode horizontal lockup (#0F172A background)
├── speedcam-lockup-mono.svg       # Flat monochrome lockup
├── speedcam-stacked-color.svg     # Stacked lockup for badges, splash screens, posters
├── speedcam-stacked-dark.svg      # Stacked lockup on dark background
├── concepts.png                   # 3-concept review overview sheet
├── test-preview.html              # Full interactive HTML test sheet (ladder, invert, squint)
├── presentation.html              # 5-slide client presentation board with mockups
├── presentation_slides/           # Presentation slides rendered to high-res PNGs
│   ├── slide-01.png ... slide-05.png
└── variants/                      # Automated production delivery variants
    ├── speedcam-symbol-black.svg
    ├── speedcam-symbol-white.svg
    ├── speedcam-symbol-mono-ff5e1e.svg
    ├── speedcam-symbol-square.svg
    ├── speedcam-symbol-favicon.svg
    ├── speedcam-symbol-app-icon.svg
    ├── speedcam-symbol-app-icon-*.png (32, 64, 128, 256, 512, 1024)
    ├── speedcam-symbol-black-*.png
    ├── speedcam-symbol-white-*.png
    ├── speedcam-symbol-mono-ff5e1e-*.png
    ├── speedcam-lockup-mono-black-*.png (640, 1280)
    ├── speedcam-lockup-mono-white-*.png (640, 1280)
    ├── speedcam-lockup-mono-mono-ff5e1e-*.png (640, 1280)
    ├── speedcam-lockup-color.png (1280x400)
    ├── speedcam-lockup-dark.png (1280x400)
    ├── speedcam-stacked-color.png (1024x880)
    ├── speedcam-stacked-dark.png (1024x880)
    ├── favicon.ico
    ├── favicon.svg
    ├── apple-touch-icon.png
    ├── icon-192.png, icon-512.png, maskable-512.png
    ├── site.webmanifest
    └── head-snippet.html
```

---

## 6. Rules & Misuse
1. **Do not distort or stretch** the proportions of the symbol or wordmark.
2. **Do not use arbitrary colors** outside the approved brand palette.
3. **Do not add drop shadows, outer glows, bevels, or photographic filters**.
4. **Do not replace the custom vector wordmark with typed generic system fonts**.
5. **Always maintain minimum contrast** (Carbon Slate on light, Pure White on dark).
