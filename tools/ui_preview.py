#!/usr/bin/env python3
"""Render the custom-drawn player views to an HTML page.

WaveformTimeBar and LevelHudView have no layout to inspect — they are a few hundred lines
of onDraw, and the only way to know what they look like short of installing the app is to
run the same arithmetic somewhere you can see the result.

So that is what this does: it reproduces their drawing maths in SVG. It is a *port*, not a
capture, which means it can drift from the Kotlin if one is edited and the other is not.
That is a real limitation and worth stating plainly. What it is good for is judging shape,
proportion and whether a value looks right at the extremes — 0%, 100%, boosted, scrubbing —
which is exactly the class of thing that is otherwise invisible until it is on a phone.

Usage:  python tools/ui_preview.py  →  build/preview/ui.html
"""

from __future__ import annotations

import math
import os
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DRAWABLE = os.path.join(ROOT, "app", "src", "main", "res", "drawable")
OUT = os.path.join(ROOT, "build", "preview")
NS = "http://schemas.android.com/apk/res/android"

# A dense phone, so the dp figures in the Kotlin land on realistic pixel sizes.
DENSITY = 3.0


# ---- WaveformTimeBar ----

THUMB_RADIUS_DP = 5.0
AMPLITUDE_FRACTION = 0.78
STEP_DP = 1.5
FADE_DP = 20.0
PLAYED = "#FFFFFF"
UNPLAYED = "rgba(255,255,255,0.35)"


# (amplitude scale, phase offset, opacity) — mirrors WaveformTimeBar.STRANDS.
STRANDS = ((1.0, 0.0, 1.0), (0.70, 2.1, 0.5), (0.44, 4.2, 0.3))


def wave_at(x: float, track_width: float, phase: float) -> float:
    """Mirrors WaveformTimeBar.waveAt."""
    a = math.sin(2 * math.pi * x / (track_width / 13.1) + phase)
    b = math.sin(2 * math.pi * x / (track_width / 7.3) - phase * 0.6)
    c = math.sin(2 * math.pi * x / (track_width / 23.7) + phase * 1.7)
    return 0.52 * a + 0.32 * b + 0.16 * c


def waveform(width_dp: float, height_dp: float, fraction: float, phase: float = 0.0) -> str:
    w = width_dp * DENSITY
    h = height_dp * DENSITY
    stroke = 2.0 * DENSITY
    track_left = THUMB_RADIUS_DP * DENSITY
    track_right = w - THUMB_RADIUS_DP * DENSITY
    track_width = max(track_right - track_left, 1.0)
    centre_y = h / 2
    played = track_left + track_width * fraction

    parts = [f'<line x1="{played:.1f}" y1="{centre_y:.1f}" x2="{track_right:.1f}" '
             f'y2="{centre_y:.1f}" stroke="{UNPLAYED}" stroke-width="{stroke:.1f}" '
             'stroke-linecap="round"/>']

    if played > track_left + 0.5:
        step = STEP_DP * DENSITY
        span = played - track_left
        fade = max(min(span * 0.12, FADE_DP * DENSITY), 1.0)
        for index, (amp_scale, phase_offset, alpha) in enumerate(STRANDS):
            amplitude = (h / 2 - 2 * DENSITY) * AMPLITUDE_FRACTION * amp_scale
            points = []
            x = track_left
            while x <= played:
                t = x - track_left
                taper = max(min(t / fade, (played - x) / fade, 1.0), 0.0)
                y = centre_y + amplitude * taper * wave_at(
                    t, track_width, phase + phase_offset)
                points.append(f"{x:.1f},{y:.1f}")
                x += step
            points.append(f"{played:.1f},{centre_y:.1f}")
            width = (2.0 - index * 0.3) * DENSITY
            parts.append(f'<polyline points="{" ".join(points)}" fill="none" '
                         f'stroke="{PLAYED}" stroke-opacity="{alpha}" '
                         f'stroke-width="{width:.1f}" stroke-linecap="round" '
                         'stroke-linejoin="round"/>')

    parts.append(f'<circle cx="{played:.1f}" cy="{centre_y:.1f}" '
                 f'r="{THUMB_RADIUS_DP * DENSITY:.1f}" fill="{PLAYED}"/>')

    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{w:.0f}" height="{h:.0f}" '
            f'viewBox="0 0 {w:.0f} {h:.0f}">{"".join(parts)}</svg>')


# ---- LevelHudView ----

WIDTH_DP, HEIGHT_DP = 54.0, 172.0
PADDING_DP, TRACK_DP, GLYPH_DP = 12.0, 6.0, 22.0
BOOST_SHARE = 0.33
BOOST_COLOR = "#F2C230"


def glyph_paths(name: str) -> str:
    """The vector drawable's paths, ready to drop into an SVG group."""
    root = ET.parse(os.path.join(DRAWABLE, f"{name}.xml")).getroot()
    out = []
    for child in root:
        if child.tag == "path":
            out.append(f'<path d="{child.get(f"{{{NS}}}pathData")}" fill="#FFFFFF"/>')
    return "".join(out)


def level_hud(level: float, label: str, glyph: str, overflow: float = 0.0) -> str:
    w = WIDTH_DP * DENSITY
    h = HEIGHT_DP * DENSITY
    pad = PADDING_DP * DENSITY
    glyph_size = GLYPH_DP * DENSITY
    text_size = 13.0 * DENSITY

    top = pad + text_size + pad * 0.6
    bottom = h - pad - glyph_size - pad * 0.6
    track_w = TRACK_DP * DENSITY
    left = (w - track_w) / 2

    span = bottom - top
    normal_span = span * (1 - BOOST_SHARE) if overflow > 0 else span
    filled = normal_span * level

    parts = [f'<rect width="{w:.0f}" height="{h:.0f}" rx="{w / 2:.1f}" '
             'fill="rgba(0,0,0,0.70)"/>',
             f'<rect x="{left:.1f}" y="{top:.1f}" width="{track_w:.1f}" '
             f'height="{span:.1f}" rx="{track_w / 2:.1f}" fill="rgba(255,255,255,0.25)"/>']
    if filled > 0:
        parts.append(f'<rect x="{left:.1f}" y="{bottom - filled:.1f}" width="{track_w:.1f}" '
                     f'height="{filled:.1f}" rx="{track_w / 2:.1f}" fill="#FFFFFF"/>')
    if overflow > 0:
        boost_top = bottom - normal_span - span * BOOST_SHARE * overflow
        parts.append(f'<rect x="{left:.1f}" y="{boost_top:.1f}" width="{track_w:.1f}" '
                     f'height="{bottom - normal_span - boost_top:.1f}" '
                     f'rx="{track_w / 2:.1f}" fill="{BOOST_COLOR}"/>')

    parts.append(f'<text x="{w / 2:.1f}" y="{pad + text_size:.1f}" fill="#FFFFFF" '
                 f'font-size="{text_size:.1f}" font-family="system-ui" font-weight="700" '
                 f'text-anchor="middle">{label}</text>')

    scale = glyph_size / 24.0
    gx = (w - glyph_size) / 2
    gy = h - pad - glyph_size
    parts.append(f'<g transform="translate({gx:.1f},{gy:.1f}) scale({scale:.3f})">'
                 f'{glyph_paths(glyph)}</g>')

    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{w:.0f}" height="{h:.0f}" '
            f'viewBox="0 0 {w:.0f} {h:.0f}">{"".join(parts)}</svg>')


# ---- Toolbar capsule, badges and icons ----

def icon(name: str, size: float, colour: str | None = "#FFFFFF",
         opacity: float = 1.0) -> str:
    """One vector drawable at a given size.

    A single-colour drawable is recoloured by the caller, because in the app it is tinted by
    the theme. Pass colour=None for one that carries its own palette — the padlock, which is
    the user's own artwork and is shown untinted.
    """
    root = ET.parse(os.path.join(DRAWABLE, f"{name}.xml")).getroot()
    viewport = float(root.get(f"{{{NS}}}viewportWidth", "24"))
    parts = []
    for child in root:
        if child.tag != "path":
            continue
        data = child.get(f"{{{NS}}}pathData")
        alpha = child.get(f"{{{NS}}}fillAlpha", "1")
        fill = child.get(f"{{{NS}}}fillColor", "#FF000000")
        stroke = child.get(f"{{{NS}}}strokeColor")
        own = "#" + fill[3:] if len(fill) == 9 else fill
        painted = "none" if fill == "#00000000" else (colour or own)
        extra = ""
        if stroke:
            width = child.get(f"{{{NS}}}strokeWidth", "1")
            cap = child.get(f"{{{NS}}}strokeLineCap", "butt")
            extra = (f' stroke="{colour or own}" stroke-width="{width}" '
                     f'stroke-linecap="{cap}" fill="none"')
            painted = "none"
        rule = child.get(f"{{{NS}}}fillType", "nonZero")
        svg_rule = "evenodd" if rule == "evenOdd" else "nonzero"
        parts.append(f'<path d="{data}" fill="{painted}" fill-opacity="{alpha}" '
                     f'fill-rule="{svg_rule}"{extra}/>')
    scale = size / viewport
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size:.0f}" height="{size:.0f}" '
            f'viewBox="0 0 {size:.0f} {size:.0f}" style="opacity:{opacity}">'
            f'<g transform="scale({scale:.4f})">{"".join(parts)}</g></svg>')


def capsule(icons: list[str], dark: bool) -> str:
    """The toolbar's frosted action capsule, at the colours from values/ and values-night/."""
    fill_top = "rgba(255,255,255,0.15)" if dark else "rgba(0,0,0,0.08)"
    fill_bottom = "rgba(255,255,255,0.08)" if dark else "rgba(0,0,0,0.04)"
    stroke = "rgba(255,255,255,0.18)" if dark else "rgba(0,0,0,0.10)"
    tint = "#E3E3E8" if dark else "#45464F"
    cells = "".join(
        f'<div style="width:38px;height:38px;display:flex;align-items:center;'
        f'justify-content:center">{icon(name, 22, tint)}</div>' for name in icons)
    return (f'<div style="height:44px;display:inline-flex;align-items:center;padding:0 3px;'
            f'border-radius:100px;border:1px solid {stroke};'
            f'background:linear-gradient(180deg,{fill_top},{fill_bottom})">{cells}</div>')


def toolbar(title: str, icons: list[str], dark: bool) -> str:
    bg = "#141419" if dark else "#FBF8FD"
    fg = "#E6E1E5" if dark else "#1C1B1F"
    return (f'<div style="background:{bg};padding:10px 12px;border-radius:12px;'
            f'display:flex;align-items:center;justify-content:space-between;min-width:420px">'
            f'<span style="color:{fg};font-size:20px;font-weight:500;padding-left:8px">{title}'
            f'</span>{capsule(icons, dark)}</div>')


def speed_badge() -> str:
    return ('<div style="display:inline-flex;align-items:center;gap:6px;'
            'background:rgba(0,0,0,0.85);border-radius:100px;padding:9px 16px 9px 18px;'
            'color:#fff;font-weight:700;font-size:15px;letter-spacing:0.02em">'
            f'2x{icon("ic_fast_forward", 18)}</div>')


def transport_row() -> str:
    """The player's transport buttons at their real sizes, white and unplated."""
    def button(name, box, pad):
        inner = box - pad * 2
        return (f'<div style="width:{box}px;height:{box}px;display:flex;align-items:center;'
                f'justify-content:center">{icon(name, inner)}</div>')
    # Media3 supplies the play glyph, so it is not one of the app's own drawables; the
    # triangle is inlined here rather than adding a resource that only this preview uses.
    play_size = 76 - 15 * 2
    play = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{play_size}" '
            f'height="{play_size}" viewBox="0 0 24 24">'
            '<path d="M8,5v14l11,-7z" fill="#FFFFFF"/></svg>')
    return ('<div style="display:inline-flex;align-items:center;gap:10px">'
            + button("ic_skip_previous", 52, 12)
            + f'<div style="width:76px;height:76px;display:flex;align-items:center;'
              f'justify-content:center">{play}</div>'
            + button("ic_skip_next", 52, 12)
            + "</div>")


# ---- Chrome that is layout rather than onDraw ----

# Enough of the Material palette to place a colour by name. Approximations of the baseline
# M3 roles; what these are for is judging weight and contrast, not matching a hex.
LIGHT = {"surface": "#FBF8FD", "on_surface": "#1C1B1F", "container": "#F2ECF6",
         "on_variant": "#45464F", "primary": "#6750A4",
         "secondary_container": "#E8DEF8", "on_secondary_container": "#1D192B",
         "outline": "rgba(0,0,0,0.14)"}
DARK = {"surface": "#141419", "on_surface": "#E6E1E5", "container": "#211F26",
        "on_variant": "#C9C5D0", "primary": "#D0BCFF",
        "secondary_container": "#4A4458", "on_secondary_container": "#E8DEF8",
        "outline": "rgba(255,255,255,0.16)"}


def palette(dark: bool) -> dict:
    return DARK if dark else LIGHT


def nav_pill(selected: str, dark: bool) -> str:
    """The floating navigation capsule, at the colours in values/ and values-night/."""
    if dark:
        top, bottom = "rgba(46,46,54,0.961)", "rgba(35,35,42,0.922)"
        stroke = "rgba(255,255,255,0.20)"
    else:
        top, bottom = "rgba(255,255,255,0.969)", "rgba(242,238,246,0.929)"
        stroke = "rgba(0,0,0,0.122)"
    p = palette(dark)

    tabs = []
    for key, label, glyph in (("library", "Library", "ic_folder"),
                              ("shorts", "Shorts", "ic_shorts"),
                              ("settings", "Settings", "ic_settings")):
        on = key == selected
        fill = p["secondary_container"] if on else "transparent"
        tint = p["on_secondary_container"] if on else p["on_variant"]
        tabs.append(
            f'<div style="width:78px;height:50px;margin:0 2px;border-radius:100px;'
            f'background:{fill};display:flex;flex-direction:column;align-items:center;'
            f'justify-content:center;gap:3px">{icon(glyph, 22, tint)}'
            f'<span style="font-size:10px;color:{tint};line-height:1">{label}</span></div>')

    return (f'<div style="height:62px;display:inline-flex;align-items:center;padding:0 5px;'
            f'border-radius:100px;border:1px solid {stroke};'
            f'box-shadow:0 6px 18px rgba(0,0,0,0.22);'
            f'background:linear-gradient(180deg,{top},{bottom})">{"".join(tabs)}</div>')


def nav_scene(selected: str, dark: bool) -> str:
    """The capsule over the content it floats above, which is the whole point of it."""
    p = palette(dark)
    rows = "".join(
        f'<div style="height:34px;margin:0 12px 8px;border-radius:8px;'
        f'background:{p["container"]}"></div>' for _ in range(6))
    return (f'<div class="mock" style="width:300px;height:236px;border-radius:20px;'
            f'overflow:hidden;'
            f'position:relative;background:{p["surface"]};padding-top:12px">{rows}'
            f'<div style="position:absolute;left:0;right:0;bottom:12px;text-align:center">'
            f'{nav_pill(selected, dark)}</div></div>')


def locked_tile(dark: bool) -> str:
    """A locked folder in the grid: the padlock at its real size on a real tile."""
    p = palette(dark)
    return (f'<div class="mock" style="width:182px;padding:16px;border-radius:20px;'
            f'background:{p["surface"]}">'
            f'<div style="height:106px;border-radius:10px;'
            f'background:rgba(128,128,128,0.13);display:flex;align-items:center;'
            f'justify-content:center">{icon("ic_lock_folder", 52, None)}</div>'
            f'<div style="margin-top:8px;font-weight:700;font-size:13px;'
            f'color:{p["on_surface"]}">Private</div>'
            f'<div style="font-size:11px;color:{p["on_variant"]}">Locked</div></div>')


def sort_sheet(dark: bool) -> str:
    """The view-options sheet: layout, order, and Random with no direction to offer."""
    p = palette(dark)

    def header(text):
        return (f'<div style="margin:18px 0 10px;font-size:11px;letter-spacing:.07em;'
                f'text-transform:uppercase;color:{p["primary"]}">{text}</div>')

    def toggle3(*args):
        *choices, chosen = args
        cells = []
        last = len(choices) - 1
        for index, (label, glyph) in enumerate(choices):
            on = index == chosen
            radius = ("100px 0 0 100px" if index == 0
                      else "0 100px 100px 0" if index == last else "0")
            fill = p["secondary_container"] if on else "transparent"
            tint = p["on_secondary_container"] if on else p["on_variant"]
            cells.append(
                f'<div style="flex:1;height:40px;border:1px solid {p["outline"]};'
                f'border-radius:{radius};background:{fill};display:flex;gap:6px;'
                f'align-items:center;justify-content:center;color:{tint};font-size:12px">'
                f'{icon(glyph, 16, tint)}{label}</div>')
        return f'<div style="display:flex">{"".join(cells)}</div>'

    choices = "".join(
        f'<div style="display:flex;align-items:center;gap:12px;height:44px;'
        f'color:{p["on_surface"]};font-size:15px">'
        f'<span style="width:19px;height:19px;border-radius:50%;border:2px solid '
        f'{p["primary"] if on else p["on_variant"]};display:inline-block;position:relative">'
        + (f'<span style="position:absolute;inset:3px;border-radius:50%;'
           f'background:{p["primary"]}"></span>' if on else "")
        + f'</span>{label}</div>'
        for label, on in (("Newest", False), ("Name", False), ("Size", False),
                          ("Duration", False), ("Random", True))
    )

    return (f'<div class="mock" style="width:320px;border-radius:22px 22px 0 0;'
            f'padding:10px 20px 22px;'
            f'background:{p["surface"]}">'
            f'<div style="width:32px;height:4px;border-radius:2px;margin:0 auto;'
            f'background:{p["outline"]}"></div>'
            + header("View") + toggle3(("Masonry", "ic_view_masonry"),
                                       ("Grid", "ic_view_grid"),
                                       ("List", "ic_view_list"), 0)
            + header("Sort by") + choices
            + header("Order")
            + f'<div style="height:40px;border:1px solid {p["outline"]};border-radius:100px;'
              f'display:flex;gap:7px;align-items:center;justify-content:center;'
              f'color:{p["primary"]};font-size:13px">'
              f'{icon("ic_shuffle", 17, p["primary"])}Shuffle again</div>'
            + f'<div style="margin-top:12px;font-size:12px;color:{p["on_variant"]}">'
              f'Ascending and descending give way to this when the order is Random.</div>'
            + "</div>")


def settings_group(dark: bool) -> str:
    """One card of settings rows, to check the corners meet and the heading has air."""
    p = palette(dark)
    rows = [("Where clips come from", "Scan the whole device"),
            ("Also include short landscape videos", None),
            ("Longest landscape clip", "30 seconds")]

    cells = []
    for index, (title, summary) in enumerate(rows):
        first, last = index == 0, index == len(rows) - 1
        radius = (f'{"18px 18px" if first else "0 0"} {"18px 18px" if last else "0 0"}')
        body = f'<div style="font-size:15px;color:{p["on_surface"]}">{title}</div>'
        if summary:
            body += (f'<div style="font-size:12px;margin-top:2px;'
                     f'color:{p["on_variant"]}">{summary}</div>')
        widget = ""
        if summary is None:
            widget = (f'<div style="width:44px;height:26px;border-radius:100px;'
                      f'background:{p["secondary_container"]};position:relative">'
                      f'<span style="position:absolute;right:3px;top:3px;width:20px;'
                      f'height:20px;border-radius:50%;background:{p["primary"]}"></span>'
                      f'</div>')
        cells.append(
            f'<div style="background:{p["container"]};border-radius:{radius};'
            f'padding:12px 18px;min-height:58px;display:flex;align-items:center;'
            f'justify-content:space-between;gap:12px"><div>{body}</div>{widget}</div>')

    heading = (f'<div style="margin:0 0 8px 18px;font-size:11px;letter-spacing:.07em;'
               f'text-transform:uppercase;color:{p["primary"]}">Shorts feed</div>')
    return (f'<div class="mock" style="width:330px;padding:16px;border-radius:20px;'
            f'background:{p["surface"]}">{heading}{"".join(cells)}</div>')


# Height over width for eight stand-in clips: ordinary portrait, a couple of squarer ones
# and one landscape, so the masonry columns have something to stagger.
SHAPES = [1.78, 1.33, 1.78, 0.75, 1.6, 1.78, 1.0, 1.45]


def _tile(width: float, ratio: float, index: int, favourite: bool) -> str:
    """One thumbnail tile: scrim, duration, and the heart when it is marked."""
    height = width * ratio
    heart = ""
    if favourite:
        heart = (f'<span style="position:absolute;right:7px;bottom:6px;width:15px;'
                 f'height:15px">{icon("ic_heart_badge", 15, None)}</span>')
    return (
        f'<div style="width:{width:.0f}px;height:{height:.0f}px;border-radius:14px;'
        f'overflow:hidden;position:relative;'
        f'background:linear-gradient({150 + index * 21}deg,#3b4a63,#6d5340)">'
        f'<div style="position:absolute;left:0;right:0;bottom:0;height:46px;'
        f'background:linear-gradient(180deg,rgba(0,0,0,0),rgba(0,0,0,0.55))"></div>'
        f'<span style="position:absolute;left:8px;bottom:6px;font-size:11px;color:#fff;'
        f'text-shadow:0 1px 3px rgba(0,0,0,0.7)">0:{18 + index * 7}</span>{heart}</div>')


def shorts_wall(dark: bool, masonry: bool) -> str:
    """Two columns, at each clip's own shape or all at 4:5."""
    p = palette(dark)
    column_width = 146.0

    if masonry:
        # Same greedy placement the staggered layout manager uses: next tile goes to
        # whichever column is currently shorter.
        columns: list[list[str]] = [[], []]
        heights = [0.0, 0.0]
        for index, ratio in enumerate(SHAPES):
            target = 0 if heights[0] <= heights[1] else 1
            columns[target].append(_tile(column_width, ratio, index, index == 2))
            heights[target] += column_width * ratio + 6
        body = "".join(
            f'<div style="display:flex;flex-direction:column;gap:6px">{"".join(c)}</div>'
            for c in columns)
        inner = f'<div style="display:flex;gap:6px">{body}</div>'
    else:
        tiles = "".join(_tile(column_width, 1.25, i, i == 2) for i in range(6))
        inner = (f'<div style="display:flex;flex-wrap:wrap;gap:6px;'
                 f'width:{column_width * 2 + 6:.0f}px">{tiles}</div>')

    # Title, count under it, then the quick views — the order they appear on the tab.
    chips = []
    for index, label in enumerate(("All", "Recent", "Favourites", "Longest")):
        on = index == 0
        fill = p["secondary_container"] if on else "transparent"
        tint = p["on_secondary_container"] if on else p["on_variant"]
        border = "none" if on else f'1px solid {p["outline"]}'
        chips.append(
            f'<span style="height:30px;padding:0 14px;border-radius:100px;background:{fill};'
            f'border:{border};color:{tint};font-size:12px;display:inline-flex;'
            f'align-items:center;white-space:nowrap">{label}</span>')

    header = (f'<div style="margin-bottom:12px">'
              f'<div style="font-size:20px;font-weight:500;color:{p["on_surface"]};'
              f'line-height:1.2">Shorts</div>'
              f'<div style="font-size:12px;color:{p["on_variant"]};margin-top:1px">'
              f'128 videos</div>'
              f'<div style="display:flex;gap:8px;margin-top:12px">{"".join(chips)}</div>'
              f'</div>')
    return (f'<div class="mock" style="width:330px;padding:16px;border-radius:20px;'
            f'background:{p["surface"]};overflow:hidden">{header}{inner}</div>')


PAGE = """<title>Seamless player UI preview</title>
<style>
 body{{background:#0d0d10;color:#e8e8ee;font:14px system-ui,sans-serif;margin:0;padding:26px}}
 h2{{font-size:15px;font-weight:600;color:#c9c9d6;margin:30px 0 14px}}
 .frame{{background:linear-gradient(160deg,#243447,#4a3b52 60%,#6d5340);
        border-radius:14px;padding:22px;max-width:760px}}
 .bar{{margin-bottom:20px}}
 .bar span{{display:block;font-size:11px;color:rgba(255,255,255,.55);margin-bottom:5px}}
 .row{{display:flex;gap:26px;row-gap:24px;align-items:flex-start;flex-wrap:wrap}}
 figure{{margin:0;text-align:center}}
 /* The chrome mocks below are real layouts, which are start-aligned. Without this they
    would inherit the centring the icon figures want and read as a different design. */
 figure .mock{{text-align:left}}
 figcaption{{margin-top:9px;font-size:12px;color:#9a9aa8}}
 p.note{{color:#8a8a98;max-width:62em;line-height:1.5}}
 .bar svg{{width:100%;height:auto}}
 /* The level readouts only. Shown at roughly the size they occupy on a phone screen
    rather than at 3x pixels; both dimensions are given because an SVG with width:auto
    and a fixed height collapses to nothing. Scoped, or it would resize every icon on
    the page as well. */
 .levels svg{{height:210px;width:66px}}
</style>
<p class="note">Both of these are drawn in <code>onDraw</code>, so this page re-runs the same
arithmetic in SVG rather than screenshotting the app. Sizes are at 3x density, as on the
phone.</p>

<h2>Waveform timeline &mdash; what you have watched has shape, what is left does not</h2>
<div class="frame">{bars}</div>

<h2>Volume and brightness level</h2>
<div class="row levels">{huds}</div>

<h2>Toolbar actions &mdash; the frosted capsule</h2>
<div class="row">{toolbars}</div>

<h2>Speed badge, and the transport row at its real sizes</h2>
<div class="frame" style="display:flex;gap:40px;align-items:center;max-width:640px">
 {badge}{transport}
</div>

<h2>Icons</h2>
<div class="row">{icons}</div>

<h2>The favourite mark, over video and on a tile</h2>
<div class="row">{hearts}</div>

<h2>The floating navigation capsule, over the content it floats above</h2>
<div class="row">{navs}</div>

<h2>A locked folder, with the traced padlock at its own colours</h2>
<div class="row">{locks}</div>

<h2>Layout and order, in one sheet</h2>
<div class="row">{sheets}</div>

<h2>Settings, grouped into cards</h2>
<div class="row">{settings}</div>

<h2>The shorts wall &mdash; masonry, then the uniform grid</h2>
<div class="row">{shorts}</div>
"""


def main() -> int:
    bars = "".join(
        f'<div class="bar"><span>{int(f * 100)}%</span>{waveform(300, 30, f, phase)}</div>'
        for f, phase in ((0.0, 0.0), (0.18, 0.8), (0.5, 1.6), (0.83, 2.4), (1.0, 3.2))
    )

    huds = "".join(
        f'<figure>{svg}<figcaption>{caption}</figcaption></figure>'
        for svg, caption in (
            (level_hud(0.0, "0%", "ic_volume_off"), "muted"),
            (level_hud(0.35, "35%", "ic_volume_low"), "volume, low"),
            (level_hud(1.0, "100%", "ic_volume_up"), "volume, full"),
            (level_hud(1.0, "165%", "ic_volume_up", overflow=0.65), "boosted past 100%"),
            (level_hud(0.62, "62%", "ic_brightness"), "brightness"),
            (level_hud(0.0, "AUTO", "ic_brightness_auto"), "brightness, automatic"),
        )
    )

    toolbars = "".join(
        f'<figure>{toolbar(title, names, dark)}<figcaption>{caption}</figcaption></figure>'
        for title, names, dark, caption in (
            ("Library", ["ic_search", "ic_view_options", "ic_play_circle"], True,
             "library, dark"),
            ("Library", ["ic_search", "ic_view_options", "ic_play_circle"], False,
             "library, light"),
            ("VideoTapes", ["ic_search", "ic_view_options", "ic_shuffle"], True,
             "inside a folder"),
        )
    )

    icons = "".join(
        f'<figure><div style="padding:14px;background:#26262e;border-radius:14px">'
        f'{icon(name, size)}</div><figcaption>{caption}</figcaption></figure>'
        for name, size, caption in (
            ("ic_shorts", 40, "shorts tab"),
            ("ic_view_options", 40, "layout and order"),
            ("ic_view_masonry", 40, "masonry"),
            ("ic_arrow_up", 40, "ascending"),
            ("ic_arrow_down", 40, "descending"),
            ("ic_fast_forward", 40, "speed badge"),
            ("ic_tip", 40, "contextual tip"),
            ("ic_play_circle", 40, "last played"),
        )
    )

    def pair(render, caption):
        return "".join(
            f'<figure>{render(dark)}<figcaption>{caption}, {mode}</figcaption></figure>'
            for dark, mode in ((False, "light"), (True, "dark"))
        )

    hearts = "".join(
        f'<figure><div style="padding:16px;border-radius:14px;'
        f'background:linear-gradient(150deg,#3b4a63,#6d5340)">{icon(name, size, None)}</div>'
        f'<figcaption>{caption}</figcaption></figure>'
        for name, size, caption in (
            ("ic_heart_badge", 64, "marked"),
            ("ic_heart_burst_off", 64, "unmarked"),
            ("ic_heart_badge", 18, "on a tile, actual size"),
        )
    )

    navs = "".join(
        f'<figure>{nav_scene(tab, dark)}<figcaption>{tab}, {mode}</figcaption></figure>'
        for tab, dark, mode in (("library", False, "light"), ("shorts", True, "dark"),
                                ("settings", True, "dark"))
    )
    locks = pair(locked_tile, "locked folder")
    sheets = pair(sort_sheet, "view options")
    settings = pair(settings_group, "settings group")
    shorts = "".join(
        f'<figure>{shorts_wall(dark, masonry)}<figcaption>{caption}</figcaption></figure>'
        for dark, masonry, caption in (
            (False, True, "masonry, light"),
            (True, True, "masonry, dark"),
            (True, False, "uniform grid, dark"),
        )
    )

    os.makedirs(OUT, exist_ok=True)
    target = os.path.join(OUT, "ui.html")
    with open(target, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(PAGE.format(bars=bars, huds=huds, toolbars=toolbars,
                                 badge=speed_badge(), transport=transport_row(),
                                 icons=icons, navs=navs, locks=locks, sheets=sheets,
                                 settings=settings, shorts=shorts, hearts=hearts))
    print(f"wrote {os.path.relpath(target, ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
