"""Regression guard for the docs-capture freshness classifier.

Every number in here is a **real measurement** from `.field-backdoor/docs-audit` runs on
2026-07-29 (recorded in `dev_docs/docs_capture_staleness.md`), not an invented fixture. Three of
these cases were misclassified by the classifier's first draft, which is exactly why they are here:
a verification tool that silently changes its mind about what "stale" means is worse than none.

Run with:  uv run --group docs python -m unittest tools.test_docs_capture_audit
"""

import unittest

from tools.docs_capture_audit import (
    DEFAULT_IGNORE_DELTA,
    DEFAULT_JITTER_DELTA,
    DEFAULT_JITTER_PX_PCT,
    MIN_SIGNAL_PX,
    Delta,
    classify,
    compare_images,
    failing_exit_code,
    fold_runs,
)


def pixel(pct: float, max_delta: int, *, total_px: int = 1_800_000) -> Delta:
    """A pixel delta as `compare_images` would report it: `pct` already excludes the sub-perceptual
    floor, and the above-floor pixel count follows from it."""
    return Delta(
        "pixel",
        changed_px_pct=pct,
        changed_px=int(round(total_px * pct / 100.0)),
        all_changed_px_pct=pct,
        max_delta=max_delta,
    )


def verdict(vs_head: Delta, *, other=None, self_delta=None) -> str:
    return classify(
        vs_head=vs_head,
        other_vs_head=other,
        self_delta=self_delta,
        px_pct=DEFAULT_JITTER_PX_PCT,
        max_delta=DEFAULT_JITTER_DELTA,
    )[0]


class NoiseBandTests(unittest.TestCase):
    def test_measured_antialiasing_jitter_is_not_a_finding(self) -> None:
        # timeseries_examples, unperturbed, against a committed asset known to be current.
        self.assertEqual(verdict(pixel(0.0066, 13), self_delta=pixel(0.0036, 2)), "jitter")
        self.assertEqual(verdict(pixel(0.0071, 18), self_delta=pixel(0.0050, 6)), "jitter")

    def test_tiny_area_but_low_delta_is_still_jitter(self) -> None:
        # graph_query/predicates.png. The first draft's "<=0.02% of pixels AND <=D19" rule called
        # this stale purely for crossing the area threshold, at a maximum channel delta of TWO.
        self.assertEqual(verdict(pixel(0.0202, 2), self_delta=pixel(0.0067, 1)), "jitter")

    def test_tiny_area_with_high_delta_is_not_excused(self) -> None:
        # The converse, and the reason max-delta is the discriminator: one label drawing
        # differently touches almost no pixels but at full contrast.
        self.assertEqual(
            verdict(pixel(0.004, 240), other=pixel(0.004, 240), self_delta=pixel(0.0, 0)),
            "stale",
        )


class StaleVersusNondeterministicTests(unittest.TestCase):
    def test_reproducible_re_render_is_stale(self) -> None:
        # graph_query/lenses.png and paramset.png as found on this branch's base.
        self.assertEqual(
            verdict(pixel(2.2471, 249), other=pixel(2.2474, 249), self_delta=pixel(0.0007, 1)),
            "stale",
        )
        self.assertEqual(
            verdict(pixel(2.9010, 250), other=pixel(2.9010, 250), self_delta=pixel(0.0153, 5)),
            "stale",
        )

    def test_stale_and_slightly_nondeterministic_is_still_stale(self) -> None:
        # timeseries_examples/filter_chains.png with EMAFilter perturbed: 1.06% against the
        # committed asset, 0.24% between the two audit runs. A flat "unstable => nondeterministic"
        # rule reported this as nondeterministic and buried the staleness the tool exists to find.
        self.assertEqual(
            verdict(pixel(1.0569, 238), other=pixel(1.05, 238), self_delta=pixel(0.2407, 60)),
            "stale",
        )

    def test_equal_drift_and_instability_is_nondeterministic(self) -> None:
        # The genuinely nondeterministic plot class from dev_docs/docs_capture_staleness.md 2:
        # 0.25-1.5% of pixels at D~247, and it does not reproduce itself either.
        self.assertEqual(
            verdict(pixel(1.4, 247), other=pixel(1.3, 247), self_delta=pixel(1.35, 247)),
            "nondeterministic",
        )

    def test_a_run_that_matches_head_means_not_stale(self) -> None:
        # graph_query/pathset.png: run 1 matched the committed asset within the noise band, run 2
        # differed from run 1 by 1.63%. The published figure IS one of the valid renders.
        self.assertEqual(
            verdict(pixel(0.0096, 5), other=pixel(1.63, 249), self_delta=pixel(1.6319, 249)),
            "unstable",
        )

    def test_single_run_cannot_confirm(self) -> None:
        self.assertEqual(verdict(pixel(2.25, 249)), "suspect")


class OtherSignalTests(unittest.TestCase):
    def test_output_height_change_is_its_own_signal(self) -> None:
        size = Delta("size", old_size=(940, 918), new_size=(940, 800))
        self.assertEqual(verdict(size, other=size, self_delta=Delta("identical")), "stale-size")
        self.assertEqual(verdict(size, other=size, self_delta=size), "nondeterministic-size")

    def test_deterministic_text_output_moving_is_stale(self) -> None:
        text = Delta("text", text_lines_changed=42)
        self.assertEqual(verdict(text, other=text, self_delta=Delta("identical")), "stale-text")

    def test_video_agreeing_with_itself_and_not_with_head_is_stale(self) -> None:
        binary = Delta("binary", old_bytes=1000, new_bytes=1200)
        self.assertEqual(verdict(binary, other=binary, self_delta=Delta("identical")), "stale-binary")

    def test_uncommitted_asset_is_new_not_stale(self) -> None:
        self.assertEqual(verdict(Delta("missing")), "new")


class SubPerceptualFloorTests(unittest.TestCase):
    """The functions_examples sweep's 25-file "5.08% changed" cluster: a whole-image dither of +-1."""

    def test_whole_image_plus_minus_one_dither_is_not_a_difference(self) -> None:
        from PIL import Image

        base = Image.new("RGB", (200, 200), (40, 40, 40))
        shifted = Image.new("RGB", (200, 200), (41, 41, 41))
        buffers = []
        for image in (base, shifted):
            import io

            handle = io.BytesIO()
            image.save(handle, format="PNG")
            buffers.append(handle.getvalue())
        delta = compare_images(buffers[0], buffers[1], ignore_delta=DEFAULT_IGNORE_DELTA)
        self.assertEqual(delta.max_delta, 1)
        self.assertAlmostEqual(delta.all_changed_px_pct, 100.0)  # every pixel moved...
        self.assertEqual(delta.changed_px, 0)  # ...and none of it counts
        self.assertTrue(delta.within(px_pct=DEFAULT_JITTER_PX_PCT, max_delta=DEFAULT_JITTER_DELTA))

    def test_a_real_re_render_survives_the_floor(self) -> None:
        from PIL import Image

        base = Image.new("RGB", (200, 200), (0, 0, 0))
        drawn = Image.new("RGB", (200, 200), (0, 0, 0))
        for x in range(60):
            for y in range(60):
                drawn.putpixel((x, y), (250, 250, 250))
        buffers = []
        for image in (base, drawn):
            import io

            handle = io.BytesIO()
            image.save(handle, format="PNG")
            buffers.append(handle.getvalue())
        delta = compare_images(buffers[0], buffers[1])
        self.assertEqual(delta.changed_px, 3600)
        self.assertEqual(delta.max_delta, 250)
        self.assertFalse(delta.within(px_pct=DEFAULT_JITTER_PX_PCT, max_delta=DEFAULT_JITTER_DELTA))

    def test_a_handful_of_pixels_is_not_a_changed_figure(self) -> None:
        stray = Delta("pixel", changed_px_pct=0.0001, changed_px=MIN_SIGNAL_PX - 1, max_delta=250)
        self.assertTrue(stray.within(px_pct=DEFAULT_JITTER_PX_PCT, max_delta=DEFAULT_JITTER_DELTA))


class DeltaTests(unittest.TestCase):
    def test_within_uses_max_delta_as_the_discriminator(self) -> None:
        self.assertTrue(pixel(0.9, 19).within(px_pct=DEFAULT_JITTER_PX_PCT, max_delta=DEFAULT_JITTER_DELTA))
        self.assertFalse(pixel(0.001, 20).within(px_pct=DEFAULT_JITTER_PX_PCT, max_delta=DEFAULT_JITTER_DELTA))

    def test_area_guard_still_catches_a_whole_image_low_magnitude_shift(self) -> None:
        self.assertFalse(pixel(60.0, 6).within(px_pct=DEFAULT_JITTER_PX_PCT, max_delta=DEFAULT_JITTER_DELTA))

    def test_severity_orders_the_run_that_best_matches_head_first(self) -> None:
        self.assertLess(pixel(0.0096, 5).severity(), pixel(1.63, 249).severity())


class ThreeRunFoldTests(unittest.TestCase):
    """`--repeat 3` used to cost a whole capture pass and change nothing, because the report read
    runs 1 and 2 only. Numbers below are the real 3-run audit of `interpolants_examples` +
    `correlations_examples`, `.field-backdoor/docs-audit/20260729-150636`, 2026-07-29."""

    @staticmethod
    def dims(old, new) -> Delta:
        return Delta("size", old_size=old, new_size=new)

    def fold_verdict(self, vs_head, pairwise) -> str:
        drift, runner_up, worst = fold_runs(vs_head=vs_head, pairwise=pairwise)
        return classify(
            vs_head=drift,
            other_vs_head=runner_up,
            self_delta=worst,
            px_pct=DEFAULT_JITTER_PX_PCT,
            max_delta=DEFAULT_JITTER_DELTA,
        )[0]

    def test_drift_is_the_best_of_all_three_runs_not_of_the_first_two(self) -> None:
        # smooth_duration.png: run 1 changed height, runs 2 and 3 kept HEAD's height and moved
        # 2.1239% / 2.2718%. Drift must be run 2's — the reading friendliest to the committed
        # asset — 2.1239%, not the 2.3506% a two-run read reported for the same figure.
        drift, _, worst = fold_runs(
            vs_head=[self.dims((1144, 772), (1144, 918)), pixel(2.1239, 238), pixel(2.2718, 238)],
            pairwise=[
                self.dims((1144, 918), (1144, 772)),
                self.dims((1144, 918), (1144, 772)),
                pixel(0.3524, 238),
            ],
        )
        self.assertAlmostEqual(drift.changed_px_pct, 2.1239)
        # …and the instability is the WORST pair — the size change, not the 0.35% pixel pair.
        self.assertEqual(worst.kind, "size")

    def test_a_stable_size_change_over_three_runs_is_stale_size(self) -> None:
        # keyframe_morph.png: HEAD is 918 px tall, all three runs came out 796 and agreed with each
        # other to maxΔ2. In the earlier two-run sweep the pair happened to disagree at maxΔ60 and
        # it was reported `nondeterministic-size`; a third run settles it.
        self.assertEqual(
            self.fold_verdict(
                [self.dims((1144, 918), (1144, 796))] * 3,
                [pixel(0.0, 2), pixel(0.0, 1), pixel(0.0, 1)],
            ),
            "stale-size",
        )

    def test_a_third_run_can_overturn_a_two_run_stale(self) -> None:
        # The case that justifies the fold: two runs agreeing with each other reads `stale`, and a
        # third run that reproduces the committed asset makes it `unstable` instead.
        pair = [pixel(2.20, 249), pixel(2.20, 249)]
        self.assertEqual(self.fold_verdict(pair, [pixel(0.0, 1)]), "stale")
        self.assertEqual(
            self.fold_verdict(
                [*pair, pixel(0.004, 9)],
                [pixel(0.0, 1), pixel(2.19, 249), pixel(2.19, 249)],
            ),
            "unstable",
        )

    def test_one_run_still_folds_to_the_single_run_verdict(self) -> None:
        drift, runner_up, worst = fold_runs(vs_head=[pixel(2.2, 249)], pairwise=[])
        self.assertAlmostEqual(drift.changed_px_pct, 2.2)
        self.assertIsNone(runner_up)
        self.assertIsNone(worst)
        self.assertEqual(self.fold_verdict([pixel(2.2, 249)], []), "suspect")


class ExitCodeTests(unittest.TestCase):
    """The gate. Found by driving the tool at its edges rather than by reading it."""

    def test_a_capture_that_could_not_be_measured_fails_the_gate(self) -> None:
        # unknown-failed means the capture failed and the committed asset is untouched: the run
        # learned nothing. Exit 0 there is the tool answering "still true?" without having looked.
        self.assertEqual(failing_exit_code({"counts": {"unknown-failed": 1, "jitter": 4}}), 1)

    def test_stale_and_suspect_fail_the_gate(self) -> None:
        for verdict in ("stale", "stale-size", "stale-text", "stale-binary", "suspect"):
            self.assertEqual(failing_exit_code({"counts": {verdict: 1}}), 1, verdict)

    def test_clean_verdicts_pass(self) -> None:
        for verdict in ("identical", "jitter", "unstable", "nondeterministic",
                        "nondeterministic-size", "nondeterministic-binary", "new"):
            self.assertEqual(failing_exit_code({"counts": {verdict: 3}}), 0, verdict)


if __name__ == "__main__":
    unittest.main()
