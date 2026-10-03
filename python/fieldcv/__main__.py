"""``python -m fieldcv``: run detectors over a source outside Field and print one JSON object per frame.

This is pythonhead's old command line, kept for batch runs and for anything that still reads the
JSON-lines stream. Inside Field, use :class:`fieldcv.Pipeline` from a Python box instead.

    python -m fieldcv --source movie.mp4 --tracks --pose > out.jsonl
    python -m fieldcv --source 'frames/*.png' --background --contours --save-frames out/
"""

from __future__ import annotations

import argparse
import json
import os
import sys

import cv2

from . import detectors as d
from .pipeline import Pipeline
from .sources import open_source
from .util import jsonable


def build(args) -> list[d.Detector]:
    out: list[d.Detector] = []
    if args.flow:
        out.append(d.Flow())
    if args.background:
        out.append(d.Background(args.background_kind, contours=args.contours, min_area=args.min_area))
    if args.luma:
        out.append(d.LumaKey(args.luma_threshold, contours=args.contours, min_area=args.min_area))
    if args.lines:
        out.append(d.Lines())
    if args.circles:
        out.append(d.Circles())
    if args.tracks:
        out.append(d.Tracks())
    if args.face:
        out.append(d.Face())
    if args.pose:
        out.append(d.Pose(model=args.pose_model))
    if args.hands:
        out.append(d.Hands(max_hands=args.max_hands))
    return out


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="python -m fieldcv", description=__doc__.split("\n\n")[0])
    p.add_argument("--source", default="0", help="camera index, movie file, or a glob of images (default: camera 0)")
    p.add_argument("--shrink", type=float, default=0.5, help="scale frames by this before processing")
    p.add_argument("--skip-seconds", type=float, default=0.0, help="skip into a movie")
    p.add_argument("--max-frames", type=int, default=0, help="stop after this many frames (0: run to the end)")
    for name, text in [("flow", "dense optical flow"), ("background", "background subtraction"),
                       ("luma", "luma key"), ("lines", "line segments"), ("circles", "Hough circles"),
                       ("tracks", "Lucas-Kanade point tracks"), ("face", "mediapipe face mesh"),
                       ("pose", "mediapipe body pose"), ("hands", "mediapipe hands")]:
        p.add_argument(f"--{name}", action="store_true", help=text)
    p.add_argument("--background-kind", default="MOG2", help="MOG2, KNN, or a cv2.bgsegm kind (GMG, CNT, ...)")
    p.add_argument("--contours", action="store_true", help="with --background/--luma, also trace outlines")
    p.add_argument("--min-area", type=float, default=0.0, help="drop contours smaller than this fraction of the frame")
    p.add_argument("--luma-threshold", type=float, default=128)
    p.add_argument("--pose-model", choices=["heavy", "lite"], default="heavy")
    p.add_argument("--max-hands", type=int, default=2)
    p.add_argument("--save-frames", metavar="DIR", help="write an annotated preview of every frame here")
    p.add_argument("--show", action="store_true", help="show the annotated preview in a window")
    args = p.parse_args(argv)

    kwargs = {"shrink": args.shrink}
    if args.skip_seconds and not str(args.source).isdigit() and not any(c in args.source for c in "*?["):
        kwargs["skip_seconds"] = args.skip_seconds
    detectors = build(args)
    if not detectors:
        p.error("choose at least one detector (--tracks, --pose, ...)")
    pipeline = Pipeline(open_source(args.source, **kwargs), *detectors)
    if args.save_frames:
        os.makedirs(args.save_frames, exist_ok=True)

    try:
        for result in pipeline:
            frame = result["frame"]
            if args.save_frames or args.show:
                vis = frame.image.copy()
                for det in detectors:
                    det.draw(vis, result)
                if args.save_frames:
                    cv2.imwrite(os.path.join(args.save_frames, f"out_{frame.index:06d}.png"), vis)
                if args.show:
                    cv2.imshow("fieldcv", vis)
                    cv2.waitKey(1)
            row = jsonable(result)
            row.pop("timings", None)
            if frame.path:
                row["path"] = frame.path
            sys.stdout.write(json.dumps(row) + "\n")
            sys.stdout.flush()
            if args.max_frames and pipeline.frames >= args.max_frames:
                break
    except KeyboardInterrupt:
        pass
    finally:
        pipeline.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
