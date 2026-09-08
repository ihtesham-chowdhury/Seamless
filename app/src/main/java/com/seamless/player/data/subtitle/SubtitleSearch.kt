package com.seamless.player.data.subtitle

import android.content.Context
import android.net.Uri
import com.seamless.player.data.Prefs
import com.seamless.player.data.Video
import com.seamless.player.util.Log
import com.seamless.player.util.appVersionName

/**
 * The online half, kept behind one door.
 *
 * Everything that can reach the network is on the far side of this class, and nothing calls
 * into it except in response to a tap. There is no listener, no prefetch, no warm-up, and no
 * "while we're here" — opening a video runs [LocalSubtitles] and stops. That is not a
 * performance decision so much as the product's whole position: an offline player that quietly
 * announced every file you watched to a website would be a different application.
 *
 * The order of business, once asked:
 *
 * 1. Hash the file. 128 KiB, and the only signal in the whole exchange that is a fact.
 * 2. Read the name into structured metadata — title, year, season, episode, release.
 * 3. Ask the provider, with the hash and the metadata and the wanted languages.
 * 4. Score what comes back against what we know, and decide by confidence: apply it, offer a
 *    short list, or say plainly that nothing matched.
 *
 * Step 4 is where this differs from every "download subtitles" button that has ever put the
 * wrong subtitle on a film. A hash match is applied. Anything less asks.
 *
 * All of it blocks. Call from a background thread.
 */
class SubtitleSearch(
    private val context: Context,
    private val prefs: Prefs,
    private val store: SubtitleStore,
) {

    /** What a search came to. */
    sealed interface Outcome {
        /**
         * Certain enough to have already been done: downloaded, saved and ready to select.
         * [besideVideo] is set when a copy was also written into the video's own folder.
         */
        data class Applied(
            val saved: SubtitleStore.Saved,
            val candidate: SubtitleCandidate,
            val besideVideo: Uri?,
        ) : Outcome

        /** Plausible candidates, best first, for the user to choose from. */
        data class Choices(val candidates: List<SubtitleCandidate>) : Outcome

        /** The search ran and found nothing worth offering. */
        data object NoMatch : Outcome

        /** Not configured, or switched off. [reason] is shown as written. */
        data class Unavailable(val reason: String) : Outcome

        /** It went wrong. [message] is the provider's words where it gave any. */
        data class Failed(val message: String) : Outcome
    }

    /** A candidate turned into a file on disk. */
    data class Fetched(val saved: SubtitleStore.Saved, val besideVideo: Uri?)

    /**
     * Why online search is not available, as a sentence, or null when it is.
     *
     * Two conditions, and they are different questions: the switch is consent, the key is
     * capability. Neither is assumed from the other, and the panel shows whichever is missing
     * rather than a generic refusal.
     */
    fun unavailableReason(): String? = when {
        !prefs.subtitleOnlineEnabled -> "Online subtitle search is switched off in Settings"
        prefs.subtitleApiKey.isBlank() -> provider().unavailableReason()
        else -> null
    }

    // ---- searching ----

    fun find(video: Video): Outcome {
        unavailableReason()?.let { return Outcome.Unavailable(it) }
        val provider = provider()

        val release = ReleaseName.parse(video.name)
        val hash = OsdbHash.of(context, video.uri)
        Log.d(TAG, "searching for '${release.title}' hash=${hash ?: "none"}")

        val query = SubtitleQuery(
            release = release,
            languages = prefs.subtitleSearchLanguages,
            movieHash = hash,
        )

        val ranked = try {
            SubtitleScoring.rank(provider.search(query), release, prefs.subtitleLanguage)
        } catch (error: SubtitleProviderException) {
            return Outcome.Failed(error.message ?: "The subtitle search failed")
        } catch (error: Exception) {
            Log.e(TAG, "search failed", error)
            return Outcome.Failed("The subtitle search could not be completed")
        }

        if (ranked.isEmpty()) return Outcome.NoMatch

        val best = ranked.first()
        if (prefs.subtitleAutoApply && best.isSafeToApplyAutomatically(prefs.subtitleLanguage)) {
            return try {
                val fetched = fetch(video, best)
                Outcome.Applied(fetched.saved, best, fetched.besideVideo)
            } catch (error: SubtitleProviderException) {
                // The match was right; only the download failed. Fall back to the list rather
                // than reporting nothing found, which would be a lie about what we know.
                Log.w(TAG, "automatic download failed: ${error.message}")
                Outcome.Choices(ranked.take(MAX_CHOICES))
            }
        }

        // Weak-only results are still shown, but this is the one case where the honest answer
        // is "no confident match": offering six wrong subtitles is worse than offering none.
        if (best.confidence == Confidence.WEAK) return Outcome.NoMatch

        return Outcome.Choices(ranked.take(MAX_CHOICES))
    }

    /**
     * Downloads one candidate and puts it where it will be found again.
     *
     * The app's own store first, because that write cannot fail for want of permission. The
     * copy beside the video is attempted afterwards and its failure is ignored — it is a
     * courtesy to other players, not part of this working.
     *
     * Throws [SubtitleProviderException] with a message fit to show the user.
     */
    fun fetch(video: Video, candidate: SubtitleCandidate): Fetched {
        val download = provider().download(candidate)
        val extension = SubtitleFormats.extensionOrDefault(download.fileName)
        val saved = store.save(store.keyFor(video), candidate.language, extension, download.bytes)
            ?: throw SubtitleProviderException("The subtitle could not be saved")

        val beside = if (prefs.subtitleSaveBeside) {
            SubtitleFolder.writeBeside(
                context,
                video,
                SubtitleNames.sidecarName(video.name, candidate.language, extension),
                download.bytes,
            )
        } else {
            null
        }
        return Fetched(saved, beside)
    }

    /**
     * Built per call rather than held.
     *
     * The key, the account and the switch can all change in Settings while a player is open,
     * and a provider constructed once at startup would still be holding the old ones. Building
     * it costs nothing — it is a handful of strings — and it guarantees that turning the feature
     * off turns it off now.
     */
    private fun provider(): SubtitleProvider = OpenSubtitlesProvider(
        apiKey = prefs.subtitleApiKey,
        userAgent = OpenSubtitlesProvider.userAgent(context.appVersionName()),
        credentials = prefs.subtitleAccountName
            .takeIf { it.isNotBlank() && prefs.subtitleAccountPassword.isNotBlank() }
            ?.let { OpenSubtitlesProvider.Credentials(it, prefs.subtitleAccountPassword) },
        tokens = object : OpenSubtitlesProvider.TokenStore {
            override fun read() = prefs.subtitleToken
            override fun write(token: String?) {
                prefs.subtitleToken = token
            }
        },
    )

    private companion object {
        const val TAG = "SubtitleSearch"

        /**
         * How many candidates a person will actually read.
         *
         * The API returns fifty. A list of fifty near-identical uploads is not a choice, it is
         * a chore, and everything past the first handful scores so closely that the order
         * stops meaning anything.
         */
        const val MAX_CHOICES = 8
    }
}
