#!/usr/bin/env python3
"""Diagnostic only: align the source namespace before a three-way merge.

The exact MAIN tree stays unchanged. The common ancestor and AMS trees receive
the same mechanical namespace/symbol transformation. No original branch moves.
Runtime compatibility keys and user-visible legacy text are NOT broadly renamed.
"""
import pathlib
import subprocess
import sys

root = pathlib.Path(sys.argv[1])
paths = subprocess.check_output(["git","ls-files","-z"],cwd=root).decode().split("\0")
replacements = (
    ("com.music.bitchord", "com.music.aria"),
    ("com.dev.bitchord", "com.dev.aria"),
    ("com/music/bitchord", "com/music/aria"),
    ("com_music_bitchord", "com_music_aria"),
    ("BitChordApplication", "AriaApplication"),
    ("BitChordIcons", "AriaIcons"),
    ("BitChordTheme", "AriaTheme"),
    ("BitChordApp", "AriaApp"),
    ("Theme.BitChord", "Theme.Aria"),
    ("bitchordVersionCode", "ariaVersionCode"),
    ("bitchordVersionName", "ariaVersionName"),
    ("bitchordBetaSuffix", "ariaBetaSuffix"),
)
text_suffixes = {".kt", ".kts", ".java", ".xml", ".pro", ".cpp", ".cc", ".c", ".h", ".hpp", ".yml", ".yaml", ".sh", ".py", ".properties", ".gradle"}
changed = moved = 0
for rel in filter(None, paths):
    old = root / rel
    if not old.is_file() or old.is_symlink():
        continue
    if old.suffix in text_suffixes:
        raw = old.read_bytes()
        try:
            data = raw.decode("utf-8")
        except UnicodeDecodeError:
            continue
        for a,b in replacements:
            data = data.replace(a,b)
        if data.encode("utf-8") != raw:
            old.write_bytes(data.encode("utf-8"))
            changed += 1
    dest = rel.replace("/com/music/bitchord/", "/com/music/aria/")
    if dest.endswith("/BitChordApplication.kt"):
        dest = dest.removesuffix("BitChordApplication.kt") + "AriaApplication.kt"
    if dest.endswith("/BitChordIcons.kt"):
        dest = dest.removesuffix("BitChordIcons.kt") + "AriaIcons.kt"
    if dest != rel:
        target = root / dest
        if target.exists():
            raise SystemExit(f"Collision: {rel} -> {dest}")
        target.parent.mkdir(parents=True, exist_ok=True)
        old.rename(target)
        moved += 1
print(f"namespace_files={changed} moved_paths={moved}")
