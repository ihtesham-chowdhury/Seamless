package com.seamless.player.data.subtitle

import java.util.Locale

/**
 * The naming convention every player and every subtitle site already agrees on.
 *
 * A subtitle belongs to a video when its name is the video's name with the extension swapped,
 * optionally with tags in between:
 *
 *     Movie.mkv  ->  Movie.srt
 *                    Movie.en.srt
 *                    Movie.en-US.forced.srt
 *                    Movie.English.sdh.srt
 *
 * There is no registry for this and no specification; it is a convention read off what people
 * actually have on disk. So the rule is deliberately generous about separators and case, and
 * deliberately strict about the stem: a file only qualifies if it starts with the video's own
 * base name, which is what stops `Movie 2.srt` being offered for `Movie.mkv`.
 */
object SubtitleNames {

    /** What a subtitle file's extra tags told us. */
    data class Tags(
        val language: String?,
        val forced: Boolean,
        /** Subtitles for the deaf and hard of hearing: sound effects written out. */
        val sdh: Boolean,
    )

    private val SEPARATORS = charArrayOf('.', '_', '-', ' ')

    /** "Movie.2019.1080p.mkv" -> "Movie.2019.1080p" */
    fun baseName(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot <= 0) fileName else fileName.substring(0, dot)
    }

    /**
     * Whether [subtitleName] is a companion of [videoName].
     *
     * Exact stem, or stem followed by a separator. Nothing looser: matching on "starts with"
     * alone would hand every episode's subtitle to episode 1 in a folder named by number.
     */
    fun belongsTo(videoName: String, subtitleName: String): Boolean {
        if (!SubtitleFormats.isSubtitle(subtitleName)) return false
        val stem = baseName(videoName).lowercase(Locale.ROOT)
        if (stem.isEmpty()) return false
        val candidate = baseName(subtitleName).lowercase(Locale.ROOT)
        if (candidate == stem) return true
        return candidate.length > stem.length &&
            candidate.startsWith(stem) &&
            candidate[stem.length] in SEPARATORS
    }

    /**
     * Reads the tags between the stem and the extension.
     *
     * Only the first thing that looks like a language wins. `Movie.en.forced.srt` has one
     * language and one flag; `Movie.en.pt.srt` is a file whose name is already lying, and
     * taking the first claim is as good an answer as any.
     */
    fun tagsOf(videoName: String, subtitleName: String): Tags {
        val stem = baseName(videoName)
        val candidate = baseName(subtitleName)
        val matchesStem = candidate.length > stem.length &&
            candidate.startsWith(stem, ignoreCase = true)
        val suffix = if (matchesStem) candidate.substring(stem.length) else ""
        val words = suffix.split(*SEPARATORS).filter { it.isNotBlank() }

        var language: String? = null
        var forced = false
        var sdh = false
        words.forEach { word ->
            when (word.lowercase(Locale.ROOT)) {
                "forced" -> forced = true
                "sdh", "cc", "hi" -> sdh = true
                else -> if (language == null) language = SubtitleLanguages.normalise(word)
            }
        }
        return Tags(language, forced, sdh)
    }

    /**
     * What to call a subtitle saved next to a video, so that every other player finds it too.
     *
     * That is the point of writing it in this shape rather than in some private form: a
     * subtitle downloaded here should still work if the user opens the file in VLC tomorrow,
     * or in this app after clearing its data.
     */
    fun sidecarName(videoName: String, language: String?, extension: String): String {
        val tag = SubtitleLanguages.normalise(language)
        val stem = baseName(videoName)
        return if (tag == null) "$stem.$extension" else "$stem.$tag.$extension"
    }
}
