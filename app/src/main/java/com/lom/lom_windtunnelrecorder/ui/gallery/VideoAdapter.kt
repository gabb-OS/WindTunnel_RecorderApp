package com.lom.lom_windtunnelrecorder.ui.gallery

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.lom.lom_windtunnelrecorder.databinding.ItemVideoBinding
import java.util.Locale
import java.util.concurrent.TimeUnit


/**
 * An adapter for displaying a list of [VideoItem] objects in a [RecyclerView].
 * It uses [ListAdapter] for efficient updates with [DiffUtil].
 * Handles displaying video thumbnails (using Glide), names, and durations.
 * It also manages click events on each video item.
 *
 * @param context The context, typically from the Fragment or Activity, used for Glide and string formatting.
 * @param onVideoClick A lambda function that will be invoked when a video item is clicked.
 *                     It receives the clicked [VideoItem] as a parameter.
 */
class VideoAdapter(
    private val context: Context,
    private val onVideoClick: (VideoItem) -> Unit
) : ListAdapter<VideoItem, VideoAdapter.VideoViewHolder>(VideoDiffCallback()) {


    /**
     * Called when RecyclerView needs a new [VideoViewHolder] of the given type to represent an item.
     * Inflates the layout for a single video item using [ItemVideoBinding].
     *
     * @param parent The ViewGroup into which the new View will be added after it is bound to
     *               an adapter position.
     * @param viewType The view type of the new View.
     * @return A new VideoViewHolder that holds the View for each video item.
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val binding = ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VideoViewHolder(binding, onVideoClick)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        val videoItem = getItem(position)
        holder.bind(videoItem, context)
    }


    /**
     * ViewHolder class for individual video items in the RecyclerView.
     * It holds references to the views within the item_video.xml layout and binds
     * [VideoItem] data to these views.
     */
    class VideoViewHolder(
        private val binding: ItemVideoBinding,
        private val onVideoClick: (VideoItem) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(videoItem: VideoItem, context: Context) {
            binding.videoName.text = videoItem.name

            val minutes = TimeUnit.MILLISECONDS.toMinutes(videoItem.durationMillis)
            val seconds = TimeUnit.MILLISECONDS.toSeconds(videoItem.durationMillis) % 60
            binding.videoDuration.text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

            // Load thumbnail using Glide
            Glide.with(context)
                .load(videoItem.uri)
                .centerCrop()
                .into(binding.videoThumbnail)

            binding.root.setOnClickListener {
                onVideoClick(videoItem)
            }
        }
    }
}

/**
 * A [DiffUtil.ItemCallback] for calculating the difference between two [VideoItem] lists.
 * This is used by [ListAdapter] to efficiently update the RecyclerView when the list of videos changes.
 */
class VideoDiffCallback : DiffUtil.ItemCallback<VideoItem>() {
    override fun areItemsTheSame(oldItem: VideoItem, newItem: VideoItem): Boolean {
        return oldItem.uri == newItem.uri
    }

    override fun areContentsTheSame(oldItem: VideoItem, newItem: VideoItem): Boolean {
        return oldItem == newItem
    }
}