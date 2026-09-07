<div align="center">

# Seamless

**A local video player for Android that turns a folder of short vertical videos into a
continuous, swipeable feed — and still plays ordinary long-form video properly.**

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Min SDK](https://img.shields.io/badge/minSdk-29-brightgreen.svg)](#requirements)
[![Built with Media3](https://img.shields.io/badge/player-Media3%201.11-orange.svg)](https://developer.android.com/media/media3)

</div>

---

## Why this exists

Offline short-form video is badly served. Every player tried while building this fell into
one of two camps: fast players that could only open one file at a time, or folder players
with a visible black frame between every clip.

That gap is not a tuning problem. A conventional player owns a single `ExoPlayer` and, for
each file, runs `setMediaItem → prepare → play`. The MediaCodec configure plus the
first-keyframe decode inside that costs roughly 100–300 ms even from local storage, and no
amount of cache tweaking removes it, because the cost is decoder initialisation rather
than I/O.

Seamless keeps a small pool of players alive and uses Media3's `DefaultPreloadManager` to
buffer the neighbouring clips while the current one is still playing. Swiping changes which
surface is visible, so the transition costs about a frame.

The name is the goal.

---

## Screenshots

> **Not yet captured.** Add PNGs to
> `fastlane/metadata/android/en-US/images/phoneScreenshots/` named `1.png`, `2.png`, … and
> F-Droid will pick them up automatically. See [docs/RELEASING.md](docs/RELEASING.md).

---

## Features

**The shorts feed**
- Vertical swipe between clips with no perceptible gap
- True shuffle: a fresh permutation each session, so nothing repeats until everything has
  played, and the order differs next time
- Two source modes — folders you pick, or a whole-device scan filtered by shape and length
  (portrait always qualifies; short landscape clips optional, with a 10s/30s/1min/5min cut-off)
- A header that says how many clips there are, and quick views for All, Recent, Favourites
  and Longest
- The tab itself is a wall of clips in three presentations: masonry, where every tile is its
  clip's own shape; a uniform two-column grid; or the list, with names and sizes. Tap one and
  the feed opens on it, with everything else still shuffled behind
- Favourites: double tap the middle of a clip, or use the heart. The sides still seek
- Auto-advance, or loop the current clip

**The ordinary player**
- Custom floating controls: white glyphs over the picture, no chrome, elapsed and
  remaining either side of the timeline. Nothing is on screen until you tap, and the
  whole layer fades in and out as one
- The timeline is a waveform: what you have watched has shape, what is left is a flat line
- Volume and brightness show a level with an icon, not a line of text
- Previous / next through the folder queue, with scrubbing and double-tap for seeking
- Auto-advance depends on the *shape* of the video, not where you opened it — a portrait
  clip rolls into the next one so a folder of shorts behaves like the feed, while a
  landscape or long-form video ends and returns you to the list
- Fit / Crop / Stretch, per-player and remembered
- Playback speed from 0.5× to 3×, remembered across videos. Holding the picture gives a
  flat 2× while held; holding the speed button drops back to 1× and stays there
- Resume positions for long videos only, with an adjustable threshold
- Screen lock, unlocked by a slide or by tapping the four corners clockwise

**Gestures**

| | Shorts feed | Ordinary player |
|---|---|---|
| vertical drag | next / previous clip | brightness (left) · volume to 200% (right) |
| horizontal drag | brightness (left) · volume (right) | scrub, committed on release |
| drag down from top | — | dismiss the player |
| double tap | sides −5s / +5s · middle favourite | −10s / +10s |
| long press | 2× speed while held | 2× speed while held |
| single tap | play / pause | show controls |

Left and right are relative to the screen, so the seek gesture works the same way in
landscape as in portrait.

**Library**
- Two-up grid or list, with search and sorting by name, date, size, duration, count — or no
  order at all
- The order belongs to the screen, not to the app: the library by name, a camera roll by
  date, a folder of clips at random, all at the same time
- Rename, move, delete and share via long-press selection
- Give a folder any frame you like as its thumbnail, from any video inside it
- Pin folders, and videos inside them, to the top — independent of the sort order
- Toolbar actions in a frosted capsule, in light and dark, with layout and order behind one
  button
- Search from the toolbar. In the library it finds videos anywhere, not just folder names
- One tap back into the last thing you watched
- Hide folders from every scan
- Lock folders behind your fingerprint, face or device PIN — a locked folder shows no
  preview and is excluded from the feed

**Getting started**
- A single tip the first time you reach each screen, not a tutorial before you have seen
  anything. The full guide lives in Settings, along with a way to show the tips again

**Appearance**
- System / Light / Dark, plus eleven accent colours
- The three tabs are a floating capsule, with the content passing behind it
- Settings grouped into cards, in the order you actually change things

**Privacy**
- Entirely offline. No network permission at all, no analytics, no accounts, no ads.
  See [PRIVACY.md](PRIVACY.md).

---

## Install

**From source** — see [docs/BUILDING.md](docs/BUILDING.md).

**F-Droid** and **GitHub Releases** — not yet published. See
[docs/RELEASING.md](docs/RELEASING.md) for the intended route.

### Requirements

Android 10 (API 29) or newer.

---

## Documentation

| Document | What is in it |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Why it is built this way — the player pool, why there is no local database, the gesture split, tuning knobs |
| [docs/BUILDING.md](docs/BUILDING.md) | Building, signing, and the version-specific traps |
| [docs/RELEASING.md](docs/RELEASING.md) | Publishing to F-Droid and Google Play |
| [CONTRIBUTING.md](CONTRIBUTING.md) | How to propose changes |
| [CHANGELOG.md](CHANGELOG.md) | What changed, when |
| [PRIVACY.md](PRIVACY.md) | The privacy policy, which stores require |

---

## Built with

[Media3 / ExoPlayer](https://developer.android.com/media/media3) · AndroidX · Material
Components · [androidx.biometric](https://developer.android.com/jetpack/androidx/releases/biometric)

All dependencies are free software. There is no proprietary or Google Play Services
component, which is what makes F-Droid distribution possible.

---

## License

[GNU General Public License v3.0](LICENSE).

You may use, study, share and modify this software. If you distribute a modified version,
it must carry the same freedoms.
