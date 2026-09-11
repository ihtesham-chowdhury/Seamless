#!/usr/bin/env python3
"""Type-check the app's Kotlin against the real Android and AndroidX classes.

Why this exists
---------------
Gradle cannot run in the environment this project is written in — the JVM's
`Selector.open()` fails there, which takes the Gradle daemon with it — so the code is
written in one place and compiled in another. `verify.py` catches the mistakes that are
visible in the text of the sources. This catches the rest: wrong types, bad overrides,
methods that do not exist on the library class, nullability slips.

It does not build an APK and is not a substitute for one. It runs the Kotlin compiler
directly, with:

  * android.jar from the installed platform,
  * every dependency's classes.jar, unpacked from the Gradle cache,
  * generated stand-ins for `R`, `BuildConfig` and the view-binding classes, which AGP
    would normally produce.

The stubs are the interesting part. They are derived from the same resources the real
generator reads, so a reference to a view that is not in a layout, or a resource that does
not exist, fails here for the same reason it would fail in Android Studio.

Usage:  python tools/typecheck.py
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "app", "src", "main")
RES = os.path.join(APP, "res")
SRC = os.path.join(APP, "java")
PACKAGE = "com.seamless.player"

HOME = os.path.expanduser("~")
CACHE = os.path.join(HOME, ".gradle", "caches", "modules-2", "files-2.1")
BUILD = os.path.join(ROOT, "build", "typecheck")

ANDROID_NS = "http://schemas.android.com/apk/res/android"

# Dependencies, as "group/artifact/version". Kept in step with app/build.gradle.kts by
# check_dependencies_match() below, which fails loudly if the two drift apart.
DEPENDENCIES = [
    ("androidx.core", "core-ktx", "1.18.0"),
    ("androidx.appcompat", "appcompat", "1.8.0"),
    ("com.google.android.material", "material", "1.14.0"),
    ("androidx.constraintlayout", "constraintlayout", "2.2.2"),
    ("androidx.fragment", "fragment-ktx", "1.9.0"),
    ("androidx.recyclerview", "recyclerview", "1.4.0"),
    ("androidx.viewpager2", "viewpager2", "1.1.0"),
    ("androidx.preference", "preference-ktx", "1.2.1"),
    ("androidx.documentfile", "documentfile", "1.1.0"),
    ("androidx.biometric", "biometric", "1.1.0"),
    ("androidx.media3", "media3-exoplayer", "1.11.0"),
    ("androidx.media3", "media3-ui", "1.11.0"),
]


def fail(message: str) -> None:
    print(f"typecheck: {message}", file=sys.stderr)
    sys.exit(2)


# ---- locating the tools ----

def find_java() -> str:
    candidates = [
        os.environ.get("JAVA_HOME", "") and
        os.path.join(os.environ["JAVA_HOME"], "bin", "java.exe"),
        r"C:\Program Files\Android\Android Studio\jbr\bin\java.exe",
        shutil.which("java"),
    ]
    for candidate in candidates:
        if candidate and os.path.exists(candidate):
            return candidate
    fail("no JDK found; set JAVA_HOME")


def find_android_jar() -> str:
    roots = [
        os.environ.get("ANDROID_HOME"),
        os.environ.get("ANDROID_SDK_ROOT"),
        os.path.join(HOME, "AppData", "Local", "Android", "Sdk"),
        os.path.join(HOME, "Library", "Android", "sdk"),
        os.path.join(HOME, "Android", "Sdk"),
    ]
    for root in roots:
        platforms = root and os.path.join(root, "platforms")
        if not platforms or not os.path.isdir(platforms):
            continue
        # Highest API level available; the app compiles against the newest anyway.
        for name in sorted(os.listdir(platforms), reverse=True):
            jar = os.path.join(platforms, name, "android.jar")
            if os.path.exists(jar):
                return jar
    fail("no android.jar found; set ANDROID_HOME")


def newest_in_cache(pattern: str, where: str) -> str | None:
    """The newest jar matching a filename pattern anywhere under a cache directory."""
    found = []
    for base, _, files in os.walk(where):
        for name in files:
            if name.endswith(("-sources.jar", "-javadoc.jar")):
                continue
            if re.fullmatch(pattern, name):
                # Sort by the version directory, parsed, rather than by the path string —
                # "2.10.0" must beat "2.9.0", and the hash directory in between makes a
                # plain string sort meaningless anyway.
                version = os.path.basename(os.path.dirname(os.path.dirname(
                    os.path.join(base, name))))
                key = tuple(int(p) if p.isdigit() else 0 for p in version.split("."))
                found.append((key, os.path.join(base, name)))
    return sorted(found)[-1][1] if found else None


def compiler_classpath() -> list[str]:
    parts = []
    for group, artifact, pattern in [
        ("org.jetbrains.kotlin", "kotlin-compiler-embeddable",
         r"kotlin-compiler-embeddable-.*\.jar"),
        ("org.jetbrains.kotlin", "kotlin-stdlib", r"kotlin-stdlib-\d.*\.jar"),
        # The compiler reflects over its own argument classes to parse the command line.
        ("org.jetbrains.kotlin", "kotlin-reflect", r"kotlin-reflect-\d.*\.jar"),
        ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm",
         r"kotlinx-coroutines-core-jvm-.*\.jar"),
        # Needed by the bytecode backend, which writes @NotNull onto generated members.
        ("org.jetbrains", "annotations", r"annotations-\d.*\.jar"),
    ]:
        where = os.path.join(CACHE, group, artifact)
        if not os.path.isdir(where):
            fail(f"{group}:{artifact} is not in the Gradle cache; run a Gradle build once")
        jar = newest_in_cache(pattern, where)
        if not jar:
            fail(f"no jar for {group}:{artifact}")
        parts.append(jar)
    return parts


# ---- dependency classes ----

def check_dependencies_match() -> None:
    """Guards against this file quietly falling behind the real build."""
    gradle = open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
    declared = set(re.findall(r'implementation\("([^:]+):([^:]+):([^"]+)"\)', gradle))
    if declared != set(DEPENDENCIES):
        missing = declared - set(DEPENDENCIES)
        extra = set(DEPENDENCIES) - declared
        lines = ["DEPENDENCIES in tools/typecheck.py is out of step with build.gradle.kts"]
        lines += [f"  only in build.gradle.kts: {d}" for d in sorted(missing)]
        lines += [f"  only in typecheck.py:     {d}" for d in sorted(extra)]
        fail("\n".join(lines))


def dependency_jars() -> list[str]:
    """Unpacks each dependency's classes from its AAR, and collects plain jars as they are.

    Transitive dependencies are picked up too: everything of the right name already in the
    cache is included, which is coarser than a real resolution but errs towards having a
    class available rather than missing.

    Only the newest version of each artifact is kept, as Gradle's resolution would. The cache
    holds every version anything on this machine ever fetched, and with two versions of one
    library on the classpath the compiler takes whichever comes first — which once meant
    RecyclerView 1.1.0, from before bindingAdapterPosition existed, instead of the 1.4.0 the
    build uses.
    """
    out = os.path.join(BUILD, "libs")
    os.makedirs(out, exist_ok=True)

    # (group, artifact, file name with its version taken out) -> (version, path)
    newest: dict[tuple[str, str, str], tuple[tuple, str]] = {}
    for base, _, files in os.walk(CACHE):
        # Laid out group/artifact/version/hash/file.
        version_dir = os.path.dirname(base)
        version = os.path.basename(version_dir)
        artifact_dir = os.path.dirname(version_dir)
        owner = (os.path.basename(os.path.dirname(artifact_dir)), os.path.basename(artifact_dir))
        for name in files:
            if name.endswith(("-sources.jar", "-javadoc.jar")) or not name.endswith((".jar", ".aar")):
                continue
            key = owner + (name.replace(version, "", 1),)
            candidate = (version_key(version), os.path.join(base, name))
            if key not in newest or candidate[0] > newest[key][0]:
                newest[key] = candidate

    jars = []
    for _, path in sorted(newest.values(), key=lambda entry: entry[1]):
        name = os.path.basename(path)
        if name.endswith(".jar"):
            jars.append(path)
            continue
        extracted = os.path.join(out, name[:-4] + ".jar")
        if not os.path.exists(extracted):
            try:
                with zipfile.ZipFile(path) as archive:
                    with archive.open("classes.jar") as source:
                        with open(extracted, "wb") as target:
                            shutil.copyfileobj(source, target)
            except (KeyError, zipfile.BadZipFile):
                continue
        jars.append(extracted)
    return jars


def version_key(version: str) -> tuple:
    """Orders versions near enough as Gradle does: numerically, and a release after its own
    alpha, beta or release candidate."""
    numbers, _, qualifier = version.partition("-")
    return (tuple(int(p) if p.isdigit() else 0 for p in numbers.split(".")),
            qualifier == "", qualifier)


# ---- generated sources: R, BuildConfig, view bindings ----

def resource_names() -> dict[str, set[str]]:
    """Every resource the R class would carry, by kind."""
    names: dict[str, set[str]] = {
        k: set() for k in
        ("id", "layout", "string", "drawable", "mipmap", "color", "style", "menu",
         "array", "xml", "attr", "dimen", "bool", "integer", "plurals", "anim", "raw")
    }
    for base, _, files in os.walk(RES):
        folder = os.path.basename(base).split("-")[0]
        for name in files:
            path = os.path.join(base, name)
            stem = os.path.splitext(name)[0]

            # Folders where each file is one resource. color/ matters as much as drawable/: a
            # colour state list file is R.color.<name>, exactly like a <color> in values/.
            if folder in ("layout", "drawable", "mipmap", "menu", "xml", "anim", "animator",
                          "raw", "color", "font"):
                names.setdefault(folder, set()).add(stem)

            if not name.endswith(".xml"):
                continue
            try:
                tree = ET.parse(path)
            except ET.ParseError as error:
                fail(f"{path}: {error}")
            root = tree.getroot()

            # Ids declared anywhere with @+id/.
            for element in root.iter():
                for value in element.attrib.values():
                    match = re.fullmatch(r"@\+id/([A-Za-z0-9_]+)", value or "")
                    if match:
                        names["id"].add(match.group(1))

            # values/ files declare resources by tag.
            if folder == "values":
                for child in root:
                    kind = child.tag
                    if kind == "item":
                        kind = child.get("type", "")
                    if kind == "declare-styleable":
                        continue
                    key = {"string-array": "array", "integer-array": "array"}.get(kind, kind)
                    if key in names and child.get("name"):
                        names[key].add(child.get("name").replace(".", "_"))
    return names


def write_r_class(target: str, names: dict[str, set[str]]) -> None:
    lines = [f"package {PACKAGE}", "", "object R {"]
    for kind in sorted(names):
        if not names[kind]:
            continue
        lines.append(f"    object {kind} {{")
        for name in sorted(names[kind]):
            lines.append(f"        const val {name}: Int = 0")
        lines.append("    }")
    lines.append("}")
    lines += ["", "object BuildConfig {", "    const val DEBUG: Boolean = true", "}"]
    with open(target, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(lines) + "\n")


def camel(name: str) -> str:
    head, *rest = name.split("_")
    return head + "".join(part.capitalize() for part in rest)


def binding_name(layout: str) -> str:
    return "".join(part.capitalize() for part in layout.split("_")) + "Binding"


def view_type(tag: str) -> str:
    """The fully qualified type view binding would give a view of this tag."""
    if "." in tag:
        return tag
    if tag == "view" or tag == "merge" or tag == "include":
        return "android.view.View"
    if tag in ("fragment", "View"):
        return "android.view.View"
    return f"android.widget.{tag}" if tag not in _VIEW_PACKAGE else _VIEW_PACKAGE[tag]


_VIEW_PACKAGE = {
    "View": "android.view.View",
    "ViewGroup": "android.view.ViewGroup",
    "ViewStub": "android.view.ViewStub",
    "SurfaceView": "android.view.SurfaceView",
    "TextureView": "android.view.TextureView",
    "WebView": "android.webkit.WebView",
}


def write_bindings(target: str) -> None:
    """Generates one class per layout, with a field per id, typed by the XML tag.

    This is what makes `binding.somethingWrong` a compile error here rather than a surprise
    in Android Studio.
    """
    layout_dir = os.path.join(RES, "layout")
    lines = ["package " + PACKAGE + ".databinding", ""]

    for name in sorted(os.listdir(layout_dir)):
        if not name.endswith(".xml"):
            continue
        layout = name[:-4]
        tree = ET.parse(os.path.join(layout_dir, name))
        root = tree.getroot()

        fields: dict[str, str] = {}
        for element in root.iter():
            raw = element.get(f"{{{ANDROID_NS}}}id")
            match = re.fullmatch(r"@\+?id/([A-Za-z0-9_]+)", raw or "")
            if not match:
                continue
            if element.tag == "include":
                # An <include> exposes the included layout's binding, not a view.
                included = (element.get("layout") or "").split("/")[-1]
                fields[camel(match.group(1))] = binding_name(included)
            else:
                fields[camel(match.group(1))] = view_type(element.tag)

        root_type = view_type(root.tag)
        if root.tag == "merge":
            parent = root.get("{http://schemas.android.com/tools}parentTag")
            root_type = view_type(parent) if parent else "android.view.View"

        lines.append(f"class {binding_name(layout)} {{")
        lines.append(f"    val root: {root_type} = null!!")
        for field, kind in sorted(fields.items()):
            if field == "root":
                continue
            lines.append(f"    val {field}: {kind} = null!!")
        lines.append("    companion object {")
        lines.append("        @JvmStatic fun inflate(inflater: android.view.LayoutInflater): "
                     f"{binding_name(layout)} = null!!")
        lines.append("        @JvmStatic fun inflate(inflater: android.view.LayoutInflater, "
                     "parent: android.view.ViewGroup?, attach: Boolean): "
                     f"{binding_name(layout)} = null!!")
        lines.append("    }")
        lines.append("}")
        lines.append("")

    with open(target, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(lines) + "\n")


# ---- running the compiler ----

def main() -> int:
    check_dependencies_match()

    java = find_java()
    android_jar = find_android_jar()

    generated = os.path.join(BUILD, "generated")
    classes = os.path.join(BUILD, "classes")
    os.makedirs(generated, exist_ok=True)
    os.makedirs(classes, exist_ok=True)

    write_r_class(os.path.join(generated, "R.kt"), resource_names())
    write_bindings(os.path.join(generated, "Bindings.kt"))

    classpath = [android_jar] + dependency_jars()
    separator = ";" if os.name == "nt" else ":"

    # Several hundred jars overflow Windows' command-line limit, so the arguments go in a
    # file. Backslashes have to be escaped: the compiler reads argfiles with Java string
    # rules, and an unescaped Windows path turns into mojibake.
    def quote(value: str) -> str:
        return '"' + value.replace("\\", "\\\\") + '"'

    argfile = os.path.join(BUILD, "args.txt")
    with open(argfile, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("-classpath " + quote(separator.join(classpath)) + "\n")
        handle.write("-d " + quote(classes) + "\n")
        handle.write("-nowarn\n")
        handle.write("-jvm-target 17\n")
        # The app opts in globally; without this every Media3 call is an error here.
        handle.write("-opt-in=androidx.media3.common.util.UnstableApi\n")
        handle.write(quote(SRC) + "\n")
        handle.write(quote(generated) + "\n")

    # The compiler's own classpath is separate from the one it compiles against.
    command = [
        java, "-cp", separator.join(compiler_classpath()),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "@" + argfile,
    ]

    print(f"typecheck: {len(classpath)} jars on the classpath, android.jar from "
          f"{os.path.basename(os.path.dirname(android_jar))}")
    result = subprocess.run(command, capture_output=True, text=True)
    output = result.stdout + result.stderr

    # The stubs stand in for generated code, so complaints *about* them are noise.
    interesting = [
        line for line in output.splitlines()
        if line.strip()
        and "unable to find" not in line
        and not line.startswith("info:")
        and "/generated/" not in line.replace("\\", "/")
    ]

    if not interesting:
        print("typecheck: no errors")
        return 0

    print("\n".join(interesting))
    print(f"\ntypecheck: {sum(1 for l in interesting if ' error:' in l or l.startswith('error:'))} "
          f"error line(s)")
    return 1


if __name__ == "__main__":
    sys.exit(main())
