package com.aistra.hail.utils

import net.sourceforge.pinyin4j.PinyinHelper
import org.junit.Assert.*
import org.junit.Test

class PrivacyAndSearchTest {
    @Test fun disableVerificationOnlyStopsConfirmedDisabledTargetsAndRequiresReadback() {
        for (initial in DisableObservation.entries) {
            for (after in DisableObservation.entries) {
                var stops = 0
                var reads = 0
                val verified = initial.confirmStopped({ stops++ }, { reads++; after })
                assertEquals(if (initial == DisableObservation.DISABLED) 1 else 0, stops)
                assertEquals(stops, reads)
                assertEquals(initial == DisableObservation.STOPPED ||
                    initial == DisableObservation.DISABLED && after == DisableObservation.STOPPED, verified)
            }
        }
    }

    @Test fun pinyinFindsExactlyTheSubstringsOfPossibleReadings() {
        val name = "重行A"
        val readings = name.map { letter ->
            PinyinHelper.toHanyuPinyinStringArray(letter)?.map { it.dropLast(1) }?.distinct() ?: listOf(letter.toString())
        }
        fun combinations(initials: Boolean) = readings.fold(listOf("")) { prefixes, choices ->
            prefixes.flatMap { prefix -> choices.map { prefix + if (initials) it.take(1) else it } }
        }
        val spellings = combinations(false)
        val initials = combinations(true)
        val queries = (spellings + initials).flatMap { word ->
            word.indices.flatMap { start -> (start + 1..word.length).map { end -> word.substring(start, end) } }
        } + listOf("zxz", "zCh", "hangxing", "ZHOng", "a", "GX", "cz", "cHONGHANGa")
        for (query in queries) {
            val expected = spellings.any { it.contains(query, true) } ||
                query.length <= 8 && initials.any { it.contains(query, true) }
            assertEquals(query, expected, PinyinSearch.matchesPronunciation(name, query))
        }
    }

    @Test(timeout = 3000) fun longPolyphonicLabelCanBeSearchedWithoutEnumeratingCombinations() {
        val label = "重行".repeat(100)
        assertFalse(PinyinSearch.matchesPronunciation(label, "unavailable"))
        assertTrue(PinyinSearch.matchesPronunciation(label, "nghangchong"))
    }

    @Test fun subsequenceRequiresOrderAndMultiplicity() {
        assertTrue(FuzzySearch.containsInOrder("PACKAGE MANAGER", "PKGMGR"))
        assertFalse(FuzzySearch.containsInOrder("PACKAGE", "GG"))
        assertFalse(FuzzySearch.containsInOrder("PACKAGE", "GAP"))
    }
}
