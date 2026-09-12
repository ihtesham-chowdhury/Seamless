# Architecture

Why Seamless is built the way it is. For what it does, see the
[README](../README.md); for how to build it, see [BUILDING.md](BUILDING.md).

Built on **Media3/ExoPlayer 1.11** only — no mpv, no libVLC.

---

## The idea in one paragraph

Every conventional player owns a single `ExoPlayer` and, for each file, runs
`setMediaItem → prepare → play`. The MediaCodec configure plus first-keyframe decode inside
that costs roughly 100–300 ms even from local storage, and that is the black frame you see
between clips in VLC or MX Player. Seamless instead keeps a small pool of players alive and
uses Media3's `DefaultPreloadManager` to buffer the neighbouring clips while the current one
is still playing, so a swipe only changes which surface is visible.

---

## How it is put together

```
data/
  Video.kt           Video + VideoFolder models. Orientation is rotation-corrected.
  MediaLibrary.kt    All MediaStore reads. No local mirror of the file list — see below.
  ShortsQuery.kt     The single definition of what belongs in the feed.
  Prefs.kt           Settings, resume positions, hidden and locked folders.
ui/
  MainActivity.kt    The three tabs, and the fragment each one shows.
  nav/               The floating navigation capsule: three styles, springs, the glass.
  library/           Folder list, folder contents.
  shorts/
    ShortsActivity.kt        The feed. Portrait-locked, immersive.
    ShortsAdapter.kt         One ViewPager2 page per clip.
    PlayerPool.kt            The reused ExoPlayer instances.
    ShortsPreloadControl.kt  How far ahead to preload, per distance from the user.
    GestureOverlayLayout.kt  Touch handling that coexists with ViewPager2.
    ShortsSetupFragment.kt   Picks which folders feed the shuffle.
  player/            The conventional single-video player.
  common/
    MediaOps.kt      Rename / move / delete, with the scoped-storage consent dance.
    AppLock.kt       Biometric and device-credential gate. Stores no secret of its own.
    MediaInfo.kt     Human-readable codec and format description, used by Info and errors.
util/                Thumbnails, brightness/volume, background work.
```

### Two deliberate departures from the original plan

**No Room / no cached file index.** MediaStore *is* the index — a system-maintained,
indexed SQLite database. One projected query over it costs a few hundred milliseconds even
for thousands of rows, and mirroring it into our own table would add a staleness problem
while saving nothing. Critically, width, height, duration and rotation all come from
MediaStore columns, so nothing ever opens a file or touches `MediaMetadataRetriever`.

**The shorts feed is a separate activity, not a tab.** The Shorts tab is a source picker;
pressing play launches a portrait-locked, fullscreen activity. A feed embedded in a tab
cannot go properly immersive with a bottom bar sitting over the video.

---

## Gesture map

The two modes are deliberately different, because in the feed the vertical axis belongs to
the pager.

| | Shorts feed | Long-form player |
|---|---|---|
| vertical drag | next / previous clip | brightness (left) · volume (right) |
| horizontal drag | brightness (left) · volume (right) | scrub, committed on release |
| drag down from top edge | — | dismiss the player |
| double tap | −5s / +5s | −10s / +10s |
| long press | 2× speed while held | 2× speed while held |
| single tap | play / pause | show controls |

In the ordinary player, single taps are deliberately **not** intercepted, so `PlayerView`
keeps ownership of showing and hiding its controls. The gesture detector is fed from
`onInterceptTouchEvent`, which sees every event even when nothing is being intercepted, and
only claims the stream once a double tap or long press has actually been recognised.

That arrangement has one trap, and it bit once. Interception begins on the event *after*
the one that returns true, so claiming the stream on `ACTION_UP` means `onTouchEvent` never
sees the release at all — and a long press that ended there left the 2× speed boost latched
on for the rest of the session. The release is therefore handled in `onInterceptTouchEvent`
as well, before the claim short-circuits, and ending the boost is idempotent.

The dismiss drag commits on distance **or** speed. Distance alone made it feel heavy: a
quick flick reads as "dismiss" even when it has barely travelled.

There is no swipe-to-seek in the feed by design.

The dismiss gesture is confined to a band across the top 25% of the screen rather than
working anywhere, so it cannot be mistaken for a brightness or volume drag. It follows the
finger and springs back if released early. Toggle in Settings → Gestures.

---

## Behaviour notes

- **Shuffle** in the feed is a fresh permutation of the whole list per session, so the order
  is random, nothing repeats until everything has played, and the order differs next time.
  The ordinary player has its own shuffle toggle, off by default, and a folder's Shuffle button
  starts a shuffled session without touching it: shuffle play is an action, not a setting.
- **End of list** asks whether to reshuffle or stop.
- **Auto-advance depends on the shape of the video, not on where you opened it.** When a
  clip finishes in the ordinary player, a *portrait* video rolls straight into the next one
  in the folder, so a folder of shorts behaves like the feed; a landscape or long-form video
  ends and returns you to the list. In the feed, auto-advance can be turned off in Settings
  to loop the current clip instead.
- **Aspect ratio** is Fit everywhere by default — nothing is cropped. The button in either
  player cycles Fit → Crop → Stretch, and both defaults are also in Settings.
- **Resume positions** apply only to videos longer than the threshold in Settings
  (default 10 minutes, adjustable 1–60).
- **Orientation**: the feed is portrait-only. The ordinary player follows the video by
  default, with sensor / portrait / landscape available in Settings.
- **Folders mean folder + subfolders**, matched on MediaStore's `RELATIVE_PATH`. Hiding a
  folder hides everything beneath it, and applies to every scan including the feed.
- **Hidden folders**: long press a folder in the library to start a selection, then use the
  hide action. Settings lists what is hidden and brings folders back.
- **The shorts feed has two source modes.** Picking folders suits an organised library —
  everything in the chosen folders qualifies, which is the original behaviour. Scanning the
  whole device suits everyone else and narrows by shape and length instead: portrait clips
  always qualify whatever their length, and short landscape clips can be opted in with a
  cut-off of 10s / 30s / 1min / 5min. Both the "N videos ready" count and the feed itself go
  through `ShortsQuery.resolve`, so the number shown and the number you get cannot diverge.
- **Moving files has two routes, because MediaStore has an opinion about directories.**
  It does not merely check permissions — it also refuses a `RELATIVE_PATH` pointing at a
  directory it does not recognise as a media directory, with an `IllegalArgumentException`
  rather than a `SecurityException`. A folder like "VideoTapes" at the root of storage is
  not one, so granting write access changes nothing; this is why other players succeed
  where a MediaStore-only implementation fails. When MediaStore refuses,
  [SafMover.kt](../app/src/main/java/com/seamless/player/ui/common/SafMover.kt) offers the
  Storage Access Framework instead: the user points at the destination, the app takes a
  persistable grant, and copies into it. The delete only runs once the copy is verified
  byte-for-byte — losing an original to a half-written copy would be unforgivable.
- **Rename, move, delete and share** live behind a long press in a folder, the same
  selection gesture as the folder list. Scoped storage means an app cannot modify media it
  did not create without consent, and the mechanism differs by version — Android 11+ asks up
  front for the whole batch via `MediaStore.createWriteRequest` / `createDeleteRequest`,
  Android 10 attempts the write and catches `RecoverableSecurityException` to ask per item.
  `MediaOps` hides both behind one asynchronous flow.
- **Locking** is in Settings → Privacy: the whole app, or individual folders via the library
  selection menu. **No PIN is ever stored by this app.** The fallback authenticator is the
  system's `DEVICE_CREDENTIAL`, so the phone's own lock screen does the work — nothing
  secret is written to preferences and there is no home-grown comparison to get wrong.
  Arming a lock is refused when no biometric or screen lock is enrolled, which would
  otherwise strand the folders, and removing a lock requires authenticating first. This
  keeps folders out of casual view; it is not encryption, and the files stay readable by
  any other app.
- **List or grid, and sorting** (name / date / size / count / duration) are in the library
  toolbar and are remembered. The grid is two-up with rounded tiles and a
  "duration · size" line under each, and both library screens have a search field.
- **Opening a video from another app returns you to that app.** `PlayerActivity` declares
  `android:taskAffinity=""`, so it never joins this app's task. Apps typically launch a
  viewer with `FLAG_ACTIVITY_NEW_TASK`, and Android then places it in whichever task shares
  its affinity — by default this one, which is why closing used to land you in the library.
  With no affinity it gets its own task and finishing pops back to the caller. Launches from
  inside the app are unaffected: no `NEW_TASK` flag, so they stay in the current task.
- **Screen lock** is the padlock in either player. It swallows every touch including Back.
  Unlocking is either a slide or tapping the four corners clockwise, chosen in Settings.
- **Playback failures are shown, not swallowed**, and there are two distinct failures. One
  throws: the decoder rejects the clip, and the error code plus the format goes on screen.
  The other is silent — the player reports it is playing while never delivering a frame,
  which is what a "black video" actually is. A watchdog arms when a page takes focus and
  fires if `onRenderedFirstFrame` has not arrived in 2.5s; it retries once without the
  preloaded source, then reports the codec. Distinguishing the two is the whole point: a
  thrown error means the format is unsupported, a silent one means it decoded fine and the
  picture went nowhere. Under both sits the clip's own thumbnail, held over the page until
  the first frame arrives, so nothing is ever a bare black rectangle.
- **A feed page borrows its player on attachment, not on binding.** This looks like a
  detail and is not. RecyclerView does not recycle a page the moment it scrolls away: it
  parks two in a cache first, and `onViewRecycled` only fires when that cache overflows.
  Releasing there meant three visible pages plus two parked ones held five players
  between them, and the pool had four — so after a few swipes every new page got nothing
  and displayed nothing. Window attachment is the honest signal that a page is on screen
  or next to it. A page that still cannot get a player joins a queue and is served the
  instant one comes back, nearest-to-focus first, so the failure mode is a short wait
  rather than a permanent blank.
- **A preloaded source has one owner at a time.** `ShortsAdapter` tracks which positions
  have their `PreloadMediaSource` in a player and hands out the plain `MediaItem` instead
  when a second page asks for one already in use. Two players driving the same source is
  not an error Media3 reports; it is a page that renders nothing.
- **The screen lock shows nothing it does not have to.** No scrim over the video and no
  standing instruction. In corner mode the screen looks untouched, and a hint only slides
  in along the bottom after three failed attempts, then fades. In slider mode the
  slide-to-unlock bar is the one permanent element, because a control has to be visible to
  be usable, and it sits low and out of the way.
- **The subtitle matcher's pure half is executable, and gets executed.** `ReleaseName` and
  `SubtitleScoring` touch nothing Android, so `tools/subtitle_probe.py` compiles a `main()`
  against the type-checker's own output and runs them over twenty real release names. That is
  not gold-plating: the two worst bugs shipped in this package were a recursive builder that
  threw from a static initialiser and a year regex that read a title as a release year, and
  neither is visible to a compiler, a wiring check or a reading. Anything added to
  `data/subtitle/` that does not need a `Context` should be reachable from the probe.
- **Background work delivers its failures.** `Background` used to catch a throwable, log it and
  return — which is fine for a query whose caller has nothing on screen waiting, and was quietly
  catastrophic for one that has. A subtitle search that threw left its panel saying "Searching…"
  until the app was closed: nothing was broken about the panel, it was simply never told. Every
  lane now has an `onFailure`, it is required on the network lane, and the rule is that anything
  showing progress must pass one. The two lanes are separate for a related reason: a request
  sitting on a timeout must not hold up a MediaStore query queued behind it.
- **Errors carry a technical half as well as a human one.** `SubtitleProviderException` has a
  `detail`, and a search builds a trace as it goes — parsed title, hash, every HTTP status. The
  panel shows the sentence and hides the trace behind Copy details. A subtitle search fails on
  someone else's phone, network and account, and without the exchange in hand there is nothing
  to debug from but a description.
- **A control says one thing.** The CC button was given three states — plain, an accent ring for
  "a subtitle is playing", a filled accent disc for "the panel is open" — on the reasoning that
  those are different facts and the user needs both. They are different facts, and the user did
  not need both: what it produced was one control in a row of six that was louder than the other
  five and drew the eye to a distinction nobody was asking about. It is lit or dimmed now, like
  the shuffle button next to it. When a control's neighbours already have a language for state,
  the answer is almost always that language.
- **One door per room.** Anything reachable from two places under the same name is reachable
  from neither: the user learns no route. Subtitles were on the CC button *and* in the overflow
  menu, and the reason was a button that came and went — so the fix was to stop it going away,
  not to keep the second entrance. A control that is always in the same spot is worth more than
  a control that is only there when it has something to say.
- **A floating panel is a column that must not grow, and the cap belongs on the column.**
  Everything in `sheet_tracks.xml` used to be `wrap_content`, so a file with five subtitle tracks
  pushed the actions off the bottom of the screen — and a panel with nothing below the fold has
  no fold to find. The list carries the weight and gives up its height first. The cap then went
  on the *list*, which was the same mistake one level down: a list held to most of a landscape
  screen still yields a card that runs off the top of it, because the header, the hairlines and
  the action rows are added afterwards. `CappedColumn` caps the whole card and lets
  `LinearLayout`'s weight rule distribute the shortfall. A panel that covers the video is a
  settings page, and this app has one of those already.
- **A panel that floats is a different object from a sheet that slides.** `BottomSheetDialog`
  gave the drag, the scrim and the dismissal for free, and cost the thing the design was for: a
  bottom sheet is anchored to the edge of the screen and stretches the full width, so it reads as
  a drawer pulled out of the frame however much margin it is given. A plain floating window with
  a gravity, an inset and a width cap is less machinery and the right object. It also puts the
  enter and exit animation under our control, which is why `dismiss()` is overridden rather than
  animated at each call site — four of the five ways out of that panel are the system calling
  `dismiss` directly, and any of them left un-animated is the one that cuts to black.
- **A subtitle track's id says where it came from, though not where in the id.** Every subtitle
  file this app attaches is given an id like `seamless-sub:SAVED:0`, and Media3 hands it back as
  `1:seamless-sub:SAVED:0`, because `MergingMediaPeriod` prefixes every track id with its source
  index to keep ids unique across a merged item. Reading the origin off the front of the id
  classed every sidecar as embedded for three releases. The general rule: an id handed to a
  library is not promised to come back as it went in, so match on the part that is yours, and
  find out what actually comes back — here, by reading the bytecode — before building on it.
  Once subtitles are handed to Media3, the player's own track list is the only thing that knows
  what exists, and a table kept alongside it would have to be held in step through every
  prepare, rebuild and recycle. The id survives all of that. See
  `data/subtitle/LocalSubtitles.kt` and [SUBTITLES.md](SUBTITLES.md).
- **Subtitles are attached after playback has started, not before.** Local discovery is file
  I/O, and putting a directory listing between the tap and the first frame would tax every
  video for the benefit of the few that have a subtitle beside them. So the player prepares a
  bare media item, discovery runs on a background thread, and *only if it finds something* is
  the item rebuilt with the subtitles attached and re-prepared at the position already
  reached. The re-prepare costs a frame or two on a local file and never happens at all in
  the common case.
- **The shorts feed gets a byte-for-byte unchanged media item when there is nothing to
  attach.** A `MediaItem` carrying subtitle configurations becomes a merging source rather
  than a single one, and `DefaultPreloadManager` is the most delicate thing in this codebase.
  The feed therefore reads the subtitle store once for the entire feed — one directory
  listing, almost always empty — and calls `MediaItem.fromUri` exactly as before for every
  clip that has none. It deliberately does not look in each clip's own folder: that would be
  a listing per page during scrolling.
- **All networking is one file, and that is enforced.** `util/Http.kt` is the only place a
  connection is opened; it refuses anything but https, refuses to run on the main thread, and
  caps every response. `tools/verify.py` fails on a second file that opens a connection, and
  on an `INTERNET` declaration without cleartext disabled or without backup rules. The
  guarantee in PRIVACY.md is only worth the check that keeps it true.
- **The subtitle confidence scale has two bands, enforced rather than hoped for.** A file-hash
  match is floored at 92 and everything else capped at 87, so the top band is reachable only
  by a match on the encode itself. That is what lets the app apply one automatically without
  asking, and it is why the number shown next to a candidate cannot claim more than the
  evidence behind it. See `data/subtitle/SubtitleScoring.kt`.
- **Masonry tiles are measured from metadata, not from thumbnails.** MediaStore gives every
  clip a rotation-corrected width and height, so `ShortsTileAdapter` can set a tile's height
  at bind time and the picture arrives into a space that is already the right shape. Sizing
  from the decoded bitmap instead would mean every loaded thumbnail resizing its tile, and a
  `StaggeredGridLayoutManager` re-balancing its columns each time it happened. The aspect is
  clamped, because one file with nonsense dimensions in its metadata would otherwise produce
  a tile several screens tall. `GAP_HANDLING_NONE` is set for the same class of reason: the
  default lets the manager move an item between columns mid-scroll to close a gap.
- **The launcher icon is not adaptive.** A launcher draws a blurred plate behind every
  adaptive icon, generated from the *mask path* rather than from the artwork, so a
  transparent background layer leaves it on show. No change to the drawing can remove it —
  which is why three launchers all showed it and an icon-pack icon, being an ordinary
  drawable, did not. `android:icon` therefore points at a plain vector. The adaptive pair
  stays in the tree for anyone who would rather have themed icons.
- **The bottom navigation is not a `BottomNavigationView`.** That view measures its items
  across the full width and paints its own background edge to edge, so a narrow floating
  capsule means fighting its measuring at every step. `ui/nav/FloatingNavBar` lays out three
  `NavTabView`s itself and wears one of three styles (`NavStyle`): frosted glass, the accent
  island, liquid glass. All its motion is springs (`Spring`, an exact solution per step) driven
  from the `Choreographer`, with a cap on how far one frame may advance them, so a frame stalled
  by the new tab being built pauses the motion instead of making it jump. It takes the touches
  itself — the liquid drop follows a finger across all three tabs — but a tap still ends in the
  tab's own `performClick`, so listeners and accessibility are unchanged.
- **The frosted glass is really blurred, on Android 12 and later.** A view cannot see its
  neighbours, so `BackdropFrame`, the frame the tabs live in, records its children into a
  `RenderNode` and draws that node, and the capsule's `GlassView` draws the same node a second
  time inside a `RenderEffect` blur: a second reference to one display list, not a second paint.
  The glass is invalidated from `onDescendantInvalidated`, never from a pre-draw listener, which
  would request a frame from inside every frame. Its floor is painted opaque first, or the sharp
  content would show through the transparent gaps in its own blurred copy.
- **The timeline is one view with four hands.** Media3 drives whatever carries the id
  `exo_progress` and implements `TimeBar`, so `ui/player/TimelineBar` owns position,
  buffering, scrubbing and touch once, and `SeekBarStyle` decides only what is drawn. The
  waveform is the default and the only one that animates; the rest redraw when the position
  moves.
- **Backups go through the file picker, not a cloud SDK.** `data/SettingsBackup` writes both
  preference files as typed JSON — the type is stored beside each value, because
  SharedPreferences is typed and a string where an int belongs throws on the next read — and
  the Settings screen hands it to `ActivityResultContracts.CreateDocument`. That covers local
  storage and Google Drive alike with no dependency, which matters: the Drive SDK needs Play
  Services, and this app depends on nothing proprietary. The credentials file is not
  included, for the same reason it is excluded from Android's own backup.
- **Sorting is scoped, not global.** `Prefs.sortFor(scope)` takes `SCOPE_LIBRARY`,
  `SCOPE_SHORTS`, or a folder's MediaStore `RELATIVE_PATH`. Those three namespaces cannot
  collide, because a `RELATIVE_PATH` always ends in a separator and neither constant contains
  one. A scope that has never been given an order falls back to the old global keys, which is
  what anyone upgrading already chose. `SortKey.RANDOM` shuffles from a stored seed rather
  than freshly on each pass, so the list does not rearrange itself under a scroll; picking
  Random writes a new seed, which is what asking for it again means.
- **Settings rows name their own row layout.** `pref_row_top`, `_middle` and `_bottom` differ
  only in which corners they round, and each preference in `settings.xml`
  points at the one matching its place in its group. The alternative is teaching the adapter
  to work out group boundaries that the XML already states, to answer a question that never
  changes at runtime. The ids in those layouts are fixed by androidx: `@android:id/title`,
  `summary`, `icon`, `widget_frame`, and `icon_frame`, which resolves to androidx's own id
  because library resources merge into this package by name.
- **The dismiss gesture touches only RenderNode properties.** Translation, scale and an
  outline radius are all handled on the render thread without redrawing anything. Two things
  are deliberately *not* used: `setAlpha` on the root, which makes the framework flatten the
  whole group into an offscreen buffer first unless `hasOverlappingRendering()` says
  otherwise, and `LAYER_TYPE_HARDWARE`, which forces that same full-screen copy every frame.
  Either one on top of a playing video is enough to make the gesture stutter, and at one
  point this file had both.
- **The launcher icon is generated, not hand-written.** `art/icon.svg` is the drawing;
  `tools/icon_from_svg.py` converts it, positioning it with a single VectorDrawable group
  transform so no path data is ever rewritten. Hand-transcribing path coordinates is how the
  first version of this icon ended up ten units too wide with nothing in the XML looking
  wrong.
- **The timeline is a custom `TimeBar`.** `PlayerControlView` looks up whatever view carries
  the id `exo_progress` and, if it implements Media3's `TimeBar` interface, drives it — so
  `WaveformTimeBar` replaces `DefaultTimeBar` outright without any patching of the
  controller. It draws the watched portion as three braided sine strands and the remainder as
  a flat line, and animates only while `isShown`, which the controls' own timeout bounds to a
  few seconds at a time.
- **Search is global in the library, and deliberately not in the folder screen.** The library
  already holds every video in memory to build the folder list, so matching over them costs
  nothing and turns "search" into something that finds files rather than folder names. A
  `ConcatAdapter` puts the video hits under the folder hits. Locked folders are filtered out
  of the results: a lock that hides a folder's contents but lists them by name in a search
  box is not a lock.
- **Pinning is not a sort key.** It is applied after sorting, by `MediaLibrary.pinnedFirst`.
  Folding it into the comparator would mean the pinned items reshuffle whenever the sort
  changes, which is the opposite of what pinning is for.
- **Controls do not appear on their own.** Both players start showing only the picture;
  the transport bar, the action row and the back arrow appear on a tap. The back arrow is
  shown only when a video was opened from inside the app — from a gallery, system Back
  already returns you there.
- **The player controls are a custom layout**, not Media3's stock bar: floating circular
  controls over the picture with no chrome, outlined skip discs, elapsed on the left of the
  timeline and time remaining on the right. Supplied via `app:controller_layout_id`. The ids
  in [player_controls.xml](../app/src/main/res/layout/player_controls.xml) are load-bearing —
  `PlayerControlView` finds them by name and wires them itself, which is why
  `exo_play_pause` must be an `ImageView` (the controller swaps its icon) and the
  `*_with_amount` views must be `TextView`s (it writes the seek seconds into them). They use
  `@+id/` rather than `@id/` so they declare-or-reuse and cannot fail to resolve. Time
  remaining is ours: Media3 has no such concept, so `PlayerActivity` ticks it.
- **A locked folder reveals nothing** — no preview frame, no counts, just the name and a
  padlock — and its contents are excluded from the shorts feed as well, so a whole-device
  scan cannot put the clips you locked away on screen without asking.
- **Volume continues past 100% through the effect chain, not the player.** `Player.setVolume`
  clamps at unity gain and will not go louder, so amplification has to come from a
  `LoudnessEnhancer` attached to an audio session — the top of the scale is +6 dB, a
  doubling of amplitude, which is what other players present as "200%".
  [ScreenControls](../app/src/main/java/com/seamless/player/util/ScreenControls.kt) presents
  both halves as one continuous scale: below the system maximum it moves stream volume,
  above it the stream stays at maximum and the surplus becomes gain. The session is
  generated with `Util.generateAudioSessionId` rather than read back from the player —
  ExoPlayer has a setter but no getter — and deliberately is not session 0, which is the
  global output mix and would boost every other app too. Audio effects are optional
  hardware, so every call is guarded; where there is none, the scale simply stops at 100%.
- **Window insets are handled centrally.** From targetSdk 35 Android draws edge to edge by
  default, so content sits under the status and navigation bars unless told otherwise. The
  helpers in [util/Insets.kt](../app/src/main/java/com/seamless/player/util/Insets.kt) add
  padding to a view's *original* padding rather than replacing it, because the listener can
  run repeatedly — on rotation, on a keyboard, on a gesture-nav change — and the naive
  version accumulates padding on every pass. `MainActivity` applies them once to the
  container every tab lives in, so no fragment has to think about it.
- **Both players use `TextureView`.** The feed needed it because a `SurfaceView`'s separate
  window layer could be torn down under a recycled page, showing black. The ordinary player
  needs it for the dismiss gesture: a SurfaceView's layer lags behind view translation, so
  dragging the player down stuttered.
- **The three-dot menu** in the player has Info, Share and Open folder. Info reports what
  MediaStore knows plus what the decoder actually selected — codec, profile, frame rate,
  bitrate — which is the fastest way to identify an awkward file.
- **Theme and accent colour**, in Settings → Appearance. Theme is System / Light / Dark,
  applied through `AppCompatDelegate` (which recreates every open screen on its own).
  Accent colour is a grid picker of eleven hues, the same idea as MX Player's — it changes
  the bottom nav, buttons, switches and sliders in the browsing UI. It does not touch the
  fullscreen players, which stay black by design regardless of theme. See
  [ui/common/ThemeManager.kt](../app/src/main/java/com/seamless/player/ui/common/ThemeManager.kt)
  for how the two are applied differently — night mode is a framework mechanism that
  recreates activities on its own, an accent colour is a theme overlay that only takes
  effect while a screen's views are inflating, so picking one recreates the activity.

---

## Tuning knobs

| Where | Constant | Does what |
|---|---|---|
| `ShortsActivity` | `POOL_SIZE` | Live ExoPlayer instances (5). Lower if a device runs out of decoders — the waiting queue in `ShortsAdapter` copes, it just costs a moment. |
| `ShortsActivity` | `PRELOAD_WINDOW` | Items registered with the preload manager either side of you (12). |
| `ShortsActivity` | `setBufferDurationsMs` | Buffer sizes. Low values are what make playback start on the first frame. |
| `ShortsPreloadControl` | the tiers | How much work each neighbouring clip gets. |
| `PlayerActivity` | `SEEK_SPAN_MS` | How much timeline a full-width scrub covers. |
| `PlayerActivity` | `DOUBLE_TAP_SEEK_MS` | Double-tap jump distance (10s). |
| `PlayerGestureLayout` | `FLING_VELOCITY` | Downward speed that dismisses regardless of distance. |
| `LockOverlayView` | `SLIDER_LINGER_MS` | How long the summoned slide bar stays up. |
| `ShortsAdapter` | `FIRST_FRAME_TIMEOUT_MS` | How long a clip may claim to play without rendering before it counts as broken (2.5s). |
| `PlayerGestureLayout` | `TOP_BAND` / `CLOSE_DISTANCE` | Where the dismiss drag may start, and how far it must travel. |
| `LockOverlayView` | `ATTEMPTS_BEFORE_HINT` | Failed unlock attempts tolerated before a hint appears. |

Aspect ratio (Fit / Crop / Stretch) is no longer a build-time constant — it's the resize
button in either player and the two `*_resize_mode` settings, backed by `ResizeModes.kt`.

---

## Measuring it honestly

"Feels fast" is not measurable. Record the screen at 60 fps and count black frames between
clips. The target is one frame; anything at or under two is indistinguishable in use.
