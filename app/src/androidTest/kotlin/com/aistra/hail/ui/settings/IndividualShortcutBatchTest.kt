package com.aistra.hail.ui.settings

import android.Manifest
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.aistra.hail.R
import com.aistra.hail.app.HailApi
import com.aistra.hail.app.HailData
import com.aistra.hail.shortcut.BatchState
import com.aistra.hail.shortcut.BatchStatus
import com.aistra.hail.shortcut.ShortcutBatchActivity
import com.aistra.hail.shortcut.ShortcutBatchController
import com.aistra.hail.shortcut.ShortcutBatchStore
import com.aistra.hail.shortcut.ShortcutPinAccessibilityService
import com.aistra.hail.ui.api.InternalActionActivity
import com.aistra.hail.ui.main.MainActivity
import com.aistra.hail.widget.AllAppsWidget
import com.aistra.hail.widget.AllAppsWidgetCatalog
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/** Real Launcher3 pinning. Secure-setting changes are confined to the explicit CI emulator fixture. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class IndividualShortcutBatchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager get() = requireNotNull(context.getSystemService(ShortcutManager::class.java))
    private val assistant = ComponentName(context, ShortcutPinAccessibilityService::class.java)
    private lateinit var device: UiDevice
    private var originalFlags = 0
    private var flagsChanged = false
    private var settingsCaptured = false
    private var originalServices: String? = null
    private var originalAccessibilityEnabled: String? = null
    private var originalMode = ""
    private var originalBiometric = false
    private val launcher get() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        .resolveActivity(context.packageManager)!!.packageName
    private val manualAdd get() = By.pkg(launcher)
        .text(Pattern.compile("add( automatically| to home screen)?", Pattern.CASE_INSENSITIVE))

    @Before fun prepareDisposableEmulator() {
        assumeTrue("Never enable an accessibility service on a normal test device",
            InstrumentationRegistry.getArguments().getString("hailCiFixtures") == "true")
        originalFlags = Configurator.getInstance().uiAutomationFlags
        Configurator.getInstance().setUiAutomationFlags(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        flagsChanged = true
        automation()
        device = UiDevice.getInstance(instrumentation)
        assumeTrue("The fixture opt-in also requires an actual emulator",
            device.executeShellCommand("getprop ro.kernel.qemu").trim() == "1" ||
                device.executeShellCommand("getprop ro.boot.qemu").trim() == "1")
        originalServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        originalAccessibilityEnabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        originalMode = HailData.workingMode
        originalBiometric = HailData.biometricLogin
        settingsCaptured = true
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(HailData.WORKING_MODE, HailData.MODE_DEFAULT)
            .putBoolean(HailData.BIOMETRIC_LOGIN, false).commit()
        runBlocking { withTimeout(10_000) { ShortcutBatchController.initialize(context) } }
        ShortcutBatchController.cancel()
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
    }

    @After fun restoreAccessibilityAndPreferences() {
        try {
            if (settingsCaptured) {
                ShortcutBatchController.cancel()
                try {
                    runBlocking { withTimeout(10_000) { ShortcutBatchController.awaitSaved() } }
                } finally {
                    restoreSecure(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, originalServices)
                    restoreSecure(Settings.Secure.ACCESSIBILITY_ENABLED, originalAccessibilityEnabled)
                    PreferenceManager.getDefaultSharedPreferences(context).edit()
                        .putString(HailData.WORKING_MODE, originalMode)
                        .putBoolean(HailData.BIOMETRIC_LOGIN, originalBiometric).commit()
                    device.pressHome()
                }
            }
        } finally {
            if (flagsChanged) {
                Configurator.getInstance().setUiAutomationFlags(originalFlags)
                instrumentation.getUiAutomation(originalFlags)
            }
        }
    }

    @Test fun oneSettingsClickAutomaticallyPinsAllAppsAsSeparateIcons() {
        val catalog = AllAppsWidgetCatalog.load(context)
        val expected = catalog.map { it.packageName }.toSet()
        val fixtures = setOf("com.aistra.hail.testfixture.one", "com.aistra.hail.testfixture.two")
        assertTrue("Production inventory must include both real launchable fixtures", expected.containsAll(fixtures))
        val before = pinnedIds()
        assertTrue("This gate must exercise new pins, not merely skip already-pinned fixtures", fixtures.none { it in before })
        val widgetsBefore = AllAppsWidget.widgetIds(context).toSet()
        setAssistantEnabled(true)

        ActivityScenario.launch(MainActivity::class.java).use {
            await(By.res(context.packageName, "nav_settings")).click()
            scrollToText(R.string.add_all_app_shortcuts)
            val previousBatch = ShortcutBatchController.state.value.batchId
            clickAppText(R.string.add_all_app_shortcuts) // The only start click for the entire production catalog.

            // Deliberately no UiDevice/UiAutomation clicks, gestures, or launcher "Add" actions here.
            // Only the production accessibility service can approve these native launcher requests.
            val completed = waitForBatch(previousBatch, expected, timeout = maxOf(90_000L, expected.size * 5_000L))
            assertTrue(completed.automatic)
            assertEquals(expected, completed.packages.toSet())
            assertTrue("Every eligible app must be processed without silently skipping", completed.skipped.isEmpty())
            assertEquals(expected - before, completed.added.toSet())
            assertEquals(expected.intersect(before), completed.existing.toSet())
            assertEquals(widgetsBefore, AllAppsWidget.widgetIds(context).toSet())
            assertTrue(pinnedIds().containsAll(expected))
            manager.pinnedShortcuts.filter { it.id in expected }.forEach { pin ->
                assertTrue(pin.isPinned)
                assertTrue(pin.isEnabled)
                assertEquals(HailApi.ACTION_LAUNCH, pin.intent?.action)
                assertEquals(pin.id, pin.intent?.getStringExtra(HailData.KEY_PACKAGE))
                assertEquals(InternalActionActivity::class.java.name, pin.intent?.component?.className)
            }
            val nativeIcons = verifyIndependentLauncherIcons(catalog.filter { it.packageName in fixtures }.map { it.label })
            verificationFolder()?.let { folder ->
                File(folder, "individual-shortcuts.json").writeText(JSONObject()
                    .put("expectedPackages", JSONArray(expected.sorted()))
                    .put("added", JSONArray(completed.added)).put("existing", JSONArray(completed.existing))
                    .put("automaticTestLauncherApprovalClicks", 0)
                    .put("nativeIconEvidence", nativeIcons)
                    .put("widgetIdsBefore", JSONArray(widgetsBefore.toList()))
                    .put("widgetIdsAfter", JSONArray(AllAppsWidget.widgetIds(context).toList()))
                    .toString(2))
            }
        }

        // Repeating the same inventory must update/skip the existing stable IDs, not add duplicate icons.
        val pinsAfter = pinnedIds()
        ActivityScenario.launch<ShortcutBatchActivity>(ShortcutBatchActivity.intent(context)).use { scenario ->
            val previousBatch = ShortcutBatchController.state.value.batchId
            scenario.onActivity { ShortcutBatchController.start(it, expected.toList(), automatic = true) }
            val repeated = waitForBatch(previousBatch, expected, 20_000)
            assertTrue(repeated.added.isEmpty())
            assertEquals(expected, repeated.existing.toSet())
            assertEquals(pinsAfter, pinnedIds())
            assertEquals(widgetsBefore, AllAppsWidget.widgetIds(context).toSet())
        }
    }

    @Test fun manualDismissReopenCancelAndApprovalPreserveDurableState() {
        setAssistantEnabled(false)
        // An installed APK with no launcher activity stays outside the production All Apps inventory.
        // This gives this independent test a stable unpinned target without modifying production filters.
        val pkg = instrumentation.context.packageName
        assertFalse(AllAppsWidgetCatalog.load(context).any { it.packageName == pkg })
        assertFalse(pkg in pinnedIds())
        val widgetsBefore = AllAppsWidget.widgetIds(context).toSet()
        ActivityScenario.launch<ShortcutBatchActivity>(ShortcutBatchActivity.intent(context)).use { scenario ->
            scenario.onActivity { ShortcutBatchController.start(it, listOf(pkg), automatic = false) }
            await(manualAdd)
            waitFor { ShortcutBatchController.state.value.awaiting?.packageName == pkg }
            val pending = requireNotNull(ShortcutBatchController.state.value.awaiting)
            assertFalse(pending.automationClicked)
            device.pressBack() // Dismissing a launcher request must never count as a successful pin.
            context.startActivity(ShortcutBatchActivity.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await(By.text(context.getString(R.string.individual_batch_title)))
            assertFalse(pkg in pinnedIds())
            runBlocking { withTimeout(10_000) { ShortcutBatchController.awaitSaved() } }
            val stored = requireNotNull(ShortcutBatchStore(context).read())
            assertEquals(listOf(pkg), stored.packages)
            assertEquals(0, stored.position)
            assertEquals(pending.requestId, stored.awaiting?.requestId)
            assertFalse(stored.awaiting?.approvalReceived == true)

            scenario.recreate()
            await(By.text(context.getString(R.string.individual_batch_title)))
            assertEquals("Recreation must not silently reissue the dismissed request", pending.requestId,
                ShortcutBatchController.state.value.awaiting?.requestId)
            assertFalse(device.hasObject(manualAdd))
            clickAppText(R.string.individual_batch_cancel)
            waitFor { ShortcutBatchController.state.value.status == BatchStatus.CANCELLED }
            runBlocking { withTimeout(10_000) { ShortcutBatchController.awaitSaved() } }
            val cancelled = requireNotNull(ShortcutBatchStore(context).read())
            assertEquals(BatchStatus.CANCELLED, cancelled.status)
            assertNull(cancelled.awaiting)
            assertFalse(pkg in pinnedIds())

            // The explicit manual fallback still works when the optional service is unavailable.
            val previousBatch = ShortcutBatchController.state.value.batchId
            scenario.onActivity { ShortcutBatchController.start(it, listOf(pkg), automatic = false) }
            await(manualAdd).click() // Manual-only case; the automatic test above never does this.
            val completed = waitForBatch(previousBatch, setOf(pkg), 20_000)
            assertFalse(completed.automatic)
            assertEquals(listOf(pkg), completed.added)
            assertTrue(pkg in pinnedIds())
            assertEquals(widgetsBefore, AllAppsWidget.widgetIds(context).toSet())
        }
    }

    private fun automation() = requireNotNull(instrumentation.getUiAutomation(
        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES))

    private fun setAssistantEnabled(enabled: Boolean) {
        try {
            val services = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != assistant }.toMutableList()
            if (enabled) services += assistant.flattenToString()
            restoreSecure(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services.joinToString(":"))
            restoreSecure(Settings.Secure.ACCESSIBILITY_ENABLED, if (services.isEmpty()) "0" else "1")
            waitFor(15_000) {
                ShortcutPinAccessibilityService.isEnabled(context) == enabled &&
                    ShortcutPinAccessibilityService.isConnected() == enabled
            }
        } catch (failure: Throwable) {
            captureFailure("Assistant fixture setup failed; requestedEnabled=$enabled; $failure")
            throw failure
        }
    }

    private fun restoreSecure(key: String, value: String?) {
        // UiAutomation.executeShellCommand uses Runtime.exec, not a shell: quotes become
        // literal setting data. Direct writes also preserve null and empty original values.
        val uiAutomation = automation()
        uiAutomation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            assertTrue("Could not write secure fixture setting $key",
                Settings.Secure.putString(context.contentResolver, key, value))
            assertEquals("Secure fixture setting must match exactly: $key", value,
                Settings.Secure.getString(context.contentResolver, key))
        } finally {
            uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun pinnedIds() = manager.pinnedShortcuts.mapTo(mutableSetOf()) { it.id }

    private fun waitFor(timeout: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("Timed out; batch=${ShortcutBatchController.state.value}", condition())
    }

    private fun waitForBatch(previousBatch: String?, expected: Set<String>, timeout: Long): BatchState {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            val current = ShortcutBatchController.state.value
            if (current.batchId != null && current.batchId != previousBatch) {
                if (current.status == BatchStatus.PAUSED || current.status == BatchStatus.CANCELLED) captureFailure()
                assertFalse("Batch paused instead of completing: $current", current.status == BatchStatus.PAUSED)
                assertFalse("Batch cancelled unexpectedly: $current", current.status == BatchStatus.CANCELLED)
                if (current.status == BatchStatus.COMPLETED) {
                    assertEquals(expected.size, current.completed)
                    assertTrue("Completion requires launcher readback, not only a callback", pinnedIds().containsAll(expected))
                    return current
                }
            }
            SystemClock.sleep(100)
        }
        captureFailure()
        fail("Batch did not complete. state=${ShortcutBatchController.state.value}; pins=${pinnedIds()}")
        error("unreachable")
    }

    private fun await(selector: BySelector): UiObject2 = requireNotNull(device.wait(Until.findObject(selector), 15_000)) {
        "Expected $selector in ${device.currentPackageName}"
    }

    private fun scrollToText(@androidx.annotation.StringRes id: Int) {
        val selector = By.text(context.getString(id))
        repeat(12) {
            if (device.hasObject(selector)) return
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 3, 25)
        }
        await(selector)
    }

    private fun clickAppText(@androidx.annotation.StringRes id: Int) {
        scrollToText(id)
        val label = context.getString(id)
        var node = automation().rootInActiveWindow?.findAccessibilityNodeInfosByText(label)
            ?.firstOrNull { it.text?.toString() == label }
        while (node != null && !node.isClickable) node = node.parent
        if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true) await(By.text(label)).click()
    }

    private fun verifyIndependentLauncherIcons(labels: List<String>): JSONArray {
        device.pressHome()
        await(By.pkg(launcher).depth(0))
        // Return to the first workspace page, then inspect native icon views across pages.
        repeat(6) { swipeWorkspace(right = true) }
        val observed = linkedMapOf<String, Pair<Int, Rect>>()
        repeat(12) { page ->
            labels.filterNot { it in observed }.forEach { label ->
                device.findObjects(By.pkg(launcher).text(label)).firstOrNull { it.isLongClickable }?.let { icon ->
                    assertTrue("A native launcher icon must support its own long-press/move actions", icon.isLongClickable)
                    assertTrue(icon.isClickable)
                    assertFalse(icon.visibleBounds.isEmpty)
                    observed[label] = page to Rect(icon.visibleBounds)
                }
            }
            if (observed.size == labels.size) {
                val evidence = JSONArray()
                observed.forEach { (label, position) ->
                    evidence.put(JSONObject().put("label", label).put("launcherPackage", launcher)
                        .put("page", position.first).put("bounds", position.second.flattenToString())
                        .put("individuallyLongClickable", true))
                }
                assertEquals("Each app must occupy its own native icon cell", labels.size, observed.values.toSet().size)
                verificationFolder()?.let { device.takeScreenshot(File(it, "individual-app-shortcuts.png")) }
                return evidence
            }
            swipeWorkspace(right = false)
        }
        fail("Expected independent native launcher icons for $labels; found ${observed.keys}")
        error("unreachable")
    }

    private fun swipeWorkspace(right: Boolean) {
        val left = device.displayWidth / 5
        val rightEdge = device.displayWidth * 4 / 5
        device.swipe(if (right) left else rightEdge, device.displayHeight / 2,
            if (right) rightEdge else left, device.displayHeight / 2, 25)
        SystemClock.sleep(200)
    }

    private fun verificationFolder(): File? = context.getExternalFilesDir("verification")?.also { it.mkdirs() }

    private fun captureFailure(reason: String? = null) {
        // Read-only evidence is captured before teardown restores services and dismisses the launcher screen.
        val summary = buildString {
            appendLine(reason ?: "Shortcut batch verification failed")
            appendLine("batch=${ShortcutBatchController.state.value}")
            appendLine("pinned=${runCatching { pinnedIds() }}")
            appendLine("assistantComponent=${assistant.flattenToString()}")
            appendLine("assistantEnabled=${runCatching { ShortcutPinAccessibilityService.isEnabled(context) }}")
            appendLine("assistantConnected=${runCatching { ShortcutPinAccessibilityService.isConnected() }}")
            appendLine("assistantDiagnostics=${runCatching { ShortcutPinAccessibilityService.diagnosticState() }}")
            appendLine("uiAutomationFlags=${Configurator.getInstance().uiAutomationFlags}")
            for (key in listOf(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, Settings.Secure.ACCESSIBILITY_ENABLED)) {
                appendLine("$key=${runCatching { Settings.Secure.getString(context.contentResolver, key) }}")
            }
        }
        android.util.Log.e("IndividualShortcutBatchTest", summary)
        val folder = runCatching { verificationFolder() }.getOrNull() ?: return
        runCatching { File(folder, "individual-shortcut-failure.txt").writeText(summary) }
        runCatching {
            File(folder, "individual-shortcut-accessibility.txt").writeText(readShell("dumpsys accessibility"))
        }
        runCatching {
            val relevant = Regex("accessibility|uiautomation|AndroidRuntime|com\\.aistra\\.hail|Shortcut", RegexOption.IGNORE_CASE)
            File(folder, "individual-shortcut-accessibility-logcat.txt").writeText(
                readShell("logcat -d -t 2000").lineSequence().filter { relevant.containsMatchIn(it) }.joinToString("\n"))
        }
        runCatching { device.dumpWindowHierarchy(File(folder, "individual-shortcut-failure.xml")) }
        runCatching { device.takeScreenshot(File(folder, "individual-shortcut-failure.png")) }
    }

    private fun readShell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        automation().executeShellCommand(command)).bufferedReader().use { it.readText() }
}
