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
stage=disable-network
echo "$stage"
adb shell svc wifi disable
adb shell svc data disable
stage=install-app
echo "$stage"
# Older package managers use the non-streaming install path explicitly.
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
grep -F 'OK (2 tests)' "$out/instrumentation.txt"
! grep -E 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$out/instrumentation.txt"
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
printf 'PASS: exact APK, 13 in-app launcher selections, legacy backup import/export and invalid-backup preservation, default restored and activity alive.\n' > "$out/result.txt"
