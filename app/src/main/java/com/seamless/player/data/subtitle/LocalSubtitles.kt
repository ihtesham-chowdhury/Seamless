package com.seamless.player.data.subtitle

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import com.seamless.player.data.Video

/**
 * Where a subtitle came from.
 *
 * Carried in the track's id rather than kept in a table beside it, because once a subtitle is
 * handed to Media3 the player's own track list becomes the only thing that knows what exists —
 * and a list held alongside it would have to be kept in step through every prepare, rebuild
 * and recycle. The id survives all of that for free.
 */
enum class SubtitleOrigin {
    /** A text track inside the video file itself. */
    EMBEDDED,

    /** A file sitting in the same folder as the video. */
    BESIDE,

    /** Downloaded or picked, and kept in the app's own store. */
    SAVED;

    companion object {
        private const val PREFIX = "seamless-sub"

        /** The id given to a sidecar track, which comes back as `Format.id`. */
        fun trackId(origin: SubtitleOrigin, index: Int) = "$PREFIX:${origin.name}:$index"

        /**
         * Reads an origin back off a track id.
         *
         * Anything not of our making is [EMBEDDED] — which is exactly right, because a track
         * we did not attach came out of the container.
         */
        fun of(formatId: String?): SubtitleOrigin {
            val parts = formatId?.split(':') ?: return EMBEDDED
            if (parts.size < 2 || parts[0] != PREFIX) return EMBEDDED
            return entries.firstOrNull { it.name == parts[1] } ?: EMBEDDED
        }
    }
}

/**
 * One subtitle file to hand to the player.
 *
 * Deliberately not a "track": whether it becomes a selectable track is Media3's business, and
 * a file that turns out to be unreadable simply never shows up. That is the right failure —
 * silent absence rather than a row that does nothing when tapped.
 */
data class Sidecar(
    val id: String,
    val uri: Uri,
    val mimeType: String,
    val language: String?,
    val label: String,
    val origin: SubtitleOrigin,
) {
    fun toConfiguration(): MediaItem.SubtitleConfiguration =
        MediaItem.SubtitleConfiguration.Builder(uri)
            .setId(id)
            .setMimeType(mimeType)
            .setLanguage(language)
            .setLabel(label)
            .build()
}

/**
 * Finding the subtitles a video already has, without going near the network.
 *
 * Runs before playback starts, once per video, off the main thread. It costs at most two
 * directory listings — the app's own store, and the video's folder if a grant lets us look —
 * and never opens a video file, never decodes a frame, and never consults MediaStore.
 *
 * The store comes first deliberately. It is the only source that is certain to be readable,
 * it holds anything downloaded or picked before, and a subtitle found there means the online
 * search does not run again for a file it has already answered.
 */
object LocalSubtitles {

    /**
     * Everything local, ready to attach to a media item.
     *
     * Does file I/O. Never call this on the main thread.
     */
    fun discover(context: Context, store: SubtitleStore, video: Video): List<Sidecar> {
        val key = store.keyFor(video)
        // Before anything is read, not after: a video with four leftover copies of the same
        // English subtitle should never get as far as building four tracks out of them.
        store.prune(key)
        val sidecars = fromStore(store.saved(key)).toMutableList()

        // A video from outside the library has no folder to look in — relativePath is empty
        // and companions() would be listing the root of storage.
        if (video.relativePath.isNotBlank()) {
            SubtitleFolder.companions(context, video).forEachIndexed { index, file ->
                val mime = SubtitleFormats.mimeTypeFor(SubtitleFormats.extensionOf(file.name))
                    ?: return@forEachIndexed
                val tags = SubtitleNames.tagsOf(video.name, file.name)
                sidecars += Sidecar(
                    id = SubtitleOrigin.trackId(SubtitleOrigin.BESIDE, index),
                    uri = file.uri,
                    mimeType = mime,
                    language = tags.language,
                    label = labelFor(tags, file.name),
                    origin = SubtitleOrigin.BESIDE,
                )
            }
        }

        return sidecars
    }

    /**
     * Saved files as sidecars, without going near the filesystem again.
     *
     * Split out because the shorts feed needs exactly this and nothing else: it reads the whole
     * store in one listing for the entire feed and then maps each clip's share of it, rather than
     * looking in the video's own folder — which would be a directory listing per page, during
     * scrolling, for a result that is empty on every clip anyone has ever filmed.
     */
    fun fromStore(saved: List<SubtitleStore.Saved>): List<Sidecar> =
        saved.mapIndexedNotNull { index, file ->
            val mime = file.mimeType ?: return@mapIndexedNotNull null
            Sidecar(
                id = SubtitleOrigin.trackId(SubtitleOrigin.SAVED, index),
                uri = file.uri,
                mimeType = mime,
                language = file.language,
                label = SubtitleLanguages.displayName(file.language).ifEmpty { file.file.name },
                origin = SubtitleOrigin.SAVED,
            )
        }

    /**
     * "English", "English · forced", or the file name where the name told us nothing.
     *
     * A file name is a poor label and a good last resort: it is at least true, and it is the
     * only thing that distinguishes two untagged subtitles from each other.
     */
    private fun labelFor(tags: SubtitleNames.Tags, fileName: String): String {
        val base = SubtitleLanguages.displayName(tags.language).ifEmpty { fileName }
        val extra = buildList {
            if (tags.forced) add("forced")
            if (tags.sdh) add("SDH")
        }
        return if (extra.isEmpty()) base else "$base · ${extra.joinToString(" · ")}"
    }
}
