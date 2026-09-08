package com.seamless.player.data.subtitle

import android.content.Context
import android.net.Uri
import com.seamless.player.util.Log
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * The hash subtitle sites match releases by.
 *
 * It is the one signal in the whole online-matching business that is not a guess. Two files
 * with the same hash are the same encode, frame for frame, so a subtitle uploaded against
 * that hash is timed for exactly the file in hand — no offset, no "close enough". Everything
 * else — title, year, resolution, release group — is inference from a name someone typed.
 *
 * The algorithm is OpenSubtitles' own and is deliberately cheap: the file size, plus every
 * 64-bit little-endian word of the first 64 KiB and of the last 64 KiB, added together with
 * wraparound. 128 KiB read from a 40 GB remux, which is why it can be done on the way into
 * playback rather than as a background chore.
 *
 * Reads happen through absolute positions on a channel, so the file's own read position is
 * never touched and this cannot interfere with anything else holding the same descriptor.
 */
object OsdbHash {

    private const val CHUNK = 64 * 1024
    private const val MIN_SIZE = 2L * CHUNK

    /**
     * 16 lower-case hex digits, or null when the file is too small to hash or cannot be read.
     *
     * Null is an ordinary answer, not an error: a two-minute phone clip is below the minimum,
     * and matching such a thing by hash was never going to work anyway. Callers fall back to
     * a text query.
     *
     * Does file I/O. Never call this on the main thread.
     */
    fun of(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                val size = descriptor.statSize
                if (size < MIN_SIZE) return null

                FileInputStream(descriptor.fileDescriptor).use { stream ->
                    val channel = stream.channel
                    var hash = size
                    hash += sumWords(channel, 0L)
                    hash += sumWords(channel, size - CHUNK)
                    String.format("%016x", hash)
                }
            }
        } catch (error: Exception) {
            // Anything at all here — a URI that has gone away, a descriptor the provider
            // will not hand over, a file on a share that stalled — means no hash, which is a
            // state the caller already handles.
            Log.w("OsdbHash", "cannot hash $uri: ${error.message}")
            null
        }
    }

    /** Adds up the 8192 little-endian longs of one 64 KiB chunk starting at [offset]. */
    private fun sumWords(channel: FileChannel, offset: Long): Long {
        val buffer = ByteBuffer.allocate(CHUNK).order(ByteOrder.LITTLE_ENDIAN)
        var position = offset
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, position)
            if (read <= 0) break
            position += read
        }
        buffer.flip()
        var sum = 0L
        // Deliberately ignores a trailing partial word: the chunk is a whole multiple of 8
        // whenever the file is big enough to be hashed at all.
        while (buffer.remaining() >= 8) sum += buffer.long
        return sum
    }
}
