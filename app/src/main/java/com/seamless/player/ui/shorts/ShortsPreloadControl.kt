package com.seamless.player.ui.shorts

import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import kotlin.math.abs

/**
 * Tells the preload manager how much work to do for each item, based on how far it is from
 * whatever the user is watching.
 *
 * The tiers matter: buffering actual media for the immediate neighbours is what makes a
 * swipe instant, while distant items only get cheap metadata work so a folder with
 * thousands of files does not blow up memory.
 */
class ShortsPreloadControl :
    TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {

    /** Updated by the feed on every page change. */
    var currentIndex: Int = 0

    // Media3 is null-marked, so the interface's return type is non-null. "Do not preload"
    // is expressed with PRELOAD_STATUS_NOT_PRELOADED rather than with null.
    override fun getTargetPreloadStatus(rankingData: Int): DefaultPreloadManager.PreloadStatus {
        val distance = rankingData - currentIndex
        return when {
            // The next and previous clip: hold 3 seconds of decoded-ready media in memory.
            abs(distance) == 1 -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(3_000L)
            // Two away: work out the tracks, but do not buffer media yet.
            abs(distance) == 2 -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
            // Within four: just prepare the source.
            abs(distance) <= 4 -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_SOURCE_PREPARED
            else -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
        }
    }
}
