#!/usr/bin/env bash
set -euo pipefail

# Every device gate consumes the same signed debug/test bytes from build_candidate.
# Do not rebuild or re-sign them when the emulator action changes SDK user directories.
report="${1:?A verification report directory is required}"
mkdir -p "$report"
sdk_tools="$ANDROID_HOME/build-tools/36.0.0"
for directory in .ci-apks/debug .ci-apks/androidTest; do
    (cd "$directory" && sha256sum -c SHA256SUMS)
done
python3 - <<'PY'
from pathlib import Path
import json
for variant, package in (('debug', 'com.aistra.hail.debug'), ('androidTest', 'com.aistra.hail.debug.test')):
    directory = Path('.ci-apks') / variant
    metadata = json.loads(directory.joinpath('output-metadata.json').read_text())
    assert metadata['applicationId'] == package, f'Unexpected {variant} application ID'
    assert len(metadata['elements']) == 1, f'Expected one {variant} APK'
    filename = metadata['elements'][0]['outputFile']
    assert Path(filename).name == filename and filename.endswith('.apk'), 'Invalid APK filename'
    assert sorted(path.name for path in directory.glob('*.apk')) == [filename], 'APK and metadata disagree'
PY
candidate=".ci-apks/debug/$(jq -er '.elements[0].outputFile' .ci-apks/debug/output-metadata.json)"
instrumentation=".ci-apks/androidTest/$(jq -er '.elements[0].outputFile' .ci-apks/androidTest/output-metadata.json)"
"$sdk_tools/apksigner" verify --print-certs "$candidate" > "$report/candidate-certificate.txt"
"$sdk_tools/apksigner" verify --print-certs "$instrumentation" > "$report/instrumentation-certificate.txt"
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
for package in com.aistra.hail.debug com.aistra.hail.debug.test; do
    if adb shell pm path "$package" | tr -d '\r' | grep -q '^package:'; then
        echo "Expected a fresh emulator without $package already installed." >&2
        exit 1
    fi
done
adb install --no-incremental --no-streaming -t "$candidate"
adb install --no-incremental --no-streaming -t "$instrumentation"
sha256sum "$candidate" "$instrumentation" > "$report/candidate-apks.sha256"
