package com.seamless.player.ui.shorts

import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.seamless.player.R
import com.seamless.player.data.ShortsLayout
import com.seamless.player.data.Video
import com.seamless.player.util.Thumbnails
import com.seamless.player.util.formatDuration

/**
 * The wall of clips on the shorts tab, in masonry or in grid.
 *
 * One adapter for both, because they differ in exactly one thing — how tall a tile is — and
 * everything else about them, the binding, the thumbnail request, the recycling rules, is
 * identical. Two adapters would have been the same file twice.
 *
 * Deliberately not the library's grid adapter either. That one is built around 16:9 tiles
 * with a name in bold and a metadata line under it, which is right for browsing files and
 * wrong for choosing between forty near-identical vertical clips: there the frame is the
 * only thing that tells them apart, so the frame gets all of the space.
 *
 * **Why the height is computed here.** A masonry column whose tiles size themselves once
 * their images load is a column that jumps while you scroll it, and a
 * StaggeredGridLayoutManager will re-balance the columns underneath you when it happens.
 * MediaStore already told us every clip's width and height, so the tile is measured from
 * metadata before the thumbnail is even requested, and the picture arrives into a space that
 * was already the right shape.
 */
class ShortsTileAdapter(
    private val isFavourite: (Video) -> Boolean,
    private val onClick: (Video) -> Unit,
) : RecyclerView.Adapter<ShortsTileAdapter.Holder>() {

    private var items: List<Video> = emptyList()

    /** Set before the layout manager is swapped; the next bind uses it. */
    var layout: ShortsLayout = ShortsLayout.MASONRY

    /**
     * Column width in pixels, measured from the RecyclerView rather than assumed.
     *
     * Zero until the list has been laid out once, which is why [Holder.bind] falls back to
     * wrapping: a tile with no width to work from cannot be given a height either.
     */
    var columnWidth: Int = 0

    fun submit(videos: List<Video>) {
        items = videos
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_short_tile, parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {

        private val card: View = view.findViewById(R.id.card)
        private val thumb: ImageView = view.findViewById(R.id.thumb)
        private val duration: TextView = view.findViewById(R.id.duration)
        private val favourite: ImageView = view.findViewById(R.id.favourite)

        fun bind(video: Video) {
            duration.text = formatDuration(video.durationMs)
            favourite.visibility = if (isFavourite(video)) View.VISIBLE else View.GONE

            applyHeight(video)
            Thumbnails.load(thumb, video, THUMB_SIZE)
            itemView.setOnClickListener { onClick(video) }
        }

        private fun applyHeight(video: Video) {
            val params = card.layoutParams
            params.height = tileHeight(video)
            card.layoutParams = params
        }

        private fun tileHeight(video: Video): Int {
            if (columnWidth <= 0) return ViewGroup.LayoutParams.WRAP_CONTENT
            // The card is narrower than its column by the gap around it, and it is the card
            // whose shape has to match the video.
            val cardWidth = columnWidth - itemView.paddingLeft - itemView.paddingRight
            if (cardWidth <= 0) return ViewGroup.LayoutParams.WRAP_CONTENT
            val ratio = when (layout) {
                ShortsLayout.GRID, ShortsLayout.LIST -> GRID_RATIO
                ShortsLayout.MASONRY -> aspectOf(video)
            }
            return (cardWidth * ratio).toInt()
        }

        /**
         * Height over width, clamped.
         *
         * Without the clamp one badly rotated file — or one with nonsense dimensions in its
         * metadata, which happens — produces a tile several screens tall and a column that
         * can never catch up with the other. The limits are wide enough that every ordinary
         * shape passes through untouched.
         */
        private fun aspectOf(video: Video): Float {
            if (video.width <= 0 || video.height <= 0) return GRID_RATIO
            return (video.height.toFloat() / video.width).coerceIn(MIN_RATIO, MAX_RATIO)
        }
    }

    private companion object {
        /**
         * The uniform grid's shape.
         *
         * 4:5 rather than the 9:16 these clips actually are. A two-column wall of 16:9-tall
         * tiles shows barely two rows on a phone, which makes scanning a chore; 4:5 is still
         * unmistakably portrait, keeps three rows in view, and crops far less than the 3:4 a
         * photo library would use. Masonry is there for anyone who wants the true shape.
         */
        const val GRID_RATIO = 1.25f
        const val MIN_RATIO = 0.62f
        const val MAX_RATIO = 1.9f

        /** Tall, because these tiles are: a square request would waste most of what returns. */
        val THUMB_SIZE = Size(320, 568)
    }
}
