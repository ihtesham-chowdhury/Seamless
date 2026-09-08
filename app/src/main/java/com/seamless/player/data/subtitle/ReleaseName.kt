package com.seamless.player.data.subtitle

import java.util.Locale

/**
 * What a video file is, read off its name.
 *
 * A structured guess, not a first-search-result shortcut. `The.Matrix.1999.1080p.BluRay.
 * x264-AMIABLE.mkv` carries a title, a year, a resolution, a source, a codec and a release
 * group, and every one of those is worth something when deciding whether a subtitle from a
 * stranger's upload actually matches this file. Sending the whole file name to a search
 * endpoint and taking the first hit throws all of it away.
 *
 * Two shapes are recognised. The scene shape above, and the bracketed shape anime is
 * distributed in — `[Group] Show - 07 [1080p][HEVC].mkv` — which has no season number, puts
 * the group first, and separates the episode with a dash. Both end up in the same
 * [ReleaseInfo], so nothing downstream has to know which it came from.
 */
data class ReleaseInfo(
    /** Best guess at the work's name, in words: "The Matrix". Never blank. */
    val title: String,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
    /** "1080p", "2160p" — as written, because that is how providers write it too. */
    val resolution: String?,
    /** "BluRay", "WEB-DL", "HDTV". */
    val source: String?,
    /** "x264", "HEVC". */
    val codec: String?,
    /** Trailing release group, where the name has one. */
    val group: String?,
) {
    val isEpisode: Boolean get() = episode != null

    /**
     * What to send as a text query.
     *
     * Title and year only. Season and episode go as their own parameters, and every technical
     * token is a filter to score with afterwards rather than something to search for — a
     * search for "1080p" narrows nothing and excludes the 720p upload that would have matched
     * this file perfectly.
     */
    val queryText: String
        get() = if (year != null && !isEpisode) "$title $year" else title
}

object ReleaseName {

    private val RESOLUTIONS = listOf("2160p", "1440p", "1080p", "720p", "576p", "480p", "4k", "8k")

    /**
     * Ordered longest-first so "WEB-DL" is found before "WEB", and written in the casing we
     * want to display rather than the casing we match against.
     */
    private val SOURCES = listOf(
        "BluRay", "Blu-Ray", "BDRemux", "BDRip", "BRRip", "REMUX",
        "WEB-DL", "WEBRip", "WEBDL", "WEB",
        "HDTV", "PDTV", "DVDRip", "DVDScr", "HDRip", "CAMRip", "HDCAM", "TS", "CAM",
    )

    private val CODECS = listOf(
        "x265", "x264", "h265", "h264", "H.265", "H.264", "HEVC", "AVC", "AV1", "XviD", "DivX",
        "VP9",
    )

    /** Words that are never part of a title, and always mark where the title stopped. */
    private val NOISE = setOf(
        "extended", "unrated", "uncut", "remastered", "proper", "repack", "internal",
        "limited", "dubbed", "subbed", "multi", "dual", "hdr", "hdr10", "dv", "sdr",
        "10bit", "8bit", "aac", "ac3", "eac3", "dts", "truehd", "atmos", "flac", "opus",
        "5.1", "7.1", "2.0", "hindi", "dsnp", "amzn", "nf", "hmax", "hulu", "atvp",
    )

    private val SEASON_EPISODE = Regex("""\bs(\d{1,2})[\s._-]?e(\d{1,3})\b""", RegexOption.IGNORE_CASE)
    private val SEASON_X_EPISODE = Regex("""\b(\d{1,2})x(\d{2,3})\b""")
    private val VERBOSE_EPISODE =
        Regex("""\bseason[\s._-]?(\d{1,2}).{0,8}?episode[\s._-]?(\d{1,3})\b""", RegexOption.IGNORE_CASE)
    private val YEAR = Regex("""\b(19\d{2}|20\d{2})\b""")
    private val BRACKETED = Regex("""[\[(][^\[\]()]*[])]""")
    private val DASH_EPISODE = Regex("""\s-\s(\d{1,3})(?:v\d)?\s*$""")
    private val TRAILING_GROUP = Regex("""-([A-Za-z0-9]{2,20})$""")

    /**
     * [fileName] may or may not carry an extension; either is fine.
     *
     * Never throws and never returns a blank title. A name this cannot make sense of comes
     * back as its own title, which is exactly what a text search should then be given.
     */
    fun parse(fileName: String): ReleaseInfo {
        val stem = SubtitleNames.baseName(fileName.trim()).ifBlank { fileName.trim() }

        // Anime first: its brackets would otherwise be read as noise inside the title.
        val brackets = BRACKETED.findAll(stem).map { it.value }.toList()
        val withoutBrackets = BRACKETED.replace(stem, " ").trim(' ', '-', '_', '.')

        val resolution = findToken(stem, RESOLUTIONS)
        val source = findToken(stem, SOURCES)
        val codec = findToken(stem, CODECS)

        var season: Int? = null
        var episode: Int? = null
        var cutAt = -1

        SEASON_EPISODE.find(stem)?.let {
            season = it.groupValues[1].toIntOrNull()
            episode = it.groupValues[2].toIntOrNull()
            cutAt = it.range.first
        }
        if (episode == null) VERBOSE_EPISODE.find(stem)?.let {
            season = it.groupValues[1].toIntOrNull()
            episode = it.groupValues[2].toIntOrNull()
            cutAt = it.range.first
        }
        if (episode == null) SEASON_X_EPISODE.find(stem)?.let {
            season = it.groupValues[1].toIntOrNull()
            episode = it.groupValues[2].toIntOrNull()
            cutAt = it.range.first
        }

        // The anime shape: no season, the episode after a bare dash at the end of the name
        // once the bracketed tags are gone.
        if (episode == null && brackets.isNotEmpty()) {
            DASH_EPISODE.find(withoutBrackets)?.let {
                episode = it.groupValues[1].toIntOrNull()
            }
        }

        val yearMatch = YEAR.find(stem)
        val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()
        if (yearMatch != null && (cutAt < 0 || yearMatch.range.first < cutAt)) {
            cutAt = yearMatch.range.first
        }

        val group = TRAILING_GROUP.find(stem)
            ?.groupValues?.get(1)
            ?.takeIf { it.lowercase(Locale.ROOT) !in NOISE }

        val titleSource = if (brackets.isNotEmpty() && cutAt < 0) {
            DASH_EPISODE.replace(withoutBrackets, "")
        } else {
            if (cutAt > 0) stem.substring(0, cutAt) else stem
        }

        return ReleaseInfo(
            title = cleanTitle(titleSource).ifBlank { cleanTitle(stem).ifBlank { stem } },
            year = year,
            season = season,
            episode = episode,
            resolution = resolution,
            source = source,
            codec = codec,
            group = group,
        )
    }

    /** The token as we would like to display it, or null when the name does not carry one. */
    private fun findToken(stem: String, tokens: List<String>): String? {
        val haystack = stem.lowercase(Locale.ROOT)
        return tokens.firstOrNull { token ->
            val needle = token.lowercase(Locale.ROOT)
            val at = haystack.indexOf(needle)
            if (at < 0) return@firstOrNull false
            val end = at + needle.length
            val before = at == 0 || !haystack[at - 1].isLetterOrDigit()
            val after = end == haystack.length || !haystack[end].isLetterOrDigit()
            before && after
        }
    }

    /**
     * Separators to spaces, technical noise dropped, then trimmed.
     *
     * Noise words are only removed from the end. "Extended" in the middle of a title is part
     * of the title; "Extended" after it is an edition tag, and by the time we are here the
     * year has usually already cut the name at the right place anyway.
     */
    private fun cleanTitle(raw: String): String {
        val spaced = raw.replace('.', ' ').replace('_', ' ').replace('-', ' ')
        val words = spaced.split(' ').filter { it.isNotBlank() }.toMutableList()
        while (words.isNotEmpty() && words.last().lowercase(Locale.ROOT) in NOISE) {
            words.removeAt(words.lastIndex)
        }
        return words.joinToString(" ").trim()
    }
}
