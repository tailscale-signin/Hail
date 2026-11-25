package com.aistra.hail.ui.apps

import android.content.pm.ApplicationInfo
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aistra.hail.databinding.ItemAppsBinding
import com.aistra.hail.utils.AppIconCache
import com.aistra.hail.utils.HPackages
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Job

class AppsAdapter : ListAdapter<InstalledRow, AppsAdapter.ViewHolder>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<InstalledRow>() {
            override fun areItemsTheSame(
                oldItem: InstalledRow, newItem: InstalledRow
            ): Boolean = oldItem.info.packageName == newItem.info.packageName

            override fun areContentsTheSame(oldItem: InstalledRow, newItem: InstalledRow): Boolean =
                oldItem.copy(info = newItem.info) == newItem
        }
    }

    lateinit var onItemClickListener: OnItemClickListener
    lateinit var onItemCheckedChangeListener: OnItemCheckedChangeListener

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        ItemAppsBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val info = currentList[position]
        holder.bindInfo(info)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.loadIconJob?.cancel()
        super.onViewRecycled(holder)
    }

    inner class ViewHolder(private val binding: ItemAppsBinding) : RecyclerView.ViewHolder(binding.root) {
        private val nameColors = binding.appName.textColors
        var loadIconJob: Job? = null
        lateinit var info: ApplicationInfo
        private val pkg get() = info.packageName

        /**
         * Flag that view data is being updated to avoid triggering the event.
         * */
        private var updating = false

        init {
            binding.root.apply {
                setOnClickListener { onItemClickListener.onItemClick(binding.appStar) }
                isLongClickable = true
            }
            binding.appStar.setOnCheckedChangeListener { button, isChecked ->
                if (!updating) onItemCheckedChangeListener.onItemCheckedChange(button, isChecked, pkg)
            }
        }

        fun bindInfo(row: InstalledRow) {
            updating = true
            info = row.info
            loadIconJob?.cancel()
            val frozen = row.frozen

            binding.appIcon.apply {
                loadIconJob = AppIconCache.loadIconBitmapAsync(
                    context, info, HPackages.myUserId, this, row.grayscale && frozen
                )
            }
            binding.appName.apply {
                val name = row.label
                text = if (!row.grayscale && frozen) "❄️$name" else name
                isEnabled = !row.grayscale || !frozen
                if (!row.installed) setTextColor(
                    MaterialColors.getColor(
                        this, androidx.appcompat.R.attr.colorError
                    )
                )
                else setTextColor(nameColors)
            }
            binding.appDesc.apply {
                text = pkg
                isEnabled = !row.grayscale || !frozen
            }
            binding.appStar.isChecked = row.checked
            updating = false
        }
    }

    interface OnItemClickListener {
        fun onItemClick(buttonView: CompoundButton)
    }

    interface OnItemCheckedChangeListener {
        fun onItemCheckedChange(buttonView: CompoundButton, isChecked: Boolean, packageName: String)
    }
}
