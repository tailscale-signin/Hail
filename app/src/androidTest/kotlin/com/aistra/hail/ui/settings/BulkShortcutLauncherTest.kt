package com.aistra.hail.ui.settings

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.ShortcutManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.HailApi
import com.aistra.hail.app.HailData
import com.aistra.hail.ui.api.InternalActionActivity
import com.aistra.hail.ui.main.MainActivity
import com.aistra.hail.utils.HShortcuts
import com.aistra.hail.widget.AllAppsWidget
import com.aistra.hail.widget.AllAppsWidgetCatalog
import com.aistra.hail.widget.AllAppsWidgetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/** Preserve existing widget bindings and individual shortcut identities across app updates. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class BulkShortcutLauncherTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val launcher get() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        .resolveActivity(context.packageManager)!!.packageName
    private val approve get() = By.pkg(launcher).text(Pattern.compile("add( automatically| to home screen)?", Pattern.CASE_INSENSITIVE))

    private fun await(selector: BySelector) = requireNotNull(device.wait(Until.findObject(selector), 15_000)) {
        val labels = device.findObjects(By.text(Pattern.compile(".+"))).map { it.text }.take(25)
        "Expected $selector in ${device.currentPackageName}. Visible labels: $labels"
    }

    private fun waitFor(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
        assertTrue(condition())
    }

    private fun openShortcutSettings() {
        await(By.res(context.packageName, "nav_settings")).click()
        await(By.text(context.getString(R.string.working_mode)))
        val target = By.text(context.getString(R.string.home_surfaces_repair))
        val x = device.displayWidth / 2
        for (page in 1..12) {
            if (device.hasObject(target)) break
            device.swipe(x, device.displayHeight * 3 / 4, x, device.displayHeight / 3, 30)
            device.waitForIdle(1_000)
        }
        await(target)
    }

    private fun clickPreference(@androidx.annotation.StringRes title: Int) {
        val label = context.getString(title)
        var node = instrumentation.uiAutomation.rootInActiveWindow
            ?.findAccessibilityNodeInfosByText(label)?.firstOrNull { it.text?.toString() == label }
        while (node != null && !node.isClickable) node = node.parent
        if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true) await(By.text(label)).click()
    }

    @Test fun existingWidgetCanBeRestoredRefreshedAndAddedAgain() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val originalMode = HailData.workingMode
        val biometric = HailData.biometricLogin
        prefs.edit().putString(HailData.WORKING_MODE, HailData.MODE_DEFAULT).putBoolean(HailData.BIOMETRIC_LOGIN, false).commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                openShortcutSettings()
                assertTrue("Fresh CI installation must have no widget", AllAppsWidget.widgetIds(context).isEmpty())
                instrumentation.runOnMainSync { AllAppsWidget.requestPin(context) }
                await(approve)
                device.pressBack()
                await(By.text(context.getString(R.string.home_surfaces_repair)))
                assertTrue(AllAppsWidget.widgetIds(context).isEmpty())
                instrumentation.runOnMainSync { AllAppsWidget.requestPin(context) }
                await(approve).click()
                waitFor { AllAppsWidget.widgetIds(context).size == 1 }

                val ids = AllAppsWidget.widgetIds(context)
                // Rebuild restored host IDs and exercise the new adapter/PendingIntent identity.
                AllAppsWidget().onRestored(context, intArrayOf(987654), ids)
                val manager = AppWidgetManager.getInstance(context)
                ids.forEach { id -> assertTrue(manager.getAppWidgetOptions(id)
                    .getBoolean(AppWidgetManager.OPTION_APPWIDGET_RESTORE_COMPLETED)) }
                device.pressHome()
                val gridSelector = By.res(context.packageName, "all_apps_grid")
                await(gridSelector)
                if (InstrumentationRegistry.getArguments().getString("hailCiFixtures") == "true") {
                    // Exercise real launcher process loss on the disposable CI device, preserving its layout data.
                    device.executeShellCommand("am force-stop $launcher")
                    device.pressHome()
                    assertEquals(ids.toList(), AllAppsWidget.widgetIds(context).toList())
                    // A restarted launcher may open its default page rather than the widget's page.
                    for (page in 1..5) {
                        if (device.wait(Until.hasObject(gridSelector), 2_000)) break
                        device.swipe(device.displayWidth * 3 / 4, device.displayHeight / 2,
                            device.displayWidth / 4, device.displayHeight / 2, 25)
                    }
                    await(gridSelector)
                }
                context.getExternalFilesDir("verification")?.let { folder ->
                    folder.mkdirs()
                    device.takeScreenshot(java.io.File(folder, "all-apps-widget.png"))
                }
                val label = context.packageManager.getApplicationInfo("com.android.settings", 0)
                    .loadLabel(context.packageManager).toString()
                val cell = By.res(context.packageName, "widget_app_label").text(label)
                for (page in 1..15) {
                    if (device.hasObject(cell)) break
                    await(gridSelector).scroll(Direction.DOWN, 0.8f)
                }
                await(cell).click()
                assertTrue("Widget click must reach Android Settings through Hail", device.wait(
                    Until.hasObject(By.pkg("com.android.settings").depth(0)), 15_000))

                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                await(By.res(context.packageName, "nav_settings"))
                // MainActivity retains Settings and its scroll state on returning from the launcher.
                if (!device.hasObject(By.text(context.getString(R.string.home_surfaces_repair)))) openShortcutSettings()
                val repair = By.text(context.getString(R.string.home_surfaces_repair))
                for (page in 1..4) {
                    if (device.hasObject(repair)) break
                    device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                        device.displayWidth / 2, device.displayHeight / 2, 25)
                }
                clickPreference(R.string.home_surfaces_repair)
                await(By.text(context.getString(R.string.home_surfaces_refreshed)))
                assertEquals("Refreshing must not add duplicate widgets or queue more launcher prompts", ids.toList(), AllAppsWidget.widgetIds(context).toList())
                assertFalse(device.hasObject(approve))
                // Existing/orphaned IDs must never prevent an explicit request to the current launcher.
                instrumentation.runOnMainSync { AllAppsWidget.requestPin(context) }
                await(approve)
                device.pressBack()
                assertEquals(ids.toList(), AllAppsWidget.widgetIds(context).toList())
            }
        } finally {
            prefs.edit().putString(HailData.WORKING_MODE, originalMode).putBoolean(HailData.BIOMETRIC_LOGIN, biometric).commit()
        }
    }

    @Test fun catalogContainsDisabledLaunchersAndUsesFixedPrivateLaunchRoute() {
        val entries = AllAppsWidgetCatalog.load(context)
        assertEquals(entries.size, entries.map { it.packageName }.toSet().size)
        assertTrue(entries.any { it.packageName == "com.android.settings" })
        if (InstrumentationRegistry.getArguments().getString("hailCiFixtures") == "true") {
            assertTrue("Manifest-disabled app remains available to Hail's thaw-and-launch route",
                entries.any { it.packageName == "com.aistra.hail.testfixture.dormant" })
        }
        val factory = AllAppsWidgetFactory(context)
        factory.onDataSetChanged()
        assertTrue(factory.hasStableIds())
        assertEquals(entries.size, factory.getCount())
        val before = (0 until factory.getCount()).map(factory::getItemId)
        factory.onDataSetChanged()
        assertEquals(before, (0 until factory.getCount()).map(factory::getItemId))
        assertEquals(factory.getCount(), before.toSet().size)
        val template = AllAppsWidget.launchTemplate(context, 42)
        assertEquals(InternalActionActivity::class.java.name, template.component?.className)
        assertEquals(HailApi.ACTION_LAUNCH, template.action)
        assertTrue(template.filterEquals(AllAppsWidget.launchTemplate(context, 42)))
        assertFalse(template.filterEquals(AllAppsWidget.launchTemplate(context, 43)))
        val hostileFillIn = Intent(HailApi.ACTION_FREEZE).putExtra(HailData.KEY_PACKAGE, "com.android.settings")
            .putExtra(HailData.KEY_TAG, "Unexpected batch")
        template.fillIn(hostileFillIn, 0)
        assertEquals("Collection items cannot substitute a destructive action", HailApi.ACTION_LAUNCH, template.action)
        assertEquals("com.android.settings", HailApi.parse(template).getStringExtra(HailData.KEY_PACKAGE))
        assertNull(HailApi.parse(template).getStringExtra(HailData.KEY_TAG))
        factory.onDestroy()
    }

    @Test fun pinnedAppShortcutKeepsItsIdAndActionAcrossRefresh() {
        val manager = requireNotNull(context.getSystemService(ShortcutManager::class.java))
        val id = "widget-stability-settings"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                assertTrue(HShortcuts.requestPin(HShortcuts.appPinShortcut(AppInfo("com.android.settings"), id = id)))
            }
            await(approve).click()
            waitFor { manager.pinnedShortcuts.any { it.id == id } }
            assertTrue(HShortcuts.refreshPinnedAppShortcuts())
            val pinned = manager.pinnedShortcuts.single { it.id == id }
            assertEquals(HailApi.ACTION_LAUNCH, pinned.intent?.action)
            assertEquals("com.android.settings", pinned.intent?.getStringExtra(HailData.KEY_PACKAGE))
            assertEquals(InternalActionActivity::class.java.name, pinned.intent?.component?.className)
            assertTrue(pinned.isEnabled)
        }
    }
}
