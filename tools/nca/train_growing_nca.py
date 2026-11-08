"""Train a Growing Neural Cellular Automaton (Mordvintsev et al., Distill 2020,
https://distill.pub/2020/growing-ca, CC-BY 4.0) and export its weights for Field.

The model follows the article: a 16-channel cell state (RGBA + 12 hidden), fixed
identity / Sobel-x / Sobel-y perception (48 inputs), a per-cell MLP 48 -> 128 ReLU -> 16
with a zero-initialised last layer, a residual update applied through a stochastic
per-cell mask (p = 0.5), and alive masking (3x3 max-pooled alpha > 0.1). Training is
BPTT over 64-96 steps with a sample pool, and the "regenerating" variant damages the
worst-off pool samples with a random disc each batch.

    uv run --project . --group pen python tools/nca/train_growing_nca.py --target flower \
        --steps 4000 --out public/docs/assets/growing_nca/flower.json

The JSON written is what `afield.simulation.automata.GrowingNca` loads: `w1` (128x48,
row-major, input order identity[16], sobelX[16], sobelY[16]), `b1` (128), `w2` (16x128),
plus `size`, `target` name and the training loss history.
"""
import argparse
import json
import math
import os
import struct
import time
import zlib

import numpy as np
import torch
import torch.nn.functional as F

CH = 16
HIDDEN = 128


# --- targets ---------------------------------------------------------------------------

def _disc(img, cx, cy, r, rgb):
    h, w, _ = img.shape
    yy, xx = np.mgrid[0:h, 0:w]
    d = np.sqrt((xx + 0.5 - cx) ** 2 + (yy + 0.5 - cy) ** 2)
    a = np.clip(r - d + 0.5, 0, 1)[..., None]
    col = np.array([*rgb, 1.0], dtype=np.float32)
    img[:] = img * (1 - a) + col * a


def make_target(name, size=40):
    """Procedural RGBA targets (premultiplied), drawn here so no image download is needed."""
    img = np.zeros((size, size, 4), np.float32)
    c = size / 2
    if name == "flower":
        _disc(img, c, c + 9, 2.2, (0.25, 0.6, 0.2))  # stem base
        for i in range(8):
            _disc(img, c, c + 6 + i * 0.6, 1.6, (0.25, 0.6, 0.2))
        for k in range(6):
            a = k * math.pi / 3
            _disc(img, c + 7 * math.cos(a), c - 3 + 7 * math.sin(a), 4.5,
                  (0.9, 0.35, 0.55) if k % 2 == 0 else (0.95, 0.55, 0.7))
        _disc(img, c, c - 3, 4.0, (1.0, 0.8, 0.15))
    elif name == "ring":
        _disc(img, c, c, 15, (0.2, 0.45, 0.9))
        _disc(img, c, c, 11, (0.95, 0.95, 0.95))
        _disc(img, c, c, 7, (0.95, 0.3, 0.2))
        # hollow centre: punch alpha back out
        h, w, _ = img.shape
        yy, xx = np.mgrid[0:h, 0:w]
        d = np.sqrt((xx + 0.5 - c) ** 2 + (yy + 0.5 - c) ** 2)
        img *= np.clip(d - 3.5 + 0.5, 0, 1)[..., None]
    else:
        raise SystemExit(f"unknown target {name}")
    img[..., :3] *= img[..., 3:4]
    return img


def write_png(path, rgba01):
    """Minimal stdlib PNG writer (no PIL in the repo environment)."""
    a = (np.clip(rgba01, 0, 1) * 255).astype(np.uint8)
    h, w, _ = a.shape
    raw = b"".join(b"\x00" + a[y].tobytes() for y in range(h))

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def to_rgb_on_white(x):
    """x: [H,W,>=4] premultiplied -> RGB on white, as the article displays it."""
    rgb, a = np.clip(x[..., :3], 0, 1), np.clip(x[..., 3:4], 0, 1)
    return np.concatenate([1 - a + rgb, np.ones_like(a)], -1)


# --- model -----------------------------------------------------------------------------

class CA(torch.nn.Module):
    def __init__(self, fire_rate=0.5):
        super().__init__()
        self.fire_rate = fire_rate
        self.w1 = torch.nn.Conv2d(CH * 3, HIDDEN, 1)
        self.w2 = torch.nn.Conv2d(HIDDEN, CH, 1, bias=False)
        torch.nn.init.zeros_(self.w2.weight)
        ident = torch.tensor([[0, 0, 0], [0, 1, 0], [0, 0, 0]], dtype=torch.float32)
        sx = torch.tensor([[-1, 0, 1], [-2, 0, 2], [-1, 0, 1]], dtype=torch.float32) / 8
        k = torch.stack([ident, sx, sx.T])  # [3,3,3]
        # depthwise: output channel order identity[0..15], sobelX[0..15], sobelY[0..15]
        self.register_buffer("kernel", k.repeat_interleave(CH, 0).unsqueeze(1))  # [48,1,3,3]

    def perceive(self, x):
        xc = x.repeat(1, 3, 1, 1)  # channel j of block b sees state channel j
        return F.conv2d(F.pad(xc, (1, 1, 1, 1), "constant", 0), self.kernel, groups=CH * 3)

    @staticmethod
    def alive(x):
        return F.max_pool2d(x[:, 3:4], 3, 1, 1) > 0.1

    def forward(self, x):
        pre = self.alive(x)
        dx = self.w2(F.relu(self.w1(self.perceive(x))))
        mask = (torch.rand_like(x[:, :1]) <= self.fire_rate).float()
        x = x + dx * mask
        life = (pre & self.alive(x)).float()
        return x * life


def seed(n, size, device):
    x = torch.zeros(n, CH, size, size, device=device)
    x[:, 3:, size // 2, size // 2] = 1.0
    return x


def damage_mask(n, size, device):
    yy, xx = torch.meshgrid(torch.linspace(-1, 1, size, device=device),
                            torch.linspace(-1, 1, size, device=device), indexing="ij")
    c = torch.rand(n, 2, 1, 1, device=device) - 0.5
    r = 0.1 + 0.3 * torch.rand(n, 1, 1, device=device)
    return (((xx - c[:, 0]) ** 2 + (yy - c[:, 1]) ** 2) > r ** 2).float().unsqueeze(1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--target", default="flower")
    ap.add_argument("--steps", type=int, default=4000)
    ap.add_argument("--pad", type=int, default=12)
    ap.add_argument("--batch", type=int, default=8)
    ap.add_argument("--pool", type=int, default=1024)
    ap.add_argument("--damage", type=int, default=3, help="worst samples damaged per batch")
    ap.add_argument("--out", required=True)
    ap.add_argument("--device", default="mps" if torch.backends.mps.is_available() else "cpu")
    ap.add_argument("--seed", type=int, default=0)
    a = ap.parse_args()
    torch.manual_seed(a.seed)
    np.random.seed(a.seed)
    dev = torch.device(a.device)

    tgt_np = make_target(a.target)
    tgt_np = np.pad(tgt_np, ((a.pad, a.pad), (a.pad, a.pad), (0, 0)))
    size = tgt_np.shape[0]
    tgt = torch.tensor(tgt_np, device=dev).permute(2, 0, 1)[None]
    out_dir = os.path.dirname(os.path.abspath(a.out))
    os.makedirs(out_dir, exist_ok=True)
    write_png(os.path.join(out_dir, f"{a.target}_target.png"), to_rgb_on_white(tgt_np))

    ca = CA().to(dev)
    opt = torch.optim.Adam(ca.parameters(), lr=2e-3)
    sched = torch.optim.lr_scheduler.MultiStepLR(opt, [2000], 0.1)
    pool = seed(a.pool, size, dev)
    losses = []
    t0 = time.time()
    for step in range(a.steps):
        idx = torch.randperm(a.pool, device=dev)[:a.batch]
        x = pool[idx]
        with torch.no_grad():
            l = ((x[:, :4] - tgt) ** 2).mean((1, 2, 3))
            order = torch.argsort(l, descending=True)
            x = x[order]
            idx = idx[order]
            x[:1] = seed(1, size, dev)
            if a.damage:
                x[-a.damage:] *= damage_mask(a.damage, size, dev)
        n_iter = int(np.random.randint(64, 97))
        for _ in range(n_iter):
            x = ca(x)
        loss = ((x[:, :4] - tgt) ** 2).mean()
        opt.zero_grad()
        loss.backward()
        for p in ca.parameters():
            p.grad /= p.grad.norm() + 1e-8
        opt.step()
        sched.step()
        pool[idx] = x.detach()
        losses.append(float(loss))
        if step % 100 == 0:
            print(f"step {step} loss {loss.item():.5f} log10 {math.log10(loss.item()):.2f} "
                  f"{time.time() - t0:.0f}s", flush=True)

    w1 = ca.w1.weight.detach().cpu().numpy().reshape(HIDDEN, CH * 3)
    b1 = ca.w1.bias.detach().cpu().numpy()
    w2 = ca.w2.weight.detach().cpu().numpy().reshape(CH, HIDDEN)
    json.dump({"source": "Mordvintsev et al., Growing Neural Cellular Automata, Distill 2020",
               "target": a.target, "size": size, "channels": CH, "hidden": HIDDEN,
               "fireRate": 0.5, "steps": a.steps, "damage": a.damage,
               "loss": [round(v, 6) for v in losses[::50]],
               "w1": w1.round(6).ravel().tolist(), "b1": b1.round(6).tolist(),
               "w2": w2.round(6).ravel().tolist(),
               "targetRgba": tgt_np.round(4).ravel().tolist()}, open(a.out, "w"))

    # numpy rollout from one seed, with damage at step 400: the evidence frames.
    with torch.no_grad():
        x = seed(1, size, dev)
        for s in range(1, 801):
            x = ca(x)
            if s == 400:
                yy, xx = torch.meshgrid(torch.arange(size, device=dev), torch.arange(size, device=dev),
                                        indexing="ij")
                x *= (((xx - size // 2) ** 2 + (yy - size // 2 + 4) ** 2) > 8 ** 2).float()
            if s in (20, 60, 100, 200, 400, 401, 450, 500, 600, 800):
                write_png(os.path.join(out_dir, f"{a.target}_torch_{s:04d}.png"),
                          to_rgb_on_white(x[0].permute(1, 2, 0).cpu().numpy()))
    print("final loss", losses[-1], "wrote", a.out)


if __name__ == "__main__":
    main()
