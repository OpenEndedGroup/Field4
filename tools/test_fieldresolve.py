"""Tests for `python/fieldresolve`: timecode arithmetic and marker placement, against a fake Resolve.

The real API is only reachable with Resolve running, so these tests use `fieldresolve._fake`, a
stand-in for what `DaVinciResolveScript.scriptapp("Resolve")` returns; its docstring describes the
fixture the expected values below come from.
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "python"))

import fieldresolve as fr  # noqa: E402
from fieldresolve import __main__ as worker  # noqa: E402
from fieldresolve import _fake  # noqa: E402


def install():
    fr._apps = {"": _fake.scriptapp()}


class TimecodeTests(unittest.TestCase):
    def test_non_drop(self):
        self.assertEqual(fr.frames_to_timecode(86400, 24), "01:00:00:00")
        self.assertEqual(fr.frames_to_timecode(86400 + 23, 23.976), "01:00:00:23")
        self.assertEqual(fr.timecode_to_frames("01:00:00:00", 25), 90000)

    def test_drop_frame_labels(self):
        # 29.97 DF: ;00 and ;01 are skipped at every minute except each tenth.
        self.assertEqual(fr.frames_to_timecode(1799, 29.97, True), "00:00:59;29")
        self.assertEqual(fr.frames_to_timecode(1800, 29.97, True), "00:01:00;02")
        self.assertEqual(fr.frames_to_timecode(17982, 29.97, True), "00:10:00;00")
        self.assertEqual(fr.frames_to_timecode(107892, 29.97, True), "01:00:00;00")
        self.assertEqual(fr.frames_to_timecode(215784, 59.94, True), "01:00:00;00")

    def test_round_trip(self):
        for fps, drop in [(24, False), (23.976, False), (25, False), (29.97, True), (30, False), (59.94, True)]:
            for frame in [0, 1, 1799, 1800, 1801, 17981, 17982, 107892, 123457]:
                tc = fr.frames_to_timecode(frame, fps, drop)
                self.assertEqual(fr.timecode_to_frames(tc, fps, drop), frame, (fps, drop, tc))

    def test_drop_frame_needs_a_fractional_rate(self):
        self.assertEqual(fr.frames_to_timecode(1800, 30, True), "00:01:00:00")


class MarkerTests(unittest.TestCase):
    def setUp(self):
        install()

    def tearDown(self):
        fr._apps = {}

    def test_current_timeline_markers_sorted_and_placed(self):
        ms = fr.markers()
        self.assertEqual([m["name"] for m in ms], ["Marker 1", "Marker 2"])
        self.assertEqual(ms[1]["frame"], 48)
        self.assertEqual(ms[1]["recordFrame"], 86448)
        self.assertEqual(ms[1]["timecode"], "01:00:02:00")
        self.assertAlmostEqual(ms[1]["seconds"], 2.0)
        self.assertEqual(ms[0]["duration"], 24)
        self.assertEqual(ms[0]["customData"], "x")

    def test_timeline_by_name_uses_its_own_rate(self):
        ms = fr.markers("Edit DF")
        self.assertEqual(ms[0]["timecode"], "01:01:00;02")
        t = fr.timeline("Edit DF")
        self.assertFalse(t["current"])
        self.assertTrue(t["dropFrame"])
        self.assertEqual(t["startTimecode"], "01:00:00;00")

    def test_missing_timeline_names_the_ones_there(self):
        with self.assertRaises(KeyError) as e:
            fr.markers("Nope")
        self.assertIn("Edit DF", str(e.exception))
        reply = worker.answer({"id": 7, "op": "markers", "timeline": "Nope"})
        self.assertEqual((reply["ok"], reply["kind"], reply["id"]), (False, "missing", 7))

    def test_timelines_lists_all_and_marks_current(self):
        ts = fr.timelines()
        self.assertEqual([(t["name"], t["index"], t["current"]) for t in ts], [("Edit 1", 1, True), ("Edit DF", 2, False)])

    def test_clip_markers_on_the_timeline_and_trimmed_ones_dropped(self):
        ms = fr.clip_markers()
        self.assertEqual(len(ms), 1)
        m = ms[0]
        # clip starts at record 86500, its visible part starts 1000 frames into the source; source frame 1010
        self.assertEqual((m["recordFrame"], m["frame"], m["clip"], m["clipFrame"], m["trackType"], m["track"]), (86510, 110, "A001.mov", 1010, "video", 1))

    def test_playhead(self):
        p = fr.playhead()
        self.assertEqual((p["frame"], p["recordFrame"], p["timeline"]), (24, 86424, "Edit 1"))
        with self.assertRaises(ValueError):
            fr.playhead("Edit DF")

    def test_a_host_gets_its_own_connection(self):
        import os
        os.environ["FIELDRESOLVE_FAKE"] = "1"
        try:
            self.assertEqual(fr.status(host="macbook-pro-2")["project"], "Fake Project on macbook-pro-2")
            self.assertEqual(fr.status()["project"], "Fake Project")
            reply = worker.answer({"id": 2, "op": "markers", "host": "macbook-pro-2"})
            self.assertEqual([m["name"] for m in reply["result"]], ["Marker 1", "Marker 2"])
        finally:
            del os.environ["FIELDRESOLVE_FAKE"]

    def test_an_unreachable_host_fails_fast_with_advice(self):
        import socket
        import time
        probe = socket.socket()
        probe.bind(("127.0.0.1", 0))
        closed = probe.getsockname()[1]
        probe.close()  # a port that was free a moment ago, so nothing listens on it
        t = time.time()
        with self.assertRaises(fr.ResolveUnavailable) as e:
            fr._check_reachable("127.0.0.1", timeout=2, port=closed)
        self.assertIn("External scripting using > Network", str(e.exception))
        self.assertLess(time.time() - t, 3)

    def test_worker_status(self):
        reply = worker.answer({"id": 1, "op": "status"})
        self.assertTrue(reply["ok"])
        self.assertEqual(reply["result"]["timeline"], "Edit 1")
        self.assertEqual(reply["result"]["timelineCount"], 2)


if __name__ == "__main__":
    unittest.main()
