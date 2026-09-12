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

CI already builds a debug APK on every push and uploads it as a workflow artifact, so
anyone with repository access can download one without building anything themselves.

**A debug APK is for testing and never for publishing.** It is signed with the Android debug
key, which is the same on every machine on earth and ships with the SDK — so anyone can build
an "update" to it that a phone will accept. It also has minification off, `BuildConfig.DEBUG`
true (which is what silences logging in release builds), and is roughly three times the size.
Worse than any of that: a phone will not replace a debug-signed install with a properly signed
one, so the first person you hand it to has to uninstall before they can ever have a real
build. Publishing means `assembleRelease`, and `assembleRelease` means a keystore.

---

## Signing a release

Release builds are unsigned unless you provide a keystore. Nothing about signing is
committed, and `.gitignore` refuses `*.jks`, `*.keystore` and `keystore.properties`.

Create a keystore once — and *once* is the whole point, because this key becomes the app's
identity for the rest of its life. `keytool` comes with the JDK; on Windows it is not usually
on the PATH, but Android Studio ships one at
`C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe`.

```bash
keytool -genkey -v -keystore seamless-release.jks \
  -keyalg RSA -keysize 4096 -validity 10000 -alias seamless
```

4096 rather than the more commonly copied 2048: signing happens once per release, so the extra
work costs nothing measurable, and the key has to outlive every guess anyone is making now
about how long 2048 stays respectable. 10000 days is a little over 27 years, comfortably past
the 2033 floor Google Play imposes on upload keys.

Keep the file **outside the repository**. `.gitignore` refuses `*.jks` already, but a key that
was never in the working tree cannot be committed by an unlucky `git add -A` from a future
version of the ignore file.

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

One thing to know before the first APK goes out, because it is easier to plan for than to
undo. If Seamless later joins Google Play, Play App Signing re-signs uploads with a key Google
holds, and the resulting install carries a different signature from the APKs published here.
The two cannot update each other: someone who installed from GitHub and later installs from
Play has to uninstall first, and vice versa. That is not a reason to avoid either — F-Droid
builds and signs its own way too — only a reason to expect it and to say so on the release
page rather than to be asked about it.

---

## Cutting a release

In order. Steps 1 to 4 are this repository; 5 to 8 are the APK; 9 and 10 are GitHub.

**1. Everything is committed and the checks pass.**

```bash
python tools/verify.py && python tools/typecheck.py && python tools/subtitle_probe.py
```

**2. `versionCode` and `versionName` in `app/build.gradle.kts` are the version you mean.**
`versionCode` must go up by at least one every single time, and never down: it is the only
number Android compares when deciding whether one APK may replace another. `versionName` is
the one humans read and is compared by nothing.

**3. `CHANGELOG.md` has a section for it** — `## [x.y.z] — YYYY-MM-DD` rather than
`[Unreleased]` — with a fresh empty `[Unreleased]` above it and the two link references at
the bottom of the file updated.

**4. `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` exists.** One file per
versionCode, named by the number and not by the name, **500 characters maximum** — F-Droid
truncates silently past that. Only tagged versions need one, because only a tagged version
is ever built.

**5. Build the release APK.**

```bash
./gradlew assembleRelease
```

Not `clean assembleRelease`. On Windows, `clean` deletes `app/build`, and Windows refuses to
delete a directory any process has open — which Android Studio does, continuously, for a
project it has loaded. It fails with `Unable to delete directory` and names a folder that
looks unrelated to what you asked for. It also achieves nothing: the release variant's own
outputs are the only thing that could be stale, and Gradle rebuilds those anyway.

Output: `app/build/outputs/apk/release/app-release.apk`. With no `keystore.properties` it
comes out unsigned and named `app-release-unsigned.apk` — if that is what you have, stop and
go back to [Signing a release](#signing-a-release).

**6. Check the APK is what you think it is** before it leaves the machine. Both tools are in
`$ANDROID_HOME/build-tools/<version>/`:

```bash
aapt2 dump badging app/build/outputs/apk/release/app-release.apk | head -3
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

The first line must show the versionCode and versionName from step 2. The certificate's
SHA-256 must be **the same as the last release's** — a different key means no existing
install can be updated, only uninstalled and replaced. On the first signed release there is
nothing to compare it with, so write the fingerprint down instead: it is what every later
release is checked against.

**7. Install it over the previous version on a real device** and open it once. This is the
step that catches a ProGuard rule that was needed and is not there: `isMinifyEnabled` is on
for release and off for debug, so a release build can fail in ways nothing before this point
would have shown.

**8. Rename it.** `app-release.apk` says nothing to whoever downloads it:

```bash
cp app/build/outputs/apk/release/app-release.apk seamless-0.2.3.apk
```

**9. Tag it, and push the tag.** The tag is what the changelog links point at and what
F-Droid watches:

```bash
git push origin main
git tag -a v0.2.3 -m "Seamless 0.2.3"
git push origin v0.2.3
```

**10. Publish the release.** On GitHub: **Releases → Draft a new release**, choose the tag
that is already there, paste the changelog section as the notes, and attach the renamed APK.
Or in one command, if you have the `gh` CLI:

```bash
gh release create v0.2.3 seamless-0.2.3.apk --title "Seamless 0.2.3" --notes-file notes.md
```

> The keystore is the release. Everything else here can be done again; a lost signing key
> cannot, and it ends the app's ability to update itself for everyone who already has it.
> Back up the `.jks` and its passwords somewhere that survives this computer.

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
| `gradlew clean` on Windows | `Unable to delete directory 'app\build'` | Windows will not delete a directory another process has open, and Android Studio holds one for every loaded project. Drop `clean`, or close Studio |
| `clipToOutline` on an ancestor of a `TextureView` | The video goes black mid-gesture while everything else keeps drawing | A TextureView is composited from a hardware layer of its own, and a clip against a rounded outline is not applied to it on every driver. Paint the corners over the children instead |
| A `.ps1` with an em dash, no BOM | `Unexpected token '}'`, `string is missing the terminator` | Windows PowerShell 5.1 decodes a BOM-less file as the ANSI codepage. An em dash's last byte becomes U+201D, which PowerShell accepts as a closing quote. Keep helper scripts ASCII |

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
