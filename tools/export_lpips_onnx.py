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


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--out", type=Path, default=None, help="model path (default: <cache>/lpips-alex/lpips_alex.onnx)")
    args = parser.parse_args()

    import numpy as np
    import onnx
    import onnxruntime as ort
    import torch
    import lpips

    out = args.out or (cache_root() / "lpips-alex" / "lpips_alex.onnx")
    out.parent.mkdir(parents=True, exist_ok=True)

    reference_model = lpips.LPIPS(net="alex", version="0.1", verbose=False).eval()

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
        "versions": {
            "lpips": metadata.version("lpips"),
            "torchvision": metadata.version("torchvision"),
            "torch": torch.__version__,
            "onnx": onnx.__version__,
            "onnxruntime": ort.__version__,
        },
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


if __name__ == "__main__":
    code = main()
    sys.stdout.flush()
    sys.stderr.flush()
    # os._exit, not sys.exit: with torch and onnxruntime both loaded, interpreter teardown can abort
    # in a native destructor ("recursive_mutex lock failed") after the model is written and
    # verified, turning a good export into exit code 134.
    os._exit(code)
