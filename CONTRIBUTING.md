# Contributing

Thanks for looking. This started as a personal project built because no existing player
did offline short-form video well, so it has opinions. They are all negotiable — but the
reasoning behind them is written down, and it is worth reading before proposing a change.

---

## Before you start

For anything larger than a bug fix, **open an issue first**. It is cheaper to disagree
about an idea than about a finished pull request.

Two documents will save you time:

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — why the player pool exists, why there is
  no local database, why the two gesture maps differ
- [docs/BUILDING.md](docs/BUILDING.md) — the toolchain, and a table of version-specific
  traps that have each cost a build cycle

---

## Things that are deliberate

Please do not "fix" these without discussion. Each was a decision, not an oversight.

| Decision | Reasoning |
|---|---|
| No local database of the video list | MediaStore *is* an indexed system database. Mirroring it adds staleness and saves nothing. Width, height, duration and rotation all come from its columns, so no file is ever opened to build the list |
| The shorts feed uses `TextureView`, not `SurfaceView` | A SurfaceView's separate window layer is created and destroyed asynchronously; inside `ViewPager2` a recycled page could render into a dying surface, which showed up as black clips |
| The feed is its own activity, not a tab | A tab cannot go properly immersive with a bottom bar over the video |
| Vertical and horizontal gestures swap between the two players | In the feed the vertical axis belongs to the pager, so brightness and volume move to horizontal |
| No `INTERNET` permission | It is what makes the privacy claim verifiable rather than a promise. Anything needing the network is out of scope |
| Kotlin is compiled by AGP's built-in support | Adding `org.jetbrains.kotlin.android` alongside AGP 9 is a hard build failure |

---

## Style

Match the surrounding code. In practice:

- Explain **why**, not what. A comment restating the code earns nothing; a comment
  explaining why a `TextureView` is used there is worth keeping.
- Keep the data layer free of Android UI types. `Prefs` and `MediaLibrary` know nothing
  about Media3 or resources; that is what `ui/common/` adapters are for.
- Kotlin official style, 4-space indent, ~100 column lines.
- New user-facing text goes in `res/values/strings.xml`. No hardcoded strings in layouts
  or code.

## Verifying a change

There is no test suite yet — adding one would be a welcome contribution. Until then:

0. `python tools/verify.py` — a second, not a full build. It catches the mistakes that
   otherwise cost you a compile cycle: a renamed string, a layout id that no longer exists,
   an extension function used without importing it, a listener interface that grew a member
   and was not implemented everywhere, a dotted style name missing `parent=""`. It
   understands wiring, not types, so it supplements the compiler rather than replacing it.
1. `python tools/typecheck.py` — the type half of the same idea, for anyone who cannot
   run Gradle. It invokes the Kotlin compiler directly against `android.jar` and the
   dependency jars in the Gradle cache, generating stand-ins for `R`, `BuildConfig` and
   the view-binding classes from the same resources AGP reads. It will not build an APK
   and does not replace step 3, but it catches wrong types and calls to library methods
   that do not exist. Needs a JDK and an installed SDK platform; nothing else.
2. `python tools/svg_to_vector.py <source.svg> <target.xml> [size_dp]` for any other SVG
   that has to become a drawable — do not retype path data by hand.
   `python tools/icon_from_svg.py` if you changed `art/icon.svg`, then
   `python tools/icon_preview.py` and `python tools/ui_preview.py` if you touched the
   launcher icon, the custom-drawn player views, or any icon they show. Both write an HTML page to
   `build/preview/`. The icon one reads the real drawables; the UI one is a *port* of the
   `onDraw` maths into SVG, so it can drift from the Kotlin — it is for judging shape and
   proportion at the extremes, not for proving correctness.
3. `./gradlew assembleDebug` must pass.
4. Install on a real device. Emulator video decode is not representative, and much of this
   app is about decode timing.
5. If you touched the feed, verify the thing it exists for: record the screen at 60 fps and
   count black frames between clips. The target is one. "Feels fast" is not a measurement.

## Commits and pull requests

- One logical change per commit, present tense: "Fix black frame on recycled pages".
- Say in the PR *why* the change is needed and how you verified it on-device.
- If you changed behaviour, update the relevant doc in the same PR.

## Licence

Contributions are accepted under the [GPL-3.0](LICENSE), the project's licence. By opening
a pull request you agree your work is distributed under it.
