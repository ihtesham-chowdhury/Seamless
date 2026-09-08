package com.seamless.player.data.subtitle

import androidx.media3.common.MimeTypes
import java.util.Locale

/**
 * Which subtitle files this app will touch, and what to tell Media3 they are.
 *
 * The list is short on purpose: every entry here is a format the playback stack already
 * decodes, so a subtitle discovered or downloaded is a subtitle that will actually appear.
 * Offering a `.sub` that Media3 cannot render outside a Matroska container would be worse
 * than not offering it, because the failure arrives silently, after the user has chosen.
 */
object SubtitleFormats {

    /** Lower-case, no dot, in the order we would rather have them. */
    val EXTENSIONS = listOf("srt", "vtt", "ass", "ssa", "ttml", "dfxp")

    /** Null for anything not in [EXTENSIONS]. */
    fun mimeTypeFor(extension: String): String? = when (extension.lowercase(Locale.ROOT)) {
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml", "dfxp" -> MimeTypes.APPLICATION_TTML
        else -> null
    }

    /** The extension of a file name, lower-cased, or an empty string when it has none. */
    fun extensionOf(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0 || dot == fileName.lastIndex) return ""
        return fileName.substring(dot + 1).lowercase(Locale.ROOT)
    }

    fun isSubtitle(fileName: String) = extensionOf(fileName) in EXTENSIONS

    /**
     * What to call a downloaded file whose name we do not trust.
     *
     * Providers name their files after the release rather than after the format, and a few
     * hand back no extension at all. SubRip is the right guess when there is nothing to go
     * on: it is what the overwhelming majority of subtitle downloads are.
     */
    fun extensionOrDefault(fileName: String): String =
        extensionOf(fileName).takeIf { it in EXTENSIONS } ?: "srt"
}
