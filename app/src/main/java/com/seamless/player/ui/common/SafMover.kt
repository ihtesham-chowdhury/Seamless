package com.seamless.player.ui.common

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.seamless.player.data.Video
import com.seamless.player.util.Log

/**
 * Moves videos into a folder the user picked through the Storage Access Framework.
 *
 * This exists because MediaStore refuses some perfectly ordinary moves. It does not just
 * check permissions — it also polices *where* a video is allowed to live, and rejects a
 * `RELATIVE_PATH` pointing at a directory it does not recognise as a media directory. A
 * folder like "VideoTapes" at the root of storage is not one, so no amount of granted
 * write access will let the move through.
 *
 * SAF has no such rule. Once the user has pointed at a destination folder, the app holds a
 * grant for that tree and can write into it directly, which is how file managers and other
 * players manage moves MediaStore would refuse.
 *
 * The move is a copy followed by a delete, and the delete only happens once the copy has
 * been verified — losing the original to a half-written copy would be unforgivable.
 */
object SafMover {

    data class Result(val moved: Int, val failed: Int, val problem: String?)

    /**
     * Copies [videos] into [treeUri]. Returns what happened; the caller is responsible for
     * deleting the originals, which needs MediaStore's own consent flow.
     */
    fun copyInto(context: Context, treeUri: Uri, videos: List<Video>): Pair<Result, List<Video>> {
        val destination = DocumentFile.fromTreeUri(context, treeUri)
        if (destination == null || !destination.canWrite()) {
            return Result(0, videos.size, "Cannot write to that folder") to emptyList()
        }

        val resolver = context.contentResolver
        var moved = 0
        var problem: String? = null
        val copied = mutableListOf<Video>()

        for (video in videos) {
            val existing = destination.findFile(video.name)
            if (existing != null) {
                if (problem == null) problem = "\"${video.name}\" is already there"
                continue
            }

            val target = destination.createFile("video/*", video.name)
            if (target == null) {
                if (problem == null) problem = "Could not create \"${video.name}\""
                continue
            }

            val bytes = runCatching {
                resolver.openInputStream(video.uri)!!.use { input ->
                    resolver.openOutputStream(target.uri)!!.use { output ->
                        input.copyTo(output)
                    }
                }
            }.onFailure { error ->
                Log.e("SafMover", "copy failed for ${video.name}", error)
                if (problem == null) problem = error.message ?: "Copy failed"
            }.getOrNull()

            // Only treat it as copied if the whole file arrived. A short write means the
            // original must stay exactly where it is.
            if (bytes != null && bytes == video.sizeBytes) {
                moved++
                copied += video
            } else {
                if (bytes != null && problem == null) {
                    problem = "\"${video.name}\" copied incompletely"
                }
                target.delete()
            }
        }

        return Result(moved, videos.size - moved, problem) to copied
    }
}
