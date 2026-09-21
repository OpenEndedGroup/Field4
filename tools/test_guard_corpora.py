#!/usr/bin/env python3
"""`dev_docs/GUARD_CORPORA.md`'s numbers are re-derived, not typed — and this is what says so.

The page exists to name one defect: *a guard whose failure message states a rule over one population
while the guard enforces it over a smaller one.* It was published on 2026-09-20 and was false in
five rows the next morning, **against its own published re-derivation commands** — `tools/test_*.py`
said 29 against 31, `*Guard*.kt` + `*ContractTest.kt` said 28 against 29, and the corpus table said
`147 / 20 / 550` and `139 / 139` when the tree held `149 / 21` and `141 / 141`. It carried no
`<!--count:-->` markers and was not in `CitationContractTest.GUARDED_PAGES`, so nothing in the tree
looked at it. A page written to name a defect, exhibiting it, unguarded.

Re-measuring by hand resets the clock and changes nothing, so the numbers moved into a generated
block instead. `tools/guard_corpora.py` runs each row's own published shell command and renders the
tables from what comes back; this module asserts the committed block is exactly what that would
produce today. The remedy is always one line and it is in the failure message.

## Why this does not pass vacuously

Four ways, and they are the reason this is more than a `diff`:

1. **The command is the unit, and it is the command the page prints.** Not a Python
   re-implementation — that would be a second definition of the population, and two definitions
   that agree today are precisely how `AgentTranscriptLayoutGuard`'s demo half sat at zero for a
   fortnight while the guard reported success. A reader can paste these into a shell.
2. **Every row is floored at one.** A scrape that silently stops matching presents as a tidy zero,
   and a zero deserves the same suspicion as a finding.
3. **The two corpora a sibling branch can grow are compared as NAMED SETS**, not counts. The whole
   reason tonight's failure was legible is that `CORPUS` and `DEMO_CORPUS` are lists of names; a
   count says something changed, a set difference says what arrived and what to do.
4. **Arithmetic the page states is re-derived**, not copied: the recursive transcript count minus
   the top-level one has to be the `docs_capture/` remainder, which is the sum the page got wrong
   (`139 + 8 = 147`) on the day it was written.

Run directly with:  uv run --project . --group backdoor python -m unittest tools.test_guard_corpora
Fix a failure with: python tools/guard_corpora.py --write
"""

from __future__ import annotations

import unittest
from pathlib import Path

from tools import guard_corpora as gc


class GuardCorporaTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.counts = gc.populations()

    def test_the_generated_block_is_what_the_commands_say_today(self) -> None:
        """The page's tables are the output of its own commands, byte for byte."""
        self.assertTrue(gc.PAGE.is_file(), f"no page at {gc.PAGE}")
        have = gc.current_block(gc.PAGE.read_text())
        want = gc.render()
        self.assertEqual(
            want,
            have,
            "dev_docs/GUARD_CORPORA.md's generated block is stale. It is written by "
            "`python tools/guard_corpora.py --write` and never by hand — run that. If a number "
            "moved because a sibling branch added a guard or a document, that is the intended "
            "signal and the regenerate is the whole fix.",
        )

    def test_every_row_selects_something(self) -> None:
        """A row that has stopped matching reads as a tidy zero, which is the worse failure."""
        empty = sorted(row.key for row in gc.ALL_ROWS if not self.counts[row.key])
        self.assertEqual(
            [],
            empty,
            f"{empty} selected nothing at all. That is a broken derivation, not a deleted corpus — "
            "check the command in tools/guard_corpora.py against the tree before touching the page. "
            "Populations this run: "
            + ", ".join(f"{k}={len(v)}" for k, v in sorted(self.counts.items())),
        )

    def test_the_demo_corpus_is_the_same_named_set_both_registries_hold(self) -> None:
        """Named sets, not counts, for the corpus two branches can grow without a merge conflict.

        `AgentDemoRegistrationTest.DEMO_CORPUS` (Kotlin) and `test_demo_build_scripts.CORPUS`
        (Python) are two hand-maintained lists of the same directory listing. Each is checked
        against the disk by its own suite; nothing checked them against *each other*, so the two
        could disagree — one branch editing one of them and a sibling editing the other is a clean
        `git merge-tree` and a corpus with two definitions.
        """
        on_disk = {Path(p).name.rstrip("/") for p in self.counts["demo_documents"]}
        on_disk = {name for name in on_disk if name}
        from tools.test_demo_build_scripts import CORPUS as PYTHON_CORPUS

        kotlin = _kotlin_demo_corpus()
        self.assertEqual(
            sorted(on_disk),
            sorted(PYTHON_CORPUS),
            "tools/test_demo_build_scripts.CORPUS and the directory listing disagree",
        )
        self.assertEqual(
            sorted(on_disk),
            sorted(kotlin),
            "AgentDemoRegistrationTest.DEMO_CORPUS and the directory listing disagree: added "
            f"{sorted(on_disk - kotlin)}, missing {sorted(kotlin - on_disk)}. The two corpora are "
            "maintained in different languages by different branches and share no file, so this "
            "is the only assertion that sees them together.",
        )

    def test_the_two_transcript_counts_differ_by_the_capture_variants(self) -> None:
        """`recursive - top level` is the `docs_capture/` remainder, which the page used to add up wrong."""
        top = len(self.counts["transcripts_top"])
        recursive = len(self.counts["transcripts_recursive"])
        nested = [p for p in self.counts["transcripts_recursive"] if "/docs_capture/" in p]
        self.assertEqual(
            recursive - top,
            len(nested),
            f"{recursive} recursive - {top} top-level = {recursive - top}, but {len(nested)} files "
            "sit under docs_capture/. Something else has appeared in a subdirectory of the "
            "transcript corpus, and the two guards that walk it disagree about what it holds.",
        )

    def test_every_materialized_bundle_names_a_transcript(self) -> None:
        """A bundle left behind by a deleted transcript is a starting point the welcome screen offers and nothing produces."""
        transcripts = {Path(p).stem for p in self.counts["transcripts_top"]}
        bundles = {Path(p.rstrip("/")).name for p in self.counts["starting_points"]}
        orphans = sorted(bundles - transcripts)
        self.assertEqual(
            [],
            orphans,
            f"{orphans} are committed under public/docs/assets/agent_transcripts/ but name no "
            "transcript. Regenerate with `./gradlew regenerateAgentTranscriptStartingPoints`.",
        )


def _kotlin_demo_corpus() -> set[str]:
    """`DEMO_CORPUS` read off the Kotlin source.

    A regex over a `setOf(...)` literal, which is exactly the kind of parse this file's own docstring
    is rude about — so it is bounded on both sides and asserts it found something, rather than
    returning an empty set and scoring a silent green.
    """
    import re

    source = (
        gc.REPO_ROOT / "src/jvmTest/kotlin/afield/docs/AgentDemoRegistrationTest.kt"
    ).read_text()
    match = re.search(r"val DEMO_CORPUS = setOf\((.*?)\n\s*\)", source, re.S)
    if match is None:
        raise AssertionError(
            "could not find `val DEMO_CORPUS = setOf(` in AgentDemoRegistrationTest.kt — the "
            "parse has broken, which is not the same as the corpus being empty"
        )
    names = set(re.findall(r'"([a-z0-9_]+)"', match.group(1)))
    if not names:
        raise AssertionError("DEMO_CORPUS parsed to zero names; fix this parse before the page")
    return names


if __name__ == "__main__":
    unittest.main()
