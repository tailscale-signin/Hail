package com.aistra.hail.ui.apps

import android.content.pm.ApplicationInfo
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.AppRisk
import com.aistra.hail.app.RiskEvidence
import com.aistra.hail.app.RiskLevel
import com.aistra.hail.ui.home.HomeRow
import com.aistra.hail.ui.home.PagerAdapter
import com.aistra.hail.ui.main.MainActivity
import com.aistra.hail.utils.AppRisks
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppRiskPresentationTest {
    @Test fun offlineCommunitySnapshotAndCoreFallbackAreAvailable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val risks = AppRisks.snapshot(context)
        fun info(pkg: String) = ApplicationInfo().apply {
            packageName = pkg
            flags = ApplicationInfo.FLAG_SYSTEM
            uid = 12345
        }
        val known = risks.classify(info("com.facebook.katana"))
        assertEquals(RiskLevel.RECOMMENDED, known.level)
        assertEquals(RiskEvidence.COMMUNITY_LIST, known.evidence)
        assertEquals(RiskLevel.RECOMMENDED, known.communityLevel)
        assertFalse(known.communityDescription.isNullOrBlank())
        assertEquals(RiskLevel.UNSAFE, risks.classify(info("com.android.systemui")).level)
        assertEquals(RiskLevel.UNKNOWN, risks.classify(info("com.oem.unknown.future.package")).level)
        val dependency = risks.classify(info("com.huawei.meetime"))
        assertEquals(listOf("com.huawei.hwvoipservice"), dependency.dependencies)
        val explanation = AppRiskPresentation.explanation(context, dependency)
        assertTrue(explanation.contains("com.huawei.hwvoipservice"))
        assertTrue(explanation.contains(AppRisks.SOURCE_COMMIT.take(12)))
        assertTrue(explanation.contains(context.getString(R.string.app_risk_evidence_limit)))
    }

    @Test fun appRowsKeepThemeColorsAndExpandGuidanceInline() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { host ->
                val adapter = AppsAdapter()
                val holder = adapter.onCreateViewHolder(FrameLayout(host), 0)
                val name = holder.itemView.findViewById<TextView>(R.id.app_name)
                val reason = holder.itemView.findViewById<TextView>(R.id.app_risk_reason)
                val badge = holder.itemView.findViewById<TextView>(R.id.app_risk)
                val originalColors = name.textColors
                val originalBadgeColors = badge.textColors
                fun row(pkg: String, risk: AppRisk) = InstalledRow(
                    ApplicationInfo(host.applicationInfo).apply { packageName = pkg }, pkg,
                    frozen = true, installed = true, checked = false, grayscale = true, sortTime = 0,
                    risk = risk
                )
                adapter.submitList(listOf(
                    row("test.critical", AppRisk(RiskLevel.UNSAFE, system = true,
                        communityDescription = "Keep this dependency.\nAnother feature needs it.")),
                    row("test.system", AppRisk(RiskLevel.UNKNOWN, system = true)),
                    row("test.user", AppRisk())
                ))
                adapter.onBindViewHolder(holder, 0)
                assertSame(originalColors, name.textColors)
                assertSame(originalBadgeColors, badge.textColors)
                assertFalse(name.isEnabled)
                assertTrue(badge.text.contains(host.getString(R.string.app_risk_unsafe)))
                assertEquals(2, reason.maxLines)
                reason.performClick()
                assertEquals(Int.MAX_VALUE, reason.maxLines)
                adapter.onBindViewHolder(holder, 1)
                assertSame(originalColors, name.textColors)
                assertSame(originalBadgeColors, badge.textColors)
                assertEquals(2, reason.maxLines)
                assertTrue(badge.text.contains(host.getString(R.string.app_risk_unknown)))
                adapter.onBindViewHolder(holder, 2)
                assertSame(originalColors, name.textColors)
                assertSame(originalBadgeColors, badge.textColors)
                adapter.onViewRecycled(holder)
            }
        }
    }

    @Test fun homeRowsKeepThemeColorsWhileSelectedAndHideInactiveLabel() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { host ->
                val adapter = PagerAdapter()
                val holder = adapter.onCreateViewHolder(FrameLayout(host), 0)
                val originalColors = holder.binding.appName.textColors
                fun row(pkg: String, risk: AppRisk, selected: Boolean = false) = HomeRow(
                    AppInfo(pkg), null, pkg, AppInfo.State.NOT_INSTALLED,
                    pinned = false, whitelisted = false, selected = selected, grayscale = true,
                    fontSize = 14f, risk = risk
                )
                adapter.submitList(listOf(
                    row("test.critical", AppRisk(RiskLevel.UNSAFE, system = true), selected = true),
                    row("test.system", AppRisk(system = true)),
                    row("test.user", AppRisk())
                ))
                adapter.onBindViewHolder(holder, 0)
                assertSame(originalColors, holder.binding.appName.textColors)
                assertTrue(holder.binding.appName.text.startsWith("✓ "))
                assertEquals(View.GONE, holder.binding.appAvailability.visibility)
                adapter.onBindViewHolder(holder, 1)
                assertSame(originalColors, holder.binding.appName.textColors)
                adapter.onBindViewHolder(holder, 2)
                assertSame(originalColors, holder.binding.appName.textColors)
                assertEquals(View.GONE, holder.binding.appAvailability.visibility)
                adapter.onViewRecycled(holder)
            }
        }
    }
}
