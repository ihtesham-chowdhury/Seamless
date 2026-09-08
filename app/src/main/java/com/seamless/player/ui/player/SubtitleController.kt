package com.seamless.player.ui.player

import android.net.Uri
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
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
    /** The set of tracks changed: the CC button may need to appear or go away. */
    private val onAvailabilityChanged: () -> Unit,
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

    /** The player has worked out what is in the file. */
    fun onTracksChanged() {
        val player = playerProvider() ?: return
        onAvailabilityChanged()

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

    fun hasTracks(): Boolean {
        val player = playerProvider() ?: return false
        return SubtitleTracks.textOptions(player).isNotEmpty()
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

    fun showSubtitleSheet() {
        val player = playerProvider() ?: return
        val options = SubtitleTracks.textOptions(player)
        val selected = SubtitleTracks.selected(options)

        val rows = mutableListOf(
            TrackSheet.Row(
                label = activity.getString(R.string.subtitle_off),
                detail = "",
                selected = selected == null,
                onClick = {
                    SubtitleTracks.disableText(player)
                    remember(SubtitleStore.OFF)
                    prefs.subtitlesOnByDefault = false
                },
            )
        )
        options.forEachIndexed { index, option ->
            rows += TrackSheet.Row(
                label = SubtitleTracks.label(activity, option, index),
                detail = SubtitleTracks.detail(activity, option),
                selected = option.isSelected,
                onClick = {
                    SubtitleTracks.select(player, option)
                    remember(option.id)
                    prefs.subtitlesOnByDefault = true
                },
            )
        }

        TrackSheet(
            title = activity.getString(R.string.subtitles),
            rows = rows,
            actions = sheetActions(),
        ).show(activity)
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
        val playing = video
        val actions = mutableListOf<TrackSheet.Action>()

        if (playing != null) {
            actions += TrackSheet.Action(
                icon = R.drawable.ic_search,
                label = activity.getString(R.string.subtitle_find),
                detail = search.unavailableReason(),
                onClick = { findOnline(playing) },
            )
            actions += TrackSheet.Action(
                icon = R.drawable.ic_add,
                label = activity.getString(R.string.subtitle_add_file),
                onClick = { pickFile.launch(PICK_MIME_TYPES) },
            )
            if (playing.relativePath.isNotBlank() &&
                SubtitleFolder.folderFor(activity, playing) == null
            ) {
                actions += TrackSheet.Action(
                    icon = R.drawable.ic_folder,
                    label = activity.getString(R.string.subtitle_grant_folder),
                    detail = activity.getString(R.string.subtitle_grant_folder_detail),
                    onClick = {
                        pickFolder.launch(SubtitleFolder.initialFolderUri(playing))
                    },
                )
            }
        }

        actions += TrackSheet.Action(
            icon = R.drawable.ic_text_size,
            label = activity.getString(R.string.subtitle_appearance),
            onClick = { showAppearance() },
        )
        return actions
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
