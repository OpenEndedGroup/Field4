#!/usr/bin/env python3
"""Git merge driver ``field-counts``: a text merge that ignores derived numerals.

    git config merge.field-counts.driver 'python3 tools/merge_derived_counts.py %O %A %B %L %P'

(registered by ``tools/install_git_hooks.py``; ``.gitattributes`` names it for the Markdown roots.)

A numeral written as ``**1025**<!--count:lprintlnChannelCallSites-->`` is derived:
``ProseNumeralContractTest`` counts it from the code, and ``tools/merge_regen.py`` rewrites it on the
merged tree (``AFIELD_WRITE_DERIVED_COUNTS=1``). Two branches that each add an ``lprintln`` both bump
it, and the plain text merge stopped on that line before the rewrite could run; on 2026-10-09 that
bounced a landing on ``public/docs/logging.md``. This driver settles each key's numeral first (theirs
if only they moved it, otherwise ours), writes it into all three sides, then runs ``git merge-file``:
a conflict that was only the numeral disappears, and a conflict in the prose around it stops the
merge exactly as before. The regeneration then replaces the settled value with the derived one.

Stdlib only, because git runs it with whatever ``python3`` is on the merging process's PATH.
"""

from __future__ import annotations

import re
import subprocess
import sys
import tempfile
from pathlib import Path

# DerivedCountGuard.ATTACHED, loosened: any word may stand in the numeral's place. The driver only
# makes the three sides agree; telling a misplaced marker from a wrong number is the test's job.
ATTACHED = re.compile(
    r"\b([A-Za-z]+-[A-Za-z]+|[0-9][0-9,_]*|[A-Za-z]+)([*`_]{0,3}[ ]?<!--\s*count:([A-Za-z][A-Za-z0-9_]*)\s*-->)"
)


def numerals(text: str) -> dict[str, str]:
    """Key -> the first numeral written against it."""
    found: dict[str, str] = {}
    for m in ATTACHED.finditer(text):
        found.setdefault(m.group(3), m.group(1))
    return found


def align(text: str, values: dict[str, str]) -> str:
    """[text] with every marked numeral whose key is in [values] replaced by that value."""
    return ATTACHED.sub(lambda m: values.get(m.group(3), m.group(1)) + m.group(2), text)


def merged_numerals(base: dict[str, str], ours: dict[str, str], theirs: dict[str, str]) -> dict[str, str]:
    """Per key, a three-way merge of the value: theirs if only they moved it, otherwise ours."""
    merged = dict(ours)
    for key, value in theirs.items():
        if key not in ours or ours[key] == base.get(key):
            merged[key] = value
    return merged


def merge(base: Path, ours: Path, theirs: Path, marker_size: str = "7", path: str = "") -> int:
    """Merge into [ours] in place, as git expects of a driver. Returns git merge-file's status."""
    texts = [side.read_text(encoding="utf-8", errors="surrogateescape") for side in (base, ours, theirs)]
    values = merged_numerals(*(numerals(text) for text in texts))
    with tempfile.TemporaryDirectory() as tmp:
        aligned = []
        for name, text in zip(("base", "ours", "theirs"), texts):
            target = ours if name == "ours" else Path(tmp) / name
            target.write_text(align(text, values) if values else text, encoding="utf-8", errors="surrogateescape")
            aligned.append(target)
        label = f":{path}" if path else ""
        return subprocess.run([
            "git", "merge-file", f"--marker-size={marker_size}",
            "-L", f"ours{label}", "-L", f"base{label}", "-L", f"theirs{label}",
            str(ours), str(aligned[0]), str(aligned[2]),
        ]).returncode


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print("usage: merge_derived_counts.py BASE OURS THEIRS [MARKER_SIZE] [PATH]", file=sys.stderr)
        return 2
    base, ours, theirs = (Path(a) for a in argv[:3])
    return merge(base, ours, theirs, *argv[3:5])


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
