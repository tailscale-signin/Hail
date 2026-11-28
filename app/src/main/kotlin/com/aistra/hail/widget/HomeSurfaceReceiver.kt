package com.aistra.hail.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aistra.hail.utils.HLog
import com.aistra.hail.utils.HShortcuts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Rebind existing surfaces after boot, an app upgrade, or a current-profile package change. */
class HomeSurfaceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        HomeSurfaceRefresh.schedule(context.applicationContext,
            repairPins = intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED,
            completion = goAsync())
    }
}

/** A bulk freeze may send hundreds of package broadcasts. Merge them and preserve a trailing refresh. */
private object HomeSurfaceRefresh {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var job: Job? = null
    private var dirty = false
    private var repair = false
    private val completions = mutableListOf<BroadcastReceiver.PendingResult>()

    fun schedule(context: Context, repairPins: Boolean, completion: BroadcastReceiver.PendingResult) = synchronized(lock) {
        dirty = true
        repair = repair || repairPins
        completions.add(completion)
        if (job != null) return@synchronized
        job = scope.launch {
            while (true) {
                delay(250)
                val (repairNow, callbacks) = synchronized(lock) {
                    val snapshot = repair to completions.toList()
                    dirty = false
                    repair = false
                    completions.clear()
                    snapshot
                }
                try {
                    AllAppsWidget.refresh(context)
                    // Do not scan every pinned shortcut for each app in a bulk freeze operation.
                    if (repairNow) HShortcuts.refreshPinnedAppShortcuts()
                } catch (e: RuntimeException) {
                    HLog.e(e)
                } finally {
                    callbacks.forEach { it.finish() }
                }
                val done = synchronized(lock) {
                    if (dirty) false else { job = null; true }
                }
                if (done) break
            }
        }
    }
}
