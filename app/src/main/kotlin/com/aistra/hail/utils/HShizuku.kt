package com.aistra.hail.utils

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputEvent
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import com.aistra.hail.BuildConfig
import moe.shizuku.server.IShizukuService
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

object HShizuku {
    val isRoot get() = Shizuku.getUid() == 0
    private val callerPackage get() = if (isRoot) BuildConfig.APPLICATION_ID else "com.android.shell"

    private fun asInterface(className: String, original: IBinder): Any = Class.forName("$className\$Stub").run {
        if (HTarget.P) HiddenApiBypass.invoke(this, null, "asInterface", ShizukuBinderWrapper(original))
        else getMethod("asInterface", IBinder::class.java).invoke(null, ShizukuBinderWrapper(original))
    }

    private fun asInterface(className: String, serviceName: String): Any =
        asInterface(className, SystemServiceHelper.getSystemService(serviceName))

    val lockScreen
        get() = runCatching {
            val input = asInterface("android.hardware.input.IInputManager", Context.INPUT_SERVICE)
            val inject = input::class.java.getMethod(
                "injectInputEvent", InputEvent::class.java, Int::class.java
            )
            val now = SystemClock.uptimeMillis()
            inject.invoke(
                input, KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_POWER, 0), 0
            )
            inject.invoke(
                input, KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_POWER, 0), 0
            )
            true
        }.getOrElse {
            HLog.e(it)
            false
        }

    fun forceStopApp(packageName: String, trace: DecisionTrace? = null): Boolean = runCatching {
        asInterface("android.app.IActivityManager", Context.ACTIVITY_SERVICE).let {
            if (HTarget.P) HiddenApiBypass.invoke(
                it::class.java, it, "forceStopPackage", packageName, HPackages.myUserId
            ) else it::class.java.getMethod(
                "forceStopPackage", String::class.java, Int::class.java
            ).invoke(
                it, packageName, HPackages.myUserId
            )
        }
        true
    }.getOrElse {
        trace?.error("force_stop_failed", packageName, it)
        HLog.e(it)
        false
    }

    fun isAuthorized(trace: DecisionTrace? = null) = runCatching {
        val connected = Shizuku.pingBinder()
        val granted = connected && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        trace?.event("permission", null, "service" to "Shizuku", "binderAlive" to connected,
            "granted" to granted, "serviceUid" to if (granted) runCatching { Shizuku.getUid() }.getOrNull() else null)
        granted
    }.onFailure { trace?.error("permission_check_failed", null, it) }.getOrDefault(false)

    class Packages {
        private val pm = asInterface("android.content.pm.IPackageManager", "package")
        private val flagType = if (HTarget.T) Long::class.javaPrimitiveType!! else Int::class.javaPrimitiveType!!
        // Installed for this user, including apps with disabled launcher components.
        private val flags: Any = if (HTarget.T) HPackages.INSTALLED_FOR_USER.toLong() else HPackages.INSTALLED_FOR_USER
        private val user = HPackages.myUserId
        private val getSetting by lazy { pm.javaClass.getMethod("getApplicationEnabledSetting", String::class.java, Int::class.java) }
        val disableSetting by lazy {
            if (isRoot) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        }

        fun setting(pkg: String) = getSetting.invoke(pm, pkg, user) as Int

        fun find(pkg: String): HPackages.UserPackage? {
            val result = if (HTarget.P) HiddenApiBypass.invoke(pm.javaClass, pm, "getApplicationInfo", pkg, flags, user)
            else pm.javaClass.getMethod("getApplicationInfo", String::class.java, flagType, Int::class.java)
                .invoke(pm, pkg, flags, user)
            val info = result as? ApplicationInfo ?: return null
            return HPackages.UserPackage(info, true, setting(pkg))
        }

        fun installed(): List<ApplicationInfo> {
            val entries = if (HTarget.P) {
                val slice = HiddenApiBypass.invoke(pm.javaClass, pm, "getInstalledApplications", flags, user)
                HiddenApiBypass.invoke(Class.forName("android.content.pm.BaseParceledListSlice"), slice, "getList")
            } else {
                val slice = pm.javaClass.getMethod("getInstalledApplications", flagType, Int::class.java).invoke(pm, flags, user)
                slice.javaClass.getMethod("getList").invoke(slice)
            }
            return (entries as List<*>).filterIsInstance<ApplicationInfo>()
        }

        fun setDisabled(packageName: String, disabled: Boolean, trace: DecisionTrace? = null): Boolean {
            val newState = if (disabled) disableSetting else PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            if (disabled) {
                val stopped = forceStopApp(packageName, trace)
                trace?.event("force_stop", packageName, "succeeded" to stopped)
            }
            trace?.event("android_call", packageName, "method" to "IPackageManager.setApplicationEnabledSetting",
                "requestedSetting" to newState, "callingPackage" to BuildConfig.APPLICATION_ID, "user" to user)
            pm.javaClass.getMethod(
                "setApplicationEnabledSetting", String::class.java, Int::class.java, Int::class.java,
                Int::class.java, String::class.java
            ).invoke(pm, packageName, newState, 0, user, BuildConfig.APPLICATION_ID)
            val actual = setting(packageName)
            trace?.event("readback", packageName, "reader" to "Shizuku IPackageManager", "observedSetting" to actual,
                "verified" to (actual == newState))
            return actual == newState
        }
    }

    fun setAppHidden(packageName: String, hidden: Boolean, trace: DecisionTrace? = null): Boolean {
        if (HPackages.getApplicationInfoOrNull(packageName) == null) {
            trace?.skipped(packageName, "Backend requires application metadata")
            return false
        }
        if (hidden) {
            val stopped = forceStopApp(packageName, trace)
            trace?.event("force_stop", packageName, "succeeded" to stopped)
        }
        return runCatching {
            val pm = asInterface("android.content.pm.IPackageManager", "package")
            pm::class.java.getMethod(
                "setApplicationHiddenSettingAsUser", String::class.java, Boolean::class.java, Int::class.java
            ).invoke(pm, packageName, hidden, HPackages.myUserId) as Boolean
        }.getOrElse {
            trace?.error("android_error", packageName, it)
            HLog.e(it)
            false
        }
    }

    fun setAppSuspended(packageName: String, suspended: Boolean, trace: DecisionTrace? = null): Boolean {
        if (HPackages.getApplicationInfoOrNull(packageName) == null) {
            trace?.skipped(packageName, "Backend requires application metadata")
            return false
        }
        if (HTarget.P) {
            val restricted = setAppRestricted(packageName, suspended)
            trace?.event("background_restriction", packageName, "requested" to suspended, "accepted" to restricted)
        }
        if (suspended) {
            val stopped = forceStopApp(packageName, trace)
            trace?.event("force_stop", packageName, "succeeded" to stopped)
        }
        return runCatching {
            val pm = asInterface("android.content.pm.IPackageManager", "package")
            (when {
                HTarget.U -> runCatching {
                    HiddenApiBypass.invoke(
                        pm::class.java,
                        pm,
                        "setPackagesSuspendedAsUser",
                        arrayOf(packageName),
                        suspended,
                        null,
                        null,
                        if (suspended) suspendDialogInfo else null,
                        0,
                        callerPackage,
                        HPackages.myUserId /*suspendingUserId*/,
                        HPackages.myUserId /*targetUserId*/
                    )
                }.getOrElse {
                    if (it is NoSuchMethodException) setPackagesSuspendedAsUserSinceQ(pm, packageName, suspended)
                    else throw it
                }

                HTarget.Q -> runCatching {
                    setPackagesSuspendedAsUserSinceQ(pm, packageName, suspended)
                }.getOrElse {
                    if (it is NoSuchMethodException) setPackagesSuspendedAsUserSinceP(pm, packageName, suspended)
                    else throw it
                }

                HTarget.P -> setPackagesSuspendedAsUserSinceP(pm, packageName, suspended)

                HTarget.N -> pm::class.java.getMethod(
                    "setPackagesSuspendedAsUser", Array<String>::class.java, Boolean::class.java, Int::class.java
                ).invoke(pm, arrayOf(packageName), suspended, HPackages.myUserId)

                else -> return false
            } as Array<*>).isEmpty()
        }.getOrElse {
            trace?.error("android_error", packageName, it)
            HLog.e(it)
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun setPackagesSuspendedAsUserSinceQ(pm: Any, packageName: String, suspended: Boolean): Any =
        HiddenApiBypass.invoke(
            pm::class.java,
            pm,
            "setPackagesSuspendedAsUser",
            arrayOf(packageName),
            suspended,
            null,
            null,
            if (suspended) suspendDialogInfo else null,
            callerPackage,
            HPackages.myUserId
        )

    @RequiresApi(Build.VERSION_CODES.P)
    private fun setPackagesSuspendedAsUserSinceP(pm: Any, packageName: String, suspended: Boolean): Any =
        HiddenApiBypass.invoke(
            pm::class.java,
            pm,
            "setPackagesSuspendedAsUser",
            arrayOf(packageName),
            suspended,
            null,
            null,
            null /*dialogMessage*/,
            callerPackage,
            HPackages.myUserId
        )

    private val suspendDialogInfo: Any
        @RequiresApi(Build.VERSION_CODES.Q) @SuppressLint("PrivateApi") get() = HiddenApiBypass.newInstance(
            Class.forName("android.content.pm.SuspendDialogInfo\$Builder")
        ).let {
            HiddenApiBypass.invoke(it::class.java, it, "setNeutralButtonAction", 1 /*BUTTON_ACTION_UNSUSPEND*/)
            HiddenApiBypass.invoke(it::class.java, it, "build")
        }

    @RequiresApi(Build.VERSION_CODES.P)
    fun setAppRestricted(packageName: String, restricted: Boolean): Boolean = runCatching {
        val appops = asInterface("com.android.internal.app.IAppOpsService", Context.APP_OPS_SERVICE)
        HiddenApiBypass.invoke(
            appops::class.java,
            appops,
            "setMode",
            HiddenApiBypass.invoke(AppOpsManager::class.java, null, "strOpToOp", "android:run_any_in_background"),
            HPackages.packageUid(packageName),
            packageName,
            if (restricted) AppOpsManager.MODE_IGNORED else AppOpsManager.MODE_ALLOWED
        )
        true
    }.getOrElse {
        HLog.e(it)
        false
    }

    fun uninstallApp(packageName: String): Boolean =
        execute("pm ${if (HPackages.canUninstallNormally(packageName)) "uninstall" else "uninstall --user current"} $packageName").first == 0

    fun reinstallApp(packageName: String): Boolean =
        execute("pm install-existing --user current $packageName").first == 0

    fun execute(command: String, root: Boolean = isRoot): Pair<Int, String?> = runCatching {
        IShizukuService.Stub.asInterface(Shizuku.getBinder()).newProcess(arrayOf(if (root) "su" else "sh"), null, null)
            .run {
                ParcelFileDescriptor.AutoCloseOutputStream(outputStream).use {
                    it.write(command.toByteArray())
                }
                waitFor() to inputStream.text.ifBlank { errorStream.text }.also { destroy() }
            }
    }.getOrElse { 1 to it.stackTraceToString() }

    private val ParcelFileDescriptor.text
        get() = ParcelFileDescriptor.AutoCloseInputStream(this).bufferedReader().use { it.readText() }
}
