package com.aistra.hail.app

import android.content.Intent
import android.content.pm.ApplicationInfo
import com.aistra.hail.HailApp.Companion.app
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.aistra.hail.R
import com.aistra.hail.BuildConfig
import com.aistra.hail.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AppManager {
    val lockScreen: Boolean
        get() = with(HailData.workingMode) {
            when {
                startsWith(HailData.OWNER) -> HPolicy.lockScreen
                startsWith(HailData.DHIZUKU) -> HDhizuku.lockScreen
                startsWith(HailData.SU) || startsWith(HailData.JAILBREAK) -> HShell.lockScreen
                startsWith(HailData.SHIZUKU) -> HShizuku.lockScreen
                else -> false
            }
        }

    private val mutations = Mutex()
    private val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes = changed.asSharedFlow()

    fun isAppFrozen(info: ApplicationInfo, mode: String = HailData.workingMode): Boolean = when {
        mode.endsWith(HailData.STOP) -> info.flags and ApplicationInfo.FLAG_STOPPED != 0
        mode.endsWith(HailData.DISABLE) -> !info.enabled
        mode.endsWith(HailData.HIDE) -> HPackages.isHidden(info)
        mode.endsWith(HailData.SUSPEND) -> HTarget.N && info.flags and ApplicationInfo.FLAG_SUSPENDED != 0
        else -> !info.enabled || HPackages.isHidden(info) || (HTarget.N && info.flags and ApplicationInfo.FLAG_SUSPENDED != 0)
    }

    fun state(info: ApplicationInfo?, mode: String = HailData.workingMode): AppInfo.State = when {
        info == null -> AppInfo.State.NOT_FOUND
        isAppFrozen(info, mode) -> AppInfo.State.FROZEN
        else -> AppInfo.State.UNFROZEN
    }

    suspend fun changeFrozen(packages: Collection<String>, frozen: Boolean): FreezeResult {
        val mode = HailData.workingMode
        val result = mutations.withLock {
            withContext(Dispatchers.IO) {
                FreezeBatch.run(packages, BuildConfig.APPLICATION_ID, frozen,
                    state = { state(HPackages.getApplicationInfoOrNull(it), mode) },
                    apply = { batch ->
                        when (mode) {
                            HailData.MODE_OWNER_SUSPEND -> HPolicy.setSuspended(batch, frozen)
                            HailData.MODE_DHIZUKU_SUSPEND -> HDhizuku.setSuspended(batch, frozen)
                            else -> batch.filterTo(linkedSetOf()) { pkg ->
                                try {
                                    setAppFrozen(pkg, frozen, mode)
                                } catch (e: RuntimeException) {
                                    HLog.e(e)
                                    false
                                }
                            }
                        }
                    })
            }
        }
        if (result.changed.isNotEmpty()) {
            changed.tryEmit(Unit)
            app.setAutoFreezeService()
        }
        return result
    }

    fun resultMessage(result: FreezeResult, frozen: Boolean): String {
        if (result.failed.isNotEmpty() || result.missing.isNotEmpty()) {
            if (result.failed.size == 1 && result.missing.isEmpty() && result.changed.isEmpty() && result.unchanged.isEmpty()) {
                return app.getString(if (!frozen && HailData.workingMode.endsWith(HailData.STOP))
                    R.string.stop_unfreeze else R.string.permission_denied)
            }
            return app.getString(R.string.freeze_partial, result.changed.size, result.unchanged.size,
                result.missing.size, result.failed.size)
        }
        val display = result.changed.singleOrNull() ?: result.changed.size.toString()
        return app.getString(if (frozen) R.string.msg_freeze else R.string.msg_unfreeze, display)
    }

    suspend fun prepareLaunch(pkg: String): Intent? {
        val mode = HailData.workingMode
        // Launching is the Android operation that clears a force-stop.
        if (!mode.endsWith(HailData.STOP)) {
            val result = changeFrozen(listOf(pkg), false)
            if (pkg in result.missing) return null
            if (pkg in result.failed) throw IllegalStateException(resultMessage(result, false))
        }
        return withContext(Dispatchers.IO) {
            var intent = app.packageManager.getLaunchIntentForPackage(pkg)
            if (intent == null && mode == HailData.MODE_ISLAND_HIDE) {
                for (attempt in 1..5) {
                    delay(100)
                    intent = app.packageManager.getLaunchIntentForPackage(pkg)
                    if (intent != null) break
                }
            }
            intent
        }
    }

    private fun setAppFrozen(packageName: String, frozen: Boolean, mode: String): Boolean =
        when (mode) {
            HailData.MODE_OWNER_HIDE -> HPolicy.setAppHidden(packageName, frozen)
            HailData.MODE_DHIZUKU_HIDE -> HDhizuku.setAppHidden(packageName, frozen)
            HailData.MODE_SU_STOP -> frozen && HShell.forceStopApp(packageName)
            HailData.MODE_SU_DISABLE -> HShell.setAppDisabled(packageName, frozen)
            HailData.MODE_SU_HIDE -> HShell.setAppHidden(packageName, frozen)
            HailData.MODE_SU_SUSPEND -> HShell.setAppSuspended(packageName, frozen)
            HailData.MODE_SHIZUKU_STOP -> frozen && HShizuku.forceStopApp(packageName)
            HailData.MODE_SHIZUKU_DISABLE -> HShizuku.setAppDisabled(packageName, frozen)
            HailData.MODE_SHIZUKU_HIDE -> HShizuku.setAppHidden(packageName, frozen)
            HailData.MODE_SHIZUKU_SUSPEND -> HShizuku.setAppSuspended(packageName, frozen)
            HailData.MODE_ISLAND_HIDE -> HIsland.setAppHidden(packageName, frozen)
            HailData.MODE_ISLAND_SUSPEND -> HIsland.setAppSuspended(packageName, frozen)
            HailData.MODE_PRIVAPP_STOP -> frozen && HPackages.forceStopApp(packageName)
            HailData.MODE_JAILBREAK_DISABLE, HailData.MODE_PRIVAPP_DISABLE ->
                HPackages.setAppDisabled(packageName, frozen)

            else -> false
        }

    fun uninstallApp(packageName: String): Boolean = with(HailData.workingMode) {
        if (
            (startsWith(HailData.OWNER) && HPolicy.uninstallApp(packageName))
            || (startsWith(HailData.DHIZUKU) && HDhizuku.uninstallApp(packageName))
            || ((startsWith(HailData.SU) || startsWith(HailData.JAILBREAK)) && HShell.uninstallApp(packageName))
            || (startsWith(HailData.SHIZUKU) && HShizuku.uninstallApp(packageName))
        )
            return true
        HUI.startActivity(Intent.ACTION_DELETE, HPackages.packageUri(packageName))
        false
    }

    fun reinstallApp(packageName: String): Boolean = with(HailData.workingMode) {
        when {
            startsWith(HailData.SU) || startsWith(HailData.JAILBREAK) -> HShell.reinstallApp(packageName)
            startsWith(HailData.SHIZUKU) -> HShizuku.reinstallApp(packageName)
            else -> false
        }
    }

    suspend fun execute(command: String): Pair<Int, String?> = withContext(Dispatchers.IO) {
        with(HailData.workingMode) {
            when {
                startsWith(HailData.SU) || startsWith(HailData.JAILBREAK) -> HShell.execute(command, true)
                startsWith(HailData.SHIZUKU) -> HShizuku.execute(command)
                else -> 0 to null
            }
        }
    }
}