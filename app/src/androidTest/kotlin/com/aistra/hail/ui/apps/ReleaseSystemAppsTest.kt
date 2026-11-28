package com.aistra.hail.ui.apps

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
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
import java.io.File
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
        uiFailure("Missing $selector")
    }

    private fun uiFailure(message: String): String {
        // Capture the failed screen before the test's finally block returns to the launcher.
        // A diagnostic failure must not hide the original assertion.
        runCatching {
            val directory = requireNotNull(context.getExternalFilesDir("verification-release")).apply { mkdirs() }
            val name = "ui-failure-${SystemClock.elapsedRealtime()}"
            runCatching { device.dumpWindowHierarchy(File(directory, "$name.xml")) }
            runCatching { device.takeScreenshot(File(directory, "$name.png")) }
        }
        val visible = runCatching {
            "in ${device.currentPackageName}: " + device.findObjects(By.text(Pattern.compile("(?s).+"))).take(24).map { it.text }
        }.getOrDefault("(UI details unavailable)")
        return "$message $visible"
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

    private fun configure(packages: List<String>, systemApps: Boolean = true, legacyFilterPrompt: Boolean = true) {
        await(res("nav_settings")).click()
        await(text(R.string.working_mode)).click()
        await(text(R.string.mode_shizuku_disable)).click()
        device.wait(Until.findObject(By.pkg("moe.shizuku.privileged.api")
            .text(Pattern.compile("Allow all the time", Pattern.CASE_INSENSITIVE))), 4_000)?.click()
        await(By.textStartsWith(context.getString(R.string.mode_shizuku_disable)))
        await(res("nav_apps")).click()
        if (systemApps) {
            menu(R.string.filter_by, "action_apps_filter")
            await(text(R.string.filter_system_apps)).click()
            if (legacyFilterPrompt) await(text(R.string.action_continue)).click()
        }
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

    private fun settingsAction(id: Int): AccessibilityNodeInfo? {
        val label = context.getString(id)
        val root = host.uiAutomation.rootInActiveWindow ?: return null
        if (root.packageName?.toString() != target) return null
        // Compose's node provider does not implement findAccessibilityNodeInfosByText.
        fun findLabel(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.packageName?.toString() != target) return null
            if (node.isVisibleToUser && node.text?.toString() == label) return node
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                findLabel(child)?.let { return it }
            }
            return null
        }
        var node = findLabel(root)
        while (node != null && node.packageName?.toString() == target && node.isVisibleToUser) {
            if (node.isClickable) return node.takeIf { it.isEnabled }
            node = node.parent
        }
        return null
    }

    private fun settingsItem(id: Int, expectedChecked: Boolean? = null) {
        val selector = By.pkg(target).text(context.getString(id))
        await(res("toolbar").hasDescendant(text(R.string.title_settings)))
        // Lazy preferences have no accessibility node until they enter the viewport.
        // Recheck after each gesture instead of trusting scrollIntoView's end-of-list result.
        for (page in 1..12) {
            if (device.hasObject(selector)) break
            val bounds = await(By.pkg(target).scrollable(true)).visibleBounds
            assertTrue("Settings list must be visible", bounds.width() > 0 && bounds.height() > 0)
            assertTrue("Could not scroll Settings to ${context.getString(id)}", device.swipe(
                bounds.centerX(), bounds.top + bounds.height() * 3 / 4,
                bounds.centerX(), bounds.top + bounds.height() / 4, 30
            ))
            device.waitForIdle(1_000)
        }
        await(selector)
        // The label can be a non-clickable child or partly clipped at the viewport edge.
        // Activate the preference's own semantic action, without guessing a tap position.
        val action = requireNotNull(settingsAction(id)) {
            uiFailure("No enabled clickable Settings ancestor for ${context.getString(id)}")
        }
        if (expectedChecked != null) {
            check(action.isCheckable && action.isChecked != expectedChecked) {
                uiFailure("${context.getString(id)} must be checkable and ${!expectedChecked} before toggling")
            }
        }
        check(action.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            uiFailure("Settings rejected the click on ${context.getString(id)}")
        }
        if (expectedChecked != null) {
            val deadline = SystemClock.elapsedRealtime() + 12_000
            do {
                val current = settingsAction(id)
                if (current?.isCheckable == true && current.isChecked == expectedChecked) return
                SystemClock.sleep(100)
            } while (SystemClock.elapsedRealtime() < deadline)
            error(uiFailure("${context.getString(id)} did not become checked=$expectedChecked"))
        }
    }

    private fun exportLog(server: String) {
        await(res("nav_settings")).click()
        settingsItem(R.string.debug_logging, expectedChecked = false) // Stop first; the captured failure must remain exportable.
        settingsItem(R.string.debug_export)
        val documents = Pattern.compile(".*documentsui")
        await(By.pkg(documents).clazz("android.widget.EditText")).text = "Hail-trace-$server.jsonl"
        await(By.pkg(documents).text(Pattern.compile("save", Pattern.CASE_INSENSITIVE))).click()
        await(res("nav_settings"))
    }

    private fun openStatus(label: String) {
        await(res("app_name").text(label)).longClick()
        await(text(R.string.package_status_controls)).click()
    }

    @Test fun measuresBulkDisableAndEnableOnInstalledFixtures() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("hailBulkBenchmark") == "true")
        val baseline = args.getString("distribution") == "baseline"
        val packages = (1..12).map { "com.aistra.hail.testfixture.bench$it" }
        fun stopped(pkg: String) = pm.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_STOPPED != 0
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(host.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
        try {
            packages.forEach { setFixture(it, PackageManager.COMPONENT_ENABLED_STATE_ENABLED) }
            context.startActivity(requireNotNull(pm.getLaunchIntentForPackage(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (baseline) configure(packages, systemApps = false)
            await(res("nav_settings")).click()
            settingsItem(R.string.debug_logging, expectedChecked = true)
            await(res("nav_home")).click()
            repeat(6) {
                // Model a mixed workload: one running app and eleven that Android has already stopped.
                packages.forEach { shell("am force-stop $it") }
                val active = packages.first()
                context.startActivity(requireNotNull(pm.getLaunchIntentForPackage(active)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                assertTrue(device.wait(Until.hasObject(By.pkg(active)), 10_000))
                assertFalse(stopped(active))
                device.pressBack()
                await(res("nav_home"))
                assertEquals(11, packages.count(::stopped))
                menu(R.string.action_freeze_all, "action_freeze_all")
                packages.forEach { expectSetting(it, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) }
                val deadline = SystemClock.elapsedRealtime() + 10_000
                while (!stopped(active) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
                assertTrue(packages.all(::stopped))
                menu(R.string.action_unfreeze_all, "action_unfreeze_all")
                packages.forEach { expectSetting(it, PackageManager.COMPONENT_ENABLED_STATE_ENABLED) }
            }
            exportLog(if (baseline) "benchmark-baseline" else "benchmark-current")
        } finally {
            packages.forEach { setFixture(it, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) }
            device.pressHome()
        }
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
            "fork" -> {
                assertEquals(requireNotNull(args.getString("releaseVersion")), apk.versionName)
                assertEquals(requireNotNull(args.getString("releaseCode")).toLong(), apk.longVersionCode)
            }
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
            if (!upgrade) configure(packages, legacyFilterPrompt = !fork)
            if (fork) {
                await(res("nav_settings")).click()
                settingsItem(R.string.debug_logging, expectedChecked = true)
                // Restart the release process. Recording must resume from its persisted opt-in.
                device.pressHome()
                ParcelFileDescriptor.AutoCloseInputStream(host.uiAutomation.executeShellCommand("am force-stop $target")).use { it.readBytes() }
                context.startActivity(requireNotNull(pm.getLaunchIntentForPackage(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
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
            if (fork) menu(R.string.action_freeze_all, "action_freeze_all")
            menu(R.string.action_unfreeze_all, "action_unfreeze_all")
            packages.forEach { expectSetting(it, enabled) }
            if (distribution == "upstream" && !root) {
                val pkg = packages.last()
                setFixture(pkg, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)
                for (freeze in listOf(true, false)) {
                    if (freeze) menu(R.string.action_freeze_all, "action_freeze_all")
                    else menu(R.string.action_unfreeze_all, "action_unfreeze_all")
                    expectSetting(packages.first(), if (freeze) disableState else enabled)
                    device.waitForIdle()
                    expectSetting(pkg, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)
                }
                Log.i("HailParity", "Upstream shell retained setting 4 through freeze and thaw while Clock changed")
            }
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
                setFixture(pkg, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)
                menu(R.string.action_freeze_all, "action_freeze_all")
                expectSetting(packages.first(), disableState)
                if (root) expectSetting(pkg, disableState) else {
                    await(res("action_feedback_message").textContains("Shell cannot change component state"))
                    expectSetting(pkg, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)
                    assertFalse("Freeze failures remain inline", device.hasObject(By.res("android", "button1")))
                    await(res("dismiss_action_feedback")).click()
                }
                exportLog(if (root) "root" else "shell")
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
