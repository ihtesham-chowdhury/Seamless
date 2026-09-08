package com.seamless.player.data.subtitle

/**
 * How confident we are that a candidate is the right subtitle for the file in hand.
 *
 * Three levels rather than a percentage, because three is the number of *behaviours*: get out
 * of the way, ask, or admit defeat. The percentage is still shown next to a candidate, since a
 * person choosing between two of them wants to see the difference — but nothing in the app
 * branches on it.
 */
enum class Confidence {
    /** Use it. In practice this means the file hashes matched. */
    EXCELLENT,

    /** Plausible, and worth offering, but not worth deciding on someone's behalf. */
    GOOD,

    /** Probably wrong. Shown only when there is nothing better. */
    WEAK,
}

/**
 * One subtitle a provider is offering, already scored against the video it was searched for.
 *
 * [fileId] is whatever the provider needs back in order to hand over the bytes; nothing else
 * interprets it.
 */
data class SubtitleCandidate(
    val providerId: String,
    val fileId: String,
    val fileName: String,
    val language: String?,
    /** The release the uploader says this is timed for: "WEB-DL", "BluRay 1080p AMIABLE". */
    val release: String?,
    val downloads: Int,
    /** The file hashes matched — the same encode, not merely the same film. */
    val hashMatch: Boolean,
    val trusted: Boolean,
    val hearingImpaired: Boolean,
    val featureTitle: String?,
    val featureYear: Int?,
    /** 0..100, from [SubtitleScoring]. */
    val score: Int = 0,
) {
    /**
     * Read straight off the score, and the score is built so that this is meaningful.
     *
     * [SubtitleScoring] floors a hash match at 92 and caps everything else at 87, so the top band
     * is reachable only by a file whose fingerprint matched. That is the invariant the whole
     * feature rests on: "Excellent" is a fact about the encode, not an opinion about the title.
     */
    val confidence: Confidence
        get() = when {
            score >= 88 -> Confidence.EXCELLENT
            score >= 48 -> Confidence.GOOD
            else -> Confidence.WEAK
        }

    /**
     * Whether this can be applied without asking.
     *
     * Only a hash match qualifies, and only in a language that was asked for. Everything else
     * — a title that matches, a year that matches, a hundred thousand downloads — is still an
     * inference from a file name, and a wrong subtitle applied silently is a worse experience
     * than a question.
     */
    fun isSafeToApplyAutomatically(preferred: String?): Boolean {
        if (!hashMatch) return false
        val wanted = SubtitleLanguages.normalise(preferred) ?: return true
        val mine = SubtitleLanguages.normalise(language) ?: return false
        return mine.substringBefore('-') == wanted.substringBefore('-')
    }
}

/** What to look for. Built once per video and handed to every provider unchanged. */
data class SubtitleQuery(
    val release: ReleaseInfo,
    /** Preferred first. A provider may or may not honour the order; the scoring does. */
    val languages: List<String>,
    /** [OsdbHash], when the file was big enough to hash. */
    val movieHash: String?,
)

/** Bytes, and what the provider called them. */
data class SubtitleDownload(val fileName: String, val bytes: ByteArray) {
    // Generated equals/hashCode on a ByteArray compare references, which is never what anyone
    // means. Nothing compares these, so the honest thing is to say so rather than to write
    // deep versions nobody would call.
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/**
 * Thrown for anything the user should be told about: no key, a refusal, a quota, a timeout.
 *
 * The message is shown verbatim, so it is written for a person rather than for a log. Where a
 * provider explains itself in its own response, that explanation is preferred over ours — it
 * is more likely to be current.
 */
class SubtitleProviderException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * A source of subtitles from the network.
 *
 * The interface exists so the online half can be replaced without the player learning
 * anything. Everything above it — the local engine, the scoring, the sheets, the store —
 * depends on this and not on any particular site, so swapping provider is a matter of writing
 * one class and changing one line in [SubtitleSearch].
 *
 * Implementations do blocking I/O and are called from a background thread. None of them may
 * be constructed with side effects: nothing must reach the network until [search] is called,
 * because nothing may reach the network until the user asks.
 */
interface SubtitleProvider {

    /** Stable, stored with a candidate so it can be handed back to the right provider. */
    val id: String

    /** Shown in settings and in the search sheet. */
    val label: String

    /**
     * Null when the provider is ready to use, otherwise a sentence saying what is missing —
     * shown to the user in place of the search.
     */
    fun unavailableReason(): String?

    /** Ordered best-first by the provider; re-scored afterwards regardless. */
    fun search(query: SubtitleQuery): List<SubtitleCandidate>

    fun download(candidate: SubtitleCandidate): SubtitleDownload
}
