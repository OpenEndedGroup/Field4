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


def result(stamp: str, host: str = "h.local", think: float = 1.0, **steps: tuple[float, float]) -> dict:
    """A result whose summary holds one scenario `s` with the given (median, p90) per step."""
    return {
        "stamp": stamp, "reps": 100, "thinkSeconds": think, "coldRuns": 0, "loadAverageStartEnd": [1.0, 1.0],
        "git": {"commit": "c" * 40, "dirty": False},
        "machine": {"host": host, "cpu": "cpu", "cores": 10, "memBytes": 2**35, "os": "os"},
        "summary": {"s": {k.replace("_", " "): {"n": 100, "failed": 0, "wallMs": m, "wallP90Ms": p, "spansMs": {"kotlin.compile": m / 2}}
                          for k, (m, p) in steps.items()}},
    }


class RatchetTest(unittest.TestCase):
    def test_a_band_is_three_times_the_measured_spread_but_never_below_the_minimum(self) -> None:
        runs = [result("1", quiet=(100.0, 120.0), noisy=(100.0, 120.0)),
                result("2", quiet=(102.0, 121.0), noisy=(130.0, 200.0)),
                result("3", quiet=(101.0, 119.0), noisy=(100.0, 120.0))]
        steps = feel_bench.make_baseline(runs)["steps"]["s"]
        self.assertEqual(steps["quiet"]["wallMs"], 101.0)
        self.assertEqual(steps["quiet"]["tolMedianPct"], feel_bench.MIN_MEDIAN_PCT)
        # The noisy step's median run is 100; one run was 30% out, so its band is 90%.
        self.assertEqual(steps["noisy"]["tolMedianPct"], 90.0)
        self.assertGreater(steps["noisy"]["tolP90Pct"], feel_bench.MIN_P90_PCT)

    def test_check_flags_regressions_and_improvements_outside_the_band_only(self) -> None:
        baseline = feel_bench.make_baseline([result("1", same=(100.0, 120.0), slower=(100.0, 120.0), quicker=(100.0, 120.0), tiny=(10.0, 12.0))])
        head = result("2", same=(110.0, 140.0), slower=(130.0, 150.0), quicker=(70.0, 90.0), tiny=(14.0, 15.0))
        rows, problems = feel_bench.check_against(head, baseline)
        self.assertEqual(problems, [])
        verdicts = {r["step"]: r["verdict"] for r in rows}
        self.assertEqual(verdicts, {"same": "ok", "slower": "regressed", "quicker": "faster", "tiny": "ok"})

    def test_a_p90_regression_alone_is_a_regression(self) -> None:
        baseline = feel_bench.make_baseline([result("1", tail=(100.0, 120.0))])
        rows, _ = feel_bench.check_against(result("2", tail=(100.0, 200.0)), baseline)
        self.assertEqual(rows[0]["verdict"], "regressed")

    def test_another_host_or_think_time_is_not_comparable(self) -> None:
        baseline = feel_bench.make_baseline([result("1", a=(1.0, 2.0))])
        _, problems = feel_bench.check_against(result("2", host="other", think=0.0, a=(1.0, 2.0)), baseline)
        self.assertEqual(len(problems), 2)

    def test_a_missing_or_failed_step_fails_the_check(self) -> None:
        baseline = feel_bench.make_baseline([result("1", a=(1.0, 2.0), b=(1.0, 2.0))])
        head = result("2", a=(1.0, 2.0))
        head["summary"]["s"]["a"]["failed"] = 1
        verdicts = {r["step"]: r["verdict"] for r in feel_bench.check_against(head, baseline)[0]}
        self.assertEqual(verdicts, {"a": "failed", "b": "missing"})

    def test_one_baseline_per_host_and_think_time(self) -> None:
        with self.assertRaises(SystemExit):
            feel_bench.make_baseline([result("1", a=(1.0, 2.0)), result("2", host="other", a=(1.0, 2.0))])

    def test_the_committed_page_is_what_page_writes(self) -> None:
        self.assertEqual(feel_bench.BASELINE_PAGE.read_text(encoding="utf-8"), feel_bench.render_page(),
                         f"{feel_bench.BASELINE_PAGE.name} is generated: run `feel_bench.py page`")


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
