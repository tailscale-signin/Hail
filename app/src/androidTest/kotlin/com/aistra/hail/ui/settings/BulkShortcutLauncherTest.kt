package com.aistra.hail.ui.settings

import android.content.Intent
import android.content.pm.ShortcutManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.HailApi
import com.aistra.hail.app.HailData
import com.aistra.hail.ui.api.ApiActivity
import com.aistra.hail.ui.main.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class BulkShortcutLauncherTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    private fun await(selector: BySelector) = requireNotNull(device.wait(Until.findObject(selector), 15_000)) {
        val labels = device.findObjects(By.text(Pattern.compile(".+"))).map { it.text }.take(25)
        "Expected $selector in ${device.currentPackageName}. Visible labels: $labels"
    }

    private fun openBulkPreference(expected: BySelector) {
        val summary = By.text(context.getString(R.string.add_all_app_shortcuts_summary))
        await(summary)
        device.waitForIdle()
        await(summary).click()
        if (device.wait(Until.hasObject(expected), 4_000)) return
        // The preference is unique to Settings. A launcher window or an open batch dialog must never be tapped again.
        if (device.hasObject(summary)) {
            val label = context.getString(R.string.add_all_app_shortcuts_summary)
            var node = instrumentation.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByText(label)?.firstOrNull { it.text?.contains(label) == true }
            while (node != null && !node.isClickable) node = node.parent
            if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true) await(summary).click()
        }
        await(expected)
    }

    @Test fun dismissRetryApproveAndRepeatFromSettings() {
        val packages = listOf("com.android.settings", context.packageName)
        val originals = HailData.checkedList.toList()
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .resolveActivity(context.packageManager)!!.packageName
        val approve = By.pkg(launcher).text(Pattern.compile("add( automatically| to home screen)?", Pattern.CASE_INSENSITIVE))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity {
                    HailData.checkedList.clear()
                    HailData.checkedList.addAll(packages.map { AppInfo(it) })
                }
                await(By.res(context.packageName, "nav_settings")).click()
                await(By.text(context.getString(R.string.working_mode)))
                val lastPreference = By.text(context.getString(R.string.action_clear_dynamic_shortcuts))
                val x = device.displayWidth / 2
                for (page in 1..10) {
                    if (device.hasObject(lastPreference)) break
                    device.swipe(x, device.displayHeight * 3 / 4, x, device.displayHeight / 3, 30)
                    device.waitForIdle(1_000)
                }
                openBulkPreference(approve)
                device.pressBack()
                await(By.text(Pattern.compile(context.getString(R.string.shortcut_batch_retry), Pattern.CASE_INSENSITIVE))).click()
                val manager = requireNotNull(context.getSystemService(ShortcutManager::class.java))
                for (pkg in packages) {
                    await(approve).click()
                    val deadline = SystemClock.elapsedRealtime() + 15_000
                    while (manager.pinnedShortcuts.none { it.id == pkg } && SystemClock.elapsedRealtime() < deadline) {
                        SystemClock.sleep(100)
                    }
                    val shortcut = manager.pinnedShortcuts.single { it.id == pkg }
                    assertEquals(HailApi.ACTION_LAUNCH, shortcut.intent?.action)
                    assertEquals(ApiActivity::class.java.name, shortcut.intent?.component?.className)
                    assertEquals(pkg, shortcut.intent?.getStringExtra(HailData.KEY_PACKAGE))
                }
                await(By.text(context.getString(R.string.shortcut_batch_result, 2, 0, 0)))
                await(By.text(context.getString(android.R.string.ok))).click()
                openBulkPreference(By.text(context.getString(R.string.shortcut_batch_result, 0, 2, 0)))
            } finally {
                scenario.onActivity {
                    HailData.checkedList.clear()
                    HailData.checkedList.addAll(originals)
                }
            }
        }
    }
}
