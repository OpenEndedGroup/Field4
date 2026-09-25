#!/usr/bin/env python3
"""Regenerate direct-lighting docs from a running, isolated Field with the optional SDK.

Run through uv. --port must name a Field launched from this checkout in a graphical
session. GPU quality gates must pass before their captures are published.
"""
import argparse
import json
import pathlib
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
ARTIFACTS = ROOT / "build/lighting-experiment"
ASSETS = ROOT / "public/docs/assets/direct_lighting"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--only", choices=["gpu", "controls", "all"], default="all")
    args = parser.parse_args()
    ARTIFACTS.mkdir(parents=True, exist_ok=True)

    def backdoor(label, commands, browser=False):
        command = [sys.executable, str(ROOT / "tools/field_backdoor.py"), "--json",
                   "--attach-existing", "--port", str(args.port), "--timeout", "900"]
        if browser:
            command += ["--with-playwright"]
        for item in commands:
            command += ["--command", item]
        log = ARTIFACTS / (label + ".json")
        with log.open("w") as stdout, log.with_suffix(".stderr").open("w") as stderr:
            subprocess.run(command, cwd=ROOT, stdout=stdout, stderr=stderr, check=True)
        text = log.read_text()
        remaining = text
        decoder = json.JSONDecoder()
        while remaining.strip():
            result, end = decoder.raw_decode(remaining.lstrip())
            assert result["ok"], log
            remaining = remaining.lstrip()[end:]
        assert "VUID-" not in text, f"Vulkan validation error: {log}"
        print(f"Passed {label}: {log}", flush=True)

    if args.only in ("all", "gpu"):
        backdoor("quality-run", ["run-jvm-main afield.four.lighting.DirectLightingQualitySmoke --arg=--clip --timeout 840"])
        quality = ARTIFACTS / "quality"
        for name in ("unshadowed", "shadow", "moved-light", "normals", "visibility", "rtxdi-initial",
                     "rtxdi-full", "rtxdi-reconstructed", "rtxdi-reference", "rtxdi-history-rejection",
                     "rtxdi-light-id", "rtxdi-reservoir-m", "rtxdi-reservoir-weight", "rtxdi-motion"):
            shutil.copyfile(quality / f"{name}.png", ASSETS / f"{name}.png")
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-framerate", "30", "-i",
                        str(quality / "clip/%04d.png"), "-c:v", "libx264", "-crf", "17",
                        "-pix_fmt", "yuv420p", "-movflags", "+faststart",
                        str(ASSETS / "rtxdi-moving.mp4")], check=True)
        shutil.copyfile(quality / "measurements.json", ROOT / "dev_docs/lighting/results/quality-measurements.json")

    if args.only in ("all", "controls"):
        # Exercise the real editable Starting Point, including inherited renderer access.
        baseline = ARTIFACTS / "baseline-controls.kt"
        example = (ROOT / "dev_docs/agents/demos/direct_lighting/example.kt").read_text()
        baseline.write_text(example.replace("val sdkReady = lab.visibility.available",
                                            "val sdkReady = false && lab.visibility.available"))
        backdoor("controls-capture", [
            "macro write direct_lighting --file dev_docs/agents/transcripts/direct_lighting.yaml",
            "macro run direct_lighting",
            "wait-native-renderer agent_graphics_context --frames 20",
            "page-eval --file tools/lighting/capture_background.js",
            f"screenshot-box direct_light_lab {ASSETS / 'rtxdi-controls.png'} --padding 12",
            f"native-screenshot {ASSETS / 'rtxdi-live.png'} --uid agent_graphics_context",
            "stop direct_light_lab",
            f"launch direct_light_lab --file {baseline}",
            "wait-native-renderer agent_graphics_context --frames 20",
            f"screenshot-box direct_light_lab {ASSETS / 'controls.png'} --padding 12",
            "stop direct_light_lab",
            "stop agent_graphics_context",
        ], browser=True)


if __name__ == "__main__":
    main()
