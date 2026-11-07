"""Curves anchored by memory: each point is the mel spectrum averaged over a different timescale.

The rolling analyzer embeds the last N spectra. This one keeps N exponential moving averages (EMAs)
of the spectrum instead, with time constants spaced geometrically from one hop up to
``longest_seconds``. Each hop, MDS embeds those N averaged spectra. Point order runs from the
longest memory to the present, so the last point is still the newest and most responsive one.

Because every point keeps its identity from hop to hop (point k is always "the sound as remembered
over timescale k"), alignment fits all of them, weighted by their time constants, and without
rescaling: the slow points barely move, so they hold the frame still and coordinates stay in
Wasserstein units. Long timescales start from the first spectrum, so the far end of the curve
describes the beginning of the file until enough of it has gone by.

    python -m fieldaudio_curve.ema input.wav output.f4curve [--points 48] [--longest 20]
"""
import argparse
from pathlib import Path
import struct

import numpy as np

from .curve import CurveConfig, _embed_distances, _mel_filter, read_wav_channels

#: Header ``kind`` at byte 40 of a .f4curve file (0, the reserved default, is a rolling window).
KIND_EMA = 1


class EmaCurveAnalyzer:
    """Incrementally consume mono samples; one curve of EMA timescales per complete window."""

    def __init__(self, config, longest_seconds=20.0):
        if not longest_seconds > config.hop_size / config.sample_rate:
            raise ValueError("longest_seconds must be longer than one hop")
        self.config = config
        hop = config.hop_size / config.sample_rate
        n = config.history
        # Longest first: the array runs from the long view to the present, like a rolling curve.
        self.taus = hop * (longest_seconds / hop) ** (np.arange(n)[::-1] / (n - 1))
        self._alpha = 1 - np.exp(-hop / self.taus)
        self._weights = self.taus / self.taus.sum()
        self._filter = _mel_filter(config)
        self._window = np.hanning(config.fft_size)
        self._samples = np.empty(0, dtype=np.float64)
        self._start = 0
        self._emas = None
        self._previous = None

    def push(self, samples):
        samples = np.asarray(samples, dtype=np.float64)
        if samples.ndim != 1 or not np.isfinite(samples).all():
            raise ValueError("samples must be finite mono audio")
        self._samples = np.concatenate((self._samples, samples))
        c, result = self.config, []
        while len(self._samples) >= c.fft_size:
            power = np.abs(np.fft.rfft(self._samples[:c.fft_size] * self._window)) ** 2
            mass = (self._filter @ power) ** c.compression
            total = mass.sum()
            mass = mass / total if total > 1e-20 else np.zeros_like(mass)
            if self._emas is None:
                self._emas = np.tile(mass, (c.history, 1))
            else:
                self._emas += self._alpha[:, None] * (mass - self._emas)
            cdf = np.cumsum(self._emas, axis=1)[:, :-1]
            distances = np.abs(cdf[:, None, :] - cdf[None, :, :]).sum(axis=2) / (c.mel_bands - 1)
            coords = self._align(_embed_distances(distances, c.dimensions))
            self._previous = coords.copy()
            result.append(((self._start + c.fft_size / 2) / c.sample_rate, coords))
            self._samples = self._samples[c.hop_size:]
            self._start += c.hop_size
        return result

    def _align(self, coords):
        """Rigid fit (rotation, reflection, translation) onto the previous curve, weighted by timescale."""
        previous, w = self._previous, self._weights[:, None]
        if previous is None or ((previous - previous.mean(0)) ** 2).sum() < 1e-20:
            return coords
        xm, ym = (w * coords).sum(0), (w * previous).sum(0)
        x = coords - xm
        u, _, vt = np.linalg.svd((w * x).T @ (previous - ym))
        return x @ (u @ vt) + ym


def write_ema_curves(path, samples, config, longest_seconds=20.0):
    """Write a .f4curve file of EMA curves; history is the number of timescales."""
    samples = np.asarray(samples)
    frames = max(0, 1 + (len(samples) - config.fft_size) // config.hop_size)
    header = struct.pack('<8sIIIIIIQId12x', b'F4CURVE\0', 1, config.sample_rate, config.fft_size,
                         config.hop_size, config.history, config.dimensions, frames,
                         KIND_EMA, longest_seconds)
    analyzer = EmaCurveAnalyzer(config, longest_seconds)
    with open(Path(path), 'wb') as out:
        out.write(header)
        for start in range(0, len(samples), config.hop_size * 16):
            for timestamp, coords in analyzer.push(samples[start:start + config.hop_size * 16]):
                out.write(struct.pack('<dII', timestamp, len(coords), 0))
                out.write(coords.astype('<f4').tobytes())


def main():
    parser = argparse.ArgumentParser(description='Curves of spectra averaged over geometric timescales')
    parser.add_argument('input')
    parser.add_argument('output')
    parser.add_argument('--points', type=int, default=48, help='number of timescales (points per curve)')
    parser.add_argument('--longest', type=float, default=20.0, help='longest time constant, seconds')
    parser.add_argument('--fft-size', type=int, default=2048)
    parser.add_argument('--hop-size', type=int, default=512)
    parser.add_argument('--mel-bands', type=int, default=64)
    parser.add_argument('--dimensions', type=int, choices=(2, 3), default=2)
    parser.add_argument('--compression', type=float, default=0.3)
    args = parser.parse_args()
    rate, samples = read_wav_channels(args.input)
    config = CurveConfig(rate, args.fft_size, args.hop_size, args.mel_bands, args.points, args.dimensions,
                         compression=args.compression)
    output = Path(args.output)
    for channel in range(samples.shape[1]):
        path = output if samples.shape[1] == 1 else output.with_name(f"{output.stem}.ch{channel + 1}{output.suffix}")
        write_ema_curves(path, samples[:, channel], config, args.longest)
        print(f"channel {channel + 1}: {path}")


if __name__ == '__main__':
    main()
