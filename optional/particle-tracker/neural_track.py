#!/usr/bin/env python3
"""Bidirectional RAFT-large -> streaming C++ particle tracker.
Install a matching torch/torchvision build for your GPU first. No dense-flow
cache is written: only two flow fields are resident at a time.
"""
import argparse
import json
import math
import pathlib
import struct
import subprocess
import sys


def read_exact(stream, size):
    chunks = bytearray()
    while len(chunks) < size:
        part = stream.read(size - len(chunks))
        if not part:
            break
        chunks.extend(part)
    if chunks and len(chunks) != size:
        raise RuntimeError('Truncated decoded RGB frame')
    return chunks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=pathlib.Path)
    parser.add_argument('output', type=pathlib.Path)
    parser.add_argument('--width', type=int, default=960)
    parser.add_argument('--fps', type=float)
    parser.add_argument('--frames', type=int, default=0)
    parser.add_argument('--device', default='cuda')
    parser.add_argument('--iterations', type=int, default=20)
    parser.add_argument('--cpu-threads', type=int, default=8)
    parser.add_argument('--weights', default='C_T_SKHT_V2')
    parser.add_argument('--tracker', type=pathlib.Path,
                        default=pathlib.Path(__file__).with_name('particle-video'))
    # Everything after -- belongs to the C++ tracker.
    argv = sys.argv[1:]
    split = argv.index('--') if '--' in argv else len(argv)
    args = parser.parse_args(argv[:split])
    extra = argv[split+1:] if split < len(argv) else []
    forbidden = {'--flow', '--width', '--fps', '--frames'}
    if any(x in forbidden for x in extra):
        parser.error('flow, width, fps, and frames are controlled by the wrapper')
    if not 32 <= args.width <= 16384 or not 1 <= args.iterations <= 100 or args.frames < 0 or not 1 <= args.cpu_threads <= 256:
        parser.error('Invalid width, iterations, frames, or CPU threads')
    source, target = args.input.resolve(), args.output.resolve()
    if source == target or source == pathlib.Path(str(target)+'.run.json'):
        parser.error('Input and output must differ')
    if not source.is_file():
        parser.error('Input must be a local file')
    import numpy as np
    import torch
    import torchvision
    import torch.nn.functional as F
    from torchvision.models.optical_flow import raft_large, Raft_Large_Weights
    if args.device.startswith('cuda') and not torch.cuda.is_available():
        raise RuntimeError('CUDA unavailable; install CUDA-enabled PyTorch or explicitly use --device cpu')
    torch.set_num_threads(args.cpu_threads)
    weights = Raft_Large_Weights[args.weights]
    model = raft_large(weights=weights, progress=True).eval().to(args.device)
    probe = subprocess.check_output(['ffprobe', '-v', 'error', '-select_streams', 'v:0',
                                    '-show_entries', 'stream=width,height,avg_frame_rate',
                                    '-of', 'json', str(source)], text=True)
    stream = json.loads(probe)['streams'][0]
    w = args.width
    # Match C++ round for positive values, not Python's round-to-even.
    h = int(math.floor(stream['height']*w/stream['width']+.5))
    num, den = map(float, stream['avg_frame_rate'].split('/'))
    fps = args.fps if args.fps is not None else num/den if den else 0
    if not (32 <= h <= 16384 and math.isfinite(fps) and 0 < fps <= 1000):
        raise ValueError('Unsupported dimensions/FPS; set --width and --fps')
    # Both decoders receive identical FPS text, scaling, pixel format, and limits.
    fps_text = format(fps, '.12g')
    fps = float(fps_text)
    decode_cmd = ['ffmpeg', '-v', 'error', '-nostdin', '-noautorotate', '-i', str(source),
                  '-map', '0:v:0', '-an', '-sn', '-vf', f'fps={fps_text},scale={w}:{h}', '-threads', '1']
    if args.frames:
        decode_cmd += ['-frames:v', str(args.frames)]
    decode_cmd += ['-f', 'rawvideo', '-pix_fmt', 'rgb24', 'pipe:1']
    tracker_cmd = [str(args.tracker.resolve()), str(source), str(target),
                   '--flow', '-', '--width', str(w), '--fps', fps_text]
    if args.frames:
        tracker_cmd += ['--frames', str(args.frames)]
    tracker_cmd += extra

    def tensor(rgb):
        image = torch.from_numpy(np.frombuffer(rgb, dtype=np.uint8).reshape(h, w, 3).copy())
        image = image.permute(2, 0, 1).unsqueeze(0).to(args.device, dtype=torch.float32)/127.5-1
        # Preserve geometry; pad rather than distort the image to a multiple of 8.
        ph = max(128, (h+7)//8*8)-h
        pw = max(128, (w+7)//8*8)-w
        return F.pad(image, (0, pw, 0, ph), mode='replicate')

    def infer(a, b):
        with torch.inference_mode():
            prediction = model(a, b, num_flow_updates=args.iterations)[-1]
            field = prediction[0, :, :h, :w].permute(1, 2, 0).contiguous().cpu().numpy()
        if not np.isfinite(field).all():
            raise RuntimeError('Model produced non-finite flow')
        return field.astype('<f4', copy=False).tobytes()

    decoder = subprocess.Popen(decode_cmd, stdout=subprocess.PIPE)
    tracker = None
    try:
        tracker = subprocess.Popen(tracker_cmd, stdin=subprocess.PIPE)
        tracker.stdin.write(struct.pack('<8sIIdQ', b'PVFLOW1\0', w, h, fps, 0))
        raw = read_exact(decoder.stdout, w*h*3)
        if not raw:
            raise RuntimeError('No frames decoded')
        previous = tensor(raw)
        pairs = 0
        while True:
            raw = read_exact(decoder.stdout, w*h*3)
            if not raw:
                break
            current = tensor(raw)
            forward = infer(previous, current)
            backward = infer(current, previous)
            tracker.stdin.write(struct.pack('<Q', pairs))
            tracker.stdin.write(forward)
            tracker.stdin.write(backward)
            pairs += 1
            previous = current
            if pairs % 10 == 0:
                print(f'Neural flow: {pairs} pairs', file=sys.stderr)
        decoder.stdout.close()
        if decoder.wait() != 0 or pairs < 1:
            raise RuntimeError('Decoder failed or fewer than two frames')
        # Completion marker is written only after decoding and inference succeed.
        tracker.stdin.write(struct.pack('<Q', (1 << 64)-1))
        tracker.stdin.close()
        if tracker.wait() != 0:
            raise RuntimeError('C++ particle tracker failed')
        metadata = dict(backend='torchvision-raft-large', weights=weights.name,
                        torch=torch.__version__, torchvision=torchvision.__version__,
                        iterations=args.iterations, device=args.device, width=w, height=h,
                        fps=fps, frames=pairs+1, tracker_command=tracker_cmd)
        pathlib.Path(str(target)+'.run.json').write_text(json.dumps(metadata, indent=2)+'\n')
    finally:
        # Closing input allows the C++ process to detect truncation and clean its
        # partial output; kill only if graceful failure does not complete promptly.
        if tracker is not None and tracker.poll() is None:
            try:
                tracker.stdin.close()
            except (BrokenPipeError, OSError):
                pass
            try:
                tracker.wait(timeout=10)
            except subprocess.TimeoutExpired:
                tracker.terminate()
                tracker.wait()
        if decoder.poll() is None:
            decoder.terminate()
            decoder.wait()


if __name__ == '__main__':
    main()
