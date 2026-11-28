package com.aistra.hail.shortcut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinConfirmationMatcherTest {
    private val launcher = "com.example.launcher"
    private val activity = "com.android.launcher3.dragndrop.AddItemActivity"
    private val spec = PinConfirmationSpec(
        launcherPackage = launcher,
        confirmationActivities = setOf(activity),
        publisherLabel = "Hail",
        targetLabel = "Camera",
        publisherViewIds = setOf("$launcher:id/widget_appName"),
        targetViewIds = setOf("$launcher:id/widget_name"),
        addLabels = setOf("Add to Home screen")
    )

    private fun node(
        key: Int,
        text: String,
        id: String? = null,
        button: Boolean = false
    ) = PinUiNode(
        key = key,
        packageName = launcher,
        viewId = id,
        className = if (button) "android.widget.Button" else "android.widget.TextView",
        text = text,
        visible = true,
        enabled = true,
        clickable = button
    )

    private fun screen() = listOf(
        node(0, "Hail", "$launcher:id/widget_appName"),
        node(1, "Camera", "$launcher:id/widget_name"),
        node(2, "Cancel", button = true),
        node(3, "Add to Home screen", button = true)
    )

    private fun match(nodes: List<PinUiNode> = screen(), expected: PinConfirmationSpec = spec) =
        PinConfirmationMatcher.findAddButton(expected, activity, launcher, nodes)

    @Test fun matchesLauncher3WithExactPublisherTargetAndLocalizedButton() {
        assertEquals(3, match())
        val translated = screen().toMutableList().apply { set(3, get(3).copy(text = "Zum Startbildschirm hinzufügen")) }
        assertEquals(3, match(translated, spec.copy(addLabels = setOf("Zum Startbildschirm hinzufügen"))))
    }

    @Test fun rejectsOtherPackagesAndUnresolvedActivities() {
        assertNull(PinConfirmationMatcher.findAddButton(spec, activity, "com.other.app", screen()))
        assertNull(PinConfirmationMatcher.findAddButton(spec, "com.example.launcher.SettingsActivity", launcher, screen()))
        assertNull(PinConfirmationMatcher.findAddButton(spec, null, launcher, screen()))
        assertNull(match(screen().map { if (it.key == 3) it.copy(packageName = "com.other.app") else it }))
    }

    @Test fun rejectsWrongPublisherOrTargetEvenWithARealAddButton() {
        assertNull(match(screen().map { if (it.key == 0) it.copy(text = "Another app") else it }))
        assertNull(match(screen().map { if (it.key == 1) it.copy(text = "Contacts") else it }))
        assertNull(match(screen().map { if (it.key == 0) it.copy(viewId = "$launcher:id/unrelated") else it }))
    }

    @Test fun refusesDuplicateOrInvisibleOrDisabledButtons() {
        assertNull(match(screen() + node(4, "Add to Home screen", button = true)))
        assertNull(match(screen().map { if (it.key == 3) it.copy(visible = false) else it }))
        assertNull(match(screen().map { if (it.key == 3) it.copy(enabled = false) else it }))
        assertNull(match(screen().map { if (it.key == 3) it.copy(clickable = false) else it }))
    }

    @Test fun refusesAmbiguousOrHiddenPreviewLabels() {
        assertNull(match(screen() + node(4, "Camera", "$launcher:id/widget_name")))
        assertNull(match(screen().map { if (it.key == 0) it.copy(visible = false) else it }))
        assertNull(match(screen().map { if (it.key == 1) it.copy(packageName = "com.other.app") else it }))
    }

    @Test fun distinguishesHailShortcutFromPublisherUsingStructuredIds() {
        val hailScreen = screen().map { if (it.key == 1) it.copy(text = "Hail") else it }
        assertEquals(3, match(hailScreen, spec.copy(targetLabel = "Hail")))
    }

    @Test fun standardPositiveButtonStillRequiresExactPinActivityAndDistinctPreviewLabels() {
        val generic = spec.copy(publisherViewIds = emptySet(), targetViewIds = emptySet(), addLabels = emptySet())
        val nodes = screen().map { if (it.key == 3) it.copy(viewId = "android:id/button1", text = "Add") else it }
        assertEquals(3, match(nodes, generic))
        assertNull(PinConfirmationMatcher.findAddButton(generic, "android.app.AlertDialog", launcher, nodes))
        assertNull(match(nodes.map { if (it.key == 0) it.copy(text = "Other app") else it }, generic))
        assertNull(match(nodes.map { if (it.key == 1) it.copy(text = "Hail") else it }, generic.copy(targetLabel = "Hail")))
    }

    @Test fun genericAddTextAndAnUnrelatedViewAreNeverEnough() {
        val generic = spec.copy(publisherViewIds = emptySet(), targetViewIds = emptySet(), addLabels = emptySet())
        assertNull(match(screen(), generic))
        assertNull(match(screen().map { if (it.key == 3) it.copy(className = "android.widget.TextView") else it }))
        assertNull(match(expected = spec.copy(targetLabel = "")))
        assertNull(match(expected = spec.copy(publisherLabel = "")))
    }
}
