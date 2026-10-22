import math
import os

def make_concept_a_symbol():
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
        paths.append(f'  <path fill="#0F172A" d="{d}"/>')
        
    aperture = (f"M {cx} {cy - 32} A 32 32 0 1 1 {cx - 0.1} {cy - 32} Z "
                f"M {cx} {cy - 16} A 16 16 0 1 0 {cx - 0.1} {cy - 16} Z")
    paths.append(f'  <path fill="#0F172A" fill-rule="evenodd" d="{aperture}"/>')
    
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256" width="256" height="256" role="img" aria-labelledby="title-a">\n'
        '  <title id="title-a">SpeedCam Concept A - Velocity Iris</title>\n'
        + "\n".join(paths) + "\n</svg>"
    )

def make_concept_b_symbol():
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256" width="256" height="256" role="img" aria-labelledby="title-b">\n'
        '  <title id="title-b">SpeedCam Concept B - Kinetic S-Radar</title>\n'
        '  <!-- Upper Radar Arc / S-Crest -->\n'
        '  <path fill="#0F172A" d="M 198 60 C 182 38 157 24 128 24 C 80 24 42 62 42 110 C 42 122 45 133 56 142 L 84 114 C 79 108 76 102 76 96 C 76 74 99 56 128 56 C 146 56 162 66 172 86 L 198 60 Z"/>\n'
        '  <!-- Lower Radar Arc / S-Base -->\n'
        '  <path fill="#0F172A" d="M 58 196 C 74 218 99 232 128 232 C 176 232 214 194 214 146 C 214 134 211 123 200 114 L 172 142 C 177 148 180 154 180 160 C 180 182 157 200 128 200 C 110 200 94 190 84 170 L 58 196 Z"/>\n'
        '  <!-- Center Camera Aperture / Radar Reticle -->\n'
        '  <path fill="#0F172A" fill-rule="evenodd" d="M 128 86 A 42 42 0 1 1 127.9 86 Z M 128 106 A 22 22 0 1 0 128.1 106 Z"/>\n'
        '  <!-- Center Focal Sensor / Speed Core -->\n'
        '  <circle cx="128" cy="128" r="11" fill="#0F172A"/>\n'
        '</svg>'
    )

def make_concept_c_symbol():
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256" width="256" height="256" role="img" aria-labelledby="title-c">\n'
        '  <title id="title-c">SpeedCam Concept C - HUD Reticle</title>\n'
        '  <!-- Top-Left Focus Bracket -->\n'
        '  <path fill="#0F172A" d="M 40 92 L 40 60 C 40 48.9 48.9 40 60 40 L 92 40 L 92 56 L 64 56 C 59.6 56 56 59.6 56 64 L 56 92 Z"/>\n'
        '  <!-- Top-Right Focus Bracket -->\n'
        '  <path fill="#0F172A" d="M 216 92 L 216 60 C 216 48.9 207.1 40 196 40 L 164 40 L 164 56 L 192 56 C 196.4 56 200 59.6 200 64 L 200 92 Z"/>\n'
        '  <!-- Bottom-Left Focus Bracket -->\n'
        '  <path fill="#0F172A" d="M 40 164 L 40 196 C 40 207.1 48.9 216 60 216 L 92 216 L 92 200 L 64 200 C 59.6 200 56 196.4 56 192 L 56 164 Z"/>\n'
        '  <!-- Bottom-Right Focus Bracket -->\n'
        '  <path fill="#0F172A" d="M 216 164 L 216 196 C 216 207.1 207.1 216 196 216 L 164 216 L 164 200 L 192 200 C 196.4 200 200 196.4 200 192 L 200 164 Z"/>\n'
        '  <!-- Primary Velocity Chevron (45-degree forward thrust) -->\n'
        '  <path fill="#0F172A" d="M 128 68 L 184 124 L 160 124 L 128 92 L 96 124 L 72 124 Z"/>\n'
        '  <!-- Secondary Proximity Chevron -->\n'
        '  <path fill="#0F172A" d="M 128 116 L 172 160 L 152 160 L 128 136 L 104 160 L 84 160 Z"/>\n'
        '  <!-- Sensor Beacon -->\n'
        '  <circle cx="128" cy="180" r="14" fill="#0F172A"/>\n'
        '</svg>'
    )

def get_wordmark_paths(start_x=300, base_y=86, fill="#0F172A"):
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
    paths.append(f'    <path fill="{fill}" d="{s_path}"/>')
    
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
    paths.append(f'    <path fill="{fill}" fill-rule="evenodd" d="{p_path}"/>')
    
    # E1
    e1x = start_x + 124
    e1_path = (
        f"M {e1x} {sy} L {e1x+42} {sy} L {e1x+42} {sy+13} L {e1x+14} {sy+13} "
        f"L {e1x+14} {sy+35} L {e1x+38} {sy+35} L {e1x+38} {sy+48} L {e1x+14} {sy+48} "
        f"L {e1x+14} {sy+71} L {e1x+42} {sy+71} L {e1x+42} {sy+84} L {e1x} {sy+84} Z"
    )
    paths.append(f'    <path fill="{fill}" d="{e1_path}"/>')
    
    # E2
    e2x = start_x + 180
    e2_path = (
        f"M {e2x} {sy} L {e2x+42} {sy} L {e2x+42} {sy+13} L {e1x+14+56} {sy+13} "
        f"L {e1x+14+56} {sy+35} L {e1x+38+56} {sy+35} L {e1x+38+56} {sy+48} L {e1x+14+56} {sy+48} "
        f"L {e1x+14+56} {sy+71} L {e2x+42} {sy+71} L {e2x+42} {sy+84} L {e2x} {sy+84} Z"
    )
    paths.append(f'    <path fill="{fill}" d="{e2_path}"/>')
    
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
    paths.append(f'    <path fill="{fill}" fill-rule="evenodd" d="{d_path}"/>')
    
    # C
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
    paths.append(f'    <path fill="{fill}" d="{c_path_clean}"/>')
    
    # A (rounded to 2 decimals)
    ax = start_x + 368
    a_path = (
        f"M {ax+25.0:.2f} {sy} "
        f"L {ax+47.51:.2f} {sy+84} L {ax+33.51:.2f} {sy+84} "
        f"L {ax+28.51:.2f} {sy+65.34} L {ax+21.49:.2f} {sy+65.34} "
        f"L {ax+16.49:.2f} {sy+84} L {ax+2.49:.2f} {sy+84} Z "
        f"M {ax+25.0:.2f} {sy+26} L {ax+21.49:.2f} {sy+52} L {ax+28.51:.2f} {sy+52} Z"
    )
    paths.append(f'    <path fill="{fill}" fill-rule="evenodd" d="{a_path}"/>')
    
    # M (rounded to 2 decimals)
    mx = start_x + 432
    m_path = (
        f"M {mx} {sy} L {mx+14} {sy} L {mx+29.0:.2f} {sy+56} L {mx+44.01:.2f} {sy} L {mx+58} {sy} "
        f"L {mx+58} {sy+84} L {mx+44} {sy+84} L {mx+44} {sy+32} L {mx+33.0:.2f} {sy+73.08} "
        f"L {mx+25.0:.2f} {sy+73.08} L {mx+14} {sy+32} L {mx+14} {sy+84} L {mx} {sy+84} Z"
    )
    paths.append(f'    <path fill="{fill}" d="{m_path}"/>')
    
    return "\n".join(paths)

def make_lockup(symbol_svg_body, title, sym_offset_x=48, fill="#0F172A"):
    body = symbol_svg_body.split("<svg")[1]
    body = body[body.find(">")+1:body.rfind("</svg>")].strip()
    if "<title" in body:
        end_t = body.find("</title>") + 8
        body = body[end_t:].strip()
        
    wordmark = get_wordmark_paths(start_x=280, base_y=86, fill=fill)
    
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 820 256" width="820" height="256" role="img" aria-labelledby="lockup-title">\n'
        f'  <title id="lockup-title">{title}</title>\n'
        f'  <g id="symbol" transform="translate({sym_offset_x}, 32) scale(0.75)">\n'
        f'    {body}\n'
        f'  </g>\n'
        f'  <g id="wordmark">\n'
        f'{wordmark}\n'
        f'  </g>\n'
        f'</svg>'
    )

sym_a = make_concept_a_symbol()
sym_b = make_concept_b_symbol()
sym_c = make_concept_c_symbol()

with open("logo/speedcam/concept-a-symbol.svg", "w") as f: f.write(sym_a)
with open("logo/speedcam/concept-b-symbol.svg", "w") as f: f.write(sym_b)
with open("logo/speedcam/concept-c-symbol.svg", "w") as f: f.write(sym_c)

# Sym A width = 208 * 0.75 = 156. Center offset = 48 -> left margin 66
# Sym B width = 172 * 0.75 = 129. Center offset = 60 -> left margin 66
# Sym C width = 176 * 0.75 = 132. Center offset = 58 -> left margin 66
# Right margin of wordmark is at 820 - (280 + 490) = 50.
with open("logo/speedcam/concept-a-lockup.svg", "w") as f: f.write(make_lockup(sym_a, "SpeedCam Lockup A", 48))
with open("logo/speedcam/concept-b-lockup.svg", "w") as f: f.write(make_lockup(sym_b, "SpeedCam Lockup B", 60))
with open("logo/speedcam/concept-c-lockup.svg", "w") as f: f.write(make_lockup(sym_c, "SpeedCam Lockup C", 58))

print("Lockups and symbols re-written.")
