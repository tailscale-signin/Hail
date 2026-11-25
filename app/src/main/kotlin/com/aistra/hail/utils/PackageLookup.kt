package com.aistra.hail.utils

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
            runCatching { service.installed() }.onFailure { trace?.error("binder_inventory_failed", null, it) }.getOrNull()
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

    fun all(): Map<String, UserPackage> = inventory

    /** A complete privileged inventory plus retained metadata can settle absence without probing each package again. */
    fun fromInventory(pkg: String, forChange: Boolean = false): UserPackage? {
        val record = inventory[pkg]
        if (record == null || (!record.installed && serviceInventory == null && root == null)) return find(pkg)
        trace?.event("lookup", pkg, "reader" to "current-user batch inventory", "installed" to record.installed)
        val service = shizuku
        if (!forChange || !record.installed || service == null) return record
        val setting = runCatching { service.setting(pkg) }.onFailure { trace?.error("binder_setting_failed", pkg, it) }.getOrNull()
        return record.copy(enabledSetting = setting)
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
