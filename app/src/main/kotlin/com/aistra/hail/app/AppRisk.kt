package com.aistra.hail.app

/** Advisory removal categories, independent of Android package state and freeze permissions. */
enum class RiskLevel {
    RECOMMENDED, CAUTION, RISKY, UNSAFE, UNKNOWN;

    companion object {
        fun fromCommunity(value: String?): RiskLevel = when (value?.lowercase()) {
            "recommended" -> RECOMMENDED
            "advanced" -> CAUTION
            "expert" -> RISKY
            "unsafe" -> UNSAFE
            else -> UNKNOWN
        }
    }
}

enum class EssentialRole { ANDROID_CORE, HOME, INPUT_METHOD, DEVICE_ADMIN }
enum class AppNameTone { NORMAL, SYSTEM, CRITICAL }

data class CommunityRisk(val level: RiskLevel, val description: String)

data class AppRisk(
    val level: RiskLevel = RiskLevel.UNKNOWN,
    val system: Boolean = false,
    val essentialRole: EssentialRole? = null,
    val communityDescription: String? = null
) {
    val nameTone: AppNameTone get() = when {
        level == RiskLevel.UNSAFE -> AppNameTone.CRITICAL
        system -> AppNameTone.SYSTEM
        else -> AppNameTone.NORMAL
    }
}

/** Exact package matches only. A package prefix is never proof of safety or criticality. */
class AppRiskClassifier(private val community: Map<String, CommunityRisk>) {
    fun classify(
        packageName: String,
        system: Boolean,
        systemUid: Boolean = false,
        role: EssentialRole? = null
    ): AppRisk {
        val entry = community[packageName]
        val essential = role ?: if (system && (systemUid || packageName in corePackages))
            EssentialRole.ANDROID_CORE else null
        return AppRisk(
            level = if (essential != null) RiskLevel.UNSAFE else entry?.level ?: RiskLevel.UNKNOWN,
            system = system,
            essentialRole = essential,
            communityDescription = entry?.description
        )
    }

    companion object {
        // Small fallback for core Android components if community data is unavailable.
        // Other OEM/framework names are classified by the pinned list or remain Unknown.
        private val corePackages = setOf(
            "android", "com.android.systemui", "com.android.settings", "com.android.providers.settings",
            "com.android.phone", "com.android.server.telecom", "com.android.providers.telephony",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.android.networkstack", "com.google.android.networkstack", "com.android.networkstack.inprocess",
            "com.android.networkstack.permissionconfig", "com.android.shell"
        )
    }
}
