import json
from pathlib import Path
import tempfile
import unittest

import numpy as np
from PIL import Image
import torch

from transport_flow import Options, TransportFlow, bsp_permutation, source, stipple, train, unsafe_weight_blend


class TransportFlowTest(unittest.TestCase):
    def test_image_density_preserves_aspect_and_rejects_empty_mass(self):
        image = Image.new("L", (20, 10), 255)
        image.paste(0, (0, 0, 5, 10))
        points = stipple(image, 500, seed=4)
        self.assertTrue(np.all(points[:, 0] < -.5))
        self.assertTrue(np.all(np.abs(points[:, 1]) <= .5))
        np.testing.assert_array_equal(points, stipple(image, 500, seed=4))
        with self.assertRaises(ValueError):
            stipple(Image.new("L", (5, 5), 255), 10)

    def test_bsp_is_a_bijection_and_shortens_a_spatial_pairing(self):
        a = source(127, seed=4)
        b = source(127, seed=6) + (.3, 0)
        permutation = bsp_permutation(a, b)
        np.testing.assert_array_equal(np.sort(permutation), np.arange(127))
        self.assertLess(np.square(a - b[permutation]).sum(), np.square(a - b).sum() * .4)

    def test_queries_batching_save_load_export_and_bake_agree(self):
        torch.manual_seed(1)
        model = TransportFlow(Options(width=8, depth=2), 2)
        p = source(9)
        np.testing.assert_allclose(model.velocity(p, .3, .7, 3), model.velocity(p, .3, .7), atol=1e-7)
        forward = model.transport(p, 1., .7, steps=64)
        reverse = model.transport(forward, 0., .7, steps=64, start_time=1.)
        np.testing.assert_allclose(reverse, p, atol=2e-5)
        self.assertGreater(np.abs(model.velocity(p, .3, 0) - model.velocity(p, .3, 1)).max(), .001)
        self.assertGreater(np.abs(model.velocity(p, 0, .3) - model.velocity(p, 1, .3)).max(), .001)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            model.save(root / "model.pt")
            copy = TransportFlow.load(root / "model.pt")
            np.testing.assert_array_equal(copy.velocity(p, .2, .8), model.velocity(p, .2, .8))
            model.export(root / "model.json")
            data = json.loads((root / "model.json").read_text())
            self.assertEqual(len(data["weights"]), sum(p.numel() for p in model.parameters()))
            model.bake(root / "field.npz", resolution=5, transport_times=(0, .4, 1), movie_times=(0, .7))
            field = np.load(root / "field.npz", allow_pickle=False)
            self.assertEqual(field["velocity"].shape, (2, 3, 5, 5, 2))
            at = np.array([[field["x"][2], field["y"][1]]])
            np.testing.assert_allclose(field["velocity"][1, 1, 1, 2], model.velocity(at, .4, .7)[0], atol=1e-7)
        np.testing.assert_allclose(unsafe_weight_blend(model, model, .6).velocity(p, .3, .7), model.velocity(p, .3, .7), atol=1e-7)

    def test_uniform_sheet_learns_a_movie_translation_at_an_unseen_time(self):
        # Ground-truth density and midpoint are known; test actual learning, not loss alone.
        torch.set_num_threads(2)
        base = source(4096, 12)
        targets = [base * .35 + (-.4, -.1), base * .35 + (.4, .1)]
        model = train(targets, Options(width=32, depth=3, steps=1400, batch=128, sigma=.02, seed=7))
        canonical = source(1024, 18)
        endpoints = [model.transport(canonical, 1., tau) for tau in (0., .5, 1.)]
        for points, expected in zip(endpoints, ((-.4, -.1), (0., 0.), (.4, .1))):
            np.testing.assert_allclose(points.mean(0), expected, atol=.065)
            self.assertTrue(np.all(points.std(0) < .3))
        # Each identity's midpoint stays close to the smooth interpolated transport.
        self.assertLess(np.linalg.norm(endpoints[1] - .5 * (endpoints[0] + endpoints[2]), axis=1).mean(), .1)

    def test_temporal_regularization_and_experimental_time_ranges_are_executable(self):
        p = source(64)
        model = train([p * .3 - .2, p * .3 + .2], Options(width=8, depth=2, steps=8, batch=16, temporal_strength=.01))
        self.assertTrue(np.isfinite(model.transport(p, 1.2, -.1)).all())
        with self.assertRaises(ValueError):
            model.transport(p, steps=0)
        with self.assertRaises(ValueError):
            model.velocity(p, .5, batch_size=0)
        with self.assertRaises(ValueError):
            train([np.array([[np.nan, 0]])])
        self.assertEqual(model.transport(np.empty((0, 2))).shape, (0, 2))


if __name__ == "__main__":
    unittest.main()
