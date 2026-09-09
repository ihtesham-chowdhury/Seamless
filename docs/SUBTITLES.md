# Subtitles

How the subtitle system is put together, what it deliberately does not do, and the one thing
you have to set up yourself.

---

## Setting it up

**Subtitles already in your files need no setup at all.** A text track inside an `.mkv` shows
up on the CC button the moment the video opens.

**Subtitle files on your storage** need Android's permission to read them — see
[Why the folder permission exists](#why-the-folder-permission-exists) below. One prompt,
once per folder, permanently.

**Looking one up online** needs two things, both in **Settings → Subtitles**:

1. Turn on **Look up subtitles online**. It is off by default.
2. Paste an **OpenSubtitles API key**. Registration is free at
   <https://www.opensubtitles.com/consumers>.

Seamless ships no key of its own, and this is not an oversight:

- The API terms do not permit sharing a key between consumers.
- An open-source repository has nowhere to hide one. A key committed here would be a key
  published here, and it would be revoked within days — breaking subtitle search for
  everyone at once.
- With no key present the app cannot reach the network at all, which means the default
  install genuinely makes no requests. That is worth more than the convenience.

An OpenSubtitles **username and password** are optional and affect one thing: anonymous
downloads are capped per address per day, and signing in raises the cap. They are stored on
the device, sent only to the provider's sign-in endpoint, exchanged for a token that is
cached, and excluded from cloud backup.

---

## What happens when you open a video

Nothing that touches the network, and nothing that delays the picture.

```
tap ──► bare media item ──► prepare ──► first frame
          │
          └─► background: look locally
                            │
                            └─► found something? rebuild the item with it attached,
                                re-prepare at the position already reached
```

The re-prepare is the only interruption, it happens only when there was actually something to
attach, and for a local file it costs a frame or two. On a phone clip with no subtitle
anywhere near it the whole feature costs one directory listing on a background thread.

Local discovery looks in two places:

1. **The app's own subtitle store** — anything downloaded or added before. Always readable.
2. **The video's own folder**, if a grant covers it, for companion files:

   ```
   Movie.mkv
   Movie.srt            Movie.en.srt         Movie.en-US.forced.srt
   Movie.English.srt    Movie.bn.srt         Movie.eng.sdh.srt
   ```

   The rule is the video's base name, then a separator, then optional tags — a language, and
   `forced` or `sdh`. Nothing looser: matching on "starts with" alone would hand episode 1's
   subtitle to every episode in a folder named by number.

Formats: `.srt`, `.vtt`, `.ass`, `.ssa`, `.ttml`, `.dfxp`. Every one of those is a format the
existing playback stack decodes, so a subtitle that is offered is a subtitle that will appear.
`.sub` is not offered, because Media3 cannot render VobSub outside a Matroska container and a
row that fails silently after you have chosen it is worse than no row.

---

## Why the folder permission exists

This is a platform rule rather than a design choice, and it is worth being exact about.

A subtitle file is **not media**. `READ_MEDIA_VIDEO` — the permission this app asks for —
grants videos and nothing else. On Android 13 and later there is no permission at all that
grants ordinary files belonging to other apps. So on a modern phone Seamless genuinely cannot
see `Movie.srt` sitting beside `Movie.mkv`, however plainly you can see it in your file
manager. That is scoped storage working as intended.

There are exactly two honest routes, and the app offers both:

| Route | When it works | What it costs |
|---|---|---|
| **Let Seamless read this folder** (`ACTION_OPEN_DOCUMENT_TREE`, taken persistably) | Always | One prompt, once per folder, permanently. Every companion subtitle in that folder is then found on its own, for every video in it. |
| **Choose subtitle file…** (`ACTION_OPEN_DOCUMENT`) | Always | One prompt per file. The file is copied into the app's store, so it sticks. |
| A plain file read | Android 12 and earlier only | Nothing — it is tried first and allowed to fail. |

The folder row only appears in the panel when it would achieve something: the video has a
folder, and no grant covers it yet.

---

## Online matching

Not `filename → first search result`. That is the implementation that has put the wrong
subtitle on a film in every player that has ever shipped it.

### 1. The file's own fingerprint

The **OSDb hash**: the file size plus every 64-bit little-endian word of the first and last
64 KiB, added with wraparound. 128 KiB read from a 40 GB remux, which is why it can happen on
the way into a search rather than as a background chore.

This is the only signal in the entire exchange that is a fact. Two files with the same hash
are the same encode, frame for frame, so a subtitle uploaded against that hash is timed for
exactly the file in hand — no offset, no "close enough". Everything else is inference from a
name somebody typed.

### 2. The name, read into structure

```
The.Matrix.1999.1080p.BluRay.x264-AMIABLE.mkv
└─ title "The Matrix"  year 1999  resolution 1080p  source BluRay  codec x264  group AMIABLE

Breaking.Bad.S01E03.1080p.BluRay.x265-RARBG.mkv
└─ title "Breaking Bad"  season 1  episode 3  …

[SubsPlease] Frieren - 07 (1080p) [F1A2B3C4].mkv
└─ title "Frieren"  episode 7  resolution 1080p
```

Two shapes are recognised: the scene convention, and the bracketed shape anime is distributed
in — no season number, group first, episode after a bare dash. Both produce the same structure,
so nothing downstream has to know which it came from.

Only the title and year are sent as a text query. Season and episode go as their own
parameters. Every technical token is kept back as something to **score** with rather than
something to search for — searching for "1080p" narrows nothing and excludes the 720p upload
that would have matched perfectly.

### 3. Scoring, in two bands

| Band | Score | What it means | What the app does |
|---|---|---|---|
| **Excellent** | 92–100 | The file's fingerprint matched an upload | Downloads it, activates it, shows a line of text and an Undo |
| **Good** | 48–87 | Right film, probably right timing | Shows a short list to choose from |
| **Weak** | below 48 | Probably wrong | Says no confident match, and suggests what to try |

The bands are enforced rather than hoped for: a hash match is floored at 92 and everything
else is capped at 87. So "Excellent" is a statement about the encode, never an opinion about
the title, and the percentage next to a candidate cannot claim more than the evidence behind
it. Within the guessing band the weights — title 34, episode 18, year 14, source 7,
resolution 6, group 6, popularity 6, trusted 4 — only have to order candidates sensibly.
They are not pretending to be a probability.

Automatic application additionally requires the language to be one you asked for. A hash match
in a language you do not read is still shown, not applied.

### 4. Where it ends up

The app's own store first, because that write cannot fail for want of permission. Then, if a
folder grant exists, a second copy beside the video named the way every other player expects:

```
Movie.mkv
Movie.en.srt
```

That second copy is a courtesy, not a dependency — its failure is ignored. Downloads are named
by a hash of their own bytes, so downloading the same subtitle twice overwrites rather than
accumulates. After the first download, playback never needs the network again.

---

## When it does not work

The panel says what happened, and **Copy details** puts the whole exchange on the clipboard.
That is the thing to paste into a bug report; it looks like this:

```
Seamless 0.2.1 · OpenSubtitles
file: The.Matrix.1999.1080p.BluRay.x264-AMIABLE.mkv
as: The Matrix (1999)
hash: 8e245d9679d31e12
want: en, bn
search  HTTP 200 OK  /api/v1/subtitles?languages=bn,en&moviehash=…  12 rows, 12 usable
best: en 98% (hash match)
link    HTTP 406 Not Acceptable  /api/v1/download  {"message":"download limit reached"}
```

Every line is a step, in order, so the one that failed is the one to read. A few that come up:

| What it says | What it means |
|---|---|
| `HTTP 403` on search | The API key was refused. Check it in Settings → Subtitles. |
| `HTTP 406` or `429` on link | The daily download limit. Anonymous is five a day per address; signing in raises it. |
| `hash: none` | The file is under 128 KiB, or could not be opened. Matching falls back to the name, so nothing will be applied automatically. |
| `as:` naming the wrong film | The file name did not parse. Renaming it closer to the original release is the fix; the title and the year are all a search has to go on. |
| `SocketTimeoutException` | The connection stalled. Nothing is wrong with the setup. |

**Nothing can leave the panel spinning.** Every path out of a search — success, no match, a
refusal, a timeout, or an exception nobody predicted — ends in something on screen. That was
not true before, and the bug it caused is the reason this section exists: `Background` used to
log a failure and return, so the panel it was feeding was simply never told.

---

## The panel

One control opens it and there is no second way in. That is worth saying because there used to
be: the CC button appeared only when a video already had captions, so "go and find me one" had
to live in the overflow menu as well, under the same name. Two entries to one panel do not make
it twice as findable — they make neither of them the answer to "where is that".

So the control is always there, and it carries its own state instead of a badge:

| | What it means |
|---|---|
| plain disc | nothing switched on |
| thin accent ring | a subtitle is playing |
| accent ring and fill | the panel is open |

The last two are separate on purpose. Closing the panel is the moment you find out whether
anything changed, and if "on" and "open" looked the same there would be nothing to find out.
The accent is the one chosen in Settings, mixed a third of the way to white so it separates
from a dark disc on a bright frame and from a bright disc on a dark one. The player itself
stays uncoloured; a whole video player tinted in someone's chosen purple would be a strange
thing to insist on, but one control with a state is exactly what an accent is for.

The panel answers one question and puts everything else beneath it:

```
Subtitles                    ×

  Off
✓ English      Downloaded   🗑
  English      In this video
  বাংলা          In this folder
─────────────────────────────
🔍 Find subtitles
📁 Choose subtitle file…
─────────────────────────────
Aa Appearance
```

Above the first line: what am I watching. Between the lines: what to do when the answer is
"none of these". Below the second: appearance, which is real, wanted, and nobody's reason for
opening this panel.

**Only the list scrolls.** Everything used to be one column that grew, which meant a file with
five text tracks pushed all three actions off the bottom of the screen — and in landscape,
where films are actually watched, off the bottom of a fairly short screen. It looked exactly
like the appearance controls disappearing once you added a subtitle. The list now gives up its
own height first, and is capped at a little under half the screen so the panel stays a panel
with the film visible around it.

---

## One subtitle per language

Downloading English twice used to leave two rows called English, four times four rows, and
nothing to tell them apart or take them away. The store deduplicated identical bytes, which
catches the same file twice and misses the case that actually happens: a second upload of the
same subtitle, differing by a line of timing.

The rule now is one saved subtitle per video per language. A second English replaces the first,
because the reason there is a second is that the first was wrong. Anything genuinely different
is a different language and keeps its own row. Files that accumulated before the rule are
cleared on the next play, newest kept — a rule that only applied going forward would leave the
existing mess there for ever.

Two rows can still both say English, and should: one inside the video and one downloaded are
different files with different timings, and the second line says which is which.

**Deleting.** A subtitle this app put on the device carries a quiet delete at the end of its
row. Nothing else does, and the distinction is not squeamishness:

- a track inside the video has no file of its own to remove;
- a `.srt` in your own folder is a file you put there, and a caption menu is not where anyone
  should be able to delete files they did not know were listed.

Deleting the track that is playing falls back the same way opening a video does — the preferred
language if something else carries it, off if nothing does. The panel stays open, because
tidying comes in twos and threes and closing after each one would mean opening the panel three
times.

---

## What it remembers

- **Per video**: the exact track, or "off". So a film watched without subtitles stays that way.
- **As a habit**: whether subtitles were left on at all. Turn English on for episode one and
  episodes two to ten come up with subtitles already showing, in whichever track carries your
  preferred language. This is what stops the player asking every episode.
- **Preferred language**, in Settings. Your device's own language is *also* included in every
  search, so a Bengali phone gets Bengali results without anyone changing a setting, and the
  preference decides which of the two wins.

---

## Appearance

Size, weight, edge, background opacity and vertical position — five controls, reached from the
subtitle panel rather than from Settings, because a caption style is not something anyone can
picture from a label. Every change applies as it moves, and the caption is lifted into the
middle of the picture while the panel is open so there is something to look at above it.

The defaults are the feature: white text, an outline rather than an opaque black bar, and a
resting position clear of the transport controls. The bar is what every other player does and
it is why subtitled films look like training videos. When the controls come up, the caption
moves up with them — the requirement is that a subtitle is never behind a control, and moving
the subtitle is the only way to meet it rather than hope.

Two things are deliberately overridden:

- **Android's global caption style.** Honouring it sounds respectful until you meet its
  defaults, which are the black bar. It is also invisible from inside the app: a user cannot
  tell why this player looks different from the last one.
- **The subtitle file's own styling.** An `.ass` file can specify fonts, colours and sizes,
  most of them chosen for a desktop player in 2009. What is configured here is what appears,
  consistently, across every file.

---

## In the shorts feed

Embedded tracks, and anything already downloaded for that clip — read from the store in one
directory listing for the whole feed. The CC button appears for the handful of clips that have
captions and stays away for the rest. Every existing gesture is untouched: single tap to
play or pause, double tap in the middle to favourite, double tap at the sides to seek, long
press for 2×.

Two things are absent from the feed on purpose:

- **Looking in each clip's folder for companion files.** That would be a directory listing per
  page while your thumb is moving, to answer a question whose answer is "no" for every clip
  anyone has ever filmed.
- **Online search.** It works by matching a release name against a database of films.
  `VID_20240817_204411.mp4` gives it nothing to match, so the button would be an invitation to
  fail.

A media item with no subtitles attached is byte-for-byte the item the feed always used, so its
preload engine — the most delicate thing in this app — is unchanged for every clip that has
none.

---

## Audio tracks

The same panel, from the overflow menu, hidden when a file has only one. "Which audio track"
and "which subtitle track" are one question asked about two renderers; the only thing that
differs is the wording on the row. It selects through Media3's own track selection, exactly as
subtitles do.

---

## Chapters: deliberately absent

Investigated and not implemented, and this is the reason rather than an omission.

ExoPlayer's extractors do not surface chapter metadata for local files. Matroska chapters live
in a `Chapters` element the Matroska extractor does not read, and MP4 chapter tracks
(`chap`-referenced text tracks, or Nero-style `chpl`) are not exposed through any public
Media3 API. There is nothing to put behind a Chapters menu item that would not be invented,
and an artificial "every ten minutes" chapter list is worse than none.

If Media3 grows a chapter API, the overflow menu is where it goes.

---

## Architecture

```
data/subtitle/
  SubtitleFormats.kt      which extensions, and what to tell Media3 they are
  SubtitleLanguages.kt    "eng", "English", "bangla" → one tag; tag → a word
  SubtitleNames.kt        the companion-file naming convention
  ReleaseName.kt          file name → title, year, season, episode, release
  OsdbHash.kt             the 128 KiB fingerprint
  SubtitleStore.kt        where downloads live; which track a video was left on
  SubtitleFolder.kt       the folder grant: reading and writing beside the video
  LocalSubtitles.kt       discovery, and the sidecars it produces
  SubtitleProvider.kt     the interface, the query, the candidate, the confidence
  SubtitleScoring.kt      the two bands
  OpenSubtitlesProvider.kt  the one implementation
  SubtitleSearch.kt       the only door to the network

ui/player/
  SubtitleController.kt   all of it, for the ordinary player
  SubtitleTracks.kt       reading and selecting tracks — subtitles and audio alike
  CcButton.kt             the control's three states, drawn from the accent
  TrackSheet.kt           the floating panel
  SubtitleAppearanceSheet.kt
  SubtitleCandidatesSheet.kt
  FloatingSheet.kt        a bottom sheet that floats over video without waking the system bars

ui/common/
  SubtitleStyles.kt       preferences → CaptionStyleCompat
  CappedScrollView.kt     a list that will not take the whole screen

util/
  Http.kt                 the whole of this app's networking
```

Two seams are worth knowing about:

**`SubtitleProvider`** is what makes the online half replaceable. Everything above it — the
local engine, the scoring, the panels, the store — depends on the interface and not on any
particular website. Swapping provider is one new class and one line in `SubtitleSearch`.

**A track's id carries where it came from.** Every subtitle the app attaches is given an id
like `seamless-sub:SAVED:0`, which Media3 hands back as `Format.id`. Anything without that
prefix came out of the container. This is why there is no table of subtitles kept alongside the
player's own track list — such a table would have to be kept in step through every prepare,
rebuild and recycle, and the id survives all of it for free.

---

## Performance rules, and where they are enforced

| Rule | Where |
|---|---|
| Never block the main thread | `Http` throws if called on it; discovery and search go through `Background` |
| Never delay playback | the bare item is prepared first; subtitles are attached afterwards, and only if found |
| Never rescan storage during playback | discovery runs once per video; the feed reads the store once for the whole feed |
| Never decode video to identify subtitles | the hash is 128 KiB of file, read positionally, never a frame |
| Never fetch the same thing twice | downloads are named by a hash of their bytes; a subtitle in the store stops the search running again |
| No expensive work while scrolling | the feed does no per-page storage work at all |
| https only, one file | `util/Http.kt` refuses anything else; `tools/verify.py` refuses a second file that opens a connection |

The parser and the scorer are run rather than reasoned about: `python tools/subtitle_probe.py`
puts twenty real release names through them and prints what each one became. Add a name to it
when one parses badly — that is the fastest route from a bad match to a fix.
