package com.seamless.player.data

import android.net.Uri

/**
 * One video file.
 *
 * Everything here comes straight out of MediaStore columns. We never open the file or
 * touch MediaMetadataRetriever to build this — that is the single biggest reason the
 * library opens instantly even with thousands of files.
 *
 * [width] and [height] are already rotation-corrected, so [isPortrait] is trustworthy.
 */
data class Video(
    val id: Long,
    val uri: Uri,
    val name: String,
    val relativePath: String,
    val folderName: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val dateModified: Long,
) {
    val isPortrait: Boolean get() = height > width && height > 0
}

/**
 * A directory containing videos, keyed by MediaStore's RELATIVE_PATH (e.g. "Download/Reels/").
 *
 * Using the path rather than BUCKET_ID is deliberate: it lets us treat a folder as
 * "this folder plus everything under it" with a simple prefix match.
 */
data class VideoFolder(
    val path: String,
    val name: String,
    val videoCount: Int,
    val portraitCount: Int,
    val totalBytes: Long,
    val newestModified: Long,
    val cover: Video?,
) {
    /** Drives the "force-fit the folder's dominant orientation" rule. */
    val isMostlyPortrait: Boolean get() = portraitCount * 2 > videoCount
}
