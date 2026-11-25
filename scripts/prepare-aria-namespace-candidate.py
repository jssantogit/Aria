#!/usr/bin/env python3
"""Prepare a reviewable namespace migration in a disposable, clean checkout.

This deliberately does not push, commit or rewrite historical integration batches.
The resulting patch is a candidate, not a compatibility guarantee.
"""
import argparse
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OLD_NEW = (
    ("com.music.bitchord", "com.music.aria"),
    ("com/music/bitchord", "com/music/aria"),
    ("com_music_bitchord", "com_music_aria"),
    ("com.dev.bitchord", "com.dev.aria"),
    ("com/dev/bitchord", "com/dev/aria"),
    ("com_dev_bitchord", "com_dev_aria"),
)
TEXT_SUFFIXES = {".kt", ".kts", ".java", ".cpp", ".c", ".h", ".hpp", ".xml",
                 ".pro", ".properties", ".gradle", ".cmake", ".yml", ".yaml"}
MODULES = {"app", "shared", "sharedUi"}
OLD = re.compile("|".join(re.escape(old) for old, _ in OLD_NEW))
SERIAL = re.compile(r"@(?:[\w.]+\.)?(?:Serializable|SerialName|Polymorphic)\b|classDiscriminator\b|SerializersModule\b|ObjectInputStream\b")
SENSITIVE = re.compile(r'["\x27]|@SerialName|Class\.forName|FindClass|GetMethodID|SharedPreferences|ACTION_|Uri\.parse')

def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT)

def migrate(text):
    for old, new in OLD_NEW:
        text = text.replace(old, new)
    return text

def in_scope(path):
    # Workflow changes will be applied separately by the repository connector.
    return path.parts[0] in MODULES

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path,
                        help="Existing or new output directory outside the checkout")
    args = parser.parse_args()
    output = args.output.resolve()
    if output == ROOT or ROOT in output.parents:
        raise SystemExit("Output must be outside the checkout")
    if git("status", "--porcelain", "-z"):
        raise SystemExit("Candidate requires a clean disposable checkout")
    output.mkdir(parents=True, exist_ok=True)
    report = ["Namespace migration candidate; source HEAD: " + git("rev-parse", "HEAD").decode().strip(),
              "Quoted/reflective references require review; this is not a compatibility attestation.",
              "Historical integration manifests and their test expectations are not rewritten.",
              ""]
    files = [Path(p) for p in git("ls-files", "-z").decode("utf-8").split("\0") if p]
    edits, moves, outside, serial, literals = [], [], [], [], []
    destinations = set()
    for path in files:
        full = ROOT / path
        if full.is_symlink() or not full.is_file():
            if OLD.search(path.as_posix()):
                raise SystemExit(f"Unexpected nonregular migration path: {path}")
            continue
        target = Path(migrate(path.as_posix()))
        scoped = in_scope(path)
        if not scoped and target != path:
            outside.append(f"{path}: legacy path outside migration scope")
        if scoped and target != path:
            if target in destinations or (ROOT / target).exists():
                raise SystemExit(f"Migration destination collision: {target}")
            destinations.add(target)
            moves.append((path, target))
        if full.suffix not in TEXT_SUFFIXES and full.name != "CMakeLists.txt":
            continue
        source = full.read_bytes().decode("utf-8")
        for number, line in enumerate(source.splitlines(), 1):
            if OLD.search(line):
                if not scoped:
                    outside.append(f"{path}:{number}")
                elif SENSITIVE.search(line):
                    literals.append(f"{path}:{number}")
            if scoped and path.suffix in {".kt", ".java"} and SERIAL.search(line):
                serial.append(f"{path}:{number}")
        if scoped:
            revised = migrate(source)
            if revised != source:
                # A namespace substitution cannot introduce or remove whitespace.
                # This catches unintended formatting changes, including moved files
                # whose existing trailing whitespace Git would otherwise flag.
                if re.findall(r"\s+", source) != re.findall(r"\s+", revised):
                    raise SystemExit(f"Whitespace changed unexpectedly: {path}")
                edits.append((path, revised))
    if not moves or not edits:
        raise SystemExit("Expected legacy source paths and references were not found")
    report += [f"Tracked file moves: {len(moves)}", f"Text edits: {len(edits)}",
               "", "Quoted or reflective namespace references (review required):",
               *literals, "", "Serialization and polymorphism audit candidates:", *serial,
               "", "Namespace references outside migration scope (preserved):", *outside]
    (output / "namespace-review.txt").write_text("\n".join(report) + "\n", encoding="utf-8")
    for path, content in edits:
        (ROOT / path).write_bytes(content.encode("utf-8"))
    for path, target in moves:
        destination = ROOT / target
        destination.parent.mkdir(parents=True, exist_ok=True)
        (ROOT / path).rename(destination)
    git("add", "-A", "--", "app", "shared", "sharedUi")
    # Existing whitespace is byte-preserved by the invariant above. Retain
    # conflict-marker checking and explicitly raise rename detection capacity.
    check = subprocess.run(
        ["git", "-c", "diff.renameLimit=4000", "-c",
         "core.whitespace=-blank-at-eol,-blank-at-eof,-space-before-tab",
         "diff", "--cached", "--find-renames=20%", "--check"],
        cwd=ROOT, capture_output=True,
    )
    if check.returncode:
        raise SystemExit("Candidate diff check failed (conflict markers or Git error)")

    changed = git("diff", "--cached", "--name-only", "-z").decode("utf-8").split("\0")
    if any(not in_scope(Path(p)) for p in changed if p):
        raise SystemExit("Unexpected staged change outside migration scope")
    patch = git("-c", "diff.renameLimit=4000", "diff", "--cached", "--binary",
                "--full-index", "--find-renames=20%")
    if not patch:
        raise SystemExit("Empty namespace migration patch")
    (output / "aria-namespace.patch").write_bytes(patch)
    (output / "source-head.txt").write_bytes(git("rev-parse", "HEAD"))
    (output / "candidate-tree.txt").write_bytes(git("write-tree"))
    print("Quoted/reflective namespace references (paths only):")
    for hit in literals:
        print("  " + hit)
    print("Serialization/polymorphism candidates (paths only):")
    for hit in serial:
        print("  " + hit)
    print(f"Candidate: {len(moves)} moves, {len(edits)} text edits, "
          f"{len(literals)} quoted/reflective matches, {len(serial)} serialization matches.")
    print("Candidate only: review compatibility before promotion. No commit or push performed.")

if __name__ == "__main__":
    main()
