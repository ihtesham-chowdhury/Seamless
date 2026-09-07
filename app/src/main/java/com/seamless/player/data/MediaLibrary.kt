package com.seamless.player.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.seamless.player.util.Log
import kotlin.random.Random

/**
 * Reads the device's video library.
 *
 * There is deliberately no local database cache here. MediaStore *is* the index — it is a
 * system-maintained, indexed SQLite database, and one projected query over it costs a few
 * hundred milliseconds even for thousands of rows. Mirroring it into our own table would
 * add a staleness problem and save nothing.
 */
object MediaLibrary {

    private val PROJECTION = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.RELATIVE_PATH,
        MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.WIDTH,
        MediaStore.Video.Media.HEIGHT,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DATE_MODIFIED,
        MediaStore.MediaColumns.ORIENTATION,
    )

    /**
     * Every playable video on external storage, newest first.
     *
     * [excludedPaths] are dropped by prefix, so hiding a folder hides everything under it.
     * Call off the main thread.
     */
    fun queryAll(context: Context, excludedPaths: Set<String> = emptySet()): List<Video> {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val out = ArrayList<Video>(512)

        val cursor = context.contentResolver.query(
            collection,
            PROJECTION,
            "${MediaStore.Video.Media.DURATION} > 0",
            null,
            "${MediaStore.Video.Media.DATE_MODIFIED} DESC",
        ) ?: return emptyList()

        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            val bucketCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val wCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val hCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val orientCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.ORIENTATION)

            while (c.moveToNext()) {
                val path = c.getString(pathCol) ?: ""
                if (excludedPaths.any { path.startsWith(it) }) continue

                val id = c.getLong(idCol)
                val rawW = c.getInt(wCol)
                val rawH = c.getInt(hCol)
                val rotation = if (c.isNull(orientCol)) 0 else c.getInt(orientCol)

                // A phone-shot "portrait" video is often stored as 1920x1080 with a 90 degree
                // rotation flag. Swap the axes so orientation checks mean what they say.
                val quarterTurned = rotation == 90 || rotation == 270
                val width = if (quarterTurned) rawH else rawW
                val height = if (quarterTurned) rawW else rawH

                out += Video(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    name = c.getString(nameCol) ?: "video_$id",
                    relativePath = path,
                    folderName = c.getString(bucketCol) ?: path.trimEnd('/').substringAfterLast('/'),
                    durationMs = c.getLong(durCol),
                    width = width,
                    height = height,
                    sizeBytes = c.getLong(sizeCol),
                    dateModified = c.getLong(dateCol),
                )
            }
        }
        Log.d("MediaLibrary", "queried ${out.size} videos, ${excludedPaths.size} folders hidden")
        return out
    }

    /**
     * Groups a video list into folders.
     *
     * [chosenCover] is asked, per folder, for the id of the video the user picked as its
     * thumbnail; return 0 for "no choice". A choice that no longer matches anything in the
     * folder — the file was deleted or moved — quietly falls back to the first video rather
     * than leaving the folder blank.
     */
    fun foldersOf(
        videos: List<Video>,
        chosenCover: (String) -> Long = { 0L },
    ): List<VideoFolder> =
        videos.groupBy { it.relativePath }
            .map { (path, items) ->
                VideoFolder(
                    path = path,
                    name = items.first().folderName.ifBlank {
                        path.trimEnd('/').substringAfterLast('/').ifBlank { "Internal storage" }
                    },
                    videoCount = items.size,
                    portraitCount = items.count { it.isPortrait },
                    totalBytes = items.sumOf { it.sizeBytes },
                    newestModified = items.maxOf { it.dateModified },
                    cover = chosenCover(path)
                        .takeIf { it != 0L }
                        ?.let { id -> items.firstOrNull { it.id == id } }
                        ?: items.firstOrNull(),
                )
            }

    /**
     * Moves the pinned items to the front, keeping the existing order inside each group.
     *
     * Applied after sorting rather than folded into the comparator, so that pinning and
     * sorting stay independent: change the sort and the pinned things stay put at the top,
     * arranged among themselves by whatever the new sort says.
     */
    fun <T> pinnedFirst(items: List<T>, isPinned: (T) -> Boolean): List<T> {
        val (pinned, rest) = items.partition(isPinned)
        return if (pinned.isEmpty()) items else pinned + rest
    }

    /**
     * Videos belonging to [folderPaths], treating each entry as "this folder and everything
     * beneath it" via a prefix match.
     */
    fun videosUnder(videos: List<Video>, folderPaths: Set<String>): List<Video> {
        if (folderPaths.isEmpty()) return emptyList()
        return videos.filter { v -> folderPaths.any { v.relativePath.startsWith(it) } }
    }

    /**
     * Narrows the whole library down to what belongs in a shorts feed.
     *
     * A portrait clip always qualifies, whatever its length — that is the defining shape of
     * the format. A landscape clip only qualifies if the user opted into short landscape
     * material and it comes in under the cut-off.
     */
    fun shortsCandidates(
        videos: List<Video>,
        includeShortLandscape: Boolean,
        maxLandscapeSeconds: Int,
    ): List<Video> {
        val capMs = maxLandscapeSeconds * 1000L
        return videos.filter { video ->
            video.isPortrait || (includeShortLandscape && video.durationMs in 1..capMs)
        }
    }

    // ---- sorting ----

    fun sortFolders(folders: List<VideoFolder>, sort: SortSetting): List<VideoFolder> {
        if (sort.key == SortKey.RANDOM) return folders.shuffled(Random(sort.seed))
        val ordered = when (sort.key) {
            SortKey.TITLE -> folders.sortedBy { it.name.lowercase() }
            SortKey.DATE -> folders.sortedBy { it.newestModified }
            SortKey.SIZE -> folders.sortedBy { it.totalBytes }
            SortKey.COUNT -> folders.sortedBy { it.videoCount }
            // Folders have no single duration; fall back to something sensible.
            SortKey.DURATION, SortKey.RANDOM -> folders.sortedBy { it.videoCount }
        }
        return if (sort.ascending) ordered else ordered.reversed()
    }

    fun sortVideos(videos: List<Video>, sort: SortSetting): List<Video> {
        // Not reversed for a direction: an order with no meaning has no other way round,
        // and offering one would only make the same list look like a different answer.
        if (sort.key == SortKey.RANDOM) return videos.shuffled(Random(sort.seed))
        val ordered = when (sort.key) {
            SortKey.TITLE -> videos.sortedBy { it.name.lowercase() }
            SortKey.DATE -> videos.sortedBy { it.dateModified }
            SortKey.SIZE -> videos.sortedBy { it.sizeBytes }
            SortKey.DURATION -> videos.sortedBy { it.durationMs }
            // Videos have no count; keep the order stable rather than doing something odd.
            SortKey.COUNT, SortKey.RANDOM -> videos.sortedBy { it.dateModified }
        }
        return if (sort.ascending) ordered else ordered.reversed()
    }
}
