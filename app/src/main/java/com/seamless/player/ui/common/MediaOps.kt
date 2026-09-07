package com.seamless.player.ui.common

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.seamless.player.data.Video
import com.seamless.player.util.Log

/**
 * Rename, move and delete against MediaStore.
 *
 * The awkward part is consent. An app may not modify media it did not create without the
 * user agreeing first, and the mechanism changed between the two versions this app
 * supports:
 *
 *  - Android 11+ : ask up front with MediaStore.createWriteRequest / createDeleteRequest,
 *                  which show a system dialog covering the whole batch at once.
 *  - Android 10  : just try it, and catch RecoverableSecurityException, which carries an
 *                  IntentSender that asks for permission on one item at a time.
 *
 * Either way the flow is asynchronous: we hand an IntentSender to the activity, and the
 * work resumes in [onConsentResult] once the user has answered. [pending] is what we
 * intend to do when they say yes.
 */
class MediaOps(
    private val activity: Activity,
    private val consentLauncher: ActivityResultLauncher<IntentSenderRequest>,
) {

    /** What to run once consent comes back positive. */
    private var pending: (() -> Unit)? = null

    /** Reported after any operation so the caller can refresh and report. */
    var onChanged: ((message: String) -> Unit)? = null

    /**
     * Raised when MediaStore refuses a move outright. The caller offers the Storage Access
     * Framework instead, which is not bound by MediaStore's directory rules.
     */
    var onMoveNeedsFolderAccess: ((videos: List<Video>, reason: String) -> Unit)? = null

    private val resolver get() = activity.contentResolver

    // ---- public operations ----

    fun rename(video: Video, newName: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
        }
        withWriteAccess(listOf(video.uri)) {
            applyUpdate(video.uri, values, "renamed to $newName")
        }
    }

    /**
     * [destination] is a MediaStore RELATIVE_PATH such as "Movies/Keep/".
     *
     * Note what this can and cannot do. Granting write access is only half the problem:
     * MediaStore also polices *where* a video may live, and rejects a move into a directory
     * it does not consider valid for the media type with an IllegalArgumentException rather
     * than a SecurityException. That failure has nothing to do with permissions, which is
     * why it survived being granted them — so the real message is reported instead of a
     * generic "failed".
     */
    fun move(videos: List<Video>, destination: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, destination)
        }
        withWriteAccess(videos.map { it.uri }) {
            var moved = 0
            var firstProblem: String? = null

            videos.forEach { video ->
                runCatching { resolver.update(video.uri, values, null, null) }
                    .onSuccess { rows ->
                        if (rows > 0) moved++
                        else if (firstProblem == null) {
                            firstProblem = "MediaStore reported no change"
                        }
                    }
                    .onFailure { error ->
                        Log.e("MediaOps", "move failed for ${video.name}", error)
                        if (firstProblem == null) firstProblem = explain(error, destination)
                    }
            }

            when {
                moved == videos.size -> onChanged?.invoke("Moved ${videos.size}")
                moved > 0 ->
                    onChanged?.invoke("Moved $moved of ${videos.size} — ${firstProblem.orEmpty()}")
                // Nothing moved: MediaStore would not have it. Rather than stopping at an
                // apology, offer the route that does work.
                else -> onMoveNeedsFolderAccess?.invoke(videos, firstProblem ?: "Could not move")
                    ?: onChanged?.invoke(firstProblem ?: "Could not move")
            }
        }
    }

    /** Deletes originals after a successful SAF copy. Uses the same consent flow as delete. */
    fun deleteAfterCopy(videos: List<Video>, movedCount: Int) {
        if (videos.isEmpty()) {
            onChanged?.invoke("Nothing was copied")
            return
        }
        val uris = videos.map { it.uri }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pending = { onChanged?.invoke("Moved $movedCount") }
            launch(MediaStore.createDeleteRequest(resolver, uris).intentSender)
            return
        }
        runCatching { uris.forEach { resolver.delete(it, null, null) } }
            .onSuccess { onChanged?.invoke("Moved $movedCount") }
            .onFailure { error ->
                if (!requestRecoverableConsent(error) { deleteAfterCopy(videos, movedCount) }) {
                    // The copies exist; only the originals survive. Say so plainly.
                    onChanged?.invoke("Copied $movedCount, but could not remove the originals")
                }
            }
    }

    /**
     * Turns MediaStore's exception into something a person can act on. The wording of the
     * underlying message is not stable across versions, so the original is kept as well.
     */
    private fun explain(error: Throwable, destination: String): String {
        val raw = error.message.orEmpty()
        val top = destination.trim('/').substringBefore('/')
        return when {
            error is IllegalArgumentException || raw.contains("not allowed", ignoreCase = true) ->
                "Android does not allow videos in \"$top\". Try a folder under Movies, " +
                    "DCIM or Pictures.\n$raw"

            error is SecurityException ->
                "Permission was refused for that file.\n$raw"

            else -> raw.ifBlank { error::class.java.simpleName }
        }
    }

    fun delete(videos: List<Video>) {
        val uris = videos.map { it.uri }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // The system performs the deletion itself once approved, so there is no
            // follow-up work — only a refresh.
            pending = { onChanged?.invoke("Deleted ${videos.size}") }
            launch(MediaStore.createDeleteRequest(resolver, uris).intentSender)
            return
        }
        // Android 10: attempt directly, and ask only if refused.
        runCatching {
            var deleted = 0
            uris.forEach { deleted += resolver.delete(it, null, null) }
            onChanged?.invoke("Deleted $deleted")
        }.onFailure { error ->
            if (!requestRecoverableConsent(error) { delete(videos) }) {
                Log.e("MediaOps", "delete failed", error)
                onChanged?.invoke("Could not delete")
            }
        }
    }

    // ---- consent plumbing ----

    /**
     * Runs [work], obtaining write access first on Android 11+ where it must be granted up
     * front, and reactively on Android 10 where the failure tells us what to ask for.
     */
    private fun withWriteAccess(uris: List<Uri>, work: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pending = work
            launch(MediaStore.createWriteRequest(resolver, uris).intentSender)
        } else {
            work()
        }
    }

    private fun applyUpdate(uri: Uri, values: ContentValues, success: String) {
        runCatching { resolver.update(uri, values, null, null) }
            .onSuccess { onChanged?.invoke(if (it > 0) success else "Nothing changed") }
            .onFailure { error ->
                if (!requestRecoverableConsent(error) { applyUpdate(uri, values, success) }) {
                    Log.e("MediaOps", "update failed", error)
                    onChanged?.invoke("Could not change that file")
                }
            }
    }

    /**
     * Android 10's path: a RecoverableSecurityException carries the dialog to show. Returns
     * true when consent was requested, false when the failure was something else.
     */
    private fun requestRecoverableConsent(error: Throwable, retry: () -> Unit): Boolean {
        val recoverable = error as? RecoverableSecurityException ?: return false
        pending = retry
        launch(recoverable.userAction.actionIntent.intentSender)
        return true
    }

    private fun launch(sender: IntentSender) {
        consentLauncher.launch(IntentSenderRequest.Builder(sender).build())
    }

    /** Called by the activity from its ActivityResult callback. */
    fun onConsentResult(granted: Boolean) {
        val work = pending
        pending = null
        if (granted) work?.invoke() else onChanged?.invoke("Permission refused")
    }
}
