"""Record the pose stream that public/docs/chain_tracked_body.md replays.

Runs fieldcv's mediapipe Pose detector (heavy model) over the public-domain
`ballet_mecanique_swing` clip and writes one JSON file: per frame, either null
(no body found) or 33 landmarks as [x, y, z, visibility], rounded to 4 decimals.
x and y are normalized image coordinates (0..1, y down); z is mediapipe's
relative depth on the same scale as x.

    uv run --project . --group cv python templates/chain_tracked_body/record_pose.py
"""
import json
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
CLIP = "fieldcv/samples/ballet_mecanique_swing.mp4"
OUT = ROOT / "public/docs/assets/chain_tracked_body/swing_pose.json"

run = subprocess.run(
    [sys.executable, "-m", "fieldcv", "--source", CLIP, "--pose", "--pose-model", "heavy"],
    cwd=ROOT / "python", check=True, capture_output=True, text=True)
frames = []
width = height = None
for line in run.stdout.splitlines():
    f = json.loads(line)
    width, height = f["width"], f["height"]
    if not f["poses"]:
        frames.append(None)
        continue
    pose, vis = f["poses"][0], f["visibility"][0]
    frames.append([[round(p[0], 4), round(p[1], 4), round(p[2], 4), round(v, 4)] for p, v in zip(pose, vis)])
OUT.write_text(json.dumps({"source": CLIP, "detector": "fieldcv.Pose(model=\"heavy\")",
                           "fps": 25, "width": width, "height": height, "frames": frames},
                          separators=(",", ":")) + "\n")
print(f"{OUT}: {len(frames)} frames, {sum(f is not None for f in frames)} with a body")
