"""Offline conditional flow matching. Coordinates are normalized, y points down.

One network allocates uniform source area to jittered target density. Movie time
is a condition, never the ODE integration variable. No renderer dependency.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass
import json
import math
from pathlib import Path
from typing import Sequence

import numpy as np
from PIL import Image
import torch
from torch import nn

FORMAT = "field.transport-flow.v1"


@dataclass(frozen=True)
class Options:
    width: int = 64
    depth: int = 3
    steps: int = 4000
    batch: int = 256
    learning_rate: float = 0.002
    sigma: float = 0.025
    source_half_extent: tuple[float, float] = (1.0, 1.0)
    coupling: str = "bsp"
    temporal_strength: float = 0.0
    temporal_delta: float = 0.025
    seed: int = 17

    def __post_init__(self):
        if min(self.width, self.depth, self.steps, self.batch) <= 0:
            raise ValueError("width, depth, steps and batch must be positive")
        if self.coupling not in ("random", "bsp"):
            raise ValueError("coupling must be random or bsp")
        if not math.isfinite(self.sigma) or self.sigma < 0:
            raise ValueError("sigma must be finite and nonnegative")
        if len(self.source_half_extent) != 2 or any(not math.isfinite(x) or x <= 0 for x in self.source_half_extent):
            raise ValueError("source_half_extent needs two finite positive values")
        if not math.isfinite(self.learning_rate) or self.learning_rate <= 0:
            raise ValueError("learning_rate must be finite and positive")
        if not math.isfinite(self.temporal_strength) or self.temporal_strength < 0:
            raise ValueError("temporal_strength must be finite and nonnegative")
        if not math.isfinite(self.temporal_delta) or self.temporal_delta <= 0:
            raise ValueError("temporal_delta must be finite and positive")


def stipple(image: str | Path | Image.Image, count: int, seed: int = 0,
            density: str = "darkness", gamma: float = 1.0) -> np.ndarray:
    """Sample pixel mass (with replacement), then uniform subpixel positions.

    The longest image dimension spans [-1,1]; the other preserves aspect ratio.
    These are weighted stochastic stipples, not a blue-noise stippler. White
    images under darkness sampling fail rather than inventing a distribution.
    """
    if count <= 0 or not math.isfinite(gamma) or gamma <= 0:
        raise ValueError("count and gamma must be positive")
    if density not in ("darkness", "brightness"):
        raise ValueError("density must be darkness or brightness")
    if isinstance(image, Image.Image):
        pixels = np.asarray(image.convert("L"), dtype=np.float64) / 255
    else:
        with Image.open(image) as loaded:
            pixels = np.asarray(loaded.convert("L"), dtype=np.float64) / 255
    mass = ((1 - pixels) if density == "darkness" else pixels) ** gamma
    if mass.sum() <= 0:
        raise ValueError("image has no target mass")
    rng = np.random.default_rng(seed)
    h, w = mass.shape
    selected = rng.choice(w * h, count, p=(mass / mass.sum()).ravel())
    xy = np.column_stack((selected % w, selected // w)) + rng.random((count, 2))
    return ((xy - (w / 2, h / 2)) * (2 / max(w, h))).astype(np.float32)


def checked_frames(frames: Sequence[np.ndarray]) -> list[np.ndarray]:
    if not frames:
        raise ValueError("at least one target frame is required")
    result = []
    for frame in frames:
        f = np.asarray(frame, dtype=np.float32)
        if f.ndim != 2 or f.shape[1] != 2 or len(f) == 0 or not np.isfinite(f).all():
            raise ValueError("each frame must be a finite nonempty (N,2) point array")
        result.append(f)
    return result


def bsp_permutation(source: np.ndarray, target: np.ndarray) -> np.ndarray:
    """Equal-mass recursive median matching, O(N log²N), no cost matrix.

    Split both clouds on the widest combined axis, recurse in matching halves.
    This is a cheap spatial coupling, not exact optimal transport. Each target
    occurs once, preserving both empirical marginals even for odd batch sizes.
    """
    if source.shape != target.shape or source.ndim != 2 or source.shape[1] != 2:
        raise ValueError("BSP requires equal (N,2) arrays")
    result = np.empty(len(source), dtype=np.int64)

    def pair(a, b):
        if len(a) <= 1:
            result[a] = b
            return
        axis = int(np.argmax(np.ptp(source[a], axis=0) + np.ptp(target[b], axis=0)))
        a = a[np.argsort(source[a, axis], kind="stable")]
        b = b[np.argsort(target[b, axis], kind="stable")]
        mid = len(a) // 2
        pair(a[:mid], b[:mid])
        pair(a[mid:], b[mid:])

    pair(np.arange(len(source)), np.arange(len(target)))
    return result


class TransportFlow(nn.Module):
    """Small SiLU MLP: (x,y,transport_time[,movie_time]) -> (vx,vy)."""
    def __init__(self, options: Options, frame_count: int = 1):
        super().__init__()
        if frame_count <= 0:
            raise ValueError("frame_count must be positive")
        self.options = options
        self.frame_count = frame_count
        self.temporal = frame_count > 1
        widths = [4 if self.temporal else 3] + [options.width] * options.depth + [2]
        self.layers = nn.ModuleList(nn.Linear(a, b) for a, b in zip(widths, widths[1:]))
        self.losses: list[float] = []

    @property
    def device(self):
        return next(self.parameters()).device

    def forward(self, x, transport_time, movie_time=0.0):
        t = torch.as_tensor(transport_time, device=x.device, dtype=x.dtype).expand(x.shape[:-1]).unsqueeze(-1)
        parts = [x, t]
        if self.temporal:
            tau = torch.as_tensor(movie_time, device=x.device, dtype=x.dtype).expand(x.shape[:-1]).unsqueeze(-1)
            parts.append(tau)
        h = torch.cat(parts, dim=-1)
        for layer in self.layers[:-1]:
            h = torch.nn.functional.silu(layer(h))
        return self.layers[-1](h)

    @torch.no_grad()
    def velocity(self, points, transport_time: float, movie_time: float = 0.0, batch_size: int = 65536):
        """Batched CPU-array interface; neither time is clamped."""
        if not all(math.isfinite(t) for t in (transport_time, movie_time)):
            raise ValueError("times must be finite")
        x = self._points(points, batch_size)
        result = [self(x[i:i + batch_size], transport_time, movie_time).cpu()
                  for i in range(0, len(x), batch_size)]
        return torch.cat(result).numpy() if result else np.empty((0, 2), np.float32)

    def _points(self, points, batch_size):
        if batch_size <= 0:
            raise ValueError("batch_size must be positive")
        x = torch.as_tensor(points, dtype=torch.float32, device=self.device)
        if x.ndim != 2 or x.shape[1] != 2 or not torch.isfinite(x).all():
            raise ValueError("points must be a finite (N,2) array")
        return x

    @torch.no_grad()
    def trajectories(self, points, transport_time: float = 1.0, movie_time: float = 0.0,
                     steps: int = 32, start_time: float = 0.0, integrator: str = "midpoint",
                     batch_size: int = 65536):
        """Return (steps+1,N,2); input order/identity survives integration.

        Start/end times may reverse or overshoot. Movie time stays fixed during
        each ODE solve. This is transport motion, not dF/d(movie_time).
        """
        if steps <= 0 or integrator not in ("euler", "midpoint", "rk4"):
            raise ValueError("steps must be positive; integrator must be euler, midpoint or rk4")
        if not all(math.isfinite(t) for t in (transport_time, start_time, movie_time)):
            raise ValueError("times must be finite")
        x = self._points(points, batch_size).clone()
        h = (transport_time - start_time) / steps
        history = [x.cpu().numpy().copy()]
        for step in range(steps):
            t = start_time + h * step
            for i in range(0, len(x), batch_size):
                p = x[i:i + batch_size]
                a = self(p, t, movie_time)
                if integrator == "euler":
                    v = a
                else:
                    b = self(p + 0.5 * h * a, t + 0.5 * h, movie_time)
                    if integrator == "midpoint":
                        v = b
                    else:
                        c = self(p + 0.5 * h * b, t + 0.5 * h, movie_time)
                        d = self(p + h * c, t + h, movie_time)
                        v = (a + 2 * b + 2 * c + d) / 6
                x[i:i + batch_size] = p + h * v
            history.append(x.cpu().numpy().copy())
        return np.stack(history)

    @torch.no_grad()
    def transport(self, points, transport_time: float = 1.0, movie_time: float = 0.0,
                  steps: int = 32, start_time: float = 0.0, integrator: str = "midpoint",
                  batch_size: int = 65536):
        """Flow map for arbitrary geometry. Batches bound trajectory storage."""
        points = np.asarray(points, dtype=np.float32)
        self._points(points, batch_size)
        if len(points) == 0:
            return self.trajectories(points, transport_time, movie_time, steps, start_time, integrator, batch_size)[-1]
        return np.concatenate([self.trajectories(points[i:i + batch_size], transport_time, movie_time,
                              steps, start_time, integrator, batch_size)[-1]
                               for i in range(0, len(points), batch_size)])

    def save(self, path):
        torch.save({"format": FORMAT, "options": asdict(self.options), "frame_count": self.frame_count,
                    "state_dict": self.state_dict(), "losses": self.losses}, path)

    @classmethod
    def load(cls, path, device="cpu"):
        data = torch.load(path, map_location=device, weights_only=True)
        if data["format"] != FORMAT:
            raise ValueError("unsupported transport checkpoint")
        model = cls(Options(**data["options"]), data["frame_count"]).to(device)
        model.load_state_dict(data["state_dict"])
        model.losses = data["losses"]
        return model.eval()

    def export(self, path):
        """Portable JSON, row-major weights then bias for each linear layer."""
        flat = []
        for layer in self.layers:
            flat.extend(layer.weight.detach().cpu().ravel().tolist())
            flat.extend(layer.bias.detach().cpu().tolist())
        data = {"format": FORMAT, "temporal": self.temporal, "activation": "silu",
                "coordinates": "normalized-y-down", "sourceHalfExtent": self.options.source_half_extent,
                "frameTimes": np.linspace(0, 1, self.frame_count).tolist() if self.temporal else [0.0],
                "widths": [self.layers[0].in_features] + [l.out_features for l in self.layers],
                "weights": flat, "training": asdict(self.options)}
        Path(path).write_text(json.dumps(data, allow_nan=False))

    @torch.no_grad()
    def bake(self, path, resolution=32, transport_times=(0., .5, 1.), movie_times=(0., .5, 1.), extent=1.5):
        """NPZ velocity[ movie, transport, y, x, xy ]; normalized velocities.

        This samples velocity, not the flow map. Consumers integrate in transport
        time with movie time held fixed. The lattice bounds must contain paths.
        """
        if resolution < 2 or not math.isfinite(extent) or extent <= 0:
            raise ValueError("bake needs resolution >=2 and finite positive extent")
        ts, taus = np.asarray(transport_times), np.asarray(movie_times)
        if ts.ndim != 1 or taus.ndim != 1 or not len(ts) or not len(taus) or not np.isfinite(ts).all() or not np.isfinite(taus).all():
            raise ValueError("bake times must be nonempty finite vectors")
        axis = np.linspace(-extent, extent, resolution, dtype=np.float32)
        xx, yy = np.meshgrid(axis, axis)
        points = np.column_stack((xx.ravel(), yy.ravel()))
        values = np.stack([np.stack([self.velocity(points, float(t), float(tau)).reshape(resolution, resolution, 2)
                          for t in ts]) for tau in taus])
        np.savez_compressed(path, format=FORMAT, x=axis, y=axis, transport_times=ts, movie_times=taus, velocity=values)


def train(frames: Sequence[np.ndarray], options: Options = Options(), device="cpu",
          model: TransportFlow | None = None, progress=None) -> TransportFlow:
    frames = checked_frames(frames)
    torch.manual_seed(options.seed)
    rng = np.random.default_rng(options.seed)
    if model is None:
        model = TransportFlow(options, len(frames)).to(device)
    elif model.frame_count != len(frames) or model.options.width != options.width or model.options.depth != options.depth:
        raise ValueError("fine-tuning needs matching frame count and architecture")
    model.to(device).train()
    model.options = options
    optimizer = torch.optim.AdamW(model.parameters(), lr=options.learning_rate, weight_decay=0.0)
    for step in range(options.steps):
        j = int(rng.integers(len(frames)))
        tau = j / (len(frames) - 1) if model.temporal else 0.0
        x0 = rng.uniform(-1, 1, (options.batch, 2)).astype(np.float32) * options.source_half_extent
        x1 = frames[j][rng.integers(len(frames[j]), size=options.batch)].copy()
        x1 += rng.normal(0, options.sigma, x1.shape)
        if options.coupling == "bsp":
            x1 = x1[bsp_permutation(x0, x1)]
        t = torch.as_tensor(rng.random(options.batch), device=device, dtype=torch.float32)
        a, b = (torch.as_tensor(x, device=device, dtype=torch.float32) for x in (x0, x1))
        xt = (1 - t[:, None]) * a + t[:, None] * b
        v = model(xt, t, tau)
        regression = (v - (b - a)).square().mean()
        loss = regression
        if model.temporal and options.temporal_strength:
            # Finite-difference derivative at common (x,t), avoiding transport-time confusion.
            movie = torch.rand(options.batch, device=device)
            left = (movie - options.temporal_delta / 2).clamp(0, 1)
            right = (movie + options.temporal_delta / 2).clamp(0, 1)
            derivative = (model(xt, t, right) - model(xt, t, left)) / (right - left)[:, None]
            loss = loss + options.temporal_strength * derivative.square().mean()
        optimizer.zero_grad(set_to_none=True)
        loss.backward()
        optimizer.step()
        # Mild decay reduces the final integration bias without stopping exploration.
        for group in optimizer.param_groups:
            group["lr"] = options.learning_rate * (1 - .9 * (step + 1) / options.steps)
        model.losses.append(float(loss.detach()))
        if progress and (step + 1) % 500 == 0:
            progress(step + 1, float(np.mean(model.losses[-500:])))
    return model.eval()


def source(count: int, seed=1, arrangement="random", half_extent=(1., 1.)):
    if count <= 0:
        raise ValueError("source count must be positive")
    if arrangement == "random":
        p = np.random.default_rng(seed).uniform(-1, 1, (count, 2))
    elif arrangement == "grid":
        n = math.ceil(math.sqrt(count))
        axis = (np.arange(n) + .5) * 2 / n - 1
        xx, yy = np.meshgrid(axis, axis)
        p = np.column_stack((xx.ravel(), yy.ravel()))[:count]
    else:
        raise ValueError("arrangement must be random or grid")
    return (p * half_extent).astype(np.float32)


def unsafe_weight_blend(a: TransportFlow, b: TransportFlow, amount: float):
    """Deliberate experiment, no promise of functional/temporal interpolation."""
    if not math.isfinite(amount):
        raise ValueError("blend amount must be finite")
    if (a.frame_count != b.frame_count or a.options.width != b.options.width or a.options.depth != b.options.depth
            or tuple(a.options.source_half_extent) != tuple(b.options.source_half_extent)):
        raise ValueError("weight blend needs matching architecture and coordinate domain")
    result = TransportFlow(a.options, a.frame_count).to(a.device)
    with torch.no_grad():
        for out, left, right in zip(result.parameters(), a.parameters(), b.parameters()):
            out.copy_(left * (1 - amount) + right.to(a.device) * amount)
    return result.eval()


def sliced_distance(a, b, seed=3, directions=64):
    """Empirical sliced W1 distance; sensitive to mass proportions, unlike Chamfer."""
    rng = np.random.default_rng(seed)
    angles = rng.uniform(0, np.pi, directions)
    axes = np.stack((np.cos(angles), np.sin(angles)))
    pa, pb = np.sort(np.asarray(a) @ axes, axis=0), np.sort(np.asarray(b) @ axes, axis=0)
    quantiles = (np.arange(max(len(a), len(b))) + .5) / max(len(a), len(b))
    def resample(p):
        old = (np.arange(len(p)) + .5) / len(p)
        return np.stack([np.interp(quantiles, old, p[:, i]) for i in range(directions)], axis=1)
    return float(np.abs(resample(pa) - resample(pb)).mean())


def path_metrics(history):
    delta = np.diff(history, axis=0)
    lengths = np.linalg.norm(delta, axis=-1)
    unit = delta / np.maximum(lengths[..., None], 1e-8)
    angles = np.arccos(np.clip((unit[:-1] * unit[1:]).sum(-1), -1, 1))
    moving = (lengths[:-1] > 1e-6) & (lengths[1:] > 1e-6)
    return {"mean_path_length": float(lengths.sum(0).mean()),
            "mean_total_turn_radians": float((angles * moving).sum(0).mean())}
