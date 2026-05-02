package com.kooo.evcam.v2.ui.playback

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kooo.evcam.databinding.ItemV2VideoBinding
import com.kooo.evcam.databinding.ItemV2VideoDateBinding

class V2VideoPlaybackAdapter(
    private val onItemClick: (V2VideoGroup) -> Unit,
    private val onThumbnailNeeded: (V2VideoGroup) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val groups = mutableListOf<V2VideoGroup>()
    private val rows = mutableListOf<Row>()

    private sealed class Row {
        data class DateHeader(val label: String) : Row()
        data class Video(val group: V2VideoGroup) : Row()
    }

    class VideoVH(val binding: ItemV2VideoBinding) : RecyclerView.ViewHolder(binding.root)
    class DateVH(val binding: ItemV2VideoDateBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.DateHeader -> VIEW_TYPE_DATE
        is Row.Video -> VIEW_TYPE_VIDEO
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_DATE) {
            DateVH(ItemV2VideoDateBinding.inflate(inflater, parent, false))
        } else {
            VideoVH(ItemV2VideoBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.DateHeader -> (holder as DateVH).binding.videoDateTitle.text = row.label
            is Row.Video -> bindVideo(holder as VideoVH, row.group)
        }
    }

    override fun getItemCount() = rows.size

    fun getSpanSize(position: Int): Int = if (rows.getOrNull(position) is Row.DateHeader) SPAN_COUNT else 1

    fun clear() {
        val oldSize = rows.size
        groups.clear()
        rows.clear()
        if (oldSize > 0) notifyItemRangeRemoved(0, oldSize)
    }

    fun replaceAll(next: List<V2VideoGroup>) {
        groups.clear()
        groups.addAll(next)
        rebuildRows()
        notifyDataSetChanged()
    }

    fun addOrUpdate(group: V2VideoGroup): Int {
        val existing = groups.indexOfFirst { it.identityKey == group.identityKey }
        if (existing >= 0) {
            groups[existing] = group
        } else {
            val insertAt = groups.indexOfFirst { it.timestamp < group.timestamp }.let { if (it < 0) groups.size else it }
            groups.add(insertAt, group)
        }
        rebuildRows()
        notifyDataSetChanged()
        return rows.indexOfFirst { it is Row.Video && it.group.identityKey == group.identityKey }
    }

    fun updateThumbnail(identityKey: String, thumbnail: Bitmap) {
        val index = groups.indexOfFirst { it.identityKey == identityKey }
        if (index < 0) return
        val updated = groups[index].copy(thumbnail = thumbnail)
        groups[index] = updated
        val rowIndex = rows.indexOfFirst { it is Row.Video && it.group.identityKey == identityKey }
        if (rowIndex >= 0) {
            rows[rowIndex] = Row.Video(updated)
            notifyItemChanged(rowIndex)
        }
    }

    fun firstOrNull(): V2VideoGroup? = groups.firstOrNull()

    fun snapshot(): List<V2VideoGroup> = groups.toList()

    private fun bindVideo(holder: VideoVH, group: V2VideoGroup) {
        holder.binding.videoTime.text = group.displayTime
        holder.binding.videoPlayIcon.visibility = if (group.isPhoto) android.view.View.GONE else android.view.View.VISIBLE
        if (group.thumbnail != null) {
            holder.binding.videoThumbnail.setImageBitmap(group.thumbnail)
            holder.binding.videoThumbnail.alpha = 1f
        } else {
            holder.binding.videoThumbnail.setImageDrawable(null)
            holder.binding.videoThumbnail.alpha = 0.35f
            onThumbnailNeeded(group)
        }
        holder.binding.root.setOnClickListener { onItemClick(group) }
    }

    private fun rebuildRows() {
        rows.clear()
        var lastDate = ""
        groups.forEach { group ->
            val date = dateLabel(group)
            if (date != lastDate) {
                rows += Row.DateHeader(date)
                lastDate = date
            }
            rows += Row.Video(group)
        }
    }

    private fun dateLabel(group: V2VideoGroup): String {
        val prefix = group.timestamp.take(8)
        if (prefix.length == 8 && prefix.all { it.isDigit() }) {
            return "${prefix.take(4)}年${prefix.substring(4, 6)}月${prefix.substring(6, 8)}日"
        }
        return group.displayDate
    }

    private companion object {
        const val VIEW_TYPE_DATE = 0
        const val VIEW_TYPE_VIDEO = 1
        const val SPAN_COUNT = 4
    }
}
