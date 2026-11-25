package com.aistra.hail

import android.app.Application
import android.app.UiModeManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.*

class HailApp : Application() {
    override fun onCreate() {
        super.onCreate()
        app = this
        // DirtyDataUpdater.update(app)
        if (!HTarget.S) setAppTheme(HailData.appTheme)
        if (HailData.workingMode.startsWith(HailData.DHIZUKU)) HDhizuku.init()
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val shortcutDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var serviceUpdate: Job? = null
    private var lastServiceState: Pair<Boolean, Boolean>? = null

    fun recordLaunch(pkg: String) {
        appScope.launch(shortcutDispatcher) {
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
                    candidates.any { pkg -> HPackages.getApplicationInfoOrNull(pkg)?.let {
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

    fun setAppTheme(theme: String) {
        if (HTarget.S) getSystemService<UiModeManager>()!!.setApplicationNightMode(
            when (theme) {
                HailData.THEME_LIGHT -> UiModeManager.MODE_NIGHT_NO
                HailData.THEME_DARK -> UiModeManager.MODE_NIGHT_YES
                else -> UiModeManager.MODE_NIGHT_AUTO
            }
        )
        else AppCompatDelegate.setDefaultNightMode(
            when (theme) {
                HailData.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                HailData.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }


    companion object {
        lateinit var app: HailApp private set
    }
}