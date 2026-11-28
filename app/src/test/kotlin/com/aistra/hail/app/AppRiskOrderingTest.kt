package com.aistra.hail.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AppRiskOrderingTest {
    private data class Row(val label: String, val packageName: String, val risk: AppRisk)
    private val names = compareBy<Row> { it.label }.thenBy { it.packageName }

    @Test fun unknownRemainsLastInBothRiskDirections() {
        val input = listOf(RiskLevel.UNKNOWN, RiskLevel.CAUTION, RiskLevel.UNSAFE,
            RiskLevel.RECOMMENDED, RiskLevel.RISKY)
        fun sorted(unsafeFirst: Boolean) = input.sortedWith(
            AppRiskOrdering.comparator(unsafeFirst, { it }, compareBy { it.name }))
        assertEquals(listOf(RiskLevel.RECOMMENDED, RiskLevel.CAUTION, RiskLevel.RISKY,
            RiskLevel.UNSAFE, RiskLevel.UNKNOWN), sorted(unsafeFirst = false))
        assertEquals(listOf(RiskLevel.UNSAFE, RiskLevel.RISKY, RiskLevel.CAUTION,
            RiskLevel.RECOMMENDED, RiskLevel.UNKNOWN), sorted(unsafeFirst = true))
    }

    @Test fun eachCategoryUsesTheSameNameAndPackageTieBreakInBothDirections() {
        val input = listOf(
            Row("Beta", "org.example.beta", AppRisk(RiskLevel.CAUTION)),
            Row("Alpha", "org.example.alpha.second", AppRisk(RiskLevel.CAUTION)),
            Row("Alpha", "org.example.alpha.first", AppRisk(RiskLevel.CAUTION)),
            Row("Unknown B", "org.example.unknown.b", AppRisk()),
            Row("Unknown A", "org.example.unknown.a", AppRisk())
        )
        val expected = listOf("org.example.alpha.first", "org.example.alpha.second", "org.example.beta",
            "org.example.unknown.a", "org.example.unknown.b")
        for (unsafeFirst in listOf(false, true)) {
            val sorted = input.sortedWith(AppRiskOrdering.comparator(unsafeFirst, { it.risk.level }, names))
            assertEquals(expected, sorted.map { it.packageName })
        }
    }

    @Test fun sortingUsesDeviceOverridesInsteadOfTheUnderlyingCommunityRating() {
        val classifier = AppRiskClassifier(mapOf(
            "org.example.home" to CommunityRisk(RiskLevel.RECOMMENDED, "Replace its active role first."),
            "org.example.optional" to CommunityRisk(RiskLevel.RECOMMENDED, "Optional feature.")
        ))
        val input = listOf(
            Row("Active Home", "org.example.home", classifier.classify("org.example.home", false,
                role = EssentialRole.HOME)),
            Row("Optional", "org.example.optional", classifier.classify("org.example.optional", false)),
            Row("Unlisted", "org.example.unknown", classifier.classify("org.example.unknown", false))
        )
        fun sorted(unsafeFirst: Boolean) = input.sortedWith(
            AppRiskOrdering.comparator(unsafeFirst, { it.risk.level }, names)).map { it.packageName }
        assertEquals(listOf("org.example.optional", "org.example.home", "org.example.unknown"), sorted(false))
        assertEquals(listOf("org.example.home", "org.example.optional", "org.example.unknown"), sorted(true))
    }
}
