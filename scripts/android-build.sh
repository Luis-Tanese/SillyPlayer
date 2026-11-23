#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
if [[ ! -x .deps/android-sdk/platform-tools/adb || ! -x .deps/gradle-8.13/bin/gradle || ! -f mobile/android/local.properties ]]; then python3 scripts/android-bootstrap.py; fi
python3 scripts/android-native.py
key_dir="$HOME/.local/share/SillyPlayer/signing"
mkdir -p "$key_dir"
chmod 700 "$key_dir"
if [[ ! -f "$key_dir/android-debug.keystore" ]]; then
  keytool -genkeypair -keystore "$key_dir/android-debug.keystore" -storepass android -keypass android -alias androiddebugkey -dname "CN=SillyPlayer Development" -keyalg RSA -keysize 3072 -validity 10000 >/dev/null 2>&1
  chmod 600 "$key_dir/android-debug.keystore"
fi
if [ "$#" -eq 0 ]; then set -- :app:assembleDebug; fi
cd mobile/android
../../.deps/gradle-8.13/bin/gradle --console=plain --max-workers=3 "$@"
