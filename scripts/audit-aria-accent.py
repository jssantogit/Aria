#!/usr/bin/env python3
"""Inventory blue UI color candidates without changing data/brand palettes."""
import colorsys
from collections import Counter
import json
from xml.etree import ElementTree as ET
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HEX = re.compile(r"(?i)(?<!\w)(?:0x|#)([0-9a-f]{8}|[0-9a-f]{6})(?![0-9a-f])")
RGB = re.compile(r"\b(?:Color|(?:android\.graphics\.)?Color\.rgb|rgb)\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*[,)]")
# Additional literal forms used by Compose, Android graphics and SVG.
FLOAT_COMPONENT = r"(?:\d+\.\d*|\.\d+|\d+)[fF]"
NORMALIZED_RGB = re.compile(
    rf"\bColor\(\s*({FLOAT_COMPONENT})\s*,\s*({FLOAT_COMPONENT})\s*,\s*({FLOAT_COMPONENT})\s*[,)]"
)
ARGB = re.compile(r"\bColor\.argb\(\s*\d{1,3}\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*\)")
SHORT_PAINT = re.compile(r"""(?i)\b(?:[\w-]+:)?(?:fill(?:Color)?|stroke(?:Color)?|(?:text|background|tint|stop)[-]?color|color|tint)\s*=\s*['"](#(?:[0-9a-f]{4}|[0-9a-f]{3}))['"]""")
NAMED = re.compile(r"(?i)\bColor\.(?:blue|cyan)\b|@(?:android:)?color/[\w]*(?:blue|cyan)[\w]*|\b(?:fill|stroke|color)\s*=\s*['\"](?:blue|cyan)['\"]")
FILL = re.compile(r"(?:background|containerColor|checkedTrackColor|checkedBorderColor|activeTrackColor|tint|color)\s*[=(].*(?:appleMusicKeyColor|ariaAccentColor|AppleMusic(?:Dark|Light)Key|Aria(?:Dark|Light)Accent)")
INK = re.compile(r"(?:contentColor|checkedThumbColor|color|tint)\s*=\s*Color\.White\b")
# Reviewed non-accent occurrences: artwork and placeholder gradients, service
# and genre cards, and the semantic five-star rating scale.
# Exact path/color multiplicity is enforced; no wildcard exemptions.
ALLOWED_BLUE = {
    "app/src/main/java/com/music/aria/ui/replay/ReplayPoster.kt": [
        "0xFF2B5876"
    ],
    "sharedUi/src/jvmSharedMain/kotlin/com/music/aria/ui/components/CompactRatingIndicator.kt": [
        "0xFF0091FF"
    ],
    "sharedUi/src/jvmSharedMain/kotlin/com/music/aria/ui/player/MeshGradient.kt": [
        "0xFF2B5876"
    ],
    "sharedUi/src/jvmSharedMain/kotlin/com/music/aria/ui/screens/ExploreScreen.kt": [
        "0xFF8090C8",
        "0xFF6A88B0",
        "0xFF7AAED0"
    ],
    "sharedUi/src/jvmSharedMain/kotlin/com/music/aria/ui/screens/HomeScreen.kt": [
        "0xFF25304E",
        "0xFF485573",
        "0xFF374153",
        "0xFF1E3C72",
        "0xFF2A5298",
        "0xFF134E5E",
        "0xFF2C5364",
        "0xFF135960",
        "0xFF1C4279",
        "0xFF436CB6"
    ]
}
OLD_BRAND = re.compile(r"bitchord", re.I)
# Ban the removed logo and banner anywhere in runtime sources. Other legacy
# references are inventoried by symbol (not source text) for compatibility review.
RETIRED_GOOGLE_UI = re.compile(r"\b(?:ic_google_g|SignInBanner)\b")
GOOGLE_ACCOUNT_RESIDUAL = re.compile(r"\b(?:GoogleAccountSession|YouTubeProfile|BrowserSession|CapturedSession|WebSessionMode|AccountProfileSelector|AccountChannelDialog|AccountCard|activeSession|hasApiSid|sign_in_with_google|tap_to_sign_in_google|onboarding_sign_in_google|sign_in_youtube_music|library_sign_in_description|listen_together_sign_in_footer)\b|accounts\.google\.com", re.I)
def blue(rgb):
    h, s, v = colorsys.rgb_to_hsv(*(x / 255 for x in rgb))
    return 185 <= h * 360 <= 255 and s >= .25 and v >= .25
def candidates(line, suffix=".kt"):
    for m in HEX.finditer(line):
        raw = m.group(1)
        # Android XML and Kotlin use ARGB; SVG uses RGBA.
        channels = raw[:6] if suffix == ".svg" else raw[-6:]
        rgb = tuple(int(channels[i:i+2], 16) for i in (0,2,4))
        if blue(rgb):
            yield m.group()
    for m in RGB.finditer(line):
        rgb = tuple(map(int, m.groups()))
        if all(x <= 255 for x in rgb) and blue(rgb):
            yield m.group()
    for m in NORMALIZED_RGB.finditer(line):
        rgb = tuple(float(x[:-1]) for x in m.groups())
        if all(0 <= x <= 1 for x in rgb) and blue(tuple(x * 255 for x in rgb)):
            yield m.group()
    for m in ARGB.finditer(line):
        rgb = tuple(map(int, m.groups()))
        if all(x <= 255 for x in rgb) and blue(rgb):
            yield m.group()
    if suffix in {".xml", ".svg"}:
        for m in SHORT_PAINT.finditer(line):
            raw = m.group(1)[1:]
            channels = raw[:3] if suffix == ".svg" else raw[-3:]
            if blue(tuple(int(x * 2, 16) for x in channels)):
                yield m.group(1)
    for m in NAMED.finditer(line):
        yield m.group()
def self_test():
    # Exercise the exact scanner used by the inventory on every CI invocation.
    cases = [
        ("Color(0xFF0091FF)", ".kt", ["0xFF0091FF"]),
        ("Color(0xFF0091FF); Color(0xFF0091FF)", ".kt", ["0xFF0091FF", "0xFF0091FF"]),
        ("Color(0f, .5f, 1.0f)", ".kt", ["Color(0f, .5f, 1.0f)"]),
        ("Color(0f, .5f, 2f)", ".kt", []),
        ("Color.argb(128, 0, 128, 255)", ".java", ["Color.argb(128, 0, 128, 255)"]),
        ('fill="#0088ff80"', ".svg", ["#0088ff80"]),
        ('android:fillColor="#800088ff"', ".xml", ["#800088ff"]),
        ('fill="#08f8"', ".svg", ["#08f8"]),
        ('android:fillColor="#808f"', ".xml", ["#808f"]),
        ('stroke="#08f"', ".svg", ["#08f"]),
        ('href="#08f"', ".svg", []),
        ('fill="#ffb74780"', ".svg", []),
        ("Color(0xFFB74D0A)", ".kt", []),
        ("Color.Blue", ".kt", ["Color.Blue"]),
    ]
    for line, suffix, expected in cases:
        actual = list(candidates(line, suffix))
        if actual != expected:
            raise AssertionError(f"Accent scanner regression: {line}: {actual!r} != {expected!r}")
    print(f"Accent scanner self-tests: {len(cases)} passed")

def main():
    self_test()
    tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode().split("\0")
    results = []
    reviewed = 0
    for name in sorted(filter(None, tracked)):
        if not name.startswith(("app/src/", "sharedUi/src/", "shared/src/")):
            continue
        if re.search(r"/(?:test|androidTest|[^/]*Test)/", name):
            continue
        path = ROOT / name
        if not path.is_file() or path.suffix not in {".kt", ".java", ".xml", ".svg"}:
            continue
        reviewed += 1
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        if path.stem == "ic_google_g":
            results.append({"path":name,"line":0,"kind":"retired-google-ui","value":"ic_google_g"})
        # Inspect displayed resource values, not persisted resource identifiers.
        # This covers translations, arrays and plurals across all runtime flavors.
        if path.suffix == ".xml" and path.parent.name.startswith("values"):
            root = ET.parse(path).getroot()
            for node in root:
                if node.tag not in {"string", "string-array", "plurals"}:
                    continue
                if OLD_BRAND.search("".join(node.itertext())):
                    results.append({"path":name,"line":0,"kind":"old-brand-resource-value","value":node.get("name","?")})
        for index, line in enumerate(lines):
            if path.suffix in {".kt", ".java"}:
                for match in RETIRED_GOOGLE_UI.finditer(line):
                    results.append({"path":name,"line":index+1,"kind":"retired-google-ui","value":match.group()})
                for match in GOOGLE_ACCOUNT_RESIDUAL.finditer(line):
                    results.append({"path":name,"line":index+1,"kind":"legacy-account-inventory","value":match.group()})
            for value in sorted(candidates(line, path.suffix)):
                results.append({"path":name,"line":index+1,"kind":"literal-blue","value":value})
            if FILL.search(line):
                for delta, nearby in enumerate(lines[index:index+20]):
                    if INK.search(nearby):
                        results.append({"path":name,"line":index+1,"kind":"accent-and-nearby-white","value":f"white at {index+delta+1}"})
                        break
    print(f"Reviewed {reviewed} tracked source/resource files; {len(results)} candidates")
    for result in results:
        print(f'{result["path"]}:{result["line"]} [{result["kind"]}] {result["value"]}')
    output = ROOT / "accent-audit.json"
    output.write_text(json.dumps({"files":reviewed,"candidates":results},indent=2)+"\n")
    permitted = Counter((name, value.lower()) for name, values in ALLOWED_BLUE.items() for value in values)
    observed = Counter((r["path"], r["value"].lower()) for r in results if r["kind"] == "literal-blue")
    unexpected = observed - permitted
    old_resources = [r for r in results if r["kind"] == "old-brand-resource-value"]
    print(f"Approved non-accent blue occurrences: {sum((observed & permitted).values())}")
    retired_ui = [r for r in results if r["kind"] == "retired-google-ui"]
    print(f"Retired Google logo/banner references: {len(retired_ui)}")
    print(f"Legacy account inventory: {sum(r['kind'] == 'legacy-account-inventory' for r in results)}")
    if unexpected or old_resources or retired_ui:
        for (name, value), count in unexpected.items():
            print(f"REVIEW REQUIRED: {name}: {value} ({count})")
        raise SystemExit("Unreviewed blue or retired user-facing brand resource")
    print("Reviewed blue and visible resource identity gate passed; contrast candidates still require review.")
if __name__ == "__main__":
    main()
