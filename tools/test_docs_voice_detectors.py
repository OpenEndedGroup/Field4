#!/usr/bin/env python3
"""Pin that the `tools/docs_voice/` prose detectors can still detect.

These detectors ran for a month from `~/.claude/scheduled-tasks/`, where nothing could
fail on them. The failure mode that costs most is not a false positive -- a firing reads
its hits and throws those out. It is a detector that silently stops matching: a regex
edited to kill one false positive, an import that breaks, a surface filter that starts
excluding everything. Then a nightly prints `nonzero=0 sum=0`, the run reports the queue
empty, and nobody learns for weeks that the board went blank rather than clean.

So what is gated here is CAPABILITY, never content:

  - every module imports. An unimportable detector is exactly as broken as a silent one.
  - every module that ships a `live_fire()` passes it.
  - `score_voice.score()` still fires on the four faults Marc named on 2026-09-13, which
    are the specification this whole pass serves, and stays silent on plain documentation
    prose.

Deliberately NOT gated: any page's score. `score_voice.py --calibrate` asserts that Marc's
own four pages stay near zero and it is green today, but wiring a *content* threshold into
`check` is a live decision about the docs tree and not one a test should make quietly. See
`tools/docs_voice/README.md` for the open question.
"""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

DETECTORS = Path(__file__).resolve().parent / "docs_voice"


def load(name):
    """Import a detector module by path, the way its own `sys.path` shim expects."""
    if str(DETECTORS) not in sys.path:
        sys.path.insert(0, str(DETECTORS))
    spec = importlib.util.spec_from_file_location(name, DETECTORS / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def module_names():
    return sorted(p.stem for p in DETECTORS.glob("*.py"))


# The four faults Marc named, verbatim, on 2026-09-13. Each must score.
MARC_QUOTES = [
    "### The reading is total; the *parameterisation* is where the refusals live",
    "## Two ways the numbers will surprise you",
    "The left panel is the sanity check, and it carries a trap.",
    "## Mirrors, and where a TRS triple loses one",
]

# Plain documentation prose, of the kind the pass is trying to produce. Must stay silent.
CLEAN = """# Loading a mesh from disk

`meshFromDisk` reads OBJ, PLY and STL. Vertex colours survive the round trip; normals are
recomputed unless the file carries them. A file with no faces loads as a point cloud.

    val mesh = meshFromDisk("bunny.ply")

Pass `weld = false` to keep duplicate vertices, which matters when the source encodes a
crease by splitting a vertex rather than by marking an edge.
"""


class DetectorsImport(unittest.TestCase):
    def test_every_module_imports(self):
        """An unimportable detector reports a clean board for the wrong reason."""
        self.assertTrue(module_names(), f"no detectors found under {DETECTORS}")
        for name in module_names():
            with self.subTest(module=name):
                load(name)


class LiveFire(unittest.TestCase):
    def test_live_fire_self_tests_pass(self):
        """Every detector that ships a self-test still passes it."""
        ran = []
        for name in module_names():
            module = load(name)
            fire = getattr(module, "live_fire", None)
            if fire is None:
                continue
            with self.subTest(module=name):
                self.assertEqual(fire(), 0, f"{name}.live_fire() did not return 0")
                ran.append(name)
        self.assertTrue(ran, "no detector ships a live_fire(); the suite gates nothing")


class TargetVoice(unittest.TestCase):
    """The specification: Marc's own four quotes fire, and clean prose does not."""

    def setUp(self):
        self.sv = load("score_voice")

    def test_each_named_fault_scores(self):
        for quote in MARC_QUOTES:
            with self.subTest(quote=quote[:48]):
                total, *_rest = self.sv.score(quote + "\n\nSome body text follows it.\n")
                self.assertGreater(
                    total, 0,
                    f"score_voice no longer fires on a fault Marc named: {quote!r}")

    def test_plain_documentation_is_silent(self):
        total, _words, _em, hits, _detail, _meas = self.sv.score(CLEAN)
        self.assertEqual(total, 0, f"fired on plain documentation prose: {dict(hits)}")


class BoardsStayAlive(unittest.TestCase):
    """Every board still runs to completion over the real tree, and says something.

    This is the gap the import test above could not see, and it cost a day. On
    2026-09-23 `alt_text.py` died on every invocation with `ValueError: No closing
    quotation` out of `shlex.split`, because one page's `alt=` described a Kotlin lambda
    and a first-`}` attribute regex cut the quoted value in half. It imported fine. Its
    `live_fire()` passed, because the fixture had no braces in it. And a board that dies
    is indistinguishable from a board that is clean unless a human reads its stderr --
    so a firing ran it, saw no rows, and recorded the queue as empty. Twice.

    What is asserted is deliberately weak and therefore stable: exit 0, and at least one
    non-blank line of output. NOT a row count, NOT a page, NOT a score -- those are live
    decisions about the docs tree, which this suite's own docstring says a test must not
    make quietly. A board legitimately reporting zero hits still prints its summary line.
    """

    # Boards that take two git revisions or a generated-page pairing rather than a
    # directory, so `<script> public/docs` is not their calling convention.
    NOT_A_DIRECTORY_SCAN = {"check_fences", "check_kdoc_mirror"}
    # Not boards: a shared helper, the surface-scoring library, and the census harness.
    NOT_A_BOARD = {"build_machinery", "in_fences", "test_census"}

    def test_every_board_exits_zero_and_prints_something(self):
        import subprocess

        docs = DETECTORS.parent.parent / "public" / "docs"
        self.assertTrue(docs.is_dir(), f"expected a docs tree at {docs}")
        checked = []
        for name in module_names():
            if name in self.NOT_A_DIRECTORY_SCAN or name in self.NOT_A_BOARD:
                continue
            with self.subTest(board=name):
                done = subprocess.run(
                    [sys.executable, str(DETECTORS / f"{name}.py"), str(docs)],
                    capture_output=True, text=True, timeout=300,
                )
                self.assertEqual(
                    done.returncode, 0,
                    f"{name}.py exited {done.returncode} over the real tree. A board that "
                    f"dies reports nothing about any page.\n{done.stderr[-1500:]}")
                self.assertTrue(
                    done.stdout.strip(),
                    f"{name}.py exited 0 and printed nothing. Silence and cleanliness "
                    f"must not look the same.")
                checked.append(name)
        self.assertGreater(
            len(checked), 8,
            f"only {len(checked)} board(s) were run; the exclusion sets have swallowed the suite")


class Narrowings(unittest.TestCase):
    """Both directions of the narrowings a firing paid to derive.

    A narrowing is the one edit shape this suite's docstring names as most dangerous: it
    is made to kill a false positive, and it is trivially over-applied into a pattern that
    matches nothing. Each case below pins the false positive *staying* dead AND the true
    sense staying reachable, so a later widening and a later over-narrowing both fail here
    rather than in a ledger entry six firings on.
    """

    def test_argument_is_only_a_label_when_it_is_an_argument_for_something(self):
        sv = load("score_voice")
        # 2026-09-21: ten of the twelve `the argument` in public/docs are a call's
        # argument. This one is an interaction-net port table, and it used to score.
        prog = "`aux(1)` is the argument, and `aux(0)` is where the result comes back out.\n"
        self.assertEqual(
            sv.score(prog)[0], 0,
            "`is the argument` fires on the programming term again; check the `for` lookahead")
        # NOT "That is the argument for ...", which the family's own `(?<!that )` lookbehind
        # blocks whatever `argument` does -- the ledger recorded that sentence as this
        # narrowing's regression check and it never fired either way.
        rhetorical = "The deciding factor is the argument for deriving the count.\n"
        self.assertGreater(
            sv.score(rhetorical)[0], 0,
            "the rhetorical `the argument for <doing something>` is no longer reachable")

    def test_a_semicolon_is_punctuation_in_a_caption_and_two_headings_in_a_title(self):
        fl = load("fence_labels")

        def semicolons(arg):
            """`fence_labels` reads labels out of a fence BODY, not out of its header."""
            fence = "```diffstatic {data-lang=kotlin}\nchart(%s)\n```\n" % arg
            return [h for h in fl.scan(fence) if h[2] == "head:head-semicolon"]

        # All ten semicolon-carrying labels in the tree are captions, where a semicolon
        # joins two clauses of a running sentence. In a title it is a heading split in two.
        self.assertEqual(
            semicolons('caption = "The box halves when h halves; the sphere quarters."'), [],
            "head-semicolon fires inside a caption again; three rows it would flag are "
            "baked into committed figures and would cost a re-capture to 'fix'")
        self.assertTrue(
            semicolons('title = "Loading a mesh; what the loader refuses"'),
            "head-semicolon no longer reaches a title, which is the only place it ever "
            "meant anything")


if __name__ == "__main__":
    unittest.main()
