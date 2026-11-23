#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
adb="$PWD/.deps/android-sdk/platform-tools/adb"
if [[ ! -x "$adb" ]]; then echo 'Run python3 scripts/android-bootstrap.py first.' >&2; exit 1; fi
case "${1:-devices}" in
  pair) "$adb" pair "${2:?Use pair IP:PAIRING_PORT}" ;;
  connect) "$adb" connect "${2:?Use connect IP:DEBUG_PORT}" ;;
  devices) "$adb" devices -l ;;
  install) apk="${2:-dist/android/0.1.12/SillyPlayer-0.1.12-android-arm64-v8a.apk}"; if [[ -n "${3:-}" ]]; then "$adb" -s "$3" install -r "$apk"; else "$adb" install -r "$apk"; fi ;;
  log) "$adb" logcat -s SillyPlayer AndroidRuntime ;;
  *) echo 'Usage: android-device.sh devices|pair IP:PORT|connect IP:PORT|install [APK] [SERIAL]|log' >&2; exit 2 ;;
esac
