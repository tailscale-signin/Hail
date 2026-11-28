package com.aistra.hail.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import com.aistra.hail.R
import com.aistra.hail.app.HailApi
import com.aistra.hail.app.HailData
import com.aistra.hail.ui.api.InternalActionActivity
import com.aistra.hail.ui.main.MainActivity
import com.aistra.hail.utils.HLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One launcher-owned widget holds the whole collection; it never requests one pin per app. */
class AllAppsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isNotEmpty()) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(EVER_PINNED, true).apply()
        ids.forEach { manager.updateAppWidget(it, views(context, it)) }
        refresh(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        manager.updateAppWidget(id, views(context, id))
    }

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        // The host owns IDs. Rebuild every adapter/PendingIntent with its restored ID.
        onUpdate(context, AppWidgetManager.getInstance(context), newWidgetIds)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        updateSettingsState(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            PINNED -> {
                if (widgetIds(context).isNotEmpty()) {
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(EVER_PINNED, true).apply()
                    message.value = R.string.all_apps_widget_added
                    refresh(context)
                }
            }
            REFRESH -> refresh(context)
            else -> super.onReceive(context, intent)
        }
    }

    companion object {
        private const val PREFS = "home_surface_state"
        private const val EVER_PINNED = "all_apps_widget_added"
        private const val PINNED = "com.aistra.hail.widget.PINNED"
        private const val REFRESH = "com.aistra.hail.widget.REFRESH"
        private val message = MutableStateFlow(R.string.all_apps_widget_summary)
        val pinMessage = message.asStateFlow()

        fun widgetIds(context: Context): IntArray = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, AllAppsWidget::class.java))

        fun updateSettingsState(context: Context) {
            if (widgetIds(context).isEmpty() && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(EVER_PINNED, false)) message.value = R.string.all_apps_widget_missing
        }

        /** Only the launcher's callback reports success; a request's true result is not a pin receipt. */
        fun requestPin(context: Context) {
            try {
                if (widgetIds(context).isNotEmpty()) {
                    refresh(context)
                    message.value = R.string.all_apps_widget_refreshed
                    return
                }
                val manager = AppWidgetManager.getInstance(context)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !manager.isRequestPinAppWidgetSupported) {
                    message.value = R.string.all_apps_widget_manual
                    return
                }
                val callback = PendingIntent.getBroadcast(context, 0,
                    Intent(context, AllAppsWidget::class.java).setAction(PINNED),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                message.value = R.string.all_apps_widget_pending
                if (!manager.requestPinAppWidget(ComponentName(context, AllAppsWidget::class.java), null, callback)) {
                    message.value = R.string.all_apps_widget_manual
                }
            } catch (e: RuntimeException) {
                HLog.e(e)
                message.value = R.string.all_apps_widget_manual
            }
        }

        @Suppress("DEPRECATION")
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = widgetIds(context)
            if (ids.isEmpty()) return
            ids.forEach { manager.updateAppWidget(it, views(context, it)) }
            // Collection cells are loaded on demand, avoiding a bitmap/Binder payload for 300 apps.
            manager.notifyAppWidgetViewDataChanged(ids, R.id.all_apps_grid)
        }

        fun launchTemplate(context: Context, widgetId: Int): Intent =
            Intent(context, InternalActionActivity::class.java).setAction(HailApi.ACTION_LAUNCH)
                .setData(Uri.parse("hail-widget://apps/$widgetId"))
                .putExtra(HailApi.EXTRA_ORIGIN, HailApi.WIDGET)
                .putExtra(HailData.KEY_TAG, null as String?)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        internal fun views(context: Context, widgetId: Int): RemoteViews =
            RemoteViews(context.packageName, R.layout.widget_all_apps).apply {
                setRemoteAdapter(R.id.all_apps_grid, Intent(context, AllAppsWidgetService::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    .setData(Uri.parse("hail-widget://collection/$widgetId")))
                setEmptyView(R.id.all_apps_grid, R.id.all_apps_empty)
                val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                // Only the package extra is supplied by a cell. Action and unexported component are fixed.
                setPendingIntentTemplate(R.id.all_apps_grid, PendingIntent.getActivity(context, widgetId,
                    launchTemplate(context, widgetId), PendingIntent.FLAG_UPDATE_CURRENT or mutable))
                setOnClickPendingIntent(R.id.all_apps_heading, PendingIntent.getActivity(context, widgetId,
                    Intent(context, MainActivity::class.java).setData(Uri.parse("hail-widget://open/$widgetId")),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                setOnClickPendingIntent(R.id.all_apps_refresh, PendingIntent.getBroadcast(context, widgetId,
                    Intent(context, AllAppsWidget::class.java).setAction(REFRESH)
                        .setData(Uri.parse("hail-widget://refresh/$widgetId")),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
    }
}
