package com.seamless.player.data.subtitle

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import com.seamless.player.data.Video
import com.seamless.player.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Where a downloaded subtitle lives, and which one a video was last watched with.
 *
 * Two things are kept, and they are kept apart on purpose.
 *
 * **The files.** In the app's own directory, always writable, needing no permission and no
 * grant. Writing next to the video is nicer — every other player would then find it too — but
 * on Android 11 and later an app cannot write into a folder of someone else's files without
 * being handed that folder first, so the app's own directory is the answer that always works
 * and the folder next to the video is the bonus when a grant happens to exist. See
 * [SubtitleFolder].
 *
 * **The choice.** Which track was playing when you left, so the next episode does not need
 * asking again. One small preferences file of its own, the same reasoning as resume
 * positions: a few hundred of these must not slow down reading ordinary settings.
 *
 * File names carry their own metadata — `<video key>.<language>.<content hash>.<extension>` —
 * which is what makes "do not download the same subtitle twice" free. The same bytes produce
 * the same name, so a second download of something already held overwrites itself rather than
 * accumulating.
 */
class SubtitleStore(context: Context) {

    private val app = context.applicationContext
    private val choices = app.getSharedPreferences("subtitles", Context.MODE_PRIVATE)

    /** One saved subtitle file. */
    data class Saved(
        val file: File,
        val videoKey: String,
        val language: String?,
    ) {
        val uri: Uri get() = Uri.fromFile(file)
        val mimeType: String?
            get() = SubtitleFormats.mimeTypeFor(SubtitleFormats.extensionOf(file.name))
    }

    private val directory: File
        get() = File(app.filesDir, "subtitles").apply { if (!exists()) mkdirs() }

    /**
     * A stable name for a video.
     *
     * MediaStore's id where there is one. A video handed over by another app has no id, and
     * its content URI is the only stable thing about it, so that is hashed instead — the URI
     * itself cannot be a file name.
     */
    fun keyFor(video: Video): String =
        if (video.id > 0) video.id.toString() else "u" + shortHash(video.uri.toString().toByteArray())

    // ---- files ----

    /** Everything held for one video. Cheap: one directory listing, filtered by prefix. */
    fun saved(videoKey: String): List<Saved> {
        val prefix = "$videoKey."
        val files = directory.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.name.startsWith(prefix) && SubtitleFormats.isSubtitle(it.name) }
            .sortedBy { it.name }
            .map { Saved(it, videoKey, languageOf(videoKey, it.name)) }
    }

    /**
     * Everything held, keyed by video, in a single directory listing.
     *
     * For the shorts feed, which needs the answer for a thousand clips at once and must not do a
     * thousand listings to get it. Almost always empty — nobody downloads subtitles for a clip
     * off their own camera — and empty is exactly the case this makes free.
     */
    fun savedByVideo(): Map<String, List<Saved>> {
        val files = directory.listFiles() ?: return emptyMap()
        val byKey = HashMap<String, MutableList<Saved>>()
        files.forEach { file ->
            if (!file.isFile || !SubtitleFormats.isSubtitle(file.name)) return@forEach
            val key = file.name.substringBefore('.').takeIf { it.isNotEmpty() } ?: return@forEach
            byKey.getOrPut(key) { mutableListOf() } += Saved(file, key, languageOf(key, file.name))
        }
        byKey.values.forEach { it.sortBy { saved -> saved.file.name } }
        return byKey
    }

    /** Every video that has something saved, so settings can say how much is held. */
    fun totalBytes(): Long = directory.listFiles()?.sumOf { it.length() } ?: 0L

    fun count(): Int = directory.listFiles()?.count { it.isFile } ?: 0

    /**
     * Writes [bytes] and returns the file, or null if it could not be written.
     *
     * Overwrites silently when the same bytes for the same video and language are already
     * held, which is the whole point of the content hash in the name.
     */
    fun save(videoKey: String, language: String?, extension: String, bytes: ByteArray): Saved? {
        val tag = SubtitleLanguages.normalise(language) ?: "und"
        val name = "$videoKey.$tag.${shortHash(bytes)}.$extension"
        val file = File(directory, name)
        return try {
            file.writeBytes(bytes)
            Saved(file, videoKey, tag.takeIf { it != "und" })
        } catch (error: Exception) {
            Log.e("SubtitleStore", "cannot write $name", error)
            null
        }
    }

    /** Undo, for the transient notice shown after an automatic match. */
    fun delete(saved: Saved) {
        runCatching { saved.file.delete() }
    }

    fun clear() {
        directory.listFiles()?.forEach { runCatching { it.delete() } }
        choices.edit { clear() }
    }

    // ---- remembered choice ----

    /**
     * The track id last chosen for this video, [OFF] if subtitles were switched off, or null
     * if the question has never come up.
     */
    fun rememberedChoice(videoKey: String): String? = choices.getString(videoKey, null)

    fun remember(videoKey: String, trackId: String?) = choices.edit {
        if (trackId == null) remove(videoKey) else putString(videoKey, trackId)
    }

    // ---- naming ----

    /** Pulls the language back out of a stored file name. */
    private fun languageOf(videoKey: String, fileName: String): String? {
        val rest = fileName.removePrefix("$videoKey.")
        val tag = rest.substringBefore('.')
        return if (tag == "und") null else SubtitleLanguages.normalise(tag)
    }

    private fun shortHash(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        return digest.take(5).joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Stored as the remembered choice when the user turned subtitles off for a video. */
        const val OFF = "off"
    }
}
