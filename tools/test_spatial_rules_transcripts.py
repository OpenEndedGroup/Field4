"""The spatial rules tutorial's code lives on its page; two transcripts carry copies of it.

`spatial_rules_web.yaml` is the starting point a reader opens, holding step 11's code, and
`docs_capture/spatial_rules_trace.yaml` holds step 10's so a hidden capture can press a strand.
A copy that drifts from its fence would show readers, or photograph, code the page does not show.
"""

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
    def test_the_starting_point_is_step_eleven(self):
        self.assertEqual(step_code("### 11. Make it bigger"), box_code(TRANSCRIPTS / "spatial_rules_web.yaml", "srw_web"))

    def test_the_trace_capture_runs_step_ten(self):
        self.assertEqual(
            step_code("### 10. Ask why a strand is there"),
            box_code(TRANSCRIPTS / "docs_capture/spatial_rules_trace.yaml", "srw_trace"),
        )


if __name__ == "__main__":
    unittest.main()
