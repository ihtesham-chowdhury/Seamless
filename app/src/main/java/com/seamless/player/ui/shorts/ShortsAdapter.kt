package com.seamless.player.ui.shorts

import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.ViewGroup
import android.widget.ImageView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.recyclerview.widget.RecyclerView
import com.seamless.player.R
import com.seamless.player.data.Video
import com.seamless.player.databinding.PageShortBinding
import com.seamless.player.ui.common.LevelHudView
import com.seamless.player.ui.common.MediaInfo
import com.seamless.player.util.Log
import com.seamless.player.util.ScreenControls
import com.seamless.player.util.Thumbnails
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One page per video in the shorts feed.
 *
 * Each page borrows a player from [PlayerPool] while it is on screen or next to it, and
 * hands it back the moment it leaves. Because the neighbouring pages already hold their own
 * prepared player, moving between them costs about a frame instead of a decoder init.
 *
 * The borrowing is tied to **window attachment**, not to binding, and that distinction is
 * the whole reason this file was rewritten. See [attachPlayer].
 */
class ShortsAdapter(
    private val videos: List<Video>,
    private val host: Host,
) : RecyclerView.Adapter<ShortsAdapter.PageHolder>() {

    /** Everything the pages need from the activity. */
    interface Host {
        fun acquirePlayer(): ExoPlayer?
        fun recyclePlayer(player: ExoPlayer)
        fun playExclusively(player: ExoPlayer)
        fun mediaItemAt(index: Int): MediaItem
        fun preloadedSourceAt(index: Int): MediaSource?
        fun screenControls(): ScreenControls
        fun autoAdvance(): Boolean
        /** A Media3 AspectRatioFrameLayout resize constant. */
        fun resizeMode(): Int
        /** The matching ImageView scale type, for the poster frame. */
        fun posterScale(): ImageView.ScaleType
        fun onVideoEnded(index: Int)
        fun onVideoFailed(index: Int, video: Video, error: PlaybackException)
        fun onBrightnessChanged(value: Float)
        /** The user touched a page; the feed uses this to reveal its overlay. */
        fun onTapped()

        /** Marks or unmarks the clip at [index]; reports what it now is. */
        fun onFavouriteToggled(index: Int): Boolean
    }

    /** The page the user is actually looking at. Only this one plays. */
    var focusedPosition: Int = 0
        set(value) {
            field = value
            attached.forEach { it.updateFocus(value) }
        }

    /** Cleared while the screen lock is engaged. */
    var gesturesEnabled: Boolean = true
        set(value) {
            field = value
            attached.forEach { it.applyGestureState(value) }
        }

    private val attached = mutableSetOf<PageHolder>()

    /**
     * Pages that asked for a player while every one of them was spoken for.
     *
     * This queue is the fix for the black pages. Previously a page that could not get a
     * player simply gave up and stayed empty for as long as you looked at it — no error, no
     * retry, nothing to see. Now it waits its turn and is served the instant one is handed
     * back.
     */
    private val waiting = mutableSetOf<PageHolder>()

    /**
     * Feed positions whose preloaded source is currently driven by a player.
     *
     * A PreloadMediaSource is a single-owner object. If two pages ever hold the same one at
     * once — which a rebind overlapping a recycle can arrange — the second gets a source
     * already being torn down by the first, and renders nothing. Positions are claimed here
     * and the plain MediaItem is used instead when a claim cannot be made.
     */
    private val sourcesInUse = mutableSetOf<Int>()

    override fun getItemCount() = videos.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val binding = PageShortBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PageHolder(binding)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) = holder.bind(position)

    override fun onViewAttachedToWindow(holder: PageHolder) {
        attached += holder
        holder.applyGestureState(gesturesEnabled)
        holder.attachPlayer()
        holder.updateFocus(focusedPosition)
    }

    /**
     * Releasing here rather than only in [onViewRecycled] is the other half of the fix.
     *
     * RecyclerView does not recycle a view the moment it scrolls away: it parks it in an
     * internal cache first, and only calls onViewRecycled when that cache overflows. Pages
     * sitting in the cache therefore kept holding a player each. Three visible pages plus
     * two parked ones needed five players from a pool of four, so once a few swipes had gone
     * by the pool was permanently empty and new pages got nothing — the black clips.
     *
     * Detachment is the honest signal that a page is no longer on screen or next to it.
     */
    override fun onViewDetachedFromWindow(holder: PageHolder) {
        attached -= holder
        waiting -= holder
        holder.releasePlayer()
        serveWaitingPages()
    }

    override fun onViewRecycled(holder: PageHolder) {
        attached -= holder
        waiting -= holder
        holder.releasePlayer()
        serveWaitingPages()
    }

    /** Re-applies the resize mode to every live page after the user changes it. */
    fun refreshResizeMode() {
        attached.forEach { it.applyResizeMode() }
    }

    /** Called by the activity when it is going away, so no player is left holding a surface. */
    fun releaseAll() {
        attached.toList().forEach { it.releasePlayer() }
        attached.clear()
        waiting.clear()
        sourcesInUse.clear()
    }

    /**
     * Hands freed players to the waiting pages that need them most — nearest to what the
     * user is looking at first, since those are the ones about to be noticed.
     *
     * Called after releasing, never from inside the release itself. That ordering matters:
     * a page being rebound releases its player and then takes one straight back, and if the
     * queue were served in between, the page the user is actually swiping to could lose the
     * player to one parked off screen.
     */
    private fun serveWaitingPages() {
        while (true) {
            val next = waiting
                .filter { it.wantsPlayer }
                .minByOrNull { abs(it.index - focusedPosition) }
                ?: return
            waiting -= next
            next.attachPlayer()
            // attachPlayer puts itself back in the queue if the pool was empty, which means
            // there is nothing left to hand out and no point continuing.
            if (next in waiting) return
        }
    }

    inner class PageHolder(
        private val binding: PageShortBinding,
    ) : RecyclerView.ViewHolder(binding.root), GestureOverlayLayout.Listener, Player.Listener {

        private var player: ExoPlayer? = null

        /** Read by the adapter when deciding which waiting page to serve first. */
        var index: Int = RecyclerView.NO_POSITION
            private set

        private var inFocus = false

        /** True while this page holds the claim on its position's preloaded source. */
        private var usingPreloadedSource = false

        /** Set once we have already retried this item without its preloaded source. */
        private var retriedWithoutPreload = false

        /** Cleared on bind, set by onRenderedFirstFrame. Drives the black-page watchdog. */
        private var hasRenderedFrame = false

        /** A bound page with no player is one the adapter still owes something to. */
        val wantsPlayer: Boolean
            get() = player == null && index != RecyclerView.NO_POSITION

        /**
         * Catches the failure mode that reports nothing: the player says it is playing, but
         * not one frame has reached the screen. Without this such a clip is simply a black
         * page for as long as you look at it.
         */
        private val blackFrameWatchdog = object : Runnable {
            // Written as an object, not a lambda, because it reschedules itself: inside a
            // Runnable { } lambda "this" would be the view holder, not the Runnable.
            override fun run() {
                val exo = player ?: return
                if (hasRenderedFrame || !inFocus) return
                val video = videos.getOrNull(index) ?: return

                if (!retriedWithoutPreload) {
                    retriedWithoutPreload = true
                    Log.w("ShortsAdapter", "no first frame for ${video.name}; retrying unpreloaded")
                    releaseSourceClaim()
                    exo.setMediaItem(host.mediaItemAt(index))
                    exo.prepare()
                    exo.playWhenReady = true
                    binding.root.postDelayed(this, FIRST_FRAME_TIMEOUT_MS)
                    return
                }

                Log.e("ShortsAdapter", "gave up on ${video.name}: decoding but never rendered")
                showFailure(
                    video.name,
                    binding.root.context.getString(
                        R.string.playback_no_frames,
                        MediaInfo.shortDescription(exo.videoFormat),
                    ),
                )
            }
        }

        /** Redraws the thin position bar four times a second while playing. */
        private val ticker = object : Runnable {
            override fun run() {
                val p = player ?: return
                val duration = p.duration
                if (duration > 0) {
                    binding.progress.progress =
                        ((p.currentPosition.toFloat() / duration) * 1000).roundToInt()
                }
                binding.root.postDelayed(this, 250)
            }
        }

        init {
            // binding.root *is* the GestureOverlayLayout; view binding does not expose the
            // root under its own id.
            binding.root.listener = this
        }

        /**
         * Sets up everything that does not need a player. The player is taken separately,
         * on attachment, because binding happens well before a page reaches the screen and
         * can happen again for a page that never left it.
         */
        fun bind(position: Int) {
            // A rebind means a different clip, so anything held for the old one is wrong now.
            releasePlayer()

            index = position
            val video = videos[position]

            binding.title.text = video.name
            binding.progress.progress = 0
            binding.levelHud.visibility = View.GONE
            binding.speedBadge.visibility = View.GONE
            binding.favouriteBurst.animate().cancel()
            binding.favouriteBurst.alpha = 0f
            binding.errorMessage.visibility = View.GONE
            binding.poster.visibility = View.VISIBLE
            Thumbnails.load(binding.poster, video, POSTER_SIZE)
            applyResizeMode()

            // A cached page can be handed back already attached, in which case no attach
            // callback is coming and this is the only chance to ask for a player.
            if (binding.root.isAttachedToWindow) attachPlayer()
            // Only now: this page had first refusal on the player it just gave up.
            serveWaitingPages()
        }

        /**
         * Borrows a player and prepares this page's clip on it. Safe to call more than once;
         * a page that already has one keeps it.
         */
        fun attachPlayer() {
            if (player != null || index == RecyclerView.NO_POSITION) return

            val exo = host.acquirePlayer()
            if (exo == null) {
                // Not fatal, and no longer silent: get in line instead of staying blank.
                waiting += this
                Log.w("ShortsAdapter", "no player free for $index; page queued")
                return
            }
            waiting -= this

            player = exo
            hasRenderedFrame = false
            retriedWithoutPreload = false
            binding.poster.visibility = View.VISIBLE
            binding.errorMessage.visibility = View.GONE

            exo.addListener(this)
            exo.repeatMode =
                if (host.autoAdvance()) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE

            // Prefer the preloaded source, unless another page still holds the claim on it,
            // and fall back to the raw item if preloading has not caught up with a fast
            // scroll. Either way this page ends up with something to play.
            usingPreloadedSource = false
            val claimed = sourcesInUse.add(index)
            val source = if (claimed) host.preloadedSourceAt(index) else null
            if (source != null) {
                usingPreloadedSource = true
                exo.setMediaSource(source)
            } else {
                // Give back a claim we took but are not going to use. Only ours: if the add
                // failed, the claim belongs to another page and must be left alone.
                if (claimed) sourcesInUse -= index
                exo.setMediaItem(host.mediaItemAt(index))
            }

            exo.playWhenReady = false
            exo.prepare()
            binding.playerView.player = exo

            // Recomputed rather than trusted: a page can be handed a player after the focus
            // has already moved onto it, and the old code left such a page paused for ever.
            inFocus = index == focusedPosition
            applyFocus()
        }

        /**
         * Hands the player back to the pool. Deliberately does *not* pass it on to a
         * waiting page — see [serveWaitingPages] for why that has to happen afterwards.
         */
        fun releasePlayer() {
            binding.root.removeCallbacks(ticker)
            binding.root.removeCallbacks(blackFrameWatchdog)
            binding.playerView.player = null
            inFocus = false

            val exo = player ?: return
            player = null
            releaseSourceClaim()
            exo.removeListener(this)
            host.recyclePlayer(exo)
        }

        /** Gives up this page's claim on its preloaded source, if it holds one. */
        private fun releaseSourceClaim() {
            if (!usingPreloadedSource) return
            usingPreloadedSource = false
            sourcesInUse -= index
        }

        fun applyGestureState(enabled: Boolean) {
            binding.root.gesturesEnabled = enabled
        }

        fun applyResizeMode() {
            binding.playerView.resizeMode = host.resizeMode()
            binding.poster.scaleType = host.posterScale()
        }

        /** Starts or stops playback depending on whether this page is the visible one. */
        fun updateFocus(focused: Int) {
            val shouldPlay = index != RecyclerView.NO_POSITION && index == focused
            if (shouldPlay == inFocus) return
            inFocus = shouldPlay
            applyFocus()
        }

        /**
         * Acts on [inFocus]. Kept separate from [updateFocus] so that a page which receives
         * its player late can still start playing without a focus *change* to trigger it.
         */
        private fun applyFocus() {
            val exo = player ?: return
            if (inFocus) {
                host.playExclusively(exo)
                binding.root.post(ticker)
                // Only arm the watchdog for the page actually on screen; a neighbour that
                // has not rendered yet is simply not playing.
                binding.root.removeCallbacks(blackFrameWatchdog)
                if (!hasRenderedFrame) {
                    binding.root.postDelayed(blackFrameWatchdog, FIRST_FRAME_TIMEOUT_MS)
                }
            } else {
                exo.pause()
                exo.seekTo(0)
                binding.root.removeCallbacks(ticker)
                binding.root.removeCallbacks(blackFrameWatchdog)
            }
        }

        // ---- playback events ----

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && inFocus && host.autoAdvance()) {
                host.onVideoEnded(index)
            }
        }

        /** Proof that pixels actually reached the screen; stands the watchdog down. */
        override fun onRenderedFirstFrame() {
            hasRenderedFrame = true
            binding.root.removeCallbacks(blackFrameWatchdog)
            binding.errorMessage.visibility = View.GONE
            // The video is now on screen behind it, so the still can go.
            binding.poster.visibility = View.GONE
        }

        /**
         * A failure here used to show as a silent black page. Now it retries once without the
         * preloaded source — which rules out preloading as the cause — and otherwise reports
         * the real decoder error on screen.
         */
        override fun onPlayerError(error: PlaybackException) {
            val exo = player ?: return
            val video = videos.getOrNull(index) ?: return
            Log.e("ShortsAdapter", "playback failed for ${video.name}", error)

            if (!retriedWithoutPreload) {
                retriedWithoutPreload = true
                Log.d("ShortsAdapter", "retrying ${video.name} without the preloaded source")
                releaseSourceClaim()
                exo.setMediaItem(host.mediaItemAt(index))
                exo.prepare()
                if (inFocus) exo.playWhenReady = true
                return
            }

            showFailure(
                video.name,
                "${error.errorCodeName}\n" +
                    "${error.cause?.message ?: error.message.orEmpty()}\n" +
                    MediaInfo.shortDescription(exo.videoFormat),
            )
            if (inFocus) host.onVideoFailed(index, video, error)
        }

        /** Puts the reason on the page instead of leaving a blank rectangle. */
        private fun showFailure(name: String, detail: String) {
            binding.root.removeCallbacks(blackFrameWatchdog)
            binding.errorMessage.text = binding.root.context.getString(
                R.string.playback_failed_detail,
                binding.root.context.getString(R.string.playback_failed, name),
                detail,
            )
            binding.errorMessage.visibility = View.VISIBLE
        }

        // ---- gestures ----

        override fun onDragStart(rightHalf: Boolean) = showHud(rightHalf, 0f)

        override fun onDrag(rightHalf: Boolean, delta: Float) = showHud(rightHalf, delta)

        override fun onDragEnd() {
            binding.levelHud.animate().alpha(0f).setDuration(250).withEndAction {
                binding.levelHud.visibility = View.GONE
                binding.levelHud.alpha = 1f
            }
        }

        override fun onSingleTap() {
            val exo = player ?: return
            if (exo.isPlaying) exo.pause() else host.playExclusively(exo)
            // A tap is also how the feed's chrome is summoned; pausing and revealing the
            // controls belong together.
            host.onTapped()
        }

        override fun onDoubleTap(zone: GestureOverlayLayout.TapZone) {
            if (zone == GestureOverlayLayout.TapZone.MIDDLE) {
                showFavourite(host.onFavouriteToggled(index))
                return
            }
            val exo = player ?: return
            val step =
                if (zone == GestureOverlayLayout.TapZone.RIGHT) SEEK_STEP_MS else -SEEK_STEP_MS
            exo.seekTo((exo.currentPosition + step).coerceAtLeast(0))
        }

        /**
         * A heart, stamped over the picture and gone again.
         *
         * Deliberately short and deliberately not a burst of particles: this is a personal
         * library, and marking a file you own should feel like a note to yourself rather
         * than like a reaction posted somewhere. The outline is what removing looks like, so
         * the same gesture never leaves you guessing which way it went.
         */
        private fun showFavourite(favourite: Boolean) {
            val view = binding.favouriteBurst
            view.setImageResource(
                if (favourite) R.drawable.ic_heart_badge else R.drawable.ic_heart_burst_off
            )
            view.animate().cancel()
            view.alpha = 0f
            view.scaleX = 0.55f
            view.scaleY = 0.55f
            view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(FAVOURITE_IN_MS)
                .setInterpolator(OvershootInterpolator(2.4f))
                .withEndAction {
                    view.animate()
                        .alpha(0f)
                        .scaleX(0.92f)
                        .scaleY(0.92f)
                        .setStartDelay(FAVOURITE_HOLD_MS)
                        .setDuration(FAVOURITE_OUT_MS)
                        .setInterpolator(null)
                        .start()
                }
                .start()
        }

        override fun onSpeedBoostStart() {
            player?.setPlaybackSpeed(BOOST_SPEED)
            binding.speedBadge.visibility = View.VISIBLE
        }

        override fun onSpeedBoostEnd() {
            player?.setPlaybackSpeed(1f)
            binding.speedBadge.visibility = View.GONE
        }

        /**
         * Left half of the screen adjusts brightness, right half adjusts volume — both on a
         * horizontal drag, because vertical belongs to the pager here.
         *
         * The readout itself is the same vertical level the ordinary player uses. The
         * gesture differs between the two modes out of necessity; the thing it moves should
         * not, or the same quantity would look like two different quantities.
         */
        private fun showHud(rightHalf: Boolean, delta: Float) {
            val controls = host.screenControls()
            if (rightHalf) {
                if (delta != 0f) {
                    val step = delta * controls.maxVolume * VOLUME_SENSITIVITY
                    controls.volume = (controls.volume + step.roundToInt())
                        .coerceIn(0, controls.maxVolume)
                }
                val fraction = controls.volume.toFloat() / controls.maxVolume.coerceAtLeast(1)
                binding.levelHud.show(
                    LevelHudView.Kind.VOLUME,
                    level = fraction,
                    label = "${(fraction * 100).roundToInt()}%",
                )
            } else {
                if (delta != 0f) {
                    controls.brightness = controls.brightness + delta * BRIGHTNESS_SENSITIVITY
                    host.onBrightnessChanged(controls.brightness)
                }
                val automatic = controls.isBrightnessAutomatic
                binding.levelHud.show(
                    LevelHudView.Kind.BRIGHTNESS,
                    level = if (automatic) 0f else controls.brightness,
                    label = if (automatic) {
                        binding.root.context.getString(R.string.hud_auto_short)
                    } else {
                        "${(controls.brightness * 100).roundToInt()}%"
                    },
                    automatic = automatic,
                )
            }
            binding.levelHud.animate().cancel()
            binding.levelHud.alpha = 1f
            binding.levelHud.visibility = View.VISIBLE
        }
    }

    private companion object {
        /**
         * How long a focused clip may claim to be playing without producing a frame before
         * we treat it as broken. Generous enough not to fire on a slow first decode.
         */
        const val FIRST_FRAME_TIMEOUT_MS = 2_500L
        const val SEEK_STEP_MS = 5_000L
        const val FAVOURITE_IN_MS = 190L
        const val FAVOURITE_HOLD_MS = 260L
        const val FAVOURITE_OUT_MS = 170L
        const val BOOST_SPEED = 2f
        /** A full-width drag moves volume through this many times the full range. */
        const val VOLUME_SENSITIVITY = 1.5f
        const val BRIGHTNESS_SENSITIVITY = 1.5f
        /** Bigger than a library thumbnail: this one is shown full screen. */
        val POSTER_SIZE = Size(480, 854)
    }
}
