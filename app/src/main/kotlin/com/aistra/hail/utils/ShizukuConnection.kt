package com.aistra.hail.utils

import android.content.pm.PackageManager
import com.aistra.hail.app.AppManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicInteger

/** Process-wide connection observation. Only an explicit user action requests permission. */
object ShizukuConnection {
    enum class Status { STOPPED, UNSUPPORTED, PERMISSION_REQUIRED, DENIED, READY }

    data class State(
        val status: Status = Status.STOPPED,
        val uid: Int? = null,
        val apiVersion: Int? = null,
        val generation: Long = 0
    ) {
        val ready get() = status == Status.READY
    }

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val monitor = Any()
    private var generation = 0L
    private var initialized = false
    private val permissionRequests = Mutex()
    private val nextRequestCode = AtomicInteger(71_000)

    private val received = Shizuku.OnBinderReceivedListener {
        synchronized(monitor) { generation++ }
        // A replacement binder can arrive without a preceding death notification.
        ShizukuCommandClient.disconnect()
        refreshAsync()
    }
    private val dead = Shizuku.OnBinderDeadListener {
        val stoppedGeneration = synchronized(monitor) { ++generation }
        publish(State(generation = stoppedGeneration), stoppedGeneration)
        ShizukuCommandClient.disconnect()
    }
    private val permission = Shizuku.OnRequestPermissionResultListener { _, _ -> refreshAsync() }

    @Synchronized
    fun initialize() {
        if (initialized) return
        initialized = true
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
        // Sticky registration also covers a binder supplied by the provider before Application.onCreate.
        Shizuku.addBinderReceivedListenerSticky(received)
        refreshAsync()
    }

    fun refreshAsync() {
        scope.launch { refresh() }
    }

    /** Refresh on foreground entry or an operation boundary, not once per package in a batch. */
    fun refresh(): State {
        val observedGeneration = synchronized(monitor) { generation }
        val observed = try {
            when {
                !Shizuku.pingBinder() -> State()
                Shizuku.isPreV11() -> State(Status.UNSUPPORTED)
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                    State(Status.READY, Shizuku.getUid(), Shizuku.getVersion())
                Shizuku.shouldShowRequestPermissionRationale() -> State(Status.DENIED)
                else -> State(Status.PERMISSION_REQUIRED)
            }
        } catch (error: RuntimeException) {
            HLog.e(error)
            State()
        }
        // A late read must not restore READY after the binder-death callback.
        publish(observed.copy(generation = observedGeneration), observedGeneration)
        return mutableState.value
    }

    private fun publish(next: State, expectedGeneration: Long) {
        val previous = synchronized(monitor) {
            if (generation != expectedGeneration || mutableState.value == next) return
            mutableState.value.also { mutableState.value = next }
        }
        // Never hold our state monitor while calling another service or notifying UI observers.
        if (previous.ready && !next.ready) ShizukuCommandClient.disconnect()
        DecisionLog.begin("shizuku", "connection")?.event("connection_state", null,
            "status" to next.status.name, "serviceUid" to next.uid, "serviceApi" to next.apiVersion)
        AppManager.backendChanged()
    }

    /** Each request owns its listeners; timeout, cancellation, failure and binder death all remove them. */
    suspend fun requestPermission(): Boolean = permissionRequests.withLock {
        val current = withContext(Dispatchers.IO) { refresh() }
        if (current.ready) return@withLock true
        if (current.status != Status.PERMISSION_REQUIRED) return@withLock false
        val code = nextRequestCode.incrementAndGet()
        val granted = withContext(Dispatchers.Main.immediate) {
            withTimeoutOrNull(60_000L) {
                callbackFlow {
                    val result = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
                        if (requestCode == code) trySend(grantResult == PackageManager.PERMISSION_GRANTED)
                    }
                    val disconnected = Shizuku.OnBinderDeadListener { trySend(false) }
                    try {
                        Shizuku.addRequestPermissionResultListener(result)
                        Shizuku.addBinderDeadListener(disconnected)
                        val sameConnection = synchronized(monitor) { generation == current.generation }
                        if (sameConnection && Shizuku.pingBinder()) Shizuku.requestPermission(code) else trySend(false)
                        awaitClose()
                    } finally {
                        // The request itself can throw before awaitClose is reached.
                        Shizuku.removeRequestPermissionResultListener(result)
                        Shizuku.removeBinderDeadListener(disconnected)
                    }
                }.first()
            } ?: false
        }
        val updated = withContext(Dispatchers.IO) { refresh() }
        granted && updated.ready && updated.generation == current.generation
    }
}
