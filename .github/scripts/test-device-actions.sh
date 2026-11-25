#!/usr/bin/env bash
set -euo pipefail

# This script runs only inside the disposable CI emulator.
sdk_tools="$ANDROID_HOME/build-tools/36.0.0"
fixture_dir="$(mktemp -d)"
trap 'rm -rf "$fixture_dir"' EXIT
apk_dir=app/build/outputs/apk/debug
adb install -r "$apk_dir/$(jq -r '.elements[0].outputFile' "$apk_dir/output-metadata.json")"
adb shell dpm set-device-owner com.aistra.hail.debug/com.aistra.hail.receiver.DeviceAdminReceiver
for name in one two; do
    cat > "$fixture_dir/AndroidManifest.xml" <<XML
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.aistra.hail.testfixture.$name">
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
    "$sdk_tools/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --ks-key-alias androiddebugkey "$fixture_dir/$name.apk"
    adb install -r "$fixture_dir/$name.apk"
done
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.hailCiFixtures=true
