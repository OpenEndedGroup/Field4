"""Reproducible training, measurements and actual model-rendered figures/video."""
from __future__ import annotations

import argparse
from dataclasses import replace
import json
import math
from pathlib import Path
import shutil
import subprocess
import time

import numpy as np
from PIL import Image, ImageDraw, ImageFont
import torch

from transport_flow import Options, TransportFlow, path_metrics, sliced_distance, source, stipple, train, unsafe_weight_blend


def font(size):
    try:
        return ImageFont.truetype("DejaVuSans.ttf", size)
    except OSError:
        return ImageFont.load_default(size=size)


def target_image(tau, size=192):
    """A deterministic density movie: ring material changes into two soft blobs."""
    axis = (np.arange(size) + .5) * 2 / size - 1
    x, y = np.meshgrid(axis, axis)
    angle = tau * 2 * np.pi
    cx, cy = .22 * np.cos(angle), .22 * np.sin(angle)
    radius = np.hypot(x - cx, y - cy)
    ring = np.exp(-((radius - .48) / .065) ** 2)
    blobs = np.exp(-(((x - .4 * np.cos(angle)) / .19) ** 2 + ((y - .4 * np.sin(angle)) / .25) ** 2))
    blobs += np.exp(-(((x + .4 * np.cos(angle)) / .19) ** 2 + ((y + .4 * np.sin(angle)) / .25) ** 2))
    mix = .5 - .5 * np.cos(2 * np.pi * tau)
    density = (1 - mix) * ring + mix * blobs
    return Image.fromarray(np.uint8(255 * (1 - np.clip(density, 0, 1))))


def letter_image(letter):
    image = Image.new("L", (192, 192), 255)
    draw = ImageDraw.Draw(image)
    f = font(170)
    bounds = draw.textbbox((0, 0), letter, font=f)
    draw.text(((192 - (bounds[2] - bounds[0])) / 2 - bounds[0],
               (192 - (bounds[3] - bounds[1])) / 2 - bounds[1]), letter, fill=0, font=f)
    return image


def density_movie(images, path, fps=4):
    """Encode the exact grayscale target frames, before stippling or transport."""
    if shutil.which("ffmpeg") is None:
        raise RuntimeError("ffmpeg is required to render the original density MP4")
    width, height = images[0].size
    with subprocess.Popen(["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24",
                           "-s", f"{width}x{height}", "-r", str(fps), "-i", "-", "-an",
                           "-c:v", "libx264", "-crf", "18", "-pix_fmt", "yuv420p",
                           "-movflags", "+faststart", str(path)], stdin=subprocess.PIPE) as encoder:
        for image in images:
            encoder.stdin.write(image.convert("RGB").tobytes())
        encoder.stdin.close()
        if encoder.wait() != 0:
            raise RuntimeError("original density movie encoding failed")


def coordinate_grid():
    fine, coarse = np.linspace(-1, 1, 65), np.linspace(-1, 1, 13)
    return np.stack([np.column_stack((fine, np.full_like(fine, v))) for v in coarse] +
                    [np.column_stack((np.full_like(fine, v), fine)) for v in coarse]).astype(np.float32)


def colors(points):
    # Color encodes canonical identity, never recolored from current positions.
    x, y = ((points + 1) / 2).T
    return np.column_stack((70 + 150 * x, 110 + 120 * y, 210 - 90 * x)).clip(0, 255).astype(np.uint8)


def panel(draw, rect, title, points=None, grid=None, target=None, paths=None, vectors=None, identity=None):
    left, top, w, h = rect
    draw.text((left + 12, top + 8), title, fill="#e0e5ee", font=font(16))
    draw.rectangle((left + 5, top + 36, left + w - 5, top + h - 5), outline="#384558")
    def screen(p):
        p = np.asarray(p)
        return np.column_stack((left + w / 2 + p[:, 0] * (w - 35) / 2.6,
                                top + 38 + (h - 45) / 2 + p[:, 1] * (h - 55) / 2.6))
    if target is not None:
        for x, y in screen(target):
            draw.ellipse((x - 1.2, y - 1.2, x + 1.2, y + 1.2), fill="#596374")
    if grid is not None:
        for line in grid:
            draw.line([tuple(p) for p in screen(line)], fill="#43786e", width=1)
    if paths is not None:
        for path in paths.transpose(1, 0, 2):
            draw.line([tuple(p) for p in screen(path)], fill="#507d8e", width=1)
    if vectors is not None:
        at, velocity = vectors
        for a, b in zip(screen(at), screen(at + .12 * velocity)):
            draw.line([tuple(a), tuple(b)], fill="#e4af67", width=2)
            draw.ellipse((b[0] - 1.5, b[1] - 1.5, b[0] + 1.5, b[1] + 1.5), fill="#e4af67")
    if points is not None:
        for (x, y), color in zip(screen(points), identity if identity is not None else colors(points)):
            draw.ellipse((x - .9, y - .9, x + .9, y + .9), fill=tuple(map(int, color)))


def figure(model, canonical, targets, path, steps=32):
    image = Image.new("RGB", (1200, 780), "#111925")
    draw = ImageDraw.Draw(image)
    grid = coordinate_grid()
    identity = colors(canonical)
    tau = .5 if model.temporal else 0.
    settings = [(0., 0.), (.5, tau), (1., 0.), (1., tau), (1., 1. if model.temporal else 0.)]
    for i, (t, movie) in enumerate(settings):
        p = model.transport(canonical, t, movie, steps)
        g = model.transport(grid.reshape(-1, 2), t, movie, steps).reshape(grid.shape)
        target = targets[round(movie * (len(targets) - 1))] if t == 1 and movie in (0., 1.) else None
        title = f"transport t={t:.2f}  movie tau={movie:.2f}"
        if model.temporal and not any(np.isclose(movie, j / (len(targets) - 1)) for j in range(len(targets))):
            title += " (unseen)"
        panel(draw, ((i % 3) * 400, (i // 3) * 390, 400, 390), title, p, g, target, identity=identity)
    at = source(196, arrangement="grid")
    panel(draw, (800, 390, 400, 390), f"paths + velocity at t=.5, tau={tau}",
          paths=model.trajectories(canonical[:80], 1., tau, steps), vectors=(at, model.velocity(at, .5, tau)))
    image.save(path)


def movie(model, canonical, path, frames=90, steps=32, reverse=False, overshoot=0.):
    if shutil.which("ffmpeg") is None:
        raise RuntimeError("ffmpeg is required to render MP4; install it or omit --movie")
    size = (960, 720)
    grid = coordinate_grid()
    identity = colors(canonical)
    with subprocess.Popen(["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24",
                           "-s", "960x720", "-r", "24", "-i", "-", "-an", "-c:v", "libx264",
                           "-crf", "20", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(path)],
                          stdin=subprocess.PIPE) as encoder:
        for frame in range(frames):
            u = frame / max(1, frames - 1)
            if reverse:
                u = 1 - u
            depth = .5 - .5 * math.cos(2 * math.pi * u)
            settings = [(1. + overshoot, u), (.5, u), (u * (1 + overshoot), u),
                        (depth * (1 + overshoot), .5)]
            image = Image.new("RGB", size, "#111925")
            draw = ImageDraw.Draw(image)
            for i, (t, tau) in enumerate(settings):
                p = model.transport(canonical, t, tau, steps)
                g = model.transport(grid.reshape(-1, 2), t, tau, steps).reshape(grid.shape)
                title = f"t={t:.2f}  tau={tau:.2f}"
                panel(draw, ((i % 2) * 480, (i // 2) * 360, 480, 360), title, p, g, identity=identity)
            encoder.stdin.write(image.tobytes())
        encoder.stdin.close()
        if encoder.wait() != 0:
            raise RuntimeError("ffmpeg encoding failed")


def measure(model, canonical, targets, steps):
    report = {"parameters": sum(p.numel() for p in model.parameters()),
              "loss_last_200": float(np.mean(model.losses[-200:])), "frames": []}
    for j, target in enumerate(targets):
        tau = j / max(1, len(targets) - 1)
        history = model.trajectories(canonical, 1., tau, steps)
        reference = model.transport(canonical, 1., tau, steps * 4, integrator="rk4")
        row = {"movie_time": tau, "sliced_w1": sliced_distance(history[-1], target),
               "source_sliced_w1": sliced_distance(canonical, target), **path_metrics(history),
               "integration_error": {str(n): float(np.linalg.norm(model.transport(canonical, 1., tau, n) - reference, axis=1).mean())
                                     for n in (8, 16, 32)}}
        report["frames"].append(row)
    taus = np.linspace(0, 1, 17)
    endpoints = np.stack([model.transport(canonical, 1., float(tau), steps) for tau in taus])
    report["movie_motion"] = {"mean_step_displacement": float(np.linalg.norm(np.diff(endpoints, axis=0), axis=-1).mean()),
                              "max_step_displacement": float(np.linalg.norm(np.diff(endpoints, axis=0), axis=-1).max()),
                              "mean_second_difference": float(np.linalg.norm(np.diff(endpoints, n=2, axis=0), axis=-1).mean())}
    # CPU/GPU timing is explicitly for this Python device, never a Field runtime claim.
    probe = source(10000)
    model.velocity(probe, .5, .5)
    start = time.perf_counter()
    for _ in range(10):
        model.velocity(probe, .5, .5)
    report["velocity_10000_ms"] = (time.perf_counter() - start) * 100
    return report


def read_inputs(args):
    if args.points:
        loaded = np.load(args.points, allow_pickle=False)
        return [loaded] if loaded.ndim == 2 else list(loaded)
    if args.images:
        return [stipple(p, args.count, args.seed + j, args.density, args.gamma) for j, p in enumerate(args.images)]
    if args.video:
        # Decode into a bounded temporary directory; no shell or guessed frame rate.
        import tempfile
        probe = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "json", args.video],
                               capture_output=True, text=True, check=True)
        duration = float(json.loads(probe.stdout)["format"]["duration"])
        with tempfile.TemporaryDirectory(prefix="field-stipple-video-") as temporary:
            subprocess.run(["ffmpeg", "-v", "error", "-i", args.video, "-vf",
                            f"fps={args.target_frames / duration},scale=256:256:force_original_aspect_ratio=decrease",
                            "-frames:v", str(args.target_frames), str(Path(temporary) / "%04d.png")], check=True)
            return [stipple(p, args.count, args.seed + j, args.density, args.gamma) for j, p in enumerate(sorted(Path(temporary).glob("*.png")))]
    raise ValueError("supply --images, --points or --video")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("demo", "train", "render"))
    parser.add_argument("--out", type=Path, default=Path("build/transport-flow"))
    inputs = parser.add_mutually_exclusive_group()
    inputs.add_argument("--images", nargs="+")
    inputs.add_argument("--points", help=".npy (N,2) or (frames,N,2), normalized coordinates")
    inputs.add_argument("--video", help="video path; decoded through ffmpeg")
    parser.add_argument("--checkpoint", type=Path)
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    parser.add_argument("--steps", type=int, default=4000)
    parser.add_argument("--batch", type=int, default=256)
    parser.add_argument("--width", type=int, default=64)
    parser.add_argument("--depth", type=int, default=3)
    parser.add_argument("--count", type=int, default=4096)
    parser.add_argument("--sigma", type=float, default=.025)
    parser.add_argument("--source-half-extent", type=float, nargs=2, default=(1., 1.))
    parser.add_argument("--coupling", choices=("random", "bsp"), default="bsp")
    parser.add_argument("--temporal-strength", type=float, default=0.)
    parser.add_argument("--temporal-delta", type=float, default=.025)
    parser.add_argument("--seed", type=int, default=17)
    parser.add_argument("--density", choices=("darkness", "brightness"), default="darkness")
    parser.add_argument("--gamma", type=float, default=1.)
    parser.add_argument("--integration-steps", type=int, default=32)
    parser.add_argument("--target-frames", type=int, default=16)
    parser.add_argument("--source-arrangement", choices=("random", "grid"), default="random")
    parser.add_argument("--movie", action="store_true")
    parser.add_argument("--movie-frames", type=int, default=90)
    parser.add_argument("--reverse", action="store_true")
    parser.add_argument("--overshoot", type=float, default=0.)
    parser.add_argument("--unsafe-weights", action="store_true", help="also compare sequential/independent weight interpolation")
    args = parser.parse_args()
    if min(args.target_frames, args.movie_frames, args.integration_steps, args.count) <= 0:
        parser.error("frame counts, integration steps and stipple count must be positive")
    args.out.mkdir(parents=True, exist_ok=True)
    torch.set_num_threads(4)
    options = Options(width=args.width, depth=args.depth, steps=args.steps, batch=args.batch, sigma=args.sigma,
                      source_half_extent=tuple(args.source_half_extent), coupling=args.coupling, seed=args.seed,
                      temporal_strength=args.temporal_strength, temporal_delta=args.temporal_delta)
    canonical = source(1600, args.seed + 99, args.source_arrangement, options.source_half_extent)
    report = {"device": args.device, "torch": torch.__version__, "options": vars(options), "experiments": {}}
    if args.mode == "demo":
        for letter in ("C", "S"):
            letter_image(letter).save(args.out / f"target-{letter}.png")
        letters = [stipple(letter_image(letter), args.count, args.seed + j) for j, letter in enumerate(("C", "S"))]
        ring = [stipple(target_image(0), args.count, args.seed)]
        movie_images = [target_image(j / max(1, args.target_frames - 1)) for j in range(args.target_frames)]
        movie_images[0].save(args.out / "source-density.png")
        if args.movie:
            density_movie(movie_images, args.out / "source-density.mp4")
        movie_targets = [stipple(image, args.count, args.seed + j) for j, image in enumerate(movie_images)]
        jobs = [("static-ring", ring, options), ("image-random", letters[:1], replace(options, coupling="random")),
                ("image-bsp", letters[:1], replace(options, coupling="bsp")),
                ("two-images", letters, options), ("short-video", movie_targets, options)]
    else:
        jobs = [("transport", read_inputs(args), options)]
    trained = {}
    for name, targets, opt in jobs:
        print(f"{name}: {len(targets)} target frame(s), {opt.coupling}", flush=True)
        start = time.perf_counter()
        if args.mode == "render":
            if not args.checkpoint:
                parser.error("render requires --checkpoint")
            model = TransportFlow.load(args.checkpoint, args.device)
            if model.frame_count != len(targets):
                parser.error("render target frame count differs from checkpoint")
        else:
            initial = TransportFlow.load(args.checkpoint, args.device) if args.checkpoint else None
            model = train(targets, opt, args.device, initial,
                          progress=lambda n, loss: print(f"  {n}: {loss:.5f}", flush=True))
        elapsed = time.perf_counter() - start
        trained[name] = model
        model.save(args.out / f"{name}.pt")
        model.export(args.out / f"{name}.json")
        figure(model, canonical, targets, args.out / f"{name}.png", args.integration_steps)
        metrics = measure(model, canonical, targets, args.integration_steps)
        metrics["training_seconds"] = elapsed if args.mode != "render" else None
        report["experiments"][name] = metrics
        if model.temporal:
            model.bake(args.out / f"{name}-field.npz")
        if args.movie and (model.temporal or args.mode != "demo"):
            movie(model, canonical, args.out / f"{name}.mp4", args.movie_frames, args.integration_steps,
                  args.reverse, args.overshoot)
        (args.out / "measurements.json").write_text(json.dumps(report, indent=2, allow_nan=False))
    if args.unsafe_weights:
        if args.mode != "demo":
            parser.error("--unsafe-weights is a demo side experiment")
        a = TransportFlow.load(args.out / "image-bsp.pt", args.device)
        ancestor = TransportFlow.load(args.out / "image-bsp.pt", args.device)
        b = train(letters[1:], replace(options, coupling="bsp"), args.device, ancestor)
        # Independent initialization; identical training options permit portable blending.
        torch.manual_seed(args.seed + 1234)
        independent = train(letters[1:], replace(options, coupling="bsp"), args.device,
                            TransportFlow(replace(options, coupling="bsp")).to(args.device))
        for label, right in (("sequential", b), ("independent", independent)):
            right.save(args.out / f"unsafe-{label}-endpoint.pt")
            right.export(args.out / f"unsafe-{label}-endpoint.json")
            middle = unsafe_weight_blend(a, right, .5)
            middle.export(args.out / f"unsafe-{label}.json")
            figure(middle, canonical, letters[:1], args.out / f"unsafe-{label}.png", args.integration_steps)
            report["experiments"][f"unsafe-{label}"] = {"midpoint_path_metrics": path_metrics(middle.trajectories(canonical)),
                                                       "guaranteed_interpolation": False}
        (args.out / "measurements.json").write_text(json.dumps(report, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
