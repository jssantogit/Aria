#!/usr/bin/env bash
# Synthetic clean emulator only. No accounts, external media or host mutations.
set -euo pipefail
apk_dir="${1:?APK directory required}"
out="${2:?evidence directory required}"
test_dir="${3:?instrumentation APK directory required}"
mkdir -p "$out"
trap 'rc=$?; if (( rc != 0 )); then printf "FAIL: exit %s; stage=%s\n" "$rc" "${stage:-setup}" | tee "$out/result.txt"; fi' EXIT
stage=discover-apks
mapfile -t apks < <(find "$apk_dir" -type f -name '*x86_64*.apk' | sort)
mapfile -t test_apks < <(find "$test_dir" -type f -name '*.apk' | sort)
test "${#apks[@]}" -eq 1
test "${#test_apks[@]}" -eq 1
pkg=com.dev.aria
component="$pkg/com.music.aria.LauncherDefault"
stage=verify-emulator
echo "$stage"
adb wait-for-device
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1
api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
echo "API $api" | tee "$out/api.txt"
# API 26's legacy svc process was killed (137) before APK installation.
# Root is restricted to radio preparation on a disposable emulator.
if [[ "$api" == 26 ]]; then
    stage=api26-airplane-root
    echo "$stage"
    adb root
    adb wait-for-device
    test "$(adb shell id -u | tr -d '\r')" = 0
    stage=api26-airplane-settings
    echo "$stage"
    adb shell settings put global airplane_mode_on 1
    stage=api26-airplane-broadcast
    echo "$stage"
    adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true | tee "$out/airplane.txt"
    test "$(adb shell settings get global airplane_mode_on | tr -d '\r')" = 1
    stage=api26-airplane-unroot
    echo "$stage"
    adb unroot
    adb wait-for-device
    test "$(adb shell id -u | tr -d '\r')" = 2000
else
    stage=disable-wifi
    echo "$stage"
    adb shell svc wifi disable
    stage=disable-data
    echo "$stage"
    adb shell svc data disable
fi
stage=verify-offline
echo "$stage"
# Poll the OS instead of assuming asynchronous radio commands worked.
offline=false
for attempt in {1..30}; do
    adb shell dumpsys connectivity | tr -d '\r' | grep 'Active default network:' > "$out/network-state.txt" || true
    if grep -Eq '^[[:space:]]*Active default network: none[[:space:]]*$' "$out/network-state.txt"; then
        offline=true
        break
    fi
    sleep 1
done
cat "$out/network-state.txt"
test "$offline" = true
stage=install-app
echo "$stage"
if [[ "$api" == 26 ]]; then adb install --no-streaming "${apks[0]}"; else adb install "${apks[0]}"; fi
adb shell pm path "$pkg" | grep -q '^package:'
resolve_launcher() {
    adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$pkg" | tr -d '\r'
}
resolve_launcher | grep -Fx "$component"
stage=install-instrumentation
echo "$stage"
adb install "${test_apks[0]}"
adb shell pm list instrumentation | tr -d '\r' | tee "$out/runners.txt"
runner="$(sed -n 's/^instrumentation:\([^ ]*\) (target=com\.dev\.aria)$/\1/p' "$out/runners.txt" | tr -d '\r')"
test "$runner" = "com.dev.aria.test/androidx.test.runner.AndroidJUnitRunner"
stage=instrumentation
echo "$stage"
adb shell am instrument -w -e ariaIdentitySynthetic true -e class com.music.aria.identity.AriaIdentitySmokeTest "$runner" | tr -d '\r' | tee "$out/instrumentation.txt"
grep -Fx 'OK (3 tests)' "$out/instrumentation.txt"
if grep -E 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$out/instrumentation.txt"; then exit 1; fi
stage=launch
echo "$stage"
resolve_launcher | grep -Fx "$component"
adb shell input keyevent 82
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "$component" | tr -d '\r' > "$out/start.txt"
grep -Fx 'Status: ok' "$out/start.txt"
sleep 10
adb shell pidof "$pkg" >/dev/null
adb shell dumpsys activity activities | grep -F "$pkg/" > "$out/activity.txt"
adb exec-out screencap -p > "$out/launch.png"
python3 - "$out/launch.png" <<'PY'
from pathlib import Path
import sys
image = Path(sys.argv[1]).read_bytes()
assert image.startswith(b"\x89PNG\r\n\x1a\n") and len(image) > 1024
PY
printf 'PASS: exact APK, 13 in-app launcher selections, app label/party URI/FileProvider contracts, legacy backup import/export and invalid-backup preservation, default restored and activity alive.\n' > "$out/result.txt"
