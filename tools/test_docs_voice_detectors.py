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
import subprocess
import sys
import tempfile
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


# Boards that need something other than a docs root on the command line. Every board is
# run; none is skipped. A skip list is how a board goes dark, which is the whole subject
# of BoardsSurviveTheTree below.
# The default below -- `public/docs` -- is load-bearing, and a board that takes a
# different root is invisible to this gate rather than gated by it. `kdoc_overviews.py`
# takes a *Kotlin source* root. From 2026-09-25 to 2026-09-25 it was run here with
# `public/docs`, found no `*.kt` under it, printed `overviews=0 ... sum=0` and exited 0 --
# so both assertions below passed on a scan of **zero files**, including the one whose own
# failure message is "a reader cannot tell a clean board from one that scanned zero
# files". A wrong argv looks exactly like a clean tree. Add the root when you add a board.
BOARD_ARGV = {
    "check_fences": ["HEAD", "HEAD"],
    "check_kdoc_mirror": ["--sweep"],
    "kdoc_overviews": ["src", "public/docs/reference"],
}

# Exempt from `test_no_board_is_blind_to_its_root`, each with why. A board belongs here
# only if it genuinely does not take a filesystem root -- not because making it print its
# sweep size was inconvenient.
ROOT_BLIND_BY_DESIGN = {
    # Takes two git refs and reads the trees behind them, so a directory argument is not
    # a root it could be blind to.
    "check_fences",
    # `--sweep` walks the repo from the tool's own location and takes no root at all.
    "check_kdoc_mirror",
}

# Boards known to die on this base, each with where the fix already is. Enforced in BOTH
# directions, like `expected_test_failures.json`: an unregistered failure is an ordinary
# failure, and a registered board that starts passing fails too -- so an entry cannot
# outlive its bug the way a sentence in a commit message can.
# Empty is the good state, and it was not empty for ten days: `alt_text` sat here from
# 2026-09-15 until 9e8d7fd7c was cherry-picked onto this branch on 2026-09-25, and every
# firing in between scanned no alt text at all. The entry said "delete this when that
# branch lands; this test will tell you to", and it did, in the same run that made the
# board pass. Keep entries this specific: one board, one failure, and where the fix is.
KNOWN_RED = {}


class BoardsSurviveTheTree(unittest.TestCase):
    """Every board runs over the real `public/docs` without dying.

    Importing and passing `live_fire()` is not the same as surviving 618 real files, and
    the gap is not hypothetical. `alt_text.py` imported cleanly, passed its live fire, and
    raised on page 137 of 140 for a full day in September 2026 -- reporting nothing at all
    about the other 139, with a traceback nobody read, because a board is not gated and a
    board that dies looks exactly like a board that is clean. The same broken expression
    made `double_published.py` drop a fence silently and `check_doc_assets.py` report a
    visible figure as an orphan.

    So: exit 0, and print a count. Under a minute for the whole directory, whatever is in it.

    (The sentence here used to say "for all eighteen", and by 2026-09-25 there were twenty.
    A planted count in a docstring nobody re-derives is the same fault `number_restated.py`
    in this directory now looks for on the docs pages.)
    """

    def boards(self):
        return sorted(p for p in DETECTORS.glob("*.py") if not p.name.startswith("_"))

    _ran = {}

    def run_board(self, path):
        """One sweep, shared by both assertions: one subprocess per board, not two."""
        if path.stem not in self._ran:
            argv = BOARD_ARGV.get(path.stem, ["public/docs"])
            self._ran[path.stem] = subprocess.run(
                [sys.executable, str(path), *argv],
                cwd=DETECTORS.parent.parent,
                capture_output=True,
                text=True,
                timeout=300,
            )
        return self._ran[path.stem]

    def test_every_board_exits_zero_on_the_real_tree(self):
        self.assertTrue(self.boards(), f"no boards found under {DETECTORS}")
        for path in self.boards():
            with self.subTest(board=path.name):
                done = self.run_board(path)
                if path.stem in KNOWN_RED:
                    self.assertNotEqual(
                        done.returncode, 0,
                        f"{path.name} passes now, so its KNOWN_RED entry is stale and "
                        f"must be deleted. It said: {KNOWN_RED[path.stem]}")
                    continue
                self.assertEqual(
                    done.returncode, 0,
                    f"{path.name} died on the real tree, so it is reporting nothing "
                    f"about any page.\n{done.stderr[-2000:]}")

    def test_every_board_prints_a_count(self):
        """A board that exits 0 and prints nothing is as invisible as one that crashes."""
        for path in self.boards():
            if path.stem in KNOWN_RED:
                continue
            with self.subTest(board=path.name):
                out = self.run_board(path).stdout
                self.assertTrue(out.strip(), f"{path.name} printed nothing at all")
                self.assertTrue(
                    any(c.isdigit() for c in out),
                    f"{path.name} printed no number, so a reader cannot tell a clean "
                    f"board from one that scanned zero files:\n{out[-500:]}")

    def test_no_board_is_blind_to_its_root(self):
        """Run each board against an EMPTY directory. The output must change.

        `test_every_board_prints_a_count` above asks for a digit, and a digit is not
        enough: `overviews=0`, `0 hit(s)`, and `0 figure(s) published twice` all contain
        one and all three were printed by boards that had read nothing. On 2026-09-25
        three boards were blind at once -- `kdoc_overviews.py` because this file passed it
        a docs root when it takes a Kotlin source root, and `moves.py` and
        `double_published.py` because they reported hits without ever reporting how many
        pages they had swept. All three were green on every assertion in this file.

        An empty root is the cheapest possible collapsed input, so if a board says the
        same thing about it as about 618 real files, that board cannot distinguish clean
        from collapsed and neither can its reader. This is the fourth appearance in three
        firings of a sweep reporting nothing because its input was empty; the other two
        were a `| head` that truncated a completeness check and a zsh string that did not
        word-split. Those were one-off mistakes in a shell. This one is in a file.
        """
        with tempfile.TemporaryDirectory() as void:
            for path in self.boards():
                if path.stem in ROOT_BLIND_BY_DESIGN or path.stem in KNOWN_RED:
                    continue
                with self.subTest(board=path.name):
                    real = self.run_board(path).stdout
                    argv = [void if not a.startswith("-") else a
                            for a in BOARD_ARGV.get(path.stem, ["public/docs"])]
                    empty = subprocess.run(
                        [sys.executable, str(path), *argv],
                        cwd=DETECTORS.parent.parent, capture_output=True,
                        text=True, timeout=300).stdout
                    self.assertNotEqual(
                        real, empty,
                        f"{path.name} says exactly the same thing about an empty "
                        f"directory as about the real tree, so its report cannot "
                        f"distinguish a clean sweep from one whose input collapsed. "
                        f"Print the size of the sweep, or add it to "
                        f"ROOT_BLIND_BY_DESIGN with the reason.\n{real[-400:]}")


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
