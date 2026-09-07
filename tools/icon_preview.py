#!/usr/bin/env python3
"""Render the launcher icon to an HTML page, masked the way launchers mask it.

An adaptive icon is easy to get subtly wrong and impossible to judge from the XML. The
drawable is 108x108 but only the middle 72x72 is ever shown, and the device picks its own
mask — circle, squircle, rounded square — so a mark that looks fine in an editor can be
clipped on someone's phone and nowhere else.

This translates the actual drawables into SVG and shows them under each mask, at the sizes
a launcher really uses, with the mask boundary drawn as a guide. It reads the same files the
app ships, so the preview cannot drift from what is built.

It earned its place immediately: the first version of the icon had an arithmetic error in a
rounded-rectangle path that made the card ten units too wide and pushed it off centre, which
was invisible in the XML and obvious the moment it was drawn against the mask.

Usage:  python tools/icon_preview.py  →  build/preview/icon.html
"""

from __future__ import annotations

import os
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DRAWABLE = os.path.join(ROOT, "app", "src", "main", "res", "drawable")
OUT = os.path.join(ROOT, "build", "preview")
NS = "http://schemas.android.com/apk/res/android"


def svg_body(path: str) -> str:
    """The paths and groups of a VectorDrawable, as SVG elements.

    Only the subset this icon uses: fills, alpha, fill rules and group translation. It is
    not a general converter and does not pretend to be.
    """
    root = ET.parse(path).getroot()
    out: list[str] = []

    def walk(node) -> None:
        for child in node:
            if child.tag == "group":
                tx = child.get(f"{{{NS}}}translateX", "0")
                ty = child.get(f"{{{NS}}}translateY", "0")
                sx = child.get(f"{{{NS}}}scaleX")
                sy = child.get(f"{{{NS}}}scaleY")
                px = child.get(f"{{{NS}}}pivotX", "0")
                py = child.get(f"{{{NS}}}pivotY", "0")
                transform = f"translate({tx},{ty})"
                if sx or sy:
                    transform += (f" translate({px},{py}) scale({sx or 1},{sy or sx or 1})"
                                  f" translate(-{px},-{py})")
                out.append(f'<g transform="{transform}">')
                walk(child)
                out.append("</g>")
            elif child.tag == "path":
                data = child.get(f"{{{NS}}}pathData")
                fill = child.get(f"{{{NS}}}fillColor", "#FF000000")
                alpha = child.get(f"{{{NS}}}fillAlpha", "1")
                rule = child.get(f"{{{NS}}}fillType", "nonZero")
                # Android writes #AARRGGBB; SVG wants #RRGGBB plus a separate opacity.
                colour = "#" + fill[3:] if len(fill) == 9 else fill
                svg_rule = "evenodd" if rule == "evenOdd" else "nonzero"
                out.append(f'<path d="{data}" fill="{colour}" fill-opacity="{alpha}" '
                           f'fill-rule="{svg_rule}"/>')

    walk(root)
    return "\n".join(out)


def tile(size: int, body: str, guides: bool = False) -> str:
    """One masked tile. viewBox 18..90 is the 72x72 a launcher shows of the 108x108 canvas.

    Nothing is painted behind the mark: the adaptive icon's background layer is transparent,
    so the tile's own backdrop stands in for the wallpaper.
    """
    guide = ('<circle cx="54" cy="54" r="36" fill="none" stroke="#00E5FF" '
             'stroke-width="0.6" stroke-dasharray="2 2"/>') if guides else ""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" '
            f'viewBox="18 18 72 72">{body}{guide}</svg>')


def plain(size: int, body: str, shadow: bool = False) -> str:
    """The shipped icon, drawn the way a launcher draws a non-adaptive drawable: whole, at
    its own shape, with no mask and no mask shadow.

    [shadow] turns on a stand-in for the plate a launcher puts behind an *adaptive* icon —
    generated from the mask, not from the artwork — so the two can be compared side by side.
    """
    plate = ""
    if shadow:
        plate = ('<defs><filter id="soft" x="-40%" y="-40%" width="180%" height="180%">'
                 '<feGaussianBlur stdDeviation="4"/></filter></defs>'
                 '<rect x="18" y="20" width="72" height="72" rx="22" fill="#000" '
                 'opacity="0.5" filter="url(#soft)"/>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" '
            f'viewBox="0 0 108 108">{plate}{body}</svg>')


PAGE = """<title>Seamless icon preview</title>
<style>
 body{background:#101014;color:#e8e8ee;font:14px system-ui,sans-serif;margin:0;padding:26px}
 .row{display:flex;gap:30px;align-items:flex-end;flex-wrap:wrap}
 figure{margin:0;text-align:center}
 figcaption{margin-top:9px;font-size:12px;color:#9a9aa8}
 .circle{clip-path:circle(50%% at 50%% 50%%)}
 .squircle{clip-path:inset(0 round 22%%)}
 .pale{background:#EDE7DC}
 .dark{background:#1B1B20}
 .shot{background:linear-gradient(135deg,#6E7F9B,#C6A98B)}
 svg{display:block}
</style>
<h2 style="font-size:15px;color:#c9c9d6;margin:0 0 10px">What ships: no adaptive wrapper</h2>
<p style="color:#9a9aa8;max-width:60em;margin:0 0 18px">
 A launcher draws a blurred plate behind every adaptive icon, generated from the mask path
 rather than from the artwork &mdash; which is why a transparent background layer leaves it
 showing and why nothing in the drawing could remove it. The right-hand tile stands that
 plate back in, for comparison. A plain drawable never goes through it.
</p>
<div class="row">
 <figure><div class="pale">%(f192)s</div><figcaption>as it ships &middot; pale</figcaption></figure>
 <figure><div class="dark">%(fd192)s</div><figcaption>as it ships &middot; dark</figcaption></figure>
 <figure><div class="pale">%(fs192)s</div><figcaption>what the adaptive one did</figcaption></figure>
 <figure><div class="pale">%(f96)s</div><figcaption>96px</figcaption></figure>
 <figure><div class="pale">%(f48)s</div><figcaption>48px</figcaption></figure>
</div>

<h2 style="font-size:15px;color:#c9c9d6;margin:34px 0 10px">
 The adaptive pair, kept as the alternative</h2>
<p style="color:#9a9aa8;max-width:60em;margin:0 0 18px">
 Still in the tree, and one manifest line away. The background layer is transparent, so these
 tiles show the mark over stand-in wallpapers; the cyan circle is the mask edge.
</p>
<div class="row">
 <figure><div class="pale">%(s192)s</div><figcaption>on a pale wallpaper &middot; 192px</figcaption></figure>
 <figure><div class="dark">%(c192)s</div><figcaption>on a dark wallpaper &middot; 192px</figcaption></figure>
 <figure><div class="shot">%(g192)s</div><figcaption>mask edge in cyan</figcaption></figure>
 <figure><div class="pale">%(s96)s</div><figcaption>96px</figcaption></figure>
 <figure><div class="pale">%(s48)s</div><figcaption>48px</figcaption></figure>
 <figure><div class="squircle" style="background:#3A6EA5">%(m96)s</div>
  <figcaption>themed (monochrome)</figcaption></figure>
</div>
"""


def main() -> int:
    foreground = svg_body(os.path.join(DRAWABLE, "ic_launcher_foreground.xml"))
    unmasked = svg_body(os.path.join(DRAWABLE, "ic_launcher_unmasked.xml"))
    monochrome = svg_body(os.path.join(DRAWABLE, "ic_launcher_monochrome.xml"))
    # The system tints the monochrome layer; white on a colour stands in for that.
    monochrome = monochrome.replace('fill="#FF000000"', 'fill="#FFFFFF"')

    os.makedirs(OUT, exist_ok=True)
    target = os.path.join(OUT, "icon.html")
    with open(target, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(PAGE % {
            "s192": tile(192, foreground),
            "c192": tile(192, foreground),
            "g192": tile(192, foreground, guides=True),
            "s96": tile(96, foreground),
            "s48": tile(48, foreground),
            "m96": tile(96, monochrome),
            "f192": plain(192, unmasked),
            "fd192": plain(192, unmasked),
            "fs192": plain(192, unmasked, shadow=True),
            "f96": plain(96, unmasked),
            "f48": plain(48, unmasked),
        })
    print(f"wrote {os.path.relpath(target, ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
