"""Require a completed, passing Android instrumentation result for each release scenario."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

expected = {
    ("DebugLogTest", "offByDefaultOrderedBoundedAndExportableAfterStopping"),
    ("DeviceFreezeTest", "freezeRepeatThenThawLaunchAndRestoreFromBackup"),
    ("DeviceFreezeTest", "privateDefaultsAndBrandResourcesArePackaged"),
    ("DeviceFreezeTest", "readsAndroidSuspensionAndReportsDeniedOwnerMutation"),
    ("BackupSettingsTest", "existingSingleTagAndMultiTagListsSurviveUpgrade"),
    ("BackupSettingsTest", "invalidBackupsAndDeviceLocalSettings"),
    ("BackupSettingsTest", "settingsWritesFilePreviewsCancelsAndRestoresMultiTagBackup"),
    ("BulkShortcutLauncherTest", "dismissRetryApproveAndRepeatFromSettings"),
    ("SystemAppsTest", "idleModeOpensAndroidSettingsForTheSelectedSystemApp"),
    ("SystemAppsTest", "disablesAndEnablesAPreinstalledAppWithShizukuPermission"),
    ("SystemAppsTest", "deniedSystemAppChangePreservesEnabledState"),
    ("SystemAppsTest", "retriesOnlyUnappliedPackagesAfterAcknowledgedOrInterruptedBatch"),
    ("SystemAppsTest", "retainedSystemAppShowsProfileStatusAndCanBeRecheckedFromHome"),
    ("SystemAppsTest", "retainedSystemAppCanStoreShizukuOverrideWithoutReinstalling"),
    ("SystemAppsTest", "installedDisableVerificationDoesNotRequireRedundantInstalledFlag"),
    ("SystemAppsTest", "installedEvidenceAndRecycledHomeColorsAreIndependentOfRetainedFlags"),
}
log = Path(sys.argv[1])
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

suite = ET.Element("testsuite", name="Hail.Android16", tests=str(len(expected)))
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
ET.ElementTree(suite).write(log.with_name("TEST-Hail.xml"), encoding="utf-8", xml_declaration=True)
if failures or not finished:
    raise SystemExit("Android release scenarios did not all pass. See runner.txt.")
print(f"Verified {len(expected)} Android instrumentation scenarios.")
