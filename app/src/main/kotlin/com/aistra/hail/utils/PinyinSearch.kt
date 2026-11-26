package com.aistra.hail.utils

import net.sourceforge.pinyin4j.PinyinHelper
import java.util.Locale

object PinyinSearch {
    fun searchPinyinAll(raw: String?, query: String?): Boolean {
        if (query.isNullOrEmpty()) return true
        if (raw.isNullOrEmpty() || query.length > 48 || Locale.getDefault().language != Locale.CHINESE.language) return false
        return matchesPronunciation(raw, query)
    }

    internal fun matchesPronunciation(raw: String, query: String): Boolean {
        require(query.length in 1..48)
        // Bit n represents a matching substring of length n + 1. Merge alternatives only
        // at syllable boundaries, so letters from different readings cannot be spliced together.
        val masks = mutableMapOf<Char, Long>()
        for ((index, ch) in query.withIndex()) {
            val key = ch.uppercaseChar().lowercaseChar()
            masks[key] = (masks[key] ?: 0L) or (1L shl index)
        }
        val complete = 1L shl (query.length - 1)
        fun advance(state: Long, ch: Char) =
            ((state shl 1) or 1L) and (masks[ch.uppercaseChar().lowercaseChar()] ?: 0L)

        var spellings = 0L
        var initials = 0L
        for (ch in raw) {
            val readings = PinyinHelper.toHanyuPinyinStringArray(ch)
            if (readings == null) {
                spellings = advance(spellings, ch)
                initials = advance(initials, ch)
                if ((spellings and complete) != 0L || (query.length <= 8 && (initials and complete) != 0L)) return true
                continue
            }
            var nextSpellings = 0L
            var nextInitials = 0L
            for (reading in readings) {
                var state = spellings
                // pinyin4j appends a tone digit to each reading.
                for (index in 0 until reading.lastIndex) {
                    state = advance(state, reading[index])
                    if ((state and complete) != 0L) return true
                }
                nextSpellings = nextSpellings or state
                if (query.length <= 8) nextInitials = nextInitials or advance(initials, reading.first())
            }
            spellings = nextSpellings
            initials = nextInitials
            if ((initials and complete) != 0L) return true
        }
        return false
    }
}
