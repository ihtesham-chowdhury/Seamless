package com.seamless.player.ui.common

import android.app.Activity
import android.content.ClipData
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.FileProvider
import com.seamless.player.R
import com.seamless.player.util.Background
import com.seamless.player.util.Log
import java.io.File

/**
 * Hands a video to another app, however it reached Seamless.
 *
 * A video from the library is a MediaStore entry, and passing it on is a matter of attaching a
 * read grant. A video opened from a gallery or a file manager is not, and sharing one used to
 * close the player, for either of two reasons:
 *
 * - A `file://` address may not leave the app at all. For anything targeting Android 7 or later
 *   the system throws FileUriExposedException the moment one is put in an outgoing intent, and
 *   some file managers and older galleries still send them.
 * - A `content://` address belongs to the app that sent it. Seamless holds a temporary grant to
 *   read it, and startActivity throws a SecurityException when that grant cannot be passed on.
 *
 * So the address is first swapped for one Seamless can vouch for: the same file's MediaStore
 * entry, found by its path or by its name and size, and failing that, for a file path, this
 * app's own FileProvider. Whatever is left is tried as it came, and a refusal is a message
 * rather than a crash.
 */
object VideoShare {

    private const val TAG = "VideoShare"

    /** Must match the share provider's authority in the manifest. */
    private const val AUTHORITY_SUFFIX = ".share"

    fun share(activity: Activity, uri: Uri) {
        val context = activity.applicationContext
        Background.run(
            work = { resolve(context, uri) },
            then = { target -> send(activity, target) },
            // Resolving only improves the address; if it fails, try the address as it came.
            onFailure = { send(activity, Target(uri, null)) },
        )
    }

    /** An address that can be handed on, and its type when its owner will say. */
    private class Target(val uri: Uri, val type: String?)

    /** Call off the main thread: every branch asks a content provider something. */
    private fun resolve(context: Context, uri: Uri): Target? {
        val shareable = when (uri.scheme) {
            ContentResolver.SCHEME_FILE -> uri.path?.let { path ->
                mediaStoreByPath(context, path) ?: ownProvider(context, path)
            }
            ContentResolver.SCHEME_CONTENT ->
                if (uri.authority == MediaStore.AUTHORITY) uri
                else mediaStoreByNameAndSize(context, uri) ?: uri
            else -> null
        } ?: return null
        val type = runCatching { context.contentResolver.getType(shareable) }.getOrNull()
        return Target(shareable, type)
    }

    private fun send(activity: Activity, target: Target?) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (target == null) {
            refuse(activity)
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = target.type?.takeIf { it.startsWith("video/") } ?: "video/*"
            putExtra(Intent.EXTRA_STREAM, target.uri)
            // The grant travels with the clip data. Setting it here, rather than leaving the
            // chooser to copy it from the extra, also gives the share sheet its preview.
            clipData = ClipData.newRawUri("", target.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            activity.startActivity(Intent.createChooser(send, activity.getString(R.string.share_video)))
        } catch (refused: RuntimeException) {
            // SecurityException when a borrowed grant cannot be passed on, FileUriExposedException
            // for a file path that slipped through. Either way nothing was sent, and saying so is
            // better than closing the player on someone halfway through a film.
            Log.e(TAG, "share refused for a ${target.uri.scheme} address", refused)
            refuse(activity)
        }
    }

    private fun refuse(activity: Activity) {
        Toast.makeText(activity, R.string.share_unavailable, Toast.LENGTH_LONG).show()
    }

    /** The MediaStore entry for a file path, tried as given and with its links resolved. */
    @Suppress("DEPRECATION") // DATA is deprecated for reaching files through; a lookup is fine.
    private fun mediaStoreByPath(context: Context, path: String): Uri? {
        val canonical = runCatching { File(path).canonicalPath }.getOrDefault(path)
        return firstVideo(
            context,
            "${MediaStore.MediaColumns.DATA} IN (?, ?)",
            arrayOf(path, canonical),
        )
    }

    /**
     * The MediaStore entry for another app's address, matched by file name and exact size.
     *
     * Every content provider has to answer those two, and a different video with the same name
     * and the same size to the byte is not a real risk.
     */
    private fun mediaStoreByNameAndSize(context: Context, uri: Uri): Uri? {
        val (name, size) = runCatching {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null,
            )?.use { c ->
                val nameCol = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeCol = c.getColumnIndex(OpenableColumns.SIZE)
                if (!c.moveToFirst() || nameCol < 0 || sizeCol < 0) return@use null
                if (c.isNull(nameCol) || c.isNull(sizeCol)) return@use null
                c.getString(nameCol) to c.getLong(sizeCol)
            }
        }.getOrNull() ?: return null
        if (size <= 0L) return null
        return firstVideo(
            context,
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ?",
            arrayOf(name, size.toString()),
        )
    }

    private fun firstVideo(context: Context, selection: String, args: Array<String>): Uri? {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        return runCatching {
            context.contentResolver.query(
                collection, arrayOf(MediaStore.Video.Media._ID), selection, args, null,
            )?.use { c ->
                if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null
            }
        }.getOrNull()
    }

    /**
     * A file the library does not know about, served by this app's own FileProvider.
     *
     * Only shared storage is published through it (res/xml/shared_paths.xml), and nothing can
     * be read through it without a grant made here, for one file.
     */
    private fun ownProvider(context: Context, path: String): Uri? {
        val file = File(path)
        if (!file.isFile) return null
        return runCatching {
            FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
        }.getOrNull()
    }
}
