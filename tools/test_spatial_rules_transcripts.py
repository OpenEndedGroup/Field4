"""Keep the spatial rules tutorial, tour, and capture boxes in sync with their runnable fences."""

import re
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
PAGE = ROOT / "public/docs/spatial_rules.md"
TRANSCRIPTS = ROOT / "dev_docs/agents/transcripts"


def step_code(heading: str) -> str:
    section = PAGE.read_text().split(heading, 1)[1]
    match = re.search(r"```diff(?:_runner)? \{[^\n]*\}\n:::after\n(.*?)\n```", section, re.S)
    assert match, f"no diff fence after {heading!r}"
    return match.group(1).rstrip()


def box_code(path: Path, box: str) -> str:
    for op in yaml.safe_load(path.read_text())["operations"]:
        created = op.get("create")
        if created and created["box"] == box:
            return created["properties"]["code"].rstrip()
    raise AssertionError(f"{path.name} creates no box {box!r}")


class SpatialRulesTranscriptTests(unittest.TestCase):
    def test_the_tour_matches_its_recorded_examples(self):
        page = PAGE.read_text()
        tour = TRANSCRIPTS / "spatial_rules_tour.yaml"
        for film, box in [("filament", "sr_filament"), ("loom", "sr_loom"), ("thousand", "sr_torus")]:
            with self.subTest(film=film):
                match = re.search(
                    rf'^```diffstatic_runner [^\n]*name="spatial-{film}-film"[^\n]*\n(.*?)^```$',
                    page,
                    re.M | re.S,
                )
                self.assertIsNotNone(match)
                self.assertEqual(match.group(1).rstrip(), box_code(tour, box))

    def test_the_starting_point_is_step_eleven(self):
        self.assertEqual(step_code("### 11. Make it bigger"), box_code(TRANSCRIPTS / "spatial_rules_web.yaml", "srw_web"))

    def test_the_trace_capture_runs_step_ten(self):
        self.assertEqual(
            step_code("### 10. Ask why a strand is there"),
            box_code(TRANSCRIPTS / "docs_capture/spatial_rules_trace.yaml", "srw_trace"),
        )


if __name__ == "__main__":
    unittest.main()
