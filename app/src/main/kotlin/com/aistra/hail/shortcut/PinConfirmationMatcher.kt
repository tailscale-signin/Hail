package com.aistra.hail.shortcut

import java.util.Locale

/** A deliberately small, transient description of the currently visible launcher pin screen. */
internal data class PinUiNode(
    val key: Int,
    val packageName: String?,
    val viewId: String?,
    val className: String?,
    val text: String?,
    val visible: Boolean,
    val enabled: Boolean,
    val clickable: Boolean
)

internal data class PinConfirmationSpec(
    val launcherPackage: String,
    val confirmationActivities: Set<String>,
    val publisherLabel: String,
    val targetLabel: String,
    val publisherViewIds: Set<String> = emptySet(),
    val targetViewIds: Set<String> = emptySet(),
    val addLabels: Set<String> = emptySet(),
    val nova: NovaPinConfirmationSpec? = null
)

/** Verified against official Nova 8.0.18 and 8.1.1 pin activities, not its ordinary edit dialog. */
internal data class NovaPinConfirmationSpec(
    val launcherLabel: String,
    val headerViewIds: Set<String>,
    val targetViewIds: Set<String>,
    val shortcutMarkerViewIds: Set<String>,
    val shortcutMarkerLabels: Set<String>,
    val dimensionsViewIds: Set<String>,
    val buttonViewIds: Set<String>,
    val addLabels: Set<String>
) {
    companion object {
        const val PACKAGE = "com.teslacoilsw.launcher"
        const val ACTIVITY = "com.android.launcher3.dragndrop.AddItemActivity"
    }
}

/**
 * Never searches another app, matches a generic "Add" string, or guesses a coordinate.
 * An exact activity resolved for CONFIRM_PIN_SHORTCUT is required before either adapter applies.
 * Launcher3 additionally exposes the publisher and shortcut in separate, named preview views.
 * Nova's verified adapter uses its launcher title, exact target and shortcut-only marker instead.
 * Other launchers are supported only when they expose separate publisher/shortcut views and a
 * standard Android positive button in that exact confirmation activity. Ambiguity fails closed.
 */
internal object PinConfirmationMatcher {
    internal data class Match(val buttonKey: Int?, val diagnostic: String)

    private val buttonClasses = setOf(
        "android.widget.Button",
        "androidx.appcompat.widget.AppCompatButton",
        "com.google.android.material.button.MaterialButton"
    )

    /** Android's TextView may expose its locale-specific all-caps transformation to accessibility. */
    fun localizedButtonLabels(resourceLabel: String, locale: Locale): Set<String> =
        setOf(resourceLabel, resourceLabel.uppercase(locale))

    fun findAddButton(
        spec: PinConfirmationSpec,
        activityName: String?,
        rootPackage: String?,
        nodes: List<PinUiNode>
    ): Int? = inspect(spec, activityName, rootPackage, nodes).buttonKey

    /** Diagnostics contain only match reasons and counts; no node text or app labels are retained. */
    fun inspect(
        spec: PinConfirmationSpec,
        activityName: String?,
        rootPackage: String?,
        nodes: List<PinUiNode>
    ): Match {
        if (spec.launcherPackage.isBlank() || spec.publisherLabel.isBlank() || spec.targetLabel.isBlank()) {
            return Match(null, "incomplete-request")
        }
        if (rootPackage != spec.launcherPackage) return Match(null, "wrong-window-package")
        if (activityName !in spec.confirmationActivities) return Match(null, "unresolved-activity")

        val visible = nodes.filter { it.visible && it.packageName == spec.launcherPackage }
        if (spec.launcherPackage == NovaPinConfirmationSpec.PACKAGE) {
            return inspectNova(spec, activityName, visible)
        }
        // Some OEM launchers include Launcher3 resources but use a standard dialog instead. The
        // presence of resource names in the APK does not prove that this screen uses those views.
        val structuredPreview = visible.any { it.viewId in spec.publisherViewIds } &&
            visible.any { it.viewId in spec.targetViewIds }
        val publisherLabel = normalizedLabel(spec.publisherLabel)
        val targetLabel = normalizedLabel(spec.targetLabel)
        val publishers = visible.filter {
            normalizedLabel(it.text) == publisherLabel && !it.clickable &&
                (!structuredPreview || it.viewId in spec.publisherViewIds)
        }
        val targets = visible.filter {
            normalizedLabel(it.text) == targetLabel && !it.clickable &&
                (!structuredPreview || it.viewId in spec.targetViewIds)
        }
        // Hail itself is a valid target. On a standard dialog both separate preview views then
        // say "Hail"; requiring each text to occur once made every such batch stop at this app.
        // Exactly two distinct matching views are required. One or three remain ambiguous.
        val sharedLabelPreview = !structuredPreview && publisherLabel == targetLabel &&
            publishers.size == 2 && publishers.map { it.key }.distinct().size == 2 && publishers == targets
        val distinctPreview = publishers.size == 1 && targets.size == 1 && publishers.single().key != targets.single().key
        if (!sharedLabelPreview && !distinctPreview) {
            return Match(null, "preview-mismatch;publishers=${publishers.size};targets=${targets.size};structured=$structuredPreview")
        }

        val buttons = visible.filter { it.enabled && it.clickable && it.className in buttonClasses }
        val positive = buttons.filter { node ->
            (node.viewId == "android:id/button1" && !node.text.isNullOrBlank()) ||
                (structuredPreview && node.text in spec.addLabels)
        }
        val candidate = positive.singleOrNull()?.key
        return Match(candidate, "${if (candidate == null) "button-mismatch" else "matched"};" +
            "buttons=${buttons.size};positive=${positive.size};structured=$structuredPreview")
    }

    private fun inspectNova(spec: PinConfirmationSpec, activityName: String?, visible: List<PinUiNode>): Match {
        if (activityName != NovaPinConfirmationSpec.ACTIVITY) return Match(null, "nova-wrong-activity")
        val nova = spec.nova ?: return Match(null, "nova-resources-unavailable")
        if (nova.launcherLabel.isBlank() || nova.headerViewIds.isEmpty() || nova.targetViewIds.isEmpty() ||
            nova.shortcutMarkerViewIds.isEmpty() || nova.shortcutMarkerLabels.isEmpty() ||
            nova.dimensionsViewIds.isEmpty() || nova.buttonViewIds.isEmpty() || nova.addLabels.isEmpty()) {
            return Match(null, "nova-incomplete-resources")
        }
        // Nova's AddItemActivity deliberately displays its OWN application label in widget_appName.
        // Requiring "Hail" there cannot work. The installed launcher's resources identify this
        // particular pin screen; its target is the request's shortLabel in a read-only TextView.
        val labels = visible.filter { !it.clickable && it.className == "android.widget.TextView" }
        val headers = labels.filter { it.viewId in nova.headerViewIds &&
            normalizedLabel(it.text) == normalizedLabel(nova.launcherLabel) }
        val targets = labels.filter { it.viewId in nova.targetViewIds &&
            normalizedLabel(it.text) == normalizedLabel(spec.targetLabel) }
        val markers = labels.filter { it.viewId in nova.shortcutMarkerViewIds && it.text in nova.shortcutMarkerLabels }
        // The same activity accepts widgets. Nova makes this marker visible and hides dimensions
        // specifically for a shortcut request; a widget confirmation must never pass this adapter.
        val dimensions = visible.any { it.viewId in nova.dimensionsViewIds }
        if (headers.size != 1 || targets.size != 1 || markers.size != 1 || dimensions ||
            (headers + targets + markers).map { it.key }.distinct().size != 3) {
            return Match(null, "nova-preview-mismatch;headers=${headers.size};targets=${targets.size};" +
                "shortcutMarkers=${markers.size};dimensions=$dimensions")
        }
        val positive = visible.filter { it.enabled && it.clickable && it.className in buttonClasses &&
            it.viewId in nova.buttonViewIds && it.text in nova.addLabels }
        return Match(positive.singleOrNull()?.key,
            "${if (positive.size == 1) "nova-matched" else "nova-button-mismatch"};positive=${positive.size}")
    }

    // Layout line wrapping and non-breaking spaces may differ from the shortLabel string. Keep
    // case and all non-space characters exact: truncated/partial labels are never sufficient.
    private fun normalizedLabel(text: String?): String? = text?.splitToSequence(' ', '\t', '\n', '\r', '\u00a0')
        ?.filter { it.isNotEmpty() }?.joinToString(" ")
}

/** A verified post-click Home activity event can close an OEM dialog without changing window ID. */
internal object PinConfirmationWindowPolicy {
    fun hasDeparted(
        confirmationWindowId: Int,
        currentWindowId: Int,
        currentPackage: String?,
        launcherPackage: String,
        hailPackage: String,
        returnedHomeWindowId: Int?
    ): Boolean = (currentPackage == launcherPackage || currentPackage == hailPackage) &&
        (currentWindowId != confirmationWindowId ||
            (currentPackage == launcherPackage && returnedHomeWindowId == currentWindowId))
}
