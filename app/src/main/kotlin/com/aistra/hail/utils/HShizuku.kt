package com.aistra.hail.utils

import android.annotation.SuppressLint
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

    class Packages internal constructor(private val pm: Any) {
        constructor() : this(asInterface("android.content.pm.IPackageManager", "package"))
        private val flagType = if (HTarget.T) Long::class.javaPrimitiveType!! else Int::class.javaPrimitiveType!!
        // Installed for this user, including apps with disabled launcher components.
        private val flags: Any = if (HTarget.T) HPackages.INSTALLED_FOR_USER.toLong() else HPackages.INSTALLED_FOR_USER
        private val retainedFlags: Any = (HPackages.INSTALLED_FOR_USER or PackageManager.MATCH_UNINSTALLED_PACKAGES).let {
            if (HTarget.T) it.toLong() else it
        }
        private val user = HPackages.myUserId
        private val getSetting by lazy { pm.javaClass.getMethod("getApplicationEnabledSetting", String::class.java, Int::class.java) }
        private val setSetting by lazy { pm.javaClass.getMethod(
            "setApplicationEnabledSetting", String::class.java, Int::class.java, Int::class.java,
            Int::class.java, String::class.java
        ) }
        private var batchUsable = HTarget.T
        val disableSetting by lazy {
            if (isRoot) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        }

        fun setting(pkg: String) = getSetting.invoke(pm, pkg, user) as Int

        fun application(pkg: String, includeRetained: Boolean = false): ApplicationInfo? {
            val queryFlags = if (includeRetained) retainedFlags else flags
            val result = if (HTarget.P) HiddenApiBypass.invoke(pm.javaClass, pm, "getApplicationInfo", pkg, queryFlags, user)
            else pm.javaClass.getMethod("getApplicationInfo", String::class.java, flagType, Int::class.java)
                .invoke(pm, pkg, queryFlags, user)
            return result as? ApplicationInfo
        }

        fun find(pkg: String): HPackages.UserPackage? {
            val installed = application(pkg)
            val info = installed ?: application(pkg, includeRetained = true) ?: return null
            // Like upstream, retained system packages can still have a per-user enabled override.
            // Reading or changing that override does not install the package for this user.
            return HPackages.UserPackage(info,
                installed != null || info.flags and ApplicationInfo.FLAG_INSTALLED != 0, setting(pkg))
        }

        fun applications(includeRetained: Boolean = false): List<ApplicationInfo> {
            val queryFlags = if (includeRetained) retainedFlags else flags
            val entries = if (HTarget.P) {
                val slice = HiddenApiBypass.invoke(pm.javaClass, pm, "getInstalledApplications", queryFlags, user)
                HiddenApiBypass.invoke(Class.forName("android.content.pm.BaseParceledListSlice"), slice, "getList")
            } else {
                val slice = pm.javaClass.getMethod("getInstalledApplications", flagType, Int::class.java).invoke(pm, queryFlags, user)
                slice.javaClass.getMethod("getList").invoke(slice)
            }
            return (entries as List<*>).filterIsInstance<ApplicationInfo>()
        }

        fun setDisabled(packages: List<String>, disabled: Boolean, singleWrites: Set<String>, trace: DecisionTrace?,
                        onFailure: (String, Exception) -> Unit): Set<String> {
            val target = if (disabled) disableSetting else PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            // Shell-restricted starting states are submitted separately, so they cannot reject an ordinary batch.
            val group = if (batchUsable && packages.size > 1) packages.filterNot(singleWrites::contains) else emptyList()
            val attempted = if (group.size > 1) group.toHashSet() else emptySet()
            val applied = if (attempted.isNotEmpty() && HTarget.T) try {
                trace?.event("bulk_write", null, "size" to group.size, "requestedSetting" to target)
                for (pkg in group) trace?.event("android_call", pkg, "method" to "IPackageManager.setComponentEnabledSettings",
                    "requestedSetting" to target, "callingPackage" to BuildConfig.APPLICATION_ID, "user" to user)
                BatchApi.write(pm, group, target, user)
                true
            } catch (e: Exception) {
                trace?.error("bulk_write_fallback", null, e)
                if (e is NoSuchMethodException || e is IllegalAccessException) batchUsable = false
                false
            } else false

            val verified = linkedSetOf<String>()
            for (pkg in packages) {
                try {
                    // Verify the group even when Android returned normally. An OEM can ignore a write.
                    val inGroup = pkg in attempted
                    val observed = if (inGroup) runCatching { setting(pkg) }
                        .onFailure { trace?.error("bulk_readback_failed", pkg, it) }.getOrNull() else null
                    val actual = if (observed == target) {
                        if (!applied) trace?.event("write_reconciled", pkg, "observedSetting" to observed)
                        target
                    } else {
                        if (applied && inGroup) trace?.event("bulk_readback_retry", pkg,
                            "observedSetting" to observed, "requestedSetting" to target)
                        trace?.event("android_call", pkg, "method" to "IPackageManager.setApplicationEnabledSetting",
                            "requestedSetting" to target, "callingPackage" to BuildConfig.APPLICATION_ID, "user" to user)
                        setSetting.invoke(pm, pkg, target, 0, user, BuildConfig.APPLICATION_ID)
                        setting(pkg)
                    }
                    trace?.event("readback", pkg, "reader" to "Shizuku IPackageManager", "observedSetting" to actual,
                        "verified" to (actual == target))
                    if (actual == target) verified.add(pkg)
                    else onFailure(pkg, IllegalStateException("Android reported enabled setting $actual after requesting $target"))
                } catch (e: Exception) {
                    onFailure(pkg, e)
                    if (trace != null) runCatching { setting(pkg) }.onSuccess { actual ->
                        trace.event("readback", pkg, "reader" to "Shizuku IPackageManager", "observedSetting" to actual,
                            "backendAccepted" to false)
                    }.onFailure { trace.error("readback_failed", pkg, it) }
                }
            }
            return verified
        }

    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private object BatchApi {
        fun write(pm: Any, packages: List<String>, target: Int, user: Int) {
            val type = PackageManager.ComponentEnabledSetting::class.java
            val settings = packages.map { HiddenApiBypass.newInstance(type, it, target, 0) }
            HiddenApiBypass.invoke(pm.javaClass, pm, "setComponentEnabledSettings", settings, user, BuildConfig.APPLICATION_ID)
        }
    }

    fun setAppHidden(packageName: String, hidden: Boolean, trace: DecisionTrace? = null): Boolean {
        if (HPackages.getApplicationInfoOrNull(packageName) == null) {
            trace?.skipped(packageName, "Backend requires application metadata")
            return false
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
        }.also { accepted ->
            if (accepted && hidden) {
                val stopped = forceStopApp(packageName, trace)
                trace?.event("force_stop", packageName, "succeeded" to stopped)
            }
        }
    }

    fun setAppSuspended(packageName: String, suspended: Boolean, trace: DecisionTrace? = null): Boolean {
        if (HPackages.getApplicationInfoOrNull(packageName) == null) {
            trace?.skipped(packageName, "Backend requires application metadata")
            return false
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
        }.also { accepted ->
            if (accepted && suspended) {
                val stopped = forceStopApp(packageName, trace)
                trace?.event("force_stop", packageName, "succeeded" to stopped)
            }
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
