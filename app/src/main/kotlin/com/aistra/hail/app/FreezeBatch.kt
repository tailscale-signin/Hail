package com.aistra.hail.app

import com.aistra.hail.utils.DecisionTrace

data class FreezeResult(
    val changed: Set<String>,
    val unchanged: Set<String>,
    val missing: Set<String>,
    val failed: Set<String>,
    val singleLabel: String? = null,
    val failureMessage: String? = null
) {
    fun reachedTarget(pkg: String) = pkg in changed || pkg in unchanged
}

internal object FreezeBatch {
    fun run(packages: Collection<String>, self: String, frozen: Boolean,
            state: (String) -> AppInfo.State, trace: DecisionTrace? = null, apply: (List<String>) -> Set<String>): FreezeResult {
        val pending = mutableListOf<String>()
        val changed = linkedSetOf<String>()
        val unchanged = linkedSetOf<String>()
        val missing = linkedSetOf<String>()
        val failed = linkedSetOf<String>()
        val seen = HashSet<String>()
        for (pkg in packages) {
            if (!seen.add(pkg)) {
                trace?.skipped(pkg, "Duplicate in the same request")
                continue
            }
            if (pkg == self) {
                trace?.skipped(pkg, "Hail cannot freeze itself")
                continue
            }
            val current = state(pkg)
            when {
                current == AppInfo.State.NOT_FOUND -> {
                    missing.add(pkg)
                    trace?.skipped(pkg, "No installed package for this Android user")
                }
                (current == AppInfo.State.FROZEN) == frozen -> {
                    unchanged.add(pkg)
                    trace?.skipped(pkg, "Already in the requested state")
                }
                else -> {
                    pending.add(pkg)
                    trace?.event("eligible", pkg, "reason" to "Current state requires a change")
                }
            }
        }
        for (batch in pending.chunked(64)) {
            trace?.event("batch_dispatched", null, "size" to batch.size)
            val succeeded = apply(batch)
            for (pkg in batch) {
                val success = pkg in succeeded
                if (success) changed.add(pkg) else failed.add(pkg)
                trace?.event("outcome", pkg, "result" to if (success) "changed" else "failed")
            }
        }
        return FreezeResult(changed, unchanged, missing, failed)
    }
}
