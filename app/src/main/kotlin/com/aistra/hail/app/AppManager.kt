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

    fun state(pkg: HPackages.UserPackage?, mode: String = HailData.workingMode, targetSetting: Int? = null,
              trace: DecisionTrace? = null, name: String? = pkg?.info?.packageName): AppInfo.State {
        if (pkg == null || !pkg.installed) {
            trace?.event("package_state", name, "installed" to false, "retainedMetadata" to (pkg != null))
            return if (pkg == null) AppInfo.State.NOT_FOUND else AppInfo.State.NOT_INSTALLED
        }
        val frozen = if (mode.endsWith(HailData.DISABLE)) {
            // The installed inventory carries effective enablement. Read an exact override for a requested change.
            val setting = if (targetSetting == null) pkg.enabledSetting else pkg.setting()
            trace?.event("enabled_setting", name, "beforeSetting" to setting, "requestedSetting" to targetSetting,
                "settingSource" to when {
                    pkg.inferredSetting -> "Effective state inferred from root package inventory"
                    setting != null -> "Android package enabled override"
                    else -> "Only effective ApplicationInfo state is available"
                })
            // Android can reactivate DISABLED_UNTIL_USED on first use. Either explicit request must reach the backend.
            when {
                setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> {
                    trace?.event("transition", name, "reason" to "Disabled until used is temporary")
                    targetSetting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                }
                // 1.13.2 wrote DISABLED_USER even with root. An explicit full disable must repair that older state.
                setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER && targetSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> {
                    trace?.event("transition", name, "reason" to "Privilege allows full disable of a previously user-disabled package")
                    false
                }
                else -> !pkg.canDisable(setting)
            }
        } else pkg.info?.let { isAppFrozen(it, mode) } == true
        trace?.event("package_state", name, "installed" to true, "system" to pkg.info?.let(HPackages::isSystemApp),
            "flags" to pkg.info?.flags, "enabled" to pkg.info?.enabled, "classifiedFrozen" to frozen)
        return if (frozen) AppInfo.State.FROZEN else AppInfo.State.UNFROZEN
    }

    suspend fun snapshotFrozenStates(apps: List<AppInfo>): Map<String, Boolean> = mutations.withLock {
        withContext(Dispatchers.IO) {
            val mode = HailData.workingMode
            val lookup = PackageLookup(mode)
            buildMap {
                for (item in apps) {
                    if (item.packageName == BuildConfig.APPLICATION_ID) continue
                    val record = if (apps.size >= 8) lookup.fromInventory(item.packageName, forChange = true) else lookup.find(item.packageName)
                    val current = state(record, mode)
                    if (!current.unavailable) put(item.packageName, current == AppInfo.State.FROZEN)
                }
            }
        }
    }

    suspend fun changeFrozen(packages: Collection<String>, frozen: Boolean, includeLabel: Boolean = true,
                             mode: String = HailData.workingMode, source: String = "app_manager",
                             trace: DecisionTrace? = DecisionLog.change(source, frozen, mode)): FreezeResult {
        trace?.event("queued", null, "packages" to packages.size, "freeze" to frozen)
        return try {
            mutations.withLock {
                withContext(Dispatchers.IO) {
                    val infoByPackage = mutableMapOf<String, ApplicationInfo>()
                    val lookup = PackageLookup(mode, trace)
                    val targetSetting = if (frozen) lookup.disableSetting else PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    trace?.event("backend_selected", null, "backend" to mode,
                        "requestedSetting" to targetSetting.takeIf { mode.endsWith(HailData.DISABLE) }, "reason" to when {
                            !mode.endsWith(HailData.DISABLE) -> "Use the selected working mode"
                            !frozen -> "Explicitly enable for the current Android user"
                            targetSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> "ADB-backed Shizuku uses disable-user"
                            targetSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> "Privileged backend uses full disable"
                            else -> "Disable backend is unavailable"
                        })
                    val failureReasons = mutableMapOf<String, String>()
                    val singleWrites = mutableSetOf<String>()
                    val shellDisable = mode == HailData.MODE_SHIZUKU_DISABLE &&
                        lookup.disableSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    fun recordFailure(pkg: String, error: Exception) {
                        if (error is CancellationException) throw error
                        trace?.error("android_error", pkg, error)
                        HLog.e(error)
                        val cause = (error as? InvocationTargetException)?.targetException ?: error
                        failureReasons[pkg] = (cause.message?.lineSequence()?.firstOrNull() ?: cause.javaClass.simpleName).take(350)
                    }
                    val result = FreezeBatch.run(packages, BuildConfig.APPLICATION_ID, frozen,
                        state = { pkg ->
                            val record = if (packages.size >= 8) lookup.fromInventory(pkg, forChange = true) else lookup.find(pkg)
                            if (shellDisable &&
                                (record?.enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED ||
                                    record?.enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED)) singleWrites.add(pkg)
                            if (includeLabel) record?.info?.let { infoByPackage[pkg] = it }
                            state(record, mode, targetSetting, trace, pkg)
                        },
                        trace = trace,
                        apply = { batch ->
                            val succeeded = when (mode) {
                                HailData.MODE_SHIZUKU_DISABLE -> {
                                    val service = lookup.shizuku
                                    if (service == null) {
                                        trace?.skipped(null, "Authorized Shizuku package service unavailable")
                                        emptySet()
                                    } else service.setDisabled(batch, frozen, singleWrites, trace, ::recordFailure)
                                }
                                HailData.MODE_OWNER_SUSPEND -> HPolicy.setSuspended(batch, frozen, trace)
                                HailData.MODE_DHIZUKU_SUSPEND -> HDhizuku.setSuspended(batch, frozen, trace)
                                else -> batch.filterTo(linkedSetOf()) { pkg ->
                                    try {
                                        setAppFrozen(pkg, frozen, mode, trace)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        recordFailure(pkg, e)
                                        false
                                    }
                                }
                            }
                            if (trace != null) for (pkg in batch) {
                                if ((!mode.endsWith(HailData.DISABLE) || pkg !in succeeded) &&
                                        (mode != HailData.MODE_SHIZUKU_DISABLE || lookup.shizuku == null)) {
                                    observeAfterChange(trace, pkg, mode, lookup, pkg in succeeded)
                                }
                            }
                            succeeded
                        })
                    if (result.changed.isNotEmpty()) {
                        changed.tryEmit(Unit)
                        app.setAutoFreezeService()
                    }
                    val single = result.changed.singleOrNull() ?: result.unchanged.singleOrNull()
                    val label = single?.let { pkg -> runCatching { infoByPackage[pkg]?.loadLabel(app.packageManager)?.toString() }.getOrNull() }
                    val failed = result.failed.singleOrNull()
                    val failureMessage = if (failed != null && mode.endsWith(HailData.DISABLE) &&
                        result.changed.isEmpty() && result.unchanged.isEmpty() && result.missing.isEmpty() && result.notInstalled.isEmpty()) {
                        val info = infoByPackage[failed] ?: HPackages.getApplicationInfoOrNull(failed)
                        val androidError = failureReasons[failed]
                        listOfNotNull(disableFailureMessage(failed, mode, info, androidError), androidError).joinToString("\n").ifBlank { null }
                    } else null
                    trace?.event("batch_result", null, "changed" to result.changed.size, "unchanged" to result.unchanged.size,
                        "missing" to result.missing.size, "notInstalled" to result.notInstalled.size, "failed" to result.failed.size)
                    result.copy(singleLabel = label, failureMessage = failureMessage, failureReasons = failureReasons)
                }
            }
        } catch (e: CancellationException) {
            trace?.event("cancelled")
            throw e
        } catch (e: Exception) {
            trace?.error("operation_failed", null, e)
            throw e
        }
    }

    private fun observeAfterChange(trace: DecisionTrace, pkg: String, mode: String, lookup: PackageLookup, accepted: Boolean) {
        // An observation must never change the outcome of the operation it is explaining.
        runCatching {
            if (mode.endsWith(HailData.DISABLE)) {
                val service = lookup.shizuku
                val state = if (service != null) service.setting(pkg) else HPackages.currentEnabledSetting(pkg)
                trace.event("readback", pkg, "reader" to if (service != null) "Shizuku IPackageManager" else "PackageManager",
                    "observedSetting" to state, "backendAccepted" to accepted)
            } else {
                val record = HPackages.findForUser(pkg)
                trace.event("readback", pkg, "reader" to "PackageManager", "installed" to record?.installed,
                    "observedFrozen" to record?.info?.let { isAppFrozen(it, mode) }, "backendAccepted" to accepted)
            }
        }.onFailure { trace.error("readback_failed", pkg, it) }
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
        if (result.notInstalled.isNotEmpty()) {
            return app.getString(if (frozen) R.string.freeze_counts_inactive else R.string.unfreeze_counts_not_installed,
                result.changed.size, result.unchanged.size, result.notInstalled.size, result.missing.size, result.failed.size)
        }
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

    suspend fun prepareLaunch(pkg: String, mode: String = HailData.workingMode,
                              trace: DecisionTrace? = DecisionLog.begin("app_manager", "launch", mode, pkg)): Intent? {
        var thawed = false
        // Launching is the Android operation that clears a force-stop.
        if (mode.endsWith(HailData.STOP)) {
            trace?.event("launch_policy", pkg, "reason" to "Android clears force-stop when the app starts")
        } else {
            trace?.event("launch_policy", pkg, "reason" to "Unfreeze before looking for a launcher activity")
            val result = changeFrozen(listOf(pkg), false, includeLabel = false, mode = mode, trace = trace)
            if (pkg in result.missing || pkg in result.notInstalled) return null
            if (pkg in result.failed) throw IllegalStateException(resultMessage(result, false))
            thawed = pkg in result.changed
        }
        return withContext(Dispatchers.IO) {
            var intent = app.packageManager.getLaunchIntentForPackage(pkg)
            trace?.event("launcher_lookup", pkg, "found" to (intent != null))
            if (intent == null && (thawed || mode == HailData.MODE_ISLAND_HIDE)) {
                for (waitMs in listOf(20L, 40L, 70L, 120L, 250L)) {
                    delay(waitMs)
                    intent = app.packageManager.getLaunchIntentForPackage(pkg)
                    trace?.event("launcher_retry", pkg, "delayMs" to waitMs, "found" to (intent != null))
                    if (intent != null) break
                }
            }
            trace?.event("launch_ready", pkg, "launcherAvailable" to (intent != null))
            intent
        }
    }

    private fun setAppFrozen(packageName: String, frozen: Boolean, mode: String, trace: DecisionTrace?): Boolean {
        if (trace != null && !mode.endsWith(HailData.DISABLE)) {
            trace.event("backend_request", packageName, "backend" to mode, "freeze" to frozen)
            if (mode.startsWith(HailData.SHIZUKU)) HShizuku.isAuthorized(trace)
        }
        if (!frozen && mode.endsWith(HailData.STOP)) {
            trace?.skipped(packageName, "Android clears force-stop on launch")
            return false
        }
        return when (mode) {
            HailData.MODE_OWNER_HIDE -> HPolicy.setAppHidden(packageName, frozen, trace)
            HailData.MODE_DHIZUKU_HIDE -> HDhizuku.setAppHidden(packageName, frozen, trace)
            HailData.MODE_SU_STOP -> HShell.forceStopApp(packageName, trace)
            HailData.MODE_SU_DISABLE -> HShell.setAppDisabled(packageName, frozen, trace)
            HailData.MODE_SU_HIDE -> HShell.setAppHidden(packageName, frozen, trace)
            HailData.MODE_SU_SUSPEND -> HShell.setAppSuspended(packageName, frozen, trace)
            HailData.MODE_SHIZUKU_STOP -> HShizuku.forceStopApp(packageName, trace)
            HailData.MODE_SHIZUKU_HIDE -> HShizuku.setAppHidden(packageName, frozen, trace)
            HailData.MODE_SHIZUKU_SUSPEND -> HShizuku.setAppSuspended(packageName, frozen, trace)
            HailData.MODE_ISLAND_HIDE -> HIsland.setAppHidden(packageName, frozen, trace)
            HailData.MODE_ISLAND_SUSPEND -> HIsland.setAppSuspended(packageName, frozen, trace)
            HailData.MODE_PRIVAPP_STOP -> HPackages.forceStopApp(packageName, trace)
            HailData.MODE_JAILBREAK_DISABLE, HailData.MODE_PRIVAPP_DISABLE ->
                HPackages.setAppDisabled(packageName, frozen, trace)

            else -> {
                trace?.skipped(packageName, "Selected mode has no package mutation backend")
                false
            }
        }
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
