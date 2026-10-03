"""Unit tests for `python/fieldcv`, the optional computer vision package for Python boxes.

Every test builds its own synthetic frames, so the expected answers are known exactly and no camera,
movie or network is involved. The suite needs the optional `cv` dependency group and skips, saying
so, without it:

    uv run --project . --group cv python -m unittest tools/test_fieldcv.py

The mediapipe tests also need the model files; they run only when those are already cached (see
`fieldcv.models`), because a unit test must not download 30 MB.
"""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "python"))

try:
    import cv2
    import numpy as np
    import fieldcv as cv4
    from fieldcv import models
except ImportError as e:  # the cv group is optional
    MISSING = f"fieldcv needs the optional cv dependency group ({e}); `uv sync --inexact --group cv`"
else:
    MISSING = None

W, H = 320, 240


def frames(images):
    return [cv4.Frame(img, i, i * 20) for i, img in enumerate(images)]


def textured(seed=1):
    rng = np.random.default_rng(seed)
    noise = rng.integers(0, 255, (H // 8, W // 8), np.uint8)
    return cv2.cvtColor(cv2.resize(noise, (W, H), interpolation=cv2.INTER_CUBIC), cv2.COLOR_GRAY2BGR)


def blank(value=200):
    return np.full((H, W, 3), value, np.uint8)


@unittest.skipIf(MISSING, MISSING or "")
class Foreground(unittest.TestCase):
    def test_luma_key_centroid_and_contour_are_normalized(self):
        img = blank()
        cv2.rectangle(img, (80, 60), (159, 119), (0, 0, 0), -1)  # 80x60 dark block
        out = cv4.LumaKey(contours=True)(frames([img])[0])
        np.testing.assert_allclose(out["centroid"], [119.5 / W, 89.5 / H], atol=1e-3)
        self.assertAlmostEqual(out["coverage"], 80 * 60 / (W * H), places=4)
        self.assertEqual(len(out["contours"]), 1)
        xs, ys = out["contours"][0][:, 0], out["contours"][0][:, 1]
        self.assertAlmostEqual(xs.min(), 80 / W, places=3)
        self.assertAlmostEqual(ys.max(), 119 / H, places=3)

    def test_empty_mask_gives_none_not_nan(self):
        out = cv4.LumaKey(contours=True)(frames([blank()])[0])
        self.assertIsNone(out["centroid"])
        self.assertEqual(out["contours"], [])
        self.assertNotIn("NaN", json.dumps(cv4.jsonable(out)))

    def test_min_area_is_a_fraction_of_the_frame(self):
        img = blank()
        cv2.rectangle(img, (10, 10), (13, 13), (0, 0, 0), -1)  # 16 px, 0.02% of the frame
        cv2.rectangle(img, (100, 100), (199, 199), (0, 0, 0), -1)
        self.assertEqual(len(cv4.LumaKey(contours=True, min_area=0.001)(frames([img])[0])["contours"]), 1)

    def test_background_finds_what_arrives(self):
        bg = cv4.Background("MOG2", contours=True)
        still = textured()
        for f in frames([still] * 30):
            bg(f)
        moved = still.copy()
        cv2.rectangle(moved, (200, 40), (279, 119), (255, 255, 255), -1)
        out = bg(cv4.Frame(moved, 30, 600))
        np.testing.assert_allclose(out["centroid"], [239.5 / W, 79.5 / H], atol=0.03)
        self.assertGreater(out["coverage"], 0.05)


@unittest.skipIf(MISSING, MISSING or "")
class Motion(unittest.TestCase):
    def shifted(self, n=6, dx=3):
        base = textured()
        m = lambda k: np.float32([[1, 0, k * dx], [0, 1, 0]])
        return frames([cv2.warpAffine(base, m(k), (W, H), borderMode=cv2.BORDER_REFLECT) for k in range(n)])

    def test_flow_sees_rightward_motion(self):
        flow = cv4.Flow()
        for f in self.shifted():
            out = flow(f)
        self.assertEqual(out["flow"].shape, (H, W, 2))
        self.assertAlmostEqual(out["mean_motion"][0] * W, 3.0, delta=0.6)
        self.assertAlmostEqual(out["mean_motion"][1] * H, 0.0, delta=0.4)

    def test_tracks_follow_the_motion_and_keep_ids(self):
        tracks = cv4.Tracks(length=4)
        first = tracks(self.shifted()[0])
        self.assertGreater(len(first["tracks"]), 10)
        for f in self.shifted()[1:]:
            out = tracks(f)
        long = [t for t in out["tracks"] if len(t) == 4]
        self.assertGreater(len(long), 10)
        dx = np.median([(t[-1, 0] - t[0, 0]) * W for t in long])
        self.assertAlmostEqual(dx, 9.0, delta=1.0)  # three steps of 3 px
        self.assertEqual(len(out["ids"]), len(set(out["ids"].tolist())))
        self.assertTrue(set(first["ids"].tolist()) & set(out["ids"].tolist()))


@unittest.skipIf(MISSING, MISSING or "")
class Shapes(unittest.TestCase):
    def test_lines_find_a_drawn_segment(self):
        img = blank(0)
        cv2.line(img, (40, 200), (280, 40), (255, 255, 255), 3)
        seg = cv4.Lines()(frames([img])[0])["segments"]
        self.assertGreaterEqual(len(seg), 1)
        longest = max(seg, key=lambda s: np.hypot(*((s[1] - s[0]) * (W, H))))
        ends = sorted(map(tuple, np.round(longest * (W, H))))
        np.testing.assert_allclose(ends, [(40, 200), (280, 40)], atol=6)

    def test_circles_find_a_drawn_circle(self):
        img = blank(0)
        cv2.circle(img, (160, 120), 30, (255, 255, 255), 3)
        c = cv4.Circles()(frames([img])[0])["circles"]
        self.assertGreaterEqual(len(c), 1)
        np.testing.assert_allclose(c[0] * (W, H, W), [160, 120, 30], atol=3)


@unittest.skipIf(MISSING, MISSING or "")
class Sources(unittest.TestCase):
    def test_images_are_sorted_and_timed_by_fps(self):
        with tempfile.TemporaryDirectory() as d:
            for i in (2, 0, 1):
                cv2.imwrite(f"{d}/f_{i}.png", blank(i * 50))
            got = list(cv4.Images(f"{d}/f_*.png", fps=25, shrink=0.5))
        self.assertEqual([f.image[0, 0, 0] for f in got], [0, 50, 100])
        self.assertEqual([f.time_ms for f in got], [0, 40, 80])
        self.assertEqual(got[0].image.shape, (H // 2, W // 2, 3))

    def test_cli_writes_one_json_object_per_frame(self):
        with tempfile.TemporaryDirectory() as d:
            for i in range(3):
                img = blank()
                cv2.rectangle(img, (20 + 10 * i, 20), (60 + 10 * i, 60), (0, 0, 0), -1)
                cv2.imwrite(f"{d}/f_{i}.png", img)
            run = subprocess.run([sys.executable, "-m", "fieldcv", "--source", f"{d}/f_*.png", "--shrink", "1",
                                  "--luma", "--contours", "--tracks"], cwd=ROOT / "python",
                                 capture_output=True, text=True, check=True)
        rows = [json.loads(line) for line in run.stdout.splitlines()]
        self.assertEqual([r["index"] for r in rows], [0, 1, 2])
        self.assertEqual(len(rows[2]["contours"]), 1)
        self.assertIn("tracks", rows[0])
        self.assertNotIn("mask", rows[0])


@unittest.skipIf(MISSING, MISSING or "")
class Samples(unittest.TestCase):
    def test_every_sample_decodes_and_loops(self):
        for name in cv4.SAMPLES:
            with self.subTest(name):
                src = cv4.sample(name)
                frames_seen = [next(src) for _ in range(12)]  # muybridge has 9 frames, so this loops
                self.assertEqual(frames_seen[0].image.ndim, 3)
                self.assertEqual([f.index for f in frames_seen], list(range(12)))
                src.close()

    def test_pipeline_draw_paints_every_detector(self):
        p = cv4.Pipeline(cv4.sample("machines"), cv4.Tracks(), cv4.Flow())
        p.step()
        r = p.step()
        drawn = p.draw(r)
        self.assertEqual(drawn.shape, r["frame"].image.shape)
        self.assertGreater(int(np.abs(drawn.astype(int) - r["frame"].image).sum()), 0)
        p.close()


def _cached(key):
    return MISSING is None and (models.model_dir() / models.MODELS[key].filename).exists()


@unittest.skipIf(MISSING, MISSING or "")
class Landmarks(unittest.TestCase):
    """mediapipe on frames with nobody in them: the shapes of "nothing found" are part of the API."""

    @unittest.skipUnless(_cached("hand") if not MISSING else False, "hand model not cached")
    def test_no_hands_is_an_empty_but_shaped_array(self):
        out = cv4.Hands()(frames([blank()])[0])
        self.assertEqual(out["hands"].shape, (0, 21, 3))
        self.assertEqual(out["handedness"], [])

    @unittest.skipUnless(_cached("pose_lite") if not MISSING else False, "pose model not cached")
    def test_no_pose_is_an_empty_but_shaped_array(self):
        p = cv4.Pose(model="lite")
        out = p(frames([blank()])[0])
        self.assertEqual(out["poses"].shape, (0, 33, 3))
        self.assertEqual(out["visibility"].shape, (0, 33))
        p.close()

    def test_edges_pairs_points_by_connection(self):
        pts = np.arange(2 * 4 * 3, dtype=np.float32).reshape(2, 4, 3)
        e = cv4.edges(pts, np.array([[0, 1], [2, 3]]))
        self.assertEqual(len(e), 4)
        np.testing.assert_array_equal(e[1], pts[0, [2, 3], :2])


if __name__ == "__main__":
    unittest.main()
