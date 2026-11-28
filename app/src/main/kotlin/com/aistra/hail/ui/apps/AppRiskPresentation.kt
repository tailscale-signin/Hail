package com.aistra.hail.ui.apps

import android.content.Context
import com.aistra.hail.R
import com.aistra.hail.app.AppRisk
import com.aistra.hail.app.EssentialRole
import com.aistra.hail.app.RiskLevel
import com.aistra.hail.app.RiskEvidence
import com.aistra.hail.utils.AppRisks

object AppRiskPresentation {
    fun label(context: Context, risk: AppRisk): String {
        val level = level(context, risk.level)
        val kind = when {
            risk.essentialRole != null -> R.string.app_risk_essential_app
            risk.system -> R.string.app_risk_system_app
            else -> R.string.app_risk_user_app
        }
        return context.getString(R.string.app_risk_badge, level, context.getString(kind))
    }

    private fun level(context: Context, level: RiskLevel): String = context.getString(when (level) {
        RiskLevel.RECOMMENDED -> R.string.app_risk_recommended
        RiskLevel.CAUTION -> R.string.app_risk_caution
        RiskLevel.RISKY -> R.string.app_risk_risky
        RiskLevel.UNSAFE -> R.string.app_risk_unsafe
        RiskLevel.UNKNOWN -> R.string.app_risk_unknown
    })

    private fun reason(context: Context, risk: AppRisk): String = when (risk.essentialRole) {
        EssentialRole.ANDROID_CORE -> context.getString(R.string.app_risk_core_reason)
        EssentialRole.HOME -> context.getString(R.string.app_risk_home_reason)
        EssentialRole.INPUT_METHOD -> context.getString(R.string.app_risk_input_reason)
        EssentialRole.DEVICE_ADMIN -> context.getString(R.string.app_risk_admin_reason)
        null -> if (risk.level == RiskLevel.UNKNOWN) context.getString(R.string.app_risk_unknown_reason)
            else context.getString(R.string.app_risk_community_reason,
                risk.communityDescription?.trim().orEmpty().ifBlank { context.getString(R.string.app_risk_check_features) })
    }

    fun explanation(context: Context, risk: AppRisk): String = buildList {
        add(reason(context, risk))
        when (risk.evidence) {
            RiskEvidence.DEVICE_ROLE -> add(context.getString(R.string.app_risk_evidence_role))
            RiskEvidence.SYSTEM_UID -> add(context.getString(R.string.app_risk_evidence_uid))
            RiskEvidence.CORE_PACKAGE_GUARD -> add(context.getString(R.string.app_risk_evidence_core))
            RiskEvidence.COMMUNITY_LIST, RiskEvidence.UNLISTED -> Unit
        }
        risk.communityLevel?.let { community ->
            add(context.getString(R.string.app_risk_evidence_source,
                level(context, community), AppRisks.SOURCE_COMMIT.take(12)))
            if (risk.essentialRole != null) {
                add(context.getString(R.string.app_risk_evidence_override))
            }
            if (risk.essentialRole != null || risk.level == RiskLevel.UNKNOWN) {
                risk.communityDescription?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    add(context.getString(R.string.app_risk_community_reason, it))
                }
            }
        }
        if (risk.dependencies.isNotEmpty()) add(context.getString(R.string.app_risk_dependencies, risk.dependencies.joinToString("\n")))
        if (risk.neededBy.isNotEmpty()) add(context.getString(R.string.app_risk_needed_by, risk.neededBy.joinToString("\n")))
        add(context.getString(R.string.app_risk_evidence_limit))
    }.joinToString("\n\n")
}
