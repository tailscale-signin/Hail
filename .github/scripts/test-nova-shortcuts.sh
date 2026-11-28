#!/usr/bin/env bash
set -euo pipefail

# The fixture changes the default launcher and enables Hail's assistant through its test harness.
# It belongs only on the separate disposable GitHub Actions emulator.
if [[ ${GITHUB_ACTIONS:-} != true ]] || ! grep -Eq '\[(ro.kernel.qemu|ro.boot.qemu)\]: \[1\]' <<< "$(adb shell getprop)"; then
    echo "Nova shortcut verification requires the disposable GitHub Actions emulator." >&2
    exit 1
fi
report=app/build/outputs/androidTest-results/nova
mkdir -p "$report"
exec > >(tee "$report/setup.log") 2>&1
sdk_tools="$ANDROID_HOME/build-tools/36.0.0"
fixture_dir="$(mktemp -d)"
trap 'rm -rf "$fixture_dir"' EXIT
nova_package=com.teslacoilsw.launcher
nova_home="$nova_package/$nova_package.NovaLauncher"
nova_apk="$fixture_dir/NovaLauncher_8.0.18.apk"

adb shell getprop ro.product.cpu.abilist | tr -d '\r' > "$report/device-abis.txt"
adb shell getprop ro.dalvik.vm.native.bridge | tr -d '\r' > "$report/native-bridge.txt"
adb shell getprop ro.build.version.sdk | tr -d '\r' > "$report/android-sdk.txt"
case ",$(cat "$report/device-abis.txt")," in
    *,arm64-v8a,*) ;;
    *) echo "The official Nova APK requires ARM support. This image does not advertise arm64-v8a." >&2; exit 1 ;;
esac
test "$(cat "$report/android-sdk.txt")" = 36

shopt -s nullglob
candidate_apks=(.ci-apks/debug/*.apk)
instrumentation_apks=(.ci-apks/androidTest/*.apk)
shopt -u nullglob
test "${#candidate_apks[@]}" -eq 1
test "${#instrumentation_apks[@]}" -eq 1
"$sdk_tools/apksigner" verify --print-certs "${candidate_apks[0]}" > "$report/candidate-certificate.txt"
"$sdk_tools/apksigner" verify --print-certs "${instrumentation_apks[0]}" > "$report/instrumentation-certificate.txt"
python3 - "$report" <<'PY'
from pathlib import Path
import re
import sys
folder = Path(sys.argv[1])
def signer(name):
    return re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)', folder.joinpath(name).read_text())
candidate = signer('candidate-certificate.txt')
assert len(candidate) == 1 and candidate == signer('instrumentation-certificate.txt'), 'Candidate and test APKs must share their build signing identity'
PY
for pkg in com.aistra.hail.debug com.aistra.hail.debug.test "$nova_package"; do
    if adb shell pm path "$pkg" | tr -d '\r' | grep -q '^package:'; then
        echo "Expected a fresh emulator without $pkg already installed." >&2
        exit 1
    fi
done
adb install --no-incremental --no-streaming -t "${candidate_apks[0]}"
adb install --no-incremental --no-streaming -t "${instrumentation_apks[0]}"
sha256sum "${candidate_apks[0]}" "${instrumentation_apks[0]}" > "$report/candidate-apks.sha256"

# Download and install the publisher's exact APK. Do not repackage, re-sign or force an ABI.
curl --fail --location --silent --show-error --retry 3 -o "$nova_apk" \
    https://teslacoilapps.com/apk/NovaLauncher_8.0.18.apk
echo "262535b90196c651557c1b63d78b80f06d80434dd4e8aa9706639b1e5e9e630b  $nova_apk" | sha256sum -c -
"$sdk_tools/apksigner" verify --print-certs "$nova_apk" > "$report/nova-certificate.txt"
"$sdk_tools/aapt2" dump badging "$nova_apk" > "$report/nova-badging.txt"
python3 - "$report" <<'PY'
from pathlib import Path
import re
import sys
folder = Path(sys.argv[1])
signers = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)', folder.joinpath('nova-certificate.txt').read_text())
assert [value.lower() for value in signers] == ['47f8423082f390616c1809262aa6df3a11449a7bd05800cedc3da6774bd4ee9b']
package = dict(re.findall(r"(\w+)='([^']*)'", folder.joinpath('nova-badging.txt').read_text().splitlines()[0]))
assert package['name'] == 'com.teslacoilsw.launcher' and package['versionName'] == '8.0.18'
PY
adb install --no-incremental --no-streaming "$nova_apk"
adb shell cmd package set-home-activity --user 0 "$nova_home"
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME \
    | tr -d '\r' > "$report/resolved-home.txt"
adb shell dumpsys package "$nova_package" > "$report/nova-installed-package.txt"

keytool -genkeypair -keystore "$fixture_dir/fixtures.p12" -storetype PKCS12 \
    -storepass android -keypass android -alias fixture -keyalg RSA -keysize 2048 \
    -validity 2 -dname "CN=Hail Nova CI Fixture"
for index in $(seq 1 284); do
    number=$(printf '%03d' "$index")
    label="Hail $number"
    if [[ $index == 115 ]]; then label=Hail; fi
    cat > "$fixture_dir/AndroidManifest.xml" <<XML
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.aistra.hail.testfixture.large$number" android:versionCode="1" android:versionName="1">
    <uses-sdk android:minSdkVersion="23" android:targetSdkVersion="36" />
    <application android:hasCode="false" android:label="$label">
        <activity android:name="android.app.Activity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
XML
    fixture_apk="$fixture_dir/large$number.apk"
    "$sdk_tools/aapt2" link -I "$ANDROID_HOME/platforms/android-36/android.jar" \
        --manifest "$fixture_dir/AndroidManifest.xml" -o "$fixture_apk"
    "$sdk_tools/apksigner" sign --ks "$fixture_dir/fixtures.p12" --ks-pass pass:android --ks-key-alias fixture "$fixture_apk"
    adb install --no-incremental --no-streaming "$fixture_apk"
done

python3 - "$report" "$nova_apk" <<'PY'
from pathlib import Path
import hashlib
import json
import re
import sys
folder, apk = map(Path, sys.argv[1:])
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
assert digest == '262535b90196c651557c1b63d78b80f06d80434dd4e8aa9706639b1e5e9e630b'
signer = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)', folder.joinpath('nova-certificate.txt').read_text())
assert len(signer) == 1
package = dict(re.findall(r"(\w+)='([^']*)'", folder.joinpath('nova-badging.txt').read_text().splitlines()[0]))
home = next(line.strip() for line in reversed(folder.joinpath('resolved-home.txt').read_text().splitlines()) if '/' in line)
owner, activity = home.split('/', 1)
if activity.startswith('.'):
    activity = owner + activity
home = owner + '/' + activity
assert home == 'com.teslacoilsw.launcher/com.teslacoilsw.launcher.NovaLauncher'
evidence = {
    'novaApkSha256': digest, 'novaSignerSha256': signer[0].lower(), 'novaApkUnmodified': True,
    'novaSourceUrl': 'https://teslacoilapps.com/apk/NovaLauncher_8.0.18.apk',
    'novaPackage': package['name'], 'novaVersionName': package['versionName'],
    'novaVersionCode': int(package['versionCode']), 'resolvedHome': home,
    'androidSdk': int(folder.joinpath('android-sdk.txt').read_text().strip()),
    'deviceAbis': folder.joinpath('device-abis.txt').read_text().strip().split(','),
    'nativeBridge': folder.joinpath('native-bridge.txt').read_text().strip(),
}
folder.joinpath('environment.json').write_text(json.dumps(evidence, indent=2) + '\n')
PY

capture_failure() {
    timeout 12s adb logcat -b main -b system -b crash -d -t 4000 > "$report/runner-failure.log" || true
    timeout 8s adb shell dumpsys activity activities > "$report/runner-activities.txt" || true
    timeout 8s adb shell dumpsys accessibility > "$report/runner-accessibility.txt" || true
}
runner="$report/large-shortcuts-runner.txt"
rc=0
timeout 15m adb shell am instrument -w -r \
    -e class com.aistra.hail.ui.settings.LargeShortcutBatchTest \
    -e hailCiFixtures true -e hailLargeShortcutBatch true \
    -e hailExpectedLauncherPackage "$nova_package" -e hailExpectedLauncherVersion 8.0.18 \
    com.aistra.hail.debug.test/androidx.test.runner.AndroidJUnitRunner > "$runner" || rc=$?
cat "$runner"
adb pull /sdcard/Android/data/com.aistra.hail.debug/files/verification-large \
    "$report/large-shortcut-evidence" >/dev/null 2>&1 || true
if [[ $rc != 0 ]]; then capture_failure; exit "$rc"; fi
if ! python3 .github/scripts/check-device-results.py "$runner" --suite large-shortcuts \
    --evidence "$report/large-shortcut-evidence/large-shortcuts.json" \
    --expected-launcher "$nova_package" --expected-launcher-version 8.0.18 \
    --environment-evidence "$report/environment.json"; then
    capture_failure
    exit 1
fi
