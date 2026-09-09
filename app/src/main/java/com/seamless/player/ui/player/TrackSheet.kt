package com.seamless.player.ui.player

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import com.seamless.player.R
import com.seamless.player.databinding.ItemSheetActionBinding
import com.seamless.player.databinding.ItemTrackRowBinding
import com.seamless.player.databinding.SheetTracksBinding

/**
 * The panel behind the CC button: what is playing, what else there is, and what to do about it.
 *
 * It knows nothing about subtitles. It is handed rows and actions and it draws them, which is
 * what lets the same panel serve subtitles in the player, subtitles in the feed — where there is
 * no online search and so one fewer action — and audio tracks, which are the same question with
 * a different noun. The alternative was three sheets that differed by a heading.
 *
 * Three things are laid out in a deliberate order, and the order is the design:
 *
 * 1. **The tracks.** What am I watching. This is what the panel is for and it is at the top.
 * 2. **The actions.** Find one, choose a file. What to do when the answer to 1 is "none of
 *    these".
 * 3. **The footer.** Appearance. Real, wanted, and nobody's reason for opening this panel — so
 *    it is below a second line where it cannot compete with the list.
 *
 * Choosing a track closes the panel; deleting one does not. That is not an inconsistency. A
 * choice is one decision and staying open afterwards would mean covering the very subtitle just
 * turned on. Deleting is tidying, which comes in twos and threes, and closing the panel after
 * each one would make removing three files a matter of opening the panel three times.
 *
 * [content] is a function rather than a value for that second case: after a deletion the panel
 * asks for the rows again and redraws itself, so the caller never has to hold a reference to
 * anything on screen.
 */
class TrackSheet(
    private val title: String,
    private val content: () -> Content,
) {

    constructor(title: String, rows: List<Row>) : this(title, { Content(rows) })

    /** Everything the panel shows, as of now. Re-read after anything that changes it. */
    data class Content(
        val rows: List<Row>,
        val actions: List<Action> = emptyList(),
        val footer: List<Action> = emptyList(),
        /**
         * What to say when [rows] is empty, with the app's mark above it. Null means an empty
         * list simply draws nothing, which is right for a panel that cannot be empty.
         */
        val emptyText: String? = null,
    )

    /**
     * One selectable track. [detail] says where it came from — "In this video", "Downloaded".
     *
     * [onRemove] is set only on something the app can actually delete, and its presence is what
     * draws the button. A track inside the video has no file to remove; a file in the user's own
     * folder is theirs and not this panel's to throw away.
     */
    data class Row(
        val label: String,
        val detail: String,
        val selected: Boolean,
        val onClick: () -> Unit,
        val onRemove: (() -> Unit)? = null,
    )

    /**
     * Something to do rather than something to choose.
     *
     * Every action closes the panel before it runs. Each of them opens a panel of its own or a
     * system picker, and two floating panels at once over a video is one too many.
     */
    data class Action(
        @DrawableRes val icon: Int,
        val label: String,
        val detail: String? = null,
        val onClick: () -> Unit,
    )

    private var binding: SheetTracksBinding? = null
    private var dialog: Dialog? = null

    val isShowing: Boolean get() = dialog?.isShowing == true

    fun dismiss() {
        dialog?.takeIf { it.isShowing }?.dismiss()
    }

    fun show(context: Context, onDismiss: () -> Unit = {}): Dialog {
        val binding = SheetTracksBinding.inflate(LayoutInflater.from(context))
        val dialog = FloatingSheet.create(context, binding.root)
        this.binding = binding
        this.dialog = dialog

        binding.sheetTitle.text = title
        binding.sheetClose.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            this.binding = null
            this.dialog = null
            onDismiss()
        }

        draw(binding, dialog)
        dialog.show()
        return dialog
    }

    /** Re-reads [content] and redraws, for a panel that is already on screen. */
    fun refresh() {
        val binding = binding ?: return
        val dialog = dialog ?: return
        draw(binding, dialog)
    }

    private fun draw(binding: SheetTracksBinding, dialog: Dialog) {
        val content = content()
        val context = binding.root.context
        val inflater = LayoutInflater.from(context)

        val empty = content.rows.isEmpty() && content.emptyText != null
        binding.empty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.emptyText.text = content.emptyText.orEmpty()
        binding.rowsScroll.visibility = if (content.rows.isEmpty()) View.GONE else View.VISIBLE

        binding.rows.removeAllViews()
        content.rows.forEach { row ->
            val item = ItemTrackRowBinding.inflate(inflater, binding.rows, false)
            item.label.text = row.label
            item.detail.text = row.detail
            item.detail.visibility = if (row.detail.isEmpty()) View.GONE else View.VISIBLE
            // A filled radio, not a tick and not a highlighted row. The two radio drawables
            // share a ring, so the selection moves without anything shifting sideways, and an
            // unselected row still shows that it could be chosen -- which an empty space does
            // not. Painting the selected row's background as well would be saying it twice.
            item.tick.setImageResource(
                if (row.selected) R.drawable.ic_radio_on else R.drawable.ic_radio_off
            )
            item.root.setOnClickListener {
                dialog.dismiss()
                row.onClick()
            }

            val remove = row.onRemove
            item.remove.visibility = if (remove == null) View.GONE else View.VISIBLE
            item.remove.setOnClickListener {
                // Not dismissing: see the note at the top of the class.
                remove?.invoke()
            }
            binding.rows.addView(item.root)
        }

        fill(binding.actions, content.actions, inflater, dialog)
        fill(binding.footer, content.footer, inflater, dialog)

        // Neither line has anything to separate when the group under it is empty, and a panel
        // that ends on a rule looks like it was cut off.
        binding.divider.visibility =
            if (content.actions.isEmpty()) View.GONE else View.VISIBLE
        binding.footerDivider.visibility =
            if (content.footer.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun fill(
        into: ViewGroup,
        actions: List<Action>,
        inflater: LayoutInflater,
        dialog: Dialog,
    ) {
        into.removeAllViews()
        into.visibility = if (actions.isEmpty()) View.GONE else View.VISIBLE
        actions.forEach { action ->
            val item = ItemSheetActionBinding.inflate(inflater, into, false)
            item.icon.setImageResource(action.icon)
            item.label.text = action.label
            item.detail.text = action.detail.orEmpty()
            item.detail.visibility = if (action.detail == null) View.GONE else View.VISIBLE
            item.root.setOnClickListener {
                dialog.dismiss()
                action.onClick()
            }
            into.addView(item.root)
        }
    }
}
