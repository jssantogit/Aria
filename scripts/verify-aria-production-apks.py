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
from xml.etree import ElementTree as ET

ABIS = {"armeabi-v7a", "arm64-v8a", "x86_64"}
LIBS = {"libaria_analysis.so", "libaria_dsd.so", "libaria_dst.so"}
LEGACY = {"libbitchord_analysis.so", "libbitchord_dsd.so", "libbitchord_dst.so"}
PACKAGE = "com.music.aria"
ANDROID = "{http://schemas.android.com/apk/res/android}"
DEFAULT = PACKAGE + ".LauncherDefault"
ALIASES = {PACKAGE + ".LauncherDefault"} | {
    PACKAGE + ".Launcher" + family + suffix
    for family in ("Glass", "SamsungGlass", "PixelGlass")
    for suffix in ("Light", "Dark", "Clear", "ClearDark")
    if family == "Glass" or suffix != "Light"
} | {PACKAGE + ".LauncherSamsungGlass", PACKAGE + ".LauncherPixelGlass"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def fingerprint(value):
    value = value.strip().replace(":", "").lower()
    require(re.fullmatch(r"[0-9a-f]{64}", value), "Expected a SHA-256 certificate fingerprint")
    return value


def sdk_tool(name):
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ["ANDROID_HOME"])
    if name == "apkanalyzer":
        latest = sdk / "cmdline-tools/latest/bin/apkanalyzer"
        if latest.is_file():
            return latest
        available = sorted((sdk / "cmdline-tools").glob("*/bin/apkanalyzer"))
        if available:
            return available[-1]
        executable = shutil.which(name)
        require(executable, "Missing Android SDK command-line tool apkanalyzer")
        return Path(executable)
    candidates = [p for p in (sdk / "build-tools").glob("*/" + name)
                  if p.is_file() and re.fullmatch(r"\d+\.\d+\.\d+", p.parent.name)]
    require(candidates, f"Missing Android SDK tool {name}")
    return max(candidates, key=lambda p: tuple(map(int, p.parent.name.split("."))))


def run(tool, *args):
    result = subprocess.run([str(tool), *map(str, args)], check=True,
                            capture_output=True, text=True)
    return result.stdout


def verify_manifest(manifest_text):
    root = ET.fromstring(manifest_text)
    require(root.get("package") == PACKAGE, "Compiled manifest has wrong package")
    app = root.find("application")
    require(app is not None, "Compiled manifest has no application")

    def attr(node, name, default=None):
        return node.get(ANDROID + name, default)

    def qualified(name):
        if name.startswith("."):
            return PACKAGE + name
        return name if "." in name else PACKAGE + "." + name

    def launcher(node):
        for intent in node.findall("intent-filter"):
            actions = {attr(child, "name") for child in intent.findall("action")}
            categories = {attr(child, "name") for child in intent.findall("category")}
            if "android.intent.action.MAIN" in actions and "android.intent.category.LAUNCHER" in categories:
                return True
        return False

    require(qualified(attr(app, "name", "")) == PACKAGE + ".AriaApplication", "Wrong application class")
    require(attr(app, "debuggable", "false") == "false", "Debuggable manifest")
    require(attr(app, "enabled", "true") == "true", "Application disabled")
    aliases = app.findall("activity-alias")
    launchers = [node for node in aliases if launcher(node)]
    names = [qualified(attr(node, "name", "")) for node in launchers]
    require(len(names) == len(ALIASES) and set(names) == ALIASES,
            "Unexpected launcher alias inventory: " + ", ".join(names))
    require(not any(launcher(node) for node in app.findall("activity")), "Unexpected direct activity launcher")
    for node, name in zip(launchers, names):
        require(attr(node, "exported") == "true", f"{name}: not exported")
        require(qualified(attr(node, "targetActivity", "")) == PACKAGE + ".MainActivity",
                f"{name}: incorrect target")
        enabled = "true" if name == DEFAULT else "false"
        require(attr(node, "enabled", "true") == enabled, f"{name}: wrong enabled state")
        require(attr(node, "icon"), f"{name}: missing icon")
    require(any(qualified(attr(node, "name", "")) == PACKAGE + ".MainActivity"
                and attr(node, "enabled", "true") == "true" for node in app.findall("activity")),
            "MainActivity absent or disabled")
    return sorted(names)


def verify_signers(output, expected, apk_name):
    # apksigner versions report either numbered signers, SDK-ranged signers
    # or signature-scheme-qualified signers. Accept only the approved public
    # fingerprint across every reported signer, never merely one matching row.
    lines = [line.strip() for line in output.splitlines()]
    counts = [line.split(":", 1)[1].strip() for line in lines
              if line.startswith("Number of signers:")]
    require(counts == ["1"], f"{apk_name}: expected exactly one signer; reported {counts}")
    certs = []
    for line in lines:
        if "certificate SHA-256 digest:" not in line or "Signer" not in line:
            continue
        match = re.fullmatch(
            r"(?:V[123](?:\.\d+)? )?Signer(?: #\d+| \(.*\))?:?[ \t]+certificate SHA-256 digest:[ \t]*([0-9a-fA-F:]+)",
            line, re.I)
        require(match is not None, f"{apk_name}: unrecognized signer digest: {line}")
        certs.append(fingerprint(match.group(1)))
    require(certs, f"{apk_name}: no signer certificate SHA-256 digest reported")
    print(f"{apk_name}: expected certificate={expected}; observed={sorted(set(certs))}", flush=True)
    require(set(certs) == {expected}, f"{apk_name}: unexpected signing certificate")


def inspect(apk, expected, aapt, apksigner, apkanalyzer):
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
    # badging can report the target activity rather than the launcher alias.
    # Inspect the actual compiled manifest, never the source manifest.
    print(f"{apk.name}: badging launchable activities: " +
          repr([line for line in badging.splitlines() if line.startswith("launchable-activity:")]), flush=True)
    aliases = verify_manifest(run(apkanalyzer, "manifest", "print", apk))

    signed = run(apksigner, "verify", "--verbose", "--print-certs", "--min-sdk-version", "26", apk)
    print(f"{apk.name}: signature verification report:\n{signed}", flush=True)
    verify_signers(signed, expected, apk.name)

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
            "versionCode": int(code), "sha256": digest, "certificateSha256": expected,
            "defaultLauncher": DEFAULT, "launcherAliases": aliases}


def verify(input_dir, expected, report, output=None):
    expected = fingerprint(expected)
    apks = sorted(input_dir.glob("*.apk"))
    require(len(apks) == 4, "Expected exactly one universal and three ABI APKs")
    aapt, apksigner = sdk_tool("aapt"), sdk_tool("apksigner")
    apkanalyzer = sdk_tool("apkanalyzer")
    results = [inspect(apk, expected, aapt, apksigner, apkanalyzer) for apk in apks]
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
