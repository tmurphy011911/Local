#!/usr/bin/env python3
"""
Generate seasonal Bo artwork for every platform from ONE definition.

Outputs (all overwritten):
  composeApp/src/commonMain/kotlin/live/localapp/local/ui/character/BoSeasonArt.kt
      Shape data drawn at runtime by Bo()/BoCharacter via BoSeason's hooks.
  composeApp/src/commonMain/composeResources/drawable/bo_tiny_<key>.xml
      Static tiny Bo (chat bubbles, paywall, version gate, splash greeting).
  iosApp/iosApp/Assets.xcassets/{BoTiny,BoDefaultClean}<Suffix>.imageset/
      Native SwiftUI screens (picked by BoArt via BoSeason.assetSuffix).

Covers the data-driven seasons only (New Year's, Winter, St. Patrick's, Easter,
Fourth of July). Halloween / Thanksgiving / Christmas / Valentine's are drawn by
hand in BoSeason.kt and have hand-made static assets.

All coordinates are in Bo's 100×100 body viewBox. Layers:
  behind      — drawn before the body fill (sits behind Bo)
  front       — drawn after the face (always)
  frontNoHat  — drawn after the face, skipped on loyalty levels that wear a hat

Usage:  python3 scripts/gen_bo_season_art.py   (from the repo root)
"""
import json
import math
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
INK = "#1A1523"


# ─── primitives → path data ─────────────────────────────────────────────────
def n(x):
    return ("%.2f" % x).rstrip("0").rstrip(".")


def ellipse(cx, cy, rx, ry):
    return (f"M{n(cx - rx)} {n(cy)} a{n(rx)} {n(ry)} 0 1 0 {n(2 * rx)} 0 "
            f"a{n(rx)} {n(ry)} 0 1 0 {n(-2 * rx)} 0 Z")


def circle(cx, cy, r):
    return ellipse(cx, cy, r, r)


def rect(x, y, w, h, r=0):
    if r == 0:
        return f"M{n(x)} {n(y)} L{n(x + w)} {n(y)} L{n(x + w)} {n(y + h)} L{n(x)} {n(y + h)} Z"
    return (f"M{n(x + r)} {n(y)} L{n(x + w - r)} {n(y)} A{n(r)} {n(r)} 0 0 1 {n(x + w)} {n(y + r)} "
            f"L{n(x + w)} {n(y + h - r)} A{n(r)} {n(r)} 0 0 1 {n(x + w - r)} {n(y + h)} "
            f"L{n(x + r)} {n(y + h)} A{n(r)} {n(r)} 0 0 1 {n(x)} {n(y + h - r)} "
            f"L{n(x)} {n(y + r)} A{n(r)} {n(r)} 0 0 1 {n(x + r)} {n(y)} Z")


def line(x1, y1, x2, y2):
    return f"M{n(x1)} {n(y1)} L{n(x2)} {n(y2)}"


def star(cx, cy, r):
    pts = []
    for i in range(10):
        a = math.pi / 2 + i * math.pi / 5
        rr = r if i % 2 == 0 else r * 0.45
        pts.append((cx + rr * math.cos(a), cy - rr * math.sin(a)))
    return "M" + " L".join(f"{n(x)} {n(y)}" for x, y in pts) + " Z"


def S(d, fill=None, stroke=None, sw=0.0, fill_alpha=1.0, stroke_alpha=1.0, rot=None, cap="round"):
    """One shape. rot = (degrees, pivotX, pivotY)."""
    return dict(d=d, fill=fill, stroke=stroke, sw=sw, fa=fill_alpha, sa=stroke_alpha, rot=rot, cap=cap)


# ─── season art ─────────────────────────────────────────────────────────────
def clover(cx, cy, r):
    out = [S(circle(cx + dx * r, cy + dy * r, r), "#3FA34D", INK, 1.4) for dx, dy in [(0, -0.9), (-0.85, 0.3), (0.85, 0.3)]]
    out.append(S(f"M{n(cx)} {n(cy + r * 0.6)} q1 {n(r * 1.4)} {n(r * 0.9)} {n(r * 2)}", None, INK, 1.4))
    return out


def burst(cx, cy, r, col):
    out = []
    for i in range(10):
        a = i * math.pi / 5
        out.append(S(line(cx + math.cos(a) * r * 0.35, cy + math.sin(a) * r * 0.35,
                          cx + math.cos(a) * r, cy + math.sin(a) * r), None, col, 2))
    return out


SEASONS = {}

# New Year's — party hat + confetti
SEASONS["NewYear"] = dict(
    key="newyear", suffix="NewYear", body=None, cheek=None, hides_cheeks=False,
    behind=[S(rect(x, y, 5, 2.6, 1.2), c, rot=(r, x + 2.5, y + 1.3))
            for x, y, c, r in [(7, 20, "#FFD166", 30), (88, 40, "#4FC3F7", -20), (17, 9, "#FF6B9E", 60),
                               (83, 13, "#7BD389", 15), (5, 46, "#B79CFF", -40), (92, 60, "#FFD166", 50),
                               (26, 3, "#4FC3F7", -25), (72, 2, "#FF6B9E", 35)]],
    front=[],
    frontNoHat=[
        S("M37 27 L56 5 L66 26 Z", "#FFD166", INK, 2.8),
        S(line(42.2, 21, 60.8, 15), None, "#E0457B", 3, cap="butt"),
        S(line(37.9, 26, 63.6, 21), None, "#E0457B", 3, cap="butt"),
        S(circle(56, 5, 3.6), "#E0457B", INK, 2),
    ],
)

# Winter — earmuffs + knit scarf
SEASONS["Winter"] = dict(
    key="winter", suffix="Winter", body=None, cheek=None, hides_cheeks=False,
    behind=[],
    front=[
        S("M14 66 Q50 76 86 66 L86 74 Q50 84 14 74 Z", "#2A9D8F", INK, 2.5),
        S("M66 74 L70 94 L80 92 L76 72 Z", "#2A9D8F", INK, 2.5),
        S(line(30, 70, 32, 77), None, "#FFFFFF", 2, stroke_alpha=0.55),
        S(line(42, 73, 43, 80), None, "#FFFFFF", 2, stroke_alpha=0.55),
        S(line(56, 73, 56, 80), None, "#FFFFFF", 2, stroke_alpha=0.55),
        S(line(69, 84, 78, 82), None, "#FFFFFF", 2, stroke_alpha=0.55),
    ],
    frontNoHat=[
        S("M18 44 C18 8 82 8 82 44", None, "#5B9BD5", 4.5),
        S(circle(15, 48, 9), "#EAF4FF", INK, 2.5),
        S(circle(85, 48, 9), "#EAF4FF", INK, 2.5),
    ],
)

# St. Patrick's — green Bo, leprechaun hat, shamrocks
SEASONS["StPatricks"] = dict(
    key="stpatricks", suffix="StPatricks", body="#5CC36E", cheek="#2E9E4A", hides_cheeks=False,
    behind=clover(86, 22, 4.5) + clover(12, 30, 3.5),
    front=[],
    frontNoHat=[
        S(ellipse(50, 25, 30, 5.5), "#2E7D32", INK, 2.8),
        S("M35 25 L37 2 L63 2 L65 25 Z", "#2E7D32", INK, 2.8),
        S(rect(36, 15, 28, 6), INK),
        S(rect(46, 14, 8, 8), None, "#FFD166", 2),
    ],
)

# Easter — lilac Bo, bunny ears, painted egg
SEASONS["Easter"] = dict(
    key="easter", suffix="Easter", body="#B79CFF", cheek="#FF8FB8", hides_cheeks=False,
    behind=[
        S(ellipse(36, 17, 7.5, 15.5), "#FFFFFF", INK, 2.8, rot=(-12, 36, 17)),
        S(ellipse(36, 17, 3.5, 10.5), "#FFB3C7", rot=(-12, 36, 17)),
        S(ellipse(64, 17, 7.5, 15.5), "#FFFFFF", INK, 2.8, rot=(14, 64, 17)),
        S(ellipse(64, 17, 3.5, 10.5), "#FFB3C7", rot=(14, 64, 17)),
    ],
    front=[
        S(ellipse(88, 70, 7, 9), "#FFE08A", INK, 2),
        S("M81.5 69 l3 -2 l3 2 l3 -2 l3 2", None, "#4FC3F7", 1.8),
    ],
    frontNoHat=[],
)

# Fourth of July — fireworks, star cheeks, sparkler
SEASONS["July4"] = dict(
    key="july4", suffix="July4", body=None, cheek=None, hides_cheeks=True,
    behind=burst(86, 16, 12, "#4F7DF0") + burst(14, 18, 10, "#D7263D") + burst(52, 8, 6, "#FFD166"),
    front=[
        S(star(28, 58, 5), "#FFFFFF", INK, 1.5),
        S(star(72, 58, 5), "#FFFFFF", INK, 1.5),
        S(line(90, 82, 80, 64), None, "#8A8A8A", 2),
        S(star(80, 62, 5), "#FFD166", INK, 1.2),
    ],
    frontNoHat=[],
)


# ─── emitters ───────────────────────────────────────────────────────────────
def argb(hex_, alpha=1.0):
    return "0x%02X%s" % (round(alpha * 255), hex_.lstrip("#").upper())


def kt_shape(s):
    fill = argb(s["fill"], s["fa"]) if s["fill"] else "null"
    stroke = argb(s["stroke"], s["sa"]) if s["stroke"] else "null"
    rot = s["rot"] or (0, 0, 0)
    cap = "StrokeCap.Butt" if s["cap"] == "butt" else "StrokeCap.Round"
    return (f'        ArtShape("{s["d"]}", {fill}, {stroke}, {n(s["sw"])}f, '
            f'{n(rot[0])}f, {n(rot[1])}f, {n(rot[2])}f, {cap}),')


def write_kotlin():
    out = ["// GENERATED by scripts/gen_bo_season_art.py — edit the script, not this file.",
           "package live.localapp.local.ui.character", "",
           "import androidx.compose.ui.graphics.StrokeCap", "",
           "/** Shape data for the data-driven seasons; drawn by [drawArt] via BoSeason's hooks. */",
           "internal object BoSeasonArt {"]
    for name, s in SEASONS.items():
        for layer in ("behind", "front", "frontNoHat"):
            shapes = s[layer]
            prop = name[0].lower() + name[1:] + layer[0].upper() + layer[1:]
            if not shapes:
                out.append(f"    val {prop}: List<ArtShape> = emptyList()")
                continue
            out.append(f"    val {prop}: List<ArtShape> by lazy {{ listOf(")
            out += [kt_shape(x) for x in shapes]
            out.append("    ) }")
    out.append("}")
    p = os.path.join(ROOT, "composeApp/src/commonMain/kotlin/live/localapp/local/ui/character/BoSeasonArt.kt")
    open(p, "w").write("\n".join(out) + "\n")


def svg_shape(s, indent="  "):
    a = [f'd="{s["d"]}"', f'fill="{s["fill"] or "none"}"']
    if s["fill"] and s["fa"] != 1:
        a.append(f'fill-opacity="{n(s["fa"])}"')
    if s["stroke"]:
        a += [f'stroke="{s["stroke"]}"', f'stroke-width="{n(s["sw"])}"', 'stroke-linejoin="round"',
              f'stroke-linecap="{s["cap"]}"']
        if s["sa"] != 1:
            a.append(f'stroke-opacity="{n(s["sa"])}"')
    if s["rot"]:
        a.append(f'transform="rotate({n(s["rot"][0])} {n(s["rot"][1])} {n(s["rot"][2])})"')
    return f'{indent}<path {" ".join(a)}/>\n'


def xml_shape(s):
    attrs = [f'android:pathData="{s["d"]}"']
    if s["fill"]:
        attrs.append(f'android:fillColor="{s["fill"]}"')
        if s["fa"] != 1:
            attrs.append(f'android:fillAlpha="{n(s["fa"])}"')
    if s["stroke"]:
        attrs += [f'android:strokeColor="{s["stroke"]}"', f'android:strokeWidth="{n(s["sw"])}"',
                  'android:strokeLineJoin="round"', f'android:strokeLineCap="{s["cap"]}"']
        if s["sa"] != 1:
            attrs.append(f'android:strokeAlpha="{n(s["sa"])}"')
    body = "    <path\n" + "".join(f"        {x}\n" for x in attrs[:-1]) + f"        {attrs[-1]} />\n"
    if s["rot"]:
        deg, px, py = s["rot"]
        body = (f'    <group android:rotation="{n(deg)}" android:pivotX="{n(px)}" android:pivotY="{n(py)}">\n'
                + re.sub(r"^", "    ", body, flags=re.M) + "    </group>\n")
    return body


def write_android():
    d = os.path.join(ROOT, "composeApp/src/commonMain/composeResources/drawable")
    base = open(os.path.join(d, "bo_tiny.xml")).read()
    cheeks_re = re.compile(r'    <!-- Cheeks -->\n(    <path\n[^/]*?android:fillColor="#E85555"\n        android:fillAlpha="0.7" />\n){2}')
    for name, s in SEASONS.items():
        x = base
        behind = s["behind"]
        front = s["front"] + s["frontNoHat"]
        if behind:
            x = x.replace("    <!-- Body -->", f"    <!-- {name}: behind -->\n" + "".join(map(xml_shape, behind)) + "\n    <!-- Body -->", 1)
        if s["body"]:
            x = x.replace('android:fillColor="#FF6B6B"', f'android:fillColor="{s["body"]}"')
        if s["hides_cheeks"]:
            x, k = cheeks_re.subn("", x)
            assert k == 1, name
        elif s["cheek"]:
            x = x.replace('android:fillColor="#E85555"', f'android:fillColor="{s["cheek"]}"')
        if front:
            x = x.replace("</vector>", f"\n    <!-- {name}: front -->\n" + "".join(map(xml_shape, front)) + "</vector>")
        open(os.path.join(d, f"bo_tiny_{s['key']}.xml"), "w").write(x)


def write_ios():
    a = os.path.join(ROOT, "iosApp/iosApp/Assets.xcassets")
    cheeks_re = re.compile(r'  <ellipse cx="28" cy="58" rx="4.5" ry="3" fill="#E85555" opacity="0.7"/>\n  <ellipse cx="72" cy="58" rx="4.5" ry="3" fill="#E85555" opacity="0.7"/>')
    for base_name, src in [("BoTiny", "BoTiny.imageset/bo-tiny.svg"),
                           ("BoDefaultClean", "BoDefaultClean.imageset/bo-default-clean.svg")]:
        base = open(os.path.join(a, src)).read()
        stem = os.path.basename(src)[:-4]
        for name, s in SEASONS.items():
            x = base
            if s["behind"]:
                x = x.replace('  <g id="bo-body">', f'  <g id="bo-{s["key"]}-behind">\n' + "".join(svg_shape(i, "    ") for i in s["behind"]) + '  </g>\n  <g id="bo-body">', 1)
            if s["body"]:
                x = x.replace('fill="#FF6B6B"', f'fill="{s["body"]}"', 1)
            if s["hides_cheeks"]:
                x, k = cheeks_re.subn("", x)
                assert k == 1, (name, base_name)
            elif s["cheek"]:
                x = x.replace('fill="#E85555"', f'fill="{s["cheek"]}"')
            front = s["front"] + s["frontNoHat"]
            if front:
                x = x.replace("</svg>", f'  <g id="bo-{s["key"]}-front">\n' + "".join(svg_shape(i, "    ") for i in front) + "  </g>\n</svg>")
            folder = os.path.join(a, f"{base_name}{s['suffix']}.imageset")
            os.makedirs(folder, exist_ok=True)
            fn = f"{stem}-{s['key']}.svg"
            open(os.path.join(folder, fn), "w").write(x)
            json.dump({"images": [{"filename": fn, "idiom": "universal"}],
                       "info": {"author": "xcode", "version": 1},
                       "properties": {"preserves-vector-representation": True}},
                      open(os.path.join(folder, "Contents.json"), "w"), indent=2)


if __name__ == "__main__":
    write_kotlin()
    write_android()
    write_ios()
    print("generated:", ", ".join(SEASONS))
