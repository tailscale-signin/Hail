package com.aistra.hail.ui.settings

import android.Manifest
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.aistra.hail.app.HailApi
import com.aistra.hail.app.HailData
import com.aistra.hail.shortcut.BatchState
import com.aistra.hail.shortcut.BatchStatus
import com.aistra.hail.shortcut.ShortcutBatchActivity
import com.aistra.hail.shortcut.ShortcutBatchController
import com.aistra.hail.shortcut.ShortcutBatchStore
import com.aistra.hail.shortcut.ShortcutPinAccessibilityService
import com.aistra.hail.ui.api.InternalActionActivity
import com.aistra.hail.widget.AllAppsWidget
import com.aistra.hail.widget.AllAppsWidgetCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Runs separately after the normal device suite, only on its explicitly opted-in CI emulator. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class LargeShortcutBatchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager get() = requireNotNull(context.getSystemService(ShortcutManager::class.java))
    private val assistant = ComponentName(context, ShortcutPinAccessibilityService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requests = ConcurrentHashMap<String, String>()
    private val progress = JSONArray()
    private lateinit var device: UiDevice
    private var originalFlags = 0
    private var flagsChanged = false
    private var settingsCaptured = false
    private var originalServices: String? = null
    private var originalAccessibilityEnabled: String? = null
    private var originalMode = ""
    private var originalBiometric = false
    private var startedAt = 0L
    private var novaHomeResumed = false
    private var novaSetupButtonClicks = 0
    private val launcher get() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        .resolveActivity(context.packageManager)!!.packageName
    private val packages = (1..FIXTURE_COUNT).map { "$FIXTURE_PREFIX${String.format(Locale.ROOT, "%03d", it)}" }
    private val expected get() = packages.toSet()

    @Before fun prepareDisposableEmulator() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("The large fixture must never run against a normal device",
            arguments.getString("hailCiFixtures") == "true" &&
                arguments.getString("hailLargeShortcutBatch") == "true")
        originalFlags = Configurator.getInstance().uiAutomationFlags
        Configurator.getInstance().setUiAutomationFlags(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        flagsChanged = true
        automation()
        device = UiDevice.getInstance(instrumentation)
        assumeTrue("Fixture opt-in also requires an actual emulator",
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
        arguments.getString("hailExpectedLauncherPackage")?.let { expectedLauncher ->
            assertEquals("The requested launcher must be the actual default Home", expectedLauncher, launcher)
        }
        arguments.getString("hailExpectedLauncherVersion")?.let { expectedVersion ->
            assertEquals(expectedVersion, context.packageManager.getPackageInfo(launcher, 0).versionName)
        }
        if (arguments.getString("hailExpectedLauncherPackage") == NOVA_PACKAGE) {
            assertEquals(36, Build.VERSION.SDK_INT)
            assertTrue("The official Nova APK needs the image's ARM translation support",
                "arm64-v8a" in Build.SUPPORTED_ABIS)
            try {
                finishNovaFirstRun()
            } catch (failure: Throwable) {
                captureFailure(failure)
                throw failure
            }
        }
        val services = originalServices.orEmpty().split(':')
            .filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != assistant }.toMutableList()
        services += assistant.flattenToString()
        restoreSecure(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services.joinToString(":"))
        restoreSecure(Settings.Secure.ACCESSIBILITY_ENABLED, "1")
        waitFor(15_000) {
            ShortcutPinAccessibilityService.isEnabled(context) && ShortcutPinAccessibilityService.isConnected()
        }
        scope.launch {
            ShortcutBatchController.state.collect { state ->
                state.awaiting?.takeIf { it.packageName.startsWith(FIXTURE_PREFIX) }?.let {
                    requests[it.requestId] = it.packageName
                }
            }
        }
    }

    @After fun restoreAccessibilityAndPreferences() {
        scope.cancel()
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

    @Test fun automaticallyPins284AppsPast115AndSkipsExistingWithoutNewRequests() {
        try {
            val catalog = AllAppsWidgetCatalog.load(context).associateBy { it.packageName }
            assertEquals("The test requires 284 separate installed, launchable APKs", FIXTURE_COUNT,
                catalog.keys.count { it.startsWith(FIXTURE_PREFIX) })
            assertTrue(catalog.keys.containsAll(expected))
            assertEquals("Hail", catalog.getValue(packages[114]).label)
            assertEquals("The item at the reported stop point must match the publisher label", "Hail",
                context.applicationInfo.loadLabel(context.packageManager).toString())
            val before = pinnedIds()
            assertTrue("This gate must create all 284 records", before.intersect(expected).isEmpty())
            val widgetsBefore = AllAppsWidget.widgetIds(context).toSet()
            startedAt = SystemClock.elapsedRealtime()

            ActivityScenario.launch<ShortcutBatchActivity>(ShortcutBatchActivity.intent(context)).use { scenario ->
                val previous = ShortcutBatchController.state.value.batchId
                // One start enters the same production foreground-service queue as the Settings button.
                // The separate normal-suite test covers that Settings button and full catalog wiring.
                scenario.onActivity { ShortcutBatchController.start(it, packages, automatic = true) }
                // No test clicks, gestures, or launcher approvals occur while this queue is active.
                val completed = waitForBatch(previous, 12 * 60_000L, recordProgress = true)
                assertTrue(completed.automatic)
                assertEquals(packages, completed.packages)
                assertEquals(expected, completed.added.toSet())
                assertEquals(FIXTURE_COUNT, completed.added.size)
                assertTrue(completed.existing.isEmpty())
                assertTrue("A problem app must not be silently skipped", completed.skipped.isEmpty())
                assertEquals(expected, pinnedIds().intersect(expected))
                assertEquals(before + expected, pinnedIds())
                val pins = manager.pinnedShortcuts.filter { it.id in expected }
                assertEquals(FIXTURE_COUNT, pins.size)
                assertEquals(FIXTURE_COUNT, pins.map { it.id }.distinct().size)
                pins.forEach { pin ->
                    assertTrue(pin.isPinned)
                    assertTrue(pin.isEnabled)
                    assertEquals(HailApi.ACTION_LAUNCH, pin.intent?.action)
                    assertEquals(pin.id, pin.intent?.getStringExtra(HailData.KEY_PACKAGE))
                    assertEquals(InternalActionActivity::class.java.name, pin.intent?.component?.className)
                }
                runBlocking { withTimeout(10_000) { ShortcutBatchController.awaitSaved() } }
                val stored = requireNotNull(ShortcutBatchStore(context).read())
                assertEquals(BatchStatus.COMPLETED, stored.status)
                assertEquals(FIXTURE_COUNT, stored.position)
                assertEquals(completed.batchId, stored.batchId)
                assertEquals(packages, stored.packages)
                assertEquals(expected, stored.added.toSet())
                assertNull(stored.awaiting)
                waitFor(5_000) { requests.values.toSet() == expected }
                assertEquals("One in-flight native request per app", FIXTURE_COUNT, requests.size)

                // The same 284 stable IDs, including item 115, must be reusable without reissuing pins.
                val requestsBeforeRepeat = requests.keys.toSet()
                val pinsBeforeRepeat = pinnedIds()
                val repeatStartedAt = SystemClock.elapsedRealtime()
                scenario.onActivity { ShortcutBatchController.start(it, packages, automatic = true) }
                val repeated = waitForBatch(completed.batchId, 30_000, recordProgress = false)
                assertEquals(expected, repeated.existing.toSet())
                assertTrue(repeated.added.isEmpty())
                assertTrue(repeated.skipped.isEmpty())
                assertEquals(requestsBeforeRepeat, requests.keys.toSet())
                assertEquals(pinsBeforeRepeat, pinnedIds())
                assertEquals(widgetsBefore, AllAppsWidget.widgetIds(context).toSet())
                val repeatElapsed = SystemClock.elapsedRealtime() - repeatStartedAt
                val nativeIcons = verifyIndependentLauncherIcons(listOf("Hail 001", "Hail 116", "Hail 284"))
                val launcherInfo = context.packageManager.getPackageInfo(launcher, 0)
                File(verificationFolder(), "large-shortcuts.json").writeText(JSONObject()
                    .put("launcherPackage", launcher)
                    .put("launcherVersionName", launcherInfo.versionName)
                    .put("launcherVersionCode", launcherInfo.longVersionCode)
                    .put("androidSdk", Build.VERSION.SDK_INT)
                    .put("deviceAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
                    .put("nativeBridge", device.executeShellCommand("getprop ro.dalvik.vm.native.bridge").trim())
                    .put("novaHomeResumed", novaHomeResumed)
                    .put("novaSetupButtonClicks", novaSetupButtonClicks)
                    .put("fixtureCount", FIXTURE_COUNT)
                    .put("expectedPackages", JSONArray(packages))
                    .put("added", JSONArray(completed.added))
                    .put("skipped", JSONArray(completed.skipped))
                    .put("uniquePinnedFixtureIds", JSONArray(pins.map { it.id }.sorted()))
                    .put("requestCount", requestsBeforeRepeat.size)
                    .put("automaticTestLauncherApprovalClicks", 0)
                    .put("item115LabelEqualsPublisher", true)
                    .put("savedCompletedPosition", stored.position)
                    .put("repeatExistingCount", repeated.existing.size)
                    .put("repeatNewRequestCount", requests.size - requestsBeforeRepeat.size)
                    .put("repeatElapsedMs", repeatElapsed)
                    .put("nativeIconEvidence", nativeIcons)
                    .put("widgetIdsBefore", JSONArray(widgetsBefore.toList()))
                    .put("widgetIdsAfter", JSONArray(AllAppsWidget.widgetIds(context).toList()))
                    .put("progress", progress).toString(2))
            }
        } catch (failure: Throwable) {
            captureFailure(failure)
            throw failure
        }
    }

    /** Uses Nova's normal, source-verified first-run button only on this opted-in emulator. */
    private fun finishNovaFirstRun() {
        assertFalse(ShortcutBatchController.state.value.active)
        val resources = context.packageManager.getResourcesForApplication(NOVA_PACKAGE)
        val skipId = resources.getIdentifier("onboarding_skip_setup", "string", NOVA_PACKAGE)
        assertTrue("Official Nova must expose its known first-run text resource", skipId != 0)
        val skipText = resources.getString(skipId)
        device.pressHome()
        val skip = device.wait(Until.findObject(By.pkg(NOVA_PACKAGE).text(skipText)), 15_000)
        if (skip != null) {
            var button = skip
            var ancestors = 0
            while (!button.isClickable && ancestors++ < 8) {
                button = requireNotNull(button.parent) { "Nova's Skip setup text has no clickable ancestor" }
            }
            assertTrue("Nova's Skip setup button must be enabled", button.isEnabled && button.isClickable)
            button.click()
            novaSetupButtonClicks++
            assertTrue("Nova's first-run screen must finish", device.wait(Until.gone(By.pkg(NOVA_PACKAGE).text(skipText)), 15_000))
            device.pressHome()
        }
        waitFor(15_000) {
            val resumed = device.executeShellCommand("dumpsys activity activities").lineSequence().filter {
                it.contains("mResumedActivity") || it.contains("topResumedActivity")
            }
            resumed.any { line ->
                line.contains("$NOVA_PACKAGE/.NovaLauncher ") || line.contains("$NOVA_PACKAGE/$NOVA_PACKAGE.NovaLauncher ")
            }
        }
        assertEquals(NOVA_PACKAGE, launcher)
        assertEquals(NOVA_PACKAGE, device.currentPackageName)
        assertFalse(device.hasObject(By.pkg(NOVA_PACKAGE).text(skipText)))
        novaHomeResumed = true
    }

    private fun waitForBatch(previous: String?, timeout: Long, recordProgress: Boolean): BatchState {
        val deadline = SystemClock.elapsedRealtime() + timeout
        val checkpoints = setOf(0, 25, 50, 75, 100, 114, 115, 116, 125, 150, 175, 200, 225, 250, 275, 284)
        val recorded = mutableSetOf<Int>()
        while (SystemClock.elapsedRealtime() < deadline) {
            val current = ShortcutBatchController.state.value
            if (current.batchId != null && current.batchId != previous) {
                assertFalse("Large batch paused at ${current.completed}/${current.total}: ${current.message}; " +
                    ShortcutPinAccessibilityService.diagnosticState(), current.status == BatchStatus.PAUSED)
                assertFalse("Large batch was cancelled", current.status == BatchStatus.CANCELLED)
                if (recordProgress && current.completed in checkpoints && recorded.add(current.completed)) {
                    progress.put(JSONObject().put("completed", current.completed).put("total", current.total)
                        .put("elapsedMs", SystemClock.elapsedRealtime() - startedAt))
                    File(verificationFolder(), "large-shortcut-progress.json").writeText(progress.toString(2))
                }
                if (current.status == BatchStatus.COMPLETED) {
                    assertEquals(FIXTURE_COUNT, current.completed)
                    return current
                }
            }
            SystemClock.sleep(50)
        }
        fail("Large batch timed out at ${ShortcutBatchController.state.value.completed}/$FIXTURE_COUNT")
        error("unreachable")
    }

    private fun verifyIndependentLauncherIcons(labels: List<String>): JSONArray {
        device.pressHome()
        assertTrue(device.wait(Until.hasObject(By.pkg(launcher).depth(0)), 15_000))
        repeat(32) { swipeWorkspace(right = true) }
        val observed = linkedMapOf<String, Pair<Int, Rect>>()
        repeat(48) { page ->
            labels.filterNot { it in observed }.forEach { label ->
                device.findObjects(By.pkg(launcher).text(label)).firstOrNull { it.isLongClickable }?.let { icon ->
                    assertTrue(icon.isClickable)
                    assertFalse(icon.visibleBounds.isEmpty)
                    observed[label] = page to Rect(icon.visibleBounds)
                }
            }
            if (observed.size == labels.size) {
                assertEquals(labels.size, observed.values.toSet().size)
                val evidence = JSONArray()
                observed.forEach { (label, location) ->
                    evidence.put(JSONObject().put("label", label).put("launcherPackage", launcher)
                        .put("page", location.first).put("bounds", location.second.flattenToString())
                        .put("individuallyLongClickable", true))
                }
                device.takeScreenshot(File(verificationFolder(), "large-shortcuts.png"))
                return evidence
            }
            swipeWorkspace(right = false)
        }
        fail("Missing independent native icon samples: ${labels - observed.keys}")
        error("unreachable")
    }

    private fun swipeWorkspace(right: Boolean) {
        val left = device.displayWidth / 5
        val rightEdge = device.displayWidth * 4 / 5
        device.swipe(if (right) left else rightEdge, device.displayHeight / 2,
            if (right) rightEdge else left, device.displayHeight / 2, 20)
        SystemClock.sleep(100)
    }

    private fun automation() = requireNotNull(instrumentation.getUiAutomation(
        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES))

    private fun restoreSecure(key: String, value: String?) {
        val uiAutomation = automation()
        uiAutomation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            assertTrue(Settings.Secure.putString(context.contentResolver, key, value))
            assertEquals(value, Settings.Secure.getString(context.contentResolver, key))
        } finally {
            uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun waitFor(timeout: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("Timed out waiting for the large shortcut fixture", condition())
    }

    private fun pinnedIds() = manager.pinnedShortcuts.mapTo(mutableSetOf()) { it.id }
    private fun verificationFolder() = requireNotNull(context.getExternalFilesDir("verification-large")).also { it.mkdirs() }

    private fun captureFailure(failure: Throwable) {
        val state = ShortcutBatchController.state.value
        val summary = JSONObject().put("failure", failure.toString()).put("completed", state.completed)
            .put("total", state.total).put("status", state.status.name).put("message", state.message)
            .put("currentPackage", state.current).put("requestsObserved", requests.size)
            .put("assistant", ShortcutPinAccessibilityService.diagnosticState()).put("progress", progress)
        android.util.Log.e("LargeShortcutBatchTest", summary.toString())
        runCatching { File(verificationFolder(), "large-shortcut-failure.json").writeText(summary.toString(2)) }
        runCatching { device.dumpWindowHierarchy(File(verificationFolder(), "large-shortcut-failure.xml")) }
        runCatching { device.takeScreenshot(File(verificationFolder(), "large-shortcut-failure.png")) }
    }

    companion object {
        private const val FIXTURE_COUNT = 284
        private const val FIXTURE_PREFIX = "com.aistra.hail.testfixture.large"
        private const val NOVA_PACKAGE = "com.teslacoilsw.launcher"
    }
}
