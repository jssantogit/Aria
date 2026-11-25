#!/usr/bin/env bash
# Clean emulator only; no account setup, media imports, or destructive host actions.
set -euo pipefail
apk_dir="${1:?APK directory required}"
out="${2:?evidence directory required}"
mkdir -p "$out"
mapfile -t apks < <(find "$apk_dir" -type f -name '*x86_64*.apk' | sort)
test "${#apks[@]}" -eq 1
apk="${apks[0]}"
pkg=com.dev.aria
component="$pkg/com.music.aria.LauncherDefault"
adb wait-for-device
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1
# No account credentials are entered on this fresh CI emulator.
adb shell svc wifi disable
adb shell svc data disable
adb install "$apk"
adb shell pm path "$pkg" | grep -q '^package:'
resolved="$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$pkg" | tr -d '\r')"
printf '%s\n' "$resolved" | grep -Fx "$component"
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
printf 'PASS: exact x86_64 APK installed, default launcher resolved, activity started and process alive after 10s.\n' > "$out/result.txt"
