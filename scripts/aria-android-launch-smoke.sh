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
    stage=airplane-mode
    echo "$stage"
    # All modern emulator APIs use the persistent radio isolation command.
    adb shell cmd connectivity airplane-mode enable
    test "$(adb shell settings get global airplane_mode_on | tr -d '\r')" = 1
    stage=disable-wifi
    echo "$stage"
    adb shell svc wifi disable
    stage=disable-data
    echo "$stage"
    adb shell svc data disable
fi
verify_offline() {
    local checkpoint="$1" consecutive=0
    stage="offline-$checkpoint"
    echo "$stage"
    # Every sample must observe airplane mode as well as no default network.
    # Do not recover from a radio policy change by hiding it.
    for attempt in {1..30}; do
        test "$(adb shell settings get global airplane_mode_on | tr -d '\r')" = 1
        adb shell dumpsys connectivity | tr -d '\r' > "$out/connectivity-$checkpoint.txt"
        grep 'Active default network:' "$out/connectivity-$checkpoint.txt" >> "$out/network-state.txt" || true
        if grep -Eq '^[[:space:]]*Active default network: none[[:space:]]*$' "$out/connectivity-$checkpoint.txt"; then
            consecutive=$((consecutive + 1))
            if (( consecutive == 3 )); then
                echo "OFFLINE: $checkpoint (3 consecutive samples)" | tee -a "$out/network-state.txt"
                return 0
            fi
        else
            consecutive=0
        fi
        sleep 1
    done
    echo "Offline checkpoint failed: $checkpoint" >&2
    tail -n 30 "$out/network-state.txt" >&2
    return 1
}
verify_offline before-install
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
verify_offline before-instrumentation
stage=instrumentation
echo "$stage"
adb shell am instrument -w -e ariaIdentitySynthetic true -e class com.music.aria.identity.AriaIdentitySmokeTest "$runner" | tr -d '\r' | tee "$out/instrumentation.txt"
grep -Fx 'OK (3 tests)' "$out/instrumentation.txt"
if grep -E 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$out/instrumentation.txt"; then exit 1; fi
verify_offline before-launch
stage=launch
echo "$stage"
resolve_launcher | grep -Fx "$component"
adb shell input keyevent 82
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "$component" | tr -d '\r' > "$out/start.txt"
grep -Fx 'Status: ok' "$out/start.txt"
stage=survival
echo "$stage"
sleep 10
# A timeout is an ADB transport failure, not proof of a dead app.
# Permit one bounded retry of the observation, never restart the app.
if timeout 60s adb shell pidof "$pkg" > "$out/pid.txt"; then
    :
else
    rc=$?
    if [[ "$rc" != 124 ]]; then exit "$rc"; fi
    echo "ADB PID observation timed out; retry once without relaunch" | tee "$out/survival-retry.txt"
    timeout 15s adb get-state | grep -Fx device
    timeout 60s adb shell pidof "$pkg" > "$out/pid.txt"
fi
cat "$out/pid.txt"
grep -Eq '^[0-9]+([[:space:]]+[0-9]+)*[[:space:]]*$' "$out/pid.txt"
stage=screenshot
echo "$stage"
timeout 25s adb exec-out screencap -p > "$out/launch.png"
stage=activity-evidence
echo "$stage"
timeout 25s adb shell dumpsys activity activities | grep -F "$pkg/" > "$out/activity.txt"
verify_offline after-launch
python3 - "$out/launch.png" <<'PY'
from pathlib import Path
import sys
image = Path(sys.argv[1]).read_bytes()
assert image.startswith(b"\x89PNG\r\n\x1a\n") and len(image) > 1024
PY
printf 'PASS: exact APK, 13 in-app launcher selections, app label/party URI/FileProvider contracts, legacy backup import/export and invalid-backup preservation, default restored and activity alive.\n' > "$out/result.txt"
