"""feel_bench.py's summary arithmetic, and that the span names its write-up explains exist in source.

No JVM: `summarize` is exercised on hand-made steps, and the span-name check reads
`dev_docs/testing/FEEL_BENCHMARKS.md` and the Kotlin sources, so a renamed `LatencySpans` site
cannot leave the page describing a span the server no longer records.
"""

from __future__ import annotations

import re
import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

import feel_bench  # noqa: E402
from feel_bench import Step, summarize  # noqa: E402

REPO = TOOLS.parent
DOC = REPO / "dev_docs/testing/FEEL_BENCHMARKS.md"


def step(phase: str, label: str, wall: float, ok: bool = True, **spans: float) -> Step:
    return Step("s", label, phase, ok, wall, None, {k.replace("_", "."): {"ms": v, "count": 1, "maxMs": v} for k, v in spans.items()})


class SummarizeTest(unittest.TestCase):
    def test_single_phases_keep_their_label_and_repeats_group_by_phase(self) -> None:
        summary = summarize([
            step("first", "first run", 1800.0, kotlin_compile=1600.0),
            step("warm", "warm 1", 400.0, kotlin_compile=300.0),
            step("warm", "warm 2", 420.0, kotlin_compile=320.0),
            step("warm", "warm 3", 500.0),
        ])["s"]
        self.assertEqual(set(summary), {"first run", "warm"})
        self.assertEqual(summary["warm"]["wallMs"], 420.0)
        self.assertEqual(summary["warm"]["wallP90Ms"], 500.0)
        # A step without the span counts as zero, so the median is over every successful step.
        self.assertEqual(summary["warm"]["spansMs"]["kotlin.compile"], 300.0)

    def test_failed_steps_are_counted_and_left_out_of_timings(self) -> None:
        summary = summarize([step("edit", "e1", 10.0), step("edit", "e2", 9999.0, ok=False)])["s"]["edit"]
        self.assertEqual((summary["n"], summary["failed"], summary["wallMs"]), (2, 1, 10.0))

    def test_all_failed_has_no_timings(self) -> None:
        summary = summarize([step("first", "live", 5000.0, ok=False)])["s"]["live"]
        self.assertNotIn("wallMs", summary)


class SpanNamesTest(unittest.TestCase):
    def test_documented_span_names_are_recorded_in_source(self) -> None:
        recorded: set[str] = set()
        for path in (REPO / "src/jvmMain/kotlin").rglob("*.kt"):
            text = path.read_text(encoding="utf-8", errors="replace")
            if "LatencySpans" in text:
                recorded.update(re.findall(r'(?:LatencySpans\.(?:time|record)|verdictTimed)\(\s*"([a-zA-Z.]+)"', text))
        documented = set(re.findall(r"^\| `([a-zA-Z.]+)` \|", DOC.read_text(encoding="utf-8"), re.M))
        self.assertTrue(documented, f"no span table found in {DOC}")
        self.assertEqual(documented - recorded, set(), "documented spans no source records")
        self.assertEqual(recorded - documented, set(), f"recorded spans missing from {DOC.name}")

    def test_scenarios_are_documented(self) -> None:
        text = DOC.read_text(encoding="utf-8")
        for name in feel_bench.SCENARIOS:
            self.assertIn(f"`{name}`", text)


if __name__ == "__main__":
    unittest.main()
