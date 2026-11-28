#!/usr/bin/env bash
set -euo pipefail
umask 077
# Do not enable xtrace. Credentials never become Gradle properties, APK
# resources, instrumentation arguments, uploaded files or command-line values.
if [[ -z "${SLSK_FIXTURES:-}" ]]; then
  echo 'BLOCKED: configure SOULSEEK_LIVE_FIXTURES with authorized hash-pinned audio.'
  exit 1
fi
if [[ -z "${SLSK_USER:-}" || -z "${SLSK_PASS:-}" ]]; then
  echo 'Soulseek CI credentials missing (configure the workflow secret aliases).'
  exit 1
fi
source scripts/acquisition_live_install.sh
aria_install_live_apks
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
printf '%s' "$SLSK_FIXTURES" | adb exec-in "run-as com.dev.aria sh -c 'cat > files/aria-live-fixtures'"
unset SLSK_USER SLSK_PASS SLSK_FIXTURES
# Do not expose raw instrumentation output or logcat. Only typed counters
# from the focused test are retained and published.
set +e
adb shell am instrument -w -r -e class com.music.aria.acquisition.live.SoulseekLiveAcquisitionTest com.dev.aria.test/androidx.test.runner.AndroidJUnitRunner >"$raw" 2>&1
rc=$?
set -e
printf 'group\trequested\tsaved\telapsed_ms\n' > acquisition-live-acquisition.tsv
awk -F= '/^INSTRUMENTATION_STATUS: aria_live=/{sub(/\r$/, "", $2); if ($2 ~ /^[A-Za-z0-9_\t-]+$/ && split($2,a,"\t")==4) print $2}' "$raw" >> acquisition-live-acquisition.tsv
cat acquisition-live-acquisition.tsv
if [[ "$rc" != 0 ]] || ! grep -q 'OK (1 test)' "$raw" || [[ "$(wc -l < acquisition-live-acquisition.tsv)" -lt 2 ]]; then
  echo 'Live acquisition incomplete or failed; inspect aggregate report only.'
  exit 1
fi
