#!/usr/bin/env python3
"""Fail closed on production APK package, ABI inventory, version and signing identity."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from zipfile import ZipFile

ABIS = {"armeabi-v7a", "arm64-v8a", "x86_64"}
LIBS = {"libaria_analysis.so", "libaria_dsd.so", "libaria_dst.so"}
LEGACY = {"libbitchord_analysis.so", "libbitchord_dsd.so", "libbitchord_dst.so"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def fingerprint(value):
    value = value.strip().replace(":", "").lower()
    require(re.fullmatch(r"[0-9a-f]{64}", value), "Expected a SHA-256 certificate fingerprint")
    return value


def sdk_tool(name):
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ["ANDROID_HOME"])
    candidates = [p for p in (sdk / "build-tools").glob("*/" + name)
                  if p.is_file() and re.fullmatch(r"\d+\.\d+\.\d+", p.parent.name)]
    require(candidates, f"Missing Android SDK tool {name}")
    return max(candidates, key=lambda p: tuple(map(int, p.parent.name.split("."))))


def run(tool, *args):
    result = subprocess.run([str(tool), *map(str, args)], check=True,
                            capture_output=True, text=True)
    return result.stdout


def inspect(apk, expected, aapt, apksigner):
    badging = run(aapt, "dump", "badging", apk)
    package_line = next((line for line in badging.splitlines() if line.startswith("package:")), "")
    fields = dict(re.findall(r"(\w+)='([^']*)'", package_line))
    require(fields.get("name") == "com.music.aria", f"{apk.name}: wrong installed package")
    version = fields.get("versionName", "")
    code = fields.get("versionCode", "")
    require(re.fullmatch(r"[0-9]+(?:\.[0-9]+)+(?:-[A-Za-z0-9.-]+)?", version), "Unsafe version name")
    require(code.isdecimal() and int(code) > 0, "Invalid version code")
    require("application-label:'Aria'" in badging.splitlines(), f"{apk.name}: wrong label")
    require("application-debuggable" not in badging.splitlines(), f"{apk.name}: debuggable release")
    require("sdkVersion:'26'" in badging.splitlines() and "targetSdkVersion:'36'" in badging.splitlines(),
            f"{apk.name}: SDK contract changed")
    require(re.search(r"^launchable-activity: name='com\.music\.aria\.LauncherDefault'", badging, re.M),
            f"{apk.name}: wrong default launcher")

    signed = run(apksigner, "verify", "--print-certs", "--min-sdk-version", "26", apk)
    certs = re.findall(r"^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F:]+)\s*$", signed, re.M)
    require(len(certs) == 1 and fingerprint(certs[0]) == expected, f"{apk.name}: unexpected signing certificate")

    libraries = {}
    with ZipFile(apk) as archive:
        for name in archive.namelist():
            parts = name.split("/")
            if len(parts) == 3 and parts[0] == "lib" and parts[2].endswith(".so"):
                libraries.setdefault(parts[1], set()).add(parts[2])
    abis = set(libraries)
    require(abis == ABIS or (len(abis) == 1 and abis <= ABIS), f"{apk.name}: unexpected ABIs {abis}")
    for abi, names in libraries.items():
        require(LIBS <= names and not LEGACY & names, f"{apk.name}/{abi}: wrong native libraries")
    kind = "universal" if abis == ABIS else next(iter(abis))
    name = f"Aria-v{version}" + ("" if kind == "universal" else f"_{kind}") + ".apk"
    with apk.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    return {"source": apk.name, "name": name, "abi": kind, "version": version,
            "versionCode": int(code), "sha256": digest, "certificateSha256": expected}


def verify(input_dir, expected, report, output=None):
    expected = fingerprint(expected)
    apks = sorted(input_dir.glob("*.apk"))
    require(len(apks) == 4, "Expected exactly one universal and three ABI APKs")
    aapt, apksigner = sdk_tool("aapt"), sdk_tool("apksigner")
    results = [inspect(apk, expected, aapt, apksigner) for apk in apks]
    require({r["abi"] for r in results} == ABIS | {"universal"}, "Duplicate or missing ABI APK")
    require(len({(r["version"], r["versionCode"]) for r in results}) == 1, "Inconsistent APK versions")
    if output is not None:
        output.mkdir(parents=True, exist_ok=False)
        for item in results:
            shutil.copyfile(input_dir / item["source"], output / item["name"])
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps({"package": "com.music.aria", "apks": results}, indent=2) + "\n")
    print(f"Verified four non-debuggable Aria production APKs: {report}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("report", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    verify(args.input, os.environ.get("ARIA_EXPECTED_CERT_SHA256", ""), args.report, args.output)
