#!/usr/bin/env python3
"""Structural regression gate for Android-only scope and the approved launcher mark."""
from pathlib import Path
from math import isclose
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

for target in ("desktopApp", "desktopUi", "tv", "wearos", "web"):
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
# Launcher-only reduction: preserve the original 108dp viewport and apply
# a uniform 85% scale around its centre (54,54). Keep UI/notification marks
# independent of launcher optical sizing.
def verify_launcher_mark_geometry(path):
    root = xml(path)
    groups = [group for group in root.iter("group")
              if approved <= {p.get(A + "pathData") for p in group.findall("path")}]
    require(len(groups) == 1, f"Expected one launcher mark group: {path}")
    group = groups[0]
    expected = {"scaleX": 0.0946616 * 0.85, "scaleY": 0.0946616 * 0.85,
                "translateX": 54 + (18.8805424 - 54) * 0.85,
                "translateY": 54 + (24.4655775 - 54) * 0.85}
    require(all(isclose(float(group.get(A + key, "nan")), value, abs_tol=1e-8, rel_tol=0)
                for key, value in expected.items()),
            f"Launcher optical size or centring changed: {path}")

manifest = xml(ROOT / "app/src/main/AndroidManifest.xml")
aliases = manifest.findall("./application/activity-alias")
require(len(aliases) == 13, "Launcher alias inventory changed; review all icon choices")
icons = {alias.attrib[A + "icon"].removeprefix("@mipmap/") for alias in aliases}
require(len(icons) == 13, "Launcher aliases must remain distinct")
for icon in icons | {"ic_launcher_round"}:
    spec = xml(RES / "mipmap-anydpi" / (icon + ".xml"))
    require(spec.tag == "adaptive-icon", f"Not adaptive: {icon}")
    for layer in ("foreground", "monochrome"):
        resource = spec.find(layer)
        require(resource is not None, f"Missing {layer}: {icon}")
        kind, name = resource.attrib[A + "drawable"].removeprefix("@").split("/")
        folder = "mipmap-anydpi" if kind == "mipmap" else kind
        require(approved <= marks(RES / folder / (name + ".xml")),
                f"Notation mark missing from {icon} {layer}")
        verify_launcher_mark_geometry(RES / folder / (name + ".xml"))

verify_launcher_mark_geometry(RES / "drawable/ic_launcher_foreground.xml")

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
playback = ROOT / "app/src/main/java/com/music/aria/playback/PlaybackService.kt"
require(not re.search(r"\b(?:WearBridge|watchSpeaker|WATCH_SESSION_FOREGROUND_MS)\b",
                      playback.read_text(encoding="utf-8")),
        "Retired Wear playback hooks returned")

# Runtime namespace is now an enforced contract. Historical integration
# manifests and their tests are immutable evidence, not runtime source.
# Only tracked source/config paths and line numbers are printed.
import subprocess
from collections import Counter

tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode("utf-8").split("\0")
namespace = re.compile(r"com[./_]music[./_]bitchord|com[./_]dev[./_]bitchord")
wear = re.compile(r"\b(?:WearBridge|WatchInstaller|WatchOutputState|WatchOutputUi|WatchEntry|WatchUpdateState|WatchPairingState|WatchAppStatus|WearCommandService|WatchSpeaker|WearOsScreen|WearOsSettingsRow|showWearOs|onWearOs|refreshWatchOutput|selectWatchOutput|leaveWatchOutput|setWatchVolume|watchOutput|watchSpeaker|WATCH_SESSION_FOREGROUND_MS)\b|\bSettingsSection\.WEAR\b")
text_suffixes = {".kt", ".kts", ".java", ".cpp", ".c", ".h", ".hpp", ".xml", ".pro",
                 ".properties", ".py", ".sh", ".yml", ".yaml", ".json", ".gradle", ".cmake"}
counts = Counter()
wear_hits = []
namespace_non_sources = []
product_identity_hits = []
for name in sorted(filter(None, tracked)):
    source = ROOT / name
    if source == Path(__file__).resolve() or not source.is_file() or source.is_symlink():
        continue
    runtime_scope = name.startswith(("app/", "shared/", "sharedUi/", ".github/workflows/"))
    if namespace.search(name):
        counts["legacy package paths"] += 1
        if runtime_scope:
            product_identity_hits.append(name)
    if source.suffix not in text_suffixes and source.name != "CMakeLists.txt":
        continue
    try:
        lines = source.read_text(encoding="utf-8").splitlines()
    except UnicodeDecodeError:
        counts["non-UTF8 tracked text candidates"] += 1
        continue
    old_lines = [i for i, line in enumerate(lines, 1) if namespace.search(line)]
    if old_lines:
        if runtime_scope:
            product_identity_hits.append(f"{name}:{','.join(map(str, old_lines))}")
        counts[f"namespace references: {name.split('/')[0]} {source.suffix or source.name}"] += 1
        if source.suffix not in {".kt", ".java"}:
            namespace_non_sources.append(f"{name}:{','.join(map(str, old_lines))}")
    watch_lines = [i for i, line in enumerate(lines, 1) if wear.search(line)]
    if watch_lines:
        wear_hits.append(f"{name}:{','.join(map(str, watch_lines))}")
print("Historical namespace reference inventory (outside runtime scope):")
for kind, count in sorted(counts.items()):
    print(f"  {kind}: {count}")
print("Namespace references outside Kotlin/Java:")
for hit in namespace_non_sources:
    print(f"  {hit}")
print("Remaining Wear contract references:")
for hit in wear_hits:
    print(f"  {hit}")

require(not wear_hits, "Retired Wear contracts returned: " + "; ".join(wear_hits))
require(not (ROOT / "app/src/main/java/com/music/aria/wear").exists(), "Retired Wear package returned")
require(not (ROOT / "app/src/main/res/values/wear.xml").exists(), "Retired Wear capability resource returned")
print("No retired Wear transport, output or settings contracts remain.")
require(not product_identity_hits, "Legacy runtime namespace returned: " + "; ".join(product_identity_hits))
for module, expected in (("app", "com.music.aria"), ("shared", "com.music.aria.shared"),
                         ("sharedUi", "com.music.aria.sharedui")):
    module_gradle = (ROOT / module / "build.gradle.kts").read_text(encoding="utf-8")
    require(re.findall(r'namespace\s*=\s*"([^"]+)"', module_gradle) == [expected],
            f"Wrong namespace for {module}")
print("Aria source, JNI, Gradle and workflow namespace contracts verified.")

# Audit remaining brand strings by path, never by source contents.
# Some are deliberately stable storage/protocol names or attribution.
brand = re.compile(r"bitchord|kushagrasingh|kabirsinghbhatia", re.IGNORECASE)
specific = {
    "application_class": re.compile(r"\bBitChordApplication\b"),
    "icon_object": re.compile(r"\bBitChordIcons\b"),
    "old_theme": re.compile(r"Theme\.BitChord\b|\bBitChord(?:Theme|Typography|App)\b"),
    "old_native": re.compile(r"\b(?:bitchord_analysis|bitchord_dsd|bitchord_dst)\b"),
    "old_version_extra": re.compile(r"bitchord(?:VersionCode|VersionName|BetaSuffix)"),
    "inherited_endpoint": re.compile(r"https?://[^ \t\"']*(?:bitchord|kushagrasingh|kabirsinghbhatia)", re.I),
}
print("Residual nominal identity inventory (compatibility and attribution require review):")
for name in sorted(filter(None, tracked)):
    source = ROOT / name
    if not name.startswith(("app/", "shared/", "sharedUi/", "backend/", ".github/workflows/")) and name != "build.gradle.kts":
        continue
    if not source.is_file() or source.is_symlink() or source.suffix not in text_suffixes | {".go", ".mod", ".ts", ".tsx", ".md", ".html", ".service"} and source.name != "CMakeLists.txt":
        continue
    try:
        lines = source.read_text(encoding="utf-8").splitlines()
    except UnicodeDecodeError:
        continue
    hits = [i for i, line in enumerate(lines, 1) if brand.search(line)]
    if not hits:
        continue
    labels = [label for label, regex in specific.items() if any(regex.search(lines[i - 1]) for i in hits)]
    print(f"  {name}:{','.join(map(str, hits))} [{','.join(labels) or 'review'}]")

# The operational Android symbols must not regress. Historical evidence and
# explicitly retained persisted keys are outside this check.
retired_symbols = []
for name in sorted(filter(None, tracked)):
    source = ROOT / name
    if name != "build.gradle.kts" and not name.startswith(("app/", "shared/", "sharedUi/")):
        continue
    if not source.is_file() or source.is_symlink() or source.suffix not in text_suffixes and source.name != "CMakeLists.txt":
        continue
    try:
        lines = source.read_text(encoding="utf-8").splitlines()
    except UnicodeDecodeError:
        continue
    hits = [i for i, line in enumerate(lines, 1) if any(specific[key].search(line) for key in ("application_class", "icon_object", "old_theme", "old_native", "old_version_extra"))]
    if hits:
        retired_symbols.append(f"{name}:{','.join(map(str, hits))}")
require(not retired_symbols, "Retired Android identity symbols returned: " + "; ".join(retired_symbols))
print("Aria Android application, icons, theme, native library and version symbols verified.")


# Keep native XML and Compose theme tokens synchronized.
accent_source = (ROOT / "sharedUi/src/jvmSharedMain/kotlin/com/music/aria/ui/theme/AppleMusicKeyColor.kt").read_text()
accent_resources = {node.attrib["name"]: node.text for node in xml(RES / "values/colors.xml").findall("color")}
for symbol, resource, folder in (
    ("AriaLightAccent", "aria_light_accent", "values"),
    ("AriaDarkAccent", "aria_dark_accent", "values-night"),
):
    match = re.search(r"val " + symbol + r" = Color\(0x([0-9A-Fa-f]{8})\)", accent_source)
    require(match and accent_resources.get(resource, "").upper() == "#" + match.group(1).upper(),
            f"Native accent differs from Compose: {symbol}")
    theme = xml(RES / folder / "themes.xml")
    items = {node.attrib["name"]: node.text for node in theme.findall(".//item")}
    for name in ("colorAccent", "android:colorAccent"):
        require(items.get(name) == "@color/" + resource, f"{folder}: missing native amber accent")
print("Aria Compose and native XML accent parity verified.")

# Full source inventory runs separately; keep the fast native build gate focused.
