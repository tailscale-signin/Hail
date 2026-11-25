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
        HailData.MODE_SHIZUKU_DISABLE -> runCatching { shizuku?.disableSetting }.getOrNull()
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

    fun all(): Map<String, UserPackage> {
        val service = shizuku
        val roots = root
        if (service == null && roots == null) return local
        return local.toMutableMap().apply {
            if (service != null) {
                runCatching { service.installed() }.getOrNull()?.forEach { info ->
                    put(info.packageName, UserPackage(info, true))
                }
            }
            roots?.forEach { (pkg, enabled) -> put(pkg, rootRecord(pkg, get(pkg), enabled)) }
        }
    }

    private fun rootRecord(pkg: String, local: UserPackage?, enabled: Boolean): UserPackage {
        val setting = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else when (val observed = HPackages.currentEnabledSetting(pkg)) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> observed
            else -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        return UserPackage(local?.info, true, setting)
    }
}
