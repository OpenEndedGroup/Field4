#!/usr/bin/env python3
"""Encode already-verified Field reconstruction captures; --run PORT first recaptures through Field.

Use uv run --project . --group docs python tools/lighting/capture_reconstruction.py.
No synthetic pictures: all panels come from ReconstructionSmoke's actual Vulkan image readbacks.
"""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / "build/reconstruction"
ASSETS = ROOT / "public/docs/assets/neural_reconstruction"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", type=int, metavar="PORT")
    parser.add_argument("--stress", action="store_true", help="Capture Performance, Ultra Performance and the actual low-resolution source")
    args = parser.parse_args()
    work = WORK / "stress" if args.stress else WORK
    work.mkdir(parents=True, exist_ok=True)
    prefix = "stress-" if args.stress else ""
    log = work / "verified-clips.json"
    if args.run:
        with log.open("w") as output:
            subprocess.run([sys.executable, "tools/field_backdoor.py", "--json", "--attach-existing",
                            "--port", str(args.run), "--timeout", "900", "--command",
                            "run-jvm-main afield.four.reconstruction.ReconstructionSmoke --arg=--clip " +
                            ("--arg=--stress " if args.stress else "") + "--timeout 840"],
                           cwd=ROOT, stdout=output, check=True)
    result = json.loads(log.read_text())
    text = result["data"].get("stdout", "") + result["data"].get("stderr", "")
    assert result["ok"] and result["data"]["exitCode"] == 0 and "RECONSTRUCTION CHECK DONE" in text
    assert "VUID-" not in text, "Fix Vulkan validation errors before publishing captures"
    ASSETS.mkdir(parents=True, exist_ok=True)
    modes = ["bypass", "performance", "ultra_performance"] if args.stress else ["bypass", "dlaa", "quality", "performance"]
    measurements = json.loads((work / "measurements.json").read_text())
    for mode in modes:
        for suffix in ["", "-input", "-geometry", "-abstract"]:
            shutil.copyfile(work / f"{mode}{suffix}.png", ASSETS / f"{prefix}{mode}{suffix}.png")
    shutil.copyfile(work / "reference-4spp.png", ASSETS / f"{prefix}reference-4spp.png")
    guide = "ultra_performance" if args.stress else "dlaa"
    metadata = json.loads((work / f"{guide}-inputs.json").read_text())
    width, height = metadata["width"], metadata["height"]
    depth = np.fromfile(work / f"{guide}-depth.f32", dtype="<f4").reshape(height, width)
    motion = np.fromfile(work / f"{guide}-motion.f32", dtype="<f4").reshape(height, width, 2)
    foreground = depth < .999
    rgb = np.zeros((height, width, 3), dtype=np.uint8)
    rgb[:] = [20, 29, 43]
    velocity_rgb = np.stack([.5 + motion[:, :, 0] / 16, .5 + motion[:, :, 1] / 16, np.zeros_like(depth) + .25], -1)
    rgb[foreground] = np.clip(velocity_rgb[foreground] * 255, 0, 255).astype("uint8")
    Image.fromarray(rgb).save(ASSETS / f"{prefix}motion.png")
    view_z = .1 / (1 - depth * .999)
    gray = np.clip((1 - np.log1p(view_z) / np.log(21)) * 255, 0, 255).astype("uint8")
    gray[~foreground] = 0
    Image.fromarray(gray).save(ASSETS / f"{prefix}depth.png")
    titles = {"bypass": "Source", "dlaa": "DLAA", "quality": "Quality", "performance": "Performance",
              "ultra_performance": "Ultra"}
    labels = [f"{titles[m]} {measurements[m]['inputWidth']} x {measurements[m]['inputHeight']}" for m in modes]
    if args.stress:
        low = measurements["ultra_performance"]
        labels.append(f"Ultra source {low['inputWidth']} x {low['inputHeight']} - nearest neighbour enlargement")
    for scene in ["geometry", "abstract"]:
        command = ["ffmpeg", "-y", "-loglevel", "error"]
        for mode in modes:
            frames = work / "clips" / f"{mode}-{scene}"
            assert len(list(frames.glob("*.png"))) == 120, frames
            command += ["-framerate", "30", "-i", str(frames / "%04d.png")]
        if args.stress:
            frames = work / "clips" / f"ultra_performance-{scene}-input"
            assert len(list(frames.glob("*.png"))) == 120, frames
            command += ["-framerate", "30", "-i", str(frames / "%04d.png")]
        filters = [f"[{i}:v]" + ("scale=1920:1080:flags=neighbor," if args.stress and i == 3 else "") + f"drawtext=text='{label}':font=monospace:fontsize=32:fontcolor=white:box=1:"
                   f"boxcolor=black@0.75:boxborderw=10:x=24:y=24[v{i}]" for i, label in enumerate(labels)]
        filters.append("[v0][v1][v2][v3]xstack=inputs=4:layout=0_0|w0_0|0_h0|w0_h0[out]")
        command += ["-filter_complex", ";".join(filters), "-map", "[out]", "-c:v", "libx264",
                    "-preset", "fast", "-crf", "18", "-pix_fmt", "yuv420p", "-movflags", "+faststart",
                    str(ASSETS / f"{prefix}{scene}.mp4")]
        subprocess.run(command, check=True)
    destination = ROOT / "dev_docs/lighting/results/reconstruction"
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(work / "measurements.json", destination / f"{prefix}measurements.json")
    shutil.copyfile(destination / f"{prefix}measurements.json", ASSETS / f"{prefix}measurements.json")
    (destination / f"{prefix}verification.json").write_text(json.dumps({"entrypoint": "afield.four.reconstruction.ReconstructionSmoke",
        "exitCode": 0, "completionMarker": True, "vulkanValidationErrors": 0, "framesPerClip": 120,
        "output": [1920, 1080], "comparisonVideo": [3840, 2160], "rayTracingEnabled": False}, indent=2) + "\n")
    print(f"Verified reconstruction captures published to {ASSETS}")


if __name__ == "__main__":
    main()
