package com.aistra.hail.shortcut

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
    val addLabels: Set<String> = emptySet()
)

/**
 * Never searches another app, matches a generic "Add" string, or guesses a coordinate.
 * An exact activity resolved for CONFIRM_PIN_SHORTCUT is required before either adapter applies.
 * Launcher3 additionally exposes the publisher and shortcut in separate, named preview views.
 * Other launchers are supported only when they expose distinct publisher/shortcut labels and a
 * standard Android positive button in that exact confirmation activity. Ambiguity fails closed.
 */
internal object PinConfirmationMatcher {
    private val buttonClasses = setOf(
        "android.widget.Button",
        "androidx.appcompat.widget.AppCompatButton",
        "com.google.android.material.button.MaterialButton"
    )

    fun findAddButton(
        spec: PinConfirmationSpec,
        activityName: String?,
        rootPackage: String?,
        nodes: List<PinUiNode>
    ): Int? {
        if (spec.launcherPackage.isBlank() || spec.publisherLabel.isBlank() || spec.targetLabel.isBlank() ||
            rootPackage != spec.launcherPackage || activityName !in spec.confirmationActivities
        ) return null

        val visible = nodes.filter { it.visible && it.packageName == spec.launcherPackage }
        val structuredPreview = spec.publisherViewIds.isNotEmpty() && spec.targetViewIds.isNotEmpty()
        val publishers = visible.filter {
            it.text == spec.publisherLabel && !it.clickable &&
                (!structuredPreview || it.viewId in spec.publisherViewIds)
        }
        val targets = visible.filter {
            it.text == spec.targetLabel && !it.clickable &&
                (!structuredPreview || it.viewId in spec.targetViewIds)
        }
        if (publishers.size != 1 || targets.size != 1 || publishers.single().key == targets.single().key) {
            return null
        }

        return visible.filter { node ->
            node.enabled && node.clickable && node.className in buttonClasses &&
                if (structuredPreview && spec.addLabels.isNotEmpty()) {
                    node.text in spec.addLabels
                } else {
                    node.viewId == "android:id/button1" && !node.text.isNullOrBlank()
                }
        }.singleOrNull()?.key
    }
}
