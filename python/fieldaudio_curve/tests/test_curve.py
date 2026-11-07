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


class CurveTest(unittest.TestCase):
    def test_transport_and_alignment(self):
        d = np.eye(3)
        p = _project(d, 2)
        self.assertGreater(np.linalg.norm(p[0] - p[2]), np.linalg.norm(p[0] - p[1]))
        previous = np.array([[0., 0.], [1., 0.], [1., 1.]])
        current = np.array([[2., 3.], [2., 5.], [0., 5.], [-1., 5.]])
        aligned = _align(current, previous)
        np.testing.assert_allclose(aligned[:3], previous, atol=1e-12)

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
            subprocess.run([sys.executable, "-m", "fieldaudio_curve", str(wav), str(out),
                            "--fft-size", "128", "--hop-size", "32", "--mel-bands", "12",
                            "--history", "8"], check=True, capture_output=True)
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

    def test_silence_is_finite(self):
        c = CurveConfig(8000, fft_size=128, hop_size=64, mel_bands=12, history=4)
        for _, points in CurveAnalyzer(c).push(np.zeros(512)):
            self.assertTrue(np.isfinite(points).all())
            self.assertTrue(np.all(points == 0))


if __name__ == '__main__':
    unittest.main()
