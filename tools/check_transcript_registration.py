#!/usr/bin/env python3
"""Name everything a new top-level agent transcript has to be registered in, before the lander does.

    uv run python tools/check_transcript_registration.py          # the whole corpus
    uv run python tools/check_transcript_registration.py --json   # machine-readable

Why: on 2026-11-05 the GPU Physarum branch added `dev_docs/agents/transcripts/gpu_physarum.yaml`
and needed two follow-up commits once the lander and the smoke suite found it: 444938ec9 (index
entry, starting-point bundle, `dev_docs/GUARD_CORPORA.md` counts) and 1269001df (its
`DemoTranscriptExecSmokeTest` classification). Each of those registers has its own guard, but they
run in different places — `checkAgentTranscriptIndex` in Gradle, `test_guard_corpora` in the Python
suites, the classification only in the `examples.demo-transcript.exec` smoke test, which no landing
runs — so each slip surfaced on its own, late. This checker asks all four questions at once, from
the files alone (no JVM, about a second), and prints the exact line to add for each miss.

For every `dev_docs/agents/transcripts/<name>.yaml` (top level only; `docs_capture/` variants are
not starting points):

1. `### <name>` heading on `public/docs/agent_transcripts.md` (what `AgentTranscriptIndexGuard`
   requires).
2. `"<name>"` in `EXECUTE`, `COMPILE_ONLY` or `KOTLINJS` of
   `src/jvmMain/kotlin/afield/examples/DemoTranscriptExecSmokeTest.kt` — an unclassified transcript
   fails that smoke test on purpose. Also the reverse: a classification naming no transcript.
3. `public/docs/assets/agent_transcripts/<name>/` exists unless `<name>` is in
   `AgentTranscriptMaterializer.EXCLUDE`. The lander regenerates the bundle before it guards, so this
   is a reminder (`./gradlew regenerateAgentTranscriptStartingPoints`), not a failure, unless `--strict-bundles`.
4. The generated block of `dev_docs/GUARD_CORPORA.md` is what `tools/guard_corpora.py` would write
   (its transcript counts move with every new transcript). The lander and precheck regenerate it on
   the merged tree, so this too is a reminder unless `--strict-corpora`.

Exit 1 on any failure. `tools/test_check_transcript_registration.py` holds the positive control
(a constructed unregistered transcript is named in every register) and the negative one (the
committed tree is clean).
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
TRANSCRIPTS = "dev_docs/agents/transcripts"
INDEX = "public/docs/agent_transcripts.md"
SMOKE = "src/jvmMain/kotlin/afield/examples/DemoTranscriptExecSmokeTest.kt"
MATERIALIZER = "src/jvmMain/kotlin/afield/examples/AgentTranscriptMaterializer.kt"
BUNDLES = "public/docs/assets/agent_transcripts"
CLASSIFICATIONS = ("EXECUTE", "COMPILE_ONLY", "KOTLINJS")

_HEADING = re.compile(r"^### ([a-z0-9_]+)\s*$", re.MULTILINE)
_LITERAL = re.compile(r'"([A-Za-z0-9_\-]+)"')


def kotlin_string_set(source: str, name: str) -> set[str] | None:
    """The string literals of `val <name> = setOf(...)` in Kotlin source, comments ignored.

    None when there is no such declaration (so a renamed set is reported, not read as empty).
    """
    match = re.search(rf"\bval\s+{re.escape(name)}\s*=\s*setOf\(", source)
    if match is None:
        return None
    depth, out, i = 1, set(), match.end()
    body: list[str] = []
    while i < len(source) and depth:
        ch = source[i]
        if source.startswith("//", i):
            i = source.find("\n", i)
            if i < 0:
                break
            continue
        if source.startswith("/*", i):
            end = source.find("*/", i + 2)
            i = len(source) if end < 0 else end + 2
            continue
        if ch == '"':
            end = source.find('"', i + 1)
            body.append(source[i:end + 1])
            i = end + 1
            continue
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        i += 1
    for literal in body:
        out.update(_LITERAL.findall(literal))
    return out


@dataclass
class Report:
    transcripts: list[str]
    failures: list[str] = field(default_factory=list)
    reminders: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.failures


def check(repo: Path = REPO, *, corpora: bool = True, strict_bundles: bool = False,
          strict_corpora: bool = False) -> Report:
    names = sorted(p.stem for p in (repo / TRANSCRIPTS).glob("*.yaml") if p.is_file())
    report = Report(names)
    known = set(names)

    index_path = repo / INDEX
    headings = set(_HEADING.findall(index_path.read_text(encoding="utf-8"))) if index_path.is_file() else set()
    for name in names:
        if name not in headings:
            report.failures.append(
                f"{name}: no index entry. Add a `### {name}` section to {INDEX} "
                "(heading, one-paragraph description, the macro write/run lines; copy a neighbour's shape)"
            )
    # Not the reverse: the index also lists saved documents (canonical_graphics, the StackSL
    # conversations) that have no transcript yaml, and AgentTranscriptIndexGuard owns that direction.

    smoke_path = repo / SMOKE
    smoke = smoke_path.read_text(encoding="utf-8") if smoke_path.is_file() else ""
    classified: dict[str, str] = {}
    for set_name in CLASSIFICATIONS:
        members = kotlin_string_set(smoke, set_name)
        if members is None:
            report.failures.append(f"{SMOKE}: no `val {set_name} = setOf(` — this checker cannot read the "
                                   "classification; update CLASSIFICATIONS in tools/check_transcript_registration.py")
            continue
        for member in members:
            classified.setdefault(member, set_name)
    for name in names:
        if name not in classified:
            report.failures.append(
                f"{name}: unclassified in DemoTranscriptExecSmokeTest. Add `\"{name}\",` (with a one-line "
                f"comment saying why) to EXECUTE (pure compute, runs headless), COMPILE_ONLY (opens a "
                f"window / needs a GPU or device) or KOTLINJS (Kotlin->JS head code) in {SMOKE}"
            )
    for stale in sorted(set(classified) - known):
        report.failures.append(f"{stale}: {classified[stale]} in {SMOKE} names no {TRANSCRIPTS}/{stale}.yaml; "
                               "remove the entry")

    materializer = repo / MATERIALIZER
    excluded = kotlin_string_set(materializer.read_text(encoding="utf-8"), "EXCLUDE") if materializer.is_file() else None
    excluded = excluded or set()
    for name in names:
        if name in excluded:
            continue
        if not (repo / BUNDLES / name / "metadata.edn").is_file():
            message = (f"{name}: no starting-point bundle {BUNDLES}/{name}/. The lander regenerates it; "
                       "to commit it yourself run `./gradlew regenerateAgentTranscriptStartingPoints` (or add the name to "
                       f"AgentTranscriptMaterializer.EXCLUDE if it is not a self-contained scene)")
            (report.failures if strict_bundles else report.reminders).append(message)

    if corpora:
        sys.path.insert(0, str(repo / "tools"))
        try:
            import guard_corpora as gc  # noqa: PLC0415

            page = repo / "dev_docs" / "GUARD_CORPORA.md"
            if gc.REPO_ROOT.resolve() == repo.resolve() and page.is_file():
                if gc.current_block(page.read_text(encoding="utf-8")) != gc.render():
                    (report.failures if strict_corpora else report.reminders).append(
                        "dev_docs/GUARD_CORPORA.md: the corpus counts are stale (a transcript, demo or index "
                        "entry was added or removed). The lander regenerates it; to commit it yourself run `uv run python tools/guard_corpora.py --write` and commit it"
                    )
        finally:
            sys.path.pop(0)
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--no-corpora", action="store_true", help="Skip the GUARD_CORPORA.md comparison.")
    parser.add_argument("--strict-bundles", action="store_true",
                        help="A missing starting-point bundle fails instead of reminding.")
    parser.add_argument("--strict-corpora", action="store_true",
                        help="Stale GUARD_CORPORA.md counts fail instead of reminding.")
    args = parser.parse_args(argv)
    report = check(corpora=not args.no_corpora, strict_bundles=args.strict_bundles,
                   strict_corpora=args.strict_corpora)
    if args.json:
        print(json.dumps({"ok": report.ok, "transcripts": len(report.transcripts),
                          "failures": report.failures, "reminders": report.reminders}, indent=2))
        return 0 if report.ok else 1
    for line in report.reminders:
        print(f"REMINDER {line}")
    if report.ok:
        print(f"OK — {len(report.transcripts)} top-level transcripts are indexed and classified.")
        return 0
    print(f"FAIL — {len(report.failures)} registration gap(s):")
    for line in report.failures:
        print(f"  {line}")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
