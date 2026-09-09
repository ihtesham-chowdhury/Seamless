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
 *
 * That covers the identical file and not the near-identical one, which is the case that
 * actually happens: search again, pick a different upload of the same English subtitle, and the
 * bytes differ by a line of timing. The panel then shows English, English, English, English,
 * with nothing to tell them apart. So [save] goes further and replaces what it supersedes — one
 * subtitle per video per language, because a second English subtitle is not a second choice, it
 * is a correction of the first. Anything genuinely different is a different language and keeps
 * its own row.
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

    /**
     * Drops everything but the newest subtitle per language for one video.
     *
     * [save] keeps this true going forward; this is for the phones where it already went wrong.
     * Four files called English accumulated before there was a rule, and a rule that only
     * applies to future downloads would leave them there for ever with no way to be rid of
     * them. Newest wins, because the reason there is a fourth is that the third was not right.
     *
     * Returns whether anything was removed. Does file I/O; never call it on the main thread.
     */
    fun prune(videoKey: String): Boolean {
        val prefix = "$videoKey."
        val mine = directory.listFiles()
            ?.filter { it.isFile && it.name.startsWith(prefix) && SubtitleFormats.isSubtitle(it.name) }
            ?: return false
        if (mine.size < 2) return false

        var removed = false
        mine.groupBy { languageOf(videoKey, it.name) ?: "und" }.forEach { (_, group) ->
            if (group.size < 2) return@forEach
            val newest = group.maxByOrNull { it.lastModified() } ?: return@forEach
            group.forEach { file ->
                if (file == newest) return@forEach
                if (runCatching { file.delete() }.getOrDefault(false)) removed = true
            }
        }
        return removed
    }

    /**
     * Deletes one file, if it is one of ours.
     *
     * The caller reaches this holding a URI it read off a player track, which is two hops from
     * anything that checked what the file is. So the check is here: a path outside this store's
     * own directory is refused rather than trusted, and there is no route from a mismatched
     * track id to deleting something that matters.
     */
    fun deleteIfOwned(uri: Uri): Boolean {
        val path = uri.path ?: return false
        val file = File(path)
        val parent = runCatching { file.canonicalFile.parentFile }.getOrNull() ?: return false
        val mine = runCatching { directory.canonicalFile }.getOrNull() ?: return false
        if (parent != mine) {
            Log.w("SubtitleStore", "refusing to delete $path: not in the store")
            return false
        }
        return runCatching { file.delete() }.getOrDefault(false)
    }

    /** Every video that has something saved, so settings can say how much is held. */
    fun totalBytes(): Long = directory.listFiles()?.sumOf { it.length() } ?: 0L

    fun count(): Int = directory.listFiles()?.count { it.isFile } ?: 0

    /**
     * Writes [bytes] and returns the file, or null if it could not be written.
     *
     * Replaces anything already held for this video in this language. See the note at the top
     * of the class: the alternative is a panel listing four subtitles all called English, and
     * the fourth is there because the first three were wrong.
     *
     * The replacement happens after the write, not before, so a failed download leaves the
     * subtitle that was working exactly where it was.
     */
    fun save(videoKey: String, language: String?, extension: String, bytes: ByteArray): Saved? {
        val tag = SubtitleLanguages.normalise(language) ?: "und"
        val name = "$videoKey.$tag.${shortHash(bytes)}.$extension"
        val file = File(directory, name)
        return try {
            file.writeBytes(bytes)
            supersede(videoKey, tag, keep = name)
            Saved(file, videoKey, tag.takeIf { it != "und" })
        } catch (error: Exception) {
            Log.e("SubtitleStore", "cannot write $name", error)
            null
        }
    }

    /** Undo, for the transient notice shown after an automatic match, and the delete button. */
    fun delete(saved: Saved) {
        runCatching { saved.file.delete() }
    }

    /**
     * Every earlier file for this video and language, gone.
     *
     * Prefix-matched on `<key>.<tag>.` rather than by listing and parsing, so a name this
     * version does not understand — written by an older one, or by a future one — is either
     * matched exactly or left alone. Never deletes [keep], which is the file just written.
     */
    private fun supersede(videoKey: String, tag: String, keep: String) {
        val prefix = "$videoKey.$tag."
        directory.listFiles()?.forEach { file ->
            if (!file.isFile || file.name == keep) return@forEach
            if (!file.name.startsWith(prefix)) return@forEach
            if (!SubtitleFormats.isSubtitle(file.name)) return@forEach
            runCatching { file.delete() }
        }
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
