#!/usr/bin/env python3
"""Structural regression gate for Android-only scope and the approved launcher mark."""
from pathlib import Path
from xml.etree import ElementTree as ET
import re

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
A = "{http://schemas.android.com/apk/res/android}"

def require(condition, message):
    if not condition:
        raise SystemExit(message)

def xml(path):
    return ET.parse(path).getroot()

def marks(path):
    return {p.attrib[A + "pathData"] for p in xml(path).iter("path")
            if A + "pathData" in p.attrib}

for target in ("desktopApp", "desktopUi", "tv", "wearos"):
    require(not (ROOT / target).exists(), f"Unsupported platform returned: {target}")
settings = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
require(set(re.findall(r'include\("(:[^"]+)"\)', settings)) == {":app", ":shared", ":sharedUi"},
        "Unexpected product module in Gradle settings")
gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
require(set(re.findall(r'applicationId\s*=\s*"([^"]+)"', gradle)) == {"com.music.aria", "com.dev.aria"},
        "Aria installed package identity changed")
for dependency in ("play-services-wearable", "wear-remote-interactions", "libadb-android", "../wearos/protocol"):
    require(dependency not in gradle, f"Retired watch dependency returned: {dependency}")
for workflow in ("desktop-removal-gate.yml", "tv-wear-removal-gate.yml"):
    require(not (ROOT / ".github/workflows" / workflow).exists(), f"Retired CI gate returned: {workflow}")

approved = marks(RES / "drawable/aria_icon_foreground.xml")
require(len(approved) == 2, "Notation mark must retain its two approved paths")
manifest = xml(ROOT / "app/src/main/AndroidManifest.xml")
aliases = manifest.findall("./application/activity-alias")
require(len(aliases) == 13, "Launcher alias inventory changed; review all icon choices")
icons = {alias.attrib[A + "icon"].removeprefix("@mipmap/") for alias in aliases}
require(len(icons) == 13, "Launcher aliases must remain distinct")
for icon in icons:
    spec = xml(RES / "mipmap-anydpi" / (icon + ".xml"))
    require(spec.tag == "adaptive-icon", f"Not adaptive: {icon}")
    for layer in ("foreground", "monochrome"):
        resource = spec.find(layer)
        require(resource is not None, f"Missing {layer}: {icon}")
        kind, name = resource.attrib[A + "drawable"].removeprefix("@").split("/")
        folder = "mipmap-anydpi" if kind == "mipmap" else kind
        require(approved <= marks(RES / folder / (name + ".xml")),
                f"Notation mark missing from {icon} {layer}")

for path in (RES / "drawable/ic_logo.xml", RES / "drawable/ic_notification_logo.xml",
             ROOT / "sharedUi/src/commonMain/composeResources/drawable/ic_logo.xml"):
    require(approved <= marks(path), f"Old logo returned: {path.relative_to(ROOT)}")
for density in ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"):
    for path in (RES / ("mipmap-" + density)).glob("ic_*"):
        require(not re.fullmatch(r"ic_(launcher(?:_round)?|(?:liquid|samsung|pixel)_glass.*_foreground|glass.*)\.(png|webp)", path.name),
                f"Legacy raster launcher mark returned: {path.relative_to(ROOT)}")
require("WearCommandService" not in (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8"),
        "Retired Wear command listener returned")
print("Android-only scope, independent installed IDs, 13 adaptive Notation aliases and UI marks verified.")


# Playback must not regain a transport for an unsupported platform.
playback = ROOT / "app/src/main/java/com/music/bitchord/playback/PlaybackService.kt"
# Accept the same source path after the planned namespace migration.
if not playback.exists():
    playback = ROOT / "app/src/main/java/com/music/aria/playback/PlaybackService.kt"
require(not re.search(r"\b(?:WearBridge|watchSpeaker|WATCH_SESSION_FOREGROUND_MS)\b",
                      playback.read_text(encoding="utf-8")),
        "Retired Wear playback hooks returned")

# Transition inventory, not a namespace acceptance gate. Keep compatibility
# strings and historical attribution visible for individual review rather than
# blindly replacing every occurrence. Only tracked source/config paths and
# line numbers are printed; never dump source lines or local configuration.
import subprocess
from collections import Counter

tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode("utf-8").split("\0")
namespace = re.compile(r"com[./_]music[./_]bitchord|com[./_]dev[./_]bitchord")
wear = re.compile(r"\b(?:WearBridge|WatchInstaller|WatchOutputState|WatchEntry|WatchUpdateState|WatchPairingState|WearCommandService|WatchSpeaker)\b")
text_suffixes = {".kt", ".kts", ".java", ".cpp", ".c", ".h", ".hpp", ".xml", ".pro",
                 ".properties", ".py", ".sh", ".yml", ".yaml", ".json", ".gradle", ".cmake"}
counts = Counter()
wear_hits = []
namespace_non_sources = []
for name in sorted(filter(None, tracked)):
    source = ROOT / name
    if source == Path(__file__).resolve() or not source.is_file() or source.is_symlink():
        continue
    if namespace.search(name):
        counts["legacy package paths"] += 1
    if source.suffix not in text_suffixes and source.name != "CMakeLists.txt":
        continue
    try:
        lines = source.read_text(encoding="utf-8").splitlines()
    except UnicodeDecodeError:
        counts["non-UTF8 tracked text candidates"] += 1
        continue
    old_lines = [i for i, line in enumerate(lines, 1) if namespace.search(line)]
    if old_lines:
        counts[f"namespace references: {name.split('/')[0]} {source.suffix or source.name}"] += 1
        if source.suffix not in {".kt", ".java"}:
            namespace_non_sources.append(f"{name}:{','.join(map(str, old_lines))}")
    watch_lines = [i for i, line in enumerate(lines, 1) if wear.search(line)]
    if watch_lines:
        wear_hits.append(f"{name}:{','.join(map(str, watch_lines))}")
print("Legacy identity migration inventory (informational):")
for kind, count in sorted(counts.items()):
    print(f"  {kind}: {count}")
print("Namespace references outside Kotlin/Java:")
for hit in namespace_non_sources:
    print(f"  {hit}")
print("Remaining Wear contract references:")
for hit in wear_hits:
    print(f"  {hit}")
