"""tools/check_transcript_gallery_figures.py: the gallery never runs four entries without a figure.

Run with:  uv run --project . --group backdoor python -m unittest tools.test_check_transcript_gallery_figures
"""

from __future__ import annotations

import subprocess
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import check_transcript_gallery_figures as gallery  # noqa: E402


def page(*entries: str, section: str = "## Scenes") -> str:
    return section + "\n\n" + "".join(f"### {e}\n\nText.\n\n" for e in entries)


class Runs(unittest.TestCase):
    def test_four_bare_entries_fail(self) -> None:
        self.assertEqual(gallery.bare_runs(page("a", "b", "c", "d")), [["a", "b", "c", "d"]])

    def test_three_bare_entries_pass(self) -> None:
        self.assertEqual(gallery.bare_runs(page("a", "b", "c")), [])

    def test_image_video_and_img_each_break_a_run(self) -> None:
        for fig in ("![x](assets/x.png)", '<video src="assets/x.mp4"></video>', '<img src="x.png">'):
            text = page("a", "b") + f"### c\n\n{fig}\n\n" + page("d", "e", "f", section="")
            self.assertEqual(gallery.bare_runs(text), [], fig)

    def test_section_heading_ends_a_run(self) -> None:
        self.assertEqual(gallery.bare_runs(page("a", "b") + page("c", "d", section="## Next")), [])


class AgainstTheRealTree(unittest.TestCase):
    def test_committed_gallery_is_clean(self) -> None:
        proc = subprocess.run([sys.executable, str(Path(gallery.__file__))], capture_output=True, text=True)
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)


if __name__ == "__main__":
    unittest.main()
