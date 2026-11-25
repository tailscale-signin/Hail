package com.aistra.hail.ui.settings

import android.content.Intent
import android.content.pm.ShortcutManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.HailApi
import com.aistra.hail.app.HailData
import com.aistra.hail.ui.api.ApiActivity
import com.aistra.hail.ui.main.MainActivity
import org.junit.Assert.*
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
        "Expected $selector in the foreground (current package: ${device.currentPackageName})"
    }

    @Test fun dismissRetryApproveAndRepeatFromSettings() {
        val packages = listOf("com.android.settings", context.packageName)
        val originalApps = HailData.checkedList.toList()
        val title = context.getString(R.string.add_all_app_shortcuts)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .resolveActivity(context.packageManager)!!.packageName
        val approve = By.pkg(launcher).text(Pattern.compile("add( automatically| to home screen)?", Pattern.CASE_INSENSITIVE))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    HailData.checkedList.clear()
                    HailData.checkedList.addAll(packages.map { AppInfo(it) })
                }
                await(By.res(context.packageName, "nav_settings")).click()
                val preferences = UiScrollable(UiSelector().scrollable(true).packageName(context.packageName))
                assertTrue(preferences.scrollIntoView(UiSelector().textStartsWith(title)))
                await(By.textStartsWith(title)).click()
                await(approve)
                device.pressBack()
                await(By.text(context.getString(R.string.shortcut_batch_retry))).click()
                for (pkg in packages) {
                    await(approve).click()
                    // Wait until this exact ID is visible before accepting another launcher prompt.
                    val manager = requireNotNull(context.getSystemService(ShortcutManager::class.java))
                    val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
                    while (manager.pinnedShortcuts.none { it.id == pkg } && android.os.SystemClock.elapsedRealtime() < deadline) {
                        android.os.SystemClock.sleep(100)
                    }
                    val shortcut = manager.pinnedShortcuts.single { it.id == pkg }
                    assertEquals(HailApi.ACTION_LAUNCH, shortcut.intent?.action)
                    assertEquals(ApiActivity::class.java.name, shortcut.intent?.component?.className)
                    assertEquals(pkg, shortcut.intent?.getStringExtra(HailData.KEY_PACKAGE))
                }
                await(By.text(context.getString(R.string.shortcut_batch_result, 2, 0, 0)))
                await(By.text(context.getString(android.R.string.ok))).click()
                await(By.textStartsWith(title)).click()
                await(By.text(context.getString(R.string.shortcut_batch_result, 0, 2, 0)))
            }
        } finally {
            HailData.checkedList.clear()
            HailData.checkedList.addAll(originalApps)
        }
    }
}
