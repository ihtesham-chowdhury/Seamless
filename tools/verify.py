#!/usr/bin/env python3
"""
Fast static checks for things that would otherwise only surface as a build failure.

A full Gradle build takes minutes; these checks take under a second and catch the
mistakes that actually happen in practice — a renamed string, a layout id that no longer
exists, an extension function used without importing it, an interface that grew a member.

    python tools/verify.py

Exits non-zero if anything looks wrong. This is a supplement to compiling, not a
replacement: it understands resource wiring and imports, not types or logic.
"""

from __future__ import annotations

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app/src/main/res")
SRC = os.path.join(ROOT, "app/src/main/java")

RES_KINDS = ["string", "array", "id", "xml", "layout", "drawable", "menu", "color",
             "style", "mipmap", "dimen"]


def camel(name: str) -> str:
    parts = name.split("_")
    return parts[0] + "".join(p.capitalize() for p in parts[1:])


def layout_for_binding(binding: str) -> str:
    base = re.sub(r"Binding$", "", binding)
    return re.sub(r"(?<!^)(?=[A-Z])", "_", base).lower()


def read(path: str) -> str:
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def collect_resources():
    declared = {kind: set() for kind in RES_KINDS}
    ids_by_layout: dict[str, set[str]] = {}
    includes: dict[str, list[tuple[str, str]]] = {}
    styles: list[tuple[str, str, bool]] = []

    for dirpath, _, files in os.walk(RES):
        folder = os.path.basename(dirpath)
        for name in files:
            if not name.endswith(".xml"):
                continue
            text = read(os.path.join(dirpath, name))
            stem = name[:-4]
            if folder.startswith("values"):
                declared["string"] |= set(re.findall(r'<string name="([^"]+)"', text))
                declared["array"] |= set(re.findall(r'<string-array name="([^"]+)"', text))
                declared["color"] |= set(re.findall(r'<color name="([^"]+)"', text))
                declared["dimen"] |= set(re.findall(r'<dimen name="([^"]+)"', text))
                for match in re.finditer(r'<style\s+name="([^"]+)"([^>]*?)>', text):
                    declared["style"].add(match.group(1).replace(".", "_"))
                    styles.append((name, match.group(1), "parent=" in match.group(2)))
            elif folder.startswith("layout"):
                declared["layout"].add(stem)
                ids_by_layout[stem] = set(
                    re.findall(r'android:id="@\+id/([A-Za-z0-9_]+)"', text))
                includes[stem] = re.findall(
                    r'<include[^>]*?android:id="@\+id/([A-Za-z0-9_]+)"[^>]*?'
                    r'layout="@layout/([A-Za-z0-9_]+)"', text)
            elif folder.startswith("drawable"):
                declared["drawable"].add(stem)
            elif folder.startswith("color"):
                # A file in res/color is a colour state list, named by its file name. Missing
                # this made every @color/ reference to one look undeclared.
                declared["color"].add(stem)
            elif folder.startswith("mipmap"):
                declared["mipmap"].add(stem)
            elif folder == "xml":
                declared["xml"].add(stem)
            elif folder == "menu":
                declared["menu"].add(stem)
            declared["id"] |= set(re.findall(r'android:id="@\+id/([A-Za-z0-9_]+)"', text))

    return declared, ids_by_layout, includes, styles


def kotlin_files() -> list[str]:
    found = []
    for dirpath, _, files in os.walk(SRC):
        found += [os.path.join(dirpath, f) for f in files if f.endswith(".kt")]
    return found


def check_resource_references(declared, ids_by_layout, includes) -> list[str]:
    problems = []
    for path in kotlin_files():
        text = read(path)
        name = os.path.basename(path)

        for match in re.finditer(
                r"(?<!android\.)\bR\.(" + "|".join(RES_KINDS) + r")\.([A-Za-z0-9_]+)", text):
            if match.group(2) not in declared[match.group(1)]:
                problems.append(f"{name}: R.{match.group(1)}.{match.group(2)} is not declared")

        bindings = set(re.findall(r"\b([A-Z][A-Za-z0-9]*Binding)\b", text))
        available: set[str] = set()
        for binding in bindings:
            layout = layout_for_binding(binding)
            available |= {camel(i) for i in ids_by_layout.get(layout, set())} | {"root"}
            available |= {camel(i) for i, _ in includes.get(layout, [])}
        if bindings:
            for ref in set(re.findall(r"\b(?:binding|b|current)\.([a-z][A-Za-z0-9]*)", text)):
                if ref not in available:
                    problems.append(f"{name}: binding.{ref} is not in the bound layout")

        for view_id in set(re.findall(r"findViewById[^(]*\(R\.id\.([A-Za-z0-9_]+)\)", text)):
            if view_id not in declared["id"]:
                problems.append(f"{name}: findViewById(R.id.{view_id}) is not declared")
    return problems


def check_xml_references(declared) -> list[str]:
    problems = []
    kinds = "string|drawable|array|menu|color|layout|mipmap|dimen"
    for dirpath, _, files in os.walk(RES):
        for name in files:
            if not name.endswith(".xml"):
                continue
            text = read(os.path.join(dirpath, name))
            for kind, ref in re.findall(r'"@(' + kinds + r')/([A-Za-z0-9_]+)"', text):
                if ref not in declared[kind]:
                    problems.append(f"{name}: @{kind}/{ref} is not declared")
    return problems


def check_style_parents(declared, styles) -> list[str]:
    """A dotted style name with no parent makes AAPT2 infer one by dropping the last
    segment; if that style does not exist, resource linking fails."""
    problems = []
    names = {name for _, name, _ in styles}
    for file_name, name, has_parent in styles:
        if "." not in name or has_parent:
            continue
        implied = name.rsplit(".", 1)[0]
        if implied not in names:
            problems.append(
                f'{file_name}: <style name="{name}"> has no parent, so AAPT2 will look for '
                f'"{implied}", which does not exist. Add parent="".')
    return problems


def check_interface_implementations() -> list[str]:
    """Adding a member to a listener interface without implementing it everywhere."""
    problems = []
    pairs = [
        ("ui/player/PlayerGestureLayout.kt", "Listener", ["ui/player/PlayerActivity.kt"]),
        ("ui/shorts/GestureOverlayLayout.kt", "Listener", ["ui/shorts/ShortsAdapter.kt"]),
        ("ui/shorts/ShortsAdapter.kt", "Host", ["ui/shorts/ShortsActivity.kt"]),
    ]
    base = os.path.join(SRC, "com/seamless/player")
    for declaring, interface, implementers in pairs:
        declaring_path = os.path.join(base, declaring)
        if not os.path.exists(declaring_path):
            continue
        match = re.search(r"interface " + interface + r"\s*\{(.*?)\n    \}",
                          read(declaring_path), re.S)
        if not match:
            continue
        required = set(re.findall(r"fun ([a-zA-Z0-9_]+)\(", match.group(1)))
        for implementer in implementers:
            path = os.path.join(base, implementer)
            if not os.path.exists(path):
                continue
            present = set(re.findall(r"override fun ([a-zA-Z0-9_]+)\(", read(path)))
            for missing in sorted(required - present):
                problems.append(
                    f"{os.path.basename(implementer)}: does not implement "
                    f"{interface}.{missing}()")
    return problems


def check_imports() -> list[str]:
    """A project symbol used from another package without importing it. This is the one
    that keeps happening with extension functions, which read like member calls."""
    problems = []
    files = kotlin_files()

    declarations: dict[str, tuple[str, str, str]] = {}
    for path in files:
        text = read(path)
        package = (re.search(r"^package\s+([\w.]+)", text, re.M) or [None, ""])[1]
        pattern = (r"^(?:@\w+\s+)*(?:public |internal |private )?"
                   r"(fun|val|var|object|class|interface|enum class|data class|sealed class)"
                   r"\s+(?:[\w.<>?, ]+\.)?(\w+)")
        for match in re.finditer(pattern, text, re.M):
            if match.start() == 0 or text[match.start() - 1] == "\n":
                declarations.setdefault(
                    match.group(2), (package, match.group(1), os.path.basename(path)))

    type_kinds = ("object", "class", "interface", "enum class", "data class", "sealed class")
    for path in files:
        text = read(path)
        package = (re.search(r"^package\s+([\w.]+)", text, re.M) or [None, ""])[1]
        imports = set(re.findall(r"^import\s+([\w.]+)", text, re.M))
        wildcards = {i[:-2] for i in imports if i.endswith(".*")}
        imported = {i.rsplit(".", 1)[-1] for i in imports}
        body = re.sub(r"^import .*$", "", text, flags=re.M)

        for name, (owner, kind, declared_in) in declarations.items():
            if owner == package or os.path.basename(path) == declared_in:
                continue
            if name in imported or owner in wildcards or f"{owner}.{name}" in body:
                continue
            escaped = re.escape(name)
            used = (re.search(r"(?<![\w.])" + escaped + r"\s*\(", body)
                    or re.search(r"\." + escaped + r"\s*\(", body)
                    or (kind in type_kinds
                        and re.search(r"(?<![\w.])" + escaped + r"(?![\w])", body)))
            if used:
                problems.append(
                    f"{os.path.basename(path)}: '{name}' ({kind} in {owner}) "
                    f"is used but not imported")
    return problems


def check_summary_providers() -> list[str]:
    """A preference cannot have both a summary provider and a summary set in code.

    Preference.setSummary throws IllegalStateException outright when a SummaryProvider is
    installed, so `app:useSimpleSummaryProvider="true"` in settings.xml plus `summary = ...`
    in the fragment is not a cosmetic conflict — it takes the whole screen down the moment it
    is opened. That is exactly how the settings tab started crashing, and nothing else in
    this file would have caught it: the XML is valid, the Kotlin compiles, and the two halves
    only meet at runtime.
    """
    settings = os.path.join(RES, "xml", "settings.xml")
    if not os.path.exists(settings):
        return []
    text = read(settings)

    # Keys whose preference installs the simple summary provider. Attribute order is not
    # guaranteed, so match within one element rather than assuming key comes first.
    provided = set()
    for element in re.findall(r"<[A-Za-z]+Preference[^>]*?/>", text, re.S):
        if "useSimpleSummaryProvider" not in element:
            continue
        key = re.search(r'android:key="([^"]+)"', element)
        if key:
            provided.add(key.group(1))

    problems = []
    for path in kotlin_files():
        source = read(path)
        name = os.path.basename(path)
        for key in sorted(provided):
            for match in re.finditer(r'"%s"' % re.escape(key), source):
                # Far enough to cover an apply { } block, close enough not to reach the next
                # preference's wiring.
                window = source[match.end():match.end() + 500]
                if re.search(r"\bsummary\s*=", window):
                    problems.append(
                        f'{name}: "{key}" has a summary provider in settings.xml and is '
                        f"assigned a summary here; setSummary throws on such a preference")
                    break
    return problems


def check_network_isolation() -> list[str]:
    """All networking through one file, and no plain HTTP.

    This app's whole position is that it does not talk to anything unless asked, and the
    manifest says so at length. A claim like that is only worth the code that keeps it true, so:
    exactly one file may open a connection, and if the INTERNET permission is declared at all
    then cleartext must be switched off. Both are the kind of thing that gets broken by a
    well-meaning one-line addition somewhere else entirely.
    """
    problems = []
    allowed = "Http.kt"
    # A call into the one networking file. Plain substrings rather than a
    # pattern: there is nothing here a regex would express better.
    http_calls = ("Http.get(", "Http.postJson(", "Http.download(")
    openers = (
        r"\bHttpURLConnection\b", r"\bHttpsURLConnection\b", r"\bopenConnection\b",
        r"\bjava\.net\.Socket\b", r"\bOkHttpClient\b", r"\bURL\(",
    )
    for path in kotlin_files():
        name = os.path.basename(path)
        if name == allowed:
            continue
        text = read(path)
        for pattern in openers:
            if re.search(pattern, text):
                problems.append(
                    f"{name}: opens a network connection itself; everything must go through "
                    f"util/{allowed}, which enforces https, a size cap and no main thread")
                break

    # Networking stays behind the subtitle providers. The player, the library and the feed
    # have no business making a request, and an import here would be the first step of one
    # arriving somewhere it cannot be reasoned about.
    callers = "data/subtitle"
    for path in kotlin_files():
        if callers.replace("/", os.sep) in path or os.path.basename(path) == allowed:
            continue
        text = read(path)
        if any(call in text for call in http_calls):
            problems.append(
                f"{os.path.basename(path)}: calls util/Http directly; requests belong in a "
                f"subtitle provider, behind SubtitleSearch")

    manifest = read(os.path.join(ROOT, "app/src/main/AndroidManifest.xml"))
    if "android.permission.INTERNET" in manifest:
        if 'android:usesCleartextTraffic="false"' not in manifest:
            problems.append(
                'AndroidManifest.xml: INTERNET is declared without '
                'android:usesCleartextTraffic="false"')
        for rules in ("dataExtractionRules", "fullBackupContent"):
            if rules not in manifest:
                problems.append(
                    f"AndroidManifest.xml: INTERNET is declared but android:{rules} is not set, "
                    f"so the subtitle provider's credentials would be included in backups")
    return problems


def check_control_characters() -> list[str]:
    """No stray control bytes in anything that ships.

    Earned its place the day it was written. A patch script building Kotlin source through a
    shell heredoc had its backslash escapes collapsed one layer too far, and a regex meant to
    contain the two characters backslash-b ended up containing one backspace byte instead. The
    file still parsed, the check it belonged to still reported success, and it had silently
    stopped testing anything. Nothing about that was visible in a diff.

    Tabs and newlines are fine; everything else in the C0 range is not.
    """
    problems = []
    allowed = {chr(9), chr(10), chr(13)}
    suffixes = (".kt", ".kts", ".xml", ".py", ".md", ".pro")
    roots = (SRC, RES, os.path.join(ROOT, "tools"), os.path.join(ROOT, "docs"))
    for root in roots:
        for dirpath, _, files in os.walk(root):
            for name in files:
                if not name.endswith(suffixes):
                    continue
                path = os.path.join(dirpath, name)
                for number, line in enumerate(read(path).split(chr(10)), 1):
                    stray = [c for c in line if ord(c) < 32 and c not in allowed]
                    if stray:
                        codes = ", ".join("0x%02x" % ord(c) for c in sorted(set(stray)))
                        problems.append(f"{name}:{number}: control character(s) {codes}")
    return problems


def check_shadowed_builders() -> list[str]:
    """A local function that shadows the receiver it was meant to call.

    Written the day it cost two rounds of testing. Inside a buildMap block, a helper declared
    as `fun put(code, vararg names)` shadows MutableMap.put, so the `put(it, code)` in its body
    called itself: infinite recursion in a static initialiser, throwing a StackOverflowError.
    An Error is not an Exception, so it walked past every handler on the way out and every later
    touch of that object failed with NoClassDefFoundError instead. Nothing about the symptom
    pointed at the cause, and neither the compiler nor a type-check has anything to say about it.

    The rule is narrow on purpose: a *local* function — indented past a class body — named after
    a method of one of the receivers a builder block supplies. Renaming it is always the fix, so
    a false positive costs one word.
    """
    trap = ("put", "add", "set", "remove", "get", "clear", "append", "plusAssign")
    problems = []
    for path in kotlin_files():
        for number, line in enumerate(read(path).split(chr(10)), 1):
            stripped = line.lstrip()
            if not stripped.startswith("fun "):
                continue
            if len(line) - len(stripped) < 8:
                continue  # a member of a class, which shadows nothing
            name = stripped[4:].split("(")[0].strip()
            if name in trap:
                problems.append(
                    f"{os.path.basename(path)}:{number}: local fun '{name}' shadows the "
                    f"builder method of the same name; give it a different name")
    return problems


def check_dead_strings(declared) -> list[str]:
    used: set[str] = set()
    for base in (SRC, RES):
        for dirpath, _, files in os.walk(base):
            for name in files:
                if not name.endswith((".kt", ".xml")):
                    continue
                text = read(os.path.join(dirpath, name))
                used |= set(re.findall(r"R\.string\.([A-Za-z0-9_]+)", text))
                used |= set(re.findall(r"@string/([A-Za-z0-9_]+)", text))
    dead = sorted(declared["string"] - used - {"app_name"})
    return [f"unused string: {name}" for name in dead]


def check_night_colour_parity() -> list[str]:
    """Colours defined only for dark mode, and accent colours defined for only one.

    Two different rules, because the two cases are not symmetrical. A colour that exists in
    values-night but not in values simply does not resolve in light mode, which is a build
    failure — that check applies to every file. The reverse is usually fine: `black` needs no
    dark variant. Accent colours are the exception, since they are meant to come in pairs and
    a missing one means a swatch that silently stops changing with the theme.
    """
    problems = []
    night_dir = os.path.join(RES, "values-night")
    if not os.path.isdir(night_dir):
        return problems

    def colours(path):
        return set(re.findall(r'<color name="([^"]+)"', read(path))) \
            if os.path.exists(path) else set()

    for name in sorted(os.listdir(night_dir)):
        if not name.endswith(".xml"):
            continue
        night = colours(os.path.join(night_dir, name))
        day = colours(os.path.join(RES, "values", name))
        for missing in sorted(night - day):
            problems.append(
                f"values-night/{name}: '{missing}' is not defined in values/{name}, "
                "so it will not resolve in light mode")

    accents = os.path.join(RES, "values/accent_colors.xml")
    night_accents = os.path.join(night_dir, "accent_colors.xml")
    if os.path.exists(accents) and os.path.exists(night_accents):
        for missing in sorted(colours(accents) - colours(night_accents)):
            problems.append(
                f"accent_colors.xml: '{missing}' has no values-night counterpart")
    return problems


def main() -> int:
    declared, ids_by_layout, includes, styles = collect_resources()

    sections = [
        ("Resource references", check_resource_references(declared, ids_by_layout, includes)),
        ("XML resource references", check_xml_references(declared)),
        ("Style parents", check_style_parents(declared, styles)),
        ("Interface implementations", check_interface_implementations()),
        ("Imports", check_imports()),
        ("Light/dark colour parity", check_night_colour_parity()),
        ("Preference summaries", check_summary_providers()),
        ("Network isolation", check_network_isolation()),
        ("Control characters", check_control_characters()),
        ("Shadowed builders", check_shadowed_builders()),
    ]
    warnings = [("Unused strings", check_dead_strings(declared))]

    failed = False
    for title, problems in sections:
        if problems:
            failed = True
            print(f"\n{title}:")
            for problem in sorted(set(problems)):
                print(f"  {problem}")
        else:
            print(f"ok  {title}")

    for title, problems in warnings:
        if problems:
            print(f"\n{title} (warning only):")
            for problem in sorted(set(problems)):
                print(f"  {problem}")
        else:
            print(f"ok  {title}")

    if failed:
        print("\nFAILED")
        return 1
    print("\nAll checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
