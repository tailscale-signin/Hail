package com.aistra.hail.shortcut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

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

    @Test fun acceptsLauncherResourceAllCapsTransformationWithoutGeneralTextMatching() {
        // Captured Android 16 Launcher3: the resource is mixed case, but TextView's accessible
        // button text is ADD TO HOME SCREEN. Publisher and target keep their exact original labels.
        val labels = PinConfirmationMatcher.localizedButtonLabels("Add to home screen", Locale.US)
        val actual = screen().map { if (it.key == 3) it.copy(text = "ADD TO HOME SCREEN") else it }
        assertEquals(3, match(actual, spec.copy(addLabels = labels)))
        assertNull(match(actual.map { if (it.key == 3) it.copy(text = "Add") else it }, spec.copy(addLabels = labels)))
        assertNull(match(actual.map { if (it.key == 0) it.copy(text = "HAIL") else it }, spec.copy(addLabels = labels)))
        assertNull(match(actual + actual.last().copy(key = 4), spec.copy(addLabels = labels)))
    }

    @Test fun allCapsVariantsUseResourceLocaleIncludingExpandingCharacters() {
        assertEquals(setOf("hinzufügen straße", "HINZUFÜGEN STRASSE"),
            PinConfirmationMatcher.localizedButtonLabels("hinzufügen straße", Locale.GERMAN))
        assertEquals(setOf("ekle işlemi", "EKLE İŞLEMİ"),
            PinConfirmationMatcher.localizedButtonLabels("ekle işlemi", Locale.forLanguageTag("tr")))
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
        assertEquals(3, match(nodes.map { if (it.key == 1) it.copy(text = "Hail") else it }, generic.copy(targetLabel = "Hail")))
    }

    @Test fun genericHailTargetRequiresExactlyTwoSeparateMatchingViews() {
        val generic = spec.copy(targetLabel = "Hail", publisherViewIds = emptySet(), targetViewIds = emptySet())
        val nodes = screen().map {
            when (it.key) {
                1 -> it.copy(text = "Hail")
                3 -> it.copy(viewId = "android:id/button1", text = "Add")
                else -> it
            }
        }
        assertEquals(3, match(nodes, generic))
        assertNull(match(nodes.filterNot { it.key == 1 }, generic))
        assertNull(match(nodes + node(4, "Hail"), generic))
        assertNull(match(nodes.map { if (it.key == 1) it.copy(key = 0) else it }, generic))
        assertNull(match(nodes.map { if (it.key == 1) it.copy(visible = false) else it }, generic))
    }

    @Test fun unusedLauncher3ResourcesDoNotDisableAStandardOemDialog() {
        val nodes = screen().map {
            when (it.key) {
                0 -> it.copy(viewId = "$launcher:id/publisher")
                1 -> it.copy(viewId = "$launcher:id/shortcut_label")
                3 -> it.copy(viewId = "android:id/button1", text = "Add automatically")
                else -> it
            }
        }
        assertEquals(3, match(nodes))
        assertNull(match(nodes.map { if (it.key == 3) it.copy(viewId = "$launcher:id/unknown") else it }))
        assertNull(match(nodes.map { if (it.key == 1) it.copy(text = "Another app") else it }))
    }

    @Test fun standardPositiveControlWorksWithStructuredPreviewAndDifferentLocalizedButtonLabel() {
        val nodes = screen().map { if (it.key == 3) it.copy(viewId = "android:id/button1", text = "Ajouter") else it }
        assertEquals(3, match(nodes))
        assertNull(match(nodes + node(4, "Add to Home screen", button = true)))
    }

    @Test fun acceptsWhitespaceWrappingButNeverCaseChangesOrTruncatedLabels() {
        val wrapped = screen().map { if (it.key == 1) it.copy(text = "My\n Camera\u00a0Pro") else it }
        assertEquals(3, match(wrapped, spec.copy(targetLabel = "My Camera Pro")))
        assertNull(match(wrapped, spec.copy(targetLabel = "My Camera")))
        assertNull(match(wrapped, spec.copy(targetLabel = "My camera Pro")))
        assertNull(match(wrapped.map { if (it.key == 1) it.copy(text = "My Camera…") else it },
            spec.copy(targetLabel = "My Camera Pro")))
    }

    @Test fun full284AppSequenceDoesNotStopOnIdenticalPublisherAndTargetAt115() {
        val generic = spec.copy(publisherViewIds = emptySet(), targetViewIds = emptySet(), addLabels = emptySet())
        repeat(284) { position ->
            val label = if (position == 115) "Hail" else "App $position"
            val nodes = listOf(node(0, "Hail"), node(1, label), node(2, "Add", "android:id/button1", button = true))
            assertEquals("position=$position", 2, match(nodes, generic.copy(targetLabel = label)))
        }
    }

    @Test fun sameWindowDismissalRequiresPostClickReturnToResolvedHome() {
        fun departed(id: Int, pkg: String?, homeWindow: Int? = null) = PinConfirmationWindowPolicy.hasDeparted(
            confirmationWindowId = 10, currentWindowId = id, currentPackage = pkg,
            launcherPackage = launcher, hailPackage = "com.aistra.hail", returnedHomeWindowId = homeWindow)
        assertFalse(departed(10, launcher))
        assertTrue(departed(10, launcher, 10))
        assertFalse(departed(10, launcher, 9))
        assertFalse(departed(10, "com.aistra.hail", 10))
        assertTrue(departed(11, launcher))
        assertTrue(departed(11, "com.aistra.hail"))
        assertFalse(departed(11, "com.other.app", 11))
        assertFalse(departed(11, null, 11))
    }

    // Official Nova 8.0.18 add_item_confirmation_activity + widget_cell_content resources.
    // AddItemActivity uses Nova's application label for the header; WidgetCell shows the
    // localized Shortcut marker and hides dimensions only when its request is a shortcut.
    private val novaPackage = NovaPinConfirmationSpec.PACKAGE
    private val novaSpec = spec.copy(
        launcherPackage = novaPackage,
        nova = NovaPinConfirmationSpec(
            launcherLabel = "Nova Launcher",
            headerViewIds = setOf("$novaPackage:id/widget_appName"),
            targetViewIds = setOf("$novaPackage:id/widget_name"),
            shortcutMarkerViewIds = setOf("$novaPackage:id/widget_shortcut_label"),
            shortcutMarkerLabels = setOf("Shortcut"),
            dimensionsViewIds = setOf("$novaPackage:id/widget_dims"),
            buttonViewIds = setOf("$novaPackage:id/place_automatically_button"),
            addLabels = setOf("Add to Home screen", "ADD TO HOME SCREEN")
        )
    )

    private fun novaScreen(label: String = "Camera") = listOf(
        node(0, "Nova Launcher", "$novaPackage:id/widget_appName"),
        node(1, label, "$novaPackage:id/widget_name"),
        node(2, "Shortcut", "$novaPackage:id/widget_shortcut_label"),
        node(3, "1 × 1", "$novaPackage:id/widget_dims").copy(visible = false),
        node(4, "Cancel", button = true),
        node(5, "ADD TO HOME SCREEN", "$novaPackage:id/place_automatically_button", button = true)
    ).map { it.copy(packageName = novaPackage) }

    private fun matchNova(nodes: List<PinUiNode> = novaScreen(), expected: PinConfirmationSpec = novaSpec,
                          activityName: String = activity, rootPackage: String = novaPackage) =
        PinConfirmationMatcher.findAddButton(expected, activityName, rootPackage, nodes)

    @Test fun novaPinScreenUsesLauncherTitleAndExactPendingShortcut() {
        assertEquals(5, matchNova())
        assertEquals(5, matchNova(novaScreen("Hail"), novaSpec.copy(targetLabel = "Hail")))
        assertEquals(5, matchNova(novaScreen("Nova Launcher"), novaSpec.copy(targetLabel = "Nova Launcher")))
        assertNull(matchNova(novaScreen().map { if (it.key == 0) it.copy(text = "Hail") else it }))
        assertNull(matchNova(novaScreen("Contacts")))
    }

    @Test fun novaAdapterCannotApproveAWidgetOrUnrelatedNovaScreen() {
        assertNull(matchNova(novaScreen().filterNot { it.key == 2 }))
        assertNull(matchNova(novaScreen().map { if (it.key == 2) it.copy(visible = false) else it }))
        assertNull(matchNova(novaScreen().map { if (it.key == 3) it.copy(visible = true) else it }))
        assertNull(matchNova(activityName = "com.teslacoilsw.launcher.NovaLauncher"))
        assertNull(matchNova(expected = novaSpec.copy(confirmationActivities = setOf("android.app.Dialog")),
            activityName = "android.app.Dialog"))
        assertNull(matchNova(rootPackage = "com.other.launcher"))
        assertNull(matchNova(expected = novaSpec.copy(launcherPackage = launcher), rootPackage = launcher))
    }

    @Test fun novaRequiresResourceNamedButtonAndLocalizedActionTogether() {
        assertNull(matchNova(novaScreen().map { if (it.key == 5) it.copy(viewId = "android:id/button1") else it }))
        assertNull(matchNova(novaScreen().map { if (it.key == 5) it.copy(text = "Add") else it }))
        assertNull(matchNova(novaScreen() + novaScreen().last().copy(key = 6)))
        assertNull(matchNova(novaScreen().map { if (it.key == 5) it.copy(enabled = false) else it }))
        assertNull(matchNova(novaScreen().map { if (it.key == 5) it.copy(visible = false) else it }))
        assertNull(matchNova(novaScreen().map { if (it.key == 5) it.copy(clickable = false) else it }))
    }

    @Test fun novaDoesNotTreatAnEditableOrAmbiguousTargetAsThePinPreview() {
        assertNull(matchNova(novaScreen().map { if (it.key == 1) it.copy(className = "android.widget.EditText") else it }))
        assertNull(matchNova(novaScreen() + novaScreen()[1].copy(key = 7)))
        assertNull(matchNova(novaScreen() + novaScreen()[2].copy(key = 8)))
        assertNull(matchNova(novaScreen().map { if (it.key == 1) it.copy(packageName = "com.other.app") else it }))
        assertNull(matchNova(novaScreen().map { if (it.key == 2) it.copy(viewId = "$novaPackage:id/unrelated") else it }))
    }

    @Test fun novaUsesInstalledLocalizedResourceLabels() {
        val labels = PinConfirmationMatcher.localizedButtonLabels("Ajouter à l’écran d’accueil", Locale.FRENCH)
        val localized = novaSpec.copy(targetLabel = "Caméra", nova = novaSpec.nova!!.copy(
            shortcutMarkerLabels = setOf("Raccourci"), addLabels = labels))
        val nodes = novaScreen("Caméra").map {
            when (it.key) {
                2 -> it.copy(text = "Raccourci")
                5 -> it.copy(text = "AJOUTER À L’ÉCRAN D’ACCUEIL")
                else -> it
            }
        }
        assertEquals(5, matchNova(nodes, localized))
        assertNull(matchNova(nodes, novaSpec.copy(targetLabel = "Caméra")))
    }

    @Test fun novaRequiresCompleteKnownResourceContract() {
        val resources = requireNotNull(novaSpec.nova)
        assertNull(matchNova(expected = novaSpec.copy(nova = null)))
        assertNull(matchNova(expected = novaSpec.copy(nova = resources.copy(launcherLabel = ""))))
        assertNull(matchNova(expected = novaSpec.copy(nova = resources.copy(shortcutMarkerViewIds = emptySet()))))
        assertNull(matchNova(expected = novaSpec.copy(nova = resources.copy(dimensionsViewIds = emptySet()))))
        assertNull(matchNova(expected = novaSpec.copy(nova = resources.copy(addLabels = emptySet()))))
    }

    @Test fun nova284AppSequenceKeepsExactPreviewMatchingAtEveryPosition() {
        repeat(284) { position ->
            val label = when (position) { 115 -> "Hail"; 116 -> "Nova Launcher"; else -> "App $position" }
            assertEquals("Nova position=$position", 5, matchNova(novaScreen(label), novaSpec.copy(targetLabel = label)))
        }
    }

    @Test fun genericAddTextAndAnUnrelatedViewAreNeverEnough() {
        val generic = spec.copy(publisherViewIds = emptySet(), targetViewIds = emptySet(), addLabels = emptySet())
        assertNull(match(screen(), generic))
        assertNull(match(screen().map { if (it.key == 3) it.copy(className = "android.widget.TextView") else it }))
        assertNull(match(expected = spec.copy(targetLabel = "")))
        assertNull(match(expected = spec.copy(publisherLabel = "")))
    }
}
