package com.aistra.hail.ui.settings

import java.io.Serializable

internal data class ShortcutBatch(
    val packages: List<String>,
    val added: Int = 0,
    val existing: Int = 0,
    val skipped: Int = 0,
    val requestId: String? = null,
    val failed: Boolean = false
) : Serializable {
    val position get() = added + existing + skipped
    val current get() = packages.getOrNull(position)

    fun confirm(id: String?) =
        if (id != null && id == requestId) copy(added = added + 1, requestId = null, failed = false)
        else this

    fun skip() = copy(skipped = skipped + 1, requestId = null, failed = false)
    fun retry() = copy(requestId = null, failed = false)

    // A launcher may pin while Hail is paused or while its process is being recreated.
    fun reconcile(pinned: Set<String>): ShortcutBatch {
        var next = this
        while (next.current in pinned) {
            next = if (next.requestId != null) next.confirm(next.requestId)
            else next.copy(existing = next.existing + 1, failed = false)
        }
        return next
    }

    companion object {
        fun from(packages: List<String>) = ShortcutBatch(packages.filter { it.isNotBlank() }.distinct())
    }
}
