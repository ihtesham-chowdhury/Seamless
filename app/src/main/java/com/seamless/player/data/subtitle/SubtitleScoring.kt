package com.seamless.player.data.subtitle

import java.util.Locale
import kotlin.math.ln
import kotlin.math.min

/**
 * How well a candidate matches the file in hand, as a number out of a hundred.
 *
 * The scoring is deliberately legible rather than clever. Every term below is something a
 * person would also check — is it the same encode, the same film, the same year, the same
 * release — and the weights say which of those actually settle the question.
 *
 * There is no machine learning here and no tuning against a corpus, both of which would be
 * dishonest at this scale. What there is instead is a scale split in two: a file whose
 * fingerprint matched starts at 92, and everything else is held below 88 however well its name
 * reads. So "Excellent" cannot be earned by a lucky title, and the number shown beside a
 * candidate cannot claim more than the evidence behind it.
 */
object SubtitleScoring {

    /**
     * Two bands, and the boundary between them is the whole design.
     *
     * A hash match is floored at [HASH_FLOOR] and everything else is capped at [NO_HASH_CEILING],
     * so the percentage on screen and the word beside it cannot disagree, and nothing that is
     * merely a very good guess can ever present itself as certain. The weights below therefore
     * only have to order candidates sensibly *within* the guessing band; they are not trying to
     * add up to a probability, and pretending otherwise by tuning them to three decimal places
     * would be false precision.
     */
    private const val HASH_FLOOR = 92
    private const val NO_HASH_CEILING = 87

    /**
     * Every candidate starts here.
     *
     * The provider was asked for this title, this year and this episode, and returned this row.
     * That filtering is itself evidence, and a scale that ignored it would rate a correctly
     * identified film with an unremarkable file name as no match at all.
     */
    private const val RETURNED_BY_SEARCH = 10

    private const val TITLE = 34
    private const val YEAR = 14
    private const val EPISODE = 18
    private const val RESOLUTION = 6
    private const val SOURCE = 7
    private const val GROUP = 6
    private const val TRUSTED = 4
    private const val POPULARITY = 6
    private const val LANGUAGE_MISS = -25
    private const val UNWANTED_SDH = -6

    /** Scores every candidate and returns them best-first. */
    fun rank(
        candidates: List<SubtitleCandidate>,
        wanted: ReleaseInfo,
        preferred: String?,
    ): List<SubtitleCandidate> = candidates
        .map { it.copy(score = score(it, wanted, preferred)) }
        .sortedWith(
            compareByDescending<SubtitleCandidate> { it.score }
                .thenByDescending { it.downloads },
        )

    private fun score(
        candidate: SubtitleCandidate,
        wanted: ReleaseInfo,
        preferred: String?,
    ): Int {
        var score = RETURNED_BY_SEARCH

        // The release string is the uploader's description of what this is timed for; the file
        // name is what they called the file. Either can carry the technical tokens, so both
        // are searched.
        val haystack = listOfNotNull(candidate.release, candidate.fileName)
            .joinToString(" ")
            .lowercase(Locale.ROOT)

        score += (TITLE * titleAgreement(wanted.title, candidate.featureTitle, haystack)).toInt()

        if (wanted.year != null && candidate.featureYear == wanted.year) score += YEAR

        if (wanted.isEpisode) {
            // An episode subtitle on the wrong episode is not a near miss, it is useless, so
            // this term is all or nothing rather than partial credit.
            val season = wanted.season
            val episode = wanted.episode
            val matches = episode != null &&
                (season == null || tokenPresent(haystack, "s%02d".format(season))) &&
                tokenPresent(haystack, "e%02d".format(episode))
            if (matches) score += EPISODE
        }

        fun shares(token: String?) = token != null && haystack.contains(token.lowercase(Locale.ROOT))
        if (shares(wanted.resolution)) score += RESOLUTION
        if (shares(wanted.source)) score += SOURCE
        if (shares(wanted.group)) score += GROUP

        if (candidate.trusted) score += TRUSTED

        // Popularity is a weak signal and is weighted like one. Logarithmic because the
        // difference between 10 and 100 downloads means something and the difference between
        // 40,000 and 50,000 does not.
        if (candidate.downloads > 0) {
            score += min(POPULARITY.toDouble(), ln(candidate.downloads.toDouble()) * 0.6).toInt()
        }

        val wanted1 = SubtitleLanguages.normalise(preferred)?.substringBefore('-')
        val mine = SubtitleLanguages.normalise(candidate.language)?.substringBefore('-')
        if (wanted1 != null && mine != null && wanted1 != mine) score += LANGUAGE_MISS

        if (candidate.hearingImpaired) score += UNWANTED_SDH

        // The two bands. A fingerprint match is not the top of a scale of guesses; it is a
        // different kind of answer, and the number says so.
        return if (candidate.hashMatch) {
            maxOf(score, HASH_FLOOR).coerceAtMost(100)
        } else {
            score.coerceIn(0, NO_HASH_CEILING)
        }
    }

    /**
     * How much of the wanted title the candidate's title accounts for, 0..1.
     *
     * Word overlap rather than an edit distance: "The Matrix" against "Matrix, The" is the same
     * film and an edit distance says otherwise. Short words are kept — dropping "the" would
     * make "The Thing" and "Thing" indistinguishable, and they are different films.
     */
    private fun titleAgreement(wanted: String, featureTitle: String?, haystack: String): Double {
        val words = wanted.lowercase(Locale.ROOT)
            .split(' ', '.', '_', '-', ':')
            .filter { it.isNotBlank() }
        if (words.isEmpty()) return 0.0

        val candidate = (featureTitle?.lowercase(Locale.ROOT) ?: "") + " " + haystack
        val hits = words.count { candidate.contains(it) }
        return hits.toDouble() / words.size
    }

    /** A token surrounded by non-alphanumerics, so "e01" does not match "e011". */
    private fun tokenPresent(haystack: String, token: String): Boolean {
        var from = 0
        while (true) {
            val at = haystack.indexOf(token, from)
            if (at < 0) return false
            val beforeOk = at == 0 || !haystack[at - 1].isLetterOrDigit()
            val end = at + token.length
            val afterOk = end == haystack.length || !haystack[end].isLetterOrDigit()
            if (beforeOk && afterOk) return true
            from = at + 1
        }
    }
}
