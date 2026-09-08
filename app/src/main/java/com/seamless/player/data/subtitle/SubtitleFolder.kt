package com.seamless.player.data.subtitle

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.seamless.player.data.Video
import com.seamless.player.util.Log

/**
 * Reading and writing the folder a video actually lives in.
 *
 * This is the part of subtitles that is a platform problem rather than a design decision, so
 * it is worth being exact about.
 *
 * A subtitle file is not media. `READ_MEDIA_VIDEO` — the permission this app asks for — grants
 * videos and nothing else, and on Android 13 and later there is no permission that grants
 * ordinary files belonging to other apps at all. So on a modern phone the app genuinely cannot
 * see `Movie.srt` sitting beside `Movie.mkv`, however plainly the user can see it in their
 * file manager. That is not a bug to be worked around; it is scoped storage working.
 *
 * There are exactly two honest routes, and both are here:
 *
 * 1. **A folder the user hands over.** `ACTION_OPEN_DOCUMENT_TREE` on the video's folder, taken
 *    persistably. From then on every companion subtitle in that folder is found automatically,
 *    for every video in it, for good — and downloads can be written back beside the video
 *    where any other player will also find them. One prompt, permanently.
 * 2. **A direct read, tried and allowed to fail.** On Android 12 and earlier the broad storage
 *    permission this app still declares does cover ordinary files, so the plain file path
 *    works there and costs one `listFiles`. It fails with a permission error on 13 and later,
 *    which is caught and means nothing worse than "nothing found this way".
 *
 * The third route — picking a single `.srt` through the document picker — lives in the player,
 * because the file it produces is copied into [SubtitleStore] and stops being this object's
 * business.
 */
object SubtitleFolder {

    /**
     * The granted tree that contains [video], walked down to the video's own folder.
     *
     * Null when no grant covers it, which is the ordinary case until the user gives one.
     */
    fun folderFor(context: Context, video: Video): DocumentFile? {
        val relative = video.relativePath.trim('/')
        if (relative.isEmpty()) return null

        context.contentResolver.persistedUriPermissions.forEach { permission ->
            if (!permission.isReadPermission) return@forEach
            val treeUri = permission.uri
            val treePath = treePathOf(treeUri) ?: return@forEach

            // "" is a grant on a whole volume and covers everything on it.
            val covers = treePath.isEmpty() ||
                relative == treePath ||
                relative.startsWith("$treePath/")
            if (!covers) return@forEach

            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@forEach
            val remainder = relative.removePrefix(treePath).trim('/')
            val folder = walk(root, remainder)
            if (folder != null) return folder
        }
        return null
    }

    /**
     * Every companion subtitle of [video], from whichever route works.
     *
     * The granted folder first: it is the one that is reliable. The direct path second, and
     * only for names the first did not already produce.
     *
     * Does file I/O. Never call this on the main thread.
     */
    fun companions(context: Context, video: Video): List<SubtitleFile> {
        val found = LinkedHashMap<String, SubtitleFile>()

        folderFor(context, video)?.let { folder ->
            runCatching {
                folder.listFiles().forEach { entry ->
                    val name = entry.name ?: return@forEach
                    if (!entry.isFile || !SubtitleNames.belongsTo(video.name, name)) return@forEach
                    found.putIfAbsent(name.lowercase(), SubtitleFile(entry.uri, name))
                }
            }.onFailure { Log.w("SubtitleFolder", "cannot list granted folder: ${it.message}") }
        }

        // Android 12 and earlier only; a caught SecurityException on newer releases.
        runCatching {
            val parent = java.io.File("/storage/emulated/0/${video.relativePath}")
            parent.listFiles()?.forEach { file ->
                if (!file.isFile || !SubtitleNames.belongsTo(video.name, file.name)) return@forEach
                found.putIfAbsent(file.name.lowercase(), SubtitleFile(Uri.fromFile(file), file.name))
            }
        }

        return found.values.toList()
    }

    /**
     * Writes [bytes] into the video's own folder, if a grant allows it. Returns the file's URI.
     *
     * Called after the subtitle has already been saved to [SubtitleStore], so a failure here
     * costs nothing — the subtitle still plays and is still found next time. This is the bonus
     * copy, the one other players can see.
     */
    fun writeBeside(context: Context, video: Video, fileName: String, bytes: ByteArray): Uri? {
        val folder = folderFor(context, video) ?: return null
        if (!folder.canWrite()) return null
        return try {
            folder.findFile(fileName)?.delete()
            val target = folder.createFile("application/x-subrip", fileName) ?: return null
            context.contentResolver.openOutputStream(target.uri)?.use { it.write(bytes) }
                ?: return null
            target.uri
        } catch (error: Exception) {
            Log.w("SubtitleFolder", "cannot write $fileName beside the video: ${error.message}")
            null
        }
    }

    /** Keeps the grant across restarts. Without this it lasts until the process dies. */
    fun remember(context: Context, treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure { Log.w("SubtitleFolder", "grant not persistable: ${it.message}") }
    }

    /** One subtitle file sitting beside a video. */
    data class SubtitleFile(val uri: Uri, val name: String)

    // ---- internals ----

    /**
     * The path part of a tree's document id: "primary:Movies/Action" -> "Movies/Action".
     *
     * A grant on the root of a volume gives "primary:" and therefore an empty path, which is
     * treated as covering everything rather than as a failure to parse.
     */
    private fun treePathOf(treeUri: Uri): String? {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return null
        val colon = documentId.indexOf(':')
        if (colon < 0) return null
        return documentId.substring(colon + 1).trim('/')
    }

    /** Steps down [path] one folder at a time, giving up the moment a step is missing. */
    private fun walk(root: DocumentFile, path: String): DocumentFile? {
        if (path.isEmpty()) return root
        var current: DocumentFile = root
        path.split('/').filter { it.isNotEmpty() }.forEach { segment ->
            current = current.findFile(segment)?.takeIf { it.isDirectory } ?: return null
        }
        return current
    }

    /**
     * Where the folder picker should open, best effort.
     *
     * Built from the documents provider's own id scheme, and handed to
     * `ActivityResultContracts.OpenDocumentTree` as its input. It is only a hint — pickers are
     * free to ignore it, and some do — so a wrong guess costs the user one extra tap rather than
     * anything worse.
     */
    fun initialFolderUri(video: Video): Uri? {
        val relative = video.relativePath.trim('/')
        if (relative.isEmpty()) return null
        return runCatching {
            DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:$relative",
            )
        }.getOrNull()
    }
}
