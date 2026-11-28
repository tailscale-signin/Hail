package com.aistra.hail.shortcut

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import com.aistra.hail.R
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

    private data class ConfirmationWindow(val id: Int, val activity: String)
    private data class Departure(val windowId: Int, val packageName: String, val since: Long)

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        setEventSubscription(null)
        scope.launch {
            ShortcutBatchController.state.collect {
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
        spec = request?.let(::loadSpec)
        setEventSubscription(request?.launcherPackage)
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
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = if (launcherPackage == null) 0 else
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            packageNames = arrayOf(launcherPackage ?: packageName)
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 80
        }
    }

    @Suppress("DEPRECATION")
    private fun loadSpec(request: PendingShortcut): PinConfirmationSpec {
        val resources = runCatching { packageManager.getResourcesForApplication(request.launcherPackage) }.getOrNull()
        fun resourceName(name: String): Set<String> {
            val id = resources?.getIdentifier(name, "id", request.launcherPackage) ?: 0
            return if (id == 0) emptySet() else
                runCatching { setOf(resources!!.getResourceName(id)) }.getOrDefault(emptySet())
        }
        val addId = resources?.getIdentifier("add_to_home_screen", "string", request.launcherPackage) ?: 0
        val addLabels = if (addId == 0) emptySet() else
            runCatching {
                val configuration = resources!!.configuration
                val locale = if (Build.VERSION.SDK_INT >= 24) configuration.locales[0] ?: Locale.getDefault()
                    else configuration.locale ?: Locale.getDefault()
                PinConfirmationMatcher.localizedButtonLabels(resources.getString(addId), locale)
            }.getOrDefault(emptySet())
        return PinConfirmationSpec(
            launcherPackage = request.launcherPackage,
            confirmationActivities = request.confirmationActivities,
            publisherLabel = applicationInfo.loadLabel(packageManager).toString(),
            targetLabel = request.label,
            publisherViewIds = resourceName("widget_appName"),
            targetViewIds = resourceName("widget_name"),
            addLabels = addLabels
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
                return
            }
            nodes += root
            val rootPackage = root.packageName?.toString()
            val clicked = clickedWindow
            if (clicked != null) {
                // A callback can arrive before this window closes (or even before it opens for an
                // existing shortcut). Wait for a stable departure after our own successful click.
                if (root.windowId != clicked.id) {
                    if (rootPackage == request.launcherPackage || rootPackage == packageName) {
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
                        diagnose("click-left-launcher")
                        pauseIfActive(R.string.shortcut_pin_assistant_left_launcher)
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
                } else if (SystemClock.elapsedRealtime() - request.requestedAtElapsed >= MATCH_TIMEOUT_MS) {
                    pauseIfActive(R.string.shortcut_pin_assistant_unrecognized)
                }
                return
            }
            val window = confirmationWindow
            if (window == null || root.windowId != window.id) {
                diagnose(if (window == null) "missing-confirmation-activity-event" else "confirmation-window-changed")
                if (window != null) {
                    pauseIfActive(R.string.shortcut_pin_assistant_dismissed)
                } else if (SystemClock.elapsedRealtime() - request.requestedAtElapsed >= MATCH_TIMEOUT_MS) {
                    pauseIfActive(R.string.shortcut_pin_assistant_unrecognized)
                }
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
                if (SystemClock.elapsedRealtime() - request.requestedAtElapsed >= MATCH_TIMEOUT_MS) {
                    pauseIfActive(R.string.shortcut_pin_assistant_unrecognized)
                }
                return
            }
            val candidate = nodes[candidateIndex]
            // Nodes are snapshots: refresh just before acting, then repeat the button match with
            // that refreshed node. A changed, hidden, or disabled control is never clicked.
            if (!candidate.refresh() || candidate.windowId != window.id) return
            snapshot[candidateIndex] = candidate.describe(candidateIndex)
            if (PinConfirmationMatcher.findAddButton(expected, window.activity, rootPackage, snapshot) != candidateIndex) return
            ShortcutBatchController.confirmationObserved(request.requestId)
            if (!ShortcutBatchController.claimAutomationClick(request.requestId)) {
                diagnose("click-claim-rejected")
                return
            }
            clickedWindow = window
            if (!candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                diagnose("click-action-rejected")
                clickedWindow = null
                ShortcutBatchController.releaseAutomationClick(request.requestId)
                pauseIfActive(R.string.shortcut_pin_assistant_click_failed)
            } else {
                diagnose("clicked-waiting-for-dismissal")
            }
        } catch (_: RuntimeException) {
            // A launcher may replace a window during a Binder read. Preserve the queue and require
            // explicit resumption instead of guessing which screen now owns a button.
            diagnose("window-read-failed")
            pauseIfActive(R.string.shortcut_pin_assistant_unrecognized)
        } finally {
            nodes.forEach { it.recycle() }
            inspecting = false
        }
    }

    /** Transient, bounded troubleshooting metadata. Never stores screen text, labels, or hierarchy. */
    private fun diagnose(reason: String) {
        diagnostic = ("$reason;eventClass=${lastEventClass ?: "none"};eventWindow=$lastEventWindow;" +
            "confirmedWindow=${confirmationWindow?.id};rootWindow=$lastRootWindow;" +
            "publisherIds=${spec?.publisherViewIds?.size ?: 0};targetIds=${spec?.targetViewIds?.size ?: 0};" +
            "addLabels=${spec?.addLabels?.size ?: 0}").take(768)
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
