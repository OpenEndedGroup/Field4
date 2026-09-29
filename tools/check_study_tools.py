#!/usr/bin/env python3
"""Fail when a study's tools file re-declares a standard verb instead of using the shared registrar.

Architecture review 3 (2026-09-28) found four near-identical `install*StudyTools` files: every
study had copied Inspect, Edit, Patch, SetNumbers, Observe, Compare, Sweep, TryVariants, AdoptVariant
and Restore — request class *and* hand-written handler — from the one before it. Those verbs are now
wired once, by `registerStudyTools` in `src/jvmMain/kotlin/afield/stdlib/LiveStudy.kt`, against the
`StudyRequest.*` interfaces. A study still declares its own request classes, because a class's name
is the tool's name and its fields are the schema a model has been shown; what it must not do is
declare one that the registrar cannot see, or register one by hand.

So, in every `*StudyTools.kt` under `src/`, for each request class whose name is a standard verb's
(`Inspect…`, `Edit…`, `Patch…`, `Set…Numbers`, `Observe…`, `Compare…`, `Sweep…`, `Try…Variants`,
`Adopt…Variant`, `Restore…`):

1. the declaration must implement the matching `StudyRequest.<Verb>` interface; and
2. the file must not pass that class to `register(` itself — `registerStudyTools` does.

A verb whose shape genuinely differs (the function study's interval-shaped Edit and Observe) is
listed in `DIFFERENT_SHAPE` with the reason. Adding to that list is the deliberate act this guard
exists to make visible.

Run:  uv run --project . python tools/check_study_tools.py [--root <repo>]
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

VERBS: list[tuple[re.Pattern[str], str]] = [
    (re.compile(r"^Inspect\w+$"), "Inspect"),
    (re.compile(r"^Edit\w+$"), "Edit"),
    (re.compile(r"^Patch\w+$"), "Patch"),
    (re.compile(r"^Set\w*Numbers$"), "SetNumbers"),
    (re.compile(r"^Observe\w+$"), "Observe"),
    (re.compile(r"^Compare\w+$"), "Compare"),
    (re.compile(r"^Sweep\w+$"), "Sweep"),
    (re.compile(r"^Try\w*Variants$"), "TryVariants"),
    (re.compile(r"^Adopt\w*Variant$"), "Adopt"),
    (re.compile(r"^Restore\w+$"), "Restore"),
]

# Standard-verb names whose requests are shaped differently enough that the registrar's interface
# would be a lie. Each needs a reason a reviewer can check.
DIFFERENT_SHAPE: dict[str, str] = {
    "EditFunctionStudy": "previews an interval (previewStartSeconds/EndSeconds), not frames at times",
    "PatchFunctionStudy": "previews an interval (previewStartSeconds/EndSeconds), not frames at times",
    "ObserveFunctionStudy": "observes a time window (startSeconds/endSeconds) of signals, not frames",
    "CompareFunctionStudy": "compares within a changed time window and named channels, not pixels",
    "ObserveStackAutoSlot": "captures the host shader now or across `over` seconds; it cannot draw a revision at chosen times",
}

DATA_CLASS = re.compile(r"\bdata\s+class\s+(\w+)\s*\(")


def declarations(text: str) -> list[tuple[str, str, int]]:
    """Each data class in [text]: its name, its header (constructor and supertypes), its line."""
    out = []
    for m in DATA_CLASS.finditer(text):
        depth, i = 1, m.end()
        while i < len(text) and depth:
            depth += {"(": 1, ")": -1}.get(text[i], 0)
            i += 1
        # The supertype list runs from the constructor's close to the body or the next declaration.
        tail = re.match(r"[^{\n]*(?:\n[ \t]+[^\n{]*)*", text[i:])
        header = text[m.start():i] + (tail.group(0) if tail else "")
        out.append((m.group(1), header, text.count("\n", 0, m.start()) + 1))
    return out


def verb_of(name: str) -> str | None:
    for pattern, verb in VERBS:
        if pattern.match(name):
            return verb
    return None


def check_file(path: Path, root: Path) -> list[str]:
    text = path.read_text(encoding="utf-8")
    rel = path.relative_to(root)
    problems = []
    for name, header, line in declarations(text):
        verb = verb_of(name)
        if verb is None or name in DIFFERENT_SHAPE:
            continue
        if not re.search(r"\bStudyRequest\." + verb + r"\b", header):
            problems.append(f"{rel}:{line}: {name} is a standard {verb} request but does not implement StudyRequest.{verb}; "
                            f"implement it and list the class in registerStudyTools(..., StudyToolClasses({verb[0].lower() + verb[1:]} = ...)), "
                            "or, if its shape truly differs, add it to DIFFERENT_SHAPE in tools/check_study_tools.py with the reason")
        if re.search(r"\bregister\(\s*" + re.escape(name) + r"::class\.java", text):
            problems.append(f"{rel}: {name} is registered by hand; registerStudyTools wires the standard verbs — pass it in StudyToolClasses instead")
    return problems


def study_tool_files(root: Path) -> list[Path]:
    return sorted(p for p in (root / "src").rglob("*StudyTools.kt") if p.is_file())


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    args = parser.parse_args(argv)
    root = args.root.resolve()
    files = study_tool_files(root)
    if not files:
        print(f"check_study_tools: no *StudyTools.kt under {root / 'src'}; nothing guarded", file=sys.stderr)
        return 1
    problems = [p for f in files for p in check_file(f, root)]
    for p in problems:
        print(p, file=sys.stderr)
    if problems:
        print(f"check_study_tools: {len(problems)} problem(s) in {len(files)} file(s)", file=sys.stderr)
        return 1
    print(f"check_study_tools: ok — {len(files)} *StudyTools.kt file(s), standard verbs all through registerStudyTools")
    return 0


if __name__ == "__main__":
    sys.exit(main())
