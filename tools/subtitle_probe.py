#!/usr/bin/env python3
"""Run the subtitle matcher's pure-Kotlin half over real file names, on this machine.

Most of what decides whether a subtitle search works happens before any network call: a file
name is read into a title, a year and an episode number, and a list of candidates is scored
against it. None of that touches Android. It is also the part that is invisible until it is
wrong, and "wrong" here means the search asks for the wrong film and comes back with nothing —
which on a phone looks exactly like a broken network.

So this compiles a small main() against the classes tools/typecheck.py has already produced and
actually runs [ReleaseName] and [SubtitleScoring]. It is the only way to exercise them without a
device, and it catches the class of bug that a type-checker cannot: a regex that throws, a
substring index that is off, a title that parses into nonsense.

Usage:  python tools/typecheck.py && python tools/subtitle_probe.py
        python tools/subtitle_probe.py "Some.Other.File.2019.1080p.mkv"
"""

from __future__ import annotations

import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import typecheck  # noqa: E402  (path has to be set first)

ROOT = typecheck.ROOT
BUILD = os.path.join(ROOT, "build", "probe")
CLASSES = os.path.join(typecheck.BUILD, "classes")

# Names chosen to cover every shape the parser claims to handle, plus the ones that have
# historically broken parsers: a title containing a year, a title containing a source word, a
# camera clip with no structure at all, and a name that is nothing but an extension.
DEFAULT_NAMES = [
    "The.Matrix.1999.1080p.BluRay.x264-AMIABLE.mkv",
    "Dune.Part.Two.2024.2160p.WEB-DL.DDP5.1.Atmos.HDR.HEVC-CMRG.mkv",
    "Breaking.Bad.S01E03.1080p.BluRay.x265-RARBG.mkv",
    "Game of Thrones - 3x09 - The Rains of Castamere.mkv",
    "[SubsPlease] Frieren - 07 (1080p) [F1A2B3C4].mkv",
    "Blade Runner 2049 (2017) [1080p] [YTS.AM].mp4",
    "1917.2019.720p.WEBRip.x264.AAC-[YTS.MX].mp4",
    "VID_20240817_204411.mp4",
    "20240817_204411.mp4",
    "Interstellar.mkv",
    "movie.mkv",
    ".mkv",
    "a.b",
    "Extended.mkv",
    "WEB-DL.mkv",
    "2160p.mkv",
    "S01E01.mkv",
    "The Matrix (1999).mkv",
    "Spider-Man.Across.the.Spider-Verse.2023.1080p.WEBRip.mkv",
    "Mission.Impossible.Dead.Reckoning.Part.One.2023.2160p.mkv",
]

PROBE = '''
import com.seamless.player.data.subtitle.ReleaseName
import com.seamless.player.data.subtitle.SubtitleCandidate
import com.seamless.player.data.subtitle.SubtitleScoring
import com.seamless.player.data.subtitle.SubtitleLanguages
import com.seamless.player.data.subtitle.SubtitleNames
import com.seamless.player.data.subtitle.SubtitleOrigin

/**
 * Exercises the parser and the scorer the way a search does, and reports rather than throws.
 *
 * Every name is run inside its own try so one failure does not hide the rest — the point of the
 * run is to see all of them.
 */
fun main(args: Array<String>) {
    var failures = 0
    args.forEach { name ->
        try {
            val release = ReleaseName.parse(name)
            val shape = buildString {
                append(release.title.ifBlank { "<blank>" })
                release.year?.let { append("  year=").append(it) }
                if (release.isEpisode) append("  s").append(release.season).append("e").append(release.episode)
                release.resolution?.let { append("  ").append(it) }
                release.source?.let { append("  ").append(it) }
                release.group?.let { append("  -").append(it) }
            }
            println("  parse   " + name)
            println("      ->  " + shape)
            println("      q   '" + release.queryText + "'")

            // Score a stand-in candidate both ways round, which is where the arithmetic lives.
            val candidate = SubtitleCandidate(
                providerId = "probe",
                fileId = "1",
                fileName = name,
                language = "en",
                release = name.replace('.', ' '),
                downloads = 1200,
                hashMatch = false,
                trusted = true,
                hearingImpaired = false,
                featureTitle = release.title,
                featureYear = release.year,
            )
            val ranked = SubtitleScoring.rank(listOf(candidate, candidate.copy(hashMatch = true)), release, "en")
            println("      sc  name=" + ranked.last().score + "%  hash=" + ranked.first().score + "%")

            // The naming rules, on the same name.
            val srt = SubtitleNames.sidecarName(name, "en", "srt")
            check(SubtitleNames.belongsTo(name, srt)) { "sidecar '" + srt + "' does not match its own video" }
            println("      sub " + srt + "  lang=" + SubtitleNames.tagsOf(name, srt).language)
        } catch (error: Throwable) {
            failures++
            println("  FAIL    " + name)
            println("      !!  " + error.javaClass.name + ": " + error.message)
            error.stackTrace.take(4).forEach { println("          at " + it) }
        }
        println()
    }

    // Track ids as Media3 actually hands them back. A merged media item puts its source index in
    // front of every track id, so the id this app gives a subtitle is never the one it gets back,
    // and reading the origin off the front of it classed every sidecar as embedded.
    try {
        val given = SubtitleOrigin.trackId(SubtitleOrigin.BESIDE, 0)
        val saved = SubtitleOrigin.trackId(SubtitleOrigin.SAVED, 2)
        listOf(given, "1:" + given, "3:" + saved, "1:eng", "0:1", null).forEach { id ->
            println("  origin  '" + id + "' -> " + SubtitleOrigin.of(id) + "  own=" + SubtitleOrigin.ownId(id))
        }
        check(SubtitleOrigin.of("1:" + given) == SubtitleOrigin.BESIDE) { "merged folder id read as embedded" }
        check(SubtitleOrigin.of("3:" + saved) == SubtitleOrigin.SAVED) { "merged saved id read as embedded" }
        check(SubtitleOrigin.ownId("1:" + given) == given) { "ownId kept the merge prefix" }
        check(SubtitleOrigin.of("1:eng") == SubtitleOrigin.EMBEDDED) { "a container track was mistaken for ours" }
        check(SubtitleOrigin.of(null) == SubtitleOrigin.EMBEDDED) { "a missing id was mistaken for ours" }
    } catch (error: Throwable) {
        failures++
        println("  FAIL    origin: " + error.javaClass.simpleName + ": " + error.message)
    }
    println()

    listOf("en", "eng", "English", "bn", "bangla", "Bengali", "pt-BR", "", "zzz").forEach {
        println("  lang    '" + it + "' -> " + SubtitleLanguages.normalise(it) +
            "  '" + SubtitleLanguages.displayName(it) + "'")
    }

    println()
    println(if (failures == 0) "probe: all names parsed" else "probe: " + failures + " FAILED")
    if (failures > 0) System.exit(1)
}
'''


def main() -> int:
    if not os.path.isdir(CLASSES):
        print("probe: run tools/typecheck.py first — it produces the classes this runs against")
        return 1

    java = typecheck.find_java()
    separator = ";" if os.name == "nt" else ":"
    os.makedirs(BUILD, exist_ok=True)

    source = os.path.join(BUILD, "Probe.kt")
    with open(source, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(PROBE)

    out = os.path.join(BUILD, "classes")

    # Only the app's own classes and the Kotlin runtime. The parser and the scorer touch
    # nothing else, and the full dependency list is several hundred jars — long enough that
    # Windows refuses to launch a process with it on the command line.
    stdlib = [jar for jar in typecheck.dependency_jars()
              if os.path.basename(jar).startswith("kotlin-stdlib")]
    classpath = [CLASSES] + stdlib

    def quote(value: str) -> str:
        return '"' + value.replace("\\", "\\\\") + '"'

    argfile = os.path.join(BUILD, "args.txt")
    with open(argfile, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("-classpath " + quote(separator.join(classpath)) + "\n")
        handle.write("-d " + quote(out) + "\n")
        handle.write("-nowarn\n")
        handle.write("-jvm-target 17\n")
        handle.write(quote(source) + "\n")

    compile_result = subprocess.run(
        [java, "-cp", separator.join(typecheck.compiler_classpath()),
         "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "@" + argfile],
        capture_output=True, text=True,
    )
    noise = [line for line in (compile_result.stdout + compile_result.stderr).splitlines()
             if line.strip() and not line.startswith("info:") and "unable to find" not in line]
    if compile_result.returncode != 0:
        print("probe: the harness would not compile")
        print("\n".join(noise))
        return 1

    names = sys.argv[1:] or DEFAULT_NAMES
    run = subprocess.run(
        [java, "-cp", separator.join([out] + classpath), "ProbeKt", *names],
        capture_output=True, text=True,
    )
    print(run.stdout.rstrip())
    if run.stderr.strip():
        print(run.stderr.rstrip())
    return run.returncode


if __name__ == "__main__":
    sys.exit(main())
