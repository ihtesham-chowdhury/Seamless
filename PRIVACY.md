# Privacy Policy

**Seamless** (`com.seamless.player`)

_Last updated: 4 September 2026_

---

## The short version

Seamless collects nothing, sends nothing, and cannot send anything. It has no network
permission at all.

---

## What is collected

**Nothing.** There is no analytics, no crash reporting, no telemetry, no advertising, and
no account. No personal data is gathered, and none is transmitted, sold or shared, because
there is nowhere for it to go.

## Why this is verifiable rather than a promise

The app declares three permissions, and none of them can move data off the device:

- `READ_MEDIA_VIDEO` on Android 13 and newer — to list and play your videos
- `READ_EXTERNAL_STORAGE` on Android 12 and older — the same thing, older name
- `MODIFY_AUDIO_SETTINGS` — to attach the audio effect used for volume above 100%

The third is a *normal* permission: granted at install, no prompt, and it gives access to
no data whatsoever. In particular it is **not** `RECORD_AUDIO`, which would allow listening
to the microphone and which this app does not request and does not want.

It does **not** declare `android.permission.INTERNET`. Without that permission Android
blocks all network access at the operating-system level, so the app is incapable of sending
data anywhere regardless of what it might contain.

The source code is public and the app is built from that source. You do not have to take
this document's word for any of it.

## Why the app reads your videos

To list and play them. That is the entire function of a video player. Seamless reads the
video library through Android's MediaStore so it can show your folders, draw thumbnails,
and play the files you choose.

## What is stored on your device

Settings and playback state, kept in the app's private storage:

- Your preferences — theme, accent colour, gesture and playback settings
- Which folders you selected, hid, or locked
- Resume positions for long videos

This never leaves the device. Uninstalling the app deletes all of it.

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
