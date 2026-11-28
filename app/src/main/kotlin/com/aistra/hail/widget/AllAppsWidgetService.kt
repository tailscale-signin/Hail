package com.aistra.hail.widget

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.aistra.hail.R
import com.aistra.hail.app.HailData
import com.aistra.hail.utils.HLog
import com.aistra.hail.utils.HPackages
import com.aistra.hail.utils.IconPack
import me.zhanghai.android.appiconloader.AppIconLoader
import java.text.Collator

/** Queries only the calling Android profile, including disabled launcher activities. */
internal object AllAppsWidgetCatalog {
    data class Entry(val packageName: String, val label: String, val info: ApplicationInfo)

    @Suppress("DEPRECATION")
    fun load(context: Context): List<Entry> {
        val manager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val flags = PackageManager.GET_DISABLED_COMPONENTS or PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS
        val candidates = manager.queryIntentActivities(intent, flags).toMutableList()
        // Include a hidden app only when Android identifies it as hidden and Hail manages it.
        // Retained metadata for uninstalled apps is never treated as an installed launcher target.
        candidates += manager.queryIntentActivities(intent, flags or PackageManager.GET_UNINSTALLED_PACKAGES)
            .filter { result -> result.activityInfo?.applicationInfo?.let {
                HailData.isChecked(it.packageName) && HPackages.isHidden(it)
            } == true }
        val collator = Collator.getInstance()
        return candidates.mapNotNull { result ->
            val activity = result.activityInfo ?: return@mapNotNull null
            val info = activity.applicationInfo ?: return@mapNotNull null
            if (!activity.exported || (!activity.enabled && info.enabled)) return@mapNotNull null
            val label = runCatching { info.loadLabel(manager).toString() }.getOrDefault(info.packageName)
            Entry(info.packageName, label.ifBlank { info.packageName }, info)
        }.distinctBy { it.packageName }.sortedWith { first, second ->
            collator.compare(first.label, second.label).takeIf { it != 0 }
                ?: first.packageName.compareTo(second.packageName)
        }
    }
}

class AllAppsWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = AllAppsWidgetFactory(applicationContext)
}

internal class AllAppsWidgetFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    @Volatile private var entries = emptyList<AllAppsWidgetCatalog.Entry>()
    private val iconLoader by lazy {
        AppIconLoader(context.resources.getDimensionPixelSize(R.dimen.app_icon_size),
            HailData.synthesizeAdaptiveIcons, context)
    }

    override fun onCreate() = Unit
    override fun onDataSetChanged() {
        try {
            entries = AllAppsWidgetCatalog.load(context)
        } catch (e: RuntimeException) {
            // A transient package-manager or launcher restart must not erase the last usable grid.
            HLog.e(e)
        }
    }
    override fun onDestroy() { entries = emptyList() }
    override fun getCount() = entries.size
    override fun getViewTypeCount() = 1
    override fun getLoadingView(): RemoteViews = RemoteViews(context.packageName, R.layout.widget_all_apps_item)
    override fun hasStableIds() = true
    override fun getItemId(position: Int) = entries.getOrNull(position)?.let { WidgetIdentity.itemId(it.packageName) } ?: 0L

    override fun getViewAt(position: Int): RemoteViews? {
        val entry = entries.getOrNull(position) ?: return null
        return RemoteViews(context.packageName, R.layout.widget_all_apps_item).apply {
            setTextViewText(R.id.widget_app_label, entry.label)
            setContentDescription(R.id.widget_app_item, context.getString(R.string.all_apps_widget_launch, entry.label))
            val icon = runCatching { IconPack.loadIcon(entry.packageName) ?: iconLoader.loadIcon(entry.info) }.getOrNull()
            if (icon != null) setImageViewBitmap(R.id.widget_app_icon, icon)
            else setImageViewResource(R.id.widget_app_icon, R.drawable.ic_baseline_android)
            setOnClickFillInIntent(R.id.widget_app_item, Intent().putExtra(HailData.KEY_PACKAGE, entry.packageName))
        }
    }
}
