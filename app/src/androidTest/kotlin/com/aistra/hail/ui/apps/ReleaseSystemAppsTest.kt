package com.aistra.hail.ui.apps

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.aistra.hail.R
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/** Black-box driver. The original signed APK and the optimized fork run in their own uninstrumented process. */
@RunWith(AndroidJUnit4::class)
class ReleaseSystemAppsTest {
    private val host = InstrumentationRegistry.getInstrumentation()
    private val context = host.targetContext
    private val pm = context.packageManager
    private val device = UiDevice.getInstance(host)
    private val target = "com.aistra.hail"
    private fun text(id: Int) = By.text(context.getString(id))
    private fun res(name: String) = By.res(target, name)

    private fun await(selector: BySelector) = requireNotNull(device.wait(Until.findObject(selector), 12_000)) {
        "Missing $selector in ${device.currentPackageName}: " + device.findObjects(By.text(Pattern.compile(".+"))).take(24).map { it.text }
    }

    private fun menu(title: Int, id: String) {
        val description = By.desc(context.getString(title))
        val item = device.findObject(description) ?: device.findObject(res(id))
        if (item != null) item.click() else {
            await(By.desc(context.getString(androidx.appcompat.R.string.abc_action_menu_overflow_description))).click()
            await(text(title)).click()
        }
    }

    private fun expectSetting(pkg: String, expected: Int) {
        val deadline = SystemClock.elapsedRealtime() + 12_000
        while (pm.getApplicationEnabledSetting(pkg) != expected && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100)
        }
        assertEquals("Android override for $pkg", expected, pm.getApplicationEnabledSetting(pkg))
    }

    private fun setFixture(pkg: String, setting: Int) {
        host.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE)
        try { pm.setApplicationEnabledSetting(pkg, setting, 0) }
        finally { host.uiAutomation.dropShellPermissionIdentity() }
    }

    private fun configure(packages: List<String>) {
        await(res("nav_settings")).click()
        await(text(R.string.working_mode)).click()
        await(text(R.string.mode_shizuku_disable)).click()
        device.wait(Until.findObject(By.pkg("moe.shizuku.privileged.api")
            .text(Pattern.compile("Allow all the time", Pattern.CASE_INSENSITIVE))), 4_000)?.click()
        await(text(R.string.mode_shizuku_disable))
        await(res("nav_apps")).click()
        menu(R.string.filter_by, "action_apps_filter")
        await(text(R.string.filter_system_apps)).click()
        await(text(R.string.action_continue)).click()
        menu(R.string.action_search, "action_search")
        for (pkg in packages) {
            await(res("search_src_text")).text = pkg
            val desc = await(res("app_desc").text(pkg))
            val star = generateSequence(desc) { it.parent }.firstNotNullOf { it.findObject(res("app_star")) }
            if (!star.isChecked) star.click()
            assertTrue(device.wait(Until.hasObject(res("app_star").checked(true)), 5_000))
        }
        device.findObject(By.desc(context.getString(androidx.appcompat.R.string.abc_toolbar_collapse_description)))?.click()
    }

    private fun openStatus(label: String) {
        await(res("app_name").text(label)).longClick()
        await(text(R.string.package_status_controls)).click()
    }

    @Test fun freezesAndThawsFromHome() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("hailReleaseParity") == "true")
        val root = args.getString("server") == "root"
        val distribution = args.getString("distribution")
        val fork = distribution == "fork"
        val legacy = distribution == "legacy"
        val upgrade = fork && root
        val apk = pm.getPackageInfo(target, 0)
        when (distribution) {
            "upstream" -> assertEquals("1.11.0", apk.versionName)
            "legacy" -> assertEquals("1.13.2", apk.versionName)
            "fork" -> assertTrue(apk.longVersionCode >= 40)
            else -> error("Unknown APK $distribution")
        }
        val packages = listOf("com.android.deskclock", listOf("com.android.dreams.basic", "com.android.printspooler")
            .first { pkg -> runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess && pm.getLaunchIntentForPackage(pkg) == null })
        packages.forEach { assertTrue(pm.getApplicationInfo(it, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0) }
        val history = context.getSharedPreferences("parity-fixture-restore", 0)
        val initial = packages.associateWith { if (upgrade) history.getInt(it, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) else pm.getApplicationEnabledSetting(it) }
        val serviceLabel = pm.getApplicationInfo(packages.last(), 0).loadLabel(pm).toString()
        val disableState = if (root && !legacy) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        val enabled = PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        var retainForUpgrade = false
        try {
            if (upgrade) packages.forEach { expectSetting(it, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) }
            else packages.forEach { setFixture(it, enabled) }
            context.startActivity(requireNotNull(pm.getLaunchIntentForPackage(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (!upgrade) configure(packages)
            await(res("nav_home")).click()
            await(res("app_name").text(pm.getApplicationInfo(packages.first(), 0).loadLabel(pm).toString()))
            if (upgrade) {
                openStatus(serviceLabel)
                await(text(R.string.apply_full_disable)).click()
                expectSetting(packages.last(), disableState)
                device.pressBack()
            }
            menu(R.string.action_freeze_all, "action_freeze_all")
            packages.forEach { expectSetting(it, disableState) }
            menu(R.string.action_unfreeze_all, "action_unfreeze_all")
            packages.forEach { expectSetting(it, enabled) }
            if (fork) {
                val pkg = packages.last()
                setFixture(pkg, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)
                openStatus(serviceLabel)
                await(res("report").textContains(context.getString(R.string.package_until_used)))
                await(text(R.string.disable_app)).click()
                if (root) {
                    expectSetting(pkg, disableState)
                    await(text(R.string.enable_app)).click()
                    expectSetting(pkg, enabled)
                } else {
                    await(res("report").textContains("Shell cannot change component state"))
                    expectSetting(pkg, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)
                }
                device.pressBack()
            }
            if (legacy) {
                menu(R.string.action_freeze_all, "action_freeze_all")
                packages.forEach { expectSetting(it, disableState) }
                assertTrue(history.edit().also { prefs -> initial.forEach { (pkg, setting) -> prefs.putInt(pkg, setting) } }.commit())
                retainForUpgrade = true
            }
            Log.i("HailParity", "${apk.versionName} / ${args.getString("server")} / ${packages.joinToString()}: Android verified $disableState then $enabled")
        } finally {
            if (!retainForUpgrade) initial.forEach { (pkg, setting) -> setFixture(pkg, setting) }
            if (upgrade) history.edit().clear().commit()
            device.pressHome()
        }
    }
}
