package com.seamless.player.ui.shorts

import androidx.media3.exoplayer.ExoPlayer
import com.seamless.player.util.Log

/**
 * A small, fixed set of ExoPlayer instances shared by the pages of the feed.
 *
 * This is the thing that removes the gap between clips. A conventional player owns one
 * ExoPlayer and, for every file, runs setMediaItem -> prepare -> play; the MediaCodec
 * configure plus first-keyframe decode inside that costs roughly 100-300ms even from local
 * storage, and that is the black frame you see in VLC or MX Player. Here the neighbouring
 * pages already hold their own prepared player, so a swipe only changes which surface is
 * visible.
 *
 * Not thread-safe by design: every call happens on the main thread.
 */
class PlayerPool(
    private val maxPlayers: Int,
    private val factory: () -> ExoPlayer,
) {
    private val all = ArrayList<ExoPlayer>(maxPlayers)
    private val available = ArrayDeque<ExoPlayer>()

    /** Returns a free player, creating one if the pool has not reached its ceiling yet. */
    fun acquire(): ExoPlayer? {
        available.removeFirstOrNull()?.let { return it }
        if (all.size < maxPlayers) {
            val player = factory()
            all += player
            Log.d("PlayerPool", "created player ${all.size}/$maxPlayers")
            return player
        }
        // Every player is spoken for. The caller shows a placeholder and tries again when a
        // page recycles; in practice this is very rare with offscreenPageLimit = 1.
        Log.w("PlayerPool", "pool exhausted")
        return null
    }

    /** Hands a player back. Kept alive and reused — releasing would defeat the whole point. */
    fun recycle(player: ExoPlayer) {
        player.pause()
        player.stop()
        player.clearMediaItems()
        if (available.none { it === player }) available.addLast(player)
    }

    /** Plays [target] and makes sure nothing else in the pool is making noise. */
    fun playExclusively(target: ExoPlayer) {
        all.forEach { if (it !== target) it.pause() }
        target.playWhenReady = true
    }

    fun pauseAll() = all.forEach { it.pause() }

    fun destroy() {
        all.forEach { it.release() }
        all.clear()
        available.clear()
    }
}
