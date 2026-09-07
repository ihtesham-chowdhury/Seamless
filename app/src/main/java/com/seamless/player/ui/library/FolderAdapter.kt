package com.seamless.player.ui.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.seamless.player.R
import com.seamless.player.data.LibraryView
import com.seamless.player.data.VideoFolder
import com.seamless.player.util.Thumbnails

/**
 * Folder list.
 *
 * Tap opens the folder. Long press starts a selection, which is how folders get hidden —
 * the toolbar swaps to a selection menu while anything is ticked.
 *
 * The list and grid layouts share view ids, so one holder serves both and the layout
 * resource doubles as the view type.
 */
class FolderAdapter(
    private val onOpen: (VideoFolder) -> Unit,
    private val onSelectionChanged: (Set<String>) -> Unit,
    /** Locked folders are marked, and the fragment asks for authentication before opening. */
    private val isLocked: (VideoFolder) -> Boolean = { false },
    /** Pinned folders are marked and sorted to the top by the fragment. */
    private val isPinned: (VideoFolder) -> Boolean = { false },
) : RecyclerView.Adapter<FolderAdapter.Holder>() {

    private var items: List<VideoFolder> = emptyList()
    private val selected = linkedSetOf<String>()

    var viewMode: LibraryView = LibraryView.LIST
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    val selection: Set<String> get() = selected
    val inSelectionMode: Boolean get() = selected.isNotEmpty()

    fun submit(folders: List<VideoFolder>) {
        items = folders
        notifyDataSetChanged()
    }

    fun clearSelection() {
        if (selected.isEmpty()) return
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged(selected)
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int): Int =
        if (viewMode == LibraryView.GRID) R.layout.item_folder_grid else R.layout.item_folder

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(viewType, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {

        private val thumb: ImageView = view.findViewById(R.id.thumb)
        private val name: TextView = view.findViewById(R.id.name)
        private val subtitle: TextView = view.findViewById(R.id.subtitle)
        private val check: ImageView = view.findViewById(R.id.check)
        private val pin: ImageView = view.findViewById(R.id.pin)

        fun bind(folder: VideoFolder) {
            val context = itemView.context
            val locked = isLocked(folder)
            name.text = folder.name

            if (locked) {
                // A locked folder gives nothing away: no preview frame, and no counts that
                // would hint at what is inside. Just the name and a padlock.
                subtitle.setText(R.string.folder_locked)
                thumb.setImageResource(R.drawable.ic_lock_folder)
                thumb.scaleType = ImageView.ScaleType.CENTER_INSIDE
                // Thumbnails.load tags the view with the video id and drops any result whose
                // tag no longer matches, so clearing it cancels an in-flight load for this row.
                thumb.tag = null
            } else {
                subtitle.text = context.getString(
                    R.string.folder_subtitle,
                    folder.videoCount,
                    context.getString(
                        if (folder.isMostlyPortrait) R.string.mostly_portrait
                        else R.string.mostly_landscape
                    ),
                )
                thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                val cover = folder.cover
                if (cover != null) Thumbnails.load(thumb, cover) else thumb.setImageDrawable(null)
            }

            check.visibility = if (folder.path in selected) View.VISIBLE else View.GONE
            pin.visibility = if (isPinned(folder)) View.VISIBLE else View.GONE

            itemView.setOnClickListener {
                // While a selection is active, tapping adds and removes rather than opening.
                if (inSelectionMode) toggle(folder) else onOpen(folder)
            }
            itemView.setOnLongClickListener {
                toggle(folder)
                true
            }
        }

        private fun toggle(folder: VideoFolder) {
            val wasSelectionMode = inSelectionMode
            if (!selected.remove(folder.path)) selected += folder.path

            // Leaving selection mode has to repaint every row, not just this one.
            if (wasSelectionMode && !inSelectionMode) notifyDataSetChanged()
            else notifyItemChanged(bindingAdapterPosition)

            onSelectionChanged(selected)
        }
    }
}
