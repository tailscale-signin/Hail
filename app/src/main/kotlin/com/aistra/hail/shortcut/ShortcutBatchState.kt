package com.aistra.hail.shortcut

enum class BatchStatus { IDLE, RUNNING, PAUSED, COMPLETED, CANCELLED }

/** Only this exact request may be handled by the optional launcher confirmation assistant. */
data class PendingShortcut(
    val batchId: String,
    val requestId: String,
    val packageName: String,
    val label: String,
    val launcherPackage: String,
    val confirmationActivities: Set<String>,
    val requestedAtElapsed: Long,
    val approvalReceived: Boolean = false,
    val confirmationObserved: Boolean = false,
    val automationClicked: Boolean = false,
    val confirmationDismissed: Boolean = false
)

data class BatchState(
    val initialized: Boolean = false,
    val batchId: String? = null,
    val packages: List<String> = emptyList(),
    val position: Int = 0,
    val added: List<String> = emptyList(),
    val existing: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    val status: BatchStatus = BatchStatus.IDLE,
    val automatic: Boolean = false,
    val launcherPackage: String? = null,
    val awaiting: PendingShortcut? = null,
    val message: String? = null
) {
    val active: Boolean get() = status == BatchStatus.RUNNING
    val total: Int get() = packages.size
    val completed: Int get() = position
    val current: String? get() = packages.getOrNull(position)
    val canResume: Boolean get() = status == BatchStatus.PAUSED && current != null

    internal fun advance(outcome: Outcome): BatchState {
        val pkg = current ?: return this
        val next = position + 1
        return copy(
            position = next,
            added = if (outcome == Outcome.ADDED) added + pkg else added,
            existing = if (outcome == Outcome.EXISTING) existing + pkg else existing,
            skipped = if (outcome == Outcome.SKIPPED) skipped + pkg else skipped,
            awaiting = null,
            status = if (next == total) BatchStatus.COMPLETED else status,
            message = null
        )
    }

    internal fun paused(reason: String): BatchState =
        if (status == BatchStatus.CANCELLED || status == BatchStatus.COMPLETED || current == null) this
        else copy(status = BatchStatus.PAUSED, message = reason)

    internal fun recover(reason: String): BatchState = copy(initialized = true).let {
        if (it.active) it.paused(reason) else it
    }

    internal fun matches(batch: String, request: String, pkg: String): Boolean =
        status != BatchStatus.CANCELLED && batchId == batch && awaiting?.let {
            it.batchId == batch && it.requestId == request && it.packageName == pkg && current == pkg
        } == true

    internal fun recordApproval(batch: String, request: String, pkg: String): BatchState =
        if (matches(batch, request, pkg)) copy(awaiting = awaiting!!.copy(approvalReceived = true)) else this

    /** A callback alone can arrive before the launcher finishes its confirmation window. */
    internal fun confirmPinned(pinned: Set<String>): BatchState {
        val pending = awaiting ?: return this
        return if (active && pending.approvalReceived && (!automatic || pending.confirmationDismissed) &&
            pending.packageName == current && pending.packageName in pinned) advance(Outcome.ADDED) else this
    }

    internal enum class Outcome { ADDED, EXISTING, SKIPPED }

    companion object {
        internal fun create(id: String, packages: List<String>, launcher: String, automatic: Boolean) =
            packages.filter { it.isNotBlank() }.distinct().let { entries ->
                BatchState(initialized = true, batchId = id, packages = entries,
                    status = if (entries.isEmpty()) BatchStatus.COMPLETED else BatchStatus.RUNNING,
                    automatic = automatic, launcherPackage = launcher)
            }
    }
}
