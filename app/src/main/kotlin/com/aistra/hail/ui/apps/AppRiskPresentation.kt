package com.aistra.hail.ui.apps

import android.content.Context
import androidx.annotation.ColorRes
import com.aistra.hail.R
import com.aistra.hail.app.AppNameTone
import com.aistra.hail.app.AppRisk
import com.aistra.hail.app.EssentialRole
import com.aistra.hail.app.RiskLevel

object AppRiskPresentation {
    @ColorRes fun nameColor(risk: AppRisk): Int? = when (risk.nameTone) {
        AppNameTone.CRITICAL -> R.color.app_name_critical
        AppNameTone.SYSTEM -> R.color.app_name_system
        AppNameTone.NORMAL -> null
    }

    fun label(context: Context, risk: AppRisk): String {
        val level = context.getString(when (risk.level) {
            RiskLevel.RECOMMENDED -> R.string.app_risk_recommended
            RiskLevel.CAUTION -> R.string.app_risk_caution
            RiskLevel.RISKY -> R.string.app_risk_risky
            RiskLevel.UNSAFE -> R.string.app_risk_unsafe
            RiskLevel.UNKNOWN -> R.string.app_risk_unknown
        })
        val kind = when {
            risk.essentialRole != null -> R.string.app_risk_essential_app
            risk.system -> R.string.app_risk_system_app
            else -> R.string.app_risk_user_app
        }
        return context.getString(R.string.app_risk_badge, level, context.getString(kind))
    }

    fun explanation(context: Context, risk: AppRisk): String = when (risk.essentialRole) {
        EssentialRole.ANDROID_CORE -> context.getString(R.string.app_risk_core_reason)
        EssentialRole.HOME -> context.getString(R.string.app_risk_home_reason)
        EssentialRole.INPUT_METHOD -> context.getString(R.string.app_risk_input_reason)
        EssentialRole.DEVICE_ADMIN -> context.getString(R.string.app_risk_admin_reason)
        null -> if (risk.level == RiskLevel.UNKNOWN) context.getString(R.string.app_risk_unknown_reason)
            else context.getString(R.string.app_risk_community_reason,
                risk.communityDescription?.trim().orEmpty().ifBlank { context.getString(R.string.app_risk_check_features) })
    }

    @ColorRes fun badgeColor(risk: AppRisk): Int = when (risk.level) {
        RiskLevel.RECOMMENDED -> R.color.app_risk_recommended
        RiskLevel.CAUTION -> R.color.app_name_system
        RiskLevel.RISKY -> R.color.app_risk_risky
        RiskLevel.UNSAFE -> R.color.app_name_critical
        RiskLevel.UNKNOWN -> R.color.md_theme_onSurfaceVariant
    }
}
