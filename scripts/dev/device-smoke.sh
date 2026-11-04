#!/usr/bin/env bash
# On-device release smoke pass over loopback ADB. Keeps your data: installs with -r and runs the
# instrumentation directly (gradle's connected*AndroidTest would UNINSTALL the app afterwards,
# wiping authorizations and settings).
#
#   1. builds + installs the debug app (build-install-debug.sh: libc++ patch + re-sign)
#   2. builds + installs the androidTest APK, runs JniSmokeTest + ScreenSmokeTest on the phone
#   3. cold-launches the app, saves a screenshot + UI dump, and fails on any FATAL in logcat
#
# Usage:  bash scripts/dev/device-smoke.sh [--no-build]
# Output: $OUT (default /tmp/shizuku-smoke-<time>/)
set -uo pipefail
cd "$(dirname "$0")/../.."

ADB_SERIAL="${ADB_SERIAL:-127.0.0.1:5555}"
PKG="af.shizuku.plus.api"
TEST_PKG="${PKG}.test"
OUT="${OUT:-${TMPDIR:-/tmp}/shizuku-smoke-$(date +%Y%m%d-%H%M%S)}"
TEST_APK_DIRS=("${HOME}/shizuku-build/manager/outputs/apk/androidTest/shizukuplus/debug" "manager/build/outputs/apk/androidTest/shizukuplus/debug")
adb() { command adb -s "$ADB_SERIAL" "$@"; }
fail=0
mkdir -p "$OUT"

adb get-state >/dev/null 2>&1 || command adb connect "$ADB_SERIAL" >/dev/null
[ "$(adb get-state 2>/dev/null)" = "device" ] || { echo "✗ no device at $ADB_SERIAL (turn on Wireless debugging / shizuku-start)"; exit 2; }

if [ "${1:-}" != "--no-build" ]; then
    bash scripts/dev/build-install-debug.sh || { echo "✗ app build/install failed"; exit 1; }
    bash gradlew -q :manager:assembleShizukuplusDebugAndroidTest || { echo "✗ test APK build failed"; exit 1; }
fi

TEST_APK=""
for d in "${TEST_APK_DIRS[@]}"; do TEST_APK="$(ls "$d"/*.apk 2>/dev/null | head -1)"; [ -n "$TEST_APK" ] && break; done
[ -n "$TEST_APK" ] || { echo "✗ androidTest APK not found"; exit 1; }
adb install -r -t "$TEST_APK" >/dev/null || { echo "✗ test APK install failed (signature must match the installed app)"; exit 1; }

adb logcat -c
echo "▸ Instrumentation smoke tests"
adb shell am instrument -w -r \
    -e class af.shizuku.manager.JniSmokeTest,af.shizuku.manager.ScreenSmokeTest \
    "${TEST_PKG}/androidx.test.runner.AndroidJUnitRunner" > "$OUT/instrument.txt" 2>&1
if command grep -q "INSTRUMENTATION_CODE: -1" "$OUT/instrument.txt" && ! command grep -qE "FAILURES!!!|Process crashed" "$OUT/instrument.txt"; then
    echo "  ✓ $(command grep -c 'INSTRUMENTATION_STATUS_CODE: 0' "$OUT/instrument.txt") tests passed"
else
    echo "  ✗ instrumentation failed — see $OUT/instrument.txt"; fail=1
fi

echo "▸ Cold launch"
adb shell am force-stop "$PKG"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 6
if adb shell pidof "$PKG" >/dev/null; then echo "  ✓ running"; else echo "  ✗ not running after launch"; fail=1; fi
adb exec-out screencap -p > "$OUT/launch.png" 2>/dev/null
adb shell uiautomator dump /data/local/tmp/smoke-ui.xml >/dev/null 2>&1 && adb pull /data/local/tmp/smoke-ui.xml "$OUT/launch-ui.xml" >/dev/null 2>&1

adb logcat -d > "$OUT/logcat.txt"
if command grep -nE "FATAL EXCEPTION|AndroidRuntime: Process: ${PKG}" "$OUT/logcat.txt" | head -5 | command grep -q .; then
    echo "  ✗ crash in logcat:"; command grep -nE -A6 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -20; fail=1
else
    echo "  ✓ no FATAL in logcat"
fi

echo "▸ Results in $OUT"
[ $fail -eq 0 ] && echo "✓ smoke pass OK" || echo "✗ smoke pass FAILED"
exit $fail
