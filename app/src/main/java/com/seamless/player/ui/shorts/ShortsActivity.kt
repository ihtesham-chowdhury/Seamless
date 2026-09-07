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
import androidx.viewpager2.widget.ViewPager2
import com.seamless.player.R
import com.seamless.player.SeamlessApp
import com.seamless.player.data.MediaLibrary
import com.seamless.player.data.Prefs
import com.seamless.player.data.ShortsQuery
import com.seamless.player.data.Video
import com.seamless.player.databinding.ActivityShortsBinding
import com.seamless.player.ui.common.ResizeModes
import com.seamless.player.ui.common.Tips
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

                // A fresh permutation every session: random order, and nothing repeats until
                // the whole list is exhausted.
                val shuffled = ShortsQuery.resolve(all, prefs, override).shuffled()

                // Tapping a clip in the tab means "start here", not "play only this". The
                // rest stays shuffled behind it, so the feed is still a feed.
                val startId = intent.getLongExtra(EXTRA_START_ID, 0L)
                val chosen = if (startId == 0L) null else shuffled.firstOrNull { it.id == startId }
                if (chosen == null) shuffled
                else listOf(chosen) + shuffled.filter { it.id != startId }
            },
            then = { result ->
                binding.loading.visibility = View.GONE
                if (result.isEmpty()) {
                    binding.empty.visibility = View.VISIBLE
                    return@run
                }
                videos = result
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

    private fun buildFeed() {
        mediaItems = videos.map { MediaItem.fromUri(it.uri) }
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

        /** Plays the sources saved in settings, in a fresh random order. */
        fun intent(context: Context): Intent = Intent(context, ShortsActivity::class.java)

        /** The same feed, opened on one particular clip. */
        fun intentAt(context: Context, videoId: Long): Intent =
            Intent(context, ShortsActivity::class.java).putExtra(EXTRA_START_ID, videoId)

        /** Plays one folder (and everything under it) as a shuffled feed. */
        fun intentForFolder(context: Context, folderPath: String): Intent =
            Intent(context, ShortsActivity::class.java)
                .putStringArrayListExtra(EXTRA_FOLDERS, arrayListOf(folderPath))
    }
}
