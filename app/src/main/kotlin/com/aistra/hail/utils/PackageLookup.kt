package com.aistra.hail.utils

import android.content.pm.PackageManager
import com.aistra.hail.app.HailData
import com.aistra.hail.utils.HPackages.UserPackage

/** A current-user package view scoped to one refresh or batch. No state survives a mutation. */
class PackageLookup(private val mode: String) {
    private val local by lazy { HPackages.packagesForUser() }
    val shizuku by lazy {
        if (mode == HailData.MODE_SHIZUKU_DISABLE && HShizuku.isAuthorized())
            runCatching { HShizuku.Packages() }.getOrNull() else null
    }
    private val root by lazy { if (mode == HailData.MODE_SU_DISABLE) HShell.installedEnabledStates() else null }

    fun find(pkg: String): UserPackage? {
        shizuku?.let { service -> runCatching { service.find(pkg) }.getOrNull()?.let { return it } }
        val record = HPackages.findForUser(pkg) { local[it] }
        return root?.get(pkg)?.let { rootRecord(pkg, record, it) } ?: record
    }

    fun all(): Map<String, UserPackage> {
        val service = shizuku
        val roots = root
        if (service == null && roots == null) return local
        return local.toMutableMap().apply {
            service?.let { runCatching { it.installed() }.getOrNull() }?.forEach { info ->
                put(info.packageName, UserPackage(info, true))
            }
            roots?.forEach { (pkg, enabled) -> put(pkg, rootRecord(pkg, get(pkg), enabled)) }
        }
    }

    private fun rootRecord(pkg: String, local: UserPackage?, enabled: Boolean): UserPackage {
        val setting = when {
            enabled -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            HPackages.currentEnabledSetting(pkg) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED ->
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
            else -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        return UserPackage(local?.info, true, setting)
    }
}
