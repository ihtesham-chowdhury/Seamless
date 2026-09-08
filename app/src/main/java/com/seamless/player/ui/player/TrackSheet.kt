package com.seamless.player.ui.player

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.annotation.DrawableRes
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.seamless.player.R
import com.seamless.player.databinding.ItemSheetActionBinding
import com.seamless.player.databinding.ItemTrackRowBinding
import com.seamless.player.databinding.SheetTracksBinding

/**
 * The panel behind the CC button: a list of tracks, and a few things to do about them.
 *
 * It knows nothing about subtitles. It is handed rows and actions and it draws them, which is
 * what lets the same panel serve subtitles in the player, subtitles in the feed — where there is
 * no online search and so one fewer action — and audio tracks, which are the same question with
 * a different noun. The alternative was three sheets that differed by a heading.
 *
 * Choosing a track closes the panel. That is the opposite of the view-options sheet, which stays
 * open because sorting is something you arrive at by trying two or three; picking a subtitle is
 * one decision, and staying open afterwards would mean covering the very subtitle you just
 * turned on.
 */
class TrackSheet(
    private val title: String,
    private val rows: List<Row>,
    private val actions: List<Action> = emptyList(),
) {

    /** One selectable track. [detail] says where it came from — "Embedded", "Downloaded". */
    data class Row(
        val label: String,
        val detail: String,
        val selected: Boolean,
        val onClick: () -> Unit,
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

    fun show(context: Context): BottomSheetDialog {
        val binding = SheetTracksBinding.inflate(LayoutInflater.from(context))
        val dialog = FloatingSheet.create(context, binding.root)

        binding.sheetTitle.text = title

        val inflater = LayoutInflater.from(context)
        rows.forEach { row ->
            val item = ItemTrackRowBinding.inflate(inflater, binding.rows, false)
            item.label.text = row.label
            item.detail.text = row.detail
            item.detail.visibility = if (row.detail.isEmpty()) View.GONE else View.VISIBLE
            // The tick keeps its space either way, so the selection can move without the text
            // beside it shifting sideways.
            item.tick.visibility = if (row.selected) View.VISIBLE else View.INVISIBLE
            item.root.setBackgroundResource(
                if (row.selected) R.drawable.sheet_row_selected_bg
                else R.drawable.ripple_sheet_row
            )
            item.root.setOnClickListener {
                dialog.dismiss()
                row.onClick()
            }
            binding.rows.addView(item.root)
        }

        binding.divider.visibility = if (actions.isEmpty()) View.GONE else View.VISIBLE
        actions.forEach { action ->
            val item = ItemSheetActionBinding.inflate(inflater, binding.actions, false)
            item.icon.setImageResource(action.icon)
            item.label.text = action.label
            item.detail.text = action.detail.orEmpty()
            item.detail.visibility = if (action.detail == null) View.GONE else View.VISIBLE
            item.root.setOnClickListener {
                dialog.dismiss()
                action.onClick()
            }
            binding.actions.addView(item.root)
        }

        dialog.show()
        return dialog
    }
}
