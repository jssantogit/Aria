#!/usr/bin/env python3
"""Inventory blue UI color candidates without changing data/brand palettes."""
import colorsys
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HEX = re.compile(r"(?i)(?<!\w)(?:0x|#)([0-9a-f]{8}|[0-9a-f]{6})(?![0-9a-f])")
RGB = re.compile(r"\b(?:Color|(?:android\.graphics\.)?Color\.rgb|rgb)\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*[,)]")
NAMED = re.compile(r"(?i)\bColor\.(?:blue|cyan)\b|@(?:android:)?color/[\w]*(?:blue|cyan)[\w]*|\b(?:fill|stroke|color)\s*=\s*['\"](?:blue|cyan)['\"]")
FILL = re.compile(r"(?:background|containerColor|checkedTrackColor|checkedBorderColor|activeTrackColor|tint|color)\s*[=(].*(?:appleMusicKeyColor|ariaAccentColor|AppleMusic(?:Dark|Light)Key|Aria(?:Dark|Light)Accent)")
INK = re.compile(r"(?:contentColor|checkedThumbColor|color|tint)\s*=\s*Color\.White\b")
def blue(rgb):
    h, s, v = colorsys.rgb_to_hsv(*(x / 255 for x in rgb))
    return 185 <= h * 360 <= 255 and s >= .25 and v >= .25
def candidates(line):
    for m in HEX.finditer(line):
        raw = m.group(1)
        # Kotlin/XML ARGB; SVG 8-digit RGBA is separately reviewed if present.
        rgb = tuple(int(raw[-6:][i:i+2], 16) for i in (0,2,4))
        if blue(rgb):
            yield m.group()
    for m in RGB.finditer(line):
        rgb = tuple(map(int, m.groups()))
        if all(x <= 255 for x in rgb) and blue(rgb):
            yield m.group()
    for m in NAMED.finditer(line):
        yield m.group()
def main():
    tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode().split("\0")
    results = []
    reviewed = 0
    for name in sorted(filter(None, tracked)):
        if not (name.startswith(("app/src/main/", "app/src/dev/", "app/src/prod/", "sharedUi/src/", "shared/src/"))):
            continue
        if re.search(r"/(?:test|androidTest|[^/]*Test)/", name):
            continue
        path = ROOT / name
        if not path.is_file() or path.suffix not in {".kt", ".java", ".xml", ".svg"}:
            continue
        reviewed += 1
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        for index, line in enumerate(lines):
            for value in sorted(set(candidates(line))):
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
    print("Audit inventory complete; classify candidates by actual role.")
if __name__ == "__main__":
    main()
