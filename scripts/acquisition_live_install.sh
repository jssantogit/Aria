#!/usr/bin/env bash
# Source only on an already booted Android emulator. Choose the APK declared
# for that ABI by AGP; universal is a fallback, never an arbitrary split.
aria_install_live_apks() {
  local abi app_apk test_apk
  abi="$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  readarray -t apks < <(python3 - "$abi" <<'PY'
import json, pathlib, sys
abi = sys.argv[1]
def resolve(directory, expected_id):
    root = pathlib.Path(directory)
    meta = json.loads((root / "output-metadata.json").read_text())
    if meta.get("applicationId") != expected_id:
        raise SystemExit("Unexpected APK application ID")
    exact, universal = [], []
    for e in meta["elements"]:
        filters = e.get("filters", [])
        if not filters:
            universal.append(e)
        elif len(filters) == 1 and filters[0].get("filterType") == "ABI" and filters[0].get("value") == abi:
            exact.append(e)
    candidates = exact or universal
    if len(candidates) != 1:
        raise SystemExit("Missing or ambiguous compatible APK")
    path = root / candidates[0]["outputFile"]
    if not path.is_file():
        raise SystemExit("APK declared by AGP missing")
    return str(path)
print(resolve("app/build/outputs/apk/dev/debug", "com.dev.aria"))
print(resolve("app/build/outputs/apk/androidTest/dev/debug", "com.dev.aria.test"))
PY
)
  if [[ "${#apks[@]}" != 2 ]]; then
    echo 'APK resolution failed using AGP output metadata.'
    return 1
  fi
  app_apk="${apks[0]}"
  test_apk="${apks[1]}"
  adb install -r "$app_apk" >/dev/null
  adb install -r "$test_apk" >/dev/null
  echo 'Compatible app and instrumentation APKs installed.'
}
