package com.aistra.hail.utils

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import androidx.core.content.getSystemService
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.receiver.DeviceAdminReceiver

object HPolicy {
    private val dpm = app.getSystemService<DevicePolicyManager>()!!
    private val admin = ComponentName(app, DeviceAdminReceiver::class.java)
    private val DPM_COMMAND = "dpm set-device-owner ${admin.flattenToShortString()}"
    val ADB_COMMAND = "adb shell $DPM_COMMAND"

    private val isDeviceOwner get() = dpm.isDeviceOwnerApp(app.packageName)
    val isProfileOwner get() = dpm.isProfileOwnerApp(app.packageName)
    val isAdminActive get() = dpm.isAdminActive(admin)
    val isDeviceOwnerActive get() = isDeviceOwner && isAdminActive

    val lockScreen get() = isAdminActive.also { if (it) dpm.lockNow() }

    fun setAppHidden(packageName: String, hidden: Boolean, trace: DecisionTrace? = null): Boolean {
        val active = isDeviceOwnerActive
        trace?.event("permission", packageName, "deviceOwnerActive" to active)
        return active && dpm.setApplicationHidden(admin, packageName, hidden)
    }

    fun setSuspended(packages: List<String>, suspended: Boolean, trace: DecisionTrace? = null): Set<String> = try {
        val permitted = HTarget.N && isDeviceOwnerActive
        trace?.event("policy_check", null, "deviceOwnerActiveAndSupported" to permitted)
        if (permitted) {
            packages.toSet() - dpm.setPackagesSuspended(admin, packages.toTypedArray(), suspended).toSet()
        } else emptySet()
    } catch (e: RuntimeException) {
        trace?.error("android_error", null, e)
        HLog.e(e)
        emptySet()
    }

    fun uninstallApp(packageName: String): Boolean = when {
        isDeviceOwnerActive -> {
            app.packageManager.packageInstaller.uninstall(
                packageName, PendingIntent.getActivity(
                    app, 0, Intent(), PendingIntent.FLAG_IMMUTABLE
                ).intentSender
            )
            true
        }

        else -> false
    }

    fun enableBackupService() {
        if (isDeviceOwnerActive && HTarget.O && !dpm.isBackupServiceEnabled(admin)) dpm.setBackupServiceEnabled(
            admin, true
        )
    }

    fun setOrganizationName(name: String? = null) {
        if (isDeviceOwnerActive && HTarget.O) dpm.setOrganizationName(admin, name)
    }

    fun removeActiveAdmin() {
        if (isAdminActive) dpm.removeActiveAdmin(admin)
    }

    @Suppress("DEPRECATION")
    fun removeProfileOwner() {
        if (!isProfileOwner) return
        if (HTarget.N) dpm.clearProfileOwner(admin) else {
            val settings = Intent(android.provider.Settings.ACTION_SYNC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (settings.resolveActivity(app.packageManager) != null) app.startActivity(settings)
            else HUI.showToast(com.aistra.hail.R.string.activity_not_found)
        }
    }

    @Suppress("DEPRECATION")
    fun removeDeviceOwner() {
        if (isDeviceOwnerActive) dpm.clearDeviceOwnerApp(app.packageName)
    }
}