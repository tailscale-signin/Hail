package com.aistra.hail.app

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
import rikka.shizuku.Shizuku
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.CancellationException

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
        mode.endsWith(HailData.DISABLE) -> !HPackages.UserPackage(info, true).canDisable()
        mode.endsWith(HailData.HIDE) -> HPackages.isHidden(info)
        mode.endsWith(HailData.SUSPEND) -> HTarget.N && info.flags and ApplicationInfo.FLAG_SUSPENDED != 0
        else -> !info.enabled || HPackages.isHidden(info) || (HTarget.N && info.flags and ApplicationInfo.FLAG_SUSPENDED != 0)
    }

    fun state(pkg: HPackages.UserPackage?, mode: String = HailData.workingMode, targetSetting: Int? = null): AppInfo.State {
        if (pkg == null || !pkg.installed) return AppInfo.State.NOT_FOUND
        val frozen = if (mode.endsWith(HailData.DISABLE)) {
            val setting = pkg.setting()
            // Android can reactivate DISABLED_UNTIL_USED on first use. Either explicit request must reach the backend.
            when {
                setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> targetSetting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                // 1.13.2 wrote DISABLED_USER even with root. An explicit full disable must repair that older state.
                setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER && targetSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
                else -> !pkg.canDisable(setting)
            }
        } else pkg.info?.let { isAppFrozen(it, mode) } == true
        return if (frozen) AppInfo.State.FROZEN else AppInfo.State.UNFROZEN
    }

    suspend fun snapshotFrozenStates(apps: List<AppInfo>): Map<String, Boolean> = mutations.withLock {
        withContext(Dispatchers.IO) {
            val mode = HailData.workingMode
            val lookup = PackageLookup(mode)
            buildMap {
                for (item in apps) {
                    if (item.packageName == BuildConfig.APPLICATION_ID) continue
                    val current = state(lookup.find(item.packageName), mode)
                    if (current != AppInfo.State.NOT_FOUND) put(item.packageName, current == AppInfo.State.FROZEN)
                }
            }
        }
    }

    suspend fun changeFrozen(packages: Collection<String>, frozen: Boolean, includeLabel: Boolean = true,
                             mode: String = HailData.workingMode): FreezeResult {
        return mutations.withLock {
            withContext(Dispatchers.IO) {
                val infoByPackage = mutableMapOf<String, ApplicationInfo>()
                val lookup = PackageLookup(mode)
                val targetSetting = if (frozen) lookup.disableSetting else PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                var androidError: String? = null
                val result = FreezeBatch.run(packages, BuildConfig.APPLICATION_ID, frozen,
                    state = { pkg ->
                        val record = lookup.find(pkg)
                        if (includeLabel) record?.info?.let { infoByPackage[pkg] = it }
                        state(record, mode, targetSetting)
                    },
                    apply = { batch ->
                        when (mode) {
                            HailData.MODE_OWNER_SUSPEND -> HPolicy.setSuspended(batch, frozen)
                            HailData.MODE_DHIZUKU_SUSPEND -> HDhizuku.setSuspended(batch, frozen)
                            else -> batch.filterTo(linkedSetOf()) { pkg ->
                                try {
                                    setAppFrozen(pkg, frozen, mode, lookup)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    HLog.e(e)
                                    val cause = (e as? InvocationTargetException)?.targetException ?: e
                                    androidError = cause.message?.lineSequence()?.firstOrNull()?.take(350)
                                    false
                                }
                            }
                        }
                    })
                if (result.changed.isNotEmpty()) {
                    changed.tryEmit(Unit)
                    app.setAutoFreezeService()
                }
                val single = result.changed.singleOrNull() ?: result.unchanged.singleOrNull()
                val label = single?.let { pkg -> runCatching { infoByPackage[pkg]?.loadLabel(app.packageManager)?.toString() }.getOrNull() }
                val failed = result.failed.singleOrNull()
                val failureMessage = if (failed != null && mode.endsWith(HailData.DISABLE) &&
                    result.changed.isEmpty() && result.unchanged.isEmpty() && result.missing.isEmpty()) {
                    val info = infoByPackage[failed] ?: HPackages.getApplicationInfoOrNull(failed)
                    listOfNotNull(disableFailureMessage(failed, mode, info, androidError), androidError).joinToString("\n").ifBlank { null }
                } else null
                result.copy(singleLabel = label, failureMessage = failureMessage)
            }
        }
    }

    private fun disableFailureMessage(pkg: String, mode: String, info: ApplicationInfo?, error: String?): String? {
        if (mode == HailData.MODE_SHIZUKU_DISABLE) {
            if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return app.getString(R.string.start_shizuku)
            if (!runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false))
                return app.getString(R.string.authorize_shizuku)
            if (error?.contains("Shell cannot change component state") == true)
                return app.getString(R.string.package_shell_refused, pkg)
        }
        return if (info != null && HPackages.isSystemApp(info)) app.getString(R.string.system_app_change_failed, pkg) else null
    }

    fun resultMessage(result: FreezeResult, frozen: Boolean): String {
        result.failureMessage?.let { return it }
        if (result.failed.isNotEmpty() || result.missing.isNotEmpty()) {
            if (result.failed.size == 1 && result.missing.isEmpty() && result.changed.isEmpty() && result.unchanged.isEmpty()) {
                return app.getString(if (!frozen && HailData.workingMode.endsWith(HailData.STOP))
                    R.string.stop_unfreeze else R.string.permission_denied)
            }
            return app.getString(R.string.freeze_partial, result.changed.size, result.unchanged.size,
                result.missing.size, result.failed.size)
        }
        if (result.changed.isEmpty() && result.unchanged.isNotEmpty()) {
            val target = result.singleLabel ?: result.unchanged.size.toString()
            return app.getString(if (frozen) R.string.already_frozen else R.string.already_unfrozen, target)
        }
        val display = result.changed.singleOrNull()?.let { result.singleLabel ?: it } ?: result.changed.size.toString()
        return app.getString(if (frozen) R.string.msg_freeze else R.string.msg_unfreeze, display)
    }

    suspend fun prepareLaunch(pkg: String): Intent? {
        val mode = HailData.workingMode
        var thawed = false
        // Launching is the Android operation that clears a force-stop.
        if (!mode.endsWith(HailData.STOP)) {
            val result = changeFrozen(listOf(pkg), false, includeLabel = false)
            if (pkg in result.missing) return null
            if (pkg in result.failed) throw IllegalStateException(resultMessage(result, false))
            thawed = pkg in result.changed
        }
        return withContext(Dispatchers.IO) {
            var intent = app.packageManager.getLaunchIntentForPackage(pkg)
            if (intent == null && (thawed || mode == HailData.MODE_ISLAND_HIDE)) {
                for (waitMs in listOf(20L, 40L, 70L, 120L, 250L)) {
                    delay(waitMs)
                    intent = app.packageManager.getLaunchIntentForPackage(pkg)
                    if (intent != null) break
                }
            }
            intent
        }
    }

    private fun setAppFrozen(packageName: String, frozen: Boolean, mode: String, lookup: PackageLookup): Boolean =
        when (mode) {
            HailData.MODE_OWNER_HIDE -> HPolicy.setAppHidden(packageName, frozen)
            HailData.MODE_DHIZUKU_HIDE -> HDhizuku.setAppHidden(packageName, frozen)
            HailData.MODE_SU_STOP -> frozen && HShell.forceStopApp(packageName)
            HailData.MODE_SU_DISABLE -> HShell.setAppDisabled(packageName, frozen)
            HailData.MODE_SU_HIDE -> HShell.setAppHidden(packageName, frozen)
            HailData.MODE_SU_SUSPEND -> HShell.setAppSuspended(packageName, frozen)
            HailData.MODE_SHIZUKU_STOP -> frozen && HShizuku.forceStopApp(packageName)
            HailData.MODE_SHIZUKU_DISABLE -> lookup.shizuku?.setDisabled(packageName, frozen) == true
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
