package com.seamless.player.ui.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.getSystemService
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.seamless.player.R
import com.seamless.player.SeamlessApp
import com.seamless.player.data.MediaLibrary
import com.seamless.player.data.OrientationMode
import com.seamless.player.data.Prefs
import com.seamless.player.data.Video
import com.seamless.player.databinding.ActivityPlayerBinding
import com.seamless.player.ui.common.ControlStyle
import com.seamless.player.ui.common.LevelHudView
import com.seamless.player.ui.common.MediaInfo
import com.seamless.player.ui.common.ResizeModes
import com.seamless.player.ui.common.Tips
import com.seamless.player.ui.common.VideoShare
import com.seamless.player.util.Background
import com.seamless.player.util.Log
import com.seamless.player.util.ScreenControls
import com.seamless.player.util.formatDuration
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The ordinary player, used from the library.
 *
 * It holds a queue built from the folder the video was opened in. What happens at the end
 * of a video depends on the video's shape: a portrait clip rolls straight into the next one
 * so a folder of shorts behaves like the feed, while a landscape or long-form video ends and
 * returns you to the list.
 */
class PlayerActivity : AppCompatActivity(), PlayerGestureLayout.Listener, Player.Listener {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var controls: ScreenControls
    private lateinit var prefs: Prefs

    private var player: ExoPlayer? = null

    /**
     * Subtitles, entire.
     *
     * Built in onCreate rather than lazily, because it registers the two document pickers it
     * needs and registerForActivityResult refuses to run once an activity has started.
     */
    private lateinit var subtitles: SubtitleController


    /** The folder contents, before ordering. Kept so shuffle can be toggled mid-playback. */
    private var source: List<Video> = emptyList()
    private var queue: List<Video> = emptyList()
    private var index = 0
    private var shuffle = false

    private val current: Video? get() = queue.getOrNull(index)

    /** Our own audio session, so the loudness effect only ever touches this app's output. */
    private val audioSessionId: Int by lazy { Util.generateAudioSessionId(this) }

    /** False when another app handed us a bare URI to play. */
    private val launchedFromLibrary: Boolean
        get() = intent.getStringExtra(EXTRA_FOLDER_PATH) != null

    /** Position when a scrub gesture started, and where it currently points. */
    private var seekAnchorMs = 0L
    private var seekTargetMs = 0L

    /** What was playing before a press-and-hold, so letting go restores it exactly. */
    private var speedBeforeBoost = 1f

    /** Whether the controls and the buttons that ride with them are currently on screen. */
    private var chromeVisible = false

    /** Scratch for hit-testing taps against the control rows. Reused, not reallocated. */
    private val hitRect = Rect()

    /**
     * Lives in the custom controller layout. Media3 has no "time remaining" concept, so this
     * one is ours to keep up to date.
     */
    private val remainingView: TextView?
        get() = binding.playerView.findViewById(R.id.time_remaining)

    private val remainingTicker = object : Runnable {
        override fun run() {
            val exo = player
            val view = remainingView
            if (exo != null && view != null) {
                val duration = exo.duration
                view.text = if (duration > 0) {
                    "-" + formatDuration((duration - exo.currentPosition).coerceAtLeast(0))
                } else {
                    ""
                }
            }
            binding.root.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = (application as SeamlessApp).prefs
        controls = ScreenControls(this)
        controls.keepScreenOn(true)
        controls.restoreBrightness(prefs.playerBrightness)
        // The folder's Shuffle button starts a shuffled session; the saved setting is only what
        // an ordinary tap on a video gets.
        shuffle = intent.getBooleanExtra(EXTRA_SHUFFLE, false) || prefs.folderShuffle
        subtitles = SubtitleController(
            activity = this,
            prefs = prefs,
            binding = binding,
            playerProvider = { player },
            onStateChanged = { updateSubtitleButton() },
        )
        binding.root.listener = this
        goImmersive()
        wireButtons()

        loadQueue()
        Tips.showOnce(this, prefs, Tips.PLAYER, R.string.tip_player, Tips.Room.NONE)
    }

    private fun wireButtons() {
        // Start clean: the picture, nothing else. Controls appear on a tap, not on arrival.
        // The layout starts these hidden too — the visibility listener below cannot fire
        // until the controller is first shown, so relying on it alone left the top row on
        // screen from the moment playback began.
        binding.playerView.controllerAutoShow = false
        binding.playerView.hideController()
        binding.actions.visibility = View.GONE
        binding.btnBack.visibility = View.GONE

        // Media3's own show/hide animation has to go, and this is why.
        //
        // PlayerControlViewLayoutManager animates a fixed set of views it finds by id:
        // exo_controls_background, exo_top_controls, exo_center_controls, exo_bottom_bar and
        // exo_progress. This app supplies a custom controller layout that contains only the
        // last of those, so the animation had exactly one target — the timeline — and slid
        // and faded it away on its own while every other control sat there until the whole
        // controller was switched off 250ms later. That is the "seek bar disappears first"
        // everyone noticed.
        //
        // Turning it off makes Media3 show and hide the controller in one step. The fade
        // below then covers everything at once, which is both correct and nicer.
        binding.playerView.setControllerAnimationEnabled(false)
        // 0 means "never time out on your own": the linger is ours to run, so the transport
        // controls and the buttons around them can leave together.
        binding.playerView.controllerShowTimeoutMs = 0
        // Tapping to dismiss is handled in onSingleTap, which fires before PlayerView sees
        // the release — early enough to start the fade rather than cut.
        binding.playerView.setControllerHideOnTouch(false)

        // No inset padding on these two. The window is already fullscreen and immersive, and
        // adding it pushed the controls inward far enough to sit over the picture.

        // Previous and next drive our own queue; Media3's exo_prev / exo_next would act on
        // the player's playlist, which only ever holds the current item.
        // The timeline is whichever of the six the setting names, and the transport buttons
        // are dressed to match it. The controller has already inflated both by now.
        ControlSkin.apply(binding.playerView, prefs.seekBarStyle)
        binding.playerView.findViewById<View>(R.id.btn_prev)?.setOnClickListener { playPrevious() }
        binding.playerView.findViewById<View>(R.id.btn_next)?.setOnClickListener { playNext() }

        // With the animation disabled this fires the instant the controller's visibility
        // changes, so it is a reliable "the controls just came up" signal to animate on.
        binding.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                if (visibility == View.VISIBLE) showChrome() else chromeVisible = false
            }
        )

        onBackPressedDispatcher.addCallback(this, lockedBack)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnMore.setOnClickListener { showMoreMenu(it) }

        binding.root.swipeDownToCloseEnabled = prefs.swipeDownToClose
        binding.root.post(remainingTicker)

        binding.btnResize.setOnClickListener {
            val next = ResizeModes.next(prefs.playerResizeMode)
            prefs.playerResizeMode = next
            applyResizeMode()
            showHud(getString(ResizeModes.label(next)))
        }

        binding.btnSubtitles.setOnClickListener { subtitles.showSubtitleSheet() }
        updateSubtitleButton()

        binding.btnShuffle.setOnClickListener { toggleShuffle() }
        updateShuffleIcon()

        binding.btnSpeed.setOnClickListener { showSpeedPicker() }
        // Press and hold on this button is "back to normal", and it stays there — the fast
        // way out of 1.75x without opening the picker and hunting for 1x. Returning true
        // marks the long press as handled, which is what stops the release from also firing
        // the click and opening the picker on top of it.
        binding.btnSpeed.setOnLongClickListener {
            resetSpeed()
            true
        }
        updateSpeedLabel()

        binding.btnLock.setOnClickListener {
            hideChrome(animate = false)
            binding.playerView.hideController()
            binding.playerView.useController = false
            binding.root.gesturesEnabled = false
            binding.lockOverlay.lock(prefs.unlockMethod)
            lockedBack.isEnabled = true
        }
        binding.lockOverlay.onUnlocked = {
            lockedBack.isEnabled = false
            binding.playerView.useController = true
            binding.root.gesturesEnabled = true
        }
    }

    // ---- controls ----

    /**
     * The controls, the back arrow and the action row, treated as one thing.
     *
     * The transport row lives inside Media3's controller and the other two are siblings of
     * the PlayerView, but the user sees a single layer of chrome, so it appears and leaves
     * as a single layer: a short fade with a small slide, each row moving away from the
     * edge it is anchored to.
     */
    private val chromeViews: List<View>
        get() = listOfNotNull(
            binding.playerView.findViewById<View>(R.id.controls_root),
            binding.btnBack,
            binding.actions,
        )

    private val hideChromeRunnable = Runnable { hideChrome() }

    private fun showChrome() {
        chromeVisible = true
        binding.root.removeCallbacks(hideChromeRunnable)
        // A caption behind the timeline is unreadable, and moving the caption is the only fix
        // that does not involve hoping the two never coincide.
        subtitles.applyStyle(SubtitleController.Lift.CONTROLS)

        val travel = CHROME_TRAVEL_DP * resources.displayMetrics.density
        chromeViews.forEach { view ->
            view.animate().cancel()
            view.visibility = View.VISIBLE
            view.alpha = 0f
            // Bottom chrome rises into place, top chrome drops into it.
            view.translationY = if (view.id == R.id.controls_root) travel else -travel
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(CHROME_FADE_IN_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        binding.root.postDelayed(hideChromeRunnable, CONTROLS_LINGER_MS)
    }

    /**
     * Idempotent: a second call while the fade is already running does nothing.
     *
     * [animate] is false where the chrome is getting out of the way of something else the
     * user just asked for — a double-tap seek, engaging the lock — and a fade would only
     * read as a flicker.
     */
    private fun hideChrome(animate: Boolean = true) {
        if (!chromeVisible) return
        chromeVisible = false
        binding.root.removeCallbacks(hideChromeRunnable)
        subtitles.applyStyle()

        val travel = CHROME_TRAVEL_DP * resources.displayMetrics.density
        chromeViews.forEach { view ->
            view.animate().cancel()
            if (!animate) {
                settleHidden(view)
                return@forEach
            }
            view.animate()
                .alpha(0f)
                .translationY(if (view.id == R.id.controls_root) travel else -travel)
                .setDuration(CHROME_FADE_OUT_MS)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { settleHidden(view) }
                .start()
        }
    }

    /** Puts one chrome view away and resets what the animation left on it. */
    private fun settleHidden(view: View) {
        // The controller has to be switched off through Media3 rather than by setting
        // visibility here, or it would consider itself still shown and the next tap on the
        // video would toggle it to hidden instead of bringing it back.
        if (view.id == R.id.controls_root) binding.playerView.hideController()
        else view.visibility = View.GONE
        view.alpha = 1f
        view.translationY = 0f
    }

    // ---- queue ----

    /**
     * The folder this queue came from, which is also its sort scope. Empty when the video
     * was opened from outside the app, where there is no folder and no queue to order.
     */
    private val folderPath: String
        get() = intent.getStringExtra(EXTRA_FOLDER_PATH).orEmpty()

    private fun loadQueue() {
        val folder = intent.getStringExtra(EXTRA_FOLDER_PATH)
        val startId = intent.getLongExtra(EXTRA_START_ID, -1L)

        if (folder == null) {
            // Opened from outside the app: a single URI with no library context.
            val uri = intent.data
            if (uri == null) {
                finish()
                return
            }
            queue = emptyList()
            startSinglePlayback(uri)
            return
        }

        Background.run(
            work = {
                MediaLibrary.videosUnder(
                    MediaLibrary.queryAll(this, prefs.hiddenFolders),
                    setOf(folder),
                )
            },
            then = { videos ->
                source = videos
                applyOrder(startId)
                if (queue.isEmpty()) finish() else startQueuePlayback()
            },
        )
    }

    /**
     * Sorts, optionally shuffles, and positions the queue on [startId].
     *
     * The order comes from the folder the queue was built from, so "next" here means the
     * next one down the list you were just looking at.
     */
    private fun applyOrder(startId: Long) {
        val sorted = MediaLibrary.sortVideos(source, prefs.sortFor(folderPath))
        if (shuffle) {
            // Whatever the user tapped stays first; everything else is randomised.
            val head = sorted.firstOrNull { it.id == startId }
            val tail = sorted.filter { it.id != startId }.shuffled()
            queue = if (head != null) listOf(head) + tail else tail
            index = 0
        } else {
            queue = sorted
            index = sorted.indexOfFirst { it.id == startId }.coerceAtLeast(0)
        }
    }

    private fun toggleShuffle() {
        shuffle = !shuffle
        prefs.folderShuffle = shuffle
        current?.let { applyOrder(it.id) }
        updateShuffleIcon()
        showHud(getString(if (shuffle) R.string.shuffle_on else R.string.shuffle_off))
    }

    private fun updateShuffleIcon() {
        binding.btnShuffle.alpha = if (shuffle) 1f else ControlStyle.INACTIVE_ALPHA
    }

    // ---- playback speed ----

    /**
     * A chosen speed persists across videos in the queue, which is the point: someone
     * watching a long lecture at 1.5x wants the next one at 1.5x too. It is deliberately
     * separate from the long-press boost, which is momentary and always returns to this.
     */
    /**
     * The speed panel: a floating card like the subtitle one, applied as it moves.
     *
     * No HUD while it is open. The panel's own number is already saying what the speed is, in
     * large type, and a toast-shaped echo of it on every slider tick would be the same fact
     * twice with one of them in the way.
     */
    private fun showSpeedPicker() {
        SpeedSheet(SPEEDS, prefs.playbackSpeed) { speed ->
            prefs.playbackSpeed = speed
            player?.setPlaybackSpeed(prefs.playbackSpeed)
            updateSpeedLabel()
        }.show(this)
    }

    /** Straight back to 1x, saved like any other choice so the next video starts there too. */
    private fun resetSpeed() {
        if (prefs.playbackSpeed == 1f) {
            showHud(getString(R.string.speed_already_normal))
            return
        }
        prefs.playbackSpeed = 1f
        applySpeed()
    }

    private fun applySpeed() {
        player?.setPlaybackSpeed(prefs.playbackSpeed)
        updateSpeedLabel()
        showHud(speedLabel(prefs.playbackSpeed))
    }

    private fun updateSpeedLabel() {
        binding.btnSpeed.text = speedLabel(prefs.playbackSpeed)
        // Normal speed is the uninteresting case; let it recede.
        binding.btnSpeed.alpha = if (prefs.playbackSpeed == 1f) ControlStyle.INACTIVE_ALPHA else 1f
    }

    /** One formatting rule for the button, the HUD and the panel, so "1.15" never reads "1.1500001". */
    private fun speedLabel(speed: Float): String = SpeedSheet.format(speed) + "x"

    // ---- playback ----

    private fun buildPlayer(): ExoPlayer = ExoPlayer.Builder(this)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(2_000, 20_000, 300, 800)
                .build()
        )
        // If the preferred decoder refuses a clip, fall back to another that claims the
        // format rather than failing outright.
        .setRenderersFactory(
            DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        )
        .build()
        .also { exo ->
            // Generating the session ourselves rather than waiting for
            // onAudioSessionIdChanged means the boost effect can be attached immediately,
            // with no window where a drag would silently do nothing. ExoPlayer exposes a
            // setter but no getter, so this is the intended route.
            exo.setAudioSessionId(audioSessionId)
            controls.attachAudioSession(audioSessionId)

            exo.addListener(this)
            binding.playerView.player = exo
            player = exo
        }

    private fun startSinglePlayback(uri: Uri) {
        val exo = player ?: buildPlayer()
        // A file handed over by another app still gets subtitles: there is no folder to look in,
        // but its own name is enough to search with, and anything downloaded for it is kept
        // against the URI so it is found again next time.
        val standIn = externalVideoStandIn()
        if (standIn != null) {
            subtitles.onVideoStarted(standIn)
            exo.setMediaItem(subtitles.mediaItemFor(standIn))
        } else {
            exo.setMediaItem(MediaItem.fromUri(uri))
        }
        exo.prepare()
        exo.playWhenReady = true
        applyResizeMode()
        applyOrientation(null)
    }

    private fun startQueuePlayback() {
        buildPlayer()
        playCurrent()
    }

    private fun playCurrent() {
        val video = current ?: return finish()
        val exo = player ?: return

        binding.errorMessage.visibility = View.GONE
        prefs.rememberLastPlayed(video)
        // The bare item, deliberately: local discovery runs on a background thread and attaches
        // what it finds afterwards. Waiting for it here would put a directory listing between the
        // tap and the first frame for every video, including the overwhelming majority that have
        // no subtitle anywhere near them.
        subtitles.onVideoStarted(video)
        exo.setMediaItem(subtitles.mediaItemFor(video))
        exo.prepare()

        val resumeAt = prefs.loadPosition(video)
        if (resumeAt > 0) exo.seekTo(resumeAt)

        exo.playWhenReady = true
        exo.setPlaybackSpeed(prefs.playbackSpeed)
        applyResizeMode()
        applyOrientation(video)
    }

    /** Explicit navigation, unlike [advanceOrFinish] which only moves for portrait clips. */
    private fun playNext() {
        if (queue.isEmpty()) return
        savePosition()
        if (index < queue.lastIndex) {
            index++
            playCurrent()
        } else {
            showHud(getString(R.string.end_of_queue))
        }
    }

    private fun playPrevious() {
        if (queue.isEmpty()) return
        val exo = player
        // The familiar behaviour: part-way in, "previous" restarts the current video.
        if (exo != null && exo.currentPosition > RESTART_THRESHOLD_MS) {
            exo.seekTo(0)
            return
        }
        savePosition()
        if (index > 0) {
            index--
            playCurrent()
        } else {
            player?.seekTo(0)
        }
    }

    private fun advanceOrFinish() {
        val finished = current
        // The rule from testing: portrait clips roll on, landscape and long-form do not.
        val shouldAdvance = finished != null && finished.isPortrait && index < queue.lastIndex
        if (shouldAdvance) {
            index++
            playCurrent()
        } else {
            finish()
        }
    }

    private fun applyResizeMode() {
        binding.playerView.resizeMode = ResizeModes.toMedia3(prefs.playerResizeMode)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) advanceOrFinish()
    }

    /** Where the CC button appears from, and where a remembered subtitle choice is applied. */
    override fun onTracksChanged(tracks: Tracks) {
        subtitles.onTracksChanged()
    }

    /**
     * Surfaces decode failures instead of leaving a black screen, then moves on. The error
     * code is shown on purpose — it is the fastest way to identify an unsupported codec.
     */
    override fun onPlayerError(error: PlaybackException) {
        val video = current
        Log.e("PlayerActivity", "playback failed for ${video?.name}", error)
        binding.errorMessage.text = getString(
            R.string.playback_failed_detail,
            getString(R.string.playback_failed, video?.name ?: ""),
            "${error.errorCodeName}\n${error.cause?.message ?: error.message.orEmpty()}",
        )
        binding.errorMessage.visibility = View.VISIBLE
        if (queue.isNotEmpty()) {
            binding.root.postDelayed({ if (!isFinishing) advanceOrFinish() }, ERROR_SKIP_DELAY_MS)
        }
    }

    /**
     * The CC control: lit when a subtitle is playing, faded when one is not.
     *
     * The same treatment as the shuffle button beside it, and for the same reason — it is the
     * same kind of fact. An earlier version gave this button an accent ring for "on" and a
     * filled accent disc for "panel open", which was more information than anyone wanted and
     * made one control in a row of six louder than the rest. Opacity says the only thing worth
     * saying here, and says it in the language the row already speaks.
     *
     * The control is always on screen either way. A button that comes and goes with the file
     * teaches nobody where it is, and it is what forced "find me a subtitle" into the overflow
     * menu as a second entrance under the same name.
     */
    private fun updateSubtitleButton() {
        binding.btnSubtitles.alpha = if (subtitles.hasActiveTrack()) 1f else ControlStyle.INACTIVE_ALPHA
    }

    // ---- overflow menu ----

    private fun showMoreMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.player_more, menu)
            // A list of one is not a choice. Chapters are absent for the same class of reason and
            // a more permanent one: ExoPlayer's extractors do not surface chapter metadata for
            // local files, so there is nothing to put behind such an entry. See docs/SUBTITLES.md.
            menu.findItem(R.id.action_audio)?.isVisible = subtitles.hasAudioChoice()
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_audio -> { subtitles.showAudioSheet(); true }
                    R.id.action_info -> { showInfo(); true }
                    R.id.action_share -> { shareCurrent(); true }
                    else -> false
                }
            }
            show()
        }
    }

    /**
     * Shows what MediaStore knows plus what the decoder actually chose. The codec lines are
     * the useful ones when a clip misbehaves.
     */
    private fun showInfo() {
        val video = current ?: externalVideoStandIn() ?: return
        val exo = player
        val details = MediaInfo.details(
            context = this,
            video = video,
            format = exo?.videoFormat,
            audio = exo?.let { describeSelected(SubtitleTracks.audioOptions(it)) },
            subtitles = exo?.let { describeSelected(SubtitleTracks.textOptions(it)) },
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.info_title)
            .setMessage(details)
            // Worth having: the useful half of this dialog is codec strings and a path,
            // which are exactly the things you want to paste somewhere rather than retype.
            .setNeutralButton(R.string.action_copy) { _, _ ->
                getSystemService<ClipboardManager>()
                    ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.info_title), details))
                // Android 13 and newer show their own copy confirmation; saying it twice
                // would just cover the one the system already put on screen.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(this, R.string.info_copied, Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * "English · 5.1", or how many tracks are there when none is playing.
     *
     * Info is a diagnostic, so it says what is selected *and* what was available — "3 available"
     * answers "why can I not hear the English dub" in a way that a blank line does not.
     */
    private fun describeSelected(options: List<SubtitleTracks.Option>): String? {
        if (options.isEmpty()) return null
        val selected = SubtitleTracks.selected(options)
            ?: return getString(R.string.subtitle_track_number, options.size)
        val index = options.indexOf(selected)
        val label = SubtitleTracks.label(this, selected, index)
        val detail = if (selected.group.type == C.TRACK_TYPE_AUDIO) {
            SubtitleTracks.audioDetail(selected)
        } else {
            SubtitleTracks.detail(this, selected)
        }
        return if (detail.isBlank()) label else "$label · $detail"
    }

    /**
     * The library's video, or the one another app handed over. The second kind cannot always
     * be passed on as it came, and used to close the player when it could not; VideoShare
     * finds an address that can be, or says why not.
     */
    private fun shareCurrent() {
        val uri = current?.uri ?: intent.data ?: return
        VideoShare.share(this, uri)
    }

    // "Open folder" used to live here. It is gone on purpose: Android has no standard
    // intent for "show me this file's directory" — "resource/folder" is a convention some
    // file managers honour and most ignore — so the action either did nothing or handed
    // the user to an app that opened somewhere else entirely. An action that cannot be
    // made to work reliably is worse than no action. The folder's path is still shown by
    // Info, and the library is one Back away.

    /** A minimal stand-in so Info works for a URI opened from another app. */
    private fun externalVideoStandIn(): Video? {
        val uri = intent.data ?: return null
        val format = player?.videoFormat
        return Video(
            id = -1L,
            uri = uri,
            name = uri.lastPathSegment ?: uri.toString(),
            relativePath = "",
            folderName = "",
            durationMs = player?.duration?.coerceAtLeast(0L) ?: 0L,
            width = format?.width ?: 0,
            height = format?.height ?: 0,
            sizeBytes = 0L,
            dateModified = 0L,
        )
    }

    // ---- orientation ----

    private fun applyOrientation(video: Video?) {
        // A video handed over by a gallery or file manager can be pinned upright, so the
        // screen does not swing round for a quick look.
        if (!launchedFromLibrary && prefs.externalPortrait) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            return
        }
        requestedOrientation = when (prefs.orientationMode) {
            OrientationMode.SENSOR -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            OrientationMode.FOLLOW_VIDEO ->
                if (video != null && video.width > 0 && video.height > 0) {
                    orientationFor(video.width, video.height)
                } else {
                    // onVideoSizeChanged settles it once the file has been read.
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
        }
    }

    private fun orientationFor(width: Int, height: Int): Int =
        if (height > width) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        if (!launchedFromLibrary && prefs.externalPortrait) return
        if (prefs.orientationMode != OrientationMode.FOLLOW_VIDEO) return
        if (videoSize.width == 0 || videoSize.height == 0) return
        requestedOrientation = orientationFor(videoSize.width, videoSize.height)
    }

    // ---- gestures ----

    override fun onVerticalDrag(rightHalf: Boolean, delta: Float) {
        if (rightHalf) {
            // The scale continues past the system maximum into amplification, so the drag
            // runs over volumeSteps rather than stopping at 100%.
            val step = delta * controls.maxVolume * VOLUME_SENSITIVITY
            controls.volumeLevel = controls.volumeLevel + step.roundToInt()
            showVolumeLevel()
        } else {
            controls.brightness = controls.brightness + delta * BRIGHTNESS_SENSITIVITY
            prefs.playerBrightness = controls.brightness
            showBrightnessLevel()
        }
    }

    private fun showVolumeLevel() {
        val normal = controls.volumeLevel.coerceAtMost(controls.maxVolume)
        val extra = (controls.volumeLevel - controls.maxVolume).coerceAtLeast(0)
        val headroom = (controls.volumeSteps - controls.maxVolume).coerceAtLeast(1)
        showLevel(
            LevelHudView.Kind.VOLUME,
            level = normal.toFloat() / controls.maxVolume.coerceAtLeast(1),
            label = "${controls.volumePercent}%",
            overflow = extra.toFloat() / headroom,
        )
    }

    private fun showBrightnessLevel() {
        val automatic = controls.isBrightnessAutomatic
        showLevel(
            LevelHudView.Kind.BRIGHTNESS,
            // Dragging past the bottom hands the screen back to the system, which can go
            // dimmer than the manual floor and adapts on its own. Nothing to fill in.
            level = if (automatic) 0f else controls.brightness,
            label = if (automatic) getString(R.string.hud_auto_short)
            else "${(controls.brightness * 100).roundToInt()}%",
            automatic = automatic,
        )
    }

    private fun showLevel(
        kind: LevelHudView.Kind,
        level: Float,
        label: String,
        overflow: Float = 0f,
        automatic: Boolean = false,
    ) {
        binding.levelHud.animate().cancel()
        binding.levelHud.alpha = 1f
        binding.levelHud.visibility = View.VISIBLE
        binding.levelHud.show(kind, level, label, overflow, automatic)
    }

    private fun hideLevel() {
        if (binding.levelHud.visibility != View.VISIBLE) return
        binding.levelHud.animate().alpha(0f).setDuration(220).withEndAction {
            binding.levelHud.visibility = View.GONE
            binding.levelHud.alpha = 1f
        }
    }

    override fun onSeekStart() {
        seekAnchorMs = player?.currentPosition ?: 0L
        seekTargetMs = seekAnchorMs
    }

    override fun onSeekDrag(cumulative: Float) {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it > 0 } ?: return
        seekTargetMs = (seekAnchorMs + (cumulative * SEEK_SPAN_MS)).toLong().coerceIn(0, duration)
        val difference = seekTargetMs - seekAnchorMs
        val sign = if (difference >= 0) "+" else "-"
        showHud("${formatDuration(seekTargetMs)}  ($sign${formatDuration(abs(difference))})")
    }

    /** Keeps the controls up for as long as anything is being pressed, dragged or scrubbed. */
    override fun onTouchDown() {
        if (chromeVisible) {
            binding.root.removeCallbacks(hideChromeRunnable)
            binding.root.postDelayed(hideChromeRunnable, CONTROLS_LINGER_MS)
        }
    }

    /**
     * A tap on the picture, confirmed as a single tap rather than half of a double one: it
     * dismisses the chrome if the chrome is up, and brings it up if not.
     *
     * PlayerView used to raise the controller itself, on the release, and this handled only
     * dismissal. That made the first tap of every double tap open the controls as well. The
     * gesture layout keeps taps on the picture away from PlayerView now, so both directions
     * are decided here.
     */
    override fun onSingleTap() {
        when {
            chromeVisible -> hideChrome()
            // Tapped during the fade-out. PlayerView will not act on this — as far as it is
            // concerned the controller is still up — so catch it here and bring it back
            // rather than leaving a fifth of a second where taps do nothing.
            binding.playerView.isControllerFullyVisible -> showChrome()
            // Down: raise the controller, and its visibility listener animates the chrome in.
            else -> binding.playerView.showController()
        }
    }

    /**
     * Whether a point lands on something interactive.
     *
     * Only the rows that actually hold buttons count. The controller's own root fills the
     * screen but is transparent and inert everywhere except its bottom bar, so testing
     * against that would swallow every tap on the picture.
     */
    override fun isOverControl(x: Float, y: Float): Boolean {
        if (!chromeVisible) return false
        return listOfNotNull(
            binding.playerView.findViewById<View>(R.id.controls_bar),
            binding.btnBack,
            binding.actions,
        ).any { view ->
            if (view.visibility != View.VISIBLE) return@any false
            hitRect.set(0, 0, view.width, view.height)
            // The transport bar is several levels down inside the PlayerView, so its own
            // left/top are meaningless here; this walks the offsets up for us.
            binding.root.offsetDescendantRectToMyCoords(view, hitRect)
            hitRect.contains(x.toInt(), y.toInt())
        }
    }

    /** Same idea as the feed: tap either side to jump, wherever the screen is rotated to. */
    override fun onDoubleTapSeek(rightHalf: Boolean) {
        val exo = player ?: return
        // Nothing to put away. The first tap no longer reaches PlayerView, so a double tap
        // leaves the controls exactly as they were and shows only the jump.
        val target = exo.currentPosition + if (rightHalf) DOUBLE_TAP_SEEK_MS else -DOUBLE_TAP_SEEK_MS
        val duration = exo.duration
        exo.seekTo(target.coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE))
        showHud(
            getString(
                if (rightHalf) R.string.seek_forward else R.string.seek_back,
                DOUBLE_TAP_SEEK_MS / 1000,
            )
        )
    }

    /**
     * Holding on the picture means 2x, flat, exactly as it says on the badge.
     *
     * It briefly multiplied the current speed instead, so holding at 1.5x gave 3x. That is
     * defensible on paper and confusing in the hand — the gesture has one advertised meaning
     * and should have one behaviour. The only concession is that it never *slows* anything:
     * above 2x the hold does nothing rather than dragging you back down.
     */
    override fun onSpeedBoostStart() {
        val exo = player ?: return
        // Read the speed off the player rather than out of preferences. They should agree,
        // but the player is the thing actually making the sound, and restoring what was
        // genuinely playing cannot leave the video at a speed nobody asked for.
        speedBeforeBoost = exo.playbackParameters.speed
        val boosted = maxOf(BOOST_SPEED, speedBeforeBoost)
        exo.setPlaybackSpeed(boosted)
        binding.speedBadge.text = speedLabel(boosted)
        binding.speedBadge.visibility = View.VISIBLE
    }

    /** Back to exactly what was playing before the hold, never to 1x. */
    override fun onSpeedBoostEnd() {
        player?.setPlaybackSpeed(speedBeforeBoost)
        binding.speedBadge.visibility = View.GONE
    }

    /**
     * Called once the card has already animated off the bottom of the screen, so the window
     * has nothing left to show and no system transition should be added on top. A fade here
     * used to run *after* the drag had finished, which read as a second, unrelated animation.
     */
    override fun onSwipeDownToClose() {
        savePosition()
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onGestureEnd() {
        if (seekTargetMs != seekAnchorMs) {
            player?.seekTo(seekTargetMs)
            seekAnchorMs = seekTargetMs
        }
        hideLevel()
        binding.hud.animate().alpha(0f).setDuration(250).withEndAction {
            binding.hud.visibility = View.GONE
            binding.hud.alpha = 1f
        }
    }

    private fun showHud(text: String) {
        binding.hud.text = text
        binding.hud.alpha = 1f
        binding.hud.visibility = View.VISIBLE
        binding.hud.removeCallbacks(hideHud)
        binding.hud.postDelayed(hideHud, HUD_LINGER_MS)
    }

    private val hideHud = Runnable {
        binding.hud.animate().alpha(0f).setDuration(250).withEndAction {
            binding.hud.visibility = View.GONE
            binding.hud.alpha = 1f
        }
    }

    // ---- lifecycle ----

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * Back does nothing while the screen is locked, or the lock would be pointless.
     *
     * A callback rather than an onBackPressed override. An app targeting Android 16 receives back
     * gestures through the dispatcher only, so the override was never asked about a swipe and a
     * locked screen could be swiped away. Enabled only while locked, so the rest of the time Back
     * keeps the system's own animation.
     */
    private val lockedBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = Unit
    }

    private fun savePosition() {
        val exo = player ?: return
        val video = current ?: return
        prefs.savePosition(video, exo.currentPosition)
    }

    override fun onResume() {
        super.onResume()
        // A dismiss drag interrupted by something else (a call, the recents switcher) would
        // otherwise leave the view part-way through the gesture.
        binding.root.resetTransform()
    }

    override fun onPause() {
        super.onPause()
        savePosition()
        player?.pause()
    }

    override fun onDestroy() {
        savePosition()
        subtitles.release()
        binding.root.removeCallbacks(remainingTicker)
        binding.root.removeCallbacks(hideChromeRunnable)
        binding.playerView.player = null
        player?.removeListener(this)
        player?.release()
        player = null
        controls.releaseAudioSession()
        controls.keepScreenOn(false)
        super.onDestroy()
    }

    companion object {
        private const val VOLUME_SENSITIVITY = 1.5f
        private const val BRIGHTNESS_SENSITIVITY = 1.5f
        /** A full-width scrub covers this much of the timeline. */
        private const val SEEK_SPAN_MS = 120_000f
        private const val HUD_LINGER_MS = 900L
        private const val ERROR_SKIP_DELAY_MS = 2_500L
        private const val DOUBLE_TAP_SEEK_MS = 10_000L
        /** Press and hold on the picture. Flat, not a multiplier — see onSpeedBoostStart. */
        private const val BOOST_SPEED = 2f
        /** How long the controls stay up once nothing is being touched. */
        private const val CONTROLS_LINGER_MS = 3_400L
        private const val CHROME_FADE_IN_MS = 180L
        private const val CHROME_FADE_OUT_MS = 220L
        /** How far the chrome slides as it fades, in pixels-per-density-unit. */
        private const val CHROME_TRAVEL_DP = 10f
        /** Past this point, "previous" restarts the current video rather than going back. */
        private const val RESTART_THRESHOLD_MS = 3_000L

        /** Offered speeds, slow to fast, with 1x in its natural place in the middle. */
        private val SPEEDS = listOf(
            0.5f, 0.75f, 0.85f, 1f, 1.15f, 1.25f, 1.35f, 1.5f, 1.75f, 2f, 2.5f, 3f,
        )

        private const val EXTRA_FOLDER_PATH = "folder_path"
        private const val EXTRA_START_ID = "start_id"
        private const val EXTRA_SHUFFLE = "shuffle"

        /**
         * Plays [video] with the rest of its folder queued behind it.
         *
         * [shuffle] turns shuffle on for this session without touching the saved setting. It is
         * what the folder's Shuffle button asks for; tapping a video afterwards still plays in
         * whatever order the player's own toggle was left in.
         */
        fun intent(context: Context, video: Video, shuffle: Boolean = false): Intent =
            intent(context, video.relativePath, video.id).apply {
                if (shuffle) putExtra(EXTRA_SHUFFLE, true)
            }

        /**
         * The same thing addressed by id, for reopening something remembered rather than
         * something in hand — the library's "last played" button has the path and the id
         * but no [Video] object, and building one would mean a query it does not need.
         */
        fun intent(context: Context, folderPath: String, videoId: Long): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_FOLDER_PATH, folderPath)
                .putExtra(EXTRA_START_ID, videoId)
    }
}
