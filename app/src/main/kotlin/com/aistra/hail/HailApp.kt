package com.aistra.hail

import android.app.Application
import android.app.UiModeManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailData
import com.aistra.hail.services.AutoFreezeService
import com.aistra.hail.utils.HDhizuku
import com.aistra.hail.utils.HTarget
import com.aistra.hail.utils.HPackages
import com.aistra.hail.utils.HShortcuts
import com.aistra.hail.utils.HLog
import com.aistra.hail.utils.DecisionLog
import com.aistra.hail.utils.HUI
import com.aistra.hail.utils.ShizukuConnection
import com.aistra.hail.widget.AllAppsWidget
import com.aistra.hail.widget.HomeSurfaceReceiver
import kotlinx.coroutines.*
import java.io.File

class HailApp : Application() {
    override fun onCreate() {
        super.onCreate()
        app = this
        ShizukuConnection.initialize()
        // DirtyDataUpdater.update(app)
        setAppTheme(HailData.appTheme)
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.getBoolean("recent_shortcuts_migrated", false)) {
            appScope.launch(shortcutDispatcher) {
                runCatching {
                    if (!HailData.recentShortcuts) HShortcuts.removeRecentApps()
                    prefs.edit().putBoolean("recent_shortcuts_migrated", true).apply()
                }.onFailure(HLog::e)
            }
        }
        if (HailData.workingMode.startsWith(HailData.DHIZUKU)) HDhizuku.init()
        val packages = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, HomeSurfaceReceiver(), packages, ContextCompat.RECEIVER_NOT_EXPORTED)
        appScope.launch(shortcutDispatcher) {
            runCatching { HShortcuts.refreshPinnedAppShortcuts(); AllAppsWidget.refresh(app) }.onFailure(HLog::e)
        }
        appScope.launch {
            AppManager.changes.collect {
                runCatching { AllAppsWidget.refresh(app) }.onFailure(HLog::e)
            }
        }
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val shortcutDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var serviceUpdate: Job? = null
    private var shortcutIconRepair: Job? = null
    private var lastServiceState: Pair<Boolean, Boolean>? = null

    fun exportDebugLog(uri: Uri) {
        appScope.launch {
            try {
                DecisionLog.export(uri)
                HUI.showToast(R.string.debug_export_saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HLog.e(e)
                HUI.showToast(R.string.debug_export_failed)
            }
        }
    }

    fun clearRecentShortcuts() {
        appScope.launch(shortcutDispatcher) { runCatching(HShortcuts::removeRecentApps).onFailure(HLog::e) }
    }

    /** Android restores pin records without bitmap icons. This marker never travels with a backup. */
    fun repairShortcutIconsOnce() {
        if (!HTarget.O || shortcutIconRepair?.isActive == true) return
        shortcutIconRepair = appScope.launch(shortcutDispatcher) {
            try {
                val marker = File(noBackupFilesDir, "shortcut-icons-restored-v1")
                // Do not mark an empty list: Android or the launcher may restore its pins later.
                if (!marker.exists() && HShortcuts.pinnedIds().isNotEmpty() &&
                    HShortcuts.refreshPinnedAppShortcuts(regenerateIcons = true)) {
                    marker.writeText("complete\n")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                HLog.e(error)
                // A limited/partial update remains retryable on the next foreground visit.
            }
        }
    }

    fun recordLaunch(pkg: String) {
        if (HailData.recentShortcuts && !HailData.biometricLogin) appScope.launch(shortcutDispatcher) {
            runCatching { HShortcuts.addDynamicShortcut(pkg) }.onFailure { HLog.e(it) }
        }
        setAutoFreezeService()
    }

    fun setAutoFreezeService(autoFreezeAfterLock: Boolean = HailData.autoFreezeAfterLock, context: Context = app) {
        appScope.launch {
            serviceUpdate?.cancel()
            serviceUpdate = launch update@{
                val candidates = if (autoFreezeAfterLock) HailData.checkedList.filter {
                    !it.whitelisted && it.packageName != packageName
                }.map { it.packageName } else emptyList()
                val whitelisted = autoFreezeAfterLock && HailData.checkedList.any { it.whitelisted }
                val mode = HailData.workingMode
                val start = candidates.isNotEmpty() && withContext(Dispatchers.IO) {
                    candidates.any { pkg -> HPackages.getApplicationInfoOrNull(pkg, HPackages.INSTALLED_FOR_USER)?.let {
                        (mode.endsWith(HailData.DISABLE) && it.flags and android.content.pm.ApplicationInfo.FLAG_STOPPED == 0) ||
                            !AppManager.isAppFrozen(it, mode)
                    } == true }
                }
                val desired = start to (start && whitelisted)
                if (desired == lastServiceState) return@update
                val intent = Intent(app, AutoFreezeService::class.java)
                try {
                    if (start) {
                        setAutoFreezeServiceEnabled(true)
                        ContextCompat.startForegroundService(context, intent)
                    } else {
                        stopService(intent)
                        setAutoFreezeServiceEnabled(false)
                    }
                    lastServiceState = desired
                } catch (e: RuntimeException) {
                    lastServiceState = null
                    HLog.e(e)
                }
            }
        }
    }

    fun setAutoFreezeServiceEnabled(enabled: Boolean) {
        val component = ComponentName(app, AutoFreezeService::class.java)
        val desired = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (packageManager.getComponentEnabledSetting(component) != desired) {
            packageManager.setComponentEnabledSetting(component, desired, PackageManager.DONT_KILL_APP)
            lastServiceState = null
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun setAppTheme(theme: String) {
        if (HTarget.S) getSystemService<UiModeManager>()!!.setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
    }


    companion object {
        lateinit var app: HailApp private set
    }
}
