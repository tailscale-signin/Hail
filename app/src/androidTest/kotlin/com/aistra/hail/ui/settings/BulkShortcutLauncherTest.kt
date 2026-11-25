package com.aistra.hail.ui.settings

import android.content.Intent
import android.content.pm.ShortcutManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class BulkShortcutLauncherTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    private fun await(selector: BySelector) = requireNotNull(device.wait(Until.findObject(selector), 15_000)) {
        val labels = device.findObjects(By.text(Pattern.compile(".+"))).map { it.text }.take(20)
        "Expected $selector in ${device.currentPackageName}. Visible labels: $labels"
    }

    @Test fun dismissRetryApproveAndRepeatFromSettings() {
        val packages = listOf("com.android.settings", context.packageName)
        val originalApps = HailData.checkedList.toList()
        val title = context.getString(R.string.add_all_app_shortcuts)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .resolveActivity(context.packageManager)!!.packageName
        val approve = By.pkg(launcher).text(Pattern.compile("add( automatically| to home screen)?", Pattern.CASE_INSENSITIVE))
        try {
            compose.runOnUiThread {
                HailData.checkedList.clear()
                HailData.checkedList.addAll(packages.map { AppInfo(it) })
            }
            await(By.res(context.packageName, "nav_settings")).click()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(title))
            compose.onNodeWithText(title).performClick()
            await(approve)
            device.pressBack()
            compose.onNodeWithText(context.getString(R.string.shortcut_batch_retry)).performClick()
            val manager = requireNotNull(context.getSystemService(ShortcutManager::class.java))
            for (pkg in packages) {
                await(approve).click()
                compose.waitUntil(timeoutMillis = 15_000) { manager.pinnedShortcuts.any { it.id == pkg } }
                val shortcut = manager.pinnedShortcuts.single { it.id == pkg }
                assertEquals(HailApi.ACTION_LAUNCH, shortcut.intent?.action)
                assertEquals(ApiActivity::class.java.name, shortcut.intent?.component?.className)
                assertEquals(pkg, shortcut.intent?.getStringExtra(HailData.KEY_PACKAGE))
            }
            assertResult(added = 2, existing = 0)
            compose.onNodeWithText(context.getString(android.R.string.ok)).performClick()
            compose.onNodeWithText(title).performClick()
            assertResult(added = 0, existing = 2)
        } finally {
            compose.runOnUiThread {
                HailData.checkedList.clear()
                HailData.checkedList.addAll(originalApps)
            }
        }
    }

    private fun assertResult(added: Int, existing: Int) {
        val message = context.getString(R.string.shortcut_batch_result, added, existing, 0)
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
