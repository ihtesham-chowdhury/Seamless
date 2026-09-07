#!/usr/bin/env python3
"""Convert art/icon.svg into the launcher icon's VectorDrawables.

The icon was traced by hand in Inkscape. Retyping its coordinates into Android's XML by
hand is exactly the kind of transcription that goes wrong quietly — an earlier version of
this icon shipped with an arithmetic slip in a rounded-rectangle path that made the card ten
units too wide, and nothing in the XML looked wrong. So the drawing is converted rather than
copied, and art/icon.svg stays in the repo as the source it came from.

The conversion is deliberately narrow: it understands the handful of SVG constructs this one
file uses — <rect> with an optional corner radius, <path>, group translation, and the
rotate(90) that Inkscape emits for a rect drawn on its side. It is not a general converter
and would not survive a redesign that used gradients, transforms on paths, or strokes.

Positioning is left to a VectorDrawable <group>: the paths keep their original coordinates
and one translate+scale maps the whole drawing into the 108x108 adaptive-icon viewport. That
way no path data is ever rewritten, so no path data can be mistyped.

Usage:  python tools/icon_from_svg.py
"""

from __future__ import annotations

import os
import re
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(ROOT, "art", "icon.svg")
DRAWABLE = os.path.join(ROOT, "app", "src", "main", "res", "drawable")

SVG = "http://www.w3.org/2000/svg"

# How wide the card should be in the 108-unit viewport.
#
# The mask is a circle of radius 36 about (54, 54) on the strictest launchers, and with no
# background layer there is nothing behind a clipped corner, so the card has to fit inside
# that circle on its own. What decides the limit is the furthest *painted* point, which on a
# rounded rectangle is not the bounding-box corner: it is the corner arc, one radius further
# in. Measuring the bounding box instead — which this script used to do — left the card 8%
# smaller than it needed to be, and every unit of that shows up as launcher plate around it.
#
# 54 puts the furthest painted point at 35.7 of the 36 available.
CARD_WIDTH = 54.0

# The unmasked icon's canvas, and how much of it the card fills.
#
# This one is not an adaptive icon, so there is no mask and no safe zone — the card is the
# whole icon and can run almost to the edge, the way an icon-pack icon does. Two units of
# margin keep the antialiased edge off the boundary.
FLAT_VIEWPORT = 108.0
FLAT_MARGIN = 2.0


def tag(element) -> str:
    return element.tag.split("}")[-1]


def style_of(element) -> dict[str, str]:
    """Inkscape puts presentation attributes in a style="a:b;c:d" string."""
    out = {}
    for pair in (element.get("style") or "").split(";"):
        if ":" in pair:
            key, value = pair.split(":", 1)
            out[key.strip()] = value.strip()
    for key in ("fill", "fill-opacity", "opacity"):
        if element.get(key):
            out[key] = element.get(key)
    return out


def group_translation(element, parents: dict) -> tuple[float, float]:
    """Accumulated translate() from every enclosing <g>."""
    dx = dy = 0.0
    node = parents.get(element)
    while node is not None:
        match = re.fullmatch(r"translate\(([-\d.eE]+)[, ]+([-\d.eE]+)\)",
                             (node.get("transform") or "").strip())
        if match:
            dx += float(match.group(1))
            dy += float(match.group(2))
        node = parents.get(node)
    return dx, dy


def rect_to_path(element, dx: float, dy: float) -> tuple[str, float, float, float, float]:
    """A <rect> as path data, plus its absolute bounds.

    Handles the rotate(90) Inkscape writes when a rectangle is drawn on its side: under that
    rotation a point (x, y) lands at (-y, x), which turns the stored negative y into the
    real x and swaps the width and height.
    """
    x = float(element.get("x", 0))
    y = float(element.get("y", 0))
    w = float(element.get("width", 0))
    h = float(element.get("height", 0))

    if (element.get("transform") or "").strip() == "rotate(90)":
        x, y, w, h = -(y + h), x, h, w

    x += dx
    y += dy

    radius = element.get("ry") or element.get("rx")
    if radius:
        r = float(radius)
        straight_w = w - 2 * r
        straight_h = h - 2 * r
        data = (f"M{x + r:g},{y:g}"
                f"h{straight_w:g}"
                f"a{r:g},{r:g} 0 0 1 {r:g},{r:g}"
                f"v{straight_h:g}"
                f"a{r:g},{r:g} 0 0 1 {-r:g},{r:g}"
                f"h{-straight_w:g}"
                f"a{r:g},{r:g} 0 0 1 {-r:g},{-r:g}"
                f"v{-straight_h:g}"
                f"a{r:g},{r:g} 0 0 1 {r:g},{-r:g}z")
    else:
        data = f"M{x:g},{y:g}h{w:g}v{h:g}h{-w:g}z"
    return data, x, y, x + w, y + h


def android_colour(css: str, opacity: str | None) -> tuple[str, str | None]:
    """#rrggbb plus a separate opacity, as Android wants it."""
    colour = css.strip()
    if not colour.startswith("#"):
        colour = "#000000"
    alpha = None
    if opacity is not None and float(opacity) < 0.999:
        alpha = f"{float(opacity):.3f}".rstrip("0").rstrip(".")
    return "#FF" + colour[1:].upper(), alpha


def collect() -> tuple[list[dict], tuple[float, float, float, float], float]:
    """Every drawable shape, in document order, with the card's bounds and corner radius."""
    tree = ET.parse(SOURCE)
    root = tree.getroot()
    parents = {child: parent for parent in root.iter() for child in parent}

    shapes: list[dict] = []
    card_bounds: tuple[float, float, float, float] | None = None
    card_radius = 0.0
    seen_paths: set[str] = set()

    for element in root.iter():
        name = tag(element)
        if name not in ("rect", "path"):
            continue
        # Inkscape's path-effect definitions live under <defs> and are not drawings.
        if any(tag(p) == "defs" for p in _ancestors(element, parents)):
            continue

        style = style_of(element)
        fill = style.get("fill")
        if not fill or fill == "none":
            continue

        dx, dy = group_translation(element, parents)
        opacity = style.get("opacity") or style.get("fill-opacity")
        colour, alpha = android_colour(fill, opacity)

        if name == "rect":
            data, x0, y0, x1, y1 = rect_to_path(element, dx, dy)
            # The first and largest rect is the card; everything else sits on top of it.
            if card_bounds is None:
                card_bounds = (x0, y0, x1, y1)
                card_radius = float(element.get("ry") or element.get("rx") or 0.0)
        else:
            data = element.get("d")
            if not data:
                continue
            if dx or dy:
                raise SystemExit(
                    f"path {element.get('id')} is inside a translated group; this converter "
                    "only shifts rects, because shifting path data means parsing it")
            # Inkscape left exact duplicates of the chevrons in the file. Drawing each one
            # twice is harmless but doubles the path count in the shipped drawable.
            if data in seen_paths:
                continue
            seen_paths.add(data)

        shapes.append({"data": data, "colour": colour, "alpha": alpha,
                       "id": element.get("id", "")})

    if card_bounds is None:
        raise SystemExit("no <rect> found; expected the card to be one")
    return shapes, card_bounds, card_radius


def _ancestors(element, parents):
    node = parents.get(element)
    while node is not None:
        yield node
        node = parents.get(node)


def build() -> tuple[str, str]:
    shapes, (x0, y0, x1, y1), radius = collect()
    scale = CARD_WIDTH / (x1 - x0)
    height = (y1 - y0) * scale
    # Centre the card in the viewport rather than assuming it is square.
    tx = (54.0 - CARD_WIDTH / 2) - scale * x0
    ty = (54.0 - height / 2) - scale * y0

    # The furthest painted point of a rounded rectangle: out to the centre of the corner
    # arc, then one radius further along that diagonal.
    r = radius * scale
    furthest = (((CARD_WIDTH / 2 - r) ** 2 + (height / 2 - r) ** 2) ** 0.5) + r
    print(f"card {CARD_WIDTH:g} x {height:.2f} units, corner radius {r:.2f}; "
          f"furthest painted point {furthest:.2f} from centre (mask radius 36)")
    if furthest > 36:
        print("  WARNING: the card falls outside the mask and will be clipped")

    header = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!--\n"
        "  GENERATED by tools/icon_from_svg.py from art/icon.svg. Do not edit by hand: edit\n"
        "  the SVG and run the script again.\n"
        "\n"
        "  There is no coloured tile behind this. The adaptive icon's background layer is\n"
        "  transparent, so the home screen shows the card's own silhouette over the wallpaper.\n"
        "  The mask is still applied, which is why the group below scales the drawing to sit\n"
        "  inside the safe zone: with no background, a clipped corner would be a clipped icon.\n"
        "-->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n'
        '    android:height="108dp"\n'
        '    android:viewportWidth="108"\n'
        '    android:viewportHeight="108">\n\n'
        "    <!-- One transform for the whole drawing, so no path data is ever rewritten. -->\n"
        "    <group\n"
        f'        android:translateX="{tx:.4f}"\n'
        f'        android:translateY="{ty:.4f}"\n'
        f'        android:scaleX="{scale:.6f}"\n'
        f'        android:scaleY="{scale:.6f}">\n'
    )

    body = []
    for shape in shapes:
        alpha = f'\n            android:fillAlpha="{shape["alpha"]}"' if shape["alpha"] else ""
        body.append(
            f'\n        <!-- {shape["id"]} -->\n'
            "        <path\n"
            f'            android:fillColor="{shape["colour"]}"{alpha}\n'
            f'            android:pathData="{shape["data"]}" />\n'
        )

    colour_icon = header + "".join(body) + "    </group>\n</vector>\n"

    # The themed variant is the same geometry in one tone: the card solid, everything on top
    # of it punched back out so the shape still reads once the colour is gone.
    mono_paths = [s["data"] for s in shapes if not s["id"].startswith("path183")]
    mono = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!--\n"
        "  GENERATED by tools/icon_from_svg.py from art/icon.svg. Do not edit by hand.\n"
        "\n"
        "  The Android 13+ themed icon. The system supplies the colour, so this is a single\n"
        "  silhouette: evenOdd turns every shape drawn on top of the card into a hole rather\n"
        "  than letting it overpaint in a colour that no longer exists. The diagonal shading\n"
        "  is dropped — it says nothing without a second tone to say it in.\n"
        "-->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n'
        '    android:height="108dp"\n'
        '    android:viewportWidth="108"\n'
        '    android:viewportHeight="108">\n\n'
        "    <group\n"
        f'        android:translateX="{tx:.4f}"\n'
        f'        android:translateY="{ty:.4f}"\n'
        f'        android:scaleX="{scale:.6f}"\n'
        f'        android:scaleY="{scale:.6f}">\n'
        "        <path\n"
        '            android:fillColor="#FF000000"\n'
        '            android:fillType="evenOdd"\n'
        f'            android:pathData="{"".join(mono_paths)}" />\n'
        "    </group>\n</vector>\n"
    )

    # ---- The unmasked icon ----
    #
    # Why this exists, and why the manifest points at it: a launcher draws a blurred shadow
    # behind every adaptive icon, and it generates that shadow from the *mask path* rather
    # than from the artwork. An opaque background layer hides it. A transparent one does not,
    # which is why this icon sat inside a soft rounded plate on every launcher tried —
    # replacing it with an icon-pack icon, which is an ordinary non-adaptive drawable, made
    # the plate disappear on all of them. The shadow was never something the artwork could
    # fix, because the artwork is not what draws it.
    #
    # A plain drawable is not put through that path at all: no mask, no mask shadow, and the
    # silhouette that reaches the home screen is the card's own shape. The cost is Android
    # 13+ themed icons, which need an adaptive icon to theme.
    flat_width = FLAT_VIEWPORT - FLAT_MARGIN * 2
    flat_scale = flat_width / (x1 - x0)
    flat_height = (y1 - y0) * flat_scale
    flat_tx = FLAT_MARGIN - flat_scale * x0
    flat_ty = (FLAT_VIEWPORT - flat_height) / 2 - flat_scale * y0

    flat = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!--\n"
        "  GENERATED by tools/icon_from_svg.py from art/icon.svg. Do not edit by hand.\n"
        "\n"
        "  The launcher icon, with no adaptive wrapper.\n"
        "\n"
        "  An adaptive icon is always given a blurred plate: the launcher generates a shadow\n"
        "  from the mask path, not from the artwork, and a transparent background layer leaves\n"
        "  that shadow showing as a soft rounded shape behind the mark. Nothing in the drawing\n"
        "  can remove it. A plain drawable never enters that code path, so what reaches the\n"
        "  home screen is the card and nothing else.\n"
        "\n"
        "  The adaptive pair is still in the tree — mipmap-anydpi-v26/ic_launcher.xml — for\n"
        "  anyone who would rather have themed icons than no plate. Switching is one line in\n"
        "  AndroidManifest.xml.\n"
        "-->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="{FLAT_VIEWPORT:g}dp"\n'
        f'    android:height="{FLAT_VIEWPORT:g}dp"\n'
        f'    android:viewportWidth="{FLAT_VIEWPORT:g}"\n'
        f'    android:viewportHeight="{FLAT_VIEWPORT:g}">\n\n'
        "    <group\n"
        f'        android:translateX="{flat_tx:.4f}"\n'
        f'        android:translateY="{flat_ty:.4f}"\n'
        f'        android:scaleX="{flat_scale:.6f}"\n'
        f'        android:scaleY="{flat_scale:.6f}">\n'
        + "".join(body) +
        "    </group>\n</vector>\n"
    )
    print(f"unmasked card {flat_width:g} x {flat_height:.2f} of a "
          f"{FLAT_VIEWPORT:g} canvas")
    return colour_icon, mono, flat


def main() -> int:
    colour_icon, mono, flat = build()
    for name, content in (("ic_launcher_foreground.xml", colour_icon),
                          ("ic_launcher_monochrome.xml", mono),
                          ("ic_launcher_unmasked.xml", flat)):
        path = os.path.join(DRAWABLE, name)
        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(content)
        print(f"wrote {os.path.relpath(path, ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
