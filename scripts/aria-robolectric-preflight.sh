#!/usr/bin/env bash
# Retry only known transient framework-download failures, never failed assertions.
set -euo pipefail
for attempt in 1 2 3; do
    log="$(mktemp)"
    if "$@" 2>&1 | tee "$log"; then
        rm -f "$log"
        exit 0
    fi
    if [[ "$attempt" == 3 ]] || ! grep -Fq 'Failed to fetch maven artifact org.robolectric:android-all-instrumented:' "$log" || ! grep -Eq 'HTTP response code: (429|502|503|504)([^0-9]|$)' "$log"; then
        rm -f "$log"
        exit 1
    fi
    rm -f "$log"
    delay=$((attempt * 45))
    echo "Transient Robolectric framework download failure; retry $((attempt + 1))/3 in ${delay}s."
    sleep "$delay"
done
exit 1
