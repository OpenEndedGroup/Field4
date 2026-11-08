"""Align simultaneous curves into a common stereo coordinate frame."""
import argparse
from pathlib import Path
import struct

import numpy as np


def align_pair(reference, moving):
    """Least-squares similarity fit of corresponding points, allowing reflections."""
    target = np.asarray(reference, dtype=np.float64)
    source = np.asarray(moving, dtype=np.float64)
    if target.shape != source.shape or target.ndim != 2 or target.shape[1] not in (2, 3) or not len(target):
        raise ValueError("curves must have the same nonempty (points, 2 or 3) shape")
    if not np.isfinite(target).all() or not np.isfinite(source).all():
        raise ValueError("curve coordinates must be finite")
    center_source = source.mean(axis=0)
    center_target = target.mean(axis=0)
    x = source - center_source
    y = target - center_target
    norm = np.sum(x * x)
    if norm < 1e-20:
        return source - center_source + center_target
    u, singular, vt = np.linalg.svd(x.T @ y)
    return (x @ (u @ vt)) * (singular.sum() / norm) + center_target


def align_stereo_files(reference_path, moving_path, output_path):
    """Write a new moving-channel file in the reference channel's frame at each time."""
    reference_path, moving_path, output_path = map(Path, (reference_path, moving_path, output_path))
    if output_path.resolve() in (reference_path.resolve(), moving_path.resolve()):
        raise ValueError("output must be different from either input")
    with reference_path.open('rb') as f:
        header = f.read(64)
    with moving_path.open('rb') as f:
        moving_header = f.read(64)
    if len(header) != 64 or header != moving_header:
        raise ValueError("curve file headers must match")
    magic, version, rate, fft, hop, history, dims, count = struct.unpack('<8sIIIIIIQ24x', header)
    if (magic != b'F4CURVE\0' or version != 1 or rate < 1 or fft < 4 or not 1 <= hop <= fft or
        history < 3 or dims not in (2, 3)):
        raise ValueError("invalid curve file header")
    dtype = np.dtype([('time', '<f8'), ('count', '<u4'), ('reserved', '<u4'),
                      ('coordinates', '<f4', (history, dims))])
    size = 64 + count * dtype.itemsize
    if reference_path.stat().st_size != size or moving_path.stat().st_size != size:
        raise ValueError("curve file length does not match header")
    if count == 0:
        with output_path.open('xb') as f:
            f.write(header)
        return
    reference = np.memmap(reference_path, dtype=dtype, mode='r', offset=64, shape=(count,))
    moving = np.memmap(moving_path, dtype=dtype, mode='r', offset=64, shape=(count,))
    # Open exclusively before the cleanup guard: an existing destination is never removed.
    with output_path.open('xb') as f:
        try:
            f.write(header)
            for start in range(0, count, 256):
                end = min(start + 256, count)
                left = reference[start:end]
                right = moving[start:end]
                if (not np.array_equal(left['time'], right['time']) or
                    not np.array_equal(left['count'], right['count']) or
                    np.any(left['count'] < 1) or np.any(left['count'] > history) or
                    not np.isfinite(left['time']).all()):
                    raise ValueError(f"mismatched or invalid records at {start}–{end - 1}")
                result = right.copy()
                for index in range(len(result)):
                    n = int(left[index]['count'])
                    result[index]['coordinates'][:n] = align_pair(left[index]['coordinates'][:n],
                                                                   right[index]['coordinates'][:n])
                f.write(result.tobytes())
        except BaseException:
            f.close()
            output_path.unlink(missing_ok=True)
            raise



def main():
    parser = argparse.ArgumentParser(description='Align one curve channel to its simultaneous reference')
    parser.add_argument('reference')
    parser.add_argument('moving')
    parser.add_argument('output')
    args = parser.parse_args()
    align_stereo_files(args.reference, args.moving, args.output)


if __name__ == '__main__':
    main()
