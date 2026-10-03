#!/bin/bash
set -euo pipefail
check_root="$(cd "$(dirname "$0")" && pwd)"
check_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
python3 "$check_root/fetch-dependencies.py" --host
mkdir -p "$check_root/build/host-tests" "$check_root/reports"
check_cp="$check_root/build/host-deps/*:$check_root/build/classes.jar:$check_root/build/deps/*:$check_sdk/platforms/android-36/android.jar"
javac --release 8 -classpath "$check_cp" -d "$check_root/build/host-tests" "$check_root/tests/HostChecks.java"
java -classpath "$check_root/build/host-tests:$check_cp" dev.mert.tvassistant.HostChecks | tee "$check_root/reports/local-speed-checks.txt"
