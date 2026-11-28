package com.aistra.hail.shortcut

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import com.aistra.hail.R
import com.aistra.hail.utils.DecisionLog
import com.aistra.hail.utils.DecisionTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * An optional, user-enabled assistant for one explicit shortcut batch. It never starts a batch,
 * grants access, sends gestures, or searches arbitrary windows for a button. The controller owns
 * the queue and its consent/lifecycle; this service is only a narrowly scoped launcher UI adapter.
 */
class ShortcutPinAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var polling: Job? = null
    private var subscribedRequest: String? = null
    private var spec: PinConfirmationSpec? = null
    private var confirmationWindow: ConfirmationWindow? = null
    private var clickedWindow: ConfirmationWindow? = null
    private var departure: Departure? = null
    private var inspecting = false
    private var lastEventClass: String? = null
    private var lastEventWindow: Int? = null
    private var lastRootWindow: Int? = null
    private var subscribedLauncher: String? = null
    private var eventSubscriptionInitialized = false
    private var homeActivities: Set<String> = emptySet()
    private var returnedHomeWindow: Int? = null
    private var trace: DecisionTrace? = null
    private var lastLoggedReason: String? = null

    private data class ConfirmationWindow(val id: Int, val activity: String)
    private data class Departure(val windowId: Int, val packageName: String, val since: Long)

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        setEventSubscription(null)
        scope.launch {
            ShortcutBatchController.state.collect { state ->
                // Keep the exact launcher subscription for the whole active batch. Switching it
                // off between each pair of requests can lose the next pin activity's only event.
                setEventSubscription(state.launcherPackage.takeIf { state.active && state.automatic })
                updateSubscription(ShortcutBatchController.currentAutomationRequest())
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val request = ShortcutBatchController.currentAutomationRequest() ?: return
        if (event == null || event.packageName?.toString() != request.launcherPackage) return
        updateSubscription(request)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val activity = event.className?.toString()
            lastEventClass = activity?.take(128)
            lastEventWindow = event.windowId
            if (activity != null && activity in request.confirmationActivities) {
                confirmationWindow = ConfirmationWindow(event.windowId, activity)
                returnedHomeWindow = null
            } else if (clickedWindow != null && activity != null && activity in homeActivities) {
                // Accept only a post-click event for the actual default Home activity. A generic
                // View/Dialog class, content-change event or another app is not dismissal proof.
                returnedHomeWindow = event.windowId
            }
        }
        inspectCurrentConfirmation()
    }

    override fun onInterrupt() {
        pauseIfActive(R.string.shortcut_pin_assistant_interrupted)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        pauseIfActive(R.string.shortcut_pin_assistant_disconnected)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        pauseIfActive(R.string.shortcut_pin_assistant_disconnected)
        scope.cancel()
        super.onDestroy()
    }

    private fun updateSubscription(request: PendingShortcut?) {
        if (request?.requestId == subscribedRequest) return
        polling?.cancel()
        polling = null
        subscribedRequest = request?.requestId
        confirmationWindow = null
        clickedWindow = null
        departure = null
        lastEventClass = null
        lastEventWindow = null
        lastRootWindow = null
        returnedHomeWindow = null
        homeActivities = request?.let { resolvedHomeActivities(it.launcherPackage) }.orEmpty()
        trace = null
        lastLoggedReason = null
        spec = request?.let(::loadSpec)
        if (request != null) {
            diagnose("subscribed")
            polling = scope.launch {
                while (ShortcutBatchController.currentAutomationRequest()?.requestId == request.requestId) {
                    inspectCurrentConfirmation()
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
    }

    private fun setEventSubscription(launcherPackage: String?) {
        if (eventSubscriptionInitialized && subscribedLauncher == launcherPackage) return
        subscribedLauncher = launcherPackage
        eventSubscriptionInitialized = true
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = if (launcherPackage == null) 0 else
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            packageNames = arrayOf(launcherPackage ?: packageName)
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            // Do not coalesce a confirmation activity event with its first content update.
            notificationTimeout = 0
        }
    }

    @Suppress("DEPRECATION")
    private fun resolvedHomeActivities(launcherPackage: String): Set<String> = runCatching {
        packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.takeIf { it.packageName == launcherPackage }?.let {
            setOfNotNull(it.name, it.targetActivity)
        }.orEmpty()
    }.getOrDefault(emptySet())

    @Suppress("DEPRECATION")
    private fun loadSpec(request: PendingShortcut): PinConfirmationSpec {
        val resources = runCatching { packageManager.getResourcesForApplication(request.launcherPackage) }.getOrNull()
        fun resourceName(name: String): Set<String> {
            val id = resources?.getIdentifier(name, "id", request.launcherPackage) ?: 0
            return if (id == 0) emptySet() else
                runCatching { setOf(resources!!.getResourceName(id)) }.getOrDefault(emptySet())
        }
        fun stringLabels(name: String): Set<String> {
            val id = resources?.getIdentifier(name, "string", request.launcherPackage) ?: 0
            return if (id == 0) emptySet() else runCatching {
                val configuration = resources!!.configuration
                val locale = if (Build.VERSION.SDK_INT >= 24) configuration.locales[0] ?: Locale.getDefault()
                    else configuration.locale ?: Locale.getDefault()
                PinConfirmationMatcher.localizedButtonLabels(resources.getString(id), locale)
            }.getOrDefault(emptySet())
        }
        val addLabels = stringLabels("add_to_home_screen")
        val nova = if (request.launcherPackage == NovaPinConfirmationSpec.PACKAGE &&
            NovaPinConfirmationSpec.ACTIVITY in request.confirmationActivities) {
            val launcherLabel = runCatching {
                packageManager.getApplicationInfo(request.launcherPackage, 0).loadLabel(packageManager).toString()
            }.getOrDefault("")
            NovaPinConfirmationSpec(
                launcherLabel = launcherLabel,
                headerViewIds = resourceName("widget_appName"),
                targetViewIds = resourceName("widget_name"),
                shortcutMarkerViewIds = resourceName("widget_shortcut_label"),
                shortcutMarkerLabels = stringLabels("shortcut"),
                dimensionsViewIds = resourceName("widget_dims"),
                buttonViewIds = resourceName("place_automatically_button"),
                addLabels = addLabels
            )
        } else null
        return PinConfirmationSpec(
            launcherPackage = request.launcherPackage,
            confirmationActivities = request.confirmationActivities,
            publisherLabel = applicationInfo.loadLabel(packageManager).toString(),
            targetLabel = request.label,
            publisherViewIds = resourceName("widget_appName"),
            targetViewIds = resourceName("widget_name"),
            addLabels = addLabels,
            nova = nova
        )
    }

    @Suppress("DEPRECATION")
    private fun inspectCurrentConfirmation() {
        // Do not even retrieve a root when there is no explicitly active automatic request.
        val request = ShortcutBatchController.currentAutomationRequest() ?: return
        if (request.requestId != subscribedRequest || inspecting) return
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true ||
            getSystemService(PowerManager::class.java)?.isInteractive == false
        ) {
            pauseIfActive(R.string.shortcut_pin_assistant_locked)
            return
        }
        inspecting = true
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        try {
            val root = rootInActiveWindow
            lastRootWindow = root?.windowId
            if (root == null) {
                diagnose("no-active-root")
                pauseAfterMatchTimeout(request)
                return
            }
            nodes += root
            val rootPackage = root.packageName?.toString()
            val clicked = clickedWindow
            if (clicked != null) {
                // A callback can arrive before this window closes (or even before it opens for an
                // existing shortcut). Wait for a stable departure after our own successful click.
                if (rootPackage != request.launcherPackage && rootPackage != packageName) {
                    diagnose("click-left-launcher")
                    pauseIfActive(R.string.shortcut_pin_assistant_left_launcher)
                    return
                }
                if (PinConfirmationWindowPolicy.hasDeparted(clicked.id, root.windowId, rootPackage,
                        request.launcherPackage, packageName, returnedHomeWindow)) {
                    val previous = departure
                    val now = SystemClock.elapsedRealtime()
                    if (previous?.windowId != root.windowId || previous.packageName != rootPackage) {
                        departure = Departure(root.windowId, rootPackage, now)
                        diagnose("click-departure-settling")
                    } else if (now - previous.since >= DISMISS_SETTLE_MS) {
                        diagnose("click-confirmation-dismissed")
                        ShortcutBatchController.automationConfirmationDismissed(request.requestId)
                    }
                } else {
                    departure = null
                }
                return
            }

            // Only package/window metadata is inspected outside the chosen launcher. Never traverse
            // another app's hierarchy or retain its text.
            if (rootPackage != request.launcherPackage) {
                diagnose(if (rootPackage == packageName) "waiting-in-hail" else "other-active-package")
                if (confirmationWindow != null && rootPackage != packageName) {
                    pauseIfActive(R.string.shortcut_pin_assistant_left_launcher)
                } else pauseAfterMatchTimeout(request)
                return
            }
            val window = confirmationWindow
            if (window == null || root.windowId != window.id) {
                diagnose(if (window == null) "missing-confirmation-activity-event" else "confirmation-window-changed")
                // Activity events and active-root changes are delivered separately. A stale root
                // during the launch animation is normal, especially after hundreds of requests.
                // Wait for the already resolved confirmation window; never click the old root.
                pauseAfterMatchTimeout(request)
                return
            }

            val snapshot = mutableListOf<PinUiNode>()
            var index = 0
            while (index < nodes.size) {
                val node = nodes[index]
                // Do not use gestures or a false accessibility-tool declaration to work around a
                // launcher that protects its confirmation as sensitive accessibility data.
                if (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive) {
                    diagnose("sensitive-node")
                    pauseIfActive(R.string.shortcut_pin_assistant_unrecognized)
                    return
                }
                snapshot += node.describe(index)
                for (child in 0 until node.childCount) {
                    if (nodes.size >= MAX_NODES) {
                        diagnose("node-limit")
                        pauseIfActive(R.string.shortcut_pin_assistant_unrecognized)
                        return
                    }
                    node.getChild(child)?.let(nodes::add)
                }
                index++
            }
            val expected = spec ?: return
            val match = PinConfirmationMatcher.inspect(expected, window.activity, rootPackage, snapshot)
            diagnose("${match.diagnostic};nodes=${snapshot.size}")
            val candidateIndex = match.buttonKey
            if (candidateIndex == null) {
                pauseAfterMatchTimeout(request)
                return
            }
            val candidate = nodes[candidateIndex]
            // Nodes are snapshots: refresh just before acting, then repeat the button match with
            // that refreshed node. A changed, hidden, or disabled control is never clicked.
            if (!candidate.refresh() || candidate.windowId != window.id) {
                diagnose("button-refresh-retry")
                pauseAfterMatchTimeout(request)
                return
            }
            snapshot[candidateIndex] = candidate.describe(candidateIndex)
            if (PinConfirmationMatcher.findAddButton(expected, window.activity, rootPackage, snapshot) != candidateIndex) {
                diagnose("button-match-changed")
                pauseAfterMatchTimeout(request)
                return
            }
            ShortcutBatchController.confirmationObserved(request.requestId)
            if (!ShortcutBatchController.claimAutomationClick(request.requestId)) {
                diagnose("click-claim-rejected")
                return
            }
            clickedWindow = window
            returnedHomeWindow = null
            if (!candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                diagnose("click-action-rejected")
                clickedWindow = null
                ShortcutBatchController.releaseAutomationClick(request.requestId)
                pauseIfActive(R.string.shortcut_pin_assistant_click_failed)
            } else {
                diagnose("clicked-waiting-for-dismissal")
            }
        } catch (error: RuntimeException) {
            // A launcher can replace its window during a Binder read. Retry a fresh snapshot
            // before a click; once a click was claimed, its outcome is uncertain and must pause.
            diagnose("window-read-failed;type=${error.javaClass.simpleName}")
            if (clickedWindow != null) pauseIfActive(R.string.shortcut_pin_assistant_unrecognized)
            else pauseAfterMatchTimeout(request)
        } finally {
            nodes.forEach { it.recycle() }
            inspecting = false
        }
    }

    private fun pauseAfterMatchTimeout(request: PendingShortcut) {
        if (SystemClock.elapsedRealtime() - request.requestedAtElapsed >= MATCH_TIMEOUT_MS) {
            pauseIfActive(R.string.shortcut_pin_assistant_unrecognized)
        }
    }

    /** Bounded troubleshooting metadata. Opt-in logs never contain screen text, labels or hierarchy. */
    private fun diagnose(reason: String) {
        diagnostic = ("$reason;eventClass=${lastEventClass ?: "none"};eventWindow=$lastEventWindow;" +
            "confirmedWindow=${confirmationWindow?.id};rootWindow=$lastRootWindow;" +
            "publisherIds=${spec?.publisherViewIds?.size ?: 0};targetIds=${spec?.targetViewIds?.size ?: 0};" +
            "addLabels=${spec?.addLabels?.size ?: 0};adapter=${if (spec?.nova != null) "nova" else "standard"}").take(768)
        val request = ShortcutBatchController.currentAutomationRequest() ?: return
        if (reason == lastLoggedReason) return
        lastLoggedReason = reason
        if (trace == null) trace = DecisionLog.begin("shortcut_assistant", "confirm_pin", pkg = request.packageName)
        trace?.event("confirmation", request.packageName,
            "batchId" to request.batchId, "requestId" to request.requestId,
            "launcher" to request.launcherPackage, "diagnostic" to diagnostic)
    }

    private fun AccessibilityNodeInfo.describe(key: Int) = PinUiNode(
        key = key,
        packageName = packageName?.toString(),
        viewId = viewIdResourceName,
        className = className?.toString(),
        text = text?.toString(),
        visible = isVisibleToUser,
        enabled = isEnabled,
        clickable = isClickable
    )

    private fun pauseIfActive(reason: Int) {
        if (ShortcutBatchController.currentAutomationRequest() != null) {
            ShortcutBatchController.pause(getString(reason))
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 150L
        private const val DISMISS_SETTLE_MS = 350L
        private const val MATCH_TIMEOUT_MS = 15_000L
        private const val MAX_NODES = 256
        @Volatile private var connected = false
        @Volatile private var diagnostic = "idle"

        fun isConnected(): Boolean = connected
        fun diagnosticState(): String = diagnostic

        fun isEnabled(context: Context): Boolean {
            val component = ComponentName(context, ShortcutPinAccessibilityService::class.java)
            val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
            return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                val service = it.resolveInfo.serviceInfo
                ComponentName(service.packageName, service.name) == component
            }
        }

        /** Opens the public Android settings page; the user alone grants or removes access. */
        fun openSettings(context: Context): Boolean = try {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
