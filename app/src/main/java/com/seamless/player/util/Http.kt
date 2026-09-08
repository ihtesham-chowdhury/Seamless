package com.seamless.player.util

import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * The whole of this app's networking.
 *
 * `HttpURLConnection` rather than a library, and that is a considered choice rather than
 * frugality for its own sake. Seamless makes network requests in exactly one situation — the
 * user asked, out loud, for a subtitle to be looked up — and adding a few hundred kilobytes
 * of HTTP client plus its transitive dependencies to an offline video player to serve that one
 * request would be the wrong trade. What is needed here is a GET, a POST of some JSON, and a
 * capped download; the platform does all three.
 *
 * Three rules are enforced rather than documented:
 *
 * - **HTTPS only.** Anything else is refused outright, matching `usesCleartextTraffic="false"`
 *   in the manifest. A subtitle provider that wants plain HTTP does not get used.
 * - **Never on the main thread.** Checked, and thrown, because the alternative is a frame drop
 *   that only appears on a slow connection and therefore only appears for other people.
 * - **A size ceiling on every response.** A subtitle is measured in tens of kilobytes. Without
 *   a cap, a redirect to something enormous would be read into memory until the process died.
 */
object Http {

    /** Big enough for any subtitle or JSON page; small enough to be harmless. */
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 20_000

    /** A finished request: the status line and the body, whatever the status was. */
    data class Response(val code: Int, val body: String) {
        val isSuccess: Boolean get() = code in 200..299
    }

    fun get(url: String, headers: Map<String, String> = emptyMap()): Response =
        text(url, "GET", headers, null)

    fun postJson(url: String, headers: Map<String, String>, json: String): Response =
        text(url, "POST", headers + ("Content-Type" to "application/json"), json)

    /**
     * Fetches a file into memory. Throws [IOException] on any non-success status.
     *
     * In memory on purpose: the caller hashes the bytes to name the file, so it needs them all
     * anyway, and a subtitle that will not fit in [MAX_BYTES] is not a subtitle.
     */
    fun download(url: String, headers: Map<String, String> = emptyMap()): ByteArray {
        val connection = open(url, "GET", headers)
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("download failed with HTTP $code")
            }
            return readCapped(bodyStream(connection))
        } finally {
            connection.disconnect()
        }
    }

    // ---- internals ----

    private fun text(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
    ): Response {
        val connection = open(url, method, headers)
        try {
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            // The error stream carries the provider's own explanation of what went wrong,
            // which is far more use to the user than "HTTP 403" on its own.
            val stream = if (code in 200..299) bodyStream(connection) else errorStream(connection)
            val text = stream?.let { String(readCapped(it), Charsets.UTF_8) }.orEmpty()
            return Response(code, text)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, method: String, headers: Map<String, String>): HttpURLConnection {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Http must not be called on the main thread"
        }
        require(url.startsWith("https://")) { "only https is allowed, got: $url" }

        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept-Encoding", "gzip")
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        return connection
    }

    private fun bodyStream(connection: HttpURLConnection): InputStream =
        if (connection.contentEncoding.equals("gzip", ignoreCase = true)) {
            GZIPInputStream(connection.inputStream)
        } else {
            connection.inputStream
        }

    private fun errorStream(connection: HttpURLConnection): InputStream? {
        val stream = connection.errorStream ?: return null
        return if (connection.contentEncoding.equals("gzip", ignoreCase = true)) {
            GZIPInputStream(stream)
        } else {
            stream
        }
    }

    private fun readCapped(stream: InputStream): ByteArray {
        stream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_BYTES) throw IOException("response larger than ${MAX_BYTES / 1024} KB")
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }
}
