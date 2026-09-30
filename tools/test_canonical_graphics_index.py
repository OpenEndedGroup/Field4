"""Guard the generated lesson index for the `canonical_graphics` example corpus.

The corpus is the tree's best set of known-good `afield.four` graphics code, and every file
in it is named by box uid. `public/docs/assets/canonical_graphics/LESSONS.md` is what makes
it reachable by lesson name and by API symbol, and it is generated — so it decays silently
the first time somebody edits a box without rerunning the generator. This is the check that
turns that into a build failure.

Run everything: `./gradlew checkPythonTools`
Fix a failure:  `uv run --project . --group backdoor --frozen python tools/gen_canonical_graphics_index.py`
"""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "tools"))

import gen_canonical_graphics_index as gen  # noqa: E402


def build_index() -> str:
    boxes = gen.read_boxes()
    for box in boxes.values():
        gen.attach_files(box)
        gen.scan_symbols(box)
    return gen.render(boxes)


class CommittedIndexIsCurrentTests(unittest.TestCase):
    def test_lessons_md_matches_freshly_generated_output(self):
        self.assertTrue(
            gen.INDEX_FILE.exists(),
            f"{gen.INDEX_FILE} is missing — run tools/gen_canonical_graphics_index.py",
        )
        self.assertEqual(
            gen.INDEX_FILE.read_text(encoding="utf-8"),
            build_index(),
            "LESSONS.md is stale. Regenerate with "
            "`uv run --project . --group backdoor --frozen python "
            "tools/gen_canonical_graphics_index.py` and commit the result.",
        )

    def test_generation_is_deterministic(self):
        # Symbols are collected in sets, so an unsorted render would churn the committed
        # file between runs and make the guard above flap rather than fail honestly.
        self.assertEqual(build_index(), build_index())


class IndexContentTests(unittest.TestCase):
    """The properties that make the index worth having at all."""

    def setUp(self):
        self.boxes = gen.read_boxes()
        for box in self.boxes.values():
            gen.attach_files(box)
            gen.scan_symbols(box)
        self.index = gen.INDEX_FILE.read_text(encoding="utf-8")

    def test_gpu_schema_lesson_is_persisted_under_renderer(self):
        uid = "b42d7b13-680e-4f9b-b789-8e6e7d8c4a21"
        self.assertIn(uid, self.boxes, "Sidecars alone do not create a box on first open")
        lesson = self.boxes[uid]
        self.assertEqual("5d. GPU schema", lesson.display_name)
        self.assertEqual(["611072da-1af6-4d2d-85bf-07a9ba191aca"], lesson.parents)
        self.assertFalse(lesson.tombstoned)
        self.assertEqual({"code", "vertex", "fragment"}, set(lesson.files))
        self.assertIn("| 5d. GPU schema | `window` |", self.index)

    def test_every_live_numbered_lesson_appears(self):
        missing = [
            box.display_name
            for box in self.boxes.values()
            if box.is_lesson and not box.tombstoned and box.files
            and f"| {box.display_name} |" not in self.index
        ]
        self.assertEqual([], missing, f"lessons absent from LESSONS.md: {missing}")

    def test_every_listed_file_exists_on_disk(self):
        # The index's whole job is turning a lesson name into a file to open, so a dead
        # filename is the one failure that makes it worse than nothing.
        import re

        referenced = set(re.findall(r"`([0-9a-f-]{36}_[a-z_]+_\.[a-z]+)`", self.index))
        self.assertGreater(len(referenced), 30, "index references suspiciously few files")
        missing = sorted(n for n in referenced if not (gen.CORPUS / n).exists())
        self.assertEqual([], missing, f"LESSONS.md names files that do not exist: {missing}")

    def test_tombstoned_boxes_are_not_advertised(self):
        for box in self.boxes.values():
            if box.tombstoned and box.is_lesson:
                with self.subTest(box=box.uid):
                    self.assertNotIn(f"| {box.display_name} |", self.index)

    def test_producer_consumer_chains_are_recorded(self):
        # `5. draw rtt` reads what `5. RTT` renders; running it without that parent is the
        # topology error the corpus exists to teach. If the chain column ever silently
        # flattens to "—", the index stops carrying the one fact an agent cannot guess.
        self.assertIn("| 5. draw rtt | `window` → `5. RTT` |", self.index)
        self.assertIn("| 3b. draw particles | `window` → `3b. compute particles` |", self.index)

    def test_api_reverse_index_resolves_real_symbols(self):
        for symbol in ("RenderToTexture", "LoadedTexture", "computeModuleHere", "GpuRadixSort"):
            with self.subTest(symbol=symbol):
                self.assertIn(f"- `{symbol}` — ", self.index)

    def test_stoplisted_jvm_noise_is_not_indexed(self):
        for symbol in ("Math", "String", "ArrayList"):
            with self.subTest(symbol=symbol):
                self.assertNotIn(f"- `{symbol}` — ", self.index)


if __name__ == "__main__":
    unittest.main()
