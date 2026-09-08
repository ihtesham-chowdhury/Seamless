package com.seamless.player.ui.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import com.seamless.player.R
import com.seamless.player.data.subtitle.SubtitleLanguages
import com.seamless.player.data.subtitle.SubtitleOrigin

/**
 * Reading and choosing tracks, without a second subtitle system.
 *
 * Everything here goes through Media3's own track selection. Subtitles found in the folder are
 * attached to the media item as subtitle configurations, so by the time they reach this file they
 * are text tracks exactly like the ones inside the container, and one code path selects either.
 * There is no parallel renderer, no timing loop of our own, and nothing to keep in step with the
 * clock — which is why subtitles cost nothing while they are switched off and next to nothing
 * while they are on.
 *
 * Audio tracks come through the same functions for the same reason. "Which audio track" and
 * "which subtitle track" are one question asked about two renderers, and the only thing that
 * differs is the wording on the row.
 */
object SubtitleTracks {

    /** One track inside one group, with enough context to select it again later. */
    data class Option(
        val group: Tracks.Group,
        val trackIndex: Int,
        val groupIndex: Int,
    ) {
        val format: Format get() = group.getTrackFormat(trackIndex)
        val isSelected: Boolean get() = group.isTrackSelected(trackIndex)

        /**
         * A name to remember this track by.
         *
         * The format's own id when it has one — every subtitle this app attaches is given one,
         * and most containers supply one for their internal tracks. Otherwise the language plus
         * the position, which is stable for the same file across a re-prepare and is the best
         * available answer for a container that names nothing.
         */
        val id: String
            get() = format.id ?: "lang:${format.language ?: "und"}:$groupIndex:$trackIndex"
    }

    fun textOptions(player: Player): List<Option> = options(player, C.TRACK_TYPE_TEXT)

    fun audioOptions(player: Player): List<Option> = options(player, C.TRACK_TYPE_AUDIO)

    private fun options(player: Player, type: Int): List<Option> {
        val groups = player.currentTracks.groups
        val found = mutableListOf<Option>()
        groups.forEachIndexed { groupIndex, group ->
            if (group.type != type) return@forEachIndexed
            for (track in 0 until group.length) {
                // A track the device cannot decode is worse than absent: a row that does
                // nothing when tapped. Media3 already knows, so ask it.
                if (!group.isTrackSupported(track)) continue
                found += Option(group, track, groupIndex)
            }
        }
        return found
    }

    fun selected(options: List<Option>): Option? = options.firstOrNull { it.isSelected }

    /** Turns the track on, and text on with it if it was off. */
    fun select(player: Player, option: Option) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(
                TrackSelectionOverride(option.group.mediaTrackGroup, listOf(option.trackIndex)),
            )
            .setTrackTypeDisabled(option.group.type, false)
            .build()
    }

    /**
     * Off, and staying off.
     *
     * Clearing the override alone is not enough: with no override Media3 falls back to its own
     * preferences, and a preferred text language — which this app sets — would put a subtitle
     * straight back on screen. Disabling the type is what makes "off" mean off.
     */
    fun disableText(player: Player) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    fun findById(options: List<Option>, id: String): Option? = options.firstOrNull { it.id == id }

    /**
     * The best match for a language among [options], or null.
     *
     * Compared on the base tag, so a preference for "en" is satisfied by "en-US". A subtitle in
     * the right language for the wrong region is what someone asking for English wants; a
     * missing subtitle is not.
     */
    fun preferring(options: List<Option>, language: String?): Option? {
        val wanted = SubtitleLanguages.normalise(language)?.substringBefore('-') ?: return null
        return options.firstOrNull {
            SubtitleLanguages.normalise(it.format.language)?.substringBefore('-') == wanted
        }
    }

    // ---- wording ----

    /** "English", "Commentary", or a numbered fallback for a track that names itself nothing. */
    fun label(context: Context, option: Option, position: Int): String {
        val format = option.format
        format.label?.takeIf { it.isNotBlank() }?.let { return it }
        SubtitleLanguages.displayName(format.language).takeIf { it.isNotBlank() }?.let { return it }
        return context.getString(R.string.subtitle_track_number, position + 1)
    }

    /** Where it came from, and anything unusual about it. */
    fun detail(context: Context, option: Option): String {
        val format = option.format
        val parts = mutableListOf<String>()

        parts += context.getString(
            when (SubtitleOrigin.of(format.id)) {
                SubtitleOrigin.EMBEDDED -> R.string.subtitle_origin_embedded
                SubtitleOrigin.BESIDE -> R.string.subtitle_origin_beside
                SubtitleOrigin.SAVED -> R.string.subtitle_origin_saved
            }
        )
        if (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) {
            parts += context.getString(R.string.subtitle_forced)
        }
        if (format.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0) {
            parts += context.getString(R.string.subtitle_sdh)
        }
        return parts.joinToString(" · ")
    }

    /** For an audio row: "5.1", "Stereo", plus the codec where it is worth naming. */
    fun audioDetail(option: Option): String {
        val format = option.format
        val parts = mutableListOf<String>()
        when (format.channelCount) {
            1 -> parts += "Mono"
            2 -> parts += "Stereo"
            6 -> parts += "5.1"
            8 -> parts += "7.1"
            in 3..Int.MAX_VALUE -> parts += "${format.channelCount} channels"
        }
        format.sampleMimeType?.substringAfterLast('/')?.uppercase()?.let { parts += it }
        return parts.joinToString(" · ")
    }
}
