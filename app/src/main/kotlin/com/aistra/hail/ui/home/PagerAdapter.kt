package com.aistra.hail.ui.home

import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.isVisible
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.AppRisk
import com.aistra.hail.databinding.ItemHomeBinding
import com.aistra.hail.utils.AppIconCache
import com.aistra.hail.utils.HPackages.myUserId
import kotlinx.coroutines.Job

data class HomeRow(val info: AppInfo, val application: ApplicationInfo?, val label: String,
                   val state: AppInfo.State, val pinned: Boolean, val whitelisted: Boolean,
                   val selected: Boolean, val grayscale: Boolean, val fontSize: Float,
                   val risk: AppRisk = AppRisk())

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
                holder.iconJob = AppIconCache.loadIconBitmapAsync(ctx, row.application, myUserId, appIcon,
                    row.grayscale && dim, darken = frozen)
            } else {
                appIcon.setImageDrawable(ctx.packageManager.defaultActivityIcon)
                AppIconCache.applyFilter(appIcon, grayscale = row.grayscale && frozen, darken = frozen)
            }
            appName.text = buildString {
                if (row.selected) append("✓ ")
                if (!row.grayscale && dim) append("❄️")
                if (row.whitelisted) append("🔒")
                append(row.label)
            }
            // Frozen labels use explicit RGB brightness, without stacking the disabled palette's
            // opacity reduction. Retained, uninstalled entries keep their separate presentation.
            appName.isEnabled = !row.grayscale || row.state != AppInfo.State.NOT_INSTALLED
            appName.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            val unavailable = row.state == AppInfo.State.NOT_FOUND
            appAvailability.isVisible = unavailable
            appAvailability.setText(com.aistra.hail.R.string.package_unavailable)
            // The check mark identifies selection while app names keep the normal theme colours.
            root.isSelected = row.selected
            if (frozen) {
                val color = holder.nameColors.getColorForState(appName.drawableState, holder.nameColors.defaultColor)
                appName.setTextColor(Color.argb(Color.alpha(color), Color.red(color) / 2,
                    Color.green(color) / 2, Color.blue(color) / 2))
            } else {
                appName.setTextColor(holder.nameColors)
            }
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
