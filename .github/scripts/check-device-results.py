"""Require a completed, passing Android instrumentation result for each release scenario."""
from pathlib import Path
import argparse
import json
import sys
import xml.etree.ElementTree as ET

expected = {
    ("ShizukuCompatibilityTest", "shizukuListenersCanBeAddedAndRemovedOnSupportedAndroidVersions"),
    ("ShizukuCommandServiceTest", "executesDrainsAndRebindsWithoutEscalation"),
    ("IndividualShortcutBatchTest", "oneSettingsClickAutomaticallyPinsAllAppsAsSeparateIcons"),
    ("IndividualShortcutBatchTest", "manualDismissReopenCancelAndApprovalPreserveDurableState"),
    ("BulkShortcutLauncherTest", "catalogContainsDisabledLaunchersAndUsesFixedPrivateLaunchRoute"),
    ("BulkShortcutLauncherTest", "pinnedAppShortcutKeepsItsIdAndActionAcrossRefresh"),
    ("BulkShortcutLauncherTest", "repairsRestoredAppTagAndGlobalIconsWithoutChangingTheirIdentities"),
    ("AppRiskPresentationTest", "offlineCommunitySnapshotAndCoreFallbackAreAvailable"),
    ("AppRiskPresentationTest", "appRowsKeepThemeColorsAndExpandGuidanceInline"),
    ("AppRiskPresentationTest", "homeRowsKeepThemeColorsWhileSelectedAndHideInactiveLabel"),
    ("DebugLogTest", "offByDefaultOrderedBoundedAndExportableAfterStopping"),
    ("DeviceFreezeTest", "freezeRepeatThenThawLaunchAndRestoreFromBackup"),
    ("DeviceFreezeTest", "privateDefaultsAndBrandResourcesArePackaged"),
    ("DeviceFreezeTest", "readsAndroidSuspensionAndReportsDeniedOwnerMutation"),
    ("BackupSettingsTest", "existingSingleTagAndMultiTagListsSurviveUpgrade"),
    ("BackupSettingsTest", "invalidBackupsAndDeviceLocalSettings"),
    ("BackupSettingsTest", "settingsWritesFilePreviewsCancelsAndRestoresMultiTagBackup"),
    ("BulkShortcutLauncherTest", "existingWidgetCanBeRestoredRefreshedAndAddedAgain"),
    ("SystemAppsTest", "idleModeOpensAndroidSettingsForTheSelectedSystemApp"),
    ("SystemAppsTest", "disablesAndEnablesAPreinstalledAppWithShizukuPermission"),
    ("SystemAppsTest", "storesAnExplicitDisableForAManifestDisabledApp"),
    ("SystemAppsTest", "launcherlessSystemComponentKeepsStateAndIgnoresLaunchTaps"),
    ("SystemAppsTest", "deniedSystemAppChangePreservesEnabledState"),
    ("SystemAppsTest", "retriesOnlyUnappliedPackagesAfterAcknowledgedOrInterruptedBatch"),
    ("SystemAppsTest", "retainedSystemAppShowsProfileStatusAndCanBeRecheckedFromHome"),
    ("SystemAppsTest", "retainedSystemAppCanStoreShizukuOverrideWithoutReinstalling"),
    ("SystemAppsTest", "installedDisableVerificationDoesNotRequireRedundantInstalledFlag"),
    ("SystemAppsTest", "installedEvidenceAndRecycledHomeColorsAreIndependentOfRetainedFlags"),
}
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("log", type=Path)
parser.add_argument("--suite", choices=("standard", "large-shortcuts"), default="standard")
parser.add_argument("--evidence", type=Path)
parser.add_argument("--expected-launcher")
parser.add_argument("--expected-launcher-version")
parser.add_argument("--environment-evidence", type=Path)
args = parser.parse_args()
if args.suite == "large-shortcuts":
    expected = {
        ("LargeShortcutBatchTest", "automaticallyPins284AppsPast115AndSkipsExistingWithoutNewRequests"),
    }
    if args.evidence is None:
        parser.error("--evidence is required for the large-shortcuts suite")
log = args.log
bundle = {}
results = {}
finished = False
for line in log.read_text().splitlines():
    if line.startswith("INSTRUMENTATION_STATUS: "):
        key, sep, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
        if sep:
            bundle[key] = value
    elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
        code = int(line.split(": ", 1)[1])
        if code != 1 and "class" in bundle and "test" in bundle:
            results[(bundle["class"].rsplit(".", 1)[-1], bundle["test"])] = code
        bundle.clear()
    elif line == "INSTRUMENTATION_CODE: -1":
        finished = True

expected |= set(results)
suite = ET.Element("testsuite", name=f"Hail.Android16.{args.suite}", tests=str(len(expected)))
failures = 0
for cls, name in sorted(expected):
    node = ET.SubElement(suite, "testcase", classname=cls, name=name)
    code = results.get((cls, name))
    if code != 0:
        failures += 1
        message = "No completion record" if code is None else f"Instrumentation status {code}"
        ET.SubElement(node, "failure", message=message)
        print(f"{cls}.{name}: {message}", file=sys.stderr)
suite.set("failures", str(failures))
xml_name = "TEST-Hail.xml" if args.suite == "standard" else "TEST-Hail-large-shortcuts.xml"
ET.ElementTree(suite).write(log.with_name(xml_name), encoding="utf-8", xml_declaration=True)
if failures or not finished:
    raise SystemExit(f"Android release scenarios did not all pass. See {log.name}.")
if args.suite == "large-shortcuts":
    evidence = json.loads(args.evidence.read_text())
    packages = {f"com.aistra.hail.testfixture.large{index:03d}" for index in range(1, 285)}
    for key in ("expectedPackages", "added", "uniquePinnedFixtureIds"):
        assert len(evidence[key]) == 284 and set(evidence[key]) == packages, key
    assert evidence["fixtureCount"] == 284
    assert evidence["requestCount"] == 284
    assert evidence["skipped"] == []
    assert evidence["automaticTestLauncherApprovalClicks"] == 0
    assert evidence["item115LabelEqualsPublisher"] is True
    assert evidence["savedCompletedPosition"] == 284
    assert evidence["repeatExistingCount"] == 284
    assert evidence["repeatNewRequestCount"] == 0
    assert evidence["widgetIdsBefore"] == evidence["widgetIdsAfter"]
    icons = evidence["nativeIconEvidence"]
    assert {icon["label"] for icon in icons} == {"Hail 001", "Hail 116", "Hail 284"}
    assert all(icon["individuallyLongClickable"] is True for icon in icons)
    progress = evidence["progress"]
    assert 1 <= len(progress) <= 16
    assert all(point["total"] == 284 for point in progress)
    assert any(115 <= point["completed"] < 284 for point in progress)
    assert progress[-1]["completed"] == 284
    if args.expected_launcher:
        assert evidence["launcherPackage"] == args.expected_launcher
        assert all(icon["launcherPackage"] == args.expected_launcher for icon in icons)
    if args.expected_launcher_version:
        assert evidence["launcherVersionName"] == args.expected_launcher_version
    if args.expected_launcher == "com.teslacoilsw.launcher":
        assert args.environment_evidence is not None, "Nova requires authentic APK and ABI evidence"
        environment = json.loads(args.environment_evidence.read_text())
        assert environment["novaApkSha256"] == "262535b90196c651557c1b63d78b80f06d80434dd4e8aa9706639b1e5e9e630b"
        assert environment["novaSignerSha256"] == "47f8423082f390616c1809262aa6df3a11449a7bd05800cedc3da6774bd4ee9b"
        assert environment["novaPackage"] == args.expected_launcher
        assert environment["novaVersionName"] == args.expected_launcher_version == "8.0.18"
        assert environment["novaApkUnmodified"] is True
        assert environment["androidSdk"] == evidence["androidSdk"] == 36
        assert "arm64-v8a" in environment["deviceAbis"] and "arm64-v8a" in evidence["deviceAbis"]
        assert environment["resolvedHome"] == "com.teslacoilsw.launcher/com.teslacoilsw.launcher.NovaLauncher"
        assert evidence["novaHomeResumed"] is True
    print("Verified 284 newly pinned native shortcuts, saved progress and zero new requests when repeated.")
print(f"Verified {len(expected)} Android instrumentation scenarios ({args.suite}).")
