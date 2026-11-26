package com.aistra.hail.utils

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.aistra.hail.app.HailData
import com.aistra.hail.utils.HPackages.UserPackage

/** A current-user package view scoped to one refresh or batch. No state survives a mutation. */
class PackageLookup(private val mode: String, private val trace: DecisionTrace? = null) {
    private val local by lazy { HPackages.packagesForUser() }
    val shizuku by lazy {
        if (mode == HailData.MODE_SHIZUKU_DISABLE && HShizuku.isAuthorized(trace))
            runCatching { HShizuku.Packages() }.onFailure { trace?.error("binder_open_failed", null, it) }.getOrNull() else null
    }
    private val root by lazy { if (mode == HailData.MODE_SU_DISABLE) HShell.installedEnabledStates(trace) else null }
    val disableSetting: Int? get() = when (mode) {
        HailData.MODE_SHIZUKU_DISABLE -> runCatching { shizuku?.disableSetting }.onFailure { trace?.error("read_disable_setting_failed", null, it) }.getOrNull()
        HailData.MODE_SU_DISABLE, HailData.MODE_PRIVAPP_DISABLE, HailData.MODE_JAILBREAK_DISABLE ->
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        else -> null
    }

    fun find(pkg: String): UserPackage? {
        shizuku?.let { service ->
            val result = runCatching { service.find(pkg) }.onFailure { trace?.error("binder_lookup_failed", pkg, it) }.getOrNull()
            trace?.event("lookup", pkg, "reader" to "Shizuku IPackageManager", "found" to (result != null))
            if (result != null) return result
        }
        val record = HPackages.findForUser(pkg, trace) { local[it] }
        return root?.get(pkg)?.let {
            trace?.event("lookup", pkg, "reader" to "root pm list packages", "found" to true, "enabled" to it)
            rootRecord(pkg, record, it)
        } ?: record
    }

    private val serviceInventory by lazy {
        shizuku?.let { service ->
            runCatching { service.applications() }.onFailure { trace?.error("binder_inventory_failed", null, it) }.getOrNull()
        }
    }

    private val inventory by lazy {
        val installed = serviceInventory
        val roots = root
        trace?.event("inventory", null, "reader" to "PackageManager", "entries" to local.size)
        if (shizuku != null) trace?.event("inventory", null, "reader" to "Shizuku IPackageManager", "entries" to installed?.size)
        if (installed == null && roots == null) local else local.toMutableMap().apply {
            installed?.forEach { put(it.packageName, UserPackage(it, true)) }
            roots?.forEach { (pkg, enabled) -> put(pkg, rootRecord(pkg, get(pkg), enabled)) }
        }
    }

    fun all(includeRetainedSystem: Boolean = false): Map<String, UserPackage> {
        val known = inventory
        if (!includeRetainedSystem || serviceInventory == null) return known
        // Some preinstalled packages are visible only to the authorized service. Keep the
        // installed-only inventory authoritative when the same package is in both queries.
        val entries = runCatching { shizuku?.applications(includeRetained = true) }
            .onFailure { trace?.error("retained_inventory_failed", null, it) }.getOrNull() ?: return known
        return known.toMutableMap().apply {
            for (info in entries) {
                if (info.packageName !in this && HPackages.isSystemApp(info)) {
                    put(info.packageName, UserPackage(info, installed = false))
                }
            }
        }
    }

    /** A complete privileged inventory plus retained metadata can settle absence without probing each package again. */
    fun fromInventory(pkg: String, forChange: Boolean = false): UserPackage? {
        val record = inventory[pkg]
        if (record == null || (!record.installed && serviceInventory == null && root == null)) return find(pkg)
        trace?.event("lookup", pkg, "reader" to "current-user batch inventory", "installed" to record.installed)
        val service = shizuku
        if (!forChange || service == null) return record
        if (!record.installed) return find(pkg) ?: record
        val setting = runCatching { service.setting(pkg) }.onFailure { trace?.error("binder_setting_failed", pkg, it) }.getOrNull()
        return record.copy(enabledSetting = setting)
    }

    data class Verification(val repaired: Set<String> = emptySet(), val unconfirmed: Set<String> = emptySet(),
                          val retained: Set<String> = emptySet())

    /** Read fresh flags after the write, including when an earlier request already disabled the app. */
    fun verifyDisabled(packages: Set<String>, retainedSettings: Set<String> = emptySet()): Verification {
        if (packages.isEmpty()) return Verification()
        val service = shizuku
        val snapshot = if (packages.size < 8) null else runCatching {
            (service?.applications() ?: HPackages.getInstalledApplications(HPackages.INSTALLED_FOR_USER))
                .associateBy { it.packageName }
        }.onFailure { trace?.error("stop_inventory_failed", null, it) }.getOrNull()

        fun read(pkg: String): ApplicationInfo? = runCatching {
            service?.application(pkg)
        }.onFailure { trace?.error("stop_lookup_failed", pkg, it) }.getOrNull()
            ?: HPackages.getApplicationInfoOrNull(pkg, HPackages.INSTALLED_FOR_USER)

        val repaired = mutableSetOf<String>()
        val unconfirmed = mutableSetOf<String>()
        val confirmedRetained = mutableSetOf<String>()
        for (pkg in packages) {
            val retained = if (pkg in retainedSettings) runCatching { service?.find(pkg) }
                .onFailure { trace?.error("retained_readback_failed", pkg, it) }.getOrNull() else null
            if (pkg in retainedSettings && retained?.installed != true) {
                val confirmed = retained != null && (retained.enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                    retained.enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)
                trace?.event("retained_setting_readback", pkg, "installed" to retained?.installed,
                    "observedSetting" to retained?.enabledSetting, "verified" to confirmed)
                // There is no installed app to stop. Never reinstall it to satisfy a stopped-flag check.
                if (confirmed) confirmedRetained.add(pkg) else unconfirmed.add(pkg)
                continue
            }
            val observed = disableObservation(retained?.info ?: snapshot?.get(pkg) ?: read(pkg))
            val verified = observed.confirmStopped(
                stop = {
                    val accepted = when (mode) {
                        HailData.MODE_SHIZUKU_DISABLE -> service != null && HShizuku.forceStopApp(pkg, trace)
                        HailData.MODE_SU_DISABLE -> HShell.forceStopApp(pkg, trace)
                        else -> HPackages.forceStopApp(pkg, trace)
                    }
                    trace?.event("force_stop", pkg, "succeeded" to accepted)
                },
                observe = { disableObservation(read(pkg)) }
            )
            if (observed == DisableObservation.STOPPED) {
                trace?.event("force_stop_skipped", pkg, "reason" to "Android already reports the package stopped")
            }
            trace?.event("disable_privacy", pkg, "disabledAndStopped" to verified)
            if (!verified) unconfirmed.add(pkg)
            else if (observed == DisableObservation.DISABLED) repaired.add(pkg)
        }
        return Verification(repaired, unconfirmed, confirmedRetained)
    }

    fun verifyRetainedEnabled(packages: Set<String>): Verification {
        val retained = mutableSetOf<String>()
        val unconfirmed = mutableSetOf<String>()
        for (pkg in packages) {
            val record = runCatching { shizuku?.find(pkg) }
                .onFailure { trace?.error("retained_readback_failed", pkg, it) }.getOrNull()
            val verified = record?.enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            trace?.event("retained_setting_readback", pkg, "installed" to record?.installed,
                "observedSetting" to record?.enabledSetting, "verified" to verified)
            if (!verified) unconfirmed.add(pkg)
            else if (record?.installed == false) retained.add(pkg)
        }
        return Verification(unconfirmed = unconfirmed, retained = retained)
    }

    internal fun disableObservation(info: ApplicationInfo?) = when {
        // These records come from installed-only queries. Some OEMs omit FLAG_INSTALLED;
        // the query's authoritative user scope must not be contradicted by that metadata bit.
        info == null || info.enabled -> DisableObservation.UNKNOWN
        info.flags and ApplicationInfo.FLAG_STOPPED != 0 -> DisableObservation.STOPPED
        else -> DisableObservation.DISABLED
    }

    private fun rootRecord(pkg: String, local: UserPackage?, enabled: Boolean): UserPackage {
        val observed = if (enabled) null else HPackages.currentEnabledSetting(pkg)
        val exact = observed == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
            observed == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER || observed == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
        val setting = when {
            enabled -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            exact -> observed
            else -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        return UserPackage(local?.info, true, setting, inferredSetting = !exact)
    }
}
