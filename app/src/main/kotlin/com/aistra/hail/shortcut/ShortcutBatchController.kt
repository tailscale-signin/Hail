package com.aistra.hail.shortcut

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.os.UserManager
import android.os.PowerManager
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.utils.HLog
import com.aistra.hail.utils.HShortcuts
import com.aistra.hail.utils.HTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** A single persisted queue. Only the foreground service dispatches launcher requests. */
object ShortcutBatchController {
    private val mutableState = MutableStateFlow(BatchState())
    val state: StateFlow<BatchState> = mutableState.asStateFlow()
    private val lock = Any()
    private val initialization = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private data class Write(val state: BatchState, val saved: CompletableDeferred<Unit> = CompletableDeferred())
    private val writes = Channel<Write>(Channel.UNLIMITED)
    private lateinit var context: Context
    private lateinit var store: ShortcutBatchStore
    private var storageFailed = false
    private var manualLostFocus = false
    private var manualReturnedAtElapsed = 0L

    init {
        scope.launch {
            for (write in writes) {
                try {
                    store.write(write.state)
                    write.saved.complete(Unit)
                } catch (e: Exception) {
                    HLog.e(e)
                    synchronized(lock) {
                        storageFailed = true
                        mutableState.value = mutableState.value.paused(context.getString(R.string.individual_batch_storage_failed))
                    }
                    write.saved.completeExceptionally(e)
                }
            }
        }
    }

    suspend fun initialize(context: Context) = initialization.withLock {
        if (state.value.initialized) return@withLock
        this.context = context.applicationContext
        store = ShortcutBatchStore(this.context)
        val restored = withContext(Dispatchers.IO) {
            runCatching { store.read() }.onFailure(HLog::e).getOrNull()
        }
        val initial = restored?.recover(context.getString(R.string.individual_batch_interrupted))
            ?: BatchState(initialized = true)
        synchronized(lock) { mutableState.value = initial }
        if (restored?.active == true) change(force = true) { initial }?.saved?.await()
    }

    fun start(context: Context, packages: List<String>, automatic: Boolean = true) =
        ShortcutBatchService.start(context, packages, automatic)

    fun resume(context: Context) = ShortcutBatchService.command(context, ShortcutBatchService.RESUME)
    fun retry(context: Context) = ShortcutBatchService.command(context, ShortcutBatchService.RETRY)
    fun skip(context: Context) = ShortcutBatchService.command(context, ShortcutBatchService.SKIP)
    fun continueManually(context: Context) = ShortcutBatchService.command(context, ShortcutBatchService.MANUAL_RESUME)

    fun pause(reason: String) { change { it.paused(reason) } }

    fun cancel() {
        val pending = state.value.awaiting
        change { current ->
            if (current.batchId == null || current.status == BatchStatus.COMPLETED) current
            else current.copy(status = BatchStatus.CANCELLED, awaiting = null,
                message = context.getString(R.string.individual_batch_cancelled))
        }
        if (pending != null) callback(pending, PendingIntent.FLAG_NO_CREATE)?.cancel()
    }

    /** Never exposes a recovered, paused, cancelled, locked, or manually confirmed request. */
    fun currentAutomationRequest(): PendingShortcut? = synchronized(lock) {
        val current = mutableState.value
        if (!current.active || !current.automatic || !isUnlocked()) null else current.awaiting
    }

    fun confirmationObserved(requestId: String) {
        changePending(requestId) { it.copy(confirmationObserved = true) }
    }

    fun claimAutomationClick(requestId: String): Boolean = synchronized(lock) {
        val current = mutableState.value
        val pending = current.awaiting ?: return@synchronized false
        if (!current.active || !current.automatic || pending.requestId != requestId ||
            !pending.confirmationObserved || pending.automationClicked || !isUnlocked() ||
            currentHomePackage() != pending.launcherPackage) return@synchronized false
        change { it.copy(awaiting = pending.copy(automationClicked = true)) }
        true
    }

    fun releaseAutomationClick(requestId: String) {
        changePending(requestId) { it.copy(automationClicked = false, confirmationDismissed = false) }
    }

    fun automationConfirmationDismissed(requestId: String) {
        changePending(requestId) {
            if (it.confirmationObserved && it.automationClicked) it.copy(confirmationDismissed = true) else it
        }
    }

    internal fun activityFocusChanged(focused: Boolean) = synchronized(lock) {
        val current = mutableState.value
        val pending = current.awaiting
        if (!current.active || current.automatic || pending == null) return@synchronized
        if (!focused) manualLostFocus = true
        else if (manualLostFocus) {
            manualReturnedAtElapsed = SystemClock.elapsedRealtime()
            change { it.copy(awaiting = pending.copy(confirmationDismissed = true)) }
        }
    }

    internal suspend fun begin(packages: List<String>, automatic: Boolean) {
        if (state.value.active || state.value.canResume) return
        storageFailed = false
        val target = launcherTarget()
        if (!HTarget.O || target == null || !isUnlocked()) {
            showMessage(context.getString(if (!HTarget.O) R.string.individual_batch_android_required
                else if (!isUnlocked()) R.string.individual_batch_locked else R.string.individual_batch_no_launcher))
            return
        }
        val next = BatchState.create(UUID.randomUUID().toString(), packages, target.first, automatic)
        change { next }?.saved?.await()
    }

    internal suspend fun continueBatch(retry: Boolean = false, skip: Boolean = false, manual: Boolean = false) {
        val initial = state.value
        if (!initial.canResume || storageFailed) return
        if (!isUnlocked()) { pause(context.getString(R.string.individual_batch_locked)); return }
        if (currentHomePackage() != initial.launcherPackage) {
            pause(context.getString(R.string.individual_batch_launcher_changed)); return
        }
        if (initial.automatic && !manual && !ShortcutPinAccessibilityService.isEnabled(context)) {
            pause(context.getString(R.string.individual_batch_assistant_disconnected)); return
        }
        val pinned = withContext(Dispatchers.IO) { HShortcuts.pinnedIds() }
        val pending = initial.awaiting
        // The user's explicit Resume/Retry is the only way to reissue an interrupted request.
        // Read back first: an approval may have survived process loss without its callback.
        if (pending != null) callback(pending, PendingIntent.FLAG_NO_CREATE)?.cancel()
        change { current ->
            if (current != initial) current
            else {
                var next = current.copy(status = BatchStatus.RUNNING, awaiting = null, message = null,
                    automatic = current.automatic && !manual)
                if (current.current in pinned) next = next.advance(
                    if (pending != null) BatchState.Outcome.ADDED else BatchState.Outcome.EXISTING)
                else if (skip) next = next.advance(BatchState.Outcome.SKIPPED)
                // Both controls are explicit user requests; neither is reached by a timeout.
                else if (retry) next = next.copy(message = null)
                next
            }
        }?.saved?.await()
    }

    internal suspend fun callbackReceived(batchId: String, requestId: String, pkg: String) {
        change { it.recordApproval(batchId, requestId, pkg) }?.saved?.await()
    }

    /** Called once per service instance. It never sends a second request while one is outstanding. */
    internal suspend fun runQueue() {
        while (state.value.active && !storageFailed) {
            try {
                if (!isUnlocked()) { pause(context.getString(R.string.individual_batch_locked)); break }
                val current = state.value
                if (currentHomePackage() != current.launcherPackage) {
                    pause(context.getString(R.string.individual_batch_launcher_changed)); break
                }
                if (current.automatic && !ShortcutPinAccessibilityService.isConnected()) {
                    pause(context.getString(R.string.individual_batch_assistant_disconnected)); break
                }
                val pending = current.awaiting
                if (pending != null) {
                    if (pending.approvalReceived && (!current.automatic || pending.confirmationDismissed)) {
                        val pinned = withContext(Dispatchers.IO) { HShortcuts.pinnedIds() }
                        if (pending.packageName in pinned) {
                            change { if (it.awaiting?.requestId == pending.requestId)
                                it.confirmPinned(pinned) else it }?.saved?.await()
                            // Let the launcher's dismiss animation settle before the next pin request.
                            delay(if (current.automatic) 250 else 500)
                            continue
                        }
                    }
                    if (!current.automatic && pending.confirmationDismissed && !pending.approvalReceived &&
                        manualReturnedAtElapsed > 0 && SystemClock.elapsedRealtime() - manualReturnedAtElapsed > 1_500) {
                        pause(context.getString(R.string.individual_batch_waiting))
                        break
                    }
                    if (SystemClock.elapsedRealtime() - pending.requestedAtElapsed > REQUEST_TIMEOUT_MS) {
                        pause(context.getString(R.string.individual_batch_waiting))
                        break
                    }
                    delay(200)
                    continue
                }
                val pkg = current.current ?: break
                val pinned = withContext(Dispatchers.IO) { HShortcuts.pinnedIds() }
                if (state.value != current) continue
                if (pkg in pinned) {
                    change { if (it == current) it.advance(BatchState.Outcome.EXISTING) else it }?.saved?.await()
                    continue
                }
                val target = launcherTarget()
                if (target == null || target.first != current.launcherPackage || !HShortcuts.supportsPinning) {
                    pause(context.getString(R.string.individual_batch_no_launcher)); break
                }
                val shortcut = withContext(Dispatchers.IO) { HShortcuts.appPinShortcut(AppInfo(pkg)) }
                if (state.value != current) continue
                val request = PendingShortcut(current.batchId!!, UUID.randomUUID().toString(), pkg,
                    shortcut.shortLabel.toString(), target.first, target.second, SystemClock.elapsedRealtime())
                synchronized(lock) { manualLostFocus = false; manualReturnedAtElapsed = 0L }
                val written = change { if (it == current) it.copy(awaiting = request) else it }
                written?.saved?.await()
                if (!state.value.active || state.value.awaiting?.requestId != request.requestId) continue
                val response = callback(request)!!
                val accepted = withContext(Dispatchers.IO) { HShortcuts.requestPin(shortcut, response.intentSender) }
                if (!accepted) {
                    response.cancel()
                    pause(context.getString(R.string.individual_batch_request_failed))
                    break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HLog.e(e)
                pause(context.getString(R.string.individual_batch_request_failed))
            }
        }
    }

    internal fun serviceStopped() {
        if (state.value.active) pause(context.getString(R.string.individual_batch_interrupted))
    }

    internal fun showMessage(message: String) {
        change { if (it.current != null) it.paused(message) else it.copy(message = message) }
    }

    internal suspend fun awaitSaved() { change(force = true) { it }?.saved?.await() }

    private fun changePending(requestId: String, transform: (PendingShortcut) -> PendingShortcut) {
        change { current ->
            val pending = current.awaiting
            if (!current.active || !current.automatic || pending?.requestId != requestId) current
            else current.copy(awaiting = transform(pending))
        }
    }

    private fun change(force: Boolean = false, transform: (BatchState) -> BatchState): Write? = synchronized(lock) {
        val before = mutableState.value
        val after = transform(before)
        if (!force && after == before) return@synchronized null
        mutableState.value = after
        if (!after.initialized || after.batchId == null) return@synchronized null
        Write(after).also { writes.trySend(it).getOrThrow() }
    }

    private fun callback(request: PendingShortcut, additionalFlags: Int = 0): PendingIntent? {
        val intent = Intent(context, ShortcutBatchReceiver::class.java)
            .setAction(ShortcutBatchReceiver.PINNED)
            .setData(Uri.Builder().scheme("hail-shortcut-result").authority(request.batchId)
                .appendPath(request.requestId).appendPath(request.packageName).build())
        return PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE or additionalFlags)
    }

    private fun isUnlocked(): Boolean {
        if (!::context.isInitialized) return false
        if (context.getSystemService(PowerManager::class.java)?.isInteractive != true) return false
        if (context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) return false
        return !HTarget.N || context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
    }

    @Suppress("DEPRECATION")
    private fun currentHomePackage(): String? = context.packageManager.resolveActivity(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
    )?.activityInfo?.packageName?.takeIf { it != "android" }

    @Suppress("DEPRECATION")
    private fun launcherTarget(): Pair<String, Set<String>>? {
        val home = currentHomePackage() ?: return null
        val activities = context.packageManager.queryIntentActivities(
            Intent(LauncherApps.ACTION_CONFIRM_PIN_SHORTCUT).setPackage(home), 0
        ).mapNotNull { it.activityInfo?.takeIf { activity -> activity.enabled }?.name }.toSet()
        return (home to activities).takeIf { activities.isNotEmpty() }
    }

    private const val REQUEST_TIMEOUT_MS = 60_000L
}
