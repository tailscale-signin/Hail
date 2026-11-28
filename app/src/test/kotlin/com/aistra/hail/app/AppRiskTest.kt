package com.aistra.hail.app

import org.junit.Assert.*
import org.junit.Test

class AppRiskTest {
    @Test fun sourceLevelsAreMappedWithoutCallingUnknownAppsSafe() {
        assertEquals(RiskLevel.RECOMMENDED, RiskLevel.fromCommunity("Recommended"))
        assertEquals(RiskLevel.CAUTION, RiskLevel.fromCommunity("Advanced"))
        assertEquals(RiskLevel.RISKY, RiskLevel.fromCommunity("Expert"))
        assertEquals(RiskLevel.UNSAFE, RiskLevel.fromCommunity("Unsafe"))
        assertEquals(RiskLevel.UNKNOWN, RiskLevel.fromCommunity("new-level"))
        assertEquals(RiskLevel.UNKNOWN, RiskLevel.fromCommunity(null))
        val classifier = AppRiskClassifier(emptyMap())
        assertEquals(AppRisk(), classifier.classify("example.unlisted", system = false))
        val system = classifier.classify("com.android.unlisted", system = true)
        assertEquals(RiskLevel.UNKNOWN, system.level)
        assertEquals(AppNameTone.SYSTEM, system.nameTone)
        assertEquals(RiskEvidence.UNLISTED, system.evidence)
        assertNull(system.communityLevel)
    }

    @Test fun onlyExactCorePackagesOrSystemUidOverrideTheCommunityList() {
        val classifier = AppRiskClassifier(mapOf("com.android.systemui" to
            CommunityRisk(RiskLevel.RECOMMENDED, "Stale or incorrect recommendation")))
        val core = classifier.classify("com.android.systemui", system = true)
        assertEquals(RiskLevel.UNSAFE, core.level)
        assertEquals(EssentialRole.ANDROID_CORE, core.essentialRole)
        assertEquals(AppNameTone.CRITICAL, core.nameTone)
        assertEquals(RiskEvidence.CORE_PACKAGE_GUARD, core.evidence)
        assertEquals(RiskLevel.RECOMMENDED, core.communityLevel)
        assertEquals(RiskLevel.UNKNOWN, classifier.classify("com.android.systemui.addon", system = true).level)
        assertEquals(RiskLevel.UNKNOWN, classifier.classify("com.vendor.framework.help", system = true).level)
        val systemUid = classifier.classify("com.oem.core", system = true, systemUid = true)
        assertEquals(RiskLevel.UNSAFE, systemUid.level)
        assertEquals(RiskEvidence.SYSTEM_UID, systemUid.evidence)
        assertEquals(RiskLevel.UNKNOWN, classifier.classify("com.oem.core", system = false, systemUid = true).level)
    }

    @Test fun currentEssentialRolesOverrideEvenRecommendedUserAppsAndCanBeCleared() {
        val classifier = AppRiskClassifier(mapOf("org.example.home" to
            CommunityRisk(RiskLevel.RECOMMENDED, "Replace with another launcher first.")))
        EssentialRole.entries.filterNot { it == EssentialRole.ANDROID_CORE }.forEach { role ->
            val risk = classifier.classify("org.example.home", system = false, role = role)
            assertEquals(RiskLevel.UNSAFE, risk.level)
            assertEquals(AppNameTone.CRITICAL, risk.nameTone)
            assertEquals(role, risk.essentialRole)
            assertEquals(RiskEvidence.DEVICE_ROLE, risk.evidence)
        }
        val oldHome = classifier.classify("org.example.home", system = false)
        assertEquals(RiskLevel.RECOMMENDED, oldHome.level)
        assertEquals(AppNameTone.NORMAL, oldHome.nameTone)
        assertNull(oldHome.essentialRole)
        assertEquals(RiskEvidence.COMMUNITY_LIST, oldHome.evidence)
    }

    @Test fun deviceOverridesRetainCommunityEvidenceWithoutInheritingItsRecommendation() {
        val entry = CommunityRisk(RiskLevel.RECOMMENDED, "May be replaced after changing the default.",
            dependencies = listOf("org.example.service"), neededBy = listOf("org.example.client"))
        val classifier = AppRiskClassifier(mapOf("org.example.home" to entry))
        val risk = classifier.classify("org.example.home", system = false, role = EssentialRole.HOME)
        assertEquals(RiskLevel.UNSAFE, risk.level)
        assertEquals(RiskEvidence.DEVICE_ROLE, risk.evidence)
        assertEquals(RiskLevel.RECOMMENDED, risk.communityLevel)
        assertEquals(entry.description, risk.communityDescription)
        assertEquals(entry.dependencies, risk.dependencies)
        assertEquals(entry.neededBy, risk.neededBy)
        val unrelated = classifier.classify("org.example.home.addon", system = false)
        assertEquals(AppRisk(), unrelated)
    }

    @Test fun unsafeColorTakesPriorityOverSystemColorAndDoesNotChangeOtherCategories() {
        for (level in RiskLevel.entries) {
            assertEquals(if (level == RiskLevel.UNSAFE) AppNameTone.CRITICAL else AppNameTone.SYSTEM,
                AppRisk(level, system = true).nameTone)
            assertEquals(if (level == RiskLevel.UNSAFE) AppNameTone.CRITICAL else AppNameTone.NORMAL,
                AppRisk(level, system = false).nameTone)
        }
    }
}
