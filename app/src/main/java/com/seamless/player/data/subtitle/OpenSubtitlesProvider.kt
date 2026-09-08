package com.seamless.player.data.subtitle

import com.seamless.player.util.Http
import com.seamless.player.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Subtitles from opensubtitles.com.
 *
 * Chosen because of the hash. Its search accepts the OSDb hash of the actual file and reports
 * whether an upload was timed against that exact encode, which is the difference between a
 * subtitle that is right and a subtitle that is merely about the same film. No other freely
 * usable source offers that, and without it "automatically download the correct subtitle"
 * cannot be done honestly — only guessed at.
 *
 * **It needs a key the user supplies, and that is not a limitation being worked around.** The
 * API requires one per application, the terms do not permit sharing it, and an open-source
 * repository has nowhere to hide one anyway — a key committed here would be a key published
 * here, revoked within days, and everyone's subtitle search would break at once. Registration
 * is free and takes a minute; the key goes in Settings and stays on the device. Until then the
 * online half of the feature is simply switched off, which also means this app makes no network
 * request of any kind out of the box.
 *
 * A username and password are optional and only affect quota: anonymous downloads are capped
 * per address per day, and signing in raises that. They are stored on the device, sent only to
 * the login endpoint, and exchanged for a token that is cached and reused.
 */
class OpenSubtitlesProvider(
    private val apiKey: String,
    private val userAgent: String,
    private val credentials: Credentials?,
    private val tokens: TokenStore,
) : SubtitleProvider {

    /** Optional, and only for quota. Never sent anywhere but the login endpoint. */
    data class Credentials(val username: String, val password: String)

    /** Somewhere to keep the login token between searches, so we log in at most once a day. */
    interface TokenStore {
        fun read(): String?
        fun write(token: String?)
    }

    override val id = ID
    override val label = "OpenSubtitles"

    override fun unavailableReason(): String? =
        if (apiKey.isBlank()) "No OpenSubtitles API key yet" else null

    // ---- search ----

    override fun search(query: SubtitleQuery): List<SubtitleCandidate> {
        if (apiKey.isBlank()) throw SubtitleProviderException("No OpenSubtitles API key yet")

        val response = Http.get(searchUrl(query), headers(authenticated = false))
        if (!response.isSuccess) throw SubtitleProviderException(explain(response))

        val data = runCatching { JSONObject(response.body).optJSONArray("data") }.getOrNull()
            ?: return emptyList()

        val candidates = mutableListOf<SubtitleCandidate>()
        for (index in 0 until data.length()) {
            val attributes = data.optJSONObject(index)?.optJSONObject("attributes") ?: continue
            candidates += parse(attributes) ?: continue
        }
        Log.d(TAG, "search returned ${candidates.size} candidates")
        return candidates
    }

    private fun searchUrl(query: SubtitleQuery): String {
        val parameters = sortedMapOf<String, String>()

        // Sorted keys and sorted language codes: the API documents this, because its edge cache
        // keys on the literal query string and an unsorted one is a guaranteed cache miss.
        val languages = query.languages
            .mapNotNull { SubtitleLanguages.normalise(it)?.substringBefore('-') }
            .distinct()
            .sorted()
        if (languages.isNotEmpty()) parameters["languages"] = languages.joinToString(",")

        query.movieHash?.let { parameters["moviehash"] = it }
        parameters["query"] = query.release.queryText

        if (query.release.isEpisode) {
            query.release.season?.let { parameters["season_number"] = it.toString() }
            query.release.episode?.let { parameters["episode_number"] = it.toString() }
        } else {
            query.release.year?.let { parameters["year"] = it.toString() }
        }

        val encoded = parameters.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8")}"
        }
        return "$BASE/subtitles?$encoded"
    }

    /** Null for an entry with no downloadable file, which the API does occasionally return. */
    private fun parse(attributes: JSONObject): SubtitleCandidate? {
        val files = attributes.optJSONArray("files") ?: return null
        val file = files.optJSONObject(0) ?: return null
        val fileId = file.opt("file_id")?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val feature = attributes.optJSONObject("feature_details")

        return SubtitleCandidate(
            providerId = ID,
            fileId = fileId,
            fileName = file.optString("file_name").ifBlank {
                attributes.optString("release").ifBlank { "subtitle" }
            },
            language = SubtitleLanguages.normalise(attributes.optString("language")),
            release = attributes.optString("release").takeIf { it.isNotBlank() },
            downloads = attributes.optInt("download_count", 0),
            hashMatch = attributes.optBoolean("moviehash_match", false),
            trusted = attributes.optBoolean("from_trusted", false),
            hearingImpaired = attributes.optBoolean("hearing_impaired", false),
            featureTitle = feature?.optString("title")?.takeIf { it.isNotBlank() }
                ?: feature?.optString("movie_name")?.takeIf { it.isNotBlank() },
            featureYear = feature?.optInt("year", 0)?.takeIf { it > 0 },
        )
    }

    // ---- download ----

    /**
     * Two steps, as the API requires: ask for a link, then fetch it.
     *
     * A 401 on the first step means the cached token has expired — they last a day — so it is
     * dropped and the whole thing tried once more. Once, not in a loop: a second failure is a
     * real one and the user should hear about it rather than watch a spinner.
     */
    override fun download(candidate: SubtitleCandidate): SubtitleDownload {
        val link = requestLink(candidate, retryOnAuthFailure = true)
        val bytes = try {
            Http.download(link.first, mapOf("User-Agent" to userAgent))
        } catch (error: IOException) {
            throw SubtitleProviderException(
                error.message ?: "The subtitle could not be downloaded",
                error,
            )
        }
        return SubtitleDownload(link.second, bytes)
    }

    private fun requestLink(
        candidate: SubtitleCandidate,
        retryOnAuthFailure: Boolean,
    ): Pair<String, String> {
        val body = JSONObject().put("file_id", candidate.fileId).toString()
        val response = Http.postJson("$BASE/download", headers(authenticated = true), body)

        if (response.code == 401 && retryOnAuthFailure && credentials != null) {
            tokens.write(null)
            return requestLink(candidate, retryOnAuthFailure = false)
        }
        if (!response.isSuccess) throw SubtitleProviderException(explain(response))

        val json = runCatching { JSONObject(response.body) }.getOrNull()
            ?: throw SubtitleProviderException("The provider sent something unreadable")
        val link = json.optString("link").takeIf { it.startsWith("https://") }
            ?: throw SubtitleProviderException(
                json.optString("message").ifBlank { "The provider refused the download" },
            )
        val name = json.optString("file_name").ifBlank { candidate.fileName }
        return link to name
    }

    // ---- authentication ----

    private fun headers(authenticated: Boolean): Map<String, String> {
        val headers = mutableMapOf(
            "Api-Key" to apiKey,
            "Accept" to "application/json",
            // Required, and required to identify the application rather than a browser. A
            // request without it is refused.
            "User-Agent" to userAgent,
        )
        if (authenticated) token()?.let { headers["Authorization"] = "Bearer $it" }
        return headers
    }

    /** The cached token, logging in first if there are credentials and no token yet. */
    private fun token(): String? {
        tokens.read()?.takeIf { it.isNotBlank() }?.let { return it }
        val account = credentials ?: return null

        val body = JSONObject()
            .put("username", account.username)
            .put("password", account.password)
            .toString()
        val response = Http.postJson("$BASE/login", headers(authenticated = false), body)
        if (!response.isSuccess) {
            // Not fatal. Anonymous downloads still work, at a lower daily limit, and telling
            // the user their sign-in failed in the middle of a subtitle search would be
            // answering a question they did not ask.
            Log.w(TAG, "sign-in failed: ${explain(response)}")
            return null
        }
        val fresh = runCatching { JSONObject(response.body).optString("token") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
        tokens.write(fresh)
        return fresh
    }

    // ---- errors ----

    /**
     * The provider's own words where it gave any.
     *
     * Their messages are current and ours are a guess from a status code, so theirs wins. The
     * fallbacks below only cover the codes whose meaning is stable and whose remedy is worth
     * stating.
     */
    private fun explain(response: Http.Response): String {
        val message = runCatching {
            JSONObject(response.body).let {
                it.optString("message").ifBlank { it.optString("errors") }
            }
        }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }
        if (message != null) return message.trim('[', ']', '"')

        return when (response.code) {
            401 -> "OpenSubtitles rejected the sign-in"
            403 -> "OpenSubtitles rejected the API key"
            406, 429 -> "The daily download limit has been reached. Signing in raises it."
            in 500..599 -> "OpenSubtitles is not answering right now"
            else -> "The subtitle search failed (HTTP ${response.code})"
        }
    }

    companion object {
        const val ID = "opensubtitles"
        private const val TAG = "OpenSubtitles"
        private const val BASE = "https://api.opensubtitles.com/api/v1"

        /** Their required format: an application name and a version, nothing else. */
        fun userAgent(versionName: String) = "Seamless v$versionName"
    }
}
