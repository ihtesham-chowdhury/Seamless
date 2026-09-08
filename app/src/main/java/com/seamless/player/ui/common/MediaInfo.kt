package com.seamless.player.ui.common

import android.content.Context
import android.text.format.Formatter
import androidx.media3.common.Format
import com.seamless.player.R
import com.seamless.player.data.Video
import com.seamless.player.util.formatDuration
import java.util.Locale

/**
 * Human-readable descriptions of what is actually being decoded.
 *
 * This exists mainly as a diagnostic. When a clip misbehaves, the first question is always
 * "what codec is it?", and the answer should be on screen rather than in a logcat filter.
 */
object MediaInfo {

    /** A compact one-liner, e.g. "video/hevc · 1080x1920 · 30 fps". Safe with a null format. */
    fun shortDescription(format: Format?): String {
        if (format == null) return "format not yet known"
        val parts = mutableListOf<String>()
        format.sampleMimeType?.let { parts += it }
        if (format.width > 0 && format.height > 0) parts += "${format.width}x${format.height}"
        if (format.frameRate > 0f) {
            parts += String.format(Locale.US, "%.0f fps", format.frameRate)
        }
        return if (parts.isEmpty()) "unknown format" else parts.joinToString(" · ")
    }

    /**
     * The full breakdown shown by the Info action.
     *
     * [audio] and [subtitles] are passed in already worded rather than read from the player here.
     * Track selection lives with the player, and pulling it into this file would mean a
     * diagnostic helper reaching into the playback layer to answer a question the caller had
     * already answered.
     */
    fun details(
        context: Context,
        video: Video,
        format: Format?,
        audio: String? = null,
        subtitles: String? = null,
    ): String {
        val lines = mutableListOf<String>()
        lines += line(context, R.string.info_name, video.name)
        lines += line(
            context, R.string.info_folder,
            video.relativePath.ifBlank { video.folderName },
        )
        lines += line(
            context, R.string.info_resolution,
            if (video.width > 0) "${video.width} x ${video.height}" else "—",
        )
        lines += line(context, R.string.info_duration, formatDuration(video.durationMs))
        lines += line(
            context, R.string.info_size,
            Formatter.formatFileSize(context, video.sizeBytes),
        )

        // Everything below comes from the decoder rather than MediaStore, so it is only
        // available once the clip has actually started.
        lines += line(context, R.string.info_codec, format?.sampleMimeType ?: "—")
        lines += line(context, R.string.info_codec_detail, format?.codecs ?: "—")
        lines += line(
            context, R.string.info_frame_rate,
            if (format != null && format.frameRate > 0f) {
                String.format(Locale.US, "%.2f fps", format.frameRate)
            } else "—",
        )
        val bitrate = format?.averageBitrate ?: Format.NO_VALUE
        lines += line(
            context, R.string.info_bitrate,
            if (bitrate > 0) "${bitrate / 1000} kbps" else "—",
        )
        lines += line(
            context, R.string.info_audio,
            audio?.takeIf { it.isNotBlank() } ?: context.getString(R.string.info_none),
        )
        lines += line(
            context, R.string.info_subtitles,
            subtitles?.takeIf { it.isNotBlank() } ?: context.getString(R.string.info_none),
        )
        return lines.joinToString("\n")
    }

    private fun line(context: Context, labelRes: Int, value: String) =
        "${context.getString(labelRes)}: $value"
}
