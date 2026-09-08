# Changelog

All notable changes to this project are recorded here.

Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning
follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

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

[Unreleased]: https://github.com/ihtesham-chowdhury/Seamless/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/ihtesham-chowdhury/Seamless/releases/tag/v0.1.0
