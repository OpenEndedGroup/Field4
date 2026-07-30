#!/usr/bin/env python3
"""Report `require`/`check`/`assert` guards that cannot fail, and float tolerances tighter than round-off.

**Report-only, and deliberately not wired into `check`.** See the "what this cannot do" section below and
`dev_docs/box_shared_state_races.md` for why: the measurement below is the useful part, and a lint whose
signal-to-noise nobody has measured is worse than none.

The bug it looks for is a guard whose two sides say the same thing, so the guard passes for every input
and the documented failure never happens. The instance that motivated it (`afield/stdlib/alife_ca.kt`,
now fixed, and the old form is quoted in a comment there) was:

    require(s.filter { it.isDigit() }.length == s.count { it.isDigit() })

Both sides count the digits in `s`. `lifeRule("B3junk/S2x3")` therefore validated cleanly and silently
returned `B3/S23`; `lifeRule("hello/world")` returned a rule under which everything dies.

Two checks:

* **self-comparison** — a `==`/`!=` inside a guard whose sides are the same expression after normalising
  whitespace (`SELF`), or whose sides share a non-trivial lambda body *and* the same leading receiver
  (`SHARED-LAMBDA`, the shape above).
* **tolerance** — a float comparison against a tolerance tighter than `1e-9` (`TIGHT-TOLERANCE`).
  Double has ~15-16 significant digits, so a tolerance below 1e-9 on a value of order 1 is asserting on
  the last few bits, and any accumulated arithmetic will eventually cross it. Reported for review, not
  as a certain bug.

Usage:
    python3 tools/lint_tautological_guards.py [--root src] [--json] [--check self,tolerance]
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass, asdict
from pathlib import Path

TOOLS_ROOT = Path(__file__).resolve().parent
REPO_ROOT = TOOLS_ROOT.parent

GUARDS = ("require", "requireNotNull", "check", "checkNotNull", "assert")
# A tolerance this tight is at the edge of double round-off for values of order 1.
TOLERANCE_FLOOR = 1e-9
LAMBDA = re.compile(r"\{[^{}]{4,}?\}")
LEADING_RECEIVER = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*")
SCIENTIFIC = re.compile(r"\b\d+(?:\.\d+)?[eE]-(\d+)\b")
DECIMAL = re.compile(r"\b0\.(0{9,})\d*\b")


@dataclass
class Finding:
    kind: str
    path: str
    line: int
    guard: str
    detail: str
    text: str


def strip_noise(source: str) -> str:
    """Blank out comments and string literals so brace/paren matching is not fooled by them.

    Replaces their *contents* with spaces rather than deleting, so every byte offset — and therefore
    every reported line number — stays exactly where it was in the original file.
    """
    out = list(source)
    i, n = 0, len(source)
    while i < n:
        two = source[i:i + 2]
        if two == "//":
            j = source.find("\n", i)
            j = n if j < 0 else j
            for k in range(i, j):
                out[k] = " "
            i = j
        elif two == "/*":
            j = source.find("*/", i + 2)
            j = n if j < 0 else j + 2
            for k in range(i, j):
                if source[k] != "\n":
                    out[k] = " "
            i = j
        elif source[i:i + 3] == '"""':
            j = source.find('"""', i + 3)
            j = n if j < 0 else j + 3
            for k in range(i, j):
                if source[k] != "\n":
                    out[k] = " "
            i = j
        elif source[i] in "\"'":
            quote = source[i]
            j = i + 1
            while j < n and source[j] != quote:
                if source[j] == "\\":
                    j += 1
                j += 1
            j = min(j + 1, n)
            for k in range(i, j):
                if source[k] != "\n":
                    out[k] = " "
            i = j
        else:
            i += 1
    return "".join(out)


def balanced_args(masked: str, open_paren: int) -> tuple[str, int] | None:
    """Text between the parens starting at `open_paren`, and the index just past the closing one."""
    depth = 0
    for i in range(open_paren, len(masked)):
        if masked[i] in "([{":
            depth += 1
        elif masked[i] in ")]}":
            depth -= 1
            if depth == 0:
                return masked[open_paren + 1:i], i + 1
    return None


def split_top_level(masked_args: str, operators: tuple[str, ...]) -> tuple[str, str, str] | None:
    """Split on the first top-level occurrence of one of `operators`. Returns (lhs, op, rhs)."""
    depth = 0
    i = 0
    while i < len(masked_args):
        c = masked_args[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif depth == 0:
            for op in operators:
                if masked_args.startswith(op, i):
                    # Don't mistake `===` for `==`, or `<=` inside `<==`.
                    after = masked_args[i + len(op):i + len(op) + 1]
                    if op == "==" and after == "=":
                        i += 1
                        break
                    return masked_args[:i], op, masked_args[i + len(op):]
        i += 1
    return None


def normalise(expression: str) -> str:
    return re.sub(r"\s+", "", expression)


def self_comparison_findings(path: Path, source: str, masked: str) -> list[Finding]:
    findings: list[Finding] = []
    for guard in GUARDS:
        for match in re.finditer(rf"\b{guard}\s*\(", masked):
            open_paren = masked.index("(", match.start())
            got = balanced_args(masked, open_paren)
            if not got:
                continue
            masked_args, _ = got
            # Only the condition: a guard's lazy message is a second argument, and messages
            # legitimately repeat the expression.
            condition = split_top_level(masked_args, (",",))
            masked_condition = condition[0] if condition else masked_args

            parts = split_top_level(masked_condition, ("==", "!="))
            if not parts:
                continue
            lhs_masked, op, rhs_masked = parts
            start = open_paren + 1
            lhs = source[start:start + len(lhs_masked)]
            rhs_start = start + len(lhs_masked) + len(op)
            rhs = source[rhs_start:rhs_start + len(rhs_masked)]
            line = source.count("\n", 0, match.start()) + 1

            if normalise(lhs) and normalise(lhs) == normalise(rhs):
                findings.append(Finding(
                    "SELF", str(path), line, guard,
                    "both sides are the same expression, so the guard cannot fail",
                    f"{guard}({normalise(lhs)} {op} {normalise(rhs)})",
                ))
                continue

            shared = {normalise(b) for b in LAMBDA.findall(lhs)} & {normalise(b) for b in LAMBDA.findall(rhs)}
            if not shared:
                continue
            lhs_receiver = LEADING_RECEIVER.match(normalise(lhs))
            rhs_receiver = LEADING_RECEIVER.match(normalise(rhs))
            if lhs_receiver and rhs_receiver and lhs_receiver.group() == rhs_receiver.group():
                findings.append(Finding(
                    "SHARED-LAMBDA", str(path), line, guard,
                    f"both sides apply {sorted(shared)[0]} to `{lhs_receiver.group()}` — "
                    "check they are not two spellings of one quantity",
                    f"{guard}({normalise(lhs)} {op} {normalise(rhs)})",
                ))
    return findings


def tolerance_findings(path: Path, source: str, masked: str) -> list[Finding]:
    findings: list[Finding] = []
    for guard in GUARDS:
        for match in re.finditer(rf"\b{guard}\s*\(", masked):
            open_paren = masked.index("(", match.start())
            got = balanced_args(masked, open_paren)
            if not got:
                continue
            masked_args, _ = got
            args = source[open_paren + 1:open_paren + 1 + len(masked_args)]
            if not re.search(r"[<>]", masked_args):
                continue
            tightest: float | None = None
            for hit in SCIENTIFIC.finditer(args):
                value = float(hit.group())
                if tightest is None or value < tightest:
                    tightest = value
            for hit in DECIMAL.finditer(args):
                value = float(hit.group())
                if tightest is None or value < tightest:
                    tightest = value
            if tightest is not None and tightest < TOLERANCE_FLOOR:
                line = source.count("\n", 0, match.start()) + 1
                findings.append(Finding(
                    "TIGHT-TOLERANCE", str(path), line, guard,
                    f"tolerance {tightest:g} is below {TOLERANCE_FLOOR:g}; double carries ~15-16 "
                    "significant digits, so accumulated arithmetic will cross this",
                    normalise(args)[:160],
                ))
    return findings


def scan(root: Path, checks: set[str]) -> list[Finding]:
    findings: list[Finding] = []
    for path in sorted(root.rglob("*.kt")):
        try:
            source = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        masked = strip_noise(source)
        rel = path.relative_to(REPO_ROOT) if path.is_relative_to(REPO_ROOT) else path
        if "self" in checks:
            findings += self_comparison_findings(rel, source, masked)
        if "tolerance" in checks:
            findings += tolerance_findings(rel, source, masked)
    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default="src")
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--check", default="self,tolerance")
    args = parser.parse_args()

    root = Path(args.root)
    if not root.is_absolute():
        root = REPO_ROOT / root
    checks = {c.strip() for c in args.check.split(",") if c.strip()}

    findings = scan(root, checks)
    if args.json:
        print(json.dumps([asdict(f) for f in findings], indent=2))
        return 0

    by_kind: dict[str, list[Finding]] = {}
    for f in findings:
        by_kind.setdefault(f.kind, []).append(f)
    for kind in ("SELF", "SHARED-LAMBDA", "TIGHT-TOLERANCE"):
        group = by_kind.get(kind, [])
        print(f"\n=== {kind}: {len(group)}")
        for f in group:
            print(f"  {f.path}:{f.line}  [{f.guard}]  {f.detail}")
            print(f"      {f.text}")
    print(f"\n{len(findings)} finding(s) over {root}")
    # Always 0: this is a report, not a gate. See the module docstring.
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
