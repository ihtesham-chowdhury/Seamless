package com.seamless.player.ui.shorts

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.seamless.player.R
import com.seamless.player.data.VideoFolder
import com.seamless.player.databinding.ItemShortsSourceBinding

/** Folder picker for the shorts feed: tick the folders you want in the shuffle. */
class ShortsSourceAdapter(
    private val onToggled: (VideoFolder, Boolean) -> Unit,
) : RecyclerView.Adapter<ShortsSourceAdapter.Holder>() {

    private var items: List<VideoFolder> = emptyList()
    private val selected = mutableSetOf<String>()

    fun submit(folders: List<VideoFolder>, selectedPaths: Set<String>) {
        items = folders
        selected.clear()
        selected += selectedPaths
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemShortsSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val binding: ItemShortsSourceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(folder: VideoFolder) {
            val context = binding.root.context
            binding.name.text = folder.name
            binding.subtitle.text = context.getString(
                R.string.folder_subtitle,
                folder.videoCount,
                context.getString(
                    if (folder.isMostlyPortrait) R.string.mostly_portrait
                    else R.string.mostly_landscape
                ),
            )
            binding.check.isChecked = folder.path in selected

            binding.root.setOnClickListener {
                val nowChecked = folder.path !in selected
                if (nowChecked) selected += folder.path else selected -= folder.path
                binding.check.isChecked = nowChecked
                onToggled(folder, nowChecked)
            }
        }
    }
}
