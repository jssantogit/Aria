#!/usr/bin/env bash
set -euo pipefail
umask 077
# One authenticated account: serialize network sessions while reporting each
# frozen corpus independently. Never let a failed corpus skip the next one.
root=acquisition-live-results
mkdir -p "$root"
printf 'corpus\texit_code\n' > "$root/run-status.tsv"
failed=0
run_corpus() {
  local corpus="$1" cases="$2" rc=0 f
  rm -f acquisition-live-report.tsv acquisition-live-search.tsv acquisition-live-candidates.jsonl
  mkdir -p "$root/$corpus"
  echo "=== Frozen corpus: $corpus ==="
  ARIA_LIVE_CORPUS="$corpus" ARIA_LIVE_CASES="$cases" bash scripts/acquisition_live_selection.sh || rc=$?
  for f in acquisition-live-report.tsv acquisition-live-search.tsv acquisition-live-candidates.jsonl; do
    if [[ -f "$f" ]]; then mv "$f" "$root/$corpus/$f"; fi
  done
  printf '%s\t%s\n' "$corpus" "$rc" >> "$root/run-status.tsv"
  if (( rc != 0 )); then failed=1; fi
}
case "${ARIA_LIVE_CORPUS:-kpop1}" in
  pending123)
    run_corpus kpop1 lesserafim_antifragile,seventeen_very_nice
    run_corpus mixed2 mj_bad,exo_love_shot,exo_growl,gaga_bad_romance
    run_corpus mixed3 taeyeon_i,whitney_i_will_always_love_you,prince_purple_rain,beyonce_halo,rihanna_umbrella,adele_hello
    ;;
  batch123)
    run_corpus kpop1 lesserafim_antifragile,straykids_gods_menu,seventeen_very_nice
    run_corpus mixed2 ''
    run_corpus mixed3 ''
    ;;
  kpop1|mixed2|mixed3)
    run_corpus "$ARIA_LIVE_CORPUS" "${ARIA_LIVE_CASES:-}"
    ;;
  *) echo 'Unknown frozen corpus'; exit 1 ;;
esac
cat "$root/run-status.tsv"
exit "$failed"
