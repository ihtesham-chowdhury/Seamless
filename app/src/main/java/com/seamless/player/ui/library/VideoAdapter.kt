package com.seamless.player.ui.library

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.seamless.player.R
import com.seamless.player.data.LibraryView
import com.seamless.player.data.Video
import com.seamless.player.util.Thumbnails
import com.seamless.player.util.formatDuration

/**
 * The videos inside a folder, in list or grid form.
 *
 * Tap plays. Long press starts a selection, which is how renaming, moving and deleting are
 * reached — the toolbar swaps to a selection menu while anything is ticked. Same interaction
 * as the folder list, deliberately.
 */
class VideoAdapter(
    private val onClick: (Video) -> Unit,
    private val onSelectionChanged: (Set<Long>) -> Unit = {},
    /** Pinned videos are marked and sorted to the top of their folder. */
    private val isPinned: (Video) -> Boolean = { false },
    /**
     * When true the row names the folder each video came from. Off inside a folder, where
     * every row would say the same thing; on in search results, where it is the one piece of
     * context that tells two similarly named files apart.
     */
    private val showFolder: Boolean = false,
    /**
     * Whether long press starts a selection. Off for search results in the library, which
     * have no selection toolbar behind them — ticking rows there would offer an action that
     * does not exist.
     */
    private val selectable: Boolean = true,
) : RecyclerView.Adapter<VideoAdapter.Holder>() {

    private var items: List<Video> = emptyList()
    private val selected = linkedSetOf<Long>()

    var viewMode: LibraryView = LibraryView.LIST
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    val selection: Set<Long> get() = selected
    val inSelectionMode: Boolean get() = selected.isNotEmpty()

    /** The selected rows, resolved back to their videos. */
    fun selectedVideos(): List<Video> = items.filter { it.id in selected }

    fun submit(videos: List<Video>) {
        items = videos
        // Drop anything that no longer exists, so a delete cannot leave a stale selection.
        val surviving = videos.mapTo(HashSet()) { it.id }
        if (selected.retainAll(surviving)) onSelectionChanged(selected)
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
        if (viewMode == LibraryView.GRID) R.layout.item_video_grid else R.layout.item_video

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(viewType, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {

        private val thumb: ImageView = view.findViewById(R.id.thumb)
        private val name: TextView = view.findViewById(R.id.name)
        private val duration: TextView = view.findViewById(R.id.duration)
        private val meta: TextView = view.findViewById(R.id.meta)
        private val check: ImageView = view.findViewById(R.id.check)
        private val pin: ImageView = view.findViewById(R.id.pin)

        fun bind(video: Video) {
            name.text = video.name
            duration.text = formatDuration(video.durationMs)
            val size = Formatter.formatFileSize(itemView.context, video.sizeBytes)
            meta.text = if (showFolder) {
                itemView.context.getString(
                    R.string.video_meta_in_folder,
                    formatDuration(video.durationMs),
                    size,
                    video.folderName,
                )
            } else {
                itemView.context.getString(
                    R.string.video_meta,
                    formatDuration(video.durationMs),
                    size,
                )
            }
            Thumbnails.load(thumb, video)
            check.visibility = if (video.id in selected) View.VISIBLE else View.GONE
            pin.visibility = if (isPinned(video)) View.VISIBLE else View.GONE

            itemView.setOnClickListener {
                // While a selection is active, tapping adds and removes rather than playing.
                if (inSelectionMode) toggle(video) else onClick(video)
            }
            itemView.setOnLongClickListener {
                if (selectable) toggle(video)
                selectable
            }
        }

        private fun toggle(video: Video) {
            val wasSelectionMode = inSelectionMode
            if (!selected.remove(video.id)) selected += video.id

            // Leaving selection mode has to repaint every row, not just this one.
            if (wasSelectionMode && !inSelectionMode) notifyDataSetChanged()
            else notifyItemChanged(bindingAdapterPosition)

            onSelectionChanged(selected)
        }
    }
}
