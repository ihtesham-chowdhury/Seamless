package com.seamless.player.ui.common

import androidx.annotation.StringRes
import com.seamless.player.R
import com.seamless.player.data.SortKey

/** Display names for sort keys, shared by the folder and video lists. */
object SortLabels {

    /** Keys that make sense for a list of folders. */
    val FOLDER_KEYS =
        listOf(SortKey.COUNT, SortKey.TITLE, SortKey.DATE, SortKey.SIZE, SortKey.RANDOM)

    /** Keys that make sense for a list of videos. */
    val VIDEO_KEYS =
        listOf(SortKey.DATE, SortKey.TITLE, SortKey.SIZE, SortKey.DURATION, SortKey.RANDOM)

    @StringRes
    fun label(key: SortKey): Int = when (key) {
        SortKey.TITLE -> R.string.sort_title
        SortKey.DATE -> R.string.sort_date
        SortKey.SIZE -> R.string.sort_size
        SortKey.DURATION -> R.string.sort_duration
        SortKey.COUNT -> R.string.sort_count
        SortKey.RANDOM -> R.string.sort_random
    }
}
