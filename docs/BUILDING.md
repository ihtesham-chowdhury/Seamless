# Building

**Toolchain:** AGP 9.3.2 · Gradle 9.5.0 · JDK 17 bytecode target ·
`minSdk 29` (Android 10) · `compileSdk` / `targetSdk` 36.

Kotlin is compiled by **AGP's built-in Kotlin support**, which 9.0 enables by default.
There is deliberately no `org.jetbrains.kotlin.android` plugin anywhere in this project —
applying it alongside AGP 9 fails with `AgpWithBuiltInKotlinAppliedCheck`. That also means
the Kotlin version comes from AGP rather than being pinned here.

---

## In Android Studio

1. **File → Open**, select the folder containing `settings.gradle.kts`, and open it as a
   project. Do not use "Import", and do not open the `app` subfolder.
2. Let the first Gradle sync run. It downloads Gradle, AGP and the AndroidX and Media3
   artifacts — several minutes on a cold cache, and it needs internet.
3. If Studio offers to **install missing SDK platform API 36**, accept.
4. Plug in a device with **USB debugging** enabled and press **Run**.

## From the command line

The wrapper scripts are committed, so:

```bash
./gradlew assembleDebug     # build the APK
./gradlew installDebug      # build and install on a connected device
./gradlew assembleRelease   # minified release build
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

### If `gradlew` is missing

It is committed to the repository, but if you are working from a tree that predates it,
generate it once with any local Gradle:

```bash
gradle wrapper --gradle-version 9.5.0
```

Then commit `gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar`. **The jar is
required** — CI and F-Droid both invoke the wrapper.

---

## Handing someone a build to test

A tester needs one file: a **debug APK**. It carries the local debug signature, so it
installs on any device where "install unknown apps" is allowed for whatever they open it
from, and it needs no keystore of yours.

**In Android Studio:** Build → Build Bundle(s) / APK(s) → Build APK(s). The notification
that appears when it finishes has a *locate* link. The file is at:

```
app/build/outputs/apk/debug/app-debug.apk
```

**From the command line**, the same file:

```bash
./gradlew assembleDebug
```

Rename it before sending it anywhere — `app-debug.apk` tells the person receiving it
nothing about what it is or which build it came from:

```bash
cp app/build/outputs/apk/debug/app-debug.apk seamless-0.1.0-debug.apk
```

To hang it off a GitHub release rather than sending the file directly:

1. `git tag -a v0.1.0 -m "0.1.0" && git push origin v0.1.0`
2. On GitHub: **Releases → Draft a new release**, choose the tag, and drag the renamed APK
   into the attachments box.

CI already builds a debug APK on every push and uploads it as a workflow artifact, so
anyone with repository access can download one without building anything themselves.

A **release** APK (`./gradlew assembleRelease`) is smaller and minified, but with no
`keystore.properties` present it comes out unsigned, and Android will not install an
unsigned APK. For one friend testing on one phone, debug is the right answer; signing
matters when you publish.

---

## Signing a release

Release builds are unsigned unless you provide a keystore. Nothing about signing is
committed, and `.gitignore` refuses `*.jks`, `*.keystore` and `keystore.properties`.

Create a keystore once:

```bash
keytool -genkey -v -keystore seamless-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias seamless
```

Then create `keystore.properties` **in the project root**:

```properties
storeFile=/absolute/path/to/seamless-release.jks
storePassword=…
keyAlias=seamless
keyPassword=…
```

`app/build.gradle.kts` picks it up automatically when present and falls back to an unsigned
release when it is not, so the build still works for anyone who just wants to compile the
source.

> **Keep the keystore and its passwords safe and backed up.** Google Play and F-Droid both
> identify an app by its signing key. Lose it and you cannot ship an update to existing
> users — you would have to publish under a new application id, and everyone would have to
> reinstall.

---

## Version-specific traps

These have all bitten this project at least once. They are recorded so they do not bite
twice.

| Trap | Symptom | Why |
|---|---|---|
| Applying `org.jetbrains.kotlin.android` | Sync fails, `AgpWithBuiltInKotlinAppliedCheck` | AGP 9 compiles Kotlin itself; a second Kotlin plugin is a hard conflict |
| Bumping `androidx.core:core-ktx` past 1.18.0 | `checkDebugAarMetadata` fails | 1.19.0 declares `minCompileSdk 37`; raise `compileSdk` first |
| A dotted style name with no `parent` | `resource style/… not found`, `failed linking references` | AAPT2 infers a parent by dropping the last name segment. Use `parent=""` to opt out |
| Missing `buildConfig = true` | `Unresolved reference: BuildConfig` | Off by default since AGP 8 |
| Referencing `exo_*` drawables from Media3 | Lint `PrivateResource`, or a missing resource | The Media3 AAR marks nothing public. Use `@+id/` for its ids and ship your own drawables |

Before bumping **any** AndroidX dependency, check what compileSdk its AAR demands:

```bash
unzip -p ~/.gradle/caches/**/<artifact>.aar META-INF/com/android/build/gradle/aar-metadata.properties
```

---

## Project layout

```
app/src/main/
  java/com/seamless/player/
    data/        Models, MediaStore access, preferences, the shorts query
    ui/
      library/   Folder list, folder contents, selection and file operations
      shorts/    The feed: pager, player pool, preload strategy, gestures
      player/    The ordinary player and its gesture layer
      common/    Locking, media operations, theming, format description
      settings/  Preferences screen
    util/        Thumbnails, brightness/volume, background work
  res/           Layouts, drawables, themes, the custom player controls
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for why the pieces are shaped this way.
