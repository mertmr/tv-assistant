#!/bin/bash
set -euo pipefail
test_root="$(cd "$(dirname "$0")" && pwd)"
workspace_root="$test_root"
test_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
test_tools="$test_sdk/build-tools/36.0.0"
test_android="$test_sdk/platforms/android-36/android.jar"
test_build="$workspace_root/build/tests"
app_build="$workspace_root/build"
mkdir -p "$test_build/classes" "$test_build/dex" "$workspace_root/reports"
"$test_tools/aapt2" link -o "$test_build/unsigned.apk" -I "$test_android" --manifest "$test_root/tests/AndroidManifest.xml"
javac --release 8 -classpath "$test_android:$app_build/classes.jar:$app_build/deps/*" -d "$test_build/classes" "$test_root/tests/TestRunner.java"
jar cf "$test_build/tests.jar" -C "$test_build/classes" .
"$test_tools/d8" --min-api 23 --lib "$test_android" --classpath "$app_build/classes.jar" --output "$test_build/dex" "$test_build/tests.jar"
python3 - "$test_build/unsigned.apk" "$test_build/dex/classes.dex" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], 'a', compression=zipfile.ZIP_DEFLATED) as archive:
    archive.write(sys.argv[2], 'classes.dex')
PY
"$test_tools/zipalign" -f 4 "$test_build/unsigned.apk" "$test_build/aligned.apk"
"$test_tools/apksigner" sign --ks "$app_build/debug.keystore" --ks-key-alias tvassistant --ks-pass pass:android --key-pass pass:android --out "$test_build/tests.apk" "$test_build/aligned.apk"
test_adb_port="${TV_ADB_PORT:-5038}"
test_device="${TV_DEVICE:-192.168.1.104:5555}"
if [[ "${TV_TEST_GROUP:-}" == emulator* ]]; then
 [[ "$test_device" == emulator-* ]] || { printf 'Refusing emulator tests on a physical device.\n'; exit 1; }
 [[ "$(adb -P "$test_adb_port" -s "$test_device" shell getprop ro.kernel.qemu | tr -d '\r')" == "1" ]] || exit 1
fi
adb -P "$test_adb_port" -s "$test_device" install -r "$test_build/tests.apk"
if [ "${TV_TEST_GROUP:-}" = "emulator-native" ]; then
 test_result="${TV_TEST_REPORT:-$workspace_root/reports/emulator-native-results.txt}"
 adb -P "$test_adb_port" -s "$test_device" shell am instrument -w -e group emulator-native dev.mert.tvassistant.tests/dev.mert.tvassistant.TestRunner | tee "$test_result"
elif [ "${TV_TEST_GROUP:-}" = "emulator" ]; then
 test_result="${TV_TEST_REPORT:-$workspace_root/reports/emulator-api34-results.txt}"
 adb -P "$test_adb_port" -s "$test_device" shell am instrument -w -e group emulator dev.mert.tvassistant.tests/dev.mert.tvassistant.TestRunner | tee "$test_result"
elif [ "${TV_TEST_GROUP:-}" = "benchmark" ]; then
 test_result="$workspace_root/reports/benchmark-results.txt"
 adb -P "$test_adb_port" -s "$test_device" shell am instrument -w -e group benchmark dev.mert.tvassistant.tests/dev.mert.tvassistant.TestRunner | tee "$test_result"
elif [ "${TV_TEST_GROUP:-}" = "youtube" ]; then
 test_result="$workspace_root/reports/tv-assistant-youtube-test-results.txt"
 adb -P "$test_adb_port" -s "$test_device" shell am instrument -w -e group youtube dev.mert.tvassistant.tests/dev.mert.tvassistant.TestRunner | tee "$test_result"
else
 test_result="$workspace_root/reports/tv-assistant-test-results.txt"
 adb -P "$test_adb_port" -s "$test_device" shell am instrument -w dev.mert.tvassistant.tests/dev.mert.tvassistant.TestRunner | tee "$test_result"
fi
rg --quiet '^[0-9]+ passed, 0 failed' "$test_result"
