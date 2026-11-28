package com.aistra.hail.utils

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.getSystemService
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.app.AppRisk
import com.aistra.hail.app.AppRiskClassifier
import com.aistra.hail.app.CommunityRisk
import com.aistra.hail.app.EssentialRole
import com.aistra.hail.app.RiskLevel
import org.json.JSONObject
import java.util.zip.GZIPInputStream

/** Offline, versioned community guidance; no package inventory is sent to a server. */
object AppRisks {
    const val SOURCE_COMMIT = "61fda8ba7b07aff0fb1351e705455ae3961fb868"

    // Loaded once, on the background list-refresh path. A damaged/missing asset fails to Unknown.
    private val classifier by lazy {
        val entries = runCatching {
            val json = app.assets.open("app-risk/uad_lists.json.gz").use { input ->
                GZIPInputStream(input).bufferedReader().use { JSONObject(it.readText()) }
            }
            buildMap {
                json.keys().forEach { pkg ->
                    val entry = json.optJSONObject(pkg) ?: return@forEach
                    put(pkg, CommunityRisk(RiskLevel.fromCommunity(entry.optString("removal")),
                        entry.optString("description")))
                }
            }
        }.onFailure(HLog::e).getOrDefault(emptyMap())
        AppRiskClassifier(entries)
    }

    /** Refresh device-specific roles with the list, rather than permanently caching the old launcher/IME. */
    fun snapshot(context: Context = app): Snapshot {
        val roles = buildMap {
            runCatching {
                val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
                    ?.activityInfo?.packageName?.let { put(it, EssentialRole.HOME) }
            }.onFailure(HLog::e)
            runCatching {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                    ?.let(ComponentName::unflattenFromString)?.packageName
                    ?.let { put(it, EssentialRole.INPUT_METHOD) }
            }.onFailure(HLog::e)
            runCatching {
                context.getSystemService<DevicePolicyManager>()?.activeAdmins?.forEach {
                    put(it.packageName, EssentialRole.DEVICE_ADMIN)
                }
            }.onFailure(HLog::e)
        }
        return Snapshot(classifier, roles)
    }

    class Snapshot internal constructor(
        private val classifier: AppRiskClassifier,
        private val roles: Map<String, EssentialRole>
    ) {
        fun classify(info: ApplicationInfo?): AppRisk = if (info == null) AppRisk() else classifier.classify(
            info.packageName, HPackages.isSystemApp(info),
            systemUid = info.uid % 100_000 == android.os.Process.SYSTEM_UID,
            role = roles[info.packageName]
        )
    }
}
