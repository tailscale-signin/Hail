package com.aistra.hail.ui.home

import android.content.pm.ApplicationInfo
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.isVisible
import com.aistra.hail.app.AppInfo
import com.aistra.hail.databinding.ItemHomeBinding
import com.aistra.hail.utils.AppIconCache
import com.aistra.hail.utils.HPackages.myUserId
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Job

data class HomeRow(val info: AppInfo, val application: ApplicationInfo?, val label: String,
                   val state: AppInfo.State, val pinned: Boolean, val whitelisted: Boolean,
                   val selected: Boolean, val grayscale: Boolean, val fontSize: Float)

class PagerAdapter : ListAdapter<HomeRow, PagerAdapter.ViewHolder>(object : DiffUtil.ItemCallback<HomeRow>() {
    override fun areItemsTheSame(oldItem: HomeRow, newItem: HomeRow) = oldItem.info == newItem.info
    override fun areContentsTheSame(oldItem: HomeRow, newItem: HomeRow) =
        oldItem.copy(application = newItem.application) == newItem
}) {
    lateinit var onItemClickListener: OnItemClickListener
    lateinit var onItemLongClickListener: OnItemLongClickListener
    val apps get() = currentList.map { it.info }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemHomeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = getItem(position)
        val frozen = row.state == AppInfo.State.FROZEN
        val dim = frozen || row.state == AppInfo.State.NOT_INSTALLED
        holder.iconJob?.cancel()
        with(holder.binding) {
            root.setOnClickListener { onItemClickListener.onItemClick(row.info) }
            root.setOnLongClickListener { onItemLongClickListener.onItemLongClick(row.info) }
            val ctx = root.context
            if (row.application != null) {
                holder.iconJob = AppIconCache.loadIconBitmapAsync(ctx, row.application, myUserId, appIcon, row.grayscale && dim)
            } else {
                appIcon.setImageDrawable(ctx.packageManager.defaultActivityIcon)
                appIcon.colorFilter = null
            }
            appName.text = buildString {
                if (!row.grayscale && dim) append("❄️")
                if (row.whitelisted) append("🔒")
                append(row.label)
            }
            appName.isEnabled = !row.grayscale || !dim
            appName.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            val unavailable = row.state == AppInfo.State.NOT_FOUND
            appAvailability.isVisible = row.state.unavailable
            appAvailability.setText(if (row.state == AppInfo.State.NOT_INSTALLED)
                com.aistra.hail.R.string.package_inactive_here else com.aistra.hail.R.string.package_unavailable)
            if (row.selected || unavailable) {
                appName.setTextColor(MaterialColors.getColor(appName, if (row.selected)
                    androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnErrorContainer))
            } else appName.setTextColor(holder.nameColors)
            appName.setTextSize(TypedValue.COMPLEX_UNIT_SP, row.fontSize)
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.iconJob?.cancel()
        super.onViewRecycled(holder)
    }

    class ViewHolder(val binding: ItemHomeBinding) : RecyclerView.ViewHolder(binding.root) {
        var iconJob: Job? = null
        val nameColors = binding.appName.textColors
    }

    interface OnItemClickListener { fun onItemClick(info: AppInfo) }
    interface OnItemLongClickListener { fun onItemLongClick(info: AppInfo): Boolean }
}
