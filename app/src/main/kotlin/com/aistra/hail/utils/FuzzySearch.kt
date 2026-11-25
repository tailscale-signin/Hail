package com.aistra.hail.utils

import com.aistra.hail.app.HailData

object FuzzySearch {
    fun search(raw: String?, query: String?): Boolean {
        if (query.isNullOrEmpty()) return true
        if (raw.isNullOrEmpty()) return false
        if (raw.contains(query, ignoreCase = true)) return true
        if (!HailData.fuzzySearch) return false
        return containsInOrder(raw.uppercase(), query.uppercase())
    }

    // For a nonempty subsequence, deleting the other characters always costs less than raw.length.
    // The previous edit-distance calculation therefore never rejected a subsequence.
    internal fun containsInOrder(raw: String, query: String): Boolean {
        var start = 0
        for (ch in query) {
            val index = raw.indexOf(ch, start)
            if (index < 0) return false
            start = index + 1
        }
        return true
    }
}
