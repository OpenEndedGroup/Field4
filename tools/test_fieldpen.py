"""Unit tests for `python/fieldpen`, the optional training half of Field's learned pen.

The Kotlin side (`afield.ai.learning.pen`, `PenModelTest`) is pinned to this package's forward pass;
this suite pins the package itself: sessions, forks, training and the export Kotlin reads. It needs
the optional `pen` dependency group (torch) and skips, saying so, without it:

    uv run --project . --group pen python -m unittest tools/test_fieldpen.py
"""

from __future__ import annotations

import base64
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "python"))

try:
    import numpy as np
    import torch
    from fieldpen import Config, Controls, HandwritingModel, Session, export_for_field, load_model, save_model
    from fieldpen.data import collate, polyline_deltas
except ImportError as e:  # the pen group is optional
    MISSING = f"fieldpen needs the optional `pen` dependency group ({e}); `uv sync --inexact --group pen`"
else:
    MISSING = None
    torch.set_num_threads(1)


@unittest.skipIf(MISSING, MISSING or "")
class SessionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.model = load_model()  # the bundled Distill weights

    def test_forks_and_branches_do_not_advance_the_pen(self):
        s = Session(self.model, 42)
        s.generate(16)
        copy = s.clone()
        b = s.branches(16, 12, seed=88)
        self.assertEqual(b, s.branches(16, 12, seed=88))
        self.assertEqual((len(b["points"]), len(b["points"][0])), (16, 12))
        self.assertEqual(s.generate(32), copy.generate(32))

    def test_replaying_generated_deltas_reproduces_the_state(self):
        start = Session(self.model, 7)
        generated = start.generate(32)
        replay = Session(self.model, 98)
        replay.consume(generated["deltas"])
        torch.testing.assert_close(start.state[0], replay.state[0])
        torch.testing.assert_close(start.state[1], replay.state[1])

    def test_lift_belongs_to_the_endpoint(self):
        s = Session(self.model)
        a = s.consume([[1, 0, 0], [1, 0, 1]])["points"]
        b = s.consume([[5, 0, 0], [1, 0, 0]])["points"]
        self.assertEqual(a + b, [[1, 0, 1], [2, 0, 0], [7, 0, 1], [8, 0, 0]])
        d = polyline_deltas([[[0, 0], [1, 0]], [[6, 0], [7, 0]]])
        np.testing.assert_array_equal(d, [[0, 0, 0], [1, 0, 1], [5, 0, 0], [1, 0, 1]])

    def test_controls_reject_nonsense(self):
        with self.assertRaises(ValueError):
            Controls(noise_scale=float("nan"))


@unittest.skipIf(MISSING, MISSING or "")
class TrainingTest(unittest.TestCase):
    def test_training_reduces_loss_and_both_formats_round_trip(self):
        torch.manual_seed(13)
        m = HandwritingModel(Config(12, 2, 2))
        opt = torch.optim.Adam(m.parameters(), lr=0.01)
        batch = [torch.tensor([[0.1, 0.02, 0.0]] * 7 + [[0.1, 0.02, 1.0]]),
                 torch.tensor([[0.1, 0.02, 0.0]] * 3 + [[0.1, 0.02, 1.0]])]
        x, y, mask = collate(batch)
        losses = []
        for _ in range(50):
            raw, _ = m(x)
            loss = m.nll(raw, y)[mask].mean()
            opt.zero_grad()
            loss.backward()
            opt.step()
            losses.append(loss.item())
        self.assertLess(losses[-1], losses[0] - 1)
        with tempfile.TemporaryDirectory() as d:
            save_model(m, Path(d) / "m.pt")
            torch.testing.assert_close(m(x)[0], load_model(Path(d) / "m.pt")(x)[0])
            export_for_field(m, Path(d) / "m.fieldpen.json")
            exported = json.loads((Path(d) / "m.fieldpen.json").read_text())
        self.assertEqual(exported["config"], dict(hidden=12, mixtures=2, layers=2))
        for name, t in m.state_dict().items():
            e = exported["tensors"][name]
            values = np.frombuffer(base64.b64decode(e["data"]), "<f4").reshape(e["shape"])
            np.testing.assert_array_equal(values, t.detach().numpy())


if __name__ == "__main__":
    unittest.main()
