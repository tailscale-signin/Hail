package com.aistra.hail.shortcut

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.app.KeyguardManager
import android.net.Uri
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aistra.hail.R
import com.aistra.hail.utils.HLog
import com.aistra.hail.utils.DecisionLog
import com.aistra.hail.utils.HTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Exists only for a user-started batch, with a persistent progress/cancel notification. */
class ShortcutBatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commands = Mutex()
    private var runner: Job? = null
    private var commandGeneration = 0L
    private var registered = false
    private var screenLease: PowerManager.WakeLock? = null
    private var leasedRequest: String? = null
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            ShortcutBatchController.pause(getString(R.string.individual_batch_locked))
        }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = NotificationManagerCompat.from(this)
        manager.createNotificationChannel(NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.individual_batch_notification_channel)).build())
        ServiceCompat.startForeground(this, NOTIFICATION, notification(ShortcutBatchController.state.value),
            if (HTarget.U) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        ContextCompat.registerReceiver(this, lockReceiver,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_BACKGROUND) },
            ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        scope.launch {
            ShortcutBatchController.state.collect { current ->
                updateScreenLease(current)
                if (current.active) runCatching { manager.notify(NOTIFICATION, notification(current)) }.onFailure(HLog::e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Invalidate an exiting runner immediately, before this command waits for the mutex.
        val generation = ++commandGeneration
        scope.launch {
            commands.withLock {
                try {
                    runner?.cancelAndJoin()
                    runner = null
                    ShortcutBatchController.initialize(this@ShortcutBatchService)
                    when (intent?.action) {
                        START -> ShortcutBatchController.begin(intent.getStringArrayListExtra(PACKAGES).orEmpty(),
                            intent.getBooleanExtra(AUTOMATIC, true))
                        RESUME -> ShortcutBatchController.continueBatch()
                        RETRY -> ShortcutBatchController.continueBatch(retry = true)
                        SKIP -> ShortcutBatchController.continueBatch(skip = true)
                        MANUAL_RESUME -> ShortcutBatchController.continueBatch(manual = true)
                        else -> ShortcutBatchController.pause(getString(R.string.individual_batch_interrupted))
                    }
                    // A later queued command will own the successor runner. A duplicate Start
                    // keeps the active/paused queue and its current request instead of replacing it.
                    if (generation == commandGeneration) runner = scope.launch {
                        try { ShortcutBatchController.runQueue() }
                        finally {
                            if (generation == commandGeneration && stopSelfResult(startId)) {
                                ServiceCompat.stopForeground(this@ShortcutBatchService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    HLog.e(e)
                    ShortcutBatchController.showMessage(getString(R.string.individual_batch_request_failed))
                    if (generation == commandGeneration && stopSelfResult(startId)) {
                        ServiceCompat.stopForeground(this@ShortcutBatchService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    }
                }
            }
        }
        // A killed process must wait for an explicit Resume; Android must not restart the queue.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ShortcutBatchController.serviceStopped()
        releaseScreenLease()
        if (registered) unregisterReceiver(lockReceiver)
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /** Launcher confirmations can fully cover Hail, so its Activity flag alone is insufficient.
     * This bounded display lease exists only during the explicit automatic batch. It never wakes
     * or unlocks the phone; pressing Power still pauses the batch and releases it immediately. */
    @Suppress("DEPRECATION")
    private fun updateScreenLease(state: BatchState) {
        if (!state.active || !state.automatic) { releaseScreenLease(); return }
        val power = getSystemService(PowerManager::class.java) ?: return
        if (!power.isInteractive || getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) {
            releaseScreenLease()
            return
        }
        val key = "${state.batchId}:${state.position}:${state.awaiting?.requestId}"
        if (key == leasedRequest && screenLease?.isHeld == true) return
        try {
            val lease = screenLease ?: power.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK,
                "Hail:ShortcutBatch").apply { setReferenceCounted(false) }.also { screenLease = it }
            // Renew only at actual queue progress, not on polling/callback fields. A stuck request
            // pauses after 60 seconds; even a failed service cleanup cannot hold this indefinitely.
            lease.acquire(90_000L)
            leasedRequest = key
        } catch (e: RuntimeException) {
            releaseScreenLease()
            HLog.e(e)
            DecisionLog.begin("shortcut_batch", "screen_lease")?.error("screen_lease_failed", null, e)
        }
    }

    private fun releaseScreenLease() {
        screenLease?.let { if (it.isHeld) it.release() }
        screenLease = null
        leasedRequest = null
    }

    private fun notification(state: BatchState): android.app.Notification {
        val show = PendingIntent.getActivity(this, 0, ShortcutBatchActivity.intent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getBroadcast(this, 0,
            Intent(this, ShortcutBatchReceiver::class.java).setAction(ShortcutBatchReceiver.CANCEL)
                .setData(Uri.fromParts("hail-shortcut-cancel", state.batchId ?: "none", null)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_outline_select_all)
            .setContentTitle(getString(R.string.individual_batch_notification_title))
            .setContentText(getString(R.string.individual_batch_counts, state.completed, state.total))
            .setContentIntent(show).setOnlyAlertOnce(true).setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(state.total, state.completed, state.total == 0)
            .addAction(0, getString(R.string.individual_batch_cancel), cancel).build()
    }

    companion object {
        internal const val START = "com.aistra.hail.shortcut.START"
        internal const val RESUME = "com.aistra.hail.shortcut.RESUME"
        internal const val RETRY = "com.aistra.hail.shortcut.RETRY"
        internal const val SKIP = "com.aistra.hail.shortcut.SKIP"
        internal const val MANUAL_RESUME = "com.aistra.hail.shortcut.MANUAL_RESUME"
        private const val PACKAGES = "packages"
        private const val AUTOMATIC = "automatic"
        private const val CHANNEL = "individual_shortcut_batch"
        private const val NOTIFICATION = 117

        internal fun start(context: Context, packages: List<String>, automatic: Boolean) {
            launch(context, Intent(context, ShortcutBatchService::class.java).setAction(START)
                .putStringArrayListExtra(PACKAGES, ArrayList(packages)).putExtra(AUTOMATIC, automatic))
        }

        internal fun command(context: Context, action: String) =
            launch(context, Intent(context, ShortcutBatchService::class.java).setAction(action))

        private fun launch(context: Context, intent: Intent) {
            try { ContextCompat.startForegroundService(context, intent) }
            catch (e: RuntimeException) {
                HLog.e(e)
                ShortcutBatchController.showMessage(context.getString(R.string.individual_batch_service_failed))
            }
        }
    }
}
