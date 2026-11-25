package com.aistra.hail.utils

import android.app.Activity
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.aistra.hail.HailApp.Companion.app
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds

object HIsland {
    const val PERMISSION_FREEZE_PACKAGE = "com.oasisfeng.island.permission.FREEZE_PACKAGE"
    const val PERMISSION_SUSPEND_PACKAGE = "com.oasisfeng.island.permission.SUSPEND_PACKAGE"
    private const val ACTION_SUSPEND = "com.oasisfeng.island.action.SUSPEND"
    private const val ACTION_UNSUSPEND = "com.oasisfeng.island.action.UNSUSPEND"
    private const val ACTION_FREEZE = "com.oasisfeng.island.action.FREEZE"
    private const val ACTION_UNFREEZE = "com.oasisfeng.island.action.UNFREEZE"
    private const val EXTRA_CALLER_ID = "caller"

    private val thread by lazy { HandlerThread("HIsland").apply { start() } }
    private val handler by lazy { Handler(thread.looper) }
    private var ownerApp: String? = null
        get() {
            field ?: checkOwnerApp()
            return field
        }

    fun checkOwnerApp() {
//        val um = app.getSystemService<UserManager>()!!
        val dpm = app.getSystemService<DevicePolicyManager>()!!
        for (pkg in arrayOf("com.oasisfeng.island", "com.oasisfeng.island.fdroid")) {
            if (dpm.isProfileOwnerApp(pkg) /* || (!um.isManagedProfile && dpm.isDeviceOwnerApp(pkg)) */) {
                ownerApp = pkg
                return
            }
        }
        ownerApp = null
    }

    fun freezePermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            app, PERMISSION_FREEZE_PACKAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun suspendPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            app, PERMISSION_SUSPEND_PACKAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun setAppFrozen(packageName: String, action: String, trace: DecisionTrace?): Boolean {
        if (ownerApp == null) {
            trace?.skipped(packageName, "Island profile owner is unavailable")
            return false
        }
        val intent = Intent(action).apply {
            data = Uri.fromParts("package", packageName, null)
            setPackage(ownerApp)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            putExtra(
                EXTRA_CALLER_ID, PendingIntent.getActivity(app, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
            )
        }
        val result = CompletableDeferred<Boolean>()
        app.sendOrderedBroadcast(
            intent, null, object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (resultCode != Activity.RESULT_OK) {
                        HLog.i("HIsland", resultData)
                    }
                    trace?.event("island_response", packageName, "resultCode" to resultCode)
                    result.complete(resultCode == Activity.RESULT_OK)
                }
            }, handler, Activity.RESULT_OK, null, null
        )
        return runBlocking {
            runCatching {
                withTimeout(500.milliseconds) { result.await() }
            }.onFailure { trace?.error("island_response_failed", packageName, it) }.getOrDefault(false)
        }
    }

    fun setAppHidden(packageName: String, hidden: Boolean, trace: DecisionTrace? = null): Boolean =
        HTarget.N && setAppFrozen(packageName, if (hidden) ACTION_FREEZE else ACTION_UNFREEZE, trace)

    fun setAppSuspended(packageName: String, suspended: Boolean, trace: DecisionTrace? = null): Boolean =
        HTarget.N && setAppFrozen(packageName, if (suspended) ACTION_SUSPEND else ACTION_UNSUSPEND, trace)
}
