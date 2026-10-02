#!/usr/bin/env python3
"""Reproduce the documented Starting Point, then publish its actual GPU comparison frames.

DISPLAY=:0 ... uv run --project . --group docs python tools/opendlssnr/capture_study.py --port 9491
Stills also have diffstatic_runner fences on open_dlss_nr.md. No optional downloads happen here.
"""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys
from PIL import Image, ImageChops, ImageStat

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "public/docs/assets/opendlssnr"


def read_json_stream(text):
    decoder = json.JSONDecoder()
    values = []
    while text.strip():
        text = text.lstrip()
        value, end = decoder.raw_decode(text)
        values.append(value)
        text = text[end:]
    return values


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=9491)
    parser.add_argument("--scene", choices=("ink", "cubes"), default="ink")
    parser.add_argument("--publish-only", action="store_true", help="Validate and encode a completed run for the selected scene in build/opendlssnr-study or build/opendlssnr-cubes")
    args = parser.parse_args()
    WORK = ROOT / ("build/opendlssnr-study" if args.scene == "ink" else "build/opendlssnr-cubes")
    transcript = "open_dlss_nr" if args.scene == "ink" else "open_dlss_nr_cubes"
    prefix = "study" if args.scene == "ink" else "cubes"
    WORK.mkdir(parents=True, exist_ok=True)
    log = WORK / "capture-log.json"
    if not args.publish_only:
        # Clearing only this tool's numbered frames avoids publishing a partly overwritten clip.
        clip = WORK / "clip"
        clip.mkdir(exist_ok=True)
        for frame in clip.glob("frame-*.png"):
            frame.unlink()
        commands = [
            f"macro write {transcript} --file dev_docs/agents/transcripts/{transcript}.yaml",
            f"macro run {transcript}",
            "wait-running open_dlss_nr --timeout 120",
            "wait-native-renderer agent_graphics_context --frames 10",
            f"native-screenshot {WORK / 'study.png'} --uid agent_graphics_context --wait-frames 3",
            "launch nr_record",
            'wait-output nr_record --kind all --contains "NR STUDY RECORD DONE" --timeout 240',
            "stop open_dlss_nr",
            "stop agent_graphics_context",
        ]
        command = [sys.executable, str(ROOT / "tools/field_backdoor.py"), "--json", "--port", str(args.port), "--timeout", "300"]
        for part in commands:
            command += ["--command", part]
        with log.open("w") as output:
            subprocess.run(command, cwd=ROOT, stdout=output, check=True)
    text = log.read_text()
    results = read_json_stream(text)
    assert len(results) == 9 and all(value.get("ok") for value in results), "A Field operation failed; inspect capture-log.json"
    assert "NR STUDY RECORD DONE" in text and "VUID-" not in text
    events = [event for result in results for event in result.get("events", [])]
    assert not any(event.get("kind") == "error" for event in events), "Field reported an execution error"
    paths = sorted((WORK / "clip").glob("frame-*.png"))
    assert [p.name for p in paths] == [f"frame-{i:03}.png" for i in range(180)]
    still_size=Image.open(WORK / "study.png").size
    assert abs(still_size[0]/still_size[1]-2304/832)<.005, "Native window aspect differs from the comparison"
    means = []
    grade_means = []
    moving_means = []
    previous = None
    # Ignore the headings/separators when measuring whether the network changes the image.
    for path in paths:
        with Image.open(path) as image:
            assert image.size == (2304, 832)
            image = image.convert("RGB")
            source = image.crop((2, 64, 768, 832))
            neural = image.crop((770, 64, 1536, 832))
            graded = image.crop((1538, 64, 2304, 832))
            means.append(sum(ImageStat.Stat(ImageChops.difference(source, neural)).mean) / 3)
            grade_means.append(sum(ImageStat.Stat(ImageChops.difference(neural, graded)).mean) / 3)
            if previous is not None:
                moving_means.append(sum(ImageStat.Stat(ImageChops.difference(previous, source)).mean) / 3)
            previous = source.copy()
    assert min(means) > 5, "NR/source comparison is too weak to publish"
    assert min(grade_means) > 5, "Grade comparison is too weak to publish"
    assert min(moving_means) > .05, "Repeated or static source frames"
    report = dict(transcript=f"dev_docs/agents/transcripts/{transcript}.yaml", captureBox="nr_record",
                  frames=180, fps=30, panelSize=[768, 768], comparisonSize=[2304, 832], nativeWindowCaptureSize=list(still_size),
                  style=1, tone=0, structure=10, intensity=1, seed=1, temporal=False,
                  grade=[.65, 1.1, 1.4], source="editable Field raster geometry" if args.scene == "cubes" else "editable Field fragment shader",
                  meanAbsoluteSrgbCodeChange=sum(means)/len(means),
                  minimumFrameSrgbCodeChange=min(means), meanGradeCodeChange=sum(grade_means)/len(grade_means),
                  minimumConsecutiveSourceCodeChange=min(moving_means),
                  frameReadback="Field RenderToTexture.readPixelsAsync after frame GPU completion",
                  encode="libx264, crf 16, yuv420p, no scaling")
    (WORK / "measurements.json").write_text(json.dumps(report, indent=2) + "\n")
    movie = WORK / f"{args.scene}-study.mp4"
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-framerate", "30", "-i", str(WORK / "clip/frame-%03d.png"),
                    "-c:v", "libx264", "-preset", "slow", "-crf", "16", "-pix_fmt", "yuv420p",
                    "-movflags", "+faststart", str(movie)], check=True)
    probe = json.loads(subprocess.check_output(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                                              "stream=width,height,nb_frames,r_frame_rate,duration", "-of", "json", str(movie)]))['streams'][0]
    assert (probe['width'], probe['height'], int(probe['nb_frames']), probe['r_frame_rate']) == (2304, 832, 180, '30/1')
    ASSETS.mkdir(parents=True, exist_ok=True)
    # Replace complete artifacts only after the run and encoder checks pass.
    for source, name in [(movie, f"{args.scene}-study.mp4"), (WORK / "study.png", f"{prefix}.png"), (WORK / "measurements.json", f"{prefix}-measurements.json")]:
        staging = ASSETS / (name + ".pending")
        shutil.copyfile(source, staging)
        staging.replace(ASSETS / name)
    print(f"NR STUDY PUBLISHED: 180 actual Field frames, 2304 × 832; mean source/NR change={report['meanAbsoluteSrgbCodeChange']:.2f} codes")


if __name__ == "__main__":
    main()
