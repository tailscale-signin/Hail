package com.aistra.hail.app

data class FreezeResult(
    val changed: Set<String>,
    val unchanged: Set<String>,
    val missing: Set<String>,
    val failed: Set<String>
) {
    fun reachedTarget(pkg: String) = pkg in changed || pkg in unchanged
}

internal object FreezeBatch {
    fun run(packages: Collection<String>, self: String, frozen: Boolean,
            state: (String) -> AppInfo.State, apply: (List<String>) -> Set<String>): FreezeResult {
        val pending = mutableListOf<String>()
        val changed = linkedSetOf<String>()
        val unchanged = linkedSetOf<String>()
        val missing = linkedSetOf<String>()
        val failed = linkedSetOf<String>()
        for (pkg in packages.distinct()) {
            if (pkg == self) continue
            when (val current = state(pkg)) {
                AppInfo.State.NOT_FOUND -> missing.add(pkg)
                else -> if ((current == AppInfo.State.FROZEN) == frozen) unchanged.add(pkg) else pending.add(pkg)
            }
        }
        for (batch in pending.chunked(64)) {
            val succeeded = apply(batch)
            for (pkg in batch) {
                if (pkg in succeeded) changed.add(pkg) else failed.add(pkg)
            }
        }
        return FreezeResult(changed, unchanged, missing, failed)
    }
}
