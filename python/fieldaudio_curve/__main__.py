import argparse
from pathlib import Path
from .curve import CurveConfig, read_wav_channels, write_curves

parser = argparse.ArgumentParser(description='Create rolling mel-Wasserstein MDS curves from a PCM WAV')
parser.add_argument('input')
parser.add_argument('output')
parser.add_argument('--fft-size', type=int, default=2048)
parser.add_argument('--hop-size', type=int, default=512)
parser.add_argument('--mel-bands', type=int, default=64)
parser.add_argument('--history', type=int, default=128)
parser.add_argument('--dimensions', type=int, choices=(2, 3), default=2)
parser.add_argument('--compression', type=float, default=0.3)
args = parser.parse_args()
rate, samples = read_wav_channels(args.input)
config = CurveConfig(rate, args.fft_size, args.hop_size, args.mel_bands, args.history, args.dimensions,
                     compression=args.compression)
output = Path(args.output)
for channel in range(samples.shape[1]):
    path = output if samples.shape[1] == 1 else output.with_name(f"{output.stem}.ch{channel + 1}{output.suffix}")
    write_curves(path, samples[:, channel], config)
    print(f"channel {channel + 1}: {path}")
