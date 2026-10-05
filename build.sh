#!/bin/bash
set -euo pipefail
app_root="$(cd "$(dirname "$0")" && pwd)"
workspace_root="$app_root"
app_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
app_tools="$app_sdk/build-tools/36.0.0"
app_android="$app_sdk/platforms/android-36/android.jar"
app_build="$workspace_root/build"
mkdir -p "$app_build" "$workspace_root/dist"
python3 "$app_root/fetch-dependencies.py"
app_deps="$app_build/deps/*"
rm -rf "$app_build/classes" "$app_build/dex"
mkdir -p "$app_build/classes" "$app_build/dex"
"$app_tools/aapt2" compile --dir "$app_root/res" -o "$app_build/resources.zip"
"$app_tools/aapt2" link -o "$app_build/unsigned.apk" -I "$app_android" --manifest "$app_root/AndroidManifest.xml" "$app_build/resources.zip"
find "$app_root/src" -name '*.java' > "$app_build/sources.txt"
javac --release 8 -classpath "$app_android:$app_deps" -d "$app_build/classes" @"$app_build/sources.txt"
jar cf "$app_build/classes.jar" -C "$app_build/classes" .
"$app_tools/d8" --min-api 23 --lib "$app_android" --output "$app_build/dex" "$app_build/classes.jar" "$app_build"/deps/*.jar
python3 - "$app_build/unsigned.apk" "$app_build/dex/classes.dex" "$app_root/THIRD_PARTY_NOTICES.md" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], 'a', compression=zipfile.ZIP_DEFLATED) as archive:
    archive.write(sys.argv[2], 'classes.dex')
    archive.write(sys.argv[3], 'assets/THIRD_PARTY_NOTICES.md')
PY
if [ ! -f "$app_build/debug.keystore" ]; then
 keytool -genkeypair -keystore "$app_build/debug.keystore" -storepass android -keypass android -alias tvassistant -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=TV Assistant Development'
fi
"$app_tools/zipalign" -f 4 "$app_build/unsigned.apk" "$app_build/aligned.apk"
"$app_tools/apksigner" sign --ks "$app_build/debug.keystore" --ks-key-alias tvassistant --ks-pass pass:android --key-pass pass:android --out "$workspace_root/dist/tv-assistant.apk" "$app_build/aligned.apk"
"$app_tools/apksigner" verify "$workspace_root/dist/tv-assistant.apk"
printf 'Built %s\n' "$workspace_root/dist/tv-assistant.apk"
