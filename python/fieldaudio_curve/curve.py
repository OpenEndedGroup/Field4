"""Mel Wasserstein distances, classical MDS, and overlap alignment."""
from dataclasses import dataclass
from pathlib import Path
import struct
import wave

import numpy as np
from scipy.linalg import eigh
from scipy.sparse.linalg import eigsh, ArpackNoConvergence


@dataclass(frozen=True)
class CurveConfig:
    sample_rate: int
    fft_size: int = 2048
    hop_size: int = 512
    mel_bands: int = 64
    history: int = 128
    dimensions: int = 2
    min_hz: float = 30.0
    max_hz: float | None = None
    #: Exponent applied to each band's energy before normalizing. 1.0 is raw power, under which a voice's
    #: lowest few bands hold most of the mass; 0.3, close to how loudness grows with intensity, spreads it.
    compression: float = 0.3

    def __post_init__(self):
        top = self.sample_rate / 2 if self.max_hz is None else self.max_hz
        if (self.sample_rate <= 0 or self.fft_size < 4 or self.hop_size < 1 or
            self.hop_size > self.fft_size or self.mel_bands < 2 or self.history < 3 or
            self.dimensions not in (2, 3) or not 0 <= self.min_hz < top <= self.sample_rate / 2 or
            not 0 < self.compression <= 4):
            raise ValueError("invalid analysis configuration")


def _mel_filter(config):
    hz_to_mel = lambda hz: 2595 * np.log10(1 + hz / 700)
    mel_to_hz = lambda mel: 700 * (10 ** (mel / 2595) - 1)
    top = config.sample_rate / 2 if config.max_hz is None else config.max_hz
    edges = mel_to_hz(np.linspace(hz_to_mel(config.min_hz), hz_to_mel(top), config.mel_bands + 2))
    frequencies = np.fft.rfftfreq(config.fft_size, 1 / config.sample_rate)
    lower = (frequencies[None, :] - edges[:-2, None]) / (edges[1:-1] - edges[:-2])[:, None]
    upper = (edges[2:, None] - frequencies[None, :]) / (edges[2:] - edges[1:-1])[:, None]
    # Area normalization prevents wide high-frequency filters from dominating white noise.
    return np.maximum(0, np.minimum(lower, upper)) * (2 / (edges[2:] - edges[:-2]))[:, None]


def _embed_distances(distances, dimensions):
    squared = distances ** 2
    gram = -0.5 * (squared - squared.mean(axis=0) - squared.mean(axis=1)[:, None] + squared.mean())
    n = len(distances)
    if n > 64 and np.any(gram):
        try:
            values, vectors = eigsh(gram, k=dimensions, which="LA", tol=1e-8,
                                    v0=np.linspace(1.0, 2.0, n))
        except ArpackNoConvergence:
            values, vectors = eigh(gram, subset_by_index=[n - dimensions, n - 1], check_finite=False)
    else:
        values, vectors = np.linalg.eigh(gram)
    order = np.argsort(values)[::-1][:dimensions]
    coords = vectors[:, order] * np.sqrt(np.maximum(values[order], 0))
    return np.pad(coords, ((0, 0), (0, max(0, dimensions - coords.shape[1]))))


def _project(distributions, dimensions):
    # W1 on uniformly spaced mel positions: integral of the difference of CDFs.
    cdf = np.cumsum(distributions, axis=1)[:, :-1]
    distances = np.abs(cdf[:, None, :] - cdf[None, :, :]).sum(axis=2) / (distributions.shape[1] - 1)
    return _embed_distances(distances, dimensions)


def _align(current, previous):
    """Similarity Procrustes on shared points; permit reflection and guard degeneracy."""
    count = min(len(previous), len(current) - 1)
    if count < 2:
        return current
    x, y = current[-count-1:-1], previous[-count:]
    xm, ym = x.mean(axis=0), y.mean(axis=0)
    xc, yc = x - xm, y - ym
    denominator = np.sum(xc * xc)
    target = np.sum(yc * yc)
    if denominator < 1e-20 or target < 1e-20:
        return current - xm + ym
    u, singular, vt = np.linalg.svd(xc.T @ yc)
    rotation = u @ vt
    scale = singular.sum() / denominator
    return (current - xm) @ rotation * scale + ym


class CurveAnalyzer:
    """Incrementally consume mono samples; one curve per complete analysis window.

    push() is chunk-independent. Times refer to the center of each FFT window.
    """
    def __init__(self, config):
        self.config = config
        self._filter = _mel_filter(config)
        self._window = np.hanning(config.fft_size)
        self._samples = np.empty(0, dtype=np.float64)
        self._start = 0
        self._cdfs = []
        self._masses = []
        #: The newest curve before it was aligned to the previous one (MDS output as solved).
        self.last_unaligned = None
        self._distances = np.empty((0, 0))
        self._previous = None

    @property
    def masses(self):
        """The unit-mass mel spectra behind the newest curve, oldest first (zero rows are silence)."""
        return np.asarray(self._masses)

    @property
    def distances(self):
        """The Wasserstein-1 distances between those spectra: the matrix MDS embedded."""
        return self._distances.copy()

    def push(self, samples):
        samples = np.asarray(samples, dtype=np.float64)
        if samples.ndim != 1 or not np.isfinite(samples).all():
            raise ValueError("samples must be finite mono audio")
        self._samples = np.concatenate((self._samples, samples))
        result = []
        c = self.config
        while len(self._samples) >= c.fft_size:
            power = np.abs(np.fft.rfft(self._samples[:c.fft_size] * self._window)) ** 2
            mass = self._filter @ power
            if c.compression != 1.0:
                mass = mass ** c.compression
            total = mass.sum()
            # Silence is a zero distribution; silence-to-silence has distance zero.
            mass = mass / total if total > 1e-20 else np.zeros_like(mass)
            cdf = np.cumsum(mass)[:-1]
            if len(self._cdfs) == c.history:
                self._cdfs.pop(0)
                self._masses.pop(0)
                self._distances = self._distances[1:, 1:]
            n = len(self._cdfs)
            distances = np.empty((n + 1, n + 1))
            distances[:n, :n] = self._distances
            if n:
                new_distances = np.abs(np.asarray(self._cdfs) - cdf).sum(axis=1) / (c.mel_bands - 1)
                distances[n, :n] = distances[:n, n] = new_distances
            distances[n, n] = 0
            self._cdfs.append(cdf)
            self._masses.append(mass)
            self._distances = distances
            coords = _embed_distances(distances, c.dimensions)
            self.last_unaligned = coords.copy()
            if self._previous is not None:
                coords = _align(coords, self._previous)
            self._previous = coords.copy()
            timestamp = (self._start + c.fft_size / 2) / c.sample_rate
            result.append((timestamp, coords))
            self._samples = self._samples[c.hop_size:]
            self._start += c.hop_size
        return result


def read_wav_channels(path):
    """Read uncompressed PCM WAV as a sample-major array, preserving channels."""
    with wave.open(str(path), 'rb') as f:
        channels, width, rate, count = f.getnchannels(), f.getsampwidth(), f.getframerate(), f.getnframes()
        if f.getcomptype() != 'NONE' or width not in (1, 2, 3, 4):
            raise ValueError('expected uncompressed 8/16/24/32-bit PCM WAV')
        raw = f.readframes(count)
    if width == 1:
        data = (np.frombuffer(raw, np.uint8).astype(np.float64) - 128) / 128
    elif width == 3:
        b = np.frombuffer(raw, np.uint8).reshape(-1, 3).astype(np.int32)
        v = b[:, 0] | (b[:, 1] << 8) | (b[:, 2] << 16)
        data = ((v ^ 0x800000) - 0x800000) / 8388608.0
    else:
        data = np.frombuffer(raw, dtype='<i' + str(width)).astype(np.float64) / (2 ** (8 * width - 1))
    return rate, data.reshape(-1, channels)


def read_wav(path):
    """Read PCM WAV as mono, downmixing channels by arithmetic mean."""
    rate, channels = read_wav_channels(path)
    return rate, channels.mean(axis=1)


def write_curves(path, samples, config):
    """Write a completed, fixed-record file for random access via memory mapping."""
    samples = np.asarray(samples)
    if samples.ndim != 1 or not np.isfinite(samples).all():
        raise ValueError('samples must be finite mono audio')
    frames = max(0, 1 + (len(samples) - config.fft_size) // config.hop_size)
    # 64-byte little-endian header; each record: float64 time, uint32 count,
    # uint32 reserved, history * dimensions float32 coordinates.
    header = struct.pack('<8sIIIIIIQ24x', b'F4CURVE\0', 1, config.sample_rate,
                         config.fft_size, config.hop_size, config.history, config.dimensions, frames)
    analyzer = CurveAnalyzer(config)
    with open(Path(path), 'wb') as out:
        out.write(header)
        for start in range(0, len(samples), config.hop_size * 16):
            for timestamp, coords in analyzer.push(samples[start:start + config.hop_size * 16]):
                padded = np.zeros((config.history, config.dimensions), dtype='<f4')
                padded[:len(coords)] = coords
                out.write(struct.pack('<dII', timestamp, len(coords), 0))
                out.write(padded.tobytes())
