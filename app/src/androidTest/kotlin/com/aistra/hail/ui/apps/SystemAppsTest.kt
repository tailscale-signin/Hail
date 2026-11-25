package com.aistra.hail.ui.apps

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.EditText
import android.widget.FrameLayout
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.aistra.hail.R
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.HailData
import com.aistra.hail.ui.main.MainActivity
import com.aistra.hail.ui.home.HomeRow
import com.aistra.hail.ui.home.PagerAdapter
import com.aistra.hail.utils.HPackages
import com.aistra.hail.utils.HShizuku
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import rikka.shizuku.Shizuku
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class SystemAppsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
    private val pm = ctx.packageManager

    private fun fixture(): ApplicationInfo {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hailCiFixtures") == "true")
        return listOf("com.android.deskclock", "com.android.calculator2", "com.android.gallery3d")
            .mapNotNull { HPackages.getApplicationInfoOrNull(it) }
            .first { HPackages.isSystemApp(it) && it.enabled && it.flags and ApplicationInfo.FLAG_INSTALLED != 0 }
    }

    private fun await(selector: BySelector) = requireNotNull(device.wait(Until.findObject(selector), 12_000)) {
        val labels = device.findObjects(By.text(Pattern.compile(".+"))).take(20).map { it.text }
        val actions = device.findObjects(By.desc(Pattern.compile(".+"))).take(12).map { it.contentDescription }
        "Missing $selector in ${device.currentPackageName}. Labels $labels. Actions $actions"
    }

    private fun waitFor(message: String, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 12_000
        while (!predicate() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
        assertTrue(message, predicate())
    }

    private fun text(id: Int) = By.text(ctx.getString(id))

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }

    private fun openToolbarItem(id: String, title: Int) {
        val description = ctx.getString(title)
        val button = device.wait(Until.findObject(By.desc(description)), 2_000)
            ?: device.findObject(By.res(ctx.packageName, id))
        if (button != null) {
            button.click()
        } else {
            await(By.desc(ctx.getString(androidx.appcompat.R.string.abc_action_menu_overflow_description))).click()
            await(text(title)).click()
        }
    }

    private fun searchForApp(scenario: ActivityScenario<MainActivity>, pkg: String): BySelector {
        openToolbarItem("action_search", R.string.action_search)
        scenario.onActivity { activity ->
            activity.findViewById<EditText>(androidx.appcompat.R.id.search_src_text).setText(pkg)
        }
        return By.res(ctx.packageName, "app_desc").text(pkg)
    }

    private fun withAppFilters(showSystem: Boolean, run: (ApplicationInfo) -> Unit) {
        val info = fixture()
        val oldMode = prefs.getString(HailData.WORKING_MODE, null)
        val filterKeys = listOf(HailData.FILTER_SYSTEM_APPS, HailData.FILTER_USER_APPS,
            HailData.FILTER_FROZEN_APPS, HailData.FILTER_UNFROZEN_APPS)
        val oldFilters = prefs.all.filterKeys { it in filterKeys }
        prefs.edit().putString(HailData.WORKING_MODE, HailData.MODE_DEFAULT)
            .apply { filterKeys.forEach { remove(it) } }
            .putBoolean(HailData.FILTER_SYSTEM_APPS, showSystem)
            .putBoolean(HailData.FILTER_USER_APPS, !showSystem).commit()
        try {
            run(info)
        } finally {
            prefs.edit().putString(HailData.WORKING_MODE, oldMode).apply {
                filterKeys.forEach { key ->
                    if (key in oldFilters) putBoolean(key, oldFilters[key] as Boolean) else remove(key)
                }
            }.commit()
            device.pressHome()
        }
    }

    @Test fun idleModeOpensAndroidSettingsForTheSelectedSystemApp() {
        withAppFilters(false) { info ->
            val pkg = info.packageName
            val oldSetting = pm.getApplicationEnabledSetting(pkg)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await(By.res(ctx.packageName, "nav_apps")).click()
                openToolbarItem("action_apps_filter", R.string.filter_by)
                await(text(R.string.filter_system_apps)).click()
                await(text(R.string.action_continue)).click()
                await(searchForApp(scenario, pkg)).longClick()
                await(text(R.string.disable_in_settings)).click()
                val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                val settingsPkg = requireNotNull(details.resolveActivity(pm)).packageName
                await(By.pkg(settingsPkg).text(info.loadLabel(pm).toString()))
                assertEquals(oldSetting, pm.getApplicationEnabledSetting(pkg))
            }
        }
    }

    @Test fun disablesAndEnablesAPreinstalledAppWithShizukuPermission() = withAppFilters(true) { info ->
        val pkg = info.packageName
        val oldSetting = pm.getApplicationEnabledSetting(pkg)
        val wasChecked = HailData.isChecked(pkg)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await(By.res(ctx.packageName, "nav_settings")).click()
                await(text(R.string.working_mode)).click()
                await(text(R.string.mode_shizuku_disable)).click()
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    await(By.pkg("moe.shizuku.privileged.api").text(Pattern.compile("Allow all the time", Pattern.CASE_INSENSITIVE))).click()
                }
                waitFor("Hail saved the authorized Shizuku mode") { HailData.workingMode == HailData.MODE_SHIZUKU_DISABLE }
                assertEquals(2000, Shizuku.getUid())
                val packageService = HShizuku.Packages()
                assertTrue(packageService.installed().any { it.packageName == pkg })
                assertEquals(true, packageService.find(pkg)?.installed)
                runBlocking {
                    for (disable in listOf(true, false)) {
                        val result = AppManager.changeFrozen(listOf(pkg), disable)
                        assertEquals(AppManager.resultMessage(result, disable), setOf(pkg), result.changed)
                        val expected = if (disable) PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER else PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        assertEquals(expected, pm.getApplicationEnabledSetting(pkg))
                        assertEquals(expected, packageService.setting(pkg))
                    }
                }
                await(By.res(ctx.packageName, "nav_apps")).click()
                val row = searchForApp(scenario, pkg)
                await(row).longClick()
                await(text(R.string.disable_app)).click()
                waitFor("Android disabled $pkg for this user") {
                    pm.getApplicationEnabledSetting(pkg) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                }
                device.waitForIdle()
                await(row).longClick()
                await(text(R.string.enable_app)).click()
                waitFor("Android enabled $pkg again") {
                    pm.getApplicationEnabledSetting(pkg) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                }
                assertEquals(wasChecked, HailData.isChecked(pkg))
                val saved = HailData.checkedList.toList()
                try {
                    scenario.onActivity {
                        HailData.checkedList.clear()
                        HailData.checkedList.add(AppInfo(pkg))
                        prefs.edit().putString(HailData.WORKING_MODE, HailData.MODE_DEFAULT).commit()
                    }
                    await(By.res(ctx.packageName, "nav_home")).click()
                    await(By.res(ctx.packageName, "app_name").text(info.loadLabel(pm).toString())).longClick()
                    await(text(R.string.package_status_controls)).click()
                    await(text(R.string.disable_with_shizuku)).click()
                    waitFor("Home disabled the package for this user") { pm.getApplicationEnabledSetting(pkg) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER }
                    await(text(R.string.enable_with_shizuku)).click()
                    waitFor("Home enabled the package again") { pm.getApplicationEnabledSetting(pkg) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED }
                    assertEquals(HailData.MODE_DEFAULT, HailData.workingMode)
                    device.pressBack()
                } finally {
                    scenario.onActivity { HailData.checkedList.clear(); HailData.checkedList.addAll(saved) }
                }
                Log.i("HailSystemApp", "Disabled and re-enabled preinstalled $pkg through Shizuku shell")
            }
        } finally {
            val reset = if (oldSetting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) "default-state" else "enable"
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
                .executeShellCommand("pm $reset --user ${HPackages.myUserId} $pkg")).use { it.readBytes() }
        }
    }

    @Test fun deniedSystemAppChangePreservesEnabledState() = runBlocking {
        val pkg = fixture().packageName
        val oldSetting = pm.getApplicationEnabledSetting(pkg)
        val oldMode = prefs.getString(HailData.WORKING_MODE, null)
        try {
            prefs.edit().putString(HailData.WORKING_MODE, HailData.MODE_PRIVAPP_DISABLE).commit()
            val denied = AppManager.changeFrozen(listOf(pkg), true)
            assertEquals(setOf(pkg), denied.failed)
            assertTrue(denied.changed.isEmpty())
            val message = AppManager.resultMessage(denied, true)
            assertTrue(message.startsWith(ctx.getString(R.string.system_app_change_failed, pkg)))
            assertTrue("Expected the platform refusal", message.lineSequence().drop(1).any { it.contains(pkg) })
            assertEquals(oldSetting, pm.getApplicationEnabledSetting(pkg))
        } finally {
            prefs.edit().putString(HailData.WORKING_MODE, oldMode).commit()
        }
        Unit
    }

    @Test fun retainedSystemAppShowsProfileStatusAndCanBeRecheckedFromHome() {
        val info = fixture()
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val pkg = info.packageName
        val user = HPackages.myUserId
        val label = info.loadLabel(pm).toString()
        val saved = HailData.checkedList.toList()
        val installed = "package:$pkg"
        fun listed() = shell("pm list packages --user $user $pkg").lineSequence().any { it == installed }
        assertTrue(listed())
        try {
            assertTrue(shell("pm uninstall -k --user $user $pkg").contains("Success"))
            assertFalse(listed())
            assertTrue(shell("pm list packages -u --user $user $pkg").lineSequence().any { it == installed })
            assertEquals(false, HPackages.findForUser(pkg)?.installed)
            assertEquals(false, HPackages.packagesForUser()[pkg]?.installed)
            assertEquals(setOf(pkg), runBlocking { AppManager.changeFrozen(listOf(pkg), true) }.notInstalled)
            assertFalse(listed())
            instrumentation.runOnMainSync { HailData.checkedList.clear(); HailData.checkedList.add(AppInfo(pkg)) }
            ActivityScenario.launch(MainActivity::class.java).use {
                await(By.res(ctx.packageName, "nav_home")).click()
                await(text(R.string.package_inactive_here))
                val row = By.res(ctx.packageName, "app_name").text(label)
                await(row).longClick()
                fun report(id: Int) = By.res(ctx.packageName, "report").textContains(ctx.getString(id))
                await(report(R.string.package_retained_here))
                await(text(R.string.check_again)).click()
                assertFalse(listed())
                assertTrue(shell("cmd package install-existing --user $user $pkg").contains(pkg))
                waitFor("Android restored fixture") { listed() }
                await(text(R.string.check_again)).click()
                await(report(R.string.package_enabled))
                device.pressBack()
                await(row)
                assertTrue(device.wait(Until.gone(text(R.string.package_inactive_here)), 10_000))
            }
            Log.i("HailSystemApp", "Verified retained $pkg, no mutation while absent, restored current-user Home controls")
        } finally {
            if (!listed()) shell("cmd package install-existing --user $user $pkg")
            instrumentation.runOnMainSync { HailData.checkedList.clear(); HailData.checkedList.addAll(saved) }
            device.pressHome()
        }
    }

    @Test fun installedEvidenceAndRecycledHomeColorsAreIndependentOfRetainedFlags() {
        val info = fixture()
        val missingFlag = ApplicationInfo(info).apply { flags = flags and ApplicationInfo.FLAG_INSTALLED.inv() }
        val verified = HPackages.UserPackage(missingFlag, true)
        assertEquals(AppInfo.State.UNFROZEN, AppManager.state(verified, HailData.MODE_SHIZUKU_DISABLE))
        assertEquals(AppInfo.State.NOT_INSTALLED, AppManager.state(verified.copy(installed = false), HailData.MODE_SHIZUKU_DISABLE))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { host ->
                val adapter = PagerAdapter()
                val holder = adapter.onCreateViewHolder(FrameLayout(host), 0)
                val colors = holder.binding.appName.textColors
                val absent = HomeRow(AppInfo("test.absent"), null, "Absent", AppInfo.State.NOT_FOUND,
                    false, false, false, true, 14f)
                val active = absent.copy(info = AppInfo(info.packageName), application = info, state = AppInfo.State.UNFROZEN)
                adapter.submitList(listOf(absent, active))
                adapter.onBindViewHolder(holder, 0)
                assertEquals(android.view.View.VISIBLE, holder.binding.appAvailability.visibility)
                adapter.onBindViewHolder(holder, 1)
                assertEquals(android.view.View.GONE, holder.binding.appAvailability.visibility)
                assertSame(colors, holder.binding.appName.textColors)
                adapter.onViewRecycled(holder)
            }
        }
    }
}
