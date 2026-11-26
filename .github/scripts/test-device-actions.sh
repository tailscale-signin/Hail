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
    adb install --no-incremental --no-streaming -r -t "$destination"
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
install_fixture() {
    local name="$1" enabled="${2:-true}"
    cat > "$fixture_dir/AndroidManifest.xml" <<XML
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.aistra.hail.testfixture.$name" android:versionCode="1" android:versionName="1">
    <uses-sdk android:minSdkVersion="23" android:targetSdkVersion="36" />
    <application android:hasCode="false" android:label="Hail fixture $name" android:enabled="$enabled">
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
    adb install --no-incremental --no-streaming -r "$fixture_dir/$name.apk"
}
for name in one two; do install_fixture "$name"; done
install_fixture dormant false
# Exercise the same shell-backed Shizuku release and consent screen used on an unrooted phone.
shizuku_apk="$fixture_dir/shizuku.apk"
curl --fail --location --silent --show-error --retry 3 -o "$shizuku_apk" \
    https://github.com/RikkaApps/Shizuku/releases/download/v13.6.0/shizuku-v13.6.0.r1086.2650830c-release.apk
echo "6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f  $shizuku_apk" | sha256sum -c -
adb install --no-incremental --no-streaming "$shizuku_apk"
shizuku_dir="$(adb shell pm path moe.shizuku.privileged.api | tr -d '\r' | sed -n 's|^package:\(.*\)/base.apk$|\1|p')"
test -n "$shizuku_dir"
adb shell "$shizuku_dir/lib/x86_64/libshizuku.so"
classes=com.aistra.hail.app.DeviceFreezeTest,com.aistra.hail.ui.settings.BackupSettingsTest,com.aistra.hail.ui.settings.BulkShortcutLauncherTest,com.aistra.hail.ui.apps.SystemAppsTest,com.aistra.hail.ui.settings.DebugLogTest
# Have the complete APK on the device before instrumentation starts. Keep Android's startup diagnostics on failure.
capture_runner_state() {
    local prefix="$1"
    timeout 12s adb logcat -b main -b system -b crash -d -t 4000 > "$report/$prefix.log" || true
    timeout 8s adb shell dumpsys activity instrumentation > "$report/$prefix-activity.txt" || true
    timeout 8s adb shell ps -A > "$report/$prefix-processes.txt" || true
}
run_device_suite() {
    timeout 7m adb shell am instrument -w -r -e class "$classes" -e hailCiFixtures true \
        com.aistra.hail.debug.test/androidx.test.runner.AndroidJUnitRunner > "$report/runner.txt" &
    local pid=$!
    for _ in {1..45}; do
        if [[ -s "$report/runner.txt" ]] || ! kill -0 "$pid" 2>/dev/null; then break; fi
        sleep 1
    done
    if [[ ! -s "$report/runner.txt" ]] && kill -0 "$pid" 2>/dev/null; then
        capture_runner_state runner-startup
        adb shell am force-stop com.aistra.hail.debug
        kill "$pid" 2>/dev/null || true
        wait "$pid" || true
        return 2
    fi
    local rc=0
    wait "$pid" || rc=$?
    cat "$report/runner.txt"
    return "$rc"
}
rc=0
run_device_suite || rc=$?
if [[ $rc == 2 ]]; then
    echo "The device did not start instrumentation; retrying the cold start once."
    rc=0
    run_device_suite || rc=$?
fi
if [[ $rc != 0 ]]; then capture_runner_state runner-failure; exit "$rc"; fi
adb logcat -d -s HailTiming:I > "$report/timings.txt"
adb logcat -d -s Hail:E HailSystemApp:I > "$report/system-app.log"
python3 .github/scripts/check-device-results.py "$report/runner.txt"

pull_trace() {
    local sample="$1" destination="$2" path
    for _ in {1..15}; do
        path=$(adb shell "find /storage/emulated/0 -type f -name 'Hail-trace-$sample.jsonl*' 2>/dev/null" | tr -d '\r' | head -n 1) || true
        if [[ -n $path ]] && adb pull "$path" "$destination" >/dev/null && [[ -s $destination ]]; then return 0; fi
        sleep 1
    done
    return 1
}

# Compare the official release and the optimized fork in the same user on the same disposable Android image.
./gradlew :app:assembleRelease
upstream="$fixture_dir/upstream.apk"
curl --fail --location --silent --show-error --retry 3 -o "$upstream" \
    https://github.com/aistra0528/Hail/releases/download/v1.11.0/Hail-v1.11.0.apk
echo "3be0c4a210b0472198ea3b675fcbd5720fa16288dcff119557644ea947003236  $upstream" | sha256sum -c -
"$sdk_tools/apksigner" verify "$upstream"
release_dir=app/build/outputs/apk/release
release_version=$(jq -er '.elements[0].versionName' "$release_dir/output-metadata.json")
release_code=$(jq -er '.elements[0].versionCode' "$release_dir/output-metadata.json")
release_apk="$release_dir/$(jq -r '.elements[0].outputFile' "$release_dir/output-metadata.json")"
"$sdk_tools/apksigner" sign --ks "$fixture_dir/test.p12" --ks-pass pass:android --ks-key-alias fixture --out "$fixture_dir/fork.apk" "$release_apk"
build_previous() {
    local sha="$1" version="$2" name="$3" work="$fixture_dir/previous-src"
    git fetch --depth=1 origin "$sha"
    git worktree add --detach "$work" "$sha"
    (cd "$work" && ./gradlew :app:assembleRelease)
    local apk="$work/app/build/outputs/apk/release/Hail-v$version.apk"
    "$sdk_tools/apksigner" sign --ks "$fixture_dir/test.p12" --ks-pass pass:android --ks-key-alias fixture --out "$fixture_dir/$name.apk" "$apk"
    git worktree remove --force "$work"
}
build_previous c6608891e5f1a2719e57e51db3645e7c701a2c29 1.13.2 legacy
build_previous 6a978311499cbabb0655e6cfed2e5328c5aed7ad 1.13.3 baseline

# The same app list, emulator and authorized shell service are used on both sides of an in-place update.
for name in bench{1..12}; do install_fixture "$name"; done
for distribution in baseline current; do
    apk=baseline
    if [[ $distribution == current ]]; then apk=fork; fi
    adb install --no-incremental --no-streaming -r "$fixture_dir/$apk.apk"
    output="$report/benchmark-$distribution.txt"
    timeout 5m adb shell am instrument -w -r -e class 'com.aistra.hail.ui.apps.ReleaseSystemAppsTest#measuresBulkDisableAndEnableOnInstalledFixtures' \
        -e hailBulkBenchmark true -e distribution "$distribution" com.aistra.hail.debug.test/androidx.test.runner.AndroidJUnitRunner | tr -d '\r' | tee "$output"
    grep -Fxq 'INSTRUMENTATION_STATUS_CODE: 0' "$output"
    grep -Fxq 'INSTRUMENTATION_CODE: -1' "$output"
    pull_trace "benchmark-$distribution" "$report/benchmark-$distribution.jsonl"
done
python3 .github/scripts/check-bulk-performance.py "$report" "$release_version" "$release_code" | tee "$report/bulk-performance.txt"

for server in shell root; do
    uid=2000
    if [[ $server == root ]]; then
        adb root
        adb wait-for-device
        test "$(adb shell id -u | tr -d '\r')" = 0
        adb shell "$shizuku_dir/lib/x86_64/libshizuku.so"
        uid=0
    fi
    actual_uid=""
    for attempt in {1..30}; do
        pid="$(adb shell pidof shizuku_server | tr -d '\r' || true)"
        if [[ $pid =~ ^[0-9]+$ ]]; then
            actual_uid="$(adb shell "cat /proc/$pid/status" | tr -d '\r' | awk '/^Uid:/ {print $2}')"
            if [[ $actual_uid == "$uid" ]]; then break; fi
        fi
        sleep 1
    done
    test "$actual_uid" = "$uid"
    distributions=(upstream fork)
    if [[ $server == root ]]; then distributions=(upstream legacy fork); fi
    for distribution in "${distributions[@]}"; do
        if [[ $server == root && $distribution == fork ]]; then
            adb install --no-incremental --no-streaming -r "$fixture_dir/fork.apk"
        else
            if adb shell pm path com.aistra.hail | tr -d '\r' | grep -q '^package:'; then adb uninstall com.aistra.hail; fi
            adb install --no-incremental --no-streaming "$fixture_dir/$distribution.apk"
        fi
        output="$report/parity-$distribution-$server.txt"
        timeout 4m adb shell am instrument -w -r -e class 'com.aistra.hail.ui.apps.ReleaseSystemAppsTest#freezesAndThawsFromHome' \
            -e hailReleaseParity true -e server "$server" -e distribution "$distribution" \
            com.aistra.hail.debug.test/androidx.test.runner.AndroidJUnitRunner | tr -d '\r' | tee "$output"
        adb logcat -d -s Hail:E HailParity:I | tail -n 90 > "$report/backend-$distribution-$server.log"
        grep -Fxq 'INSTRUMENTATION_STATUS_CODE: 0' "$output"
        grep -Fxq 'INSTRUMENTATION_CODE: -1' "$output"
        if [[ $distribution == fork ]]; then
            pull_trace "$server" "$report/decision-$server.jsonl"
            python3 .github/scripts/check-decision-log.py "$report/decision-$server.jsonl" "$server" "$release_version" "$release_code"
        fi
        grep 'HailParity.*Android verified' "$report/backend-$distribution-$server.log" | tail -n 1 >> "$report/parity.log"
    done
done
