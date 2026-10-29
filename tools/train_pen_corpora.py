"""Train the synthetic pens that ship with Field, from the corpora `PenCorpora` writes.

    uv run --project . --group backdoor python tools/field_backdoor.py --json \\
        --command "run-jvm-main afield.ai.learning.pen.PenCorpora --arg $PWD/build/pen-corpora --timeout 1500"
    uv run --project . --group pen python tools/train_pen_corpora.py [--device mps] [--threads N] build/pen-corpora [names...]

For each corpus: `fieldpen.prepare` its train/ and valid/ folders, train (from scratch, or
fine-tuning the Distill handwriting model where `RECIPES` says so), and export the best checkpoint,
in float16, to `src/jvmMain/resources/afield/pen/models/<name>.fieldpen.json` with the run's settings
and losses in its metadata. `PenModel.bundled(name)` loads the result.
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "python"))

DISTILL = ROOT / "src/jvmMain/resources/afield/pen/distill-b64.json"
OUT = ROOT / "src/jvmMain/resources/afield/pen/models"

# name: (corpus, fine-tune the handwriting model?, epochs, learning rate, sense sectors). Every run
# trains on random 256-point windows (2048 a epoch, 64 a batch) with a little input noise: whole
# drawings give too few optimizer steps, and a pen trained on noiseless inputs never learns to recover
# from its own small errors, so it drifts off the page instead of closing a figure. A pen with sense
# sectors also sees the ink around it (fieldpen.sense, radius 3 model units, ignoring its last 8
# points); each corpus that needs sight is trained twice, seeing and blind, to show what sight adds.
RECIPES = {
    "hershey": ("hershey", True, 40, 5e-4, 0),
    "harmonograph": ("harmonograph", False, 200, 2e-3, 0),
    "plants": ("plants", False, 200, 2e-3, 0),
    "truchet": ("truchet", False, 200, 2e-3, 0),
    "boids": ("boids", False, 200, 2e-3, 0),
    "mixed": ("mixed", False, 200, 2e-3, 0),
    "rounder": ("rounder", True, 30, 5e-4, 0),
    "michaux": ("michaux", False, 200, 2e-3, 16),
    "michaux-blind": ("michaux", False, 200, 2e-3, 0),
    "cracks": ("cracks", False, 200, 2e-3, 16),
    "cracks-blind": ("cracks", False, 200, 2e-3, 0),
    # the habit for guided hatching (afield.ai.learning.pen.Hatcher); unguided it cannot hatch,
    # so it has no blind twin
    "echoes": ("echoes", False, 200, 2e-3, 16),
}


def main() -> int:
    args = sys.argv[1:]
    device = "cpu"
    threads = "8"
    while args and args[0].startswith("--"):
        flag, value, *args = args
        device = value if flag == "--device" else device
        threads = value if flag == "--threads" else threads
    corpora = Path(args[0] if args else ROOT / "build/pen-corpora").resolve()
    names = args[1:] or list(RECIPES)
    import fieldpen

    OUT.mkdir(parents=True, exist_ok=True)
    for name in names:
        corpus, finetune, epochs, lr, sense = RECIPES[name]
        for split in ("train", "valid"):
            subprocess.run([sys.executable, "-m", "fieldpen.prepare", str(corpora / corpus / split),
                            str(corpora / f"{name}_{split}.npz"), "--scale", "8"]
                           + (["--sense", str(sense), "--sense-radius", "3", "--sense-recent", "8"] if sense else []),
                           check=True, cwd=ROOT / "python", stdout=subprocess.DEVNULL)
        run = ROOT / "build/pen-runs" / name
        command = [sys.executable, "-m", "fieldpen.train",
                   "--train", str(corpora / f"{name}_train.npz"), "--valid", str(corpora / f"{name}_valid.npz"),
                   "--out", str(run), "--epochs", str(epochs), "--lr", str(lr), "--threads", threads,
                   "--device", device, "--batch", "64", "--crop", "256", "--samples", "2048",
                   "--input-noise", "0.02"]
        command += ["--init", str(DISTILL)] if finetune else ["--hidden", "256"]
        if (run / "metrics.jsonl").exists():
            (run / "metrics.jsonl").unlink()
        subprocess.run(command, check=True, cwd=ROOT / "python", stdout=subprocess.DEVNULL)
        metrics = [json.loads(l) for l in (run / "metrics.jsonl").read_text().splitlines()]
        best = min(metrics, key=lambda m: m["metrics"]["valid"])
        model = fieldpen.load_model(run / "best.pt")
        fieldpen.export_for_field(model, OUT / f"{name}.fieldpen.json", half=True, metadata=dict(
            corpus=corpus, sense_sectors=sense, finetuned_from="distill" if finetune else None, epochs=epochs, lr=lr,
            crop=256, input_noise=0.02, best_epoch=best["epoch"], valid_nll=best["metrics"]["valid"],
            first_valid_nll=metrics[0]["metrics"]["valid"]))
        print(f"{name}: best epoch {best['epoch']} valid {best['metrics']['valid']:.3f} -> {OUT / name}.fieldpen.json",
              flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
