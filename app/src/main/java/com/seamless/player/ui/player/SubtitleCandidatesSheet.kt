package com.seamless.player.ui.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.core.content.getSystemService
import com.seamless.player.R
import com.seamless.player.data.subtitle.Confidence
import com.seamless.player.data.subtitle.SubtitleCandidate
import com.seamless.player.data.subtitle.SubtitleLanguages
import com.seamless.player.databinding.ItemSubtitleCandidateBinding
import com.seamless.player.databinding.SheetSubtitleCandidatesBinding

/**
 * The panel that shows what a search found — including while it is still looking.
 *
 * One panel for all four outcomes, opened the instant the search starts. The alternative is a
 * spinner somewhere in the player followed by a panel appearing, which is two things happening
 * where one would do, and it leaves the user with nothing to look at and nothing to cancel.
 *
 * The heading carries what was actually searched for. That matters more than it sounds: when the
 * results are wrong it is almost always because the file name parsed into the wrong title, and
 * seeing "The Matrix 1999" at the top of the panel explains a bad list immediately.
 */
class SubtitleCandidatesSheet(
    private val context: Context,
    heading: String,
    searchedFor: String,
) {

    private val binding = SheetSubtitleCandidatesBinding.inflate(LayoutInflater.from(context))
    private val dialog = FloatingSheet.create(context, binding.root)
    private val inflater = LayoutInflater.from(context)

    init {
        binding.heading.text = heading
        binding.subheading.text = searchedFor
        binding.subheading.visibility = if (searchedFor.isBlank()) View.GONE else View.VISIBLE
    }

    /**
     * Says so if the search has been going long enough to look stuck.
     *
     * The bug this replaces was a panel that said "Searching…" until the app was closed, because
     * the failure that ended the search was never delivered. That cannot happen now — every path
     * comes back with an outcome — but a spinner with no upper bound is a bad shape regardless,
     * and a slow network can still leave one up for half a minute. This puts a floor under how
     * long silence can last.
     */
    private val watchdog = Runnable {
        if (binding.searching.visibility != View.VISIBLE) return@Runnable
        binding.subheading.text = context.getString(R.string.subtitle_still_searching)
        binding.subheading.visibility = View.VISIBLE
    }

    /** Opens the panel in its searching state. */
    fun show() {
        dialog.show()
        binding.root.postDelayed(watchdog, SLOW_MS)
    }

    fun dismiss() {
        binding.root.removeCallbacks(watchdog)
        if (dialog.isShowing) dialog.dismiss()
    }

    val isShowing: Boolean get() = dialog.isShowing

    /**
     * Replaces the searching state with the results.
     *
     * [onPick] is given a callback of its own to report back with: null when the download
     * succeeded and the panel should close, or a message when it did not and the panel should
     * say so. Downloading takes a second or two on a phone connection, and a panel that closed
     * optimistically would have nowhere to put the failure.
     */
    fun showCandidates(
        candidates: List<SubtitleCandidate>,
        note: String? = null,
        onPick: (SubtitleCandidate, (String?) -> Unit) -> Unit,
    ) {
        binding.root.removeCallbacks(watchdog)
        binding.searching.visibility = View.GONE
        binding.copyDetails.visibility = View.GONE
        binding.listScroll.visibility = View.VISIBLE
        binding.list.removeAllViews()

        // A list that appears after something failed needs to say so, or it reads as the search
        // having simply decided not to choose.
        binding.message.text = note.orEmpty()
        binding.message.visibility = if (note == null) View.GONE else View.VISIBLE

        candidates.forEach { candidate ->
            val item = ItemSubtitleCandidateBinding.inflate(inflater, binding.list, false)
            val context = item.root.context

            item.language.text = SubtitleLanguages.displayName(candidate.language)
                .ifEmpty { context.getString(R.string.subtitle_unknown_language) }
            item.quality.text = qualityLine(context, candidate)
            item.score.text = context.getString(R.string.subtitle_score, candidate.score)

            // The release string is the ugliest and most useful line on the panel, so it is one
            // tap away rather than on show. Selectable, because someone comparing it with their
            // own file name will want to copy it.
            val release = candidate.release ?: candidate.fileName
            item.release.text = release
            item.info.setOnClickListener {
                item.release.visibility =
                    if (item.release.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }

            item.root.setOnClickListener {
                setRowBusy(item, true)
                onPick(candidate) { problem ->
                    setRowBusy(item, false)
                    if (problem == null) dismiss() else showMessage(problem)
                }
            }
            binding.list.addView(item.root)
        }
    }

    /**
     * No match, not configured, or a failure. The panel stays open so it can be read.
     *
     * [detail] is the exchange behind it, offered under a Copy button rather than printed: it is
     * the most useful thing on the panel for exactly one person and noise for everyone else.
     */
    fun showMessage(text: String, detail: String? = null) {
        binding.root.removeCallbacks(watchdog)
        binding.searching.visibility = View.GONE
        binding.listScroll.visibility = View.GONE
        binding.message.visibility = View.VISIBLE
        binding.message.text = text

        binding.copyDetails.visibility = if (detail == null) View.GONE else View.VISIBLE
        if (detail != null) {
            binding.copyDetails.setOnClickListener {
                context.getSystemService<ClipboardManager>()?.setPrimaryClip(
                    ClipData.newPlainText(context.getString(R.string.subtitles), detail),
                )
                // Android 13 and newer show their own copy confirmation; saying it twice would
                // cover the one the system already put on screen.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(context, R.string.info_copied, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** A row being fetched: its spinner replaces the information button, and taps stop. */
    private fun setRowBusy(item: ItemSubtitleCandidateBinding, busy: Boolean) {
        item.busy.visibility = if (busy) View.VISIBLE else View.GONE
        item.info.visibility = if (busy) View.GONE else View.VISIBLE
        item.root.isEnabled = !busy
    }

    /**
     * "WEB-DL · Excellent match", or as much of that as is known.
     *
     * Words rather than only a percentage. A number on its own does not say what to do with it,
     * and "Excellent" here means something precise — the file hashes matched — rather than being
     * a flourish on 98%.
     */
    private fun qualityLine(context: Context, candidate: SubtitleCandidate): String {
        val quality = context.getString(
            when (candidate.confidence) {
                Confidence.EXCELLENT -> R.string.subtitle_match_excellent
                Confidence.GOOD -> R.string.subtitle_match_good
                Confidence.WEAK -> R.string.subtitle_match_weak
            }
        )
        val parts = buildList {
            candidate.release?.let { release ->
                // The first word of a release string is nearly always the source — BluRay,
                // WEB-DL — and the rest of it is the part being kept behind the ⓘ.
                release.split(' ', '.').firstOrNull { it.length in 3..8 }?.let { add(it) }
            }
            add(quality)
            if (candidate.hearingImpaired) add(context.getString(R.string.subtitle_sdh))
        }
        return parts.joinToString(" · ")
    }

    private companion object {
        /** Long enough that an ordinary search never reaches it. */
        const val SLOW_MS = 12_000L
    }
}
