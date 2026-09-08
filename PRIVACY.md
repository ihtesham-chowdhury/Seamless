# Privacy Policy

**Seamless** (`com.seamless.player`)

_Last updated: 8 September 2026_

---

## The short version

Seamless collects nothing about you and has no account, no analytics and no advertising.

It makes exactly one kind of network request, and only when you tap a button asking for it:
looking up a subtitle for the video you are watching. That feature is off by default and
cannot work until you supply your own subtitle-provider key. Everything else about the app
works with the device in aeroplane mode, for ever.

**What changed:** versions before 0.2.0 declared no network permission at all. 0.2.0 declares
`INTERNET` for the subtitle lookup described below. If you would rather have the earlier
guarantee, 0.1.0 remains available and nothing else in it depends on this.

---

## What is collected

**Nothing.** There is no analytics, no crash reporting, no telemetry, no advertising
identifier, and no account. No profile is built, nothing is sold, nothing is shared. There is
no code in this app capable of any of that, which is a stronger statement than a policy.

## What is sent, when, and to whom

One feature, one destination, one trigger.

**The trigger.** You open the subtitle panel on a video and tap **Find subtitles**. Nothing
happens on any other occasion: not when the app starts, not when a video opens, not when the
library is scanned, not on a timer, and not in the background. There is no prefetch and no
warm-up.

**The destination.** `api.opensubtitles.com`, over HTTPS. Nowhere else.

**What is sent:**

- The title, year, season number and episode number as parsed from the *file name*
- A 64-bit fingerprint of the video file — computed from its size and 128 KiB of its
  contents, and used because it identifies the exact encode and therefore correctly timed
  subtitles
- The languages you want, which are your preferred language plus your device's language
- Your API key, and — only if you chose to sign in — a session token obtained from it

**What is not sent:** the video itself, any part of its picture or sound, its full path, your
device identifiers, your other files, your library, or anything at all about the rest of the
app's use.

If you never turn the feature on, or never supply a key, the app makes no requests of any
kind.

## Why this is verifiable rather than a promise

The app declares four permissions:

- `READ_MEDIA_VIDEO` on Android 13 and newer — to list and play your videos
- `READ_EXTERNAL_STORAGE` on Android 12 and older — the same thing, older name
- `MODIFY_AUDIO_SETTINGS` — to attach the audio effect used for volume above 100%
- `INTERNET` — for the subtitle lookup above, and nothing else

`MODIFY_AUDIO_SETTINGS` is a *normal* permission: granted at install, no prompt, and it gives
access to no data whatsoever. In particular it is **not** `RECORD_AUDIO`, which would allow
listening to the microphone and which this app does not request and does not want.

`INTERNET` is the one that deserves scrutiny, so here is where to look:

- The whole of the app's networking is one file, `app/src/main/java/com/seamless/player/util/Http.kt`.
  It refuses anything that is not HTTPS, refuses to run on the main thread, and caps every
  response.
- The only code that calls it is `data/subtitle/OpenSubtitlesProvider.kt`, reached only
  through `data/subtitle/SubtitleSearch.kt`, which is entered only from a tap.
- `tools/verify.py` fails the build if any second file opens a network connection, and if the
  manifest ever declares `INTERNET` without `usesCleartextTraffic="false"`. That check runs in
  CI on every push.
- The manifest sets `usesCleartextTraffic="false"`, so plain HTTP is blocked by the platform.

The source code is public and the app is built from that source. You do not have to take this
document's word for any of it — and the checks above mean a future change cannot quietly make
this section wrong.

## Why the app reads your videos

To list and play them. That is the entire function of a video player. Seamless reads the
video library through Android's MediaStore so it can show your folders, draw thumbnails,
and play the files you choose.

## What is stored on your device

Settings and playback state, kept in the app's private storage:

- Your preferences — theme, accent colour, gesture and playback settings
- Which folders you selected, hid, or locked
- Resume positions for long videos
- Which clips you marked as favourites
- Subtitle files you downloaded or added, and which subtitle track each video was left on
- Your subtitle-provider API key, and your provider username and password if you entered them

This never leaves the device. Uninstalling the app deletes all of it.

The last item is kept in a separate file from everything else specifically so that it can be
excluded from Android's cloud backup and device-to-device transfer, which it is — see
`res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml`. Your other settings *are*
backed up, because a restored theme and sort order is what anyone would want; a restored
credential is not this app's decision to make.

It is stored in the app's private storage, which other apps cannot read, but it is not
encrypted. If that matters to you, do not enter a password — the feature works anonymously,
at a lower daily download limit.

## Subtitle files

A subtitle downloaded for you is saved twice where possible: once in the app's private
storage, and once beside the video itself if you have granted Seamless access to that folder,
named the way other players expect so they can use it too. The second copy is a convenience
and can be deleted like any other file. **Settings → Subtitles → Clear downloaded subtitles**
removes the app's own copies.

Reading subtitle files that are already on your storage requires you to point Seamless at the
folder or the file, through Android's own picker. Android grants nothing more than what you
pick, and the app cannot widen it. See [docs/SUBTITLES.md](docs/SUBTITLES.md).

## Changing your files

Seamless can rename, move and delete videos when you ask it to. Android requires your
explicit confirmation for each such operation through a system dialog the app cannot
bypass. Deleting a video is permanent.

## Folder locking

Folders can be locked behind your device's fingerprint, face unlock or PIN.

**No PIN or biometric data is ever stored by this app.** Authentication is handled entirely
by Android's own `BiometricPrompt`, and the app is only told whether it succeeded.

Please understand what this feature is: it hides folders from view inside Seamless. **It is
not encryption.** The files stay where they are on your storage and remain readable by any
other app with media access.

## Children

The app contains no ads, no purchases, and no content of its own — it plays files already
on your device.

## Changes to this policy

Any change will be committed to this file in the public repository, with the date above
updated. The history is public.

## Contact

Please open an issue in the project's issue tracker.
