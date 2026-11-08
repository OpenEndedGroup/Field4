"""How well does each kind of curve remember a recording? Rolling window, EMA stack and map, side by side.

For each WAV (first channel), every kind keeps the same number of points and is scored, over the
second half of the file, on three questions. Distances are Wasserstein-1 between unit-mass mel
spectra, reported in units of the median distance between two random moments of the recording.

- memory: for every spectrum heard in the last ``--window`` seconds, the distance to the nearest
  point the curve still holds (median). Lower means the curve still holds what was heard.
- distinct: how many of the points differ from all the others by more than a quarter of a unit.
- move: how far the points travel in half a second (median). Lower is steadier. (A rolling window
  replaces its points every hop, so it has no such number.)

    python -m fieldaudio_curve.compare a.wav [b.wav ...] [--points 48]
"""
import argparse

import numpy as np

from .curve import CurveConfig, _mel_filter, read_wav_channels
from .ema import EmaCurveAnalyzer
from .som import SomCurveAnalyzer


def spectra(samples, config):
    """Unit-mass compressed mel spectra, one per hop, as the analyzers compute them."""
    mel, window, out = _mel_filter(config), np.hanning(config.fft_size), []
    for start in range(0, len(samples) - config.fft_size + 1, config.hop_size):
        mass = (mel @ np.abs(np.fft.rfft(samples[start:start + config.fft_size] * window)) ** 2) ** config.compression
        total = mass.sum()
        out.append(mass / total if total > 1e-20 else np.full_like(mass, 1 / len(mass)))
    return np.array(out)


def _w1(a, b):
    return np.abs(np.cumsum(a - b, -1)[..., :-1]).sum(-1) / (a.shape[-1] - 1)


def memories(kind, frames, config, longest_seconds=20.0, learn_seconds=0.5):
    """The points each kind of curve holds after every hop, as spectra."""
    n = config.history
    if kind == 'rolling':
        return [frames[max(0, k - n + 1):k + 1] for k in range(len(frames))]
    if kind == 'ema':
        analyzer, emas, out = EmaCurveAnalyzer(config, longest_seconds), None, []
        for mass in frames:
            emas = np.tile(mass, (n, 1)) if emas is None else emas + analyzer._alpha[:, None] * (mass - emas)
            out.append(emas.copy())
        return out
    analyzer, out = SomCurveAnalyzer(config, learn_seconds), []
    for mass in frames:
        analyzer._learn(mass)
        out.append(analyzer.prototypes.copy())
    return out


def score(samples, rate, points=48, window_seconds=10.0, kinds=('rolling', 'ema', 'map')):
    config = CurveConfig(rate, history=points)
    frames = spectra(samples, config)
    rng = np.random.default_rng(0)
    i, j = rng.integers(0, len(frames), (2, 4000))
    unit = float(np.median(_w1(frames[i], frames[j])))
    hop = config.hop_size / rate
    half, window = int(round(0.5 / hop)), int(round(window_seconds / hop))
    checks = list(range(len(frames) // 2, len(frames), max(1, len(frames) // 40)))
    results = {'unit': unit}
    for kind in kinds:
        held = memories('som' if kind == 'map' else kind, frames, config)
        memory = np.median([np.median([_w1(frames[t], held[k]).min() for t in range(max(0, k - window), k)])
                            for k in checks]) / unit
        def distinct(ps):
            keep = []
            for p in ps:
                if all(_w1(p, q) > 0.25 * unit for q in keep):
                    keep.append(p)
            return len(keep)
        count = float(np.median([distinct(held[k]) for k in checks[::4]]))
        move = (float('nan') if kind == 'rolling' else
                float(np.median([np.median(_w1(held[k], held[k - half])) for k in checks]) / unit))
        results[kind] = {'memory': float(memory), 'distinct': count, 'move': move}
    return results


def main():
    parser = argparse.ArgumentParser(description='Score rolling, EMA and map curves on how well they remember a recording')
    parser.add_argument('wavs', nargs='+')
    parser.add_argument('--points', type=int, default=48)
    parser.add_argument('--window', type=float, default=10.0, help='seconds of past the memory score covers')
    args = parser.parse_args()
    for path in args.wavs:
        rate, channels = read_wav_channels(path)
        r = score(channels[:, 0], rate, args.points, args.window)
        print(f"{path}: {len(channels) / rate:.1f} s, unit {r['unit']:.4f}")
        print(f"  {'':8s} {'memory':>8s} {'distinct':>9s} {'move':>7s}")
        for kind in ('rolling', 'ema', 'map'):
            k = r[kind]
            move = '' if np.isnan(k['move']) else f"{k['move']:.2f}x"
            print(f"  {kind:8s} {k['memory']:7.2f}x {k['distinct']:9.0f} {move:>7s}")


if __name__ == '__main__':
    main()
