package com.aistra.hail.ui.apps

import android.content.pm.ApplicationInfo
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aistra.hail.R
import com.aistra.hail.databinding.ItemAppsBinding
import com.aistra.hail.utils.AppIconCache
import com.aistra.hail.utils.HPackages
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
    private val expandedGuidance = mutableSetOf<String>()

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
                setTextColor(nameColors)
            }
            binding.appDesc.apply {
                text = pkg
                isEnabled = !row.grayscale || !frozen
            }
            binding.appRisk.apply {
                text = AppRiskPresentation.label(context, row.risk)
            }
            binding.appRiskReason.apply {
                text = AppRiskPresentation.explanation(context, row.risk)
                fun showGuidance() {
                    val expanded = pkg in expandedGuidance
                    maxLines = if (expanded) Int.MAX_VALUE else 2
                    ellipsize = if (expanded) null else TextUtils.TruncateAt.END
                }
                showGuidance()
                setOnClickListener {
                    if (!expandedGuidance.remove(pkg)) expandedGuidance.add(pkg)
                    showGuidance()
                }
                ViewCompat.replaceAccessibilityAction(this,
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                    context.getString(R.string.app_risk_description_toggle)) { view, _ ->
                    view.performClick()
                }
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
