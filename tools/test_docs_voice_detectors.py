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


if __name__ == "__main__":
    unittest.main()
