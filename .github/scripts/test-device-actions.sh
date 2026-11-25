#!/usr/bin/env bash
set -euo pipefail

# Package fixtures and legacy app data belong on the disposable CI emulator.
if [[ ${GITHUB_ACTIONS:-} != true ]] || ! grep -Eq '\[(ro.kernel.qemu|ro.boot.qemu)\]: \[1\]' <<< "$(adb shell getprop)"; then
    echo "This fixture setup requires the GitHub Actions emulator." >&2
    exit 1
fi
sdk_tools="$ANDROID_HOME/build-tools/36.0.0"
fixture_dir="$(mktemp -d)"
trap 'rm -rf "$fixture_dir"' EXIT
report=app/build/outputs/androidTest-results/connected/fixtures
mkdir -p "$report"
exec > >(tee "$report/setup.log") 2>&1
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
keytool -genkeypair -keystore "$fixture_dir/test.p12" -storetype PKCS12 -storepass android -keypass android -alias fixture -keyalg RSA -keysize 2048 -validity 2 -dname "CN=Hail CI Fixture"

# Ensure that the app and its instrumentation use the same temporary certificate
# even when the emulator action changes the SDK user directory.
install_test_apk() {
    local directory="$1" destination="$2"
    local source="$directory/$(jq -r '.elements[0].outputFile' "$directory/output-metadata.json")"
    "$sdk_tools/apksigner" sign --ks "$fixture_dir/test.p12" --ks-pass pass:android --ks-key-alias fixture --out "$destination" "$source"
    "$sdk_tools/apksigner" verify "$destination"
    adb install -r -t "$destination"
}
for pkg in com.aistra.hail.debug.test com.aistra.hail.debug; do
    if [[ "$(adb shell pm list packages "$pkg" | tr -d '\r')" == "package:$pkg" ]]; then adb uninstall "$pkg"; fi
done
install_test_apk app/build/outputs/apk/debug "$fixture_dir/hail.apk"
install_test_apk app/build/outputs/apk/androidTest/debug "$fixture_dir/instrumentation.apk"
adb shell 'run-as com.aistra.hail.debug mkdir -p files/v1'
adb shell -T 'run-as com.aistra.hail.debug sh -c "cat > files/v1/apps.json"' <<'JSON'
[{"package":"example.legacy.single","tag":0,"pinned":true},{"package":"example.legacy.multiple","tags":[0,-91],"whitelisted":true}]
JSON
adb shell -T 'run-as com.aistra.hail.debug sh -c "cat > files/v1/tags.json"' <<'JSON'
[{"tag":"Default","id":0},{"tag":"Legacy","id":-91}]
JSON
for name in one two; do
    cat > "$fixture_dir/AndroidManifest.xml" <<XML
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.aistra.hail.testfixture.$name" android:versionCode="1" android:versionName="1">
    <uses-sdk android:minSdkVersion="23" android:targetSdkVersion="36" />
    <application android:hasCode="false" android:label="Hail fixture $name">
        <activity android:name="android.app.Activity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
XML
    "$sdk_tools/aapt2" link -I "$ANDROID_HOME/platforms/android-37.2/android.jar" --manifest "$fixture_dir/AndroidManifest.xml" -o "$fixture_dir/$name.apk"
    "$sdk_tools/apksigner" sign --ks "$fixture_dir/test.p12" --ks-pass pass:android --ks-key-alias fixture "$fixture_dir/$name.apk"
    adb install -r "$fixture_dir/$name.apk"
done
# Exercise the same shell-backed Shizuku release and consent screen used on an unrooted phone.
shizuku_apk="$fixture_dir/shizuku.apk"
curl --fail --location --silent --show-error --retry 3 -o "$shizuku_apk" \
    https://github.com/RikkaApps/Shizuku/releases/download/v13.6.0/shizuku-v13.6.0.r1086.2650830c-release.apk
echo "6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f  $shizuku_apk" | sha256sum -c -
adb install "$shizuku_apk"
shizuku_dir="$(adb shell pm path moe.shizuku.privileged.api | tr -d '\r' | sed -n 's|^package:\(.*\)/base.apk$|\1|p')"
test -n "$shizuku_dir"
adb shell "$shizuku_dir/lib/x86_64/libshizuku.so"
classes=com.aistra.hail.app.DeviceFreezeTest,com.aistra.hail.ui.settings.BackupSettingsTest,com.aistra.hail.ui.settings.BulkShortcutLauncherTest,com.aistra.hail.ui.apps.SystemAppsTest
timeout 7m adb shell am instrument -w -r -e class "$classes" -e hailCiFixtures true \
    com.aistra.hail.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$report/runner.txt"
adb logcat -d -s HailTiming:I > "$report/timings.txt"
adb logcat -d -s Hail:E HailSystemApp:I > "$report/system-app.log"
python3 .github/scripts/check-device-results.py "$report/runner.txt"
