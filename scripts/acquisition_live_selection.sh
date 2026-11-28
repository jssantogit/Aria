#!/usr/bin/env bash
set -euo pipefail
umask 077
# Do not enable xtrace. Credentials never become Gradle properties, APK
# resources, instrumentation arguments, uploaded files or command-line values.
if [[ -z "${SLSK_USER:-}" || -z "${SLSK_PASS:-}" ]]; then
  echo 'Soulseek CI credentials missing (configure the workflow secret aliases).'
  exit 1
fi
app_apk=app/build/outputs/apk/dev/debug/app-dev-debug.apk
test_apk=app/build/outputs/apk/androidTest/dev/debug/app-dev-debug-androidTest.apk
adb install -r "$app_apk" >/dev/null
adb install -r "$test_apk" >/dev/null
raw="$(mktemp)"
cleanup() {
  adb shell run-as com.dev.aria rm -f files/aria-live-credentials >/dev/null 2>&1 || true
  adb shell pm clear com.dev.aria >/dev/null 2>&1 || true
  rm -f "$raw"
}
trap cleanup EXIT
adb shell run-as com.dev.aria mkdir -p files
python3 -c 'import os,sys; sys.stdout.buffer.write(os.environ["SLSK_USER"].encode()+b"\0"+os.environ["SLSK_PASS"].encode())' |
  adb exec-in "run-as com.dev.aria sh -c 'cat > files/aria-live-credentials'"
unset SLSK_USER SLSK_PASS
# Do not expose raw instrumentation output or logcat. Only typed counters
# from the focused test are retained and published.
set +e
adb shell am instrument -w -r -e class com.music.aria.acquisition.live.SoulseekLiveSelectionTest com.dev.aria.test/androidx.test.runner.AndroidJUnitRunner >"$raw" 2>&1
rc=$?
set -e
printf 'case\tsearch\tdecision\treason\tobserved\teligible\trank_rejected\tpreflight_rejected\talbum_rejected\tquality_rejected\ttruncated\telapsed_ms\n' > acquisition-live-report.tsv
awk -F= '/^INSTRUMENTATION_STATUS: aria_live=/{sub(/\r$/, "", $2); if ($2 ~ /^[A-Za-z0-9_\t-]+$/ && split($2,a,"\t")==12) print $2}' "$raw" >> acquisition-live-report.tsv
cat acquisition-live-report.tsv
if [[ "$rc" != 0 ]] || ! grep -q 'OK (1 test)' "$raw" || [[ "$(wc -l < acquisition-live-report.tsv)" != 6 ]]; then
  echo 'Live calibration incomplete or failed; inspect aggregate report only.'
  exit 1
fi
