package com.seamless.player.ui.player

import android.net.Uri
import android.widget.Toast
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.seamless.player.R
import com.seamless.player.data.Prefs
import com.seamless.player.data.Video
import com.seamless.player.data.subtitle.LocalSubtitles
import com.seamless.player.data.subtitle.ReleaseName
import com.seamless.player.data.subtitle.Sidecar
import com.seamless.player.data.subtitle.SubtitleCandidate
import com.seamless.player.data.subtitle.SubtitleFolder
import com.seamless.player.data.subtitle.SubtitleFormats
import com.seamless.player.data.subtitle.SubtitleLanguages
import com.seamless.player.data.subtitle.SubtitleNames
import com.seamless.player.data.subtitle.SubtitleOrigin
import com.seamless.player.data.subtitle.SubtitleProviderException
import com.seamless.player.data.subtitle.SubtitleSearch
import com.seamless.player.data.subtitle.SubtitleStore
import com.seamless.player.databinding.ActivityPlayerBinding
import com.seamless.player.ui.common.SubtitleStyles
import com.seamless.player.util.Background
import com.seamless.player.util.Log

/**
 * Everything subtitles, for the ordinary player.
 *
 * PlayerActivity gains a button, a menu item and four one-line calls; the rest is here. That
 * split is deliberate — the player is already a large file about playback, and none of what
 * follows is about playback.
 *
 * **Nothing delays the picture.** The player starts the video with a bare media item and the
 * first frame arrives as it always did. Local discovery runs beside it, and only if it actually
 * finds something does the item get rebuilt with the subtitles attached and re-prepared at the
 * position already reached. On the common case — a phone clip with no subtitle anywhere near it —
 * the whole feature costs one directory listing on a background thread and nothing else.
 *
 * **Nothing reaches the network unless asked.** Opening a video runs the local half and stops.
 * [SubtitleSearch] is only ever entered from a tap.
 */
class SubtitleController(
    private val activity: AppCompatActivity,
    private val prefs: Prefs,
    private val binding: ActivityPlayerBinding,
    private val playerProvider: () -> ExoPlayer?,
    /**
     * Something changed that the CC control shows: a track was selected, the set of tracks
     * changed, or the panel opened or closed. The control is always on screen, so this is
     * never about whether to draw it — only about which of its three states it is in.
     */
    private val onStateChanged: () -> Unit,
) {

    private val store = SubtitleStore(activity)
    private val search = SubtitleSearch(activity, prefs, store)

    private var video: Video? = null
    private var sidecars: List<Sidecar> = emptyList()

    /**
     * What the remembered choice was last applied to.
     *
     * Applying it again on every tracks change would fight the user: choosing a track is itself
     * a tracks change, and re-applying would put the remembered one straight back. The signature
     * changes when the video changes or when discovery adds tracks, which are the two moments
     * the choice genuinely needs applying.
     */
    private var appliedSignature: String? = null

    /** Set after a download or a pick: which track to switch to once it appears. */
    private var pendingSelect: Pending? = null

    private var candidates: SubtitleCandidatesSheet? = null

    /** The track panel, while it is up. Held so a deletion can redraw it in place. */
    private var panel: TrackSheet? = null

    /** The last automatic download, kept only so Undo has something to undo. */
    private var undoable: SubtitleStore.Saved? = null

    private data class Pending(val language: String?, val origin: SubtitleOrigin)

    // ---- pickers ----

    /**
     * Registered here rather than in the activity, and registered in the constructor because
     * `registerForActivityResult` refuses to run once the activity has started.
     */
    private val pickFile = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) adoptPickedFile(uri) }

    private val pickFolder = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        SubtitleFolder.remember(activity, uri)
        // The grant is the whole point: re-look now, so the subtitle the user was after appears
        // immediately rather than the next time they open the file.
        video?.let { discover(it) }
    }

    // ---- lifecycle ----

    /**
     * A new video is playing. Applies the caption style and starts looking locally.
     *
     * The style has to be re-applied per video rather than once at startup: `PlayerView` rebuilds
     * its subtitle view's state around each item, and a style set before there was anything to
     * show does not survive.
     */
    fun onVideoStarted(video: Video) {
        this.video = video
        sidecars = emptyList()
        appliedSignature = null
        pendingSelect = null
        undoable = null
        hideNotice()
        applyStyle()
        discover(video)
    }

    /**
     * The player has worked out what is in the file.
     *
     * Also where an open panel is redrawn. Discovery, a download and a deletion all change the
     * track list from a background thread and all three come back through here, so this is the
     * one place that knows the list has settled; redrawing at any of the call sites would draw
     * it as it was a moment before.
     */
    fun onTracksChanged() {
        reconcileTracks()
        onStateChanged()
        panel?.refresh()
    }

    private fun reconcileTracks() {
        val player = playerProvider() ?: return
        val options = SubtitleTracks.textOptions(player)
        if (options.isEmpty()) return

        // Something was just downloaded or picked; switch to it and remember that.
        pendingSelect?.let { wanted ->
            val match = options.firstOrNull {
                SubtitleOrigin.of(it.format.id) == wanted.origin &&
                    matchesLanguage(it.format.language, wanted.language)
            } ?: options.lastOrNull { SubtitleOrigin.of(it.format.id) == wanted.origin }
            if (match != null) {
                pendingSelect = null
                appliedSignature = signatureOf(options)
                SubtitleTracks.select(player, match)
                remember(match.id)
                return
            }
        }

        val signature = signatureOf(options)
        if (signature == appliedSignature) return
        appliedSignature = signature
        applyRememberedChoice(player, options)
    }

    /** Whether a subtitle is on screen, which is the only thing the CC control shows. */
    fun hasActiveTrack(): Boolean {
        val player = playerProvider() ?: return false
        return SubtitleTracks.selected(SubtitleTracks.textOptions(player)) != null
    }

    fun hasAudioChoice(): Boolean {
        val player = playerProvider() ?: return false
        return SubtitleTracks.audioOptions(player).size > 1
    }

    /**
     * How far the caption has to get out of the way of something.
     *
     * The requirement is that a subtitle is never behind a control, and the honest way to meet it
     * is to move the subtitle rather than to hope the two do not overlap. The two lifts are
     * different sizes because the two things being avoided are: a bar of controls, and a panel
     * covering half the screen.
     */
    enum class Lift { NONE, CONTROLS, PANEL }

    fun applyStyle(lift: Lift = Lift.NONE) {
        val view = binding.playerView.subtitleView ?: return
        val padding = when (lift) {
            Lift.NONE -> null
            // Never *lower* the text: someone who has already pushed it up past the controls
            // asked for that, and undoing it here would be overruling them.
            Lift.CONTROLS -> maxOf(prefs.subtitleBottomPadding, CONTROLS_PADDING)
            Lift.PANEL -> maxOf(prefs.subtitleBottomPadding, PANEL_PADDING)
        }
        SubtitleStyles.apply(view, prefs, padding)
    }

    fun release() {
        candidates?.dismiss()
        candidates = null
        panel?.dismiss()
        panel = null
    }

    // ---- local discovery ----

    private fun discover(video: Video) {
        Background.run(
            work = { LocalSubtitles.discover(activity, store, video) },
            then = { found ->
                // The player may have moved on while we were looking.
                if (this.video?.id != video.id) return@run
                if (found.isEmpty() && sidecars.isEmpty()) return@run
                if (found.map { it.uri } == sidecars.map { it.uri }) return@run
                sidecars = found
                Log.d(TAG, "${found.size} local subtitle file(s) for ${video.name}")
                reattach(video)
            },
        )
    }

    /**
     * Rebuilds the media item with the subtitles attached, keeping the position.
     *
     * This is the one place playback is interrupted, and it happens only when there was something
     * to attach. A local file re-prepares in a frame or two; the position and the playing state
     * carry over, so what the user sees is the picture continuing and a CC button appearing.
     */
    private fun reattach(video: Video) {
        val player = playerProvider() ?: return
        appliedSignature = null
        player.setMediaItem(mediaItemFor(video), /* resetPosition = */ false)
        player.prepare()
    }

    fun mediaItemFor(video: Video): MediaItem = MediaItem.Builder()
        .setUri(video.uri)
        .setSubtitleConfigurations(sidecars.map { it.toConfiguration() })
        .build()

    // ---- the panel ----

    /**
     * The panel, which is rebuilt from the player every time it is drawn.
     *
     * Passing a function rather than a list is what lets a deletion redraw it without closing
     * it: the rows are always whatever the player says they are now, and there is no second
     * copy of the track list to keep in step.
     */
    fun showSubtitleSheet() {
        if (playerProvider() == null) return
        val sheet = TrackSheet(activity.getString(R.string.subtitles)) { panelContent() }
        panel = sheet
        sheet.show(activity, onDismiss = {
            panel = null
            onStateChanged()
        })
        onStateChanged()
    }

    private fun panelContent(): TrackSheet.Content {
        val player = playerProvider() ?: return TrackSheet.Content(emptyList())
        val options = SubtitleTracks.textOptions(player)

        val rows = if (options.isEmpty()) {
            // No "Off" row for a video with nothing to switch off. A list of one choice that
            // is already made is not a list; the empty state says more in the same space.
            emptyList()
        } else {
            buildList {
                add(
                    TrackSheet.Row(
                        label = activity.getString(R.string.subtitle_off),
                        detail = "",
                        selected = SubtitleTracks.selected(options) == null,
                        onClick = {
                            SubtitleTracks.disableText(player)
                            remember(SubtitleStore.OFF)
                            prefs.subtitlesOnByDefault = false
                            onStateChanged()
                        },
                    )
                )
                options.forEachIndexed { index, option ->
                    add(
                        TrackSheet.Row(
                            label = SubtitleTracks.label(activity, option, index),
                            detail = SubtitleTracks.detail(activity, option),
                            selected = option.isSelected,
                            onClick = {
                                SubtitleTracks.select(player, option)
                                remember(option.id)
                                prefs.subtitlesOnByDefault = true
                                onStateChanged()
                            },
                            onRemove = removalOf(option),
                        )
                    )
                }
            }
        }

        return TrackSheet.Content(
            rows = rows,
            actions = sheetActions(),
            footer = listOf(
                TrackSheet.Action(
                    icon = R.drawable.ic_text_size,
                    label = activity.getString(R.string.subtitle_appearance),
                    onClick = { showAppearance() },
                )
            ),
            emptyText = activity.getString(R.string.subtitle_none_yet),
        )
    }

    fun showAudioSheet() {
        val player = playerProvider() ?: return
        val options = SubtitleTracks.audioOptions(player)
        val rows = options.mapIndexed { index, option ->
            TrackSheet.Row(
                label = SubtitleTracks.label(activity, option, index),
                detail = SubtitleTracks.audioDetail(option),
                selected = option.isSelected,
                onClick = { SubtitleTracks.select(player, option) },
            )
        }
        TrackSheet(activity.getString(R.string.audio_tracks), rows).show(activity)
    }

    /**
     * What can be done from the panel, in the order it makes sense to try them.
     *
     * The folder row only appears when it would achieve something — when the video has a folder
     * and no grant covers it yet — because on Android 12 and earlier, and once a grant exists, it
     * is an offer to solve a problem the user does not have.
     */
    private fun sheetActions(): List<TrackSheet.Action> {
        val playing = video ?: return emptyList()
        val actions = mutableListOf<TrackSheet.Action>()

        actions += TrackSheet.Action(
            icon = R.drawable.ic_search,
            label = activity.getString(R.string.subtitle_find),
            detail = search.unavailableReason(),
            onClick = { findOnline(playing) },
        )
        // Second, and named for what it does rather than for the plus sign. Subtitles beside
        // the video are found without being asked for, so this is the fallback for the file
        // that is somewhere else — not an equal partner to Find subtitles.
        actions += TrackSheet.Action(
            icon = R.drawable.ic_folder,
            label = activity.getString(R.string.subtitle_choose_file),
            onClick = { pickFile.launch(PICK_MIME_TYPES) },
        )
        if (playing.relativePath.isNotBlank() &&
            SubtitleFolder.folderFor(activity, playing) == null
        ) {
            actions += TrackSheet.Action(
                icon = R.drawable.ic_lock_folder,
                label = activity.getString(R.string.subtitle_grant_folder),
                detail = activity.getString(R.string.subtitle_grant_folder_detail),
                onClick = {
                    pickFolder.launch(SubtitleFolder.initialFolderUri(playing))
                },
            )
        }
        return actions
    }

    /**
     * How to remove [option], or null when there is nothing to remove.
     *
     * Anything that is a file: one this app downloaded or was handed through the picker, and
     * one sitting beside the video in a folder the app has been given. Not a track inside the
     * container, which has no file of its own and cannot be taken out of the video.
     *
     * The two are not treated the same. A downloaded subtitle is the app's own and goes on one
     * tap — it can be fetched again in a second. A `.srt` beside the video is the user's file,
     * visible to every other player they own, and possibly the only copy; that one asks first.
     * Refusing to delete it at all was the previous answer and it was the wrong one: the button
     * then never appeared for anybody whose subtitles live in their own folders, which is most
     * people with a subtitle problem.
     */
    private fun removalOf(option: SubtitleTracks.Option): (() -> Unit)? {
        val origin = SubtitleOrigin.of(option.format.id)
        if (origin == SubtitleOrigin.EMBEDDED) return null
        val sidecar = sidecars.firstOrNull { it.id == option.format.id } ?: return null
        return when (origin) {
            SubtitleOrigin.SAVED -> ({ remove(sidecar, option) })
            SubtitleOrigin.BESIDE -> ({ confirmThenRemove(sidecar, option) })
            SubtitleOrigin.EMBEDDED -> null
        }
    }

    /**
     * Asks before deleting a file the user put there.
     *
     * Named, because "delete this subtitle" is a different question from "delete
     * Movie.en.srt" when there are three of them in the folder and two are for other episodes.
     */
    private fun confirmThenRemove(sidecar: Sidecar, option: SubtitleTracks.Option) {
        val name = sidecar.fileName ?: sidecar.label
        AlertDialog.Builder(activity)
            .setTitle(R.string.subtitle_remove_file_title)
            .setMessage(activity.getString(R.string.subtitle_remove_file_message, name))
            .setPositiveButton(R.string.subtitle_remove_file_confirm) { _, _ ->
                remove(sidecar, option)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Deletes one subtitle file and puts the player back on its feet.
     *
     * The remembered choice is cleared when the track being deleted is the one playing, so the
     * next discovery does not spend its time looking for a file that is gone; what it settles
     * on instead is the ordinary rule — the preferred language if something else carries it,
     * off if nothing does. Deleting a subtitle you were not watching changes nothing you can
     * see except the row going away, which is the point.
     */
    private fun remove(sidecar: Sidecar, option: SubtitleTracks.Option) {
        val playing = video ?: return
        val wasPlaying = option.isSelected
        // Whatever Undo was holding, it is either this file or older than it; either way the
        // offer has expired, and an Undo that deletes a file which is already gone is a button
        // that does nothing.
        undoable = null
        hideNotice()
        // Forget the choice before the file goes, so the next pass through
        // applyRememberedChoice looks for a track rather than for a file that is not there.
        if (wasPlaying) remember(null)

        Background.run(
            work = {
                when (sidecar.origin) {
                    SubtitleOrigin.SAVED -> store.deleteIfOwned(sidecar.uri)
                    SubtitleOrigin.BESIDE -> SubtitleFolder.deleteCompanion(
                        activity,
                        playing.name,
                        sidecar.uri,
                        sidecar.fileName.orEmpty(),
                    )
                    SubtitleOrigin.EMBEDDED -> false
                }
            },
            then = { deleted ->
                if (!deleted) {
                    Log.w(TAG, "could not delete ${sidecar.uri}")
                    Toast.makeText(activity, R.string.subtitle_remove_failed, Toast.LENGTH_SHORT)
                        .show()
                    return@run
                }
                if (wasPlaying) playerProvider()?.let { SubtitleTracks.disableText(it) }
                // Discovery rebuilds the media item; the panel redraws from onTracksChanged
                // once the player has actually caught up.
                discover(playing)
                onStateChanged()
            },
        )
    }

    private fun showAppearance() {
        // Raised while the panel is open, so the caption is visible above it and every change can
        // be seen as it is made rather than guessed at from a label. Lowered again on dismissal.
        applyStyle(Lift.PANEL)
        SubtitleAppearanceSheet(prefs) { applyStyle(Lift.PANEL) }
            .show(activity, onDismiss = { applyStyle() })
    }

    // ---- online ----

    private fun findOnline(video: Video) {
        val sheet = SubtitleCandidatesSheet(
            activity,
            activity.getString(R.string.subtitle_find),
            ReleaseName.parse(video.name).let { release ->
                if (release.year != null) "${release.title} (${release.year})" else release.title
            },
        )
        candidates = sheet
        sheet.show()

        Background.run(
            work = { search.find(video) },
            then = { outcome ->
                if (!sheet.isShowing) return@run
                when (outcome) {
                    is SubtitleSearch.Outcome.Applied -> {
                        sheet.dismiss()
                        undoable = outcome.saved
                        pendingSelect = Pending(outcome.candidate.language, SubtitleOrigin.SAVED)
                        prefs.subtitlesOnByDefault = true
                        showFoundNotice(outcome.candidate)
                        discover(video)
                    }

                    is SubtitleSearch.Outcome.Choices ->
                        sheet.showCandidates(outcome.candidates) { candidate, done ->
                            fetch(video, candidate, done)
                        }

                    SubtitleSearch.Outcome.NoMatch ->
                        sheet.showMessage(activity.getString(R.string.subtitle_no_match))

                    is SubtitleSearch.Outcome.Unavailable ->
                        sheet.showMessage(
                            activity.getString(R.string.subtitle_unavailable, outcome.reason),
                        )

                    is SubtitleSearch.Outcome.Failed -> sheet.showMessage(outcome.message)
                }
            },
        )
    }

    /**
     * Downloads one chosen candidate.
     *
     * The background half hands back the problem rather than the result — null means it worked —
     * because nothing here needs the file itself: the next discovery finds it on disk, which is
     * also the path every later playback takes. One route in, tested every time.
     */
    private fun fetch(video: Video, candidate: SubtitleCandidate, done: (String?) -> Unit) {
        Background.online(
            work = {
                try {
                    search.fetch(video, candidate)
                    null
                } catch (error: SubtitleProviderException) {
                    error.message ?: activity.getString(R.string.subtitle_download_failed)
                }
            },
            onFailure = { error ->
                // The row's spinner is turning and only `done` stops it, so this branch is not
                // optional: without it a failed download leaves a row spinning for ever.
                done("${activity.getString(R.string.subtitle_download_failed)} — ${error.message}")
            },
            then = { problem ->
                done(problem)
                if (problem != null) return@online
                pendingSelect = Pending(candidate.language, SubtitleOrigin.SAVED)
                prefs.subtitlesOnByDefault = true
                discover(video)
            },
        )
    }

    // ---- the transient notice ----

    /**
     * The whole point of high confidence: no dialog, no decision, a line of text that goes away.
     *
     * Undo is there because "no confirmation" is only defensible if the result can be taken back
     * in one tap.
     */
    private fun showFoundNotice(candidate: SubtitleCandidate) {
        val language = SubtitleLanguages.displayName(candidate.language)
            .ifEmpty { activity.getString(R.string.subtitle_unknown_language) }
        binding.noticeTitle.text = activity.getString(R.string.subtitle_found, language)
        binding.noticeDetail.text = listOfNotNull(
            candidate.featureTitle,
            activity.getString(R.string.subtitle_match_excellent),
        ).joinToString(" · ")
        binding.noticeAction.setOnClickListener { undoDownload() }
        binding.subtitleNotice.visibility = View.VISIBLE
        binding.subtitleNotice.removeCallbacks(hideNoticeRunnable)
        binding.subtitleNotice.postDelayed(hideNoticeRunnable, NOTICE_LINGER_MS)
    }

    private val hideNoticeRunnable = Runnable { hideNotice() }

    private fun hideNotice() {
        binding.subtitleNotice.removeCallbacks(hideNoticeRunnable)
        binding.subtitleNotice.visibility = View.GONE
    }

    private fun undoDownload() {
        val saved = undoable ?: return
        val playing = video ?: return
        undoable = null
        hideNotice()
        remember(SubtitleStore.OFF)
        prefs.subtitlesOnByDefault = false
        Background.run(
            work = { store.delete(saved) },
            then = { discover(playing) },
        )
    }

    // ---- picked files ----

    /**
     * A subtitle chosen through the document picker is copied into the store rather than
     * referenced where it lies.
     *
     * A picked URI's permission lasts as long as this activity, so referencing it would give a
     * subtitle that worked once and was gone tomorrow. Copying costs a few kilobytes and makes it
     * permanent, which is what someone who went looking for the file meant.
     */
    private fun adoptPickedFile(uri: Uri) {
        val playing = video ?: return
        Background.run(
            work = {
                val name = displayNameOf(uri) ?: "subtitle.srt"
                val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@run null
                val language = languageIn(name)
                store.save(
                    store.keyFor(playing),
                    language,
                    SubtitleFormats.extensionOrDefault(name),
                    bytes,
                )
            },
            then = { saved ->
                if (saved == null) {
                    Log.w(TAG, "could not read the picked subtitle")
                    return@run
                }
                pendingSelect = Pending(saved.language, SubtitleOrigin.SAVED)
                prefs.subtitlesOnByDefault = true
                discover(playing)
            },
        )
    }

    private fun displayNameOf(uri: Uri): String? =
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    /**
     * A language token anywhere in a picked file's name.
     *
     * Read from the end, because that is where it goes — `Movie.en.srt`, not `English Movie.srt` —
     * and a title containing a language word would otherwise win over the actual tag.
     */
    private fun languageIn(fileName: String): String? = SubtitleNames.baseName(fileName)
        .split('.', '_', '-', ' ')
        .asReversed()
        .firstNotNullOfOrNull { SubtitleLanguages.normalise(it.takeIf { w -> w.length <= 12 }) }

    // ---- remembering ----

    private fun remember(trackId: String?) {
        val playing = video ?: return
        store.remember(store.keyFor(playing), trackId)
    }

    /**
     * What to select for a video the user has not answered for yet.
     *
     * Off, unless they left subtitles on somewhere else. That last part is the difference between
     * a player that remembers and one that asks every episode: turn English on for episode one and
     * the rest of the series comes up with English on, in whatever track carries it.
     */
    private fun applyRememberedChoice(player: Player, options: List<SubtitleTracks.Option>) {
        val playing = video ?: return
        when (val remembered = store.rememberedChoice(store.keyFor(playing))) {
            SubtitleStore.OFF -> SubtitleTracks.disableText(player)

            null -> {
                val wanted = if (prefs.subtitlesOnByDefault) {
                    SubtitleTracks.preferring(options, prefs.subtitleLanguage) ?: options.first()
                } else {
                    null
                }
                if (wanted != null) SubtitleTracks.select(player, wanted)
                else SubtitleTracks.disableText(player)
            }

            else -> {
                val exact = SubtitleTracks.findById(options, remembered)
                    ?: SubtitleTracks.preferring(options, prefs.subtitleLanguage)
                if (exact != null) SubtitleTracks.select(player, exact)
                else SubtitleTracks.disableText(player)
            }
        }
    }

    private fun signatureOf(options: List<SubtitleTracks.Option>): String =
        (video?.id ?: 0L).toString() + "/" + options.joinToString(",") { it.id }

    private fun matchesLanguage(actual: String?, wanted: String?): Boolean {
        if (wanted == null) return true
        val a = SubtitleLanguages.normalise(actual)?.substringBefore('-')
        val b = SubtitleLanguages.normalise(wanted)?.substringBefore('-')
        return a != null && a == b
    }

    private companion object {
        const val TAG = "SubtitleController"

        /** Clear of the transport bar, which is about a fifth of the height of the screen. */
        const val CONTROLS_PADDING = 0.20f

        /** Clear of a floating panel, which covers rather more. */
        const val PANEL_PADDING = 0.42f

        /** Long enough to read two lines and reach Undo, short enough not to be chrome. */
        const val NOTICE_LINGER_MS = 6_000L

        /**
         * What the document picker will offer.
         *
         * Subtitle files have no agreed MIME type and providers disagree wildly — `text/plain`,
         * `application/octet-stream`, `application/x-subrip`, nothing at all — so filtering
         * strictly would hide the very file the user is reaching for. The type list is wide and
         * the extension check happens after the pick.
         */
        val PICK_MIME_TYPES = arrayOf("text/*", "application/octet-stream", "application/x-subrip")
    }
}
