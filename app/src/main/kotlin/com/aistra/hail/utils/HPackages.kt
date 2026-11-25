package com.aistra.hail.utils

import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import com.aistra.hail.HailApp.Companion.app
import org.lsposed.hiddenapibypass.HiddenApiBypass

object HPackages {
    @Suppress("DEPRECATION")
    private const val INCLUDE_UNINSTALLED = PackageManager.GET_UNINSTALLED_PACKAGES
    @Suppress("DEPRECATION")
    internal const val INSTALLED_FOR_USER = PackageManager.GET_DISABLED_COMPONENTS or PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS

    data class UserPackage(val info: ApplicationInfo?, val installed: Boolean, val enabledSetting: Int? = null,
                           val inferredSetting: Boolean = false) {
        fun setting() = enabledSetting ?: info?.let { currentEnabledSetting(it.packageName) }

        fun canDisable(setting: Int? = setting()): Boolean = when (setting) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> false
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> true
            else -> info?.enabled == true
        }
    }

    private fun knownPackage(info: ApplicationInfo) = UserPackage(info, info.flags and ApplicationInfo.FLAG_INSTALLED != 0)

    fun findForUser(pkg: String, trace: DecisionTrace? = null, listed: (String) -> UserPackage? = { packagesForUser()[it] }): UserPackage? {
        val direct = getApplicationInfoOrNull(pkg, INSTALLED_FOR_USER)
        trace?.event("lookup", pkg, "reader" to "PackageManager installed", "found" to (direct != null))
        if (direct != null) return UserPackage(direct, true)
        if (HTarget.O) {
            val installed = runCatching { app.getSystemService<LauncherApps>()?.getApplicationInfo(
                pkg, INSTALLED_FOR_USER, android.os.Process.myUserHandle()) }.getOrNull()
            trace?.event("lookup", pkg, "reader" to "LauncherApps", "found" to (installed != null))
            if (installed != null) return UserPackage(installed, true)
        }
        val inventory = listed(pkg)
        trace?.event("lookup", pkg, "reader" to "current-user inventory", "found" to (inventory != null))
        if (inventory != null) return inventory
        return getApplicationInfoOrNull(pkg)?.let(::knownPackage).also {
            trace?.event("lookup", pkg, "reader" to "retained package metadata", "found" to (it != null))
        }
    }

    /** One installed-only inventory for the calling profile, including disabled apps, plus retained package metadata. */
    fun packagesForUser(): Map<String, UserPackage> = buildMap {
        for (info in getInstalledApplications()) put(info.packageName, knownPackage(info))
        for (info in getInstalledApplications(INSTALLED_FOR_USER)) put(info.packageName, UserPackage(info, true))
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private object PackageFlags {
        val applications = PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong())
        val packages = PackageManager.PackageInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong())
    }

    val myUserId get() = android.os.Process.myUserHandle().hashCode()

    fun packageUri(packageName: String) = "package:$packageName"

    @RequiresApi(Build.VERSION_CODES.N)
    fun packageUid(packageName: String) = if (HTarget.T) app.packageManager.getPackageUid(
        packageName, PackageFlags.packages
    ) else app.packageManager.getPackageUid(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)

    fun getInstalledApplications(flags: Int = INCLUDE_UNINSTALLED): List<ApplicationInfo> =
        if (HTarget.T && flags == INCLUDE_UNINSTALLED) app.packageManager.getInstalledApplications(PackageFlags.applications)
        else app.packageManager.getInstalledApplications(flags)

    fun getUnhiddenPackageInfoOrNull(
        packageName: String, flags: Int = INCLUDE_UNINSTALLED
    ) = runCatching {
        if (HTarget.T && flags == INCLUDE_UNINSTALLED) app.packageManager.getPackageInfo(packageName, PackageFlags.packages)
        else app.packageManager.getPackageInfo(packageName, flags)
    }.getOrNull()

    fun getApplicationInfoOrNull(
        packageName: String, flags: Int = INCLUDE_UNINSTALLED
    ) = runCatching {
        if (HTarget.T && flags == INCLUDE_UNINSTALLED) app.packageManager.getApplicationInfo(packageName, PackageFlags.applications)
        else app.packageManager.getApplicationInfo(packageName, flags)
    }.getOrNull()

    fun isSystemApp(info: ApplicationInfo): Boolean =
        info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

    fun currentEnabledSetting(pkg: String): Int? =
        runCatching { app.packageManager.getApplicationEnabledSetting(pkg) }.getOrNull()

    private val privateFlags by lazy { runCatching { ApplicationInfo::class.java.getField("privateFlags") }.getOrNull() }
    fun isHidden(info: ApplicationInfo): Boolean = runCatching { (privateFlags?.getInt(info) ?: 0) and 1 != 0 }.getOrDefault(false)
    fun isAppHidden(packageName: String) = getApplicationInfoOrNull(packageName)?.let(::isHidden) == true

    fun isAppStopped(packageName: String): Boolean =
        getApplicationInfoOrNull(packageName)?.run { flags and ApplicationInfo.FLAG_STOPPED == ApplicationInfo.FLAG_STOPPED }
            ?: false

    fun isAppSuspended(packageName: String): Boolean = getApplicationInfoOrNull(packageName)?.let {
        when {
//            This method will cause NameNotFoundException with uninstalled packages
//            HTarget.Q -> app.packageManager.isPackageSuspended(packageName)
            HTarget.N -> it.flags and ApplicationInfo.FLAG_SUSPENDED == ApplicationInfo.FLAG_SUSPENDED
            else -> false
        }
    } ?: false

    fun isAppUninstalled(packageName: String): Boolean = findForUser(packageName)?.installed != true

    fun canUninstallNormally(packageName: String): Boolean =
        getApplicationInfoOrNull(packageName)?.sourceDir?.startsWith("/data") ?: false

    fun forceStopApp(packageName: String, trace: DecisionTrace? = null): Boolean = runCatching {
        app.getSystemService<ActivityManager>()!!.let {
            if (HTarget.P) HiddenApiBypass.invoke(it::class.java, it, "forceStopPackage", packageName)
            else it::class.java.getMethod("forceStopPackage", String::class.java).invoke(it, packageName)
        }
        true
    }.getOrElse {
        trace?.error("force_stop_failed", packageName, it)
        HLog.e(it)
        false
    }

    fun setAppDisabled(packageName: String, disabled: Boolean, trace: DecisionTrace? = null): Boolean {
        val newState = if (disabled) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        trace?.event("permission", packageName, "appUid" to android.os.Process.myUid(),
            "permission" to "CHANGE_COMPONENT_ENABLED_STATE", "granted" to
                (app.checkSelfPermission("android.permission.CHANGE_COMPONENT_ENABLED_STATE") == PackageManager.PERMISSION_GRANTED))
        trace?.event("android_call", packageName, "method" to "PackageManager.setApplicationEnabledSetting", "requestedSetting" to newState)
        app.packageManager.setApplicationEnabledSetting(packageName, newState, 0)
        val actual = app.packageManager.getApplicationEnabledSetting(packageName)
        trace?.event("readback", packageName, "reader" to "PackageManager", "observedSetting" to actual, "verified" to (actual == newState))
        if (actual != newState) return false
        if (disabled && app.checkSelfPermission("android.permission.FORCE_STOP_PACKAGES") == PackageManager.PERMISSION_GRANTED) {
            val info = getApplicationInfoOrNull(packageName, INSTALLED_FOR_USER)
            if (info != null && info.flags and ApplicationInfo.FLAG_STOPPED != 0) {
                trace?.event("force_stop_skipped", packageName, "reason" to "Android already reports the package stopped")
            } else {
                val stopped = forceStopApp(packageName, trace)
                trace?.event("force_stop", packageName, "succeeded" to stopped)
            }
        } else if (disabled) {
            trace?.event("force_stop_skipped", packageName, "reason" to "FORCE_STOP_PACKAGES permission unavailable")
        }
        return true
    }

    @RequiresApi(Build.VERSION_CODES.P)
    fun setAppRestricted(packageName: String, restricted: Boolean): Boolean = runCatching {
        app.getSystemService<AppOpsManager>()!!.let {
            HiddenApiBypass.invoke(
                it::class.java,
                it,
                "setMode",
                "android:run_any_in_background",
                packageUid(packageName),
                packageName,
                if (restricted) AppOpsManager.MODE_IGNORED else AppOpsManager.MODE_ALLOWED
            )
        }
        true
    }.getOrElse {
        HLog.e(it)
        false
    }
}
