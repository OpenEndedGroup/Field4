#!/usr/bin/env python3
"""Fail on a JVM test that passes vacuously when its optional native library is missing.

`if (!available()) return`, `if (!ManifoldLibrary.isAvailable) return println(...)` and the block
form `if (!X.isAvailable) { println(...); return }` all make a test count as PASSED on a machine
without the library, which includes the lander. A JUnit assumption (`Assume.assumeTrue`, through a
file-local `assumeAvailable()` or a shared helper such as `requirePaintLibrary()`) makes Gradle
report it SKIPPED instead. This scans `src/jvmTest/kotlin` and `src/jvmFastTest/kotlin`.

The same holds for any other early return that announces itself as a skip -- a missing
`glslangValidator`, an absent catalog beside the built library, an opt-in environment variable:
`if (validator == null) { println("SKIPPED ..."); return }` is a pass in Gradle's count. A gate of any
condition whose block (or one-line `return println(...)`) says "SKIP" before a bare return is flagged.

An early return whose value is not Unit (`return false` inside an `available()` helper) is fine:
it is the helper, not the test. Gated by `tools/test_check_native_gated_tests.py`, which
`checkPythonTools` runs for any change to a JVM test source.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOTS = ("src/jvmTest/kotlin", "src/jvmFastTest/kotlin")

# `if (!<...available()...|...isAvailable...>)` -- the native gate.
_GATE = r"if\s*\(\s*!.*(?:available\(\)|isAvailable)[^)]*\)\s*"
# One-line: the gate followed by a `return` (bare, `return println(...)`, `return@label`).
ONE_LINE = re.compile(_GATE + r"return\b")
# Block form: the gate opens a block; a bare return inside it is the same vacuous pass.
BLOCK_OPEN = re.compile(_GATE + r"\{")
# Any condition, when the early return says it is a skip.
ANY_GATE_ONE_LINE = re.compile(r"if\s*\(.*\)\s*return\s+println\(.*skip", re.IGNORECASE)
ANY_BLOCK_OPEN = re.compile(r"if\s*\(.*\)\s*\{\s*$")
SAYS_SKIP = re.compile(r"skip", re.IGNORECASE)
BARE_RETURN = re.compile(r"(?:^|[;{]\s*)return(?:@\w+)?\s*(?:[;}]|$)")


def offenders_in(path: Path, text: str) -> list[str]:
    found: list[str] = []
    lines = text.split("\n")
    for i, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith(("//", "*", "/*")):
            continue
        if ONE_LINE.search(line):
            found.append(f"{path}:{i + 1}: {stripped}")
            continue
        if ANY_GATE_ONE_LINE.search(line):
            found.append(f"{path}:{i + 1}: {stripped}")
            continue
        m = BLOCK_OPEN.search(line)
        if not m:
            if ANY_BLOCK_OPEN.search(line) and _skip_block_returns(lines, i):
                found.append(f"{path}:{i + 1}: {stripped}")
            continue
        depth = 0
        for j in range(i, min(i + 12, len(lines))):
            segment = lines[j][m.end() - 1:] if j == i else lines[j]
            if BARE_RETURN.search(segment.strip()):
                found.append(f"{path}:{i + 1}: {stripped}")
                break
            depth += segment.count("{") - segment.count("}")
            if depth <= 0:
                break
    return found


def _skip_block_returns(lines: list[str], i: int) -> bool:
    """The block opened on line `i` prints something saying "skip", then returns bare."""
    said_skip = False
    depth = 1
    for j in range(i + 1, min(i + 10, len(lines))):
        body = lines[j].strip()
        if depth == 1 and SAYS_SKIP.search(body):
            said_skip = True
        if depth == 1 and BARE_RETURN.search(body):
            return said_skip
        depth += body.count("{") - body.count("}")
        if depth <= 0:
            return False
    return False


def scan(repo: Path) -> list[str]:
    out: list[str] = []
    for root in ROOTS:
        base = repo / root
        if not base.is_dir():
            continue
        for f in sorted(base.rglob("*.kt")):
            out.extend(offenders_in(f.relative_to(repo), f.read_text(errors="replace")))
    return out


def main(argv: list[str]) -> int:
    repo = Path(argv[1]) if len(argv) > 1 else Path(__file__).resolve().parent.parent
    if not (repo / ROOTS[0]).is_dir():
        print(f"check_native_gated_tests: no {ROOTS[0]} under {repo}", file=sys.stderr)
        return 2
    offenders = scan(repo)
    if offenders:
        print("A native-gated (or self-described skipped) test returns early, so it PASSES. Use "
              "org.junit.Assume.assumeTrue(...) (or the file's assumeAvailable()) so it is reported SKIPPED:")
        print("\n".join(offenders))
        return 1
    print("check_native_gated_tests: no vacuous native-gated early returns")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
