import math
import os
import subprocess

def polar(cx, cy, r, deg):
    rad = math.radians(deg)
    return cx + r * math.cos(rad), cy + r * math.sin(rad)

# ----------------------------------------------------------------------
# 1. Base Geometry for Concept A (Hero: Velocity Iris)
# ----------------------------------------------------------------------
def get_symbol_paths(color_blade="#FF5E1E", color_core="#0F172A"):
    cx, cy = 128, 128
    paths = []
    for rot in [0, 120, 240]:
        rad = math.radians
        p1x, p1y = 128 + 104 * math.cos(rad(rot - 20)), 128 + 104 * math.sin(rad(rot - 20))
        p2x, p2y = 128 + 104 * math.cos(rad(rot + 70)), 128 + 104 * math.sin(rad(rot + 70))
        p3x, p3y = 128 + 48 * math.cos(rad(rot + 50)), 128 + 48 * math.sin(rad(rot + 50))
        p4x, p4y = 128 + 48 * math.cos(rad(rot - 5)), 128 + 48 * math.sin(rad(rot - 5))
        d = (f"M {p1x:.1f} {p1y:.1f} "
             f"A 104 104 0 0 1 {p2x:.1f} {p2y:.1f} "
             f"A 64 64 0 0 0 {p3x:.1f} {p3y:.1f} "
             f"A 48 48 0 0 0 {p4x:.1f} {p4y:.1f} "
             f"A 64 64 0 0 1 {p1x:.1f} {p1y:.1f} Z")
        paths.append(f'  <path fill="{color_blade}" d="{d}"/>')
        
    aperture = (f"M {cx} {cy - 32} A 32 32 0 1 1 {cx - 0.1} {cy - 32} Z "
                f"M {cx} {cy - 16} A 16 16 0 1 0 {cx - 0.1} {cy - 16} Z")
    paths.append(f'  <path fill="{color_core}" fill-rule="evenodd" d="{aperture}"/>')
    return "\n".join(paths)

# ----------------------------------------------------------------------
# 2. Outlined Wordmark with two-tone coloring (SPEED in c1, CAM in c2)
# ----------------------------------------------------------------------
def get_wordmark_paths(start_x=300, base_y=86, fill_speed="#0F172A", fill_cam="#FF5E1E"):
    paths = []
    sx = start_x
    sy = base_y
    
    # S
    s_path = (
        f"M {sx+46} {sy+24} L {sx+32} {sy+24} "
        f"C {sx+32} {sy+16} {sx+28} {sy+13} {sx+23} {sy+13} "
        f"C {sx+17} {sy+13} {sx+14} {sy+16} {sx+14} {sy+22} "
        f"C {sx+14} {sy+28} {sx+18} {sy+32} {sx+28} {sy+36} "
        f"C {sx+42} {sy+42} {sx+48} {sy+49} {sx+48} {sy+62} "
        f"C {sx+48} {sy+76} {sx+36} {sy+84} {sx+23} {sy+84} "
        f"C {sx+9} {sy+84} {sx} {sy+75} {sx} {sy+58} L {sx+14} {sy+58} "
        f"C {sx+14} {sy+68} {sx+18} {sy+71} {sx+23} {sy+71} "
        f"C {sx+29} {sy+71} {sx+34} {sy+67} {sx+34} {sy+61} "
        f"C {sx+34} {sy+55} {sx+30} {sy+51} {sx+20} {sy+47} "
        f"C {sx+7} {sy+41} {sx} {sy+34} {sx} {sy+22} "
        f"C {sx} {sy+9} {sx+11} {sy} {sx+24} {sy} "
        f"C {sx+37} {sy} {sx+46} {sy+8} {sx+46} {sy+24} Z"
    )
    paths.append(f'    <path fill="{fill_speed}" d="{s_path}"/>')
    
    # P
    px = start_x + 60
    p_path = (
        f"M {px} {sy} L {px+32} {sy} "
        f"C {px+44} {sy} {px+50} {sy+8} {px+50} {sy+24} "
        f"C {px+50} {sy+40} {px+44} {sy+48} {px+32} {sy+48} "
        f"L {px+14} {sy+48} L {px+14} {sy+84} L {px} {sy+84} Z "
        f"M {px+14} {sy+13} L {px+14} {sy+35} L {px+30} {sy+35} "
        f"C {px+35} {sy+35} {px+36} {sy+31} {px+36} {sy+24} "
        f"C {px+36} {sy+17} {px+35} {sy+13} {px+30} {sy+13} Z"
    )
    paths.append(f'    <path fill="{fill_speed}" fill-rule="evenodd" d="{p_path}"/>')
    
    # E1
    e1x = start_x + 124
    e1_path = (
        f"M {e1x} {sy} L {e1x+42} {sy} L {e1x+42} {sy+13} L {e1x+14} {sy+13} "
        f"L {e1x+14} {sy+35} L {e1x+38} {sy+35} L {e1x+38} {sy+48} L {e1x+14} {sy+48} "
        f"L {e1x+14} {sy+71} L {e1x+42} {sy+71} L {e1x+42} {sy+84} L {e1x} {sy+84} Z"
    )
    paths.append(f'    <path fill="{fill_speed}" d="{e1_path}"/>')
    
    # E2
    e2x = start_x + 180
    e2_path = (
        f"M {e2x} {sy} L {e2x+42} {sy} L {e2x+42} {sy+13} L {e2x+14} {sy+13} "
        f"L {e2x+14} {sy+35} L {e2x+38} {sy+35} L {e2x+38} {sy+48} L {e2x+14} {sy+48} "
        f"L {e2x+14} {sy+71} L {e2x+42} {sy+71} L {e2x+42} {sy+84} L {e2x} {sy+84} Z"
    )
    paths.append(f'    <path fill="{fill_speed}" d="{e2_path}"/>')
    
    # D
    dx = start_x + 236
    d_path = (
        f"M {dx} {sy} L {dx+28} {sy} "
        f"C {dx+44} {sy} {dx+52} {sy+12} {dx+52} {sy+42} "
        f"C {dx+52} {sy+72} {dx+44} {sy+84} {dx+28} {sy+84} "
        f"L {dx} {sy+84} Z "
        f"M {dx+14} {sy+13} L {dx+14} {sy+71} L {dx+27} {sy+71} "
        f"C {dx+36} {sy+71} {dx+38} {sy+61} {dx+38} {sy+42} "
        f"C {dx+38} {sy+23} {dx+36} {sy+13} {dx+27} {sy+13} Z"
    )
    paths.append(f'    <path fill="{fill_speed}" fill-rule="evenodd" d="{d_path}"/>')
    
    # C (CAM)
    cx_letter = start_x + 304
    c_path_clean = (
        f"M {cx_letter+46} {sy+24} L {cx_letter+33} {sy+30} "
        f"C {cx_letter+30} {sy+19} {cx_letter+25} {sy+13} {cx_letter+18} {sy+13} "
        f"C {cx_letter+8} {sy+13} {cx_letter} {sy+26} {cx_letter} {sy+42} "
        f"C {cx_letter} {sy+58} {cx_letter+8} {sy+71} {cx_letter+18} {sy+71} "
        f"C {cx_letter+25} {sy+71} {cx_letter+30} {sy+65} {cx_letter+33} {sy+54} "
        f"L {cx_letter+46} {sy+60} "
        f"C {cx_letter+40} {sy+75} {cx_letter+30} {sy+84} {cx_letter+18} {sy+84} "
        f"C {cx_letter+4} {sy+84} {cx_letter} {sy+68} {cx_letter} {sy+42} "
        f"C {cx_letter} {sy+16} {cx_letter+4} {sy} {cx_letter+18} {sy} "
        f"C {cx_letter+30} {sy} {cx_letter+40} {sy+9} {cx_letter+46} {sy+24} Z"
    )
    paths.append(f'    <path fill="{fill_cam}" d="{c_path_clean}"/>')
    
    # A (CAM)
    ax = start_x + 368
    a_path = (
        f"M {ax+25.0:.2f} {sy} "
        f"L {ax+47.51:.2f} {sy+84} L {ax+33.51:.2f} {sy+84} "
        f"L {ax+28.51:.2f} {sy+65.34} L {ax+21.49:.2f} {sy+65.34} "
        f"L {ax+16.49:.2f} {sy+84} L {ax+2.49:.2f} {sy+84} Z "
        f"M {ax+25.0:.2f} {sy+26} L {ax+21.49:.2f} {sy+52} L {ax+28.51:.2f} {sy+52} Z"
    )
    paths.append(f'    <path fill="{fill_cam}" fill-rule="evenodd" d="{a_path}"/>')
    
    # M (CAM)
    mx = start_x + 432
    m_path = (
        f"M {mx} {sy} L {mx+14} {sy} L {mx+29.0:.2f} {sy+56} L {mx+44.01:.2f} {sy} L {mx+58} {sy} "
        f"L {mx+58} {sy+84} L {mx+44} {sy+84} L {mx+44} {sy+32} L {mx+33.0:.2f} {sy+73.08} "
        f"L {mx+25.0:.2f} {sy+73.08} L {mx+14} {sy+32} L {mx+14} {sy+84} L {mx} {sy+84} Z"
    )
    paths.append(f'    <path fill="{fill_cam}" d="{m_path}"/>')
    
    return "\n".join(paths)

out_dir = "logo/speedcam"
os.makedirs(out_dir, exist_ok=True)

# ----------------------------------------------------------------------
# 3. Generate Primary Masters
# ----------------------------------------------------------------------
# A. Symbol Color (Blaze Orange blades + Slate pupil)
sym_color = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256" width="256" height="256" role="img" aria-labelledby="title-sym-color">
  <title id="title-sym-color">SpeedCam Symbol (Color)</title>
{get_symbol_paths("#FF5E1E", "#0F172A")}
</svg>'''
with open(f"{out_dir}/speedcam-symbol-color.svg", "w") as f: f.write(sym_color)

# B. Symbol Dark (Blaze Orange blades + Pure White pupil on #0F172A)
sym_dark = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256" width="256" height="256" role="img" aria-labelledby="title-sym-dark">
  <title id="title-sym-dark">SpeedCam Symbol (Dark Background)</title>
  <rect width="256" height="256" rx="48" fill="#0F172A"/>
{get_symbol_paths("#FF5E1E", "#FFFFFF")}
</svg>'''
with open(f"{out_dir}/speedcam-symbol-dark.svg", "w") as f: f.write(sym_dark)

# C. Symbol Mono / Black master (for export_variants tool)
sym_master = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256" width="256" height="256" role="img" aria-labelledby="title-sym-master">
  <title id="title-sym-master">SpeedCam Symbol</title>
{get_symbol_paths("#0F172A", "#0F172A")}
</svg>'''
with open(f"{out_dir}/speedcam-symbol.svg", "w") as f: f.write(sym_master)

# D. Horizontal Lockup Color (Light Background)
lockup_color = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 820 256" width="820" height="256" role="img" aria-labelledby="title-lockup-color">
  <title id="title-lockup-color">SpeedCam Logo (Color Lockup)</title>
  <g id="symbol" transform="translate(48, 32) scale(0.75)">
{get_symbol_paths("#FF5E1E", "#0F172A")}
  </g>
  <g id="wordmark">
{get_wordmark_paths(start_x=280, base_y=86, fill_speed="#0F172A", fill_cam="#FF5E1E")}
  </g>
</svg>'''
with open(f"{out_dir}/speedcam-lockup-color.svg", "w") as f: f.write(lockup_color)

# E. Horizontal Lockup Dark Mode
lockup_dark = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 820 256" width="820" height="256" role="img" aria-labelledby="title-lockup-dark">
  <title id="title-lockup-dark">SpeedCam Logo (Dark Lockup)</title>
  <rect width="820" height="256" rx="28" fill="#0F172A"/>
  <g id="symbol" transform="translate(48, 32) scale(0.75)">
{get_symbol_paths("#FF5E1E", "#FFFFFF")}
  </g>
  <g id="wordmark">
{get_wordmark_paths(start_x=280, base_y=86, fill_speed="#FFFFFF", fill_cam="#FF5E1E")}
  </g>
</svg>'''
with open(f"{out_dir}/speedcam-lockup-dark.svg", "w") as f: f.write(lockup_dark)

# F. Horizontal Lockup Monochrome Master (for export_variants tool)
lockup_mono = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 820 256" width="820" height="256" role="img" aria-labelledby="title-lockup-mono">
  <title id="title-lockup-mono">SpeedCam Logo (Master Monochrome)</title>
  <g id="symbol" transform="translate(48, 32) scale(0.75)">
{get_symbol_paths("#0F172A", "#0F172A")}
  </g>
  <g id="wordmark">
{get_wordmark_paths(start_x=280, base_y=86, fill_speed="#0F172A", fill_cam="#0F172A")}
  </g>
</svg>'''
with open(f"{out_dir}/speedcam-lockup-mono.svg", "w") as f: f.write(lockup_mono)

# G. Stacked Lockup Color (Light Background)
stacked_color = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 440" width="512" height="440" role="img" aria-labelledby="title-stacked-color">
  <title id="title-stacked-color">SpeedCam Logo (Stacked Color)</title>
  <g id="symbol" transform="translate(160, 36) scale(0.75)">
{get_symbol_paths("#FF5E1E", "#0F172A")}
  </g>
  <g id="wordmark" transform="translate(74, 180) scale(0.74)">
{get_wordmark_paths(start_x=0, base_y=86, fill_speed="#0F172A", fill_cam="#FF5E1E")}
  </g>
</svg>'''
with open(f"{out_dir}/speedcam-stacked-color.svg", "w") as f: f.write(stacked_color)

# H. Stacked Lockup Dark
stacked_dark = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 440" width="512" height="440" role="img" aria-labelledby="title-stacked-dark">
  <title id="title-stacked-dark">SpeedCam Logo (Stacked Dark)</title>
  <rect width="512" height="440" rx="36" fill="#0F172A"/>
  <g id="symbol" transform="translate(160, 36) scale(0.75)">
{get_symbol_paths("#FF5E1E", "#FFFFFF")}
  </g>
  <g id="wordmark" transform="translate(74, 180) scale(0.74)">
{get_wordmark_paths(start_x=0, base_y=86, fill_speed="#FFFFFF", fill_cam="#FF5E1E")}
  </g>
</svg>'''
with open(f"{out_dir}/speedcam-stacked-dark.svg", "w") as f: f.write(stacked_dark)

print("Primary masters written successfully.")
