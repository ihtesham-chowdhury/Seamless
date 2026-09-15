# Releasing

Two destinations, with genuinely different requirements. F-Droid is the easier and better
fit for this project; Google Play asks for more paperwork.

---

## Before either

- [ ] Bump `versionCode` **and** `versionName` in `app/build.gradle.kts`. `versionCode` is
      an integer that must increase with every published build; both stores reject a build
      that reuses one.
- [ ] Add a section to [CHANGELOG.md](../CHANGELOG.md).
- [ ] Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` — one short
      paragraph, since this is what F-Droid shows in the update list.
- [ ] Capture screenshots into
      `fastlane/metadata/android/en-US/images/phoneScreenshots/` as `1.png`, `2.png`, …
- [ ] Tag the commit: `git tag -a v0.1.0 -m "0.1.0"` and push the tag.

Capturing a screenshot from a connected device (on the phone, then pulled: PowerShell's `>`
rewrites binary output and corrupts the PNG):

```bash
adb shell screencap -p /sdcard/1.png
adb pull /sdcard/1.png
```

---

## F-Droid

F-Droid builds from source on its own infrastructure. It never accepts an APK, which is
the point: what is published is provably what is in the repository.

**This project already satisfies the hard requirements:**

- Every dependency is free software — AndroidX, Material Components, Media3, biometric.
- No Google Play Services, no proprietary SDK, no analytics, no ads.
- `dependenciesInfo { includeInApk = false }` is set in `app/build.gradle.kts`. F-Droid
  rejects the signed dependency-metadata blob AGP otherwise embeds, because it is opaque
  and breaks reproducibility.
- GPL-3.0, an OSI-approved licence, with the full text in `LICENSE`.

**To submit:**

1. Push this repository somewhere public with a tagged release.
2. Fork [`fdroiddata`](https://gitlab.com/fdroid/fdroiddata).
3. Add `metadata/com.seamless.player.yml` along these lines:

   ```yaml
   Categories:
     - Multimedia
   License: GPL-3.0-only
   SourceCode: https://github.com/ihtesham-chowdhury/Seamless
   IssueTracker: https://github.com/ihtesham-chowdhury/Seamless/issues

   RepoType: git
   Repo: https://github.com/ihtesham-chowdhury/Seamless.git

   Builds:
     - versionName: 0.1.0
       versionCode: 1
       commit: v0.1.0
       subdir: app
       gradle:
         - yes

   AutoUpdateMode: Version
   UpdateCheckMode: Tags
   CurrentVersion: 0.1.0
   CurrentVersionCode: 1
   ```

4. Open a merge request. Expect review comments; they are usually about reproducibility.

The `fastlane/metadata/` directory in this repository is not decoration — F-Droid reads the
title, descriptions, changelogs and screenshots straight out of it.

---

## Google Play

Play distributes **your** signed build, so the signing key in
[BUILDING.md](BUILDING.md) matters here.

```bash
./gradlew bundleRelease     # Play wants an .aab, not an .apk
```

Additional requirements beyond F-Droid:

- **A developer account** — one-off registration fee, and identity verification.
- **A privacy policy at a public URL.** [PRIVACY.md](../PRIVACY.md) is the text; it needs
  hosting somewhere linkable. GitHub Pages, or the raw file URL, both work.
- **A Data safety declaration.** Short, but not empty. Nothing is collected in the
  background; the optional subtitle lookup sends a video's title and a file fingerprint (and
  an OpenSubtitles sign-in, if the user adds one) to OpenSubtitles when the user asks, and that
  has to be declared. Media is read with `READ_MEDIA_VIDEO`, purely to list and play the user's
  own videos, which also needs the Photo and video permissions declaration.
- **Store assets** — a 512×512 icon, a 1024×500 feature graphic, and at least two
  phone screenshots. The launcher icon in this repository is an adaptive vector and will
  need exporting to PNG at 512×512.
- **Target API compliance.** Play enforces a minimum `targetSdk` that rises annually; this
  project targets 36, which is current.
- **Content rating** questionnaire.

### A note on the permission

Both stores will ask why the app reads media. The answer is that it is a video player: it
lists and plays videos already on the device. Since 0.2.0 it also holds `INTERNET`, used only
for the optional subtitle lookup, which does nothing until the user switches it on and adds
their own key. Say so plainly in the listing; [PRIVACY.md](../PRIVACY.md) has the detail.
