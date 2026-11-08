import struct
import tempfile
import subprocess
import sys
import wave
import unittest
from pathlib import Path
import numpy as np
from fieldaudio_curve import CurveAnalyzer, CurveConfig, write_curves
from fieldaudio_curve.curve import _align, _project, _embed_distances
from fieldaudio_curve.stereo import align_pair, align_stereo_files


class CurveTest(unittest.TestCase):
    def test_transport_and_alignment(self):
        d = np.eye(3)
        p = _project(d, 2)
        self.assertGreater(np.linalg.norm(p[0] - p[2]), np.linalg.norm(p[0] - p[1]))
        previous = np.array([[0., 0.], [1., 0.], [1., 1.]])
        current = np.array([[2., 3.], [2., 5.], [0., 5.], [-1., 5.]])
        aligned = _align(current, previous)
        np.testing.assert_allclose(aligned[:3], previous, atol=1e-12)

    def test_stereo_similarity_recovers_reflection_rotation_and_scale(self):
        rng = np.random.default_rng(9)
        for dims in (2, 3):
            target = rng.normal(size=(50, dims))
            q, _ = np.linalg.qr(rng.normal(size=(dims, dims)))
            q[:, 0] *= -1 if np.linalg.det(q) > 0 else 1
            moving = target @ q * 2.3 + np.arange(dims)
            np.testing.assert_allclose(align_pair(target, moving), target, atol=1e-12)

    def test_stereo_file_validation_and_output(self):
        with tempfile.TemporaryDirectory() as directory:
            left = Path(directory) / 'left'
            right = Path(directory) / 'right'
            out = Path(directory) / 'out'
            c = CurveConfig(8000, fft_size=128, hop_size=32, mel_bands=12, history=8, dimensions=3)
            audio = np.random.default_rng(9).normal(size=512)
            write_curves(left, audio, c)
            write_curves(right, audio, c)
            align_stereo_files(left, right, out)
            self.assertEqual(left.stat().st_size, out.stat().st_size)
            dtype = np.dtype([('time', '<f8'), ('count', '<u4'), ('reserved', '<u4'),
                              ('coordinates', '<f4', (8, 3))])
            expected = np.memmap(left, dtype=dtype, mode='r', offset=64)
            actual = np.memmap(out, dtype=dtype, mode='r', offset=64)
            np.testing.assert_allclose(actual['coordinates'], expected['coordinates'], atol=1e-7)
            np.testing.assert_array_equal(actual['time'], expected['time'])
            with self.assertRaises(ValueError):
                align_stereo_files(left, right, left)
            with self.assertRaises(FileExistsError):
                align_stereo_files(left, right, out)
            bad = bytearray(right.read_bytes())
            struct.pack_into('<d', bad, 64, 123.0)
            right.write_bytes(bad)
            with self.assertRaises(ValueError):
                align_stereo_files(left, right, Path(directory) / 'bad')
            self.assertFalse((Path(directory) / 'bad').exists())

    def test_partial_eigensolver_matches_dense_mds(self):
        rng = np.random.default_rng(31)
        spectra = rng.random((80, 16))
        spectra /= spectra.sum(axis=1, keepdims=True)
        cdf = np.cumsum(spectra, axis=1)[:, :-1]
        distances = np.abs(cdf[:, None, :] - cdf[None, :, :]).sum(axis=2) / 15
        projected = _embed_distances(distances, 2)
        sq = distances ** 2
        gram = -0.5 * (sq - sq.mean(axis=0) - sq.mean(axis=1)[:, None] + sq.mean())
        values, vectors = np.linalg.eigh(gram)
        reference = vectors[:, -2:] * np.sqrt(values[-2:])
        np.testing.assert_allclose(projected @ projected.T, reference @ reference.T, atol=1e-9)

    def test_chunks_and_mapped_records(self):
        config = CurveConfig(8000, fft_size=128, hop_size=32, mel_bands=12, history=8)
        t = np.arange(512) / 8000
        audio = np.sin(2 * np.pi * (300 * t + 1800 * t * t))
        one = CurveAnalyzer(config).push(audio)
        analyzer = CurveAnalyzer(config)
        chunks = sum((analyzer.push(part) for part in np.array_split(audio, 7)), [])
        self.assertEqual(len(one), 13)
        for (ta, a), (tb, b) in zip(one, chunks):
            self.assertEqual(ta, tb)
            np.testing.assert_allclose(a, b)
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'curve'
            write_curves(path, audio, config)
            raw = path.read_bytes()
            self.assertEqual(struct.unpack_from('<8sIIIIIIQ', raw),
                             (b'F4CURVE\0', 1, 8000, 128, 32, 8, 2, 13))
            self.assertEqual(len(raw), 64 + 13 * (16 + 8 * 2 * 4))
            mapped = np.memmap(path, dtype='u1', mode='r')
            for i, (time, points) in enumerate(one):
                offset = 64 + i * 80
                timestamp, count, _ = struct.unpack_from('<dII', mapped, offset)
                self.assertEqual((timestamp, count), (time, len(points)))
                actual = np.frombuffer(mapped, '<f4', 16, offset + 16).reshape(8, 2)
                np.testing.assert_allclose(actual[:count], points, atol=1e-7)
                self.assertTrue(np.all(actual[count:] == 0))

    def test_stereo_cli_preserves_channels(self):
        with tempfile.TemporaryDirectory() as d:
            wav = Path(d) / "stereo.wav"
            t = np.arange(512) / 8000
            left = np.sin(2 * np.pi * 300 * t)
            right = np.sin(2 * np.pi * (500 * t + 2000 * t * t))
            pcm = (np.stack((left, right), axis=1) * 20000).astype("<i2")
            with wave.open(str(wav), "wb") as f:
                f.setnchannels(2)
                f.setsampwidth(2)
                f.setframerate(8000)
                f.writeframes(pcm.tobytes())
            out = Path(d) / "curves.f4curve"
            run = subprocess.run([sys.executable, "-m", "fieldaudio_curve", str(wav), str(out),
                                  "--fft-size", "128", "--hop-size", "32", "--mel-bands", "12",
                                  "--history", "8", "--progress"], check=True, capture_output=True, text=True)
            # Field's audioCurves draws its progress bar from these lines; the count spans both channels.
            progress = [tuple(map(int, line.split()[1:])) for line in run.stdout.splitlines() if line.startswith("progress ")]
            frames = 1 + (512 - 128) // 32
            self.assertEqual(progress[-1], (2 * frames, 2 * frames))
            self.assertTrue(all(b[0] > a[0] for a, b in zip(progress, progress[1:])), "the count only rises")
            left_file = out.with_name("curves.ch1.f4curve").read_bytes()
            right_file = out.with_name("curves.ch2.f4curve").read_bytes()
            self.assertFalse(out.exists())
            self.assertEqual(left_file[:64], right_file[:64])
            self.assertNotEqual(left_file[64:], right_file[64:])

    def test_rolling_distance_cache_matches_recomputation(self):
        c = CurveConfig(8000, fft_size=128, hop_size=32, mel_bands=12, history=7)
        audio = np.random.default_rng(4).normal(size=512)
        analyzer = CurveAnalyzer(c)
        analyzer.push(audio)
        cdf = np.asarray(analyzer._cdfs)
        expected = np.abs(cdf[:, None, :] - cdf[None, :, :]).sum(axis=2) / (c.mel_bands - 1)
        np.testing.assert_allclose(analyzer._distances, expected, atol=1e-14)

    def test_stages_match_the_streamed_curve(self):
        from fieldaudio_curve.stages import stages
        c = CurveConfig(8000, fft_size=128, hop_size=32, mel_bands=12, history=8)
        audio = np.random.default_rng(4).normal(size=2048)
        streamed = CurveAnalyzer(c).push(audio)
        time, coords = streamed[30]
        dump = stages(audio, c, time)
        self.assertAlmostEqual(dump["curves"][-1]["time"], time)
        np.testing.assert_allclose(dump["curves"][-1]["aligned"], coords, atol=1e-12)
        self.assertAlmostEqual(dump["curves"][0]["time"], streamed[29][0])
        masses = np.asarray(dump["masses"])
        np.testing.assert_allclose(masses.sum(axis=1), 1.0, atol=1e-12)
        cdf = np.cumsum(masses, axis=1)[:, :-1]
        expected = np.abs(cdf[:, None, :] - cdf[None, :, :]).sum(axis=2) / (c.mel_bands - 1)
        np.testing.assert_allclose(dump["distances"], expected, atol=1e-12)
        self.assertEqual(len(dump["melCentersHz"]), c.mel_bands)

    def test_ema_curves_are_anchored_by_their_slow_end(self):
        from fieldaudio_curve.ema import KIND_EMA, EmaCurveAnalyzer, write_ema_curves
        c = CurveConfig(8000, fft_size=256, hop_size=64, mel_bands=16, history=12, dimensions=2)
        rng = np.random.default_rng(3)
        # Two alternating noises, so the fast timescales keep moving while the slow ones settle.
        audio = np.concatenate([rng.normal(size=1600) * (1 if k % 2 else 0.2) for k in range(12)])
        audio[::2] += np.sin(np.arange(len(audio))[::2] * 0.9)
        analyzer = EmaCurveAnalyzer(c, longest_seconds=4.0)
        self.assertTrue(np.all(np.diff(analyzer.taus) < 0))
        curves = analyzer.push(audio)
        late = np.array([coords for _, coords in curves[len(curves) // 2:]])
        size = np.mean(np.linalg.norm(late - late.mean(axis=1, keepdims=True), axis=2))
        slow = np.linalg.norm(late[:, 0] - late[:, 0].mean(axis=0), axis=1).mean() / size
        fast = np.linalg.norm(late[:, -1] - late[:, -1].mean(axis=0), axis=1).mean() / size
        self.assertLess(slow, fast)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'ema.f4curve'
            write_ema_curves(path, audio, c, longest_seconds=4.0)
            data = path.read_bytes()
            frames = struct.unpack_from('<Q', data, 32)[0]
            self.assertEqual(frames, len(curves))
            self.assertEqual(struct.unpack_from('<Id', data, 40), (KIND_EMA, 4.0))
            self.assertEqual(len(data), 64 + frames * (16 + 4 * c.history * c.dimensions))

    def test_the_map_remembers_both_of_two_alternating_sounds(self):
        from fieldaudio_curve.som import KIND_SOM, SomCurveAnalyzer, write_som_curves
        c = CurveConfig(8000, fft_size=256, hop_size=64, mel_bands=16, history=12, dimensions=2)
        t = np.arange(4000) / 8000
        low, high = np.sin(2 * np.pi * 300 * t), np.sin(2 * np.pi * 2500 * t)
        audio = np.concatenate([low, high] * 4)
        analyzer = SomCurveAnalyzer(c, learn_seconds=0.05)
        curves = analyzer.push(audio)
        self.assertEqual(curves[-1][1].shape, (13, 2), "12 prototypes and the present")
        def mass(signal):
            probe = SomCurveAnalyzer(c)
            probe.push(signal[:256])
            return probe.prototypes[0]
        for sound in (low, high):
            # Some prototype still holds each sound, though only the high one is playing at the end.
            self.assertLess(analyzer._distances(mass(sound)).min(), 0.02)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'som.f4curve'
            write_som_curves(path, audio, c, learn_seconds=0.05)
            data = path.read_bytes()
            frames = struct.unpack_from('<Q', data, 32)[0]
            self.assertEqual(frames, len(curves))
            self.assertEqual(struct.unpack_from('<I', data, 24)[0], 13)
            self.assertEqual(struct.unpack_from('<Id', data, 40), (KIND_SOM, 0.05))
            record = 16 + 4 * 13 * 2
            self.assertEqual(len(data), 64 + frames * record)
            _, count, match = struct.unpack_from('<dII', data, 64 + (frames - 1) * record)
            self.assertEqual((count, match), (13, analyzer.last_match))

    def test_a_sheet_keeps_similar_sounds_next_to_each_other(self):
        from fieldaudio_curve.som import SomCurveAnalyzer, write_som_curves
        c = CurveConfig(8000, fft_size=256, hop_size=64, mel_bands=16, history=16, dimensions=3)
        t = np.arange(48000) / 8000
        # A tone gliding up and down: a one-parameter family of sounds for the sheet to lay out.
        audio = np.sin(2 * np.pi * np.cumsum(400 + 1500 * (0.5 + 0.5 * np.sin(2 * np.pi * 0.25 * t))) / 8000)
        analyzer = SomCurveAnalyzer(c, learn_seconds=0.2, sigma=1.0, shape=(4, 4))
        analyzer.push(audio)
        p = analyzer.prototypes
        w1 = lambda a, b: np.abs(np.cumsum(a - b, -1)[..., :-1]).sum(-1) / (c.mel_bands - 1)
        grid = np.indices((4, 4)).reshape(2, -1).T
        d2 = ((grid[:, None] - grid[None]) ** 2).sum(2)
        near = np.median(w1(p[:, None], p[None])[d2 == 1])
        far = np.median(w1(p[:, None], p[None])[d2 >= 4])
        self.assertLess(near, far / 2, "grid neighbours hold more similar sounds than distant prototypes")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'sheet.f4curve'
            write_som_curves(path, audio[:4000], c, learn_seconds=0.2, shape=(4, 4))
            data = path.read_bytes()
            self.assertEqual(struct.unpack_from('<III', data, 52), (4, 4, 1))
            self.assertEqual(struct.unpack_from('<I', data, 24)[0], 17)
        with self.assertRaises(ValueError):
            SomCurveAnalyzer(c, shape=(3, 4))

    def test_silence_is_finite(self):
        c = CurveConfig(8000, fft_size=128, hop_size=64, mel_bands=12, history=4)
        for _, points in CurveAnalyzer(c).push(np.zeros(512)):
            self.assertTrue(np.isfinite(points).all())
            self.assertTrue(np.all(points == 0))


if __name__ == '__main__':
    unittest.main()
