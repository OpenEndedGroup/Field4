"""Every stage of one rolling curve, for figures: spectra, distances, MDS before and after alignment.

    python -m fieldaudio_curve.stages input.wav output.json --time 2.0 [--channel 0] [analysis options]

The JSON holds, for the curve at the requested time and the one before it: the analysis settings,
the mel band edges in Hz, the window's unit-mass mel spectra (oldest first), the Wasserstein-1
distance matrix between them, and the MDS coordinates as solved and as aligned to the previous curve.
"""
import argparse
import json
from pathlib import Path

import numpy as np

from .curve import CurveAnalyzer, CurveConfig, read_wav_channels


def mel_edges_hz(config):
    """The mel filter centres, in Hz, one per band (the analyzer's own spacing)."""
    hz_to_mel = lambda hz: 2595 * np.log10(1 + hz / 700)
    mel_to_hz = lambda mel: 700 * (10 ** (mel / 2595) - 1)
    top = config.sample_rate / 2 if config.max_hz is None else config.max_hz
    edges = mel_to_hz(np.linspace(hz_to_mel(config.min_hz), hz_to_mel(top), config.mel_bands + 2))
    return edges[1:-1]


def stages(samples, config, time_seconds):
    """Run the analyzer up to the curve current at time_seconds; return it and its predecessor."""
    analyzer = CurveAnalyzer(config)
    captured = []
    end = int(round(time_seconds * config.sample_rate + config.fft_size / 2)) + 1
    for start in range(0, min(len(samples), end), config.hop_size):
        for timestamp, coords in analyzer.push(samples[start:min(start + config.hop_size, end)]):
            captured.append({
                "time": timestamp,
                "unaligned": analyzer.last_unaligned.tolist(),
                "aligned": coords.tolist(),
            })
            captured = captured[-2:]
    if not captured:
        raise ValueError("no complete analysis window before that time")
    return {
        "sampleRate": config.sample_rate, "fftSize": config.fft_size, "hopSize": config.hop_size,
        "melBands": config.mel_bands, "history": config.history, "dimensions": config.dimensions,
        "compression": config.compression,
        "melCentersHz": mel_edges_hz(config).tolist(),
        "masses": analyzer.masses.tolist(),
        "distances": analyzer.distances.tolist(),
        "curves": captured,
    }


def main():
    parser = argparse.ArgumentParser(description='Dump every stage of one rolling audio curve as JSON')
    parser.add_argument('input')
    parser.add_argument('output')
    parser.add_argument('--time', type=float, required=True)
    parser.add_argument('--channel', type=int, default=0)
    parser.add_argument('--fft-size', type=int, default=2048)
    parser.add_argument('--hop-size', type=int, default=512)
    parser.add_argument('--mel-bands', type=int, default=64)
    parser.add_argument('--history', type=int, default=128)
    parser.add_argument('--dimensions', type=int, choices=(2, 3), default=2)
    parser.add_argument('--compression', type=float, default=0.3)
    args = parser.parse_args()
    rate, channels = read_wav_channels(args.input)
    config = CurveConfig(rate, args.fft_size, args.hop_size, args.mel_bands, args.history, args.dimensions,
                         compression=args.compression)
    Path(args.output).write_text(json.dumps(stages(channels[:, args.channel], config, args.time)))


if __name__ == '__main__':
    main()
