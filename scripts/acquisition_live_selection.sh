#!/usr/bin/env bash
set -euo pipefail
umask 077
# Do not enable xtrace. Credentials never become Gradle properties, APK
# resources, instrumentation arguments, uploaded files or command-line values.
if [[ -z "${SLSK_USER:-}" || -z "${SLSK_PASS:-}" ]]; then
  echo 'Soulseek CI credentials missing (configure the workflow secret aliases).'
  exit 1
fi
cases="${ARIA_LIVE_CASES:-}"
corpus="${ARIA_LIVE_CORPUS:-kpop1}"
names="${ARIA_LIVE_NAMES:-false}"
[[ "$corpus" == kpop1 || "$corpus" == mixed2 || "$corpus" == mixed3 ]] || exit 1
[[ "$names" == true || "$names" == false ]] || exit 1
if [[ -n "$cases" && ! "$cases" =~ ^[a-z0-9_]+(,[a-z0-9_]+)*$ ]]; then
  echo 'Invalid corpus case IDs.'
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
unset SLSK_USER SLSK_PASS
# Do not expose raw instrumentation output or logcat. Only typed counters
# from the focused test are retained and published.
set +e
adb shell am instrument -w -r -e aria_live_corpus "$corpus" -e aria_live_names "$names" -e aria_live_cases "${cases:-all}" -e class com.music.aria.acquisition.live.SoulseekLiveSelectionTest com.dev.aria.test/androidx.test.runner.AndroidJUnitRunner >"$raw" 2>&1
rc=$?
set -e
printf 'case\tsearch\tdecision\treason\tobserved\teligible\trank_rejected\tpreflight_rejected\talbum_rejected\tquality_rejected\ttruncated\telapsed_ms\trank_detail\tpreflight_detail\talbum_detail\tquality_detail\tartist_credit_gaps\tmissing_title\tmissing_artist\ttie_resolution\ttie_groups\n' > acquisition-live-report.tsv
awk -F= '/^INSTRUMENTATION_STATUS: aria_live=/{sub(/\r$/, "", $2); if ($2 ~ /^[A-Za-z0-9_\t,:-]+$/ && split($2,a,"\t")==21) print $2}' "$raw" >> acquisition-live-report.tsv
cat acquisition-live-report.tsv
printf 'case\tinitial_kind\tfallback_1_kind\tfallback_2_kind\ttickets\tbudget_skipped\tinitial_replies\tfallback_1_replies\tfallback_2_replies\tinitial_files\tfallback_1_files\tfallback_2_files\treachability\tdirect_accepted\tindirect_offers\tindirect_connected\traw_responses\tshadow_title_filtered\tadmitted\tunsupported_format\ttruncated_slots\n' > acquisition-live-search.tsv
awk -F= '/^INSTRUMENTATION_STATUS: aria_search=/{sub(/\r$/, "", $2); if ($2 ~ /^[A-Za-z0-9_\t-]+$/ && split($2,a,"\t")==21) print $2}' "$raw" >> acquisition-live-search.tsv
cat acquisition-live-search.tsv
# Only the structured candidate payload is exported; never raw logcat.
python3 - "$raw" "$names" <<'PY'
import base64, json, sys
from pathlib import Path
source, names = sys.argv[1:]
fields = {"case","candidate","selected","stage","reason","score","match","warnings","preflight","album","quality","availability","format","duration","bitrate","sample_rate","bit_depth","tie_resolution"}
with open("acquisition-live-candidates.jsonl","w",encoding="utf-8") as out:
    for line in Path(source).read_text(errors="replace").splitlines():
        prefix="INSTRUMENTATION_STATUS: aria_candidate="
        if not line.startswith(prefix): continue
        obj=json.loads(base64.b64decode(line[len(prefix):],validate=True))
        if set(obj) != fields | ({"filename"} if names=="true" else set()):
            raise SystemExit("Unexpected candidate report schema")
        if "filename" in obj and ("/" in obj["filename"] or chr(92) in obj["filename"]):
            raise SystemExit("Candidate filename contains a path")
        out.write(json.dumps(obj,ensure_ascii=False)+"\n")
PY
# Check identity set, not merely row count. Duplicate/missing rows fail closed.
if [[ "$rc" != 0 ]] || ! grep -q 'OK (1 test)' "$raw" || ! python3 - "$cases" <<'PY'
import csv, sys
with open("acquisition-live-report.tsv", newline="") as f:
    ids = [row["case"] for row in csv.DictReader(f, delimiter="\t")]
requested = sys.argv[1].split(",") if sys.argv[1] else None
with open("acquisition-live-search.tsv", newline="") as f:
    search_ids = [row["case"] for row in csv.DictReader(f, delimiter="\t")]
assert search_ids == ids, "Missing or misordered discovery audit"
assert len(ids) == len(set(ids)), "Duplicate report rows"
assert (set(ids) == set(requested) if requested else len(ids) == 30), "Incomplete corpus"
PY
then
  echo 'Live calibration incomplete or failed; inspect aggregate report only.'
  exit 1
fi
