import copy
import json
import math
from pathlib import Path
import struct
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "python"))
from field_motion.recording import Recording, RecordingWriter, decode_frame, encode_frame, skeleton_hash, validate_skeleton


def skeleton():
    s = dict(id="fixture.two-joints", version="1", names=["root", "tip"], parents=[-1, 0],
             restTranslations=[0., 0., 0., 0., 1., 0.], restRotations=[0., 0., 0., 1.] * 2, bodies=[],
             coordinates=dict(sourceUnits="m", sourceHandedness="right", sourceUp="+Y", sourceForward="+Z",
                              sourceToField=[1., 0., 0., 0., 1., 0., 0., 0., 1.], metresPerUnit=1., common="RH_Y_UP_METRES_XYZW"))
    s["hash"] = skeleton_hash(s)
    return s


def frame(i=0, episode=0):
    p = dict(root=[i * .1, 0., 0.], localRotations=[0., 0., 0., 1.] * 2,
             worldPositions=[i * .1, 0., 0., i * .1, 1., 0.])
    return dict(time=i / 20, index=i, episode=episode, revision=i, executed=p, intended=copy.deepcopy(p),
                contacts=[.25, .75], contactProvenance="fixture", diagnostics={"test": 1.5})


class RecordingTests(unittest.TestCase):
    def test_positions_only_reference_preserves_unavailable_rotations(self):
        f = frame()
        f['intended']['localRotations'] = []
        decoded = decode_frame(encode_frame(f), 2)
        self.assertEqual([], decoded['intended']['localRotations'])
        self.assertEqual(f['intended']['worldPositions'], decoded['intended']['worldPositions'])
        self.assertEqual(f['executed']['localRotations'], decoded['executed']['localRotations'])

    def test_cross_language_hash_fixture(self):
        # Same exact metadata/hash is checked by Kotlin, including float spellings and string separators.
        self.assertEqual("3b8ec9b7fd3af8804c638b5df534f9ed705bd2176fa4f476516cdba1af093acb", skeleton()["hash"])

    def test_chunks_and_events_replay_without_models(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "recording"
            with RecordingWriter(target, skeleton(), {"backend": "fixture", "seed": 42}, chunk_frames=2) as out:
                out.event(dict(type="prompt", requestedTime=0., appliedTime=.05, revision=1))
                for i in range(5):
                    out.append(frame(i, int(i >= 3)))
            r = Recording(target)
            self.assertEqual(3, len(r.metadata["chunks"]))
            frames = list(r.frames())
            self.assertEqual([0, 1, 2, 3, 4], [f["index"] for f in frames])
            self.assertEqual([0, 0, 0, 1, 1], [f["episode"] for f in frames])
            self.assertAlmostEqual(.4, frames[-1]["executed"]["root"][0], places=6)
            self.assertEqual(.05, list(r.events())[0]["appliedTime"])
            self.assertEqual(encode_frame(frames[-1]), encode_frame(decode_frame(encode_frame(frames[-1]), 2)))
            with self.assertRaises(FileExistsError):
                RecordingWriter(target, skeleton(), {})

    def test_failure_is_not_a_complete_recording(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "recording"
            with self.assertRaises(RuntimeError):
                with RecordingWriter(target, skeleton(), {}) as out:
                    out.append(frame())
                    raise RuntimeError("worker died")
            self.assertTrue((target / "incomplete.json").is_file())
            with self.assertRaises(FileNotFoundError):
                Recording(target)

    def test_topology_hash_and_quaternion_validation(self):
        for key, value in [("parents", [-1, 1]), ("names", ["root", "root"]),
                           ("restRotations", [0.] * 8), ("hash", "changed")]:
            s = skeleton()
            s[key] = value
            with self.assertRaises(ValueError):
                validate_skeleton(s)

    def test_nonfinite_truncated_oversized_and_path_escape_rejected(self):
        bad = frame()
        bad["executed"]["root"][0] = math.nan
        with self.assertRaises(ValueError):
            encode_frame(bad)
        data = encode_frame(frame())
        for broken in (data[:-1], struct.pack(">I", 2**30) + data[4:]):
            with self.assertRaises(ValueError):
                decode_frame(broken, 2)
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "recording"
            with RecordingWriter(target, skeleton(), {}) as out:
                out.append(frame())
            p = target / "manifest.json"
            manifest = json.loads(p.read_text())
            manifest["chunks"] = ["../escape.bin"]
            p.write_text(json.dumps(manifest))
            with self.assertRaises(ValueError):
                Recording(target)

    def test_out_of_order_and_old_episode_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            with RecordingWriter(Path(tmp) / "recording", skeleton(), {}) as out:
                out.append(frame(1, 1))
                with self.assertRaises(ValueError):
                    out.append(frame(1, 1))
                with self.assertRaises(ValueError):
                    out.append(frame(2, 0))


if __name__ == "__main__":
    unittest.main()
