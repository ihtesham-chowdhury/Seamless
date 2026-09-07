package com.seamless.player.ui.library

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.seamless.player.R
import com.seamless.player.data.Video
import com.seamless.player.util.Thumbnails
import com.seamless.player.util.formatDuration

/**
 * "Use this video's frame as the folder's thumbnail".
 *
 * A grid of every video in the folder, with the current choice ticked. Deliberately a
 * dialog rather than a screen: it is a one-tap decision, and bouncing through a whole
 * activity for it would be heavier than the choice deserves.
 */
object CoverPicker {

    /**
     * @param chosenId the video currently supplying the thumbnail, or 0 if the folder is
     *   still showing its first video.
     * @param onPicked given the chosen video, or null to go back to the default.
     */
    fun show(
        context: Context,
        folderName: String,
        videos: List<Video>,
        chosenId: Long,
        onPicked: (Video?) -> Unit,
    ) {
        val list = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, SPAN)
            clipToPadding = false
            setPadding(PADDING_DP.dp(context), PADDING_DP.dp(context), PADDING_DP.dp(context), 0)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.cover_pick_title, folderName))
            .setView(list)
            // Only worth offering once something has actually been chosen.
            .apply {
                if (chosenId != 0L) {
                    setNeutralButton(R.string.cover_use_default) { _, _ -> onPicked(null) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        list.adapter = Adapter(videos, chosenId) { video ->
            onPicked(video)
            dialog.dismiss()
        }
        dialog.show()
    }

    private class Adapter(
        private val videos: List<Video>,
        private val chosenId: Long,
        private val onClick: (Video) -> Unit,
    ) : RecyclerView.Adapter<Adapter.Holder>() {

        override fun getItemCount() = videos.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_cover_choice, parent, false)
        )

        override fun onBindViewHolder(holder: Holder, position: Int) =
            holder.bind(videos[position])

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val thumb: ImageView = view.findViewById(R.id.thumb)
            private val check: ImageView = view.findViewById(R.id.check)
            private val duration: TextView = view.findViewById(R.id.duration)

            fun bind(video: Video) {
                Thumbnails.load(thumb, video)
                duration.text = formatDuration(video.durationMs)
                check.visibility = if (video.id == chosenId) View.VISIBLE else View.GONE
                itemView.setOnClickListener { onClick(video) }
            }
        }
    }

    private const val SPAN = 3
    private const val PADDING_DP = 8

    private fun Int.dp(context: Context): Int =
        (this * context.resources.displayMetrics.density).toInt()
}
