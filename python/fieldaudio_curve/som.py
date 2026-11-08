"""Curves of remembered sounds: a self-organizing map of mel spectra, on a chain, sheet or lattice.

The rolling analyzer embeds the last N spectra and the EMA analyzer N running averages; both describe
*when*. This one keeps N prototype spectra on a grid (a chain, a 2D sheet or a 3D lattice) and
describes *what*: each hop the prototype nearest the new spectrum (its best match), and its
neighbours on the grid, move a little toward it. Over time the prototypes spread out over the kinds
of sound the recording has made, and grid neighbours stay similar, so the grid is itself a curve,
surface or volume folded through spectral space.

Spectra are unit-mass distributions and the distance is the Wasserstein-1 (earth mover's) distance,
the area between cumulative spectra. Mixing two spectra mixes their cumulative spectra the same
way, so every update keeps each prototype a valid distribution.

Two changes from a textbook SOM suit a stream rather than a training set. The learning rate stays
constant, so the map keeps adapting and forgets on a timescale of ``learn_seconds`` of winning;
and a conscience handicaps prototypes that win often, so that rarely used prototypes are recruited
by new sounds. The conscience only hands a sound to a prototype nearly as close as the best match
(within ``REACH`` times its distance): without that limit a long steady sound spreads over the whole
chain and overwrites what the other prototypes remember.

The neighbourhood starts wide (half the grid) and narrows to ``sigma`` over the first
``settle_seconds``, so the grid unfolds before it fine-tunes instead of locking in a twist.

Each hop, MDS embeds the N prototypes plus the present spectrum (N + 1 points, the present last),
and a rigid fit aligns the prototypes onto the previous hop's. Prototypes move slowly, so they hold
the frame still; coordinates stay in Wasserstein units. Prototypes are listed in C order of the grid
(the last axis varies fastest), and the file header records the grid's shape.

    python -m fieldaudio_curve.som input.wav output.f4curve [--nodes 48 | --grid 12x12 | --grid 6x6x6] [--learn 0.5]
"""
import argparse
from pathlib import Path
import struct

import numpy as np

from .curve import (PROGRESS_HELP, CurveConfig, _embed_distances, _mel_filter, progress_printer,
                    read_wav_channels)

#: Header ``kind`` at byte 40 of a .f4curve file: a self-organizing map, the present as the last point.
KIND_SOM = 2

#: How much farther than the best match a prototype may be and still win through the conscience.
REACH = 1.5


class SomCurveAnalyzer:
    """Incrementally consume mono samples; one curve of map prototypes (plus the present) per window.

    ``shape`` is the grid, e.g. ``(48,)``, ``(12, 12)`` or ``(6, 6, 6)``; its product must equal
    ``config.history``, the number of prototypes (a chain of ``config.history`` if omitted).
    ``learn_seconds`` sets how fast the best match moves toward a new sound, ``sigma`` the final
    neighbourhood width in grid steps, ``conscience`` how strongly frequent winners are handicapped
    (0 turns it off) and ``settle_seconds`` how long the neighbourhood takes to narrow.
    """

    def __init__(self, config, learn_seconds=0.5, sigma=2.0, conscience=1.0, shape=None, settle_seconds=2.0):
        shape = tuple(shape) if shape else (config.history,)
        if not 1 <= len(shape) <= 3 or min(shape) < 1 or int(np.prod(shape)) != config.history:
            raise ValueError(f"grid {shape} must have 1 to 3 axes whose product is history {config.history}")
        if not learn_seconds > 0 or not sigma > 0 or conscience < 0 or settle_seconds < 0:
            raise ValueError("learn_seconds and sigma must be positive, conscience and settle_seconds non-negative")
        self.config = config
        self.shape = shape
        self.learn_seconds = learn_seconds
        n, hop = config.history, config.hop_size / config.sample_rate
        self._hop = hop
        self._rate = 1 - np.exp(-hop / learn_seconds)
        grid = np.indices(shape).reshape(len(shape), -1).T.astype(float)
        self._grid_d2 = ((grid[:, None, :] - grid[None, :, :]) ** 2).sum(axis=2)
        self._sigma, self._sigma0, self._settle = sigma, max(sigma, max(shape) / 2), settle_seconds
        self._elapsed = 0.0
        self._conscience = conscience
        # Win frequencies, averaged over roughly n * learn_seconds (at most 30 s) so every prototype gets a turn.
        self._usage_rate = 1 - np.exp(-hop / min(30.0, n * learn_seconds))
        self._usage = np.full(n, 1 / n)
        self._filter = _mel_filter(config)
        self._window = np.hanning(config.fft_size)
        self._samples = np.empty(0, dtype=np.float64)
        self._start = 0
        self.prototypes = None
        self._previous = None
        self.last_match = None

    def _distances(self, mass):
        delta = np.cumsum(self.prototypes - mass, axis=1)[:, :-1]
        return np.abs(delta).sum(axis=1) / (self.config.mel_bands - 1)

    def _learn(self, mass):
        n = self.config.history
        if self.prototypes is None:
            # Start every prototype at the first sound; the conscience pulls them apart from there.
            self.prototypes = np.tile(mass, (n, 1))
        distances = self._distances(mass)
        scale = distances.mean() + 1e-12
        biased = distances + self._conscience * scale * (n * self._usage - 1)
        # The conscience may pass a sound to a less used prototype only if that one is nearly as close:
        # otherwise one long sound would spread over, and overwrite, the prototypes of everything else.
        best = distances.min()
        biased[distances > REACH * best + 1e-3 * scale] = np.inf
        match = int(np.argmin(biased))
        sigma = self._sigma + (self._sigma0 - self._sigma) * (np.exp(-self._elapsed / self._settle) if self._settle > 0 else 0.0)
        neighbourhood = np.exp(-self._grid_d2[match] / (2 * sigma ** 2))
        self.prototypes += (self._rate * neighbourhood)[:, None] * (mass - self.prototypes)
        self._elapsed += self._hop
        self._usage += self._usage_rate * ((np.arange(n) == match) - self._usage)
        self.last_match = match

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
            mass = mass / total if total > 1e-20 else np.full_like(mass, 1 / len(mass))
            self._learn(mass)
            points = np.vstack((self.prototypes, mass))
            cdf = np.cumsum(points, axis=1)[:, :-1]
            distances = np.abs(cdf[:, None, :] - cdf[None, :, :]).sum(axis=2) / (c.mel_bands - 1)
            coords = self._align(_embed_distances(distances, c.dimensions))
            self._previous = coords.copy()
            result.append(((self._start + c.fft_size / 2) / c.sample_rate, coords))
            self._samples = self._samples[c.hop_size:]
            self._start += c.hop_size
        return result

    def _align(self, coords):
        """Rigid fit (rotation, reflection, translation) of the prototypes onto the previous hop's."""
        previous = self._previous
        if previous is None:
            return coords
        x, y = coords[:-1], previous[:-1]
        if ((y - y.mean(0)) ** 2).sum() < 1e-20:
            return coords
        xm, ym = x.mean(0), y.mean(0)
        u, _, vt = np.linalg.svd((x - xm).T @ (y - ym))
        return (coords - xm) @ (u @ vt) + ym


def write_som_curves(path, samples, config, learn_seconds=0.5, sigma=2.0, conscience=1.0, progress=None, shape=None):
    """Write a .f4curve file of map curves: ``config.history`` prototypes plus the present per record.

    The grid's shape goes in the header as three uint32 at byte 52 (unused axes are 1).

    ``progress``, if given, is called as ``progress(done, frames)`` after each chunk of records.
    """
    samples = np.asarray(samples)
    frames = max(0, 1 + (len(samples) - config.fft_size) // config.hop_size)
    points = config.history + 1
    analyzer = SomCurveAnalyzer(config, learn_seconds, sigma, conscience, shape)
    grid = tuple(analyzer.shape) + (1,) * (3 - len(analyzer.shape))
    header = struct.pack('<8sIIIIIIQIdIII', b'F4CURVE\0', 1, config.sample_rate, config.fft_size,
                         config.hop_size, points, config.dimensions, frames, KIND_SOM, learn_seconds, *grid)
    done = 0
    with open(Path(path), 'wb') as out:
        out.write(header)
        for start in range(0, len(samples), config.hop_size * 16):
            for timestamp, coords in analyzer.push(samples[start:start + config.hop_size * 16]):
                done += 1
                out.write(struct.pack('<dII', timestamp, len(coords), analyzer.last_match))
                out.write(coords.astype('<f4').tobytes())
            if progress is not None:
                progress(done, frames)


def main():
    parser = argparse.ArgumentParser(description='Curves of a self-organizing map of mel spectra')
    parser.add_argument('input')
    parser.add_argument('output')
    parser.add_argument('--nodes', type=int, default=48, help='prototypes on a chain (points per curve, plus the present)')
    parser.add_argument('--grid', help='a sheet or lattice of prototypes instead, e.g. 12x12 or 6x6x6')
    parser.add_argument('--learn', type=float, default=0.5, help='learning time constant of the best match, seconds')
    parser.add_argument('--sigma', type=float, default=2.0, help='neighbourhood width, in prototypes')
    parser.add_argument('--conscience', type=float, default=1.0, help='handicap on frequent winners (0 = off)')
    parser.add_argument('--fft-size', type=int, default=2048)
    parser.add_argument('--hop-size', type=int, default=512)
    parser.add_argument('--mel-bands', type=int, default=64)
    parser.add_argument('--dimensions', type=int, choices=(2, 3), default=2)
    parser.add_argument('--compression', type=float, default=0.3)
    parser.add_argument('--progress', action='store_true', help=PROGRESS_HELP)
    args = parser.parse_args()
    shape = tuple(int(v) for v in args.grid.lower().split('x')) if args.grid else (args.nodes,)
    rate, samples = read_wav_channels(args.input)
    config = CurveConfig(rate, args.fft_size, args.hop_size, args.mel_bands, int(np.prod(shape)), args.dimensions,
                         compression=args.compression)
    output = Path(args.output)
    channels = samples.shape[1]
    for channel in range(channels):
        path = output if channels == 1 else output.with_name(f"{output.stem}.ch{channel + 1}{output.suffix}")
        report = progress_printer(channel, channels) if args.progress else None
        write_som_curves(path, samples[:, channel], config, args.learn, args.sigma, args.conscience, report, shape)
        print(f"channel {channel + 1}: {path}")


if __name__ == '__main__':
    main()
