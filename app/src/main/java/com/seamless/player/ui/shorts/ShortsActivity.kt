package com.seamless.player.ui.shorts

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.ui.SubtitleView
import androidx.viewpager2.widget.ViewPager2
import com.seamless.player.R
import com.seamless.player.SeamlessApp
import com.seamless.player.data.MediaLibrary
import com.seamless.player.data.Prefs
import com.seamless.player.data.ShortsFilter
import com.seamless.player.data.ShortsQuery
import com.seamless.player.data.Video
import com.seamless.player.data.subtitle.LocalSubtitles
import com.seamless.player.data.subtitle.SubtitleStore
import com.seamless.player.databinding.ActivityShortsBinding
import com.seamless.player.ui.common.ResizeModes
import com.seamless.player.ui.common.SubtitleStyles
import com.seamless.player.ui.common.Tips
import com.seamless.player.ui.player.SubtitleTracks
import com.seamless.player.ui.player.TrackSheet
import com.seamless.player.util.Background
import com.seamless.player.util.Log
import com.seamless.player.util.ScreenControls

/**
 * The vertical, swipe-driven feed.
 *
 * Locked to portrait in the manifest — there is no landscape here by design.
 */
class ShortsActivity : AppCompatActivity(), ShortsAdapter.Host {

    private lateinit var binding: ActivityShortsBinding
    private lateinit var controls: ScreenControls
    private lateinit var prefs: Prefs

    private var videos: List<Video> = emptyList()
    private var mediaItems: List<MediaItem> = emptyList()

    /**
     * Subtitles in the feed, and the deliberate limit on them.
     *
     * Two sources: text tracks inside the clip, which cost nothing because they are already in
     * the container, and anything downloaded for that clip before, read from the store in one
     * directory listing for the whole feed. What is *not* here is looking in each clip's folder
     * for a companion file — that would be a directory listing per page while the user's thumb is
     * moving, to answer a question whose answer is "no" for every clip anyone has ever filmed.
     *
     * There is no online search here either. It works by matching a release name against a
     * database of films; `VID_20240817_204411.mp4` gives it nothing to match, so the button would
     * be an invitation to fail.
     */
    private val subtitleStore by lazy { SubtitleStore(this) }
    private var savedSubtitles: Map<String, List<SubtitleStore.Saved>> = emptyMap()

    private val preloadControl = ShortsPreloadControl()
    private var preloadManager: DefaultPreloadManager? = null
    private var playerPool: PlayerPool? = null
    private var feedAdapter: ShortsAdapter? = null

    /** Indices currently registered with the preload manager. */
    private val preloadWindow = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShortsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = (application as SeamlessApp).prefs
        controls = ScreenControls(this)
        controls.keepScreenOn(true)
        controls.restoreBrightness(prefs.playerBrightness)
        goImmersive()

        binding.pager.orientation = ViewPager2.ORIENTATION_VERTICAL
        // Keep exactly one page alive either side, which is what the pool is sized for.
        binding.pager.offscreenPageLimit = 1
        binding.pager.registerOnPageChangeCallback(pageCallback)

        wireButtons()
        loadVideos()
        // Room.NONE: the feed is fullscreen and immersive, so there is no navigation bar
        // to sit above.
        Tips.showOnce(this, prefs, Tips.SHORTS, R.string.tip_shorts, Tips.Room.NONE)
    }

    /** Hides the feed's overlay again once it has gone unused. */
    private val hideOverlay = Runnable {
        if (binding.lockOverlay.isLocked) return@Runnable
        binding.actions.visibility = View.GONE
        binding.btnClose.visibility = View.GONE
    }

    /**
     * Brings the close button and action row up for a few seconds. The feed shows nothing
     * at all while a clip is playing — chrome standing permanently over a full-bleed video
     * is exactly what the format is not.
     */
    private fun revealOverlay() {
        if (binding.lockOverlay.isLocked) return
        updateSubtitleButton()
        binding.actions.visibility = View.VISIBLE
        binding.btnClose.visibility = View.VISIBLE
        binding.root.removeCallbacks(hideOverlay)
        binding.root.postDelayed(hideOverlay, OVERLAY_LINGER_MS)
    }

    private fun wireButtons() {
        binding.btnClose.setOnClickListener { finish() }

        binding.btnResize.setOnClickListener {
            val next = ResizeModes.next(prefs.shortsResizeMode)
            prefs.shortsResizeMode = next
            feedAdapter?.refreshResizeMode()
            showHud(getString(ResizeModes.label(next)))
        }

        binding.btnFavourite.setOnClickListener {
            val position = binding.pager.currentItem
            if (position in videos.indices) {
                applyFavouriteIcon(prefs.toggleFavourite(videos[position].id))
            }
        }

        binding.btnSubtitles.setOnClickListener { showSubtitleSheet() }

        binding.btnLock.setOnClickListener {
            binding.root.removeCallbacks(hideOverlay)
            binding.actions.visibility = View.GONE
            binding.btnClose.visibility = View.GONE
            binding.pager.isUserInputEnabled = false
            feedAdapter?.gesturesEnabled = false
            binding.lockOverlay.lock(prefs.unlockMethod)
        }
        binding.lockOverlay.onUnlocked = {
            binding.pager.isUserInputEnabled = true
            feedAdapter?.gesturesEnabled = true
            // Back to a clear screen, not to the overlay that was up before locking.
        }
    }

    // ---- loading ----

    private fun loadVideos() {
        binding.loading.visibility = View.VISIBLE
        Background.run(
            work = {
                val all = MediaLibrary.queryAll(this, prefs.foldersExcludedFromFeed)
                // One folder handed in by the library overrides the saved settings; that is
                // "play this folder as a feed", not a change of preference.
                val override = intent.getStringArrayListExtra(EXTRA_FOLDERS)?.toSet()

                // The quick view the tab was showing. Without this the feed played
                // everything regardless: tapping a clip in Favourites gave that clip and
                // then nine hundred strangers, which is not what a filtered wall promises.
                val filter = ShortsFilter.from(intent.getStringExtra(EXTRA_FILTER))

                // A fresh permutation every session: random order, and nothing repeats until
                // the whole list is exhausted.
                val shuffled = ShortsQuery.resolve(all, prefs, override, filter).shuffled()

                // Tapping a clip in the tab means "start here", not "play only this". The
                // rest stays shuffled behind it, so the feed is still a feed.
                val startId = intent.getLongExtra(EXTRA_START_ID, 0L)
                val chosen = if (startId == 0L) null else shuffled.firstOrNull { it.id == startId }
                val ordered = if (chosen == null) shuffled
                else listOf(chosen) + shuffled.filter { it.id != startId }

                // One listing for the whole feed, on the thread that is already reading storage.
                ordered to subtitleStore.savedByVideo()
            },
            then = { (result, saved) ->
                binding.loading.visibility = View.GONE
                if (result.isEmpty()) {
                    binding.empty.visibility = View.VISIBLE
                    return@run
                }
                videos = result
                savedSubtitles = saved
                startEngine()
                buildFeed()
            },
        )
    }

    /** Creates the preload manager and the player pool, which must share components. */
    private fun startEngine() {
        // Local files need almost no buffer. Small numbers here mean playback starts on the
        // first decoded frame instead of waiting for a comfortable cushion to fill.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 1_000,
                /* maxBufferMs = */ 10_000,
                /* bufferForPlaybackMs = */ 200,
                /* bufferForPlaybackAfterRebufferMs = */ 500,
            )
            .build()

        val builder = DefaultPreloadManager.Builder(this, preloadControl)
            .setLoadControl(loadControl)
            // If the preferred decoder refuses a clip, try the next one that claims the
            // format instead of failing outright. Costs nothing when the first one works.
            .setRenderersFactory(
                DefaultRenderersFactory(this).setEnableDecoderFallback(true)
            )

        // The manager and every player must come from the same builder so they share the
        // load control, allocator and playback thread.
        preloadManager = builder.build()
        playerPool = PlayerPool(POOL_SIZE) { builder.buildExoPlayer() }
    }

    /**
     * The plain item where there is nothing to attach, which is nearly always.
     *
     * That matters more than it looks: a media item with subtitle configurations becomes a
     * merging source rather than a single one, and the preload manager is the most delicate thing
     * in this app. Keeping the common case byte-for-byte what it was means the feed's engine is
     * unchanged for every clip that has no subtitle.
     */
    private fun mediaItemFor(video: Video): MediaItem {
        val saved = savedSubtitles[subtitleStore.keyFor(video)].orEmpty()
        if (saved.isEmpty()) return MediaItem.fromUri(video.uri)
        return MediaItem.Builder()
            .setUri(video.uri)
            .setSubtitleConfigurations(
                LocalSubtitles.fromStore(saved).map { it.toConfiguration() },
            )
            .build()
    }

    private fun buildFeed() {
        mediaItems = videos.map { mediaItemFor(it) }
        feedAdapter = ShortsAdapter(videos, this).also { binding.pager.adapter = it }
        updatePreloadWindow(0)
        feedAdapter?.focusedPosition = 0
        // The page-change callback does not fire for the page the feed opens on.
        videos.firstOrNull()?.let { applyFavouriteIcon(prefs.isFavourite(it.id)) }
        Log.d("ShortsActivity", "feed ready with ${videos.size} videos")
    }

    private val pageCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            updatePreloadWindow(position)
            feedAdapter?.focusedPosition = position
            videos.getOrNull(position)?.let { applyFavouriteIcon(prefs.isFavourite(it.id)) }
            updateSubtitleButton()
        }
    }

    /**
     * Marks the clip on screen, from the double tap or from the button; they are the same
     * action and share the same state.
     */
    override fun onFavouriteToggled(index: Int): Boolean {
        val video = videos.getOrNull(index) ?: return false
        val now = prefs.toggleFavourite(video.id)
        if (index == binding.pager.currentItem) applyFavouriteIcon(now)
        return now
    }

    /** A page has worked out what is in its clip. */
    override fun onTextTracksKnown(index: Int) {
        if (index == binding.pager.currentItem) updateSubtitleButton()
    }

    override fun subtitleStyle(view: SubtitleView) {
        SubtitleStyles.apply(view, prefs)
    }

    /**
     * The CC button, present only for a clip that actually has captions.
     *
     * Recomputed rather than remembered, because the page a position refers to changes as the
     * pool hands players around, and a cached answer would be for the clip before last.
     */
    private fun updateSubtitleButton() {
        val player = feedAdapter?.focusedPlayer()
        val has = player != null && SubtitleTracks.textOptions(player).isNotEmpty()
        binding.btnSubtitles.visibility = if (has) View.VISIBLE else View.GONE
    }

    /**
     * The same panel the ordinary player uses, with one fewer thing on it.
     *
     * No "find subtitles" and no folder permission: neither belongs in a feed of camera clips.
     * Appearance does, because a caption that is too small is too small everywhere.
     */
    private fun showSubtitleSheet() {
        val player = feedAdapter?.focusedPlayer() ?: return
        val options = SubtitleTracks.textOptions(player)
        if (options.isEmpty()) return

        val rows = mutableListOf(
            TrackSheet.Row(
                label = getString(R.string.subtitle_off),
                detail = "",
                selected = SubtitleTracks.selected(options) == null,
                onClick = { SubtitleTracks.disableText(player) },
            )
        )
        options.forEachIndexed { index, option ->
            rows += TrackSheet.Row(
                label = SubtitleTracks.label(this, option, index),
                detail = SubtitleTracks.detail(this, option),
                selected = option.isSelected,
                onClick = { SubtitleTracks.select(player, option) },
            )
        }
        // The overlay would withdraw on its own timer while the panel was open, so hold it —
        // and start it again on the way out, or it would stay up until the next tap.
        binding.root.removeCallbacks(hideOverlay)
        TrackSheet(getString(R.string.subtitles), rows).show(this).setOnDismissListener {
            binding.root.postDelayed(hideOverlay, OVERLAY_LINGER_MS)
        }
    }

    /** Filled when this clip is a favourite, outlined when it is not. */
    private fun applyFavouriteIcon(favourite: Boolean) {
        binding.btnFavourite.setImageResource(
            if (favourite) R.drawable.ic_heart else R.drawable.ic_heart_outline
        )
    }

    /**
     * Keeps a sliding window of items registered with the preload manager.
     *
     * Registering all of them would mean thousands of wrapper sources sitting in memory for
     * a large folder, so items far from the user are dropped and re-added as they approach.
     */
    private fun updatePreloadWindow(center: Int) {
        val manager = preloadManager ?: return
        val low = (center - PRELOAD_WINDOW).coerceAtLeast(0)
        val high = (center + PRELOAD_WINDOW).coerceAtMost(videos.lastIndex)
        val wanted = (low..high).toSet()

        (preloadWindow - wanted).forEach { manager.remove(mediaItems[it]) }
        (wanted - preloadWindow).forEach { manager.add(mediaItems[it], it) }

        preloadWindow.clear()
        preloadWindow += wanted

        preloadControl.currentIndex = center
        manager.setCurrentPlayingIndex(center)
        manager.invalidate()
    }

    // ---- end of the list ----

    private fun showEndOfList() {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle(R.string.end_of_list_title)
            .setMessage(getString(R.string.end_of_list_message, videos.size))
            .setPositiveButton(R.string.end_of_list_again) { _, _ -> reshuffle() }
            .setNegativeButton(R.string.end_of_list_stop) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    /** Starts a new session: new random order, everything eligible again. */
    private fun reshuffle() {
        feedAdapter?.releaseAll()
        binding.pager.adapter = null
        preloadManager?.reset()
        preloadWindow.clear()
        videos = videos.shuffled()
        buildFeed()
        binding.pager.setCurrentItem(0, false)
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

    // ---- ShortsAdapter.Host ----

    override fun acquirePlayer(): ExoPlayer? = playerPool?.acquire()

    override fun recyclePlayer(player: ExoPlayer) {
        playerPool?.recycle(player)
    }

    override fun playExclusively(player: ExoPlayer) {
        playerPool?.playExclusively(player)
    }

    override fun mediaItemAt(index: Int): MediaItem = mediaItems[index]

    override fun preloadedSourceAt(index: Int): MediaSource? =
        preloadManager?.getMediaSource(mediaItems[index])

    override fun screenControls(): ScreenControls = controls

    override fun autoAdvance(): Boolean = prefs.shortsAutoAdvance

    override fun resizeMode(): Int = ResizeModes.toMedia3(prefs.shortsResizeMode)

    override fun posterScale(): ImageView.ScaleType =
        ResizeModes.toImageScale(prefs.shortsResizeMode)

    override fun onVideoEnded(index: Int) {
        if (index < videos.lastIndex) {
            binding.pager.setCurrentItem(index + 1, true)
        } else {
            showEndOfList()
        }
    }

    /** A clip that will not decode should not strand the feed; skip past it. */
    override fun onVideoFailed(index: Int, video: Video, error: PlaybackException) {
        Log.e("ShortsActivity", "skipping ${video.name}: ${error.errorCodeName}", error)
        binding.pager.postDelayed({
            if (!isFinishing && !binding.lockOverlay.isLocked) onVideoEnded(index)
        }, ERROR_SKIP_DELAY_MS)
    }

    override fun onBrightnessChanged(value: Float) {
        prefs.playerBrightness = value
    }

    override fun onTapped() = revealOverlay()

    // ---- lifecycle ----

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onBackPressed() {
        // Back must not escape the lock, or the lock would be pointless.
        if (binding.lockOverlay.isLocked) return
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onStop() {
        super.onStop()
        playerPool?.pauseAll()
    }

    override fun onDestroy() {
        binding.root.removeCallbacks(hideOverlay)
        binding.pager.unregisterOnPageChangeCallback(pageCallback)
        feedAdapter?.releaseAll()
        binding.pager.adapter = null
        playerPool?.destroy()
        preloadManager?.release()
        controls.keepScreenOn(false)
        super.onDestroy()
    }

    companion object {
        /**
         * One per page that can be alive at once, with a little headroom.
         *
         * ViewPager2 at offscreenPageLimit = 1 keeps three pages attached, and during a fast
         * fling a fourth is laid out before the one behind is taken away. Four was exactly
         * the steady-state figure and left nothing for that overlap, so a flung page could
         * find the pool empty. Five decoders is still comfortable — hardware AVC decoding
         * runs to eight or more on any device this app supports — and the waiting queue in
         * ShortsAdapter covers the case where even that is not enough.
         */
        private const val POOL_SIZE = 5
        private const val PRELOAD_WINDOW = 12
        private const val HUD_LINGER_MS = 900L
        private const val OVERLAY_LINGER_MS = 3_000L
        private const val ERROR_SKIP_DELAY_MS = 2_000L

        private const val EXTRA_FOLDERS = "folders"
        private const val EXTRA_START_ID = "start_id"
        private const val EXTRA_FILTER = "filter"

        /** Plays the sources saved in settings, narrowed by [filter], in a random order. */
        fun intent(context: Context, filter: ShortsFilter = ShortsFilter.ALL): Intent =
            Intent(context, ShortsActivity::class.java).putExtra(EXTRA_FILTER, filter.name)

        /** The same feed, opened on one particular clip. */
        fun intentAt(
            context: Context,
            videoId: Long,
            filter: ShortsFilter = ShortsFilter.ALL,
        ): Intent = intent(context, filter).putExtra(EXTRA_START_ID, videoId)

        /** Plays one folder (and everything under it) as a shuffled feed. */
        fun intentForFolder(context: Context, folderPath: String): Intent =
            Intent(context, ShortsActivity::class.java)
                .putStringArrayListExtra(EXTRA_FOLDERS, arrayListOf(folderPath))
    }
}
