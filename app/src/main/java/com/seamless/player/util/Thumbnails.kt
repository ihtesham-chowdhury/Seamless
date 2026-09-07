package com.seamless.player.util

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import com.seamless.player.data.Video
import java.util.concurrent.Executors

/**
 * Small thumbnail loader built on ContentResolver.loadThumbnail (API 29+).
 *
 * The system already generates and caches these, so this avoids both an image-loading
 * dependency and any per-file MediaMetadataRetriever work.
 */
object Thumbnails {

    /**
     * Keyed by video *and* requested width, not by video alone.
     *
     * The same file is asked for at two sizes — small for a library tile, large for the
     * feed's poster frame — and with an id-only key whichever loaded first would be handed
     * back to the other. A grid thumbnail blown up to fill a phone screen looks exactly as
     * bad as it sounds.
     */
    private val memory = object : LruCache<String, Bitmap>(
        // A twelfth of the heap is plenty for grid thumbnails.
        (Runtime.getRuntime().maxMemory() / 1024 / 12).toInt().coerceAtLeast(4 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    private val executor = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "thumb-loader").apply { priority = Thread.MIN_PRIORITY }
    }
    private val main = Handler(Looper.getMainLooper())

    /**
     * Loads [video]'s thumbnail into [view]. Safe against RecyclerView reuse: the view is
     * tagged with the video id and a late result for a recycled row is dropped.
     */
    fun load(view: ImageView, video: Video, size: Size = Size(320, 320)) {
        val key = "${video.id}@${size.width}"
        val cached = memory.get(key)
        view.tag = key
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(null)

        val context: Context = view.context.applicationContext
        executor.execute {
            val bitmap = try {
                context.contentResolver.loadThumbnail(video.uri, size, null)
            } catch (t: Throwable) {
                // Missing or unreadable thumbnails are routine; leave the placeholder.
                Log.d("Thumbnails", "no thumbnail for ${video.name}: ${t.message}")
                null
            } ?: return@execute

            memory.put(key, bitmap)
            main.post { if (view.tag == key) view.setImageBitmap(bitmap) }
        }
    }
}
