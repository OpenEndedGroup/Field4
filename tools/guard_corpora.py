#!/usr/bin/env python3
"""Re-derive the population tables in `dev_docs/GUARD_CORPORA.md` instead of typing them.

That page was written on 2026-09-20 to name one defect — *a guard whose failure message states a
rule over one population while the guard enforces it over a smaller one* — and it was false in five
rows within twenty-four hours, against **its own published re-derivation commands**. `tools/test_*.py`
said 29 and was 31; `*Guard*.kt` + `*ContractTest.kt` said 28 and was 29; the corpus table said
`147 transcripts / 20 demos / 550 create frames` and `139 / 139`, and its closing arithmetic said
`139 + 8 = 147`. Nothing guarded any of it: the page carries no `<!--count:-->` markers and is not in
`CitationContractTest.GUARDED_PAGES`, so `ProseNumeralContractTest` never looked at it.

Re-measuring by hand would reset the clock and nothing else. So the numbers are not written by a
person any more. Every row below names a command, the command is the one **printed on the page**,
and this module runs it and renders the row from what came back. `--write` rewrites the block
between the two markers; `tools/test_guard_corpora.py` asserts the committed block is exactly what
`--write` would produce, so a stale number is a red build rather than a sentence somebody has to
notice.

Why the command is the unit rather than a Python re-implementation: a re-implementation is a second
definition of the population, and two definitions that agree today are the mechanism by which the
layout guard's demo half sat at zero for a fortnight. A reader can paste these into a shell and get
the same answer, which is the only property that makes the page checkable by a human at all.

Usage:
    python tools/guard_corpora.py            # print the block, and diff it against the page
    python tools/guard_corpora.py --write    # rewrite the block in place
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

TOOLS_ROOT = Path(__file__).resolve().parent
REPO_ROOT = TOOLS_ROOT.parent
PAGE = REPO_ROOT / "dev_docs" / "GUARD_CORPORA.md"

BEGIN = "<!-- guard-corpora: generated begin -->"
END = "<!-- guard-corpora: generated end -->"


@dataclass(frozen=True)
class Row:
    """One population, its shell derivation, and what a reader should do with the number.

    `command` is run from the repository root through `/bin/sh` and its stdout is counted **by
    line**, so every command has to list one thing per line rather than print a total. That is
    deliberate: a command that prints its own count (`wc -l`, `grep -c`) cannot be made to say
    *which* thing arrived, and naming the arrival is the whole reason this family of guards was
    rewritten from counts to sets in the first place. With a list in hand, `test_guard_corpora.py`
    can diff two runs and print the added and removed names.
    """

    key: str
    label: str
    command: str
    note: str


#: The guard families. `key` is stable and is what a failure message quotes; `label` and `command`
#: are what the page prints.
FAMILIES: tuple[Row, ...] = (
    Row("check_py", "`tools/check_*.py`", "ls -1 tools/check_*.py", "standalone checker scripts"),
    Row("test_py", "`tools/test_*.py`", "ls -1 tools/test_*.py", "unittest suites, all of them in `check` via `checkPythonTools`"),
    Row(
        "guard_kt",
        "`*Guard*.kt` + `*ContractTest.kt`",
        "find src -name '*Guard*.kt' -o -name '*ContractTest.kt'",
        "a **half-corpus by construction** — see the paragraph under this table",
    ),
    Row(
        "gradle_javaexec",
        "Gradle `check…` JavaExec guards",
        'grep -o \'tasks.register<JavaExec>("check[A-Za-z]*\' build.gradle.kts',
        "the guards `check` reaches by spawning a JVM main",
    ),
)

#: The corpora those guards are stated over. Every one of these is a filesystem population that a
#: sibling branch can grow without touching a file this branch touches, which is exactly the class
#: of drift the page exists to describe.
CORPORA: tuple[Row, ...] = (
    Row(
        "transcripts_top",
        "transcripts, top level",
        "ls -1 dev_docs/agents/transcripts/*.yaml",
        "what `AgentTranscriptIndexGuard` and the three verification registries count",
    ),
    Row(
        "transcripts_recursive",
        "transcripts, recursive",
        "find dev_docs/agents/transcripts -name '*.yaml' -o -name '*.yml'",
        "what `AgentTranscriptLayoutGuard` walks; the difference is the `docs_capture/` variants",
    ),
    Row(
        "demo_documents",
        "demo documents",
        "ls -1d dev_docs/agents/demos/*/",
        "held as a named set by `AgentDemoRegistrationTest.DEMO_CORPUS` and `test_demo_build_scripts.CORPUS`",
    ),
    Row(
        "demo_build_scripts",
        "demos shipping a `build.yaml`",
        "ls -1 dev_docs/agents/demos/*/build.yaml",
        "the population `test_demo_build_scripts.py` diffs against its `_code_.kt` sidecars",
    ),
    Row(
        "index_entries",
        "entries on `public/docs/agent_transcripts.md`",
        "grep -oE '^### [a-z0-9_]+$' public/docs/agent_transcripts.md",
        "must equal the top-level transcript count, both directions",
    ),
    Row(
        "starting_points",
        "materialized starting-point bundles",
        "ls -1d public/docs/assets/agent_transcripts/*/",
        "transcripts minus `AgentTranscriptMaterializer.EXCLUDE`",
    ),
)

ALL_ROWS: tuple[Row, ...] = FAMILIES + CORPORA


def derive(row: Row) -> list[str]:
    """The lines [row]'s own published command prints, from the repository root.

    A non-zero exit is not swallowed. A command that has stopped matching presents as an empty list
    and an empty list is indistinguishable from a deleted corpus, which is the failure this whole
    page is about; `test_guard_corpora.py` therefore floors every row at one and says so.
    """
    completed = subprocess.run(
        row.command,
        shell=True,
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(
            f"{row.key}: `{row.command}` exited {completed.returncode}. "
            f"stderr: {completed.stderr.strip()[:400]}"
        )
    return [line for line in completed.stdout.splitlines() if line.strip()]


def populations() -> dict[str, list[str]]:
    return {row.key: derive(row) for row in ALL_ROWS}


def _table(rows: tuple[Row, ...], counts: dict[str, list[str]], heading: str) -> list[str]:
    out = [heading, "", "| family | count | how | what it selects |", "| --- | --- | --- | --- |"]
    for row in rows:
        command = row.command.replace("|", r"\|")
        out.append(f"| {row.label} | {len(counts[row.key])} | `{command}` | {row.note} |")
    return out


def render() -> str:
    counts = populations()
    lines: list[str] = [
        BEGIN,
        "",
        "<!-- Written by `python tools/guard_corpora.py --write`. Checked by",
        "     `tools/test_guard_corpora.py`, which runs every command below and compares. Do not",
        "     hand-edit anything between the two markers: the whole point of this block is that no",
        "     human types these numbers, because on 2026-09-20 a human typed them and five were",
        "     wrong inside a day. -->",
        "",
    ]
    lines += _table(FAMILIES, counts, "### The guard families")
    lines += [""]
    lines += _table(CORPORA, counts, "### The corpora those guards are stated over")
    lines += [
        "",
        "The two transcript counts are both right and they are not the same measurement: "
        f"{len(counts['transcripts_recursive'])} = {len(counts['transcripts_top'])} + "
        f"{len(counts['transcripts_recursive']) - len(counts['transcripts_top'])} under "
        "`docs_capture/`. If you quote one of them, say which.",
        "",
        END,
    ]
    return "\n".join(lines) + "\n"


def current_block(text: str) -> str:
    start = text.find(BEGIN)
    end = text.find(END)
    if start < 0 or end < 0:
        raise RuntimeError(
            f"{PAGE} has no `{BEGIN}` / `{END}` pair. The generated block is the page's only source "
            "of population numbers; restore the markers rather than re-typing the tables."
        )
    return text[start : end + len(END)] + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="rewrite the block in dev_docs/GUARD_CORPORA.md")
    args = parser.parse_args()

    fresh = render()
    text = PAGE.read_text()
    have = current_block(text)

    if args.write:
        if have == fresh:
            print("guard_corpora: dev_docs/GUARD_CORPORA.md already current")
            return 0
        PAGE.write_text(text.replace(have.rstrip("\n"), fresh.rstrip("\n"), 1))
        print("guard_corpora: rewrote the generated block in dev_docs/GUARD_CORPORA.md")
        return 0

    sys.stdout.write(fresh)
    if have != fresh:
        print(
            "\nguard_corpora: dev_docs/GUARD_CORPORA.md is STALE — run "
            "`python tools/guard_corpora.py --write`",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
