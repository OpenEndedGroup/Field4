#!/usr/bin/env python3
"""Export LPIPS (AlexNet backbone) to ONNX for `afield.onnx.Lpips`, and verify it.

LPIPS is Zhang et al., "The Unreasonable Effectiveness of Deep Features as a Perceptual Metric"
(CVPR 2018), from https://github.com/richzhang/PerceptualSimilarity (BSD-2-Clause). The `lpips`
package on PyPI is that repository's release; it ships the learned linear-layer weights
(`lpips/weights/v0.1/alex.pth`) and takes the AlexNet trunk from torchvision's ImageNet weights
(BSD-3-Clause), which torchvision downloads from download.pytorch.org on first use.

No prebuilt AlexNet LPIPS ONNX file could be found from a source worth trusting, so Field builds
its own, once per machine, with this script. Run it through uv with pinned packages; it writes
into the ONNX model cache `afield.onnx.OnnxModel` reads (`~/.afield/onnx/models`, or
`AFIELD_ONNX_CACHE_ROOT`):

    uv run --no-project --python 3.12 \\
        --with torch==2.14.1 --with torchvision==0.29.1 --with lpips==0.1.4 \\
        --with onnx==1.23.2 --with onnxruntime==1.30.0 --with numpy==2.5.3 \\
        python tools/export_lpips_onnx.py

With those versions the export is byte-reproducible: two runs on 2026-10-07 both produced
9,901,042 bytes with sha256 6b99961b1b2b5a2c475d74fcaed30e26151833c06db4fdd48e81468b70ff35c3, and
the ONNX graph agreed with PyTorch LPIPS to 1.2e-7 over the twelve test pairs. `afield.onnx.Lpips`
pins that digest.

`afield.onnx.Lpips` runs exactly that command itself when the model is missing.

The graph takes two float32 NCHW images in 0..1 — `candidate` of batch N and `reference` of
batch N or 1 — and returns `distance` of shape [N]: the LPIPS v0.1 distance, spatially averaged,
exactly `lpips.LPIPS(net="alex")(a, b, normalize=True)`. A reference of batch 1 broadcasts, so a
search scores N candidates against one target and pays for the target's features once. Height and
width are dynamic (at least 32).

After exporting, the script checks the ONNX graph through onnxruntime against the PyTorch module
on fixed image pairs at several sizes and refuses to install a file that disagrees by more than
1e-4. It then writes `lpips_alex.json` beside the model: the package versions, the opset, the
model's sha256 and the measured agreement. Field reads that file to know the export was verified.

## The gradient model

`--model grad` (or `both`) exports a second graph, `lpips_alex_grad.onnx`: the same inputs, and
two outputs, `distance [N]` and `gradient [N,3,H,W]`, the derivative of each distance with respect
to its own candidate's 0..1 pixels. ONNX export traces forward code only, so the backward pass is a
hand-written vector-Jacobian product (`gradient_module`), not autograd.

It is checked against `torch.autograd.grad` on `lpips.LPIPS(net="alex")` itself over the twelve
pairs above and two odd, non-square sizes, and refused unless max |difference| / max |gradient| is
within 1e-4 on every pair. One complication is real and handled, not tolerated: on a
piecewise-constant picture, positions in a max-pool window can be exactly equal, and PyTorch's and
ONNX Runtime's float32 convolutions round them differently (by up to ~1e-5), so each runtime picks
a different maximum and routes that window's gradient to a different, equally valid, position.
Where the two disagree, the check recomputes the activations in float64 and requires the two
positions to be equal there (a true tie), then compares against autograd on the reference module
with its pools reading at ONNX Runtime's argmax. Both the raw and the pinned numbers are recorded.
On 2026-10-07 `stripes-vs-checker@32` had 48 such ties (float64 gap 0), a raw relative difference
of 0.42 and a pinned one of 4.1e-7; across all fourteen pairs the decisive relative error was at
most 5.4e-6. Two exports produced the same 9,915,616 bytes, sha256
8f9569d2fabe9f68cc5fc0e6d071d88347832ff2733589e725d840df53986e7c, which `afield.onnx.Lpips` pins.
"""

from __future__ import annotations

import argparse
import hashlib
from importlib import metadata
import json
import os
import sys
import tempfile
from pathlib import Path

OPSET = 17
TOLERANCE = 1e-4
# The gradient model against torch.autograd.grad: max |difference| over max |gradient|, per pair;
# and an absolute bound for pairs whose true gradient is zero (a picture against itself).
GRAD_REL_TOLERANCE = 1e-4
GRAD_ABS_TOLERANCE = 1e-7
# Where the two runtimes pick different max-pool winners, the two positions must be equal when
# recomputed in float64 (a true tie), to this relative gap.
TIE_GAP_TOLERANCE = 1e-9


def cache_root() -> Path:
    root = os.environ.get("AFIELD_ONNX_CACHE_ROOT")
    if root:
        return Path(root)
    return Path.home() / ".afield" / "onnx" / "models"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def test_pairs(np):
    """Fixed, integer-built image pairs: (name, a, b) with a, b float32 NCHW in 0..1."""
    pairs = []
    for size in (32, 64, 128):
        y, x = np.mgrid[0:size, 0:size]
        disc = ((x - size // 2) ** 2 + (y - size // 2) ** 2 < (size * 9 // 32) ** 2).astype(np.float32)
        ramp = (x * 255 // max(1, size - 1)).astype(np.float32) / 255.0
        stripes = ((x * 6 // size) % 2).astype(np.float32)
        checker = (((x * 4 // size) + (y * 4 // size)) % 2).astype(np.float32)
        noise = ((x * 73 + y * 151 + x * y * 7) % 256).astype(np.float32) / 255.0

        def rgb(r, g, b):
            return np.stack([r, g, b])[None].astype(np.float32)

        yellow_disc = rgb(0.03 + 0.97 * disc, 0.04 + 0.78 * disc, 0.12 + 0.13 * disc)
        pairs.append((f"disc-vs-grey-disc@{size}", yellow_disc, rgb(disc, disc, disc)))
        pairs.append((f"disc-vs-ramp@{size}", yellow_disc, rgb(ramp, ramp * 0.5, 1 - ramp)))
        pairs.append((f"stripes-vs-checker@{size}", rgb(stripes, stripes, 1 - stripes), rgb(checker, 1 - checker, checker)))
        pairs.append((f"noise-vs-itself@{size}", rgb(noise, noise[::-1], noise.T), rgb(noise, noise[::-1], noise.T)))
    return pairs


def export_forward(out: Path, reference_model, np, onnx, ort, torch, lpips) -> int:
    class Exported(torch.nn.Module):
        def __init__(self, inner):
            super().__init__()
            self.inner = inner

        def forward(self, candidate, reference):
            # normalize=True maps 0..1 to the -1..1 the network was trained on.
            d = self.inner(candidate, reference.expand_as(candidate), normalize=True)
            return d.reshape(-1)

    model = Exported(reference_model).eval()
    dummy_a = torch.rand(2, 3, 64, 64)
    dummy_b = torch.rand(1, 3, 64, 64)

    with tempfile.TemporaryDirectory() as tmp:
        tmp_model = Path(tmp) / "lpips_alex.onnx"
        torch.onnx.export(
            model,
            (dummy_a, dummy_b),
            str(tmp_model),
            input_names=["candidate", "reference"],
            output_names=["distance"],
            dynamic_axes={
                "candidate": {0: "n", 2: "height", 3: "width"},
                "reference": {0: "m", 2: "height", 3: "width"},
                "distance": {0: "n"},
            },
            opset_version=OPSET,
            dynamo=False,
        )
        onnx.checker.check_model(onnx.load(str(tmp_model)))

        session = ort.InferenceSession(str(tmp_model), providers=["CPUExecutionProvider"])
        agreement = []
        worst = 0.0
        for name, a, b in test_pairs(np):
            with torch.no_grad():
                want = float(reference_model(torch.from_numpy(a), torch.from_numpy(b), normalize=True).reshape(-1)[0])
            got = float(session.run(["distance"], {"candidate": a, "reference": b})[0][0])
            err = abs(got - want)
            worst = max(worst, err)
            agreement.append({"pair": name, "pytorch": want, "onnx": got, "absError": err})
            print(f"  {name:28s} pytorch {want:.6f}  onnx {got:.6f}  |diff| {err:.2e}")
        # The batch form: three candidates against one broadcast reference, against three singles.
        batch = np.concatenate([p[1] for p in test_pairs(np)[4:7]])
        ref = test_pairs(np)[4][2]
        batched = session.run(["distance"], {"candidate": batch, "reference": ref})[0]
        singles = [session.run(["distance"], {"candidate": batch[i:i + 1], "reference": ref})[0][0] for i in range(3)]
        batch_err = float(max(abs(batched[i] - singles[i]) for i in range(3)))
        print(f"  batch of 3 against one reference vs three singles: max |diff| {batch_err:.2e}")
        if worst > TOLERANCE or batch_err > TOLERANCE:
            print(f"REFUSED: ONNX disagrees with PyTorch LPIPS by {max(worst, batch_err):.2e} > {TOLERANCE}", file=sys.stderr)
            return 1
        tmp_model.replace(out)

    record = {
        "model": "LPIPS v0.1, AlexNet backbone (lpips.LPIPS(net='alex'))",
        "source": "https://github.com/richzhang/PerceptualSimilarity (BSD-2-Clause), via PyPI lpips",
        "backbone": "torchvision AlexNet IMAGENET1K_V1 (BSD-3-Clause)",
        "versions": versions(torch, onnx, ort),
        "opset": OPSET,
        "inputs": "candidate [N,3,H,W] and reference [N or 1,3,H,W], float32 in 0..1",
        "output": "distance [N]",
        "sha256": sha256(out),
        "bytes": out.stat().st_size,
        "tolerance": TOLERANCE,
        "maxAbsError": worst,
        "batchMaxAbsError": batch_err,
        "agreement": agreement,
    }
    out.with_suffix(".json").write_text(json.dumps(record, indent=2) + "\n")
    print(f"wrote {out} ({record['bytes']} bytes, sha256 {record['sha256']})")
    print(f"verified: max |onnx - pytorch| = {worst:.2e} over {len(agreement)} pairs")
    return 0


def versions(torch, onnx, ort) -> dict:
    return {
        "lpips": metadata.version("lpips"),
        "torchvision": metadata.version("torchvision"),
        "torch": torch.__version__,
        "onnx": onnx.__version__,
        "onnxruntime": ort.__version__,
    }


def gradient_module(reference_model, torch):
    """LPIPS and its vector-Jacobian product with respect to the candidate, as a forward module.

    `torch.onnx.export` traces forward code only, so the backward pass is written out by hand,
    layer by layer, in the reverse order of `lpips.LPIPS.forward` with `normalize=True`:

      0..1 input -> 2x - 1 -> ScalingLayer (x - shift) / scale
      -> conv1 (11x11, stride 4, pad 2) relu -> maxpool 3/2 -> conv2 (5x5, pad 2) relu
      -> maxpool 3/2 -> conv3 relu -> conv4 relu -> conv5 relu   (3x3, pad 1 each)

    and at each of the five ReLU outputs `a`: f = a / (|a|_channels + 1e-10), squared difference
    from the reference's f, the 1x1 `lin` weights, the spatial mean, summed over the five stages.

    The backward: d/d f = 2 (f - f_ref) w / (H W); through the channel normalisation,
    d/d a = g / (n + eps) - a (g . a) / ((n + eps)^2 n) with n = |a| (a = 0 where n = 0, so that term
    is zero); ReLU as a mask on a > 0; each convolution by `conv_transpose2d` with its own weights
    (conv1 with output_padding 3 and then cropped to the input, since stride 4 leaves up to three
    rows the transpose does not reach by default); each max pool by scatter-adding into the argmax
    positions the forward recompute returns (`return_indices=True`) — an add, not `max_unpool2d`,
    because the 3x3 stride-2 windows overlap and one input can be the maximum of several; and
    finally the input scaling, 2 / scale per channel.
    """
    alex = reference_model.net
    convs = [m for s in (alex.slice1, alex.slice2, alex.slice3, alex.slice4, alex.slice5)
             for m in s if isinstance(m, torch.nn.Conv2d)]
    assert [tuple(c.weight.shape) for c in convs] == [(64, 3, 11, 11), (192, 64, 5, 5), (384, 192, 3, 3), (256, 384, 3, 3), (256, 256, 3, 3)]
    assert [(c.stride, c.padding) for c in convs] == [((4, 4), (2, 2)), ((1, 1), (2, 2)), ((1, 1), (1, 1)), ((1, 1), (1, 1)), ((1, 1), (1, 1))]
    pools = [m for s in (alex.slice2, alex.slice3) for m in s if isinstance(m, torch.nn.MaxPool2d)]
    assert len(pools) == 2 and all(p.kernel_size == 3 and p.stride == 2 and p.padding == 0 for p in pools)
    lins = [l.model[-1].weight for l in reference_model.lins]
    F = torch.nn.functional

    class LpipsGrad(torch.nn.Module):
        def __init__(self):
            super().__init__()
            self.shift = torch.nn.Parameter(reference_model.scaling_layer.shift.clone(), requires_grad=False)
            self.scale = torch.nn.Parameter(reference_model.scaling_layer.scale.clone(), requires_grad=False)
            for i, c in enumerate(convs):
                setattr(self, f"w{i + 1}", torch.nn.Parameter(c.weight.detach().clone(), requires_grad=False))
                setattr(self, f"b{i + 1}", torch.nn.Parameter(c.bias.detach().clone(), requires_grad=False))
            for i, w in enumerate(lins):
                setattr(self, f"lin{i}", torch.nn.Parameter(w.detach().clone(), requires_grad=False))

        def features(self, image):
            x = ((2 * image - 1) - self.shift) / self.scale
            a1 = F.relu(F.conv2d(x, self.w1, self.b1, stride=4, padding=2))
            p1, i1 = F.max_pool2d(a1, 3, 2, return_indices=True)
            a2 = F.relu(F.conv2d(p1, self.w2, self.b2, padding=2))
            p2, i2 = F.max_pool2d(a2, 3, 2, return_indices=True)
            a3 = F.relu(F.conv2d(p2, self.w3, self.b3, padding=1))
            a4 = F.relu(F.conv2d(a3, self.w4, self.b4, padding=1))
            a5 = F.relu(F.conv2d(a4, self.w5, self.b5, padding=1))
            return [a1, a2, a3, a4, a5], [i1, i2]

        @staticmethod
        def unpool_add(g, indices, like):
            flat = torch.zeros_like(like).flatten(2)
            return flat.scatter_add(2, indices.flatten(2), g.flatten(2)).view_as(like)

        def forward(self, candidate, reference):
            reference = reference.expand_as(candidate)
            acts, (i1, i2) = self.features(candidate)
            refs, _ = self.features(reference)
            eps = 1e-10
            distance = None
            grads = []
            for k in range(5):
                a, r = acts[k], refs[k]
                n = torch.sqrt(torch.sum(a ** 2, dim=1, keepdim=True))
                f = a / (n + eps)
                fr = r / (torch.sqrt(torch.sum(r ** 2, dim=1, keepdim=True)) + eps)
                w = getattr(self, f"lin{k}")
                res = F.conv2d((f - fr) ** 2, w).mean([2, 3], keepdim=True)
                distance = res if distance is None else distance + res
                ones = torch.ones_like(n)
                inv_area = ones / ones.sum(dim=[2, 3], keepdim=True)
                gf = 2 * (f - fr) * w.reshape(1, -1, 1, 1) * inv_area
                n_safe = torch.where(n > 0, n, ones)
                ga = gf / (n + eps) - a * torch.sum(gf * a, dim=1, keepdim=True) / ((n + eps) ** 2 * n_safe)
                grads.append(ga)
            a1, a2, a3, a4, a5 = acts
            zero = torch.zeros_like
            g5 = torch.where(a5 > 0, grads[4], zero(a5))
            g4 = torch.where(a4 > 0, grads[3] + F.conv_transpose2d(g5, self.w5, padding=1), zero(a4))
            g3 = torch.where(a3 > 0, grads[2] + F.conv_transpose2d(g4, self.w4, padding=1), zero(a3))
            gp2 = F.conv_transpose2d(g3, self.w3, padding=1)
            g2 = torch.where(a2 > 0, grads[1] + self.unpool_add(gp2, i2, a2), zero(a2))
            gp1 = F.conv_transpose2d(g2, self.w2, padding=2)
            g1 = torch.where(a1 > 0, grads[0] + self.unpool_add(gp1, i1, a1), zero(a1))
            gx = F.conv_transpose2d(g1, self.w1, stride=4, padding=2, output_padding=3)
            gx = gx[:, :, : candidate.size(2), : candidate.size(3)]
            gradient = gx * (2 / self.scale)
            return distance.reshape(-1), gradient

    return LpipsGrad().eval()


def autograd_reference(reference_model, torch, a, b, pinned=None):
    """distance and d distance / d candidate from `torch.autograd.grad` on the reference module.

    [pinned], when given, is the argmax of each of AlexNet's two max pools (as `max_pool2d`'s
    `return_indices` gives them, per plane): the reference module then runs unchanged except that
    each pool reads its input at those positions instead of choosing them itself. That is the
    max pool's own subgradient at a different (tied) argmax, nothing else. The reference image's
    pass is not pinned.
    """
    alex = reference_model.net
    saved = (alex.slice2[0], alex.slice3[0])

    class Pinned(torch.nn.Module):
        """The candidate's pass reads at [indices]; the reference's (the second call) pools as usual."""

        def __init__(self, indices, pool):
            super().__init__()
            self.indices = torch.from_numpy(indices)
            self.pool = pool
            self.calls = 0

        def forward(self, x):
            self.calls += 1
            if self.calls > 1:
                return self.pool(x)
            n, c, ho, wo = self.indices.shape
            return torch.gather(x.flatten(2), 2, self.indices.flatten(2)).view(n, c, ho, wo)

    try:
        if pinned is not None:
            alex.slice2[0] = Pinned(pinned[0], saved[0])
            alex.slice3[0] = Pinned(pinned[1], saved[1])
        x = torch.from_numpy(a).clone().requires_grad_(True)
        d = reference_model(x, torch.from_numpy(b), normalize=True).reshape(-1)
        (g,) = torch.autograd.grad(d.sum(), x)
    finally:
        alex.slice2[0], alex.slice3[0] = saved
    return float(d.detach()[0]), g.numpy()


def pool_ties(np, torch, model, a, onnx_indices):
    """Where ONNX Runtime's max-pool argmax differs from PyTorch's, and whether each is a true tie.

    Returns (disagreements, worst relative gap). The gap is measured on the activations recomputed
    in float64: |value at PyTorch's argmax - value at ORT's argmax| over the window maximum. On a
    piecewise-constant picture two positions in a pool window can be mathematically equal; each
    runtime's float32 convolution rounds them differently (by up to ~1e-5 relative here) and so
    picks a different maximum. A float64 gap near 1e-15 says the two really are equal, and either
    choice is the max pool's subgradient.
    """
    import copy

    model64 = copy.deepcopy(model).double()
    with torch.no_grad():
        _, indices = model.features(torch.from_numpy(a))
        acts64, _ = model64.features(torch.from_numpy(a).double())
    count, worst = 0, 0.0
    for act, mine, theirs in zip((acts64[0], acts64[1]), indices, onnx_indices):
        mine = mine.numpy()
        differ = mine != theirs
        count += int(differ.sum())
        if differ.any():
            flat = act.flatten(2).numpy()
            at_mine = np.take_along_axis(flat, mine.reshape(*mine.shape[:2], -1), 2).reshape(mine.shape)
            at_theirs = np.take_along_axis(flat, theirs.reshape(*theirs.shape[:2], -1), 2).reshape(theirs.shape)
            gap = np.abs(at_mine - at_theirs)[differ] / np.maximum(np.abs(at_mine[differ]), 1e-30)
            worst = max(worst, float(gap.max()))
    return count, worst


def grad_check_pairs(np):
    """The twelve forward pairs, plus two odd, non-square sizes that exercise conv1's crop."""
    pairs = list(test_pairs(np))
    for h, w in ((45, 38), (50, 67)):
        y, x = np.mgrid[0:h, 0:w]
        blob = (((x - w / 2) ** 2 + (y - h / 3) ** 2) < (min(h, w) / 3) ** 2).astype(np.float32)
        noise = ((x * 73 + y * 151 + x * y * 7) % 256).astype(np.float32) / 255.0
        a = np.stack([blob, 0.5 * blob + 0.2, noise])[None].astype(np.float32)
        b = np.stack([noise, blob, 1 - blob])[None].astype(np.float32)
        pairs.append((f"blob-vs-noise@{h}x{w}", a, b))
    return pairs


def export_grad(out: Path, reference_model, np, onnx, ort, torch) -> int:
    model = gradient_module(reference_model, torch)
    dummy_a = torch.rand(2, 3, 64, 64)
    dummy_b = torch.rand(1, 3, 64, 64)
    with tempfile.TemporaryDirectory() as tmp:
        tmp_model = Path(tmp) / "lpips_alex_grad.onnx"
        torch.onnx.export(
            model,
            (dummy_a, dummy_b),
            str(tmp_model),
            input_names=["candidate", "reference"],
            output_names=["distance", "gradient"],
            dynamic_axes={
                "candidate": {0: "n", 2: "height", 3: "width"},
                "reference": {0: "m", 2: "height", 3: "width"},
                "distance": {0: "n"},
                "gradient": {0: "n", 2: "height", 3: "width"},
            },
            opset_version=OPSET,
            dynamo=False,
        )
        onnx.checker.check_model(onnx.load(str(tmp_model)))
        session = ort.InferenceSession(str(tmp_model), providers=["CPUExecutionProvider"])

        agreement = []
        worst_abs = 0.0
        worst_rel = 0.0
        worst_distance = 0.0
        refused = []
        # A second graph, for the check only, returning the two max pools' argmax as ONNX Runtime
        # computes them: on piecewise-constant pictures the pool windows hold exact ties, broken by
        # each runtime's rounding, and a tie broken differently routes the gradient elsewhere.
        class Argmax(torch.nn.Module):
            def __init__(self, inner):
                super().__init__()
                self.inner = inner

            def forward(self, candidate):
                return tuple(self.inner.features(candidate)[1])

        argmax_model = Path(tmp) / "argmax.onnx"
        torch.onnx.export(Argmax(model), (dummy_a,), str(argmax_model), input_names=["candidate"],
                          output_names=["i1", "i2"], dynamic_axes={"candidate": {0: "n", 2: "height", 3: "width"}},
                          opset_version=OPSET, dynamo=False)
        argmax = ort.InferenceSession(str(argmax_model), providers=["CPUExecutionProvider"])

        for name, a, b in grad_check_pairs(np):
            want_d, want_g = autograd_reference(reference_model, torch, a, b)
            got_d, got_g = session.run(["distance", "gradient"], {"candidate": a, "reference": b})
            got_d = float(got_d[0])
            assert got_g.shape == a.shape, (name, got_g.shape, a.shape)
            if not np.isfinite(want_g).all():
                print(f"REFUSED: autograd gradient for {name} is not finite", file=sys.stderr)
                return 1
            abs_err = float(np.abs(got_g - want_g).max())
            scale = float(np.abs(want_g).max())
            rel_err = abs_err / scale if scale > 0 else 0.0
            d_err = abs(got_d - want_d)
            ties, tie_gap = pool_ties(np, torch, model, a, argmax.run(["i1", "i2"], {"candidate": a}))
            if ties:
                # The comparison that decides: autograd on the reference module with its pools'
                # argmax pinned to ORT's, allowed only where ORT's choice is a tie to rounding.
                _, pinned_g = autograd_reference(reference_model, torch, a, b, argmax.run(["i1", "i2"], {"candidate": a}))
                tie_abs = float(np.abs(got_g - pinned_g).max())
                tie_scale = float(np.abs(pinned_g).max())
                tie_rel = tie_abs / tie_scale if tie_scale > 0 else 0.0
            else:
                tie_abs, tie_scale, tie_rel = abs_err, scale, rel_err
            ok = (np.isfinite(got_g).all() and d_err <= TOLERANCE and tie_gap <= TIE_GAP_TOLERANCE and
                  (tie_rel <= GRAD_REL_TOLERANCE if tie_scale > 0 else tie_abs <= GRAD_ABS_TOLERANCE))
            if not ok:
                refused.append(name)
            worst_abs = max(worst_abs, tie_abs)
            worst_rel = max(worst_rel, tie_rel)
            worst_distance = max(worst_distance, d_err)
            agreement.append({"pair": name, "distance": want_d, "distanceAbsError": d_err, "gradientMaxAbs": scale,
                              "gradientMaxAbsError": tie_abs, "gradientRelError": tie_rel,
                              "poolTiesBrokenDifferently": ties, "poolTieRelGap": tie_gap,
                              "unpinnedGradientMaxAbsError": abs_err, "unpinnedGradientRelError": rel_err})
            tie_note = f"  [{ties} pool ties (gap {tie_gap:.1e}); unpinned max|diff| {abs_err:.2e} rel {rel_err:.2e}]" if ties else ""
            print(f"  {name:28s} d {want_d:.6f} |dd| {d_err:.1e}  max|grad| {tie_scale:.3e}  "
                  f"max|diff| {tie_abs:.2e}  rel {tie_rel:.2e}{tie_note}{'' if ok else '  OUT OF TOLERANCE'}")
        # Batch: three candidates against one broadcast reference, and against three references.
        pairs = test_pairs(np)
        batch = np.concatenate([p[1] for p in pairs[4:7]])
        refs = np.concatenate([p[2] for p in pairs[4:7]])
        _, broadcast_g = session.run(["distance", "gradient"], {"candidate": batch, "reference": refs[:1]})
        _, paired_g = session.run(["distance", "gradient"], {"candidate": batch, "reference": refs})
        batch_err = 0.0
        for i in range(3):
            _, single_b = session.run(["distance", "gradient"], {"candidate": batch[i:i + 1], "reference": refs[:1]})
            _, single_p = session.run(["distance", "gradient"], {"candidate": batch[i:i + 1], "reference": refs[i:i + 1]})
            batch_err = max(batch_err, float(np.abs(broadcast_g[i] - single_b[0]).max()), float(np.abs(paired_g[i] - single_p[0]).max()))
        print(f"  batched gradients vs one at a time (broadcast and paired references): max |diff| {batch_err:.2e}")
        if refused or batch_err > GRAD_ABS_TOLERANCE:
            print(f"REFUSED: the ONNX gradient disagrees with torch.autograd.grad on {refused or ['batching']} "
                  f"(worst relative {worst_rel:.2e} > {GRAD_REL_TOLERANCE}, abs {worst_abs:.2e}, batch {batch_err:.2e})", file=sys.stderr)
            return 1
        tmp_model.replace(out)

    record = {
        "model": "LPIPS v0.1 AlexNet and its gradient with respect to the candidate (hand-written vector-Jacobian product)",
        "source": "https://github.com/richzhang/PerceptualSimilarity (BSD-2-Clause), via PyPI lpips",
        "backbone": "torchvision AlexNet IMAGENET1K_V1 (BSD-3-Clause)",
        "versions": versions(torch, onnx, ort),
        "opset": OPSET,
        "inputs": "candidate [N,3,H,W] and reference [N or 1,3,H,W], float32 in 0..1",
        "outputs": "distance [N]; gradient [N,3,H,W] = d distance[i] / d candidate[i]",
        "sha256": sha256(out),
        "bytes": out.stat().st_size,
        "gradientRelTolerance": GRAD_REL_TOLERANCE,
        "gradientAbsTolerance": GRAD_ABS_TOLERANCE,
        "maxGradientAbsError": worst_abs,
        "maxGradientRelError": worst_rel,
        "maxDistanceAbsError": worst_distance,
        "batchMaxAbsError": batch_err,
        "agreement": agreement,
    }
    out.with_suffix(".json").write_text(json.dumps(record, indent=2) + "\n")
    print(f"wrote {out} ({record['bytes']} bytes, sha256 {record['sha256']})")
    print(f"verified: gradient max |onnx - autograd| = {worst_abs:.2e}, relative {worst_rel:.2e}, over {len(agreement)} pairs")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--model", choices=["forward", "grad", "both"], default="forward",
                        help="which graph to export: the distance (default), its gradient, or both")
    parser.add_argument("--out", type=Path, default=None, help="forward model path (default: <cache>/lpips-alex/lpips_alex.onnx)")
    parser.add_argument("--grad-out", type=Path, default=None,
                        help="gradient model path (default: <cache>/lpips-alex/lpips_alex_grad.onnx)")
    args = parser.parse_args()

    import numpy as np
    import onnx
    import onnxruntime as ort
    import torch
    import lpips

    reference_model = lpips.LPIPS(net="alex", version="0.1", verbose=False).eval()

    if args.model in ("forward", "both"):
        out = args.out or (cache_root() / "lpips-alex" / "lpips_alex.onnx")
        out.parent.mkdir(parents=True, exist_ok=True)
        code = export_forward(out, reference_model, np, onnx, ort, torch, lpips)
        if code != 0:
            return code
    if args.model in ("grad", "both"):
        out = args.grad_out or (cache_root() / "lpips-alex" / "lpips_alex_grad.onnx")
        out.parent.mkdir(parents=True, exist_ok=True)
        return export_grad(out, reference_model, np, onnx, ort, torch)
    return 0

if __name__ == "__main__":
    code = main()
    sys.stdout.flush()
    sys.stderr.flush()
    # os._exit, not sys.exit: with torch and onnxruntime both loaded, interpreter teardown can abort
    # in a native destructor ("recursive_mutex lock failed") after the model is written and
    # verified, turning a good export into exit code 134.
    os._exit(code)
