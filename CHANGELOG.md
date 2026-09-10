# Changelog

All notable changes to this project are recorded here.

Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning
follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Changed
- **Settings has icons, a rule after each heading, and switches in your colour.** Every row sits
  on a small round disc with its icon — the same plate the player's controls sit on, from the
  same Material family, so the two screens look like one product. Each section heading runs out
  into a line in the accent colour that fades to nothing. The switches are pills: the accent when
  on, a quiet grey when off, a white knob inside with air around it. And the header's line is
  broken in two on purpose, which leaves the space on the right that makes it read as a header.
- **Playback speed is a floating card, not a list in a dialog.** A large readout of the speed; a
  slider with a step either side for fine-tuning in 0.05s; and every preset as a pill, in two rows
  so none of them has to be scrolled to, with the one playing shown in white. A single scrolling
  row came first and hid most of the presets off its end. In landscape the card sits in the middle
  of the screen. It applies as it moves, like the appearance panel, because a speed is judged by
  listening to it.
- **Play, previous and next sit on glass.** A translucent disc under each and a brighter one under
  play, and nothing around the three. A capsule holding them came first and, however narrow, read
  as a bar laid over the film — the one thing floating controls are not.
- **A rounder gear.** The navigation's settings icon is Material's Round variant: the same shape,
  softer teeth.
- **CC is drawn in line.** A rounded frame and two open, round-capped C's at the same weight as the
  controls around it, in place of Material's solid tile with squared letters cut out of it.
- **A folder's Shuffle button plays the folder, shuffled.** It used to flip a setting and say so in
  a toast. It starts from a random video now, with shuffle on for that session only; tapping a
  video afterwards still plays in whatever order the player's own shuffle toggle says.
- **Volume goes past 100% in Shorts too**, up to 200%, the same as the ordinary player. The boost
  lasts the session — swipe to the next clip and it stays as loud — and leaving the feed puts it
  back to the device's own maximum, as leaving the player always has.
- **Controls that are off fade less.** Shuffle, speed at 1x and CC all dim to the same 65% now; two
  of them were at 40% and the third at 55%, which next to icons at full strength looked less like
  "off" than like "unavailable".
- **The navigation capsule is wider.** It was as narrow as its three tabs and read as a small badge.
  It takes the width less a margin either side now, up to a cap, with the tabs sharing it equally.
- **The subtitle panel is the floating card it was drawn as.** It was a bottom sheet, and no
  amount of margin turns one of those into a card resting on the picture: a bottom sheet belongs
  to the edge of the screen, stretches the full width, and reads as a drawer pulled out of the
  frame. It is inset from the end and the bottom now, capped in width, and it grows out of the
  corner the CC button sits in and shrinks back into it — scale and opacity together, weighted
  so it appears to come *from* the control rather than fade in over it.
- **Radios rather than ticks, hairlines rather than boxes, a chevron on the rows that open
  something, and no grabber.** An unselected row shows an empty ring, which says it could be
  chosen; a blank space says nothing.
- **The CC button is a plain fade, like shuffle beside it.** Lit when a subtitle is playing,
  dimmed when one is not. An accent ring for "on" and a filled accent disc for "panel open" was
  more information than anyone wanted, and it made one control in a row of six louder than the
  rest.
- **Settings has a header, shorter words and a line between its rows.** "Settings", one line
  saying what the page is for, then the same sections in the same order — but with the toolbar
  replaced by plain text, because a `MaterialToolbar` centres its title and subtitle as one
  group and pushes the title off the top edge at Material 3 sizes. That bug has already been
  fixed once on the Shorts tab. Roughly twenty settings lost words they did not need: "Apply
  certain matches at once" is "Auto-apply exact matches", "Force portrait for other apps" is
  "Force portrait outside Seamless", and the unlock method finally shows which method it is set
  to instead of a sentence explaining what unlock methods are.

### Fixed
- **Letting go of a press-and-hold brought up the player's controls.** A hold held still until it
  became the 2x boost has no touch events between its down and its up, so the up was the first
  chance to claim it — and the gesture layer cleared its claim before checking it, letting the
  release through to Media3's player view, which read the down and the up as a tap and raised the
  controls. The release is swallowed now: holding shows the 2x badge, letting go hides it, and
  nothing else appears.
- **The subtitle delete button never appeared, and folder subtitles said "In this video".**
  One cause, for three rounds. Each subtitle this app attaches is given an id that says where it
  came from, and the origin was read off the *start* of the id. But Media3 plays a video with
  subtitles as a merge of separate sources, and `MergingMediaPeriod` rewrites every track id on
  the way through by putting the source's index in front of it — so `seamless-sub:BESIDE:0` comes
  back as `1:seamless-sub:BESIDE:0`, the start of it is `1`, and every downloaded subtitle and
  every file beside a video was taken for a track inside the container, which has nothing to
  delete. Confirmed against the bytecode of Media3 1.11 rather than guessed at. The origin is
  found wherever it sits in the id now; the delete button appears for downloaded subtitles and
  for files beside the video (a file you put there asks first, and names itself); and
  `tools/subtitle_probe.py` runs the merged form of the id so this cannot quietly come back.
- **The subtitle panel ran to the bottom of the screen in both orientations.** It floats now,
  with air underneath it.
- **Half the appearance controls did not exist on a landscape video.** A phone lying down gives
  a panel about 360dp, the appearance column wants more than that, and there was no scroll — so
  Background and Position were simply unreachable unless you happened to open a portrait video.
  The controls scroll now with Reset pinned below them, and everything in the player's panels is
  a size smaller in landscape.
- **A panel could grow taller than the screen it was on.** The height cap was on the track list,
  which says nothing about how tall the *panel* ends up once a header, two hairlines and three
  action rows are added on top; a list allowed most of a short screen produced a card that ran
  off the top of it and covered the controls it was opened from. The cap is on the card now and
  the list gives up whatever is left.
- **"Clear downloaded subtitles" deleted them on one tap with no way back.** The row is
  "Downloaded subtitles" now, with its size, and the deleting happens behind a question.
- **The caption you were restyling could end up behind the panel restyling it.** The lift that
  moves it clear was a fixed fraction of the screen, and a fixed fraction cannot be right for a
  panel that is as tall as its own contents in an orientation that changes them. The panel
  measures itself now and the caption goes just above whatever it turned out to be.

---

## [0.2.3] — 2026-09-10

Subtitles, and then the three rounds of fixing them that the first attempt needed. The
engine landed in 0.2.0 and did not work at all until 0.2.2; this is the first version where
the whole feature — finding a subtitle, choosing one, being rid of one — is worth using.

### Changed
- **One CC control, always in the same place, with its state on its face.** Subtitles used to be
  reachable from the button *and* from the overflow menu, because the button only appeared once a
  video turned out to have captions — so there had to be some other way to go looking for one.
  Two doors to one room teach you that neither is the door. The button is now always present and
  the menu entry is gone, and the control says which of three things is true without a badge or a
  word on it: plain when nothing is on, a thin accent ring when a subtitle is playing, ringed and
  filled while the panel is open. The accent is the one you chose in Settings, mixed light enough
  to read over any frame.
- **The subtitle panel, rebuilt around the question it is actually asked.** Which subtitle am I
  watching, at the top, where it belongs; **Find subtitles** and **Choose subtitle file** below a
  line; **Appearance** below a second line, because it is real and it is not why anyone opened
  the panel. The panel grows out of the corner the control sits in rather than arriving as a
  dialog, and it carries a close button of its own — the only thing to tap outside it is the film.
- **"Add a subtitle file…" is now "Choose subtitle file…"**, with a folder rather than a plus.
  Subtitles beside the video are found without being asked for, so this is the fallback for a
  file that is somewhere else, not a second way to do the ordinary thing.

### Fixed
- **The appearance controls went missing on any video with a few subtitle tracks.** They were
  never missing. The whole panel was one column of `wrap_content`, so four or five tracks pushed
  Find subtitles, Choose subtitle file and Appearance off the bottom of a screen that had no way
  to scroll to them — worst in landscape, which is where films are watched. Only the track list
  scrolls now, and it gives up its own height before anything else does.
- **Downloading English four times listed English four times, with no way to be rid of any of
  them.** The store deduplicated identical bytes and nothing else, and two uploads of the same
  subtitle differ by a line of timing. One subtitle per video per language now: a second English
  replaces the first, because the reason there is a second is that the first was wrong. Files
  that piled up before this rule are cleared on the next play, newest kept. Anything left that
  this app put on the device has a quiet delete button at the end of its row — and only those,
  never a track inside the video, and never a `.srt` sitting in a folder of yours.
- **Deleting the subtitle you were watching now stands the player back up** rather than leaving
  it pointed at a file that is gone: it falls back to another track in your preferred language,
  or to off if there is none.
- **Subtitles did not work at all, and the reason was four lines of table-building.** The
  language table was built inside a `buildMap` block by a helper declared as
  `fun put(code, vararg names)` — which shadows `MutableMap.put`, so the `put(it, code)` in its
  body called itself. Infinite recursion in a static initialiser, throwing a
  `StackOverflowError`; and an `Error` is not an `Exception`, so it walked past every handler on
  the way out and every later touch of that object failed with `NoClassDefFoundError` instead.
  Nothing about the symptoms pointed anywhere near it: online search reported a generic failure
  and local subtitle files silently never appeared, because the first thing either does is ask
  what language something is in. Found by running the code rather than reading it —
  `tools/subtitle_probe.py` is new and does exactly that, and `tools/verify.py` now refuses a
  local function named after a method of the builder it sits inside.
- **"Blade Runner 2049 (2017)" was a film from 2049.** The parser took the first four-digit year
  in a name, which is the title's own whenever a title contains one — so `1917.2019.720p` was
  searched for as "1917 2019 720p WEBRip x264 AAC" from 1917. It takes the last one now, which
  is right in every case where a title carries a year, and trailing brackets are stripped so
  `Movie (2019).mkv` no longer searches for `Movie (`.
- **A subtitle search that failed said "Searching…" instead of saying what went wrong.** Not a
  slow search — a lost one. `Background` logged any exception its work threw and returned, so a
  caller waiting on a result waited for ever; and the download step let an `IOException` from a
  timed-out connection travel straight past the handler meant to catch it. Between them, a
  network hiccup produced a panel that spun until the app was closed. Failure is now delivered
  rather than swallowed, everything the network can throw is converted where it is thrown, and
  the panel carries a **Copy details** button with the whole exchange — status codes and all — so
  a failure on someone else's phone, network and account can be reported rather than described.
- **Three likely causes of the failure itself, while in there.** The request now sends
  `Content-Type: application/json`, which the API has been observed to require even on a GET;
  the search text is lower-cased, which its cache expects; and `Accept-Encoding` is no longer set
  by hand, which had left gzip to be decoded on our side of a fence the platform normally owns.
- **Network requests have their own thread.** They shared one with MediaStore, so a request
  sitting on a timeout would hold up the library scan queued behind it.

### Changed
- **Each quick view on the shorts tab keeps its own order.** Sorting Favourites by date used to
  sort All, Recent and Longest by date too — they shared one setting, which defeated the point
  of having four views: date suits Recent and not Longest, a shuffle suits Favourites and
  neither of the others. Longest still opens longest-first and Recent newest-first, but that is
  now a default rather than a rule, and either can be re-sorted on its own. The sheet names the
  view it is sorting, and offers **Use this order for every view** for anyone who wanted them
  level all along. An order chosen under the old shared setting carries over to All and
  Favourites rather than being quietly reset.

### Added
- **Subtitles.** Three sources — text tracks inside the file, companion files in the same
  folder, and an optional online lookup — behind one CC control that only appears when the
  video actually has captions. What makes the online half worth having is that it matches on
  the file's own 128 KiB fingerprint as well as its name: a fingerprint match is the same
  encode, frame for frame, so the subtitle is correctly timed rather than merely about the
  right film. That case is downloaded, switched on, and reported with a line of text and an
  Undo; a merely plausible match shows a short list with what each one is and how well it
  matches; an unlikely one says no confident match and suggests what to try, instead of
  guessing. Off by default, and inert until you supply your own free API key — so a default
  install still makes no network request of any kind. Details, and the reasoning, in
  [docs/SUBTITLES.md](docs/SUBTITLES.md).
- **Subtitle appearance, on the panel rather than in Settings.** Size, weight, edge,
  background opacity and vertical position, applied as they move, with the caption lifted
  into the middle of the picture while the panel is open so the effect is visible. The
  defaults are the point: white text with a thin outline, not the opaque black bar that
  makes subtitled films look like training videos. The caption also moves up when the
  transport controls appear, because "never behind a control" is a requirement rather than a
  hope.
- **Audio track selection**, in the overflow menu, hidden when a file has only one. It came
  free: "which audio track" and "which subtitle track" are one question asked about two
  renderers, so they share the panel and the selection code.
- **`INTERNET`, for the subtitle lookup and nothing else.** This is a real change to the
  app's position and is documented as one, at length, in the manifest and in
  [PRIVACY.md](PRIVACY.md). The whole of the app's networking is now one file that refuses
  plain HTTP and refuses to run on the main thread; `tools/verify.py` fails the build if a
  second file opens a connection, or if the manifest ever declares `INTERNET` without
  cleartext disabled or without backup rules. The provider credentials are kept in their own
  preferences file so they can be excluded from cloud backup, which they are.

### Fixed
- **"Shorts" is back at the top left of the shorts tab.** It was a `MaterialToolbar` title,
  and a toolbar measures its title and subtitle as one vertical group and centres that group
  in its own bounds. With both present at Material 3's sizes the group is taller than
  `?attr/actionBarSize`, so the overflow went above the top edge — clipping the title away
  entirely and leaving the count sitting alone where the title should have been. It is two
  ordinary text views now, which cannot do that at any font scale.
- **A quick view is now the feed you get.** Tapping a clip in Favourites played that clip and
  then everything else on the device; the same in Recent and Longest. The chip narrowed the
  wall and nothing else, because the wall applied the filter and the feed re-resolved the
  list from scratch without it. Membership now has exactly one definition, in `ShortsQuery`,
  which both go through — so what you are looking at and what you get cannot drift apart
  again. Folder mode is unchanged: it has no chips, so it can only ever mean everything in
  the chosen folders.
- **Settings no longer crashes the app when you open it.** The resume-threshold preference
  had both a summary provider declared in XML and a summary written in code, and
  `Preference.setSummary` throws outright on a preference that has a provider — so the whole
  tab went down on open. `tools/verify.py` now refuses that combination: it is a conflict
  that only exists where the XML meets the Kotlin, so neither half looked wrong on its own.

### Fixed
- **The shorts tab no longer bounces you back into the feed.** Whole-device mode opened the
  feed on arrival and left the tab itself showing one sentence explaining that it had, which
  meant backing out of the feed landed on a screen that immediately sent you back in. The
  tab is now a wall of clips: it shows what it found and you pick one.

### Fixed
- **The swipe-down dismissal no longer stutters.** Two things were forcing a full-screen
  offscreen buffer on every frame of the drag: fading a ViewGroup that reports overlapping
  content makes the framework flatten it into a layer before applying the alpha, and an
  earlier attempt at fixing this stutter had added `LAYER_TYPE_HARDWARE`, which does the
  same thing again for the same reason. Both are gone. The gesture now moves the picture
  with translation, scale and a rounding outline — RenderNode properties, which cost a
  matrix update and nothing else — and carries it the rest of the way out rather than
  handing over to a system transition that read as a second, unrelated animation.
- **Clips in the shorts feed no longer come up black.** The feed borrowed a player per page
  but only handed it back when RecyclerView *recycled* the page, and RecyclerView parks two
  pages in a cache before recycling anything. Three visible pages plus two parked ones
  needed five players from a pool of four, so after a few swipes the pool was permanently
  empty — and a page that could not get a player silently stayed blank for ever. Players are
  now returned when a page leaves the window, a page that has to wait is served the moment
  one comes free, and the pool has one player of headroom for the overlap during a fling.
- **The controls no longer leave in two stages.** Media3's controller animation only knows
  how to animate views it finds by its own ids, and this app's custom controller layout
  contains just one of them — the timeline — so the seek bar slid away on its own while
  everything else waited to be switched off. That animation is now disabled and the whole
  layer of chrome fades together.
- **Press and hold returns to the speed you were watching at**, taken from the player rather
  than from settings, so letting go can never leave the video at a speed nobody chose.
- The unlock slider no longer stretches across a whole landscape screen.
- Play/pause is a circular button the size of its neighbours, instead of an oversized bare
  glyph — Media3's icon was being scaled to the full height of the button.

### Added
- **A header and quick views on the shorts tab.** "Shorts" sits top-left the way "Library"
  does, with the clip count under it in both source modes, and a row of chips below that:
  All, Recent, Favourites, Longest. Each is a filter, an order, or both — whatever makes its
  label true. Recent narrows to the last 30 days rather than only re-ordering, because
  ordering by date is already the default and a chip that changes nothing is worse than no
  chip.
- **The shorts tab is a wall you choose from, in three presentations.** Masonry by default —
  two columns, each tile at its clip's own shape — with a uniform two-column grid and the
  existing list a tap away. Switching re-lays out what is already in memory: no re-read of
  MediaStore, no thumbnail reloaded, and your place in the wall is kept.
- **Favourites.** Double tap the middle of a clip in the feed to mark it, or use the heart in
  the action row. The edges of the screen still seek five seconds, which is what double tap
  meant before and still means — the middle third was the part of the picture that meant
  nothing. Marked clips carry a heart in the wall.
- **Random, as a sort order.** Seeded rather than reshuffled on every pass, so the list stays
  put while you scroll it, with a "Shuffle again" button where the direction control would be
  for any other order.
- **Ascending and descending**, as two labelled buttons. The direction used to be a neutral
  button in a dialog that flipped it without ever saying which way it had flipped to.
- **A grid of clips on the shorts tab**, three columns of 9:16, with a list view for anyone
  who would rather read names. Tapping one opens the feed on that clip, with the rest still
  shuffled behind it.
- Whole-device is the default source for the shorts feed on a new install: it is the mode
  with nothing to set up.
- Settings shows the app version.
- `tools/svg_to_vector.py`, for the ordinary case of a flat SVG that needs to become a
  VectorDrawable. It refuses gradients, strokes and transforms rather than dropping them
  quietly.
- **First-run tips, where the thing they describe actually is.** One sentence the first time
  you reach each screen, rather than a carousel on first launch that arrives before any of it
  means anything. Settings has the full guide, and a way to put the tips back.
- **Copy** in the video Info dialog, since the useful half of it is codec strings and a path.
- **Pin folders and videos to the top.** Long press, then **Pin to top**. Pinning sits
  outside the sort order rather than being another sort key, so changing the sort rearranges
  everything else and leaves the pinned things where they are.
- **The library search is global.** It used to match folder *names* only, so a file you could
  see perfectly well inside a folder was unfindable from the screen above it. It now also
  lists matching videos from anywhere, each labelled with the folder it came from. Locked
  folders are excluded — a search box that listed their contents by name would undo the lock
  completely.
- A folder can borrow its thumbnail from any video inside it: long press the folder, then
  **Change thumbnail**.
- **Last played** in the library toolbar, to pick up where you left off. Locked folders
  still ask for authentication when reached this way.
- The feed shows each clip's own thumbnail until its first frame is decoded, so a swipe
  lands on a picture rather than on black.
- A themed (monochrome) launcher icon for Android 13 and newer.

### Changed
- **The launcher icon is a plain drawable, not an adaptive one.** Every launcher tried drew a
  soft rounded plate behind it in default settings — Nova, Smart Launcher, crDroid — and
  swapping in an icon-pack icon removed it on all three. That plate is the adaptive icon's
  mask shadow: a launcher generates it from the mask path rather than from the artwork, so a
  transparent background layer leaves it showing and nothing in the drawing can take it away.
  A plain drawable never enters that code path. The cost is Android 13+ themed icons, which
  need an adaptive icon; the adaptive pair is still in the tree and the manifest says how to
  switch back.
- **Tiles in the shorts wall carry no filename.** A wall of `VID_20240817_204411.mp4` is a
  wall of noise, and the frame is what tells one clip from another. Duration sits over a
  short gradient rather than in a box, and the name is one tap away in list mode.
- **The order of a list belongs to that list.** Sorting was one global setting, which is
  wrong the moment you have two kinds of folder: the library reads best by name, a camera
  roll by date, a folder of clips in no order at all. Every screen now keeps its own key,
  direction and shuffle seed. Screens that were never given an order fall back to the old
  global setting, so nothing moves under anyone who had already chosen one.
- **Layout and order are one toolbar button**, opening a sheet with both. Two icons for two
  halves of the same question was one too many in a row that small, and the sheet stays open
  while you try orders — sorting is something you arrive at, not something you know.
- **The three tabs are a floating capsule** rather than a bar across the bottom of the
  screen, with the content passing behind it. Not a restyled `BottomNavigationView`: that
  view is a full-width surface by definition, and every route to making it a narrow pill
  fights its own measuring.
- **Settings is grouped into cards**, with section headings that have room to breathe. The
  resume threshold became a list of sensible values rather than a slider from 1 to 60 —
  nobody wants seventeen minutes, and a `SeekBarPreference` brings a row layout of its own
  that could not join a card.
- **The padlock on a locked folder is the traced artwork**, in its own colours rather than
  tinted flat.
- **The launcher icon is drawn 8% larger.** The old size was set by measuring the card's
  bounding-box corner against the mask, but the furthest *painted* point of a rounded
  rectangle is one radius further in, so it was smaller than it needed to be. What that
  leaves is less of the plate a launcher draws behind every adaptive icon — which is
  generated from the mask shape rather than from the artwork, and cannot be removed by an
  app.
- **The launcher icon is generated from the traced SVG** in `art/icon.svg` by
  `tools/icon_from_svg.py`, rather than retyped into Android's XML by hand. It is the same
  drawing, exactly, including the slate's dividers and the diagonal shading.
- **The toolbar actions sit in a frosted capsule.** A menu cannot give its items a shared
  background, so they are a view in the toolbar instead. This is a translucent surface with a
  gradient, not a true backdrop blur — Android has no public API for blurring what is behind
  an in-app view.
- **The shorts tab stops asking.** Where the clips come from is a decision made once, so it
  moved to Settings. Whole-device mode now opens the feed straight away; folder mode still
  shows the folder list, because choosing what to watch *is* the point of that mode.
- Press and hold shows a "2x >>" capsule instead of a plain grey label.
- Play/pause is larger, and the transport row is balanced around it.
- The padlock on a locked folder is drawn at a size that suits a grid tile, with the keyhole
  punched out rather than painted on — `android:tint` colours every path in a vector, so a
  lighter keyhole drawn on top would have been tinted into invisibility.
- A new shorts tab icon: a tall clip with the cards behind it showing at the edges.
- **The timeline is a waveform.** What you have watched is drawn as three braided, drifting
  wave strands; what is left is a flat line. Media3 drives any view carrying the id
  `exo_progress` that implements its `TimeBar` interface, so this replaces the stock bar
  without patching the controller.
- **Volume and brightness show a level, not a sentence.** A vertical column with an icon and
  a percentage, on the same side of the screen as the gesture that summoned it. Amplified
  volume continues past 100% in a different colour, so it is obvious you have left the
  normal range.
- **Search moved into the toolbar.** It was a bar standing permanently below the toolbar,
  spending a row of every screen on something used occasionally. Now it is a button that
  reveals the field, and Back closes it.
- Transport buttons are bare white glyphs again. The translucent discs behind them made the
  row read as a strip of tiles rather than as controls floating over the picture; the
  circular proportions and the oval-clipped ripple stay.
- **Press and hold on the picture is a flat 2x** and says so on the badge. It briefly
  multiplied the current speed instead, so holding at 1.5x gave 3x — defensible on paper,
  confusing in the hand. It still never *slows* anything: above 2x the hold does nothing.
- **Press and hold the speed button resets to 1x** and stays there, instead of starting a
  boost. Gestures now keep out of the controls entirely, so a press on a button is that
  button's business.
- New launcher icon, drawn as vectors so it stays sharp at every size.
- Touch feedback on the floating player buttons is clipped to the button, instead of
  flashing a square behind a round control.
- The launcher icon has no coloured tile behind it. The adaptive icon's background layer
  is transparent, so what the home screen shows is the mark's own silhouette.

### Removed
- **Open folder** from the player's overflow menu. Android has no standard intent for
  "show me this file's directory", so the action worked on some devices and did nothing on
  others. Info still shows the folder path.

---

## [0.1.0] — 2026-09-04

First release. Everything below is new.

### The shorts feed
- Vertical swipe feed with no perceptible gap between clips, built on a pool of live
  `ExoPlayer` instances and Media3's `DefaultPreloadManager`
- Session-unique shuffle: a fresh permutation each time, nothing repeating until the list
  is exhausted
- Two source modes — chosen folders, or a whole-device scan filtered by shape and length
- Auto-advance or loop; end-of-list prompt to reshuffle or stop
- Gestures: vertical swipe to move, horizontal drag for brightness and volume, double tap
  to seek ±5s, long press for 2× speed

### The ordinary player
- Custom floating controls over the picture, with elapsed and remaining time
- Auto-advance decided by video shape: portrait clips roll on, landscape and long-form
  return to the list
- Fit / Crop / Stretch, remembered per player
- Resume positions for videos past an adjustable length threshold
- Screen lock with slide or four-corner unlock
- Drag down from the top edge to dismiss
- Opening a video from another app returns you to that app on exit
- Info, Share and Open folder in an overflow menu

### Library
- Two-up grid or list, with search and sorting by name, date, size, duration or count
- Rename, move, delete and share through long-press selection, handling scoped-storage
  consent on both Android 10 and 11+
- Hide folders from every scan
- Lock folders behind biometrics or the device PIN; a locked folder shows no preview and
  is excluded from the feed

### Appearance
- System / Light / Dark themes, and eleven accent colours

### Reliability
- Playback failures are surfaced with the decoder error and codec rather than showing a
  black page
- A watchdog catches the silent case where a clip decodes but never renders a frame
- Decoder fallback enabled, so a decoder that refuses a clip is not the end of it

[Unreleased]: https://github.com/ihtesham-chowdhury/Seamless/compare/v0.2.3...HEAD
[0.2.3]: https://github.com/ihtesham-chowdhury/Seamless/releases/tag/v0.2.3
[0.1.0]: https://github.com/ihtesham-chowdhury/Seamless/releases/tag/v0.1.0
